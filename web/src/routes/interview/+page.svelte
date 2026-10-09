<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { api, ApiError } from '#lib/api';
  import type { InterviewReport as Report, LiveRole, LiveStart, Overview, TranscriptEntry } from '#lib/types';
  import { connect, LiveError, LIVE_TEXT, type LiveConnection } from '#lib/live';
  import { createFakeLive, type FakeLive } from '#lib/fakeLive';
  import InterviewReport from '../../lib/components/InterviewReport.svelte';

  const MAX_JOB_AD = 6000;
  const SUBTITLES = 6;
  const ROLES: { value: LiveRole; label: string }[] = [
    { value: 'recruiter', label: 'Recruiter:in' },
    { value: 'teamlead', label: 'Teamlead' },
    { value: 'mix', label: 'Beide' }
  ];
  const DURATIONS = [10, 15, 20] as const;

  type Phase = 'setup' | 'connecting' | 'live' | 'reporting' | 'done';
  interface ReportPayload { sessionId: string; seconds: number; transcript: TranscriptEntry[] }

  let jobAd = $state('');
  let role = $state<LiveRole>('recruiter');
  let minutes = $state<number>(15);
  let minutesLeft = $state<number | null>(null);
  let phase = $state<Phase>('setup');
  let error = $state('');
  let session = $state<LiveStart | null>(null);
  let transcript = $state<TranscriptEntry[]>([]);
  let elapsed = $state(0);
  let typed = $state('');
  let report = $state<Report | null>(null);
  let payload: ReportPayload | null = null;
  let canRetry = $state(false);
  let reportBusy = $state(false);
  let audioEl = $state<HTMLAudioElement | null>(null);

  let audioBlocked = $state(false);

  let alive = true;
  let left = false;
  let conn: LiveConnection | null = null;
  let fake = $state.raw<FakeLive | null>(null);
  let startedAt = 0;
  let ticker: ReturnType<typeof setInterval> | null = null;
  let hardStop: ReturnType<typeof setTimeout> | null = null;

  const subtitles = $derived(transcript.slice(-SUBTITLES));
  const warn = $derived(session !== null && elapsed >= session.minutes * 60 - 60 && elapsed < session.minutes * 60);
  const tooLong = $derived(jobAd.length > MAX_JOB_AD);

  const mmss = (s: number) => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
  const elapsedSeconds = () => Math.max(0, Math.round((Date.now() - startedAt) / 1000));

  /** A failing load keeps the old line (or none); the start still answers with 429 when the minutes are used up. */
  async function loadMinutes() {
    try {
      const o = await api<Overview>('/api/overview');
      if (alive) minutesLeft = o.liveMinutesLeft;
    } catch { /* keep the old line */ }
  }

  onMount(() => {
    void loadMinutes();
    window.addEventListener('pagehide', leave);
    return () => window.removeEventListener('pagehide', leave);
  });

  onDestroy(() => {
    alive = false;
    leave();
  });

  /** Tab closed, reload or navigation away mid-call: always report with keepalive (the server settles the seconds); once only. */
  function leave() {
    if (left || phase !== 'live' || !session) return; // while connecting, start() cleans up once connect settles
    left = true;
    const s = session;
    const entries = currentTranscript();
    const seconds = elapsedSeconds();
    stopCall();
    conn = null;
    fake = null;
    audioBlocked = false;
    phase = 'done';
    // Connected (phase live): always report – the server settles the elapsed seconds (also on its 422 without an answer).
    // A cancel is only for before the connection; a late cancel would not be refunded anyway.
    void api('/api/live/report', { method: 'POST', json: { sessionId: s.sessionId, seconds, transcript: entries }, keepalive: true }).catch(() => {});
  }

  function playAudio() {
    void audioEl?.play().then(() => (audioBlocked = false), () => {});
  }

  function cancel(sessionId: string): Promise<unknown> {
    return api('/api/live/cancel', { method: 'POST', json: { sessionId }, keepalive: true }).catch(() => {});
  }

  function currentTranscript(): TranscriptEntry[] {
    return fake ? fake.transcript : (conn?.transcript ?? transcript);
  }

  async function start(e: SubmitEvent) {
    e.preventDefault();
    if (phase !== 'setup') return;
    error = '';
    const text = jobAd.trim();
    if (!text) { error = 'Bitte füg eine Stellenanzeige ein.'; return; }
    if (text.length > MAX_JOB_AD) { error = `Die Stellenanzeige ist zu lang (höchstens ${MAX_JOB_AD} Zeichen).`; return; }
    phase = 'connecting';
    let s: LiveStart;
    try {
      s = await api<LiveStart>('/api/live/session', { method: 'POST', json: { jobAd: text, role, minutes } });
    } catch (err) {
      if (alive) { error = (err as Error).message; phase = 'setup'; }
      return;
    }
    if (!alive) { void cancel(s.sessionId); return; }
    session = s;
    transcript = [];
    report = null;
    payload = null;
    if (s.fake || !s.clientSecret) {
      fake = createFakeLive((t) => (transcript = t));
      transcript = fake.transcript;
      begin(s);
      return;
    }
    try {
      const c = await connect({
        clientSecret: s.clientSecret,
        model: s.model,
        audio: audioEl,
        onAudioBlocked: () => (audioBlocked = true),
        onTranscript: (t) => (transcript = t),
        onFailed: () => void end()
      });
      if (!alive) { c.close(); void cancel(s.sessionId); return; }
      conn = c;
      begin(s);
    } catch (err) {
      void cancel(s.sessionId);
      if (!alive) return;
      error = err instanceof LiveError ? err.message : LIVE_TEXT.failed;
      session = null;
      phase = 'setup';
    }
  }

  function begin(s: LiveStart) {
    startedAt = Date.now();
    elapsed = 0;
    phase = 'live';
    ticker = setInterval(() => (elapsed = elapsedSeconds()), 1000);
    hardStop = setTimeout(() => void end(), s.maxSeconds * 1000);
  }

  function stopCall() {
    if (ticker) { clearInterval(ticker); ticker = null; }
    if (hardStop) { clearTimeout(hardStop); hardStop = null; }
    conn?.close();
  }

  function send(e: SubmitEvent) {
    e.preventDefault();
    if (!fake || !typed.trim()) return;
    fake.answer(typed);
    typed = '';
  }

  async function end() {
    if (phase !== 'live' || !session) return;
    const entries = currentTranscript();
    const seconds = elapsedSeconds();
    stopCall();
    conn = null;
    fake = null;
    audioBlocked = false;
    transcript = entries;
    payload = { sessionId: session.sessionId, seconds, transcript: entries };
    phase = 'reporting';
    await requestReport();
  }

  async function requestReport() {
    if (!payload || reportBusy) return;
    reportBusy = true; error = ''; canRetry = false;
    try {
      const r = await api<Report>('/api/live/report', { method: 'POST', json: payload });
      if (!alive) return;
      report = r;
    } catch (err) {
      if (!alive) return;
      error = (err as Error).message;
      canRetry = err instanceof ApiError && (err.status === 0 || err.status >= 500);
    } finally {
      reportBusy = false;
      if (alive) phase = 'done';
    }
  }

  function again() {
    left = false; audioBlocked = false;
    phase = 'setup'; session = null; report = null; payload = null; transcript = []; error = ''; canRetry = false;
    void loadMinutes();
  }
