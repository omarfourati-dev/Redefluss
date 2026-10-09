import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import { deps } from '#lib/api';
import { liveDeps, type LiveDeps } from '#lib/live';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const problem = (status: number, detail: string) => json(status, { type: 'about:blank', title: 'x', status, detail });
const overview = {
  streakDays: 1, minutesToday: 0, turnsToday: 0, turnsLeft: 300, vocabDue: 0, azureSecondsLeft: 0,
  pronunciationEnabled: false, topMistakes: [], liveMinutesLeft: 25
};
const fakeStart = { sessionId: '7f1c2c8e-1111-4a4a-9999-000000000001', fake: true, clientSecret: null, expiresAt: null, model: 'gpt-realtime', maxSeconds: 720, minutes: 10 };
const realStart = { ...fakeStart, fake: false, clientSecret: 'ek_live', expiresAt: 1_900_000_000 };
const report = {
  overall: 'Gutes Gespräch mit klaren Beispielen.', summary: 'Du warst gut vorbereitet.',
  strengths: ['Klare Beispiele'], improvements: ['Motivation schärfen'],
  answers: [{ question: 'Erzählen Sie von sich.', answer: 'Ich bin Entwickler.', feedback: 'Gut.', better: 'Ich bin Full-Stack-Entwickler.' }],
  corrections: []
};

type Handler = (init?: RequestInit) => Response | Promise<Response>;
let fetchMock: ReturnType<typeof vi.fn>;
function mockApi(handlers: Record<string, Handler | Handler[]>) {
  const queues: Record<string, Handler[]> = {};
  for (const [k, v] of Object.entries(handlers)) queues[k] = Array.isArray(v) ? [...v] : [v];
  fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const q = queues[url];
    if (!q || q.length === 0) throw new Error(`unexpected ${url}`);
    const h = q.length > 1 ? q.shift()! : q[0];
    return h(init);
  });
  deps.fetch = fetchMock as unknown as typeof fetch;
}
const calls = (url: string) => fetchMock.mock.calls.filter(([u]) => u === url);
const body = (init: RequestInit | undefined) => JSON.parse(String(init?.body));

async function fillForm(text = 'Backend-Entwickler (m/w/d) Kotlin, Ktor, PostgreSQL') {
  await fireEvent.input(await screen.findByLabelText('Stellenanzeige'), { target: { value: text } });
}

const original = { ...liveDeps };

