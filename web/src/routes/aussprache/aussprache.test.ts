import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const pageState = vi.hoisted(() => ({ url: new URL('http://localhost/aussprache') }));
vi.mock('$app/state', () => ({ page: pageState }));

import Page from './+page.svelte';
import { deps } from '#lib/api';
import { Recorder, recorderFactory, type RecorderEnv } from '#lib/recorder';
import { wavDeps } from '#lib/wav';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const quota = { enabled: true, secondsLeft: 3 * 3600 + 12 * 60, secondsPerMonth: 16200, todayLeft: 87 };
const exercises = {
  enabled: true, quota,
  groups: {
    mistakes: [{ id: 'mistake-3', source: 'mistake', text: 'Ich habe die ganze Zeit gewartet.', hint: '„Zeit“ ist feminin.' }],
    vocab: [{ id: 'card-7', source: 'vocab', text: 'Die Besprechung beginnt um neun.', hint: 'die Besprechung' }],
    sounds: [{ id: 'sound-1', source: 'sound', text: 'Über die Brücke fahren fünf grüne Autos.', hint: 'ü wie in „über“' }]
  }
};
const assessed = {
  assessment: { recognized: 'Die Besprechung beginnt um neun.', accuracy: 90, fluency: 85, completeness: 100, pronunciation: 91,
    words: [
      { word: 'Die', score: 95, errorType: 'None', phonemes: [] },
      { word: 'Besprechung', score: 50, errorType: 'Mispronunciation', phonemes: [{ phoneme: 'ç', score: 30 }] },
      { word: 'beginnt', score: 90, errorType: 'None', phonemes: [] },
      { word: 'um', score: 90, errorType: 'None', phonemes: [] },
      { word: 'neun', score: 90, errorType: 'None', phonemes: [] }
    ] },
  quota: { ...quota, secondsLeft: 3 * 3600 + 11 * 60, todayLeft: 86 },
  weakWords: ['Besprechung']
};
// Bytes, not a jsdom Blob: Node 22's Response needs Blob.stream(), which jsdom's Blob lacks (Node 24 tolerates it).
const mp3 = () => new Response(new Uint8Array([0x49, 0x44, 0x33]), { status: 200, headers: { 'Content-Type': 'audio/mpeg' } });
const DISABLED = 'Aussprache ist noch nicht eingerichtet.';

type Handler = (url: string, init?: RequestInit) => Response | Promise<Response>;
function mockApi(handlers: Record<string, Handler>) {
  deps.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const h = handlers[url] ?? handlers[url.split('?')[0]];
    if (!h) throw new Error(`unexpected ${url}`);
    return h(url, init);
  }) as any;
}
const calls = (url: string) => (deps.fetch as any).mock.calls.filter((c: any[]) => c[0] === url);

