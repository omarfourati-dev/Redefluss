import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import { deps } from '#lib/api';
import { Recorder, recorderFactory, type RecorderEnv } from '#lib/recorder';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const card = (id: number, article: string, word: string, meaning: string, example = `Beispiel mit ${word}.`) =>
  ({ id, word, article, plural: '', meaning, example, theme: 'it', source: 'daily', dueOn: '2026-10-09', intervalDays: 0, reps: 0 });
const besprechung = card(1, 'die', 'Besprechung', 'ein Treffen, um etwas zu klären', 'Die Besprechung beginnt um neun.');
const termin = card(2, 'der', 'Termin', 'eine feste Verabredung');
const today = { newCards: [besprechung, termin], dueCount: 2, reviewsLeft: 60 };
const review = { transcript: 'Die Besprechung war lang.', grade: 4, usedCorrectly: true, feedback: 'Gut benutzt!',
  corrections: [{ wrong: 'war lang', right: 'hat lange gedauert', rule: 'Natürlicher Ausdruck.', category: 'wortwahl' }],
  better: 'Die Besprechung hat lange gedauert.', nextDue: '2026-10-10', intervalDays: 1, dueCount: 1 };
const skipped = { transcript: '', grade: 1, usedCorrectly: false, feedback: 'Kein Problem – die Karte kommt morgen wieder.',
  corrections: [], better: '', nextDue: '2026-10-10', intervalDays: 1, dueCount: 0 };
const DONE = "Alles wiederholt – morgen geht's weiter. 🎉";

type Handler = (url: string, init?: RequestInit) => Response | Promise<Response>;
function mockApi(handlers: Record<string, Handler>) {
  deps.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const key = Object.keys(handlers).find((k) => url === k || (k.endsWith('*') && url.startsWith(k.slice(0, -1))));
    if (!key) throw new Error(`unexpected ${url}`);
    return handlers[key](url, init);
  }) as any;
}
const reviewCalls = () => (deps.fetch as any).mock.calls.filter((c: any[]) => /\/review$/.test(c[0]));

async function startReview() {
  render(Page);
  await fireEvent.click(await screen.findByRole('button', { name: 'Wiederholen (2)' }));
  await screen.findByText('Sprich einen Satz mit diesem Wort.');
}

async function typeAndSend(sentence: string) {
  await fireEvent.click(screen.getByRole('button', { name: 'Lieber tippen' }));
  await fireEvent.input(screen.getByLabelText('Dein Satz'), { target: { value: sentence } });
  await fireEvent.click(screen.getByRole('button', { name: 'Senden' }));
}