describe('Interview', () => {
  beforeEach(() => {
    HTMLMediaElement.prototype.pause = vi.fn();
  });
  afterEach(() => {
    Object.assign(liveDeps, original);
    vi.useRealTimers();
  });

  it('shows the form with the remaining live minutes and a character counter', async () => {
    mockApi({ '/api/overview': () => json(200, overview) });
    render(Page);
    expect(await screen.findByText('Heute noch 25 Live-Minuten.')).toBeInTheDocument();
    expect(screen.getByText('0/6000')).toBeInTheDocument();
    await fillForm('Hallo');
    expect(screen.getByText('5/6000')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Recruiter:in' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Teamlead' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Beide' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '15 Minuten' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '10 Minuten' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '20 Minuten' })).toBeInTheDocument();
  });

  it('validates an empty and a too long job ad without calling the API', async () => {
    mockApi({ '/api/overview': () => json(200, overview) });
    render(Page);
    await screen.findByText('Heute noch 25 Live-Minuten.');
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Bitte füg eine Stellenanzeige ein.');
    await fillForm('x'.repeat(6001));
    expect(screen.getByText('6001/6000')).toBeInTheDocument();
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Die Stellenanzeige ist zu lang (höchstens 6000 Zeichen).');
    expect(calls('/api/live/session')).toHaveLength(0);
  });

  it('429: shows the limit message', async () => {
    mockApi({
      '/api/overview': () => json(200, { ...overview, liveMinutesLeft: 0 }),
      '/api/live/session': () => problem(429, 'Für heute sind die Live-Minuten aufgebraucht (30 Minuten). Morgen geht\'s weiter.')
    });
    render(Page);
    await fillForm();
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Für heute sind die Live-Minuten aufgebraucht (30 Minuten).');
    expect(screen.getByRole('button', { name: 'Gespräch starten' })).toBeEnabled();
  });

  it('test mode: scripted questions as subtitles, typed answers, report after ending', async () => {
    mockApi({
      '/api/overview': () => json(200, overview),
      '/api/live/session': () => json(200, fakeStart),
      '/api/live/report': () => json(200, report)
    });
    render(Page);
    await fillForm();
    await fireEvent.click(screen.getByRole('radio', { name: 'Teamlead' }));
    await fireEvent.click(screen.getByRole('radio', { name: '10 Minuten' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    const subtitles = await screen.findByRole('log', { name: 'Untertitel' });
    expect(subtitles).toHaveAttribute('aria-live', 'polite');
    expect(subtitles).toHaveTextContent('Erzählen Sie mir bitte kurz etwas über sich.');
    expect(body(calls('/api/live/session')[0][1])).toEqual({ jobAd: 'Backend-Entwickler (m/w/d) Kotlin, Ktor, PostgreSQL', role: 'teamlead', minutes: 10 });
    expect(screen.getByText('00:00 / 10:00')).toBeInTheDocument();

    const box = screen.getByLabelText('Antwort (Testmodus)');
    await fireEvent.input(box, { target: { value: 'Ich bin Entwickler.' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Antwort senden' }));
    expect(subtitles).toHaveTextContent('Ich bin Entwickler.');
    expect(subtitles).toHaveTextContent('Warum interessieren Sie sich für diese Stelle?');
    expect(box).toHaveValue('');

    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch beenden' }));
    expect(await screen.findByText('Gutes Gespräch mit klaren Beispielen.')).toBeInTheDocument();
    const sent = body(calls('/api/live/report')[0][1]);
    expect(sent.sessionId).toBe(fakeStart.sessionId);
    expect(typeof sent.seconds).toBe('number');
    expect(sent.transcript).toEqual([
      { role: 'interviewer', text: 'Guten Tag, Herr Fourati. Erzählen Sie mir bitte kurz etwas über sich.' },
      { role: 'omar', text: 'Ich bin Entwickler.' },
      { role: 'interviewer', text: 'Danke. Warum interessieren Sie sich für diese Stelle?' }
    ]);
    expect(screen.getByRole('region', { name: 'Das lief gut' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Neues Gespräch' })).toBeInTheDocument();
  });

  it('warns one minute before the end and stops hard at duration + 2 minutes', async () => {
    mockApi({
      '/api/overview': () => json(200, overview),
      '/api/live/session': () => json(200, fakeStart),
      '/api/live/report': () => json(200, report)
    });
    render(Page);
    await fillForm();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    await vi.advanceTimersByTimeAsync(0);
    await fireEvent.input(screen.getByLabelText('Antwort (Testmodus)'), { target: { value: 'Ich bin Entwickler.' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Antwort senden' }));
    expect(screen.queryByText('Noch 1 Minute')).toBeNull();
    await vi.advanceTimersByTimeAsync(9 * 60_000);
    expect(screen.getByText('Noch 1 Minute')).toBeInTheDocument();
    expect(screen.getByText('09:00 / 10:00')).toBeInTheDocument();
    await vi.advanceTimersByTimeAsync(2 * 60_000);
    expect(calls('/api/live/report')).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(calls('/api/live/report')).toHaveLength(1);
    expect(body(calls('/api/live/report')[0][1]).seconds).toBe(720);
    vi.useRealTimers();
    expect(await screen.findByText('Gutes Gespräch mit klaren Beispielen.')).toBeInTheDocument();
  });

  it('a failed report can be requested again with the same transcript', async () => {
    mockApi({
      '/api/overview': () => json(200, overview),
      '/api/live/session': () => json(200, fakeStart),
      '/api/live/report': [() => problem(502, 'Der Sprachdienst antwortet gerade nicht.'), () => json(200, report)]
    });
    render(Page);
    await fillForm();
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    await fireEvent.input(await screen.findByLabelText('Antwort (Testmodus)'), { target: { value: 'Ich bin Entwickler.' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Antwort senden' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch beenden' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Der Sprachdienst antwortet gerade nicht.');
    await fireEvent.click(screen.getByRole('button', { name: 'Bericht erneut anfordern' }));
    expect(await screen.findByText('Gutes Gespräch mit klaren Beispielen.')).toBeInTheDocument();
    const [first, second] = calls('/api/live/report').map(([, init]) => body(init));
    expect(second).toEqual(first);
  });

  it('nothing from Omar: the 422 message, no retry', async () => {
    mockApi({
      '/api/overview': () => json(200, overview),
      '/api/live/session': () => json(200, fakeStart),
      '/api/live/report': () => problem(422, 'Im Gespräch war nichts von dir zu hören – deshalb gibt es keinen Bericht.')
    });
    render(Page);
    await fillForm();
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Gespräch beenden' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Im Gespräch war nichts von dir zu hören');
    expect(screen.queryByRole('button', { name: 'Bericht erneut anfordern' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Neues Gespräch' })).toBeInTheDocument();
  });

  function fakeRtc(opts: { sdpStatus?: number; micError?: string } = {}) {
    const track = { stop: vi.fn() };
    const pcs: { close: ReturnType<typeof vi.fn>; dc: { onopen?: () => void; onmessage?: (e: { data: string }) => void; send: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn> } | null }[] = [];
    const rtc: Partial<LiveDeps> = {
      getUserMedia: vi.fn(async () => {
        if (opts.micError) throw Object.assign(new Error('x'), { name: opts.micError });
        return { getTracks: () => [track] } as unknown as MediaStream;
      }),
      createPeerConnection: () => {
        const pc = {
          connectionState: 'new', dc: null as (typeof pcs)[number]['dc'],
          ontrack: null, onconnectionstatechange: null,
          addTrack: vi.fn(),
          createDataChannel() { this.dc = { send: vi.fn(), close: vi.fn() }; return this.dc; },
          createOffer: async () => ({ type: 'offer', sdp: 'OFFER' }),
          setLocalDescription: async () => {},
          async setRemoteDescription() { setTimeout(() => this.dc?.onopen?.(), 0); },
          close: vi.fn()
        };
        pcs.push(pc);
        return pc as unknown as RTCPeerConnection;
      },
      fetch: vi.fn(async () => new Response('ANSWER', { status: opts.sdpStatus ?? 201 })) as unknown as typeof fetch
    };
    Object.assign(liveDeps, rtc);
    return { track, pcs, rtc };
  }

  it('live: connects via WebRTC, shows the subtitles and reports the transcript', async () => {
    const { pcs, track, rtc } = fakeRtc();
    mockApi({
      '/api/overview': () => json(200, overview),
      '/api/live/session': () => json(200, realStart),
      '/api/live/report': () => json(200, report)
    });
    render(Page);
    await fillForm();
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    const subtitles = await screen.findByRole('log', { name: 'Untertitel' });
    expect((rtc.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0]).toBe('https://api.openai.com/v1/realtime/calls?model=gpt-realtime');
    expect(screen.queryByLabelText('Antwort (Testmodus)')).toBeNull();
    const dc = pcs[0].dc!;
    dc.onmessage?.({ data: JSON.stringify({ type: 'conversation.item.added', item: { id: 'a1' } }) });
    dc.onmessage?.({ data: JSON.stringify({ type: 'response.output_audio_transcript.done', item_id: 'a1', transcript: 'Erzählen Sie von sich.' }) });
    dc.onmessage?.({ data: JSON.stringify({ type: 'conversation.item.input_audio_transcription.completed', item_id: 'u1', transcript: 'Ich bin Entwickler.' }) });
    expect(await screen.findByText('Ich bin Entwickler.')).toBeInTheDocument();
    expect(subtitles).toHaveTextContent('Erzählen Sie von sich.');
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch beenden' }));
    expect(await screen.findByText('Gutes Gespräch mit klaren Beispielen.')).toBeInTheDocument();
    expect(track.stop).toHaveBeenCalled();
    expect(pcs[0].close).toHaveBeenCalled();
    expect(body(calls('/api/live/report')[0][1]).transcript).toEqual([
      { role: 'interviewer', text: 'Erzählen Sie von sich.' },
      { role: 'omar', text: 'Ich bin Entwickler.' }
    ]);
  });

  it('live: SDP rejected → cancel, the minutes are given back', async () => {
    fakeRtc({ sdpStatus: 500 });
    mockApi({
      '/api/overview': () => json(200, overview),
      '/api/live/session': () => json(200, realStart),
      '/api/live/cancel': () => new Response(null, { status: 204 })
    });
    render(Page);
    await fillForm();
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Die Verbindung zum Interview hat nicht geklappt – deine Minuten wurden nicht verbraucht.');
    expect(body(calls('/api/live/cancel')[0][1])).toEqual({ sessionId: realStart.sessionId });
    expect(screen.getByRole('button', { name: 'Gespräch starten' })).toBeEnabled();
    expect(calls('/api/live/report')).toHaveLength(0);
  });

  it('live: mic denied → cancel and the mic hint', async () => {
    fakeRtc({ micError: 'NotAllowedError' });
    mockApi({
      '/api/overview': () => json(200, overview),
      '/api/live/session': () => json(200, realStart),
      '/api/live/cancel': () => new Response(null, { status: 204 })
    });
    render(Page);
    await fillForm();
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Ich darf das Mikrofon nicht benutzen.');
    expect(calls('/api/live/cancel')).toHaveLength(1);
  });

  it('leaving mid-call with an answer sends the report with keepalive', async () => {
    mockApi({
      '/api/overview': () => json(200, overview),
      '/api/live/session': () => json(200, fakeStart),
      '/api/live/report': () => json(200, report)
    });
    const { unmount } = render(Page);
    await fillForm();
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    await fireEvent.input(await screen.findByLabelText('Antwort (Testmodus)'), { target: { value: 'Ich bin Entwickler.' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Antwort senden' }));
    unmount();
    await vi.waitFor(() => expect(calls('/api/live/report')).toHaveLength(1));
    const init = calls('/api/live/report')[0][1] as RequestInit;
    expect(init.keepalive).toBe(true);
    expect(body(init).transcript).toHaveLength(3);
  });

  it('leaving mid-call without an answer cancels with keepalive', async () => {
    const { pcs, track } = fakeRtc();
    mockApi({
      '/api/overview': () => json(200, overview),
      '/api/live/session': () => json(200, realStart),
      '/api/live/cancel': () => new Response(null, { status: 204 })
    });
    const { unmount } = render(Page);
    await fillForm();
    await fireEvent.click(screen.getByRole('button', { name: 'Gespräch starten' }));
    await screen.findByRole('log', { name: 'Untertitel' });
    unmount();
    await vi.waitFor(() => expect(calls('/api/live/cancel')).toHaveLength(1));
    expect((calls('/api/live/cancel')[0][1] as RequestInit).keepalive).toBe(true);
    expect(calls('/api/live/report')).toHaveLength(0);
    expect(track.stop).toHaveBeenCalled();
    expect(pcs[0].close).toHaveBeenCalled();
  });
});