class FakeMediaRecorder {
  static isTypeSupported = () => true;
  mimeType = 'audio/webm';
  ondataavailable: ((e: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  onerror: (() => void) | null = null;
  start() {}
  stop() { this.ondataavailable?.({ data: new Blob(['abc'], { type: 'audio/webm' }) }); this.onstop?.(); }
}
let clock = 0;
const fakeEnv = (): RecorderEnv => ({
  getUserMedia: async () => ({ getTracks: () => [{ stop: vi.fn() }] }) as unknown as MediaStream,
  MediaRecorder: FakeMediaRecorder as unknown as typeof MediaRecorder,
  now: () => clock, setTimeout: globalThis.setTimeout.bind(globalThis), clearTimeout: globalThis.clearTimeout.bind(globalThis)
});

async function record() {
  const btn = screen.getByRole('button', { name: /Halten und sprechen/ });
  clock = 0;
  await fireEvent.pointerDown(btn);
  await screen.findByText(/Ich höre zu/);
  clock = 3000;
  await fireEvent.pointerUp(btn);
}

describe('Aussprache', () => {
  beforeEach(() => {
    pageState.url = new URL('http://localhost/aussprache');
    HTMLMediaElement.prototype.play = vi.fn(async () => {});
    HTMLMediaElement.prototype.pause = vi.fn();
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    wavDeps.toWav16k = vi.fn(async () => new Blob(['RIFF....WAVE'], { type: 'audio/wav' }));
    recorderFactory.create = vi.fn((onAuto?: (b: Blob | null) => void, maxMs?: number) => new Recorder(fakeEnv(), onAuto, maxMs));
  });

  it('shows the three groups and the quota line; the first exercise is selected', async () => {
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises) });
    render(Page);
    expect(await screen.findByRole('heading', { name: 'Deine Fehler' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Wortschatz' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Schwierige Laute' })).toBeInTheDocument();
    expect(screen.getByText('Aussprache-Kontingent: noch 3 h 12 min diesen Monat · heute noch 87 Versuche')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ich habe die ganze Zeit gewartet.' })).toHaveAttribute('aria-pressed', 'true');
    const current = screen.getByRole('region', { name: 'Dein Satz' });
    expect(within(current).getByText('Ich habe die ganze Zeit gewartet.')).toBeInTheDocument();
    expect(within(current).getByText('„Zeit“ ist feminin.')).toBeInTheDocument();
  });

  it('selecting another sentence shows it large with its hint', async () => {
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises) });
    render(Page);
    await fireEvent.click(await screen.findByRole('button', { name: 'Über die Brücke fahren fünf grüne Autos.' }));
    const current = screen.getByRole('region', { name: 'Dein Satz' });
    expect(within(current).getByText('Über die Brücke fahren fünf grüne Autos.')).toBeInTheDocument();
    expect(within(current).getByText('ü wie in „über“')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ich habe die ganze Zeit gewartet.' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('?card= preselects the vocab sentence', async () => {
    pageState.url = new URL('http://localhost/aussprache?card=7');
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises) });
    render(Page);
    expect(await screen.findByRole('button', { name: 'Die Besprechung beginnt um neun.' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(screen.getByRole('region', { name: 'Dein Satz' })).getByText('die Besprechung')).toBeInTheDocument();
  });

  it('?card= is passed to the exercises request so the API can add that card', async () => {
    pageState.url = new URL('http://localhost/aussprache?card=7');
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises) });
    render(Page);
    await screen.findByRole('button', { name: 'Die Besprechung beginnt um neun.' });
    expect(calls('/api/pronunciation/exercises?card=7')).toHaveLength(1);
  });

  it('the talk button is disabled while a sentence is being played', async () => {
    const created: HTMLAudioElement[] = [];
    const Native = globalThis.Audio;
    globalThis.Audio = function () { const a = new Native(); created.push(a); return a; } as unknown as typeof Audio;
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises),
      '/api/pronunciation/speak': () => mp3() });
    render(Page);
    expect(await screen.findByRole('button', { name: /Halten und sprechen/ })).not.toBeDisabled();
    await fireEvent.click(screen.getByRole('button', { name: '▶ Vorsprechen' }));
    await vi.waitFor(() => expect(screen.getByRole('button', { name: /Halten und sprechen/ })).toBeDisabled()); // playing until "ended"
    await vi.waitFor(() => expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(2)); // unlock + the real play
    created[0].onended?.(new Event('ended'));
    await vi.waitFor(() => expect(screen.getByRole('button', { name: /Halten und sprechen/ })).not.toBeDisabled());
    globalThis.Audio = Native;
  });

  it('?ex= preselects any exercise', async () => {
    pageState.url = new URL('http://localhost/aussprache?ex=sound-1');
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises) });
    render(Page);
    expect(await screen.findByRole('button', { name: 'Über die Brücke fahren fünf grüne Autos.' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('"Vorsprechen" and "Langsam" ask the API with slow false/true and play the audio', async () => {
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises),
      '/api/pronunciation/speak': () => mp3() });
    render(Page);
    await fireEvent.click(await screen.findByRole('button', { name: '▶ Vorsprechen' }));
    await vi.waitFor(() => expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(2)); // unlock + play
    await vi.waitFor(() => expect(screen.getByRole('button', { name: '🐢 Langsam' })).not.toBeDisabled());
    await fireEvent.click(screen.getByRole('button', { name: '🐢 Langsam' }));
    await vi.waitFor(() => expect(calls('/api/pronunciation/speak')).toHaveLength(2));
    const bodies = calls('/api/pronunciation/speak').map((c: any[]) => JSON.parse(c[1].body));
    expect(bodies).toEqual([{ text: 'Ich habe die ganze Zeit gewartet.', slow: false }, { text: 'Ich habe die ganze Zeit gewartet.', slow: true }]);
    expect(calls('/api/pronunciation/speak')[0][1].method).toBe('POST');
  });

  it('records with a 30 s limit, converts to WAV, posts it and shows the scores', async () => {
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises), '/api/pronunciation/assess': () => json(200, assessed) });
    pageState.url = new URL('http://localhost/aussprache?card=7');
    render(Page);
    await screen.findByRole('button', { name: /Halten und sprechen/ });
    await record();
    expect(await screen.findByText('Gesamt')).toBeInTheDocument();
    expect((recorderFactory.create as any).mock.calls[0][1]).toBe(30_000);
    expect(wavDeps.toWav16k).toHaveBeenCalledWith(expect.any(Blob));
    const [, init] = calls('/api/pronunciation/assess')[0];
    expect(init.method).toBe('POST');
    const form = init.body as FormData;
    expect(form.get('text')).toBe('Die Besprechung beginnt um neun.');
    const audio = form.get('audio') as File;
    expect(audio.name).toBe('aufnahme.wav');
    expect(audio.type).toBe('audio/wav');
    expect(screen.getByText('Besprechung')).toHaveClass('text-red-700');
    expect(screen.getByText('Aussprache-Kontingent: noch 3 h 11 min diesen Monat · heute noch 86 Versuche')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Halten und sprechen/ })).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: 'Nochmal' }));
    expect(screen.queryByText('Gesamt')).toBeNull();
    expect(screen.getByRole('button', { name: /Halten und sprechen/ })).toBeInTheDocument();
  });

  it('a slow word from the score view speaks just that word', async () => {
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises), '/api/pronunciation/assess': () => json(200, assessed),
      '/api/pronunciation/speak': () => mp3() });
    pageState.url = new URL('http://localhost/aussprache?card=7');
    render(Page);
    await screen.findByRole('button', { name: /Halten und sprechen/ });
    await record();
    await fireEvent.click(await screen.findByRole('button', { name: /^Besprechung/ }));
    await fireEvent.click(screen.getByRole('button', { name: '„Besprechung“ langsam anhören' }));
    await vi.waitFor(() => expect(calls('/api/pronunciation/speak')).toHaveLength(1));
    expect(JSON.parse(calls('/api/pronunciation/speak')[0][1].body)).toEqual({ text: 'Besprechung', slow: true });
  });

  it('busy: only one assessment, selection locked', async () => {
    let answer: (r: Response) => void = () => {};
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises),
      '/api/pronunciation/assess': () => new Promise<Response>((r) => { answer = r; }) });
    render(Page);
    await screen.findByRole('button', { name: /Halten und sprechen/ });
    await record();
    expect(await screen.findByText('Einen Moment …')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Halten und sprechen/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Die Besprechung beginnt um neun.' })).toBeDisabled();
    answer(json(200, assessed));
    expect(await screen.findByText('Gesamt')).toBeInTheDocument();
    expect(calls('/api/pronunciation/assess')).toHaveLength(1);
  });

  it('shows the monthly quota 429 as alert and keeps the recorder', async () => {
    const MONTH = 'Das kostenlose Aussprache-Kontingent für diesen Monat ist aufgebraucht – ab dem 1. geht es weiter.';
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises), '/api/pronunciation/quota': () => json(200, quota),
      '/api/pronunciation/assess': () => json(429, { type: 'about:blank', title: 'Too Many Requests', status: 429, detail: MONTH }) });
    render(Page);
    await screen.findByRole('button', { name: /Halten und sprechen/ });
    await record();
    expect(await screen.findByRole('alert')).toHaveTextContent(MONTH);
    expect(screen.queryByText('Gesamt')).toBeNull();
    await vi.waitFor(() => expect(screen.getByRole('button', { name: /Halten und sprechen/ })).not.toBeDisabled());
  });

  it('a 429 reloads the quota; an empty quota disables the talk button with an explanation', async () => {
    const MONTH = 'Das kostenlose Aussprache-Kontingent für diesen Monat ist aufgebraucht – ab dem 1. geht es weiter.';
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises),
      '/api/pronunciation/quota': () => json(200, { ...quota, secondsLeft: 0 }),
      '/api/pronunciation/assess': () => json(429, { type: 'about:blank', title: 'Too Many Requests', status: 429, detail: MONTH }) });
    render(Page);
    await screen.findByRole('button', { name: /Halten und sprechen/ });
    await record();
    await vi.waitFor(() => expect(calls('/api/pronunciation/quota')).toHaveLength(1));
    await vi.waitFor(() => expect(screen.getByRole('button', { name: /Halten und sprechen/ })).toBeDisabled());
    expect(screen.getByText(/für diesen Monat ist aufgebraucht/, { selector: 'p.text-center' })).toBeInTheDocument();
  });

  it('disables the talk button when no attempts are left today', async () => {
    mockApi({ '/api/pronunciation/exercises': () => json(200, { ...exercises, quota: { ...quota, todayLeft: 0 } }) });
    render(Page);
    expect(await screen.findByRole('button', { name: /Halten und sprechen/ })).toBeDisabled();
    expect(screen.getByText(/Tageslimit ist erreicht/)).toBeInTheDocument();
  });

  it('a failed WAV conversion is shown as alert without calling the API', async () => {
    wavDeps.toWav16k = vi.fn(async () => { throw new Error('Die Aufnahme konnte nicht umgewandelt werden.'); });
    mockApi({ '/api/pronunciation/exercises': () => json(200, exercises) });
    render(Page);
    await screen.findByRole('button', { name: /Halten und sprechen/ });
    await record();
    expect(await screen.findByRole('alert')).toHaveTextContent('Die Aufnahme konnte nicht umgewandelt werden.');
    expect(calls('/api/pronunciation/assess')).toHaveLength(0);
  });

  it('disabled: only the 503 text, no recorder', async () => {
    mockApi({ '/api/pronunciation/exercises': () =>
      json(503, { type: 'about:blank', title: 'Service Unavailable', status: 503, detail: DISABLED }) });
    render(Page);
    expect(await screen.findByText(DISABLED)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Halten und sprechen/ })).toBeNull();
    expect(screen.queryByRole('button', { name: '▶ Vorsprechen' })).toBeNull();
    expect(screen.queryByText(/Aussprache-Kontingent/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Nochmal versuchen' })).toBeNull();
  });

  it('a load error is an alert with a retry', async () => {
    let n = 0;
    mockApi({ '/api/pronunciation/exercises': () => (++n === 1 ? json(502, { status: 502, detail: 'Der Server antwortet gerade nicht.' }) : json(200, exercises)) });
    render(Page);
    expect(await screen.findByRole('alert')).toHaveTextContent('Der Server antwortet gerade nicht.');
    await fireEvent.click(screen.getByRole('button', { name: 'Nochmal versuchen' }));
    expect(await screen.findByRole('heading', { name: 'Deine Fehler' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('says "1 Versuch" for the last attempt of the day', async () => {
    mockApi({ '/api/pronunciation/exercises': () => json(200, { ...exercises, quota: { ...quota, secondsLeft: 45 * 60, todayLeft: 1 } }) });
    render(Page);
    expect(await screen.findByText('Aussprache-Kontingent: noch 0 h 45 min diesen Monat · heute noch 1 Versuch')).toBeInTheDocument();
  });
});
