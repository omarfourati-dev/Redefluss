import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import { deps } from '#lib/api';
import { Recorder, recorderFactory, type RecorderEnv } from '#lib/recorder';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const turn = { transcript: 'Hallo du muss kommen.', corrections: [{ wrong: 'du muss', right: 'du musst', rule: 'du → -st', category: 'konjugation' }],
  natural: 'Hallo, du musst kommen.', reply: 'Wann soll ich kommen?', replyAudio: null, replyAudioType: null, turnsLeft: 12 };

class FakeMediaRecorder {
  static isTypeSupported = () => true;
  mimeType = 'audio/webm';
  ondataavailable: ((e: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  onerror: (() => void) | null = null;
  start() {}
  stop() { this.ondataavailable?.({ data: new Blob(['abc'], { type: 'audio/webm' }) }); this.onstop?.(); }
}

function fakeMic(getUserMedia?: () => Promise<MediaStream>) {
  const track = { stop: vi.fn() };
  let grant: () => void = () => {};
  const gate = new Promise<void>((r) => { grant = r; });
  const env: RecorderEnv = {
    getUserMedia: getUserMedia ?? (async () => { await gate; return { getTracks: () => [track] } as unknown as MediaStream; }),
    MediaRecorder: FakeMediaRecorder as unknown as typeof MediaRecorder,
    now: () => performance.now(), setTimeout: globalThis.setTimeout.bind(globalThis), clearTimeout: globalThis.clearTimeout.bind(globalThis),
  };
  const create = vi.fn((onAuto?: (b: Blob | null) => void) => new Recorder(env, onAuto));
  recorderFactory.create = create;
  return { track, grant, create };
}

async function openSession() {
  deps.fetch = vi.fn(async (url: string) => url === '/api/sessions' ? json(201, { id: 'abc' }) : json(200, turn)) as any;
  render(Page);
  await fireEvent.click(screen.getByRole('button', { name: 'Arbeit' }));
  await screen.findByRole('button', { name: /Halten und sprechen/ });
}
const turnCalls = () => (deps.fetch as any).mock.calls.filter((c: any[]) => c[0] === '/api/turns');

describe('Gespräch', () => {
  beforeEach(() => {
    localStorage.clear();
    HTMLMediaElement.prototype.play = vi.fn(async () => {});
    HTMLMediaElement.prototype.pause = vi.fn();
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
  });

  it('release before the permission resolves discards the recording', async () => {
    await openSession();
    const mic = fakeMic();
    const btn = screen.getByRole('button', { name: /Halten und sprechen/ });
    await fireEvent.pointerDown(btn);
    await fireEvent.pointerDown(btn);
    await fireEvent.pointerUp(btn);
    mic.grant();
    expect(await screen.findByRole('alert')).toHaveTextContent('Zu kurz');
    await vi.waitFor(() => expect(mic.track.stop).toHaveBeenCalled());
    expect(mic.create).toHaveBeenCalledTimes(1);
    expect(turnCalls()).toHaveLength(0);
    expect(screen.queryByText(/Ich höre zu/)).not.toBeInTheDocument();
  });

  it('disables switching topic while recording', async () => {
    await openSession();
    const mic = fakeMic();
    await fireEvent.pointerDown(screen.getByRole('button', { name: /Halten und sprechen/ }));
    mic.grant();
    await screen.findByText(/Ich höre zu/);
    expect(screen.getByRole('button', { name: 'Thema wechseln' })).toBeDisabled();
  });

  it('releases the microphone when the page is destroyed while recording', async () => {
    await openSession();
    const mic = fakeMic();
    await fireEvent.pointerDown(screen.getByRole('button', { name: /Halten und sprechen/ }));
    mic.grant();
    await screen.findByText(/Ich höre zu/);
    const { cleanup } = await import('@testing-library/svelte');
    cleanup();
    await vi.waitFor(() => expect(mic.track.stop).toHaveBeenCalled());
  });

  it('ignores a late response after the page is gone and disables topic switch while busy', async () => {
    let release: (r: Response) => void = () => {};
    deps.fetch = vi.fn((url: string) => url === '/api/sessions' ? Promise.resolve(json(201, { id: 'abc' })) : new Promise<Response>((r) => { release = r; })) as any;
    render(Page);
    await fireEvent.click(screen.getByRole('button', { name: 'Arbeit' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Lieber tippen' }));
    await fireEvent.input(screen.getByLabelText('Dein Satz'), { target: { value: 'Eins' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Senden' }));
    expect(screen.getByRole('button', { name: 'Thema wechseln' })).toBeDisabled();
    const { cleanup } = await import('@testing-library/svelte');
    cleanup();
    (URL.createObjectURL as any).mockClear();
    release(json(200, { ...turn, replyAudio: 'AAAA', replyAudioType: 'audio/mpeg' }));
    await new Promise((r) => setTimeout(r, 20));
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('reuses one audio element, unlocked in the gesture, and revokes old urls', async () => {
    deps.fetch = vi.fn(async (url: string) => url === '/api/sessions' ? json(201, { id: 'abc' }) : json(200, { ...turn, replyAudio: 'AAAA', replyAudioType: 'audio/mpeg' })) as any;
    render(Page);
    await fireEvent.click(screen.getByRole('button', { name: 'Arbeit' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Lieber tippen' }));
    await fireEvent.input(screen.getByLabelText('Dein Satz'), { target: { value: 'Eins' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Senden' }));
    await screen.findByText('Wann soll ich kommen?');
    const play = HTMLMediaElement.prototype.play as any;
    expect(play).toHaveBeenCalledTimes(2); // unlock + reply
    expect(play.mock.contexts[0]).toBe(play.mock.contexts[1]);
    await fireEvent.input(screen.getByLabelText('Dein Satz'), { target: { value: 'Zwei' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Senden' }));
    await vi.waitFor(() => expect(play).toHaveBeenCalledTimes(4));
    expect(play.mock.contexts[3]).toBe(play.mock.contexts[0]);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:x');
  });

  it('explains a denied microphone and opens the text input', async () => {
    await openSession();
    fakeMic(async () => { throw new DOMException('no', 'NotAllowedError'); });
    await fireEvent.pointerDown(screen.getByRole('button', { name: /Halten und sprechen/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Mikrofon nicht benutzen');
    expect(screen.getByLabelText('Dein Satz')).toBeInTheDocument();
  });

  it('explains a missing microphone and opens the text input', async () => {
    await openSession();
    fakeMic(async () => { throw new DOMException('none', 'NotFoundError'); });
    await fireEvent.pointerDown(screen.getByRole('button', { name: /Halten und sprechen/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Kein Mikrofon gefunden – du kannst deinen Satz auch tippen.');
    expect(screen.getByLabelText('Dein Satz')).toBeInTheDocument();
  });


  it('starts a topic and answers a typed sentence', async () => {
    deps.fetch = vi.fn(async (url: string) => url === '/api/sessions' ? json(201, { id: 'abc' }) : json(200, turn)) as any;
    render(Page);
    await fireEvent.click(screen.getByRole('button', { name: 'Arbeit' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Lieber tippen' }));
    await fireEvent.input(screen.getByLabelText('Dein Satz'), { target: { value: 'Hallo du muss kommen.' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Senden' }));
    expect(await screen.findByText('Wann soll ich kommen?')).toBeInTheDocument();
    expect(screen.getByText('Noch 12 Runden heute')).toBeInTheDocument();
    const form = (deps.fetch as any).mock.calls[1][1].body as FormData;
    expect(form.get('sessionId')).toBe('abc');
    expect(form.get('text')).toBe('Hallo du muss kommen.');
    expect(JSON.parse(form.get('history') as string)).toEqual([]);
  });

  it('allows only one turn at a time', async () => {
    let release: (r: Response) => void = () => {};
    deps.fetch = vi.fn((url: string) => url === '/api/sessions' ? Promise.resolve(json(201, { id: 'abc' })) : new Promise<Response>((r) => { release = r; })) as any;
    render(Page);
    await fireEvent.click(screen.getByRole('button', { name: 'Arbeit' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Lieber tippen' }));
    await fireEvent.input(screen.getByLabelText('Dein Satz'), { target: { value: 'Eins' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Senden' }));
    expect(screen.getByRole('button', { name: 'Senden' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Halten und sprechen/ })).toBeDisabled();
    await fireEvent.submit(screen.getByLabelText('Dein Satz').closest('form')!);
    expect((deps.fetch as any).mock.calls.filter((c: any[]) => c[0] === '/api/turns')).toHaveLength(1);
    release(json(200, turn));
    expect(await screen.findByText('Wann soll ich kommen?')).toBeInTheDocument();
  });

  it('shows server errors', async () => {
    deps.fetch = vi.fn(async (url: string) => url === '/api/sessions' ? json(201, { id: 'abc' })
      : json(429, { status: 429, detail: "Tageslimit erreicht (300 Runden). Morgen geht's weiter." })) as any;
    render(Page);
    await fireEvent.click(screen.getByRole('button', { name: 'Freies Thema' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Lieber tippen' }));
    await fireEvent.input(screen.getByLabelText('Dein Satz'), { target: { value: 'Hallo' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Senden' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Tageslimit erreicht');
  });
});