</script>

<h1 class="mb-4 text-2xl font-bold">Vorstellungsgespräch</h1>

<!-- the interviewer's voice; hidden, the page shows subtitles instead -->
<audio autoplay hidden bind:this={audioEl}></audio>

{#if phase === 'setup' || phase === 'connecting'}
  <form class="flex flex-col gap-4" onsubmit={start} novalidate>
    {#if minutesLeft !== null}<p class="text-sm text-slate-500">Heute noch {minutesLeft} Live-Minuten.</p>{/if}
    <div class="flex flex-col gap-1">
      <label for="job-ad" class="font-semibold">Stellenanzeige</label>
      <textarea id="job-ad" rows="10" class="card w-full" bind:value={jobAd} disabled={phase === 'connecting'}
        aria-describedby="job-ad-count" aria-invalid={tooLong}></textarea>
      <p id="job-ad-count" class="self-end text-xs {tooLong ? 'font-semibold text-red-700' : 'text-slate-500'}">{jobAd.length}/{MAX_JOB_AD}</p>
    </div>
    <fieldset class="flex flex-wrap gap-4" disabled={phase === 'connecting'}>
      <legend class="mb-1 font-semibold">Wer führt das Gespräch?</legend>
      {#each ROLES as r (r.value)}
        <label class="flex items-center gap-2"><input type="radio" name="role" value={r.value} bind:group={role} />{r.label}</label>
      {/each}
    </fieldset>
    <fieldset class="flex flex-wrap gap-4" disabled={phase === 'connecting'}>
      <legend class="mb-1 font-semibold">Dauer</legend>
      {#each DURATIONS as d (d)}
        <label class="flex items-center gap-2"><input type="radio" name="minutes" value={d} bind:group={minutes} />{d} Minuten</label>
      {/each}
    </fieldset>
    <button type="submit" class="btn-primary py-3 text-lg" disabled={phase === 'connecting'}>Gespräch starten</button>
    {#if phase === 'connecting'}<p class="text-sm text-slate-500" aria-live="polite">Verbindung wird aufgebaut …</p>{/if}
  </form>
{:else if phase === 'live' && session}
  <section class="flex flex-col gap-4" aria-label="Gespräch läuft">
    <div class="flex items-center justify-between">
      <p class="text-2xl font-bold tabular-nums">{mmss(elapsed)} / {mmss(session.minutes * 60)}</p>
      {#if session.fake}<span class="rounded bg-amber-100 px-2 py-0.5 text-sm text-amber-900">Testmodus</span>{/if}
    </div>
    {#if audioBlocked}<button type="button" class="btn-primary self-start" onclick={playAudio}>Tippe hier, um den Ton einzuschalten</button>{/if}
    {#if warn}<p role="status" class="rounded-lg bg-amber-100 p-3 font-semibold text-amber-900">Noch 1 Minute</p>{/if}
    <ol role="log" aria-label="Untertitel" aria-live="polite" class="card flex min-h-40 flex-col gap-2">
      {#each subtitles as t, i (transcript.length - subtitles.length + i)}
        <li>
          <span class="text-xs font-semibold uppercase text-slate-500">{t.role === 'omar' ? 'Du' : 'Interviewer:in'}</span>
          <p class={t.role === 'omar' ? 'text-brand-700' : ''}>{t.text}</p>
        </li>
      {/each}
    </ol>
    {#if fake}
      <form class="flex flex-col gap-2" onsubmit={send}>
        <label for="fake-answer" class="font-semibold">Antwort (Testmodus)</label>
        <textarea id="fake-answer" rows="3" class="card w-full" bind:value={typed}></textarea>
        <button type="submit" class="btn-primary self-start" disabled={!typed.trim()}>Antwort senden</button>
      </form>
    {/if}
    <button type="button" class="btn-primary bg-red-700 py-3" onclick={() => void end()}>Gespräch beenden</button>
  </section>
{:else if phase === 'reporting'}
  <p class="text-slate-500" aria-live="polite">Dein Bericht wird erstellt …</p>
{:else if phase === 'done'}
  {#if report}
    <InterviewReport {report} />
  {/if}
  {#if canRetry}
    <button type="button" class="btn-primary mt-4" disabled={reportBusy} onclick={() => void requestReport()}>Bericht erneut anfordern</button>
  {/if}
  {#if !reportBusy}
    <button type="button" class="mt-4 block underline" onclick={again}>Neues Gespräch</button>
  {/if}
{/if}

{#if error}<p role="alert" class="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-800 dark:bg-red-950 dark:text-red-200">{error}</p>{/if}
{#if reportBusy && phase === 'done'}<p class="mt-4 text-sm text-slate-500" aria-live="polite">Dein Bericht wird erstellt …</p>{/if}