class FakeMediaRecorder {
  static isTypeSupported = () => true;
  mimeType = 'audio/webm';
  ondataavailable: ((e: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  onerror: (() => void) | null = null;
  start() {}
  stop() { this.ondataavailable?.({ data: new Blob(['abc'], { type: 'audio/webm' }) }); this.onstop?.(); }
}

function fakeEnv(over: Partial<RecorderEnv> = {}): RecorderEnv {
  return {
    getUserMedia: async () => ({ getTracks: () => [{ stop: vi.fn() }] }) as unknown as MediaStream,
    MediaRecorder: FakeMediaRecorder as unknown as typeof MediaRecorder,
    now: () => 0, setTimeout: globalThis.setTimeout.bind(globalThis), clearTimeout: globalThis.clearTimeout.bind(globalThis),
    ...over,
  };
}

describe('Wortschatz', () => {
  beforeEach(() => {
    HTMLMediaElement.prototype.play = vi.fn(async () => {});
    HTMLMediaElement.prototype.pause = vi.fn();
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
  });

  it("shows a preparing hint, then today's new words", async () => {
    let answer: (r: Response) => void = () => {};
    deps.fetch = vi.fn(() => new Promise<Response>((r) => { answer = r; })) as any;
    render(Page);
    expect(screen.getByText('Deine Wörter für heute werden vorbereitet …')).toBeInTheDocument();
    answer(json(200, today));
    expect(await screen.findByRole('heading', { name: 'Heute neu' })).toBeInTheDocument();
    expect(screen.getByText('Besprechung')).toBeInTheDocument();
    expect(screen.getByText('Termin')).toBeInTheDocument();
    expect(screen.getByText('Die Besprechung beginnt um neun.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Wiederholen (2)' })).toBeInTheDocument();
    expect(screen.queryByText('Deine Wörter für heute werden vorbereitet …')).toBeNull();
    expect((deps.fetch as any).mock.calls[0][0]).toBe('/api/vocab/today');
  });

  it('shows a load error', async () => {
    mockApi({ '/api/vocab/today': () => json(504, { status: 504, detail: 'Der Sprachdienst antwortet gerade nicht. Bitte versuch es noch einmal.' }) });
    render(Page);
    expect(await screen.findByRole('alert')).toHaveTextContent('Der Sprachdienst antwortet gerade nicht');
  });

  it('retries the load after a failure', async () => {
    let calls = 0;
    mockApi({ '/api/vocab/today': () => (++calls === 1 ? json(502, { status: 502, detail: 'Der Sprachdienst antwortet gerade nicht. Bitte versuch es noch einmal.' }) : json(200, today)) });
    render(Page);
    expect(await screen.findByRole('alert')).toHaveTextContent('Sprachdienst');
    await fireEvent.click(screen.getByRole('button', { name: 'Nochmal versuchen' }));
    expect(await screen.findByText('Termin')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Nochmal versuchen' })).toBeNull();
  });

  it('nothing due: says everything is reviewed', async () => {
    mockApi({ '/api/vocab/today': () => json(200, { ...today, dueCount: 0 }) });
    render(Page);
    expect(await screen.findByText(DONE)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Wiederholen/ })).toBeNull();
  });

  it('reviews a typed sentence: stars, feedback, corrections, better sentence, "morgen"', async () => {
    mockApi({ '/api/vocab/today': () => json(200, today), '/api/vocab/due': () => json(200, [besprechung, termin]),
      '/api/vocab/cards/*': () => json(200, review) });
    await startReview();
    expect(screen.getByText('Besprechung')).toBeInTheDocument();
    expect(screen.getByText('ein Treffen, um etwas zu klären')).toBeInTheDocument();
    expect(screen.queryByText('Die Besprechung beginnt um neun.')).toBeNull();
    expect(screen.getByRole('button', { name: /Halten und sprechen/ })).toBeInTheDocument();
    await typeAndSend('Die Besprechung war lang.');
    expect(await screen.findByText('★★★★☆')).toBeInTheDocument();
    expect(screen.getByText('Gut benutzt!')).toBeInTheDocument();
    expect(screen.getByText('hat lange gedauert')).toBeInTheDocument();
    expect(screen.getByText('Die Besprechung hat lange gedauert.')).toBeInTheDocument();
    expect(screen.getByText('Nächste Wiederholung morgen')).toBeInTheDocument();
    const [url, init] = reviewCalls()[0];
    expect(url).toBe('/api/vocab/cards/1/review');
    expect(init.method).toBe('POST');
    expect((init.body as FormData).get('text')).toBe('Die Besprechung war lang.');
    await fireEvent.click(screen.getByRole('button', { name: 'Weiter' }));
    expect(await screen.findByText('Termin')).toBeInTheDocument();
    expect(screen.queryByText('Gut benutzt!')).toBeNull();
  });

  it('says "in N Tagen" for longer intervals', async () => {
    mockApi({ '/api/vocab/today': () => json(200, today), '/api/vocab/due': () => json(200, [besprechung]),
      '/api/vocab/cards/*': () => json(200, { ...review, grade: 5, intervalDays: 6, corrections: [] }) });
    await startReview();
    await typeAndSend('Satz');
    expect(await screen.findByText('★★★★★')).toBeInTheDocument();
    expect(screen.getByText('Nächste Wiederholung in 6 Tagen')).toBeInTheDocument();
  });

  it('"Weiß ich nicht" skips to the next card and ends with the done text', async () => {
    mockApi({ '/api/vocab/today': () => json(200, today), '/api/vocab/due': () => json(200, [besprechung, termin]),
      '/api/vocab/cards/*': () => json(200, skipped) });
    await startReview();
    await fireEvent.click(screen.getByRole('button', { name: 'Weiß ich nicht' }));
    expect(await screen.findByText('Kein Problem – die Karte kommt morgen wieder.')).toBeInTheDocument();
    expect((reviewCalls()[0][1].body as FormData).get('skip')).toBe('true');
    await fireEvent.click(screen.getByRole('button', { name: 'Weiter' }));
    expect(await screen.findByText('Termin')).toBeInTheDocument();
    await fireEvent.click(screen.getByRole('button', { name: 'Weiß ich nicht' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Weiter' }));
    expect(await screen.findByText(DONE)).toBeInTheDocument();
    expect(reviewCalls().map((c: any[]) => c[0])).toEqual(['/api/vocab/cards/1/review', '/api/vocab/cards/2/review']);
  });

  it('busy disables all controls and allows only one request', async () => {
    let answer: (r: Response) => void = () => {};
    mockApi({ '/api/vocab/today': () => json(200, today), '/api/vocab/due': () => json(200, [besprechung, termin]),
      '/api/vocab/cards/*': () => new Promise<Response>((r) => { answer = r; }) });
    await startReview();
    await typeAndSend('Eins');
    expect(screen.getByRole('button', { name: 'Senden' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Weiß ich nicht' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Halten und sprechen/ })).toBeDisabled();
    expect(screen.getByText('Einen Moment …')).toBeInTheDocument();
    await fireEvent.submit(screen.getByLabelText('Dein Satz').closest('form')!);
    await fireEvent.click(screen.getByRole('button', { name: 'Weiß ich nicht' }));
    expect(reviewCalls()).toHaveLength(1);
    answer(json(200, review));
    expect(await screen.findByText('Gut benutzt!')).toBeInTheDocument();
  });

  it('shows review errors as alert and keeps the card', async () => {
    mockApi({ '/api/vocab/today': () => json(200, today), '/api/vocab/due': () => json(200, [besprechung]),
      '/api/vocab/cards/*': () => json(429, { status: 429, detail: "Tageslimit für Wiederholungen erreicht. Morgen geht's weiter." }) });
    await startReview();
    await typeAndSend('Eins');
    expect(await screen.findByRole('alert')).toHaveTextContent('Tageslimit für Wiederholungen erreicht');
    expect(screen.getByText('Besprechung')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Senden' })).not.toBeDisabled();
  });

  it('sends a spoken sentence as audio', async () => {
    mockApi({ '/api/vocab/today': () => json(200, today), '/api/vocab/due': () => json(200, [besprechung]),
      '/api/vocab/cards/*': () => json(200, review) });
    let t = 0;
    recorderFactory.create = (onAuto?: (b: Blob | null) => void) => new Recorder(fakeEnv({ now: () => t }), onAuto);
    await startReview();
    const btn = screen.getByRole('button', { name: /Halten und sprechen/ });
    await fireEvent.pointerDown(btn);
    await screen.findByText(/Ich höre zu/);
    t = 2000;
    await fireEvent.pointerUp(btn);
    expect(await screen.findByText('Gut benutzt!')).toBeInTheDocument();
    expect((reviewCalls()[0][1].body as FormData).get('audio')).toBeInstanceOf(Blob);
  });

  it('a denied microphone opens the text input', async () => {
    mockApi({ '/api/vocab/today': () => json(200, today), '/api/vocab/due': () => json(200, [besprechung]) });
    recorderFactory.create = (onAuto?: (b: Blob | null) => void) =>
      new Recorder(fakeEnv({ getUserMedia: async () => { throw new DOMException('no', 'NotAllowedError'); } }), onAuto);
    await startReview();
    await fireEvent.pointerDown(screen.getByRole('button', { name: /Halten und sprechen/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Mikrofon nicht benutzen');
    expect(screen.getByLabelText('Dein Satz')).toBeInTheDocument();
  });
});
