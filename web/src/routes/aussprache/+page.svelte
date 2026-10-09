<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { page } from '$app/state';
  import { api, apiBlob, ApiError } from '#lib/api';
  import type { AssessResponse, Exercise, Exercises, Quota } from '#lib/types';
  import { createPlayer, createTalk } from '#lib/talk.svelte';
  import { quotaLine } from '#lib/quota';
  import { wavDeps } from '#lib/wav';
  import TalkButton from '../../lib/components/TalkButton.svelte';
  import ScoreView from '../../lib/components/ScoreView.svelte';

  const DISABLED = 'Aussprache ist noch nicht eingerichtet.';
  /** The API rejects longer clips. */
  const MAX_RECORDING_MS = 30_000;

  let data = $state<Exercises | null>(null);
  let quota = $state<Quota | null>(null);
  let disabled = $state(false);
  let loading = $state(true);
  let loadFailed = $state(false);
  let selected = $state<Exercise | null>(null);
  let result = $state<AssessResponse | null>(null);
  let busy = $state(false);
  let speaking = $state(false);
  let error = $state('');
  let info = $state('');
  let alive = true;

  const groups = $derived(data ? [
    { title: 'Deine Fehler', items: data.groups.mistakes },
    { title: 'Wortschatz', items: data.groups.vocab },
    { title: 'Schwierige Laute', items: data.groups.sounds }
  ].filter((g) => g.items.length > 0) : []);

  const player = createPlayer();
  const talk = createTalk({
    onBlob: (blob) => void assess(blob),
    onError: (message) => (error = message),
    onInfo: (message) => (info = message),
    maxMs: MAX_RECORDING_MS
  });
  const locked = $derived(busy || talk.recording);

  function preselect(d: Exercises): Exercise | null {
    const all = [...d.groups.mistakes, ...d.groups.vocab, ...d.groups.sounds];
    const params = page.url.searchParams;
    const card = params.get('card');
    const wanted = card ? `card-${card}` : params.get('ex');
    return all.find((e) => e.id === wanted) ?? all[0] ?? null;
  }

  async function load() {
    loading = true; error = ''; loadFailed = false;
    try {
      const d = await api<Exercises>('/api/pronunciation/exercises');
      if (!alive) return;
      if (!d.enabled) { disabled = true; return; }
      data = d;
      quota = d.quota;
      selected = preselect(d);
    } catch (e) {
      if (!alive) return;
      if (e instanceof ApiError && e.status === 503) disabled = true;
      else { error = (e as Error).message; loadFailed = true; }
    } finally {
      if (alive) loading = false;
    }
  }

  onMount(load);

  onDestroy(() => {
    alive = false;
    talk.destroy();
    player.destroy();
  });

  function select(e: Exercise) {
    if (locked || talk.active) return;
    selected = e; result = null; error = ''; info = '';
  }

  async function speak(text: string, slow: boolean) {
    if (speaking) return;
    player.unlock(); // inside the click, before the await
    speaking = true; error = '';
    try {
      const audio = await apiBlob('/api/pronunciation/speak', { method: 'POST', json: { text, slow } });
      if (alive) player.play(audio);
    } catch (e) {
      if (alive) error = (e as Error).message;
    } finally {
      speaking = false;
    }
  }

  async function assess(blob: Blob) {
    const ex = selected;
    if (busy || !ex || result) return;
    busy = true; error = ''; info = '';
    try {
      const wav = await wavDeps.toWav16k(blob);
      const form = new FormData();
      form.append('text', ex.text);
      form.append('audio', wav, 'aufnahme.wav');
      const res = await api<AssessResponse>('/api/pronunciation/assess', { method: 'POST', body: form });
      if (!alive) return;
      quota = res.quota;
      if (selected?.id === ex.id) result = res;
    } catch (e) {
      if (alive) error = (e as Error).message;
    } finally {
      busy = false;
    }
  }

  function press() {
    if (busy || result || talk.active) return;
    error = ''; info = '';
    void talk.press();
  }

  function again() {
    if (busy) return;
    result = null; error = ''; info = '';
  }
</script>

<h1 class="mb-4 text-2xl font-bold">Aussprache</h1>

{#if loading}
  <p class="text-slate-500" aria-live="polite">Deine Übungssätze werden geladen …</p>
{/if}

{#if disabled}
  <p class="card text-center">{DISABLED}</p>
{:else if data}
  {#if quota}<p class="mb-4 text-sm text-slate-500">{quotaLine(quota)}</p>{/if}

  {#if selected}
    <section class="card flex flex-col gap-3" aria-label="Dein Satz">
      <p class="text-2xl font-semibold leading-snug">{selected.text}</p>
      {#if selected.hint}<p class="text-sm text-slate-500">{selected.hint}</p>{/if}
      <div class="flex flex-wrap gap-3">
        <button type="button" class="btn-primary" disabled={speaking || talk.recording} onclick={() => void speak(selected!.text, false)}>▶ Vorsprechen</button>
        <button type="button" class="btn-primary" disabled={speaking || talk.recording} onclick={() => void speak(selected!.text, true)}>🐢 Langsam</button>
      </div>
    </section>

    {#if result}
      <div class="mt-4">
        <ScoreView text={selected.text} assessment={result.assessment} onSpeakWord={(w) => void speak(w, true)} onAgain={again} />
      </div>
    {:else}
      <div class="mt-4 flex justify-center">
        <TalkButton disabled={busy} recording={talk.recording} seconds={talk.seconds} onPress={press} onRelease={talk.release} />
      </div>
    {/if}
  {/if}

  {#each groups as g (g.title)}
    <section class="mt-8">
      <h2 class="mb-3 text-lg font-semibold">{g.title}</h2>
      <ul class="flex flex-col gap-2">
        {#each g.items as e (e.id)}
          <li>
            <button type="button" class="card w-full text-left hover:border-brand-700 disabled:opacity-50
                {selected?.id === e.id ? 'border-brand-700 font-semibold' : ''}"
              aria-pressed={selected?.id === e.id} disabled={locked} onclick={() => select(e)}>{e.text}</button>
          </li>
        {/each}
      </ul>
    </section>
  {/each}
{/if}

{#if error}<p role="alert" class="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-800 dark:bg-red-950 dark:text-red-200">{error}</p>{/if}
{#if error && loadFailed && !data}
  <button type="button" class="btn-primary mt-3" disabled={loading} onclick={load}>Nochmal versuchen</button>
{/if}
{#if info}<p role="status" class="mt-4 rounded-lg bg-slate-100 p-3 text-sm text-slate-800 dark:bg-slate-800 dark:text-slate-200">{info}</p>{/if}
{#if busy}<p class="mt-4 text-sm text-slate-500" aria-live="polite">Einen Moment …</p>{/if}
