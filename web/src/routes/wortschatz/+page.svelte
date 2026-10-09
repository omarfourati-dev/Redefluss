<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { api } from '#lib/api';
  import type { Card, ReviewResponse, Today } from '#lib/types';
  import { createPlayer, createTalk } from '#lib/talk.svelte';
  import TalkButton from '../../lib/components/TalkButton.svelte';
  import WordCard from '../../lib/components/WordCard.svelte';
  import Corrections from '../../lib/components/Corrections.svelte';

  const DONE = "Alles wiederholt – morgen geht's weiter. 🎉";

  let today = $state<Today | null>(null);
  let loading = $state(true);
  let reviewing = $state(false);
  let queue = $state<Card[]>([]);
  let index = $state(0);
  let result = $state<ReviewResponse | null>(null);
  let dueCount = $state(0);
  let busy = $state(false);
  let error = $state('');
  let info = $state('');
  let typing = $state(false);
  let text = $state('');
  let alive = true;

  const current = $derived<Card | null>(queue[index] ?? null);
  const player = createPlayer();
  const talk = createTalk({
    onBlob: (blob) => void send({ audio: blob }),
    onError: (message, kind) => {
      error = message;
      if (kind === 'denied' || kind === 'unavailable') typing = true;
    },
    onInfo: (message) => (info = message)
  });
  const locked = $derived(busy || talk.recording);

  onMount(async () => {
    try {
      // the first call of the day generates the words (may take ~10 s)
      const t = await api<Today>('/api/vocab/today');
      if (!alive) return;
      today = t;
      dueCount = t.dueCount;
    } catch (e) {
      if (alive) error = (e as Error).message;
    } finally {
      loading = false;
    }
  });

  onDestroy(() => {
    alive = false;
    talk.destroy();
    player.destroy();
  });

  async function loadDue() {
    if (busy) return;
    busy = true; error = ''; info = '';
    try {
      const cards = await api<Card[]>('/api/vocab/due');
      if (!alive) return;
      queue = cards; index = 0; result = null; text = '';
      reviewing = true;
    } catch (e) { if (alive) error = (e as Error).message; } finally { busy = false; }
  }

  function backToWords() {
    talk.destroy();
    reviewing = false; result = null; error = ''; info = '';
  }

  async function send(input: { text?: string; audio?: Blob; skip?: boolean }) {
    const card = current;
    if (busy || !card || result) return;
    busy = true; error = ''; info = '';
    const form = new FormData();
    if (input.skip) form.append('skip', 'true');
    if (input.text !== undefined) form.append('text', input.text);
    if (input.audio) form.append('audio', input.audio, 'aufnahme');
    try {
      const res = await api<ReviewResponse>(`/api/vocab/cards/${card.id}/review`, { method: 'POST', body: form });
      if (!alive || current?.id !== card.id) return;
      result = res;
      dueCount = res.dueCount;
    } catch (e) { if (alive) error = (e as Error).message; } finally { busy = false; }
  }

  function next() {
    if (busy) return;
    result = null; text = ''; error = ''; info = '';
    index += 1;
    // more than one batch was due: fetch the rest
    if (index >= queue.length && dueCount > 0) void loadDue();
  }

  function press() {
    if (busy || result || talk.active) return;
    error = ''; info = '';
    void talk.press();
  }

  function submitText(e: SubmitEvent) {
    e.preventDefault();
    const t = text.trim();
    if (!t || locked) return;
    void send({ text: t });
  }

  function stars(grade: number) {
    const g = Math.max(0, Math.min(5, Math.round(grade)));
    return '★'.repeat(g) + '☆'.repeat(5 - g);
  }

  function nextReview(days: number) {
    return days === 1 ? 'Nächste Wiederholung morgen' : `Nächste Wiederholung in ${days} Tagen`;
  }
</script>

<h1 class="mb-4 text-2xl font-bold">Wortschatz</h1>

{#if loading}
  <p class="text-slate-500" aria-live="polite">Deine Wörter für heute werden vorbereitet …</p>
{/if}

{#if today && !reviewing}
  <section>
    <h2 class="mb-3 text-lg font-semibold">Heute neu</h2>
    {#if today.newCards.length === 0}
      <p class="text-slate-500">Heute gibt es keine neuen Wörter.</p>
    {:else}
      <div class="flex flex-col gap-3">
        {#each today.newCards as c (c.id)}<WordCard card={c} {player} />{/each}
      </div>
    {/if}
  </section>
  <div class="mt-6">
    {#if dueCount > 0}
      <button type="button" class="btn-primary w-full py-3 text-lg" disabled={busy} onclick={loadDue}>Wiederholen ({dueCount})</button>
    {:else}
      <p class="card text-center font-medium">{DONE}</p>
    {/if}
  </div>
{/if}

{#if reviewing}
  <p class="mb-4 text-sm text-slate-500">
    <button type="button" class="underline disabled:opacity-50" disabled={locked} onclick={backToWords}>Zurück zu den neuen Wörtern</button>
  </p>
  {#if current}
    <WordCard card={current} {player} review />
    {#if result}
      <section class="card mt-4 flex flex-col gap-2" aria-label="Ergebnis">
        <p class="text-2xl text-amber-500"><span aria-hidden="true">{stars(result.grade)}</span><span class="sr-only">{result.grade} von 5 Sternen</span></p>
        {#if result.transcript}<p class="text-sm text-slate-500">Du: {result.transcript}</p>{/if}
        <p>{result.feedback}</p>
        <Corrections corrections={result.corrections} />
        {#if result.better}
          <p class="text-sm"><span aria-hidden="true">💬</span> Besser: <em>{result.better}</em></p>
        {/if}
        <p class="text-sm text-slate-500">{nextReview(result.intervalDays)}</p>
        <button type="button" class="btn-primary mt-2" disabled={busy} onclick={next}>Weiter</button>
      </section>
    {:else}
      <p class="mt-4 text-center">Sprich einen Satz mit diesem Wort.</p>
      <div class="mt-4 flex flex-col items-center gap-3">
        <TalkButton disabled={busy} recording={talk.recording} seconds={talk.seconds} onPress={press} onRelease={talk.release} />
        <div class="flex gap-4 text-sm">
          <button type="button" class="underline disabled:opacity-50" disabled={locked} onclick={() => (typing = !typing)}>Lieber tippen</button>
          <button type="button" class="underline disabled:opacity-50" disabled={locked} onclick={() => void send({ skip: true })}>Weiß ich nicht</button>
        </div>
        {#if typing}
          <form class="flex w-full gap-2" onsubmit={submitText}>
            <input class="input" aria-label="Dein Satz" bind:value={text} maxlength="1000" disabled={talk.recording} />
            <button class="btn-primary" type="submit" disabled={locked}>Senden</button>
          </form>
        {/if}
      </div>
    {/if}
  {:else if !busy && !error}
    <p class="card text-center font-medium">{DONE}</p>
  {/if}
{/if}

{#if error}<p role="alert" class="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-800 dark:bg-red-950 dark:text-red-200">{error}</p>{/if}
{#if info}<p role="status" class="mt-4 rounded-lg bg-slate-100 p-3 text-sm text-slate-800 dark:bg-slate-800 dark:text-slate-200">{info}</p>{/if}
{#if busy}<p class="mt-4 text-sm text-slate-500" aria-live="polite">Einen Moment …</p>{/if}
