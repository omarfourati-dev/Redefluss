<script lang="ts">
  import { api } from '#lib/api';
  import type { TurnResponse } from '#lib/types';
  import { MicDeniedError, Recorder } from '#lib/recorder';
  import TalkButton from '../../lib/components/TalkButton.svelte';
  import TurnView from '../../lib/components/TurnView.svelte';

  const TOPICS = ['Arbeit', 'Alltag', 'Smalltalk', 'Nachrichten', 'Freies Thema'];
  const SPEAK_KEY = 'redefluss.speak';

  let sessionId = $state<string | null>(null);
  let topic = $state('');
  let ownTopic = $state('');
  let turns = $state<TurnResponse[]>([]);
  let busy = $state(false);
  let error = $state('');
  let typing = $state(false);
  let text = $state('');
  let recording = $state(false);
  let seconds = $state(0);
  let turnsLeft = $state<number | null>(null);
  let speak = $state(readSpeak());
  let ticker: ReturnType<typeof setInterval> | null = null;
  let recorder: Recorder | null = null;

  function readSpeak() { try { return localStorage.getItem(SPEAK_KEY) !== 'false'; } catch { return true; } }
  function setSpeak(v: boolean) { speak = v; try { localStorage.setItem(SPEAK_KEY, String(v)); } catch { /* ignore */ } }

  async function start(t: string) {
    error = '';
    try {
      const res = await api<{ id: string }>('/api/sessions', { method: 'POST', json: { topic: t } });
      sessionId = res.id; topic = t; turns = [];
    } catch (e) { error = (e as Error).message; }
  }

  /** History for the coach: the last 10 rounds as user/assistant pairs. */
  function history() {
    return turns.slice(-10).flatMap((t) => [{ role: 'user', text: t.transcript }, { role: 'assistant', text: t.reply }]);
  }

  async function send(input: { text?: string; audio?: Blob }) {
    if (busy || !sessionId) return;
    busy = true; error = '';
    const form = new FormData();
    form.append('sessionId', sessionId);
    form.append('speak', String(speak));
    form.append('history', JSON.stringify(history()));
    if (input.text !== undefined) form.append('text', input.text);
    if (input.audio) form.append('audio', input.audio, 'aufnahme');
    try {
      const res = await api<TurnResponse>('/api/turns', { method: 'POST', body: form });
      turns = [...turns, res];
      turnsLeft = res.turnsLeft;
      if (speak) play(res);
    } catch (e) { error = (e as Error).message; } finally { busy = false; }
  }

  function play(t: TurnResponse) {
    if (!t.replyAudio || !t.replyAudioType) return;
    const bytes = Uint8Array.from(atob(t.replyAudio), (c) => c.charCodeAt(0));
    const url = URL.createObjectURL(new Blob([bytes], { type: t.replyAudioType }));
    const audio = new Audio(url);
    audio.onended = () => URL.revokeObjectURL(url);
    void audio.play().catch(() => URL.revokeObjectURL(url));
  }

  async function press() {
    if (busy || recording) return;
    error = '';
    recorder = new Recorder(undefined, (blob) => finish(blob));
    try {
      await recorder.start();
      recording = true; seconds = 0;
      ticker = setInterval(() => (seconds += 1), 1000);
    } catch (e) {
      recorder = null;
      if (e instanceof MicDeniedError) { error = 'Ich darf das Mikrofon nicht benutzen. Erlaube es in den Browser-Einstellungen – oder tippe deinen Satz.'; typing = true; }
      else error = 'Aufnahme nicht möglich.';
    }
  }

  async function release() {
    if (!recorder || !recording) return;
    finish(await recorder.stop());
  }

  function finish(blob: Blob | null) {
    recording = false;
    if (ticker) clearInterval(ticker);
    recorder = null;
    if (blob) void send({ audio: blob });
    else error = 'Zu kurz – halte die Taste gedrückt, während du sprichst.';
  }

  function submitText(e: SubmitEvent) {
    e.preventDefault();
    const t = text.trim();
    if (!t || busy) return;
    void send({ text: t }).then(() => { if (!error) text = ''; });
  }
</script>

<h1 class="mb-4 text-2xl font-bold">Gespräch</h1>

{#if !sessionId}
  <section class="card flex flex-col gap-4">
    <p>Worüber möchtest du sprechen?</p>
    <div class="flex flex-wrap gap-2">
      {#each TOPICS as t (t)}<button type="button" class="btn-secondary" onclick={() => start(t)}>{t}</button>{/each}
    </div>
    <form class="flex gap-2" onsubmit={(e) => { e.preventDefault(); if (ownTopic.trim()) void start(ownTopic.trim()); }}>
      <input class="input" placeholder="Eigenes Thema, z. B. Vorstellungsgespräch bei Avision" bind:value={ownTopic} maxlength="80" aria-label="Eigenes Thema" />
      <button class="btn-primary" type="submit">Los</button>
    </form>
  </section>
  {#if error}<p role="alert" class="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-800 dark:bg-red-950 dark:text-red-200">{error}</p>{/if}
{:else}
  <p class="mb-4 text-sm text-slate-500">Thema: <strong>{topic}</strong>
    {#if turnsLeft !== null} · <span>Noch {turnsLeft} Runden heute</span>{/if}
    · <button type="button" class="underline" onclick={() => (sessionId = null)}>Thema wechseln</button></p>

  <div class="flex flex-col gap-6">
    {#each turns as t, i (i)}<TurnView turn={t} onReplay={() => play(t)} />{/each}
    {#if turns.length === 0}<p class="text-slate-500">Sag einfach den ersten Satz – zum Beispiel, was du heute gemacht hast.</p>{/if}
  </div>

  {#if error}<p role="alert" class="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-800 dark:bg-red-950 dark:text-red-200">{error}</p>{/if}
  {#if busy}<p class="mt-4 text-sm text-slate-500" aria-live="polite">Einen Moment …</p>{/if}

  <div class="sticky bottom-0 mt-6 flex flex-col items-center gap-3 bg-slate-50/90 py-4 backdrop-blur dark:bg-slate-950/90">
    <TalkButton disabled={busy} {recording} {seconds} onPress={press} onRelease={release} />
    <div class="flex gap-4 text-sm">
      <button type="button" class="underline" onclick={() => (typing = !typing)}>Lieber tippen</button>
      <label class="flex items-center gap-1"><input type="checkbox" checked={speak} onchange={(e) => setSpeak((e.currentTarget as HTMLInputElement).checked)} /> Antwort vorlesen</label>
    </div>
    {#if typing}
      <form class="flex w-full gap-2" onsubmit={submitText}>
        <input class="input" aria-label="Dein Satz" bind:value={text} maxlength="1000" />
        <button class="btn-primary" type="submit" disabled={busy}>Senden</button>
      </form>
    {/if}
  </div>
{/if}
