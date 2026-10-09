import { describe, expect, it, vi } from 'vitest';
import { collectTranscript, connect, LiveError, type LiveDeps, type LiveEvent } from '#lib/live';

class FakeDataChannel {
  readyState = 'connecting';
  onopen: ((e: Event) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  sent: string[] = [];
  constructor(public label: string) {}
  send(s: string) { this.sent.push(s); }
  close = vi.fn(() => { this.readyState = 'closed'; });
  open() { this.readyState = 'open'; this.onopen?.(new Event('open')); }
  emit(event: unknown) { this.onmessage?.({ data: JSON.stringify(event) }); }
}

class FakePeerConnection {
  connectionState = 'new';
  ontrack: ((e: { streams: MediaStream[] }) => void) | null = null;
  onconnectionstatechange: (() => void) | null = null;
  tracks: unknown[] = [];
  dc: FakeDataChannel | null = null;
  local: unknown = null;
  remote: RTCSessionDescriptionInit | null = null;
  constructor(public autoOpen = true) {}
  addTrack(track: unknown) { this.tracks.push(track); }
  createDataChannel(label: string) { this.dc = new FakeDataChannel(label); return this.dc; }
  async createOffer() { return { type: 'offer', sdp: 'OFFER-SDP' }; }
  async setLocalDescription(d: unknown) { this.local = d; }
  async setRemoteDescription(d: RTCSessionDescriptionInit) {
    this.remote = d;
    if (this.autoOpen) setTimeout(() => this.dc?.open(), 0);
  }
  close = vi.fn(() => { this.connectionState = 'closed'; });
  setState(s: string) { this.connectionState = s; this.onconnectionstatechange?.(); }
}

function setup(opts: { sdp?: () => Response; autoOpen?: boolean; mic?: () => Promise<MediaStream> } = {}) {
  const track = { stop: vi.fn(), kind: 'audio' };
  const stream = { getTracks: () => [track] } as unknown as MediaStream;
  const pcs: FakePeerConnection[] = [];
  const timers: (() => void)[] = [];
  const deps: LiveDeps = {
    createPeerConnection: () => {
      const pc = new FakePeerConnection(opts.autoOpen ?? true);
      pcs.push(pc);
      return pc as unknown as RTCPeerConnection;
    },
    getUserMedia: vi.fn(opts.mic ?? (async () => stream)),
    fetch: vi.fn(async () => (opts.sdp ? opts.sdp() : new Response('ANSWER-SDP', { status: 201, headers: { 'Content-Type': 'application/sdp' } }))) as unknown as typeof fetch,
    setTimeout: ((fn: () => void) => { timers.push(fn); return timers.length; }) as unknown as typeof setTimeout,
    clearTimeout: vi.fn() as unknown as typeof clearTimeout
  };
  const audio = { srcObject: null as unknown, pause: vi.fn(), play: vi.fn(async () => {}) } as unknown as HTMLAudioElement;
  return { track, stream, pcs, timers, deps, audio };
}

describe('collectTranscript', () => {
  it('orders completed transcripts by the item creation order, not by arrival', () => {
    const events: LiveEvent[] = [
      { type: 'conversation.item.added', item: { id: 'a1' } },
      { type: 'response.output_audio_transcript.done', item_id: 'a1', transcript: 'Guten Tag, erzählen Sie von sich.' },
      { type: 'conversation.item.added', item: { id: 'u1' } },
      { type: 'conversation.item.created', item: { id: 'a2' } },
      { type: 'response.output_audio_transcript.done', item_id: 'a2', transcript: 'Und warum diese Stelle?' },
      // the user's transcription completes after the interviewer already answered
      { type: 'conversation.item.input_audio_transcription.completed', item_id: 'u1', transcript: 'Ich bin Entwickler.' }
    ];
    expect(collectTranscript(events)).toEqual([
      { role: 'interviewer', text: 'Guten Tag, erzählen Sie von sich.' },
      { role: 'omar', text: 'Ich bin Entwickler.' },
      { role: 'interviewer', text: 'Und warum diese Stelle?' }
    ]);
  });

  it('ignores partial deltas, blank transcripts and unknown events', () => {
    const events: LiveEvent[] = [
      { type: 'response.output_audio_transcript.delta', item_id: 'a1', delta: 'Gu' } as LiveEvent,
      { type: 'conversation.item.input_audio_transcription.delta', item_id: 'u1', delta: 'Ich' } as LiveEvent,
      { type: 'conversation.item.input_audio_transcription.completed', item_id: 'u0', transcript: '   ' },
      { type: 'session.updated' },
      { type: 'response.output_audio_transcript.done', item_id: 'a1', transcript: ' Guten Tag. ' }
    ];
    expect(collectTranscript(events)).toEqual([{ role: 'interviewer', text: 'Guten Tag.' }]);
  });

  it('falls back to arrival order for items never announced', () => {
    expect(collectTranscript([
      { type: 'conversation.item.input_audio_transcription.completed', item_id: 'x', transcript: 'Erst ich.' },
      { type: 'response.output_audio_transcript.done', item_id: 'y', transcript: 'Dann sie.' }
    ])).toEqual([{ role: 'omar', text: 'Erst ich.' }, { role: 'interviewer', text: 'Dann sie.' }]);
  });

  it('keeps one entry per item (the last completed text wins) and caps length and count', () => {
    const many: LiveEvent[] = Array.from({ length: 205 }, (_, i) => ({
      type: 'response.output_audio_transcript.done', item_id: `i${i}`, transcript: `Frage ${i}`
    }));
    const t = collectTranscript([
      { type: 'conversation.item.input_audio_transcription.completed', item_id: 'u', transcript: 'alt' },
      { type: 'conversation.item.input_audio_transcription.completed', item_id: 'u', transcript: 'x'.repeat(2500) },
      ...many
    ]);
    expect(t).toHaveLength(200);
    expect(t[0]).toEqual({ role: 'omar', text: 'x'.repeat(2000) });
    expect(t[1]).toEqual({ role: 'interviewer', text: 'Frage 0' });
  });
});

describe('live.connect', () => {
  it('opens the mic, posts the SDP offer to OpenAI and applies the answer', async () => {
    const s = setup();
    const conn = await connect({ clientSecret: 'ek_test', model: 'gpt-realtime', audio: s.audio, onTranscript: vi.fn(), onFailed: vi.fn() }, s.deps);
    expect(s.deps.getUserMedia).toHaveBeenCalledWith({ audio: { echoCancellation: true, noiseSuppression: true } });
    const pc = s.pcs[0];
    expect(pc.tracks).toEqual([s.track]);
    expect(pc.dc?.label).toBe('oai-events');
    const [url, init] = (s.deps.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('https://api.openai.com/v1/realtime/calls?model=gpt-realtime');
    expect(init.method).toBe('POST');
    expect(init.headers).toEqual({ Authorization: 'Bearer ek_test', 'Content-Type': 'application/sdp' });
    expect(init.body).toBe('OFFER-SDP');
    expect(pc.local).toEqual({ type: 'offer', sdp: 'OFFER-SDP' });
    expect(pc.remote).toEqual({ type: 'answer', sdp: 'ANSWER-SDP' });
    // the interviewer opens the conversation
    expect(pc.dc?.sent.map((x) => JSON.parse(x).type)).toEqual(['response.create']);
    conn.close();
  });

  it('plays the remote track and turns data-channel events into the transcript', async () => {
    const s = setup();
    const onTranscript = vi.fn();
    const conn = await connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript, onFailed: vi.fn() }, s.deps);
    const pc = s.pcs[0];
    const remote = { id: 'remote' } as unknown as MediaStream;
    pc.ontrack?.({ streams: [remote] });
    expect(s.audio.srcObject).toBe(remote);
    pc.dc!.emit({ type: 'conversation.item.added', item: { id: 'a1' } });
    pc.dc!.emit({ type: 'response.output_audio_transcript.done', item_id: 'a1', transcript: 'Hallo Herr Fourati.' });
    pc.dc!.onmessage?.({ data: 'kein json' });
    pc.dc!.emit({ type: 'conversation.item.input_audio_transcription.completed', item_id: 'u1', transcript: 'Hallo!' });
    expect(onTranscript).toHaveBeenLastCalledWith([
      { role: 'interviewer', text: 'Hallo Herr Fourati.' },
      { role: 'omar', text: 'Hallo!' }
    ]);
    expect(conn.transcript).toHaveLength(2);
    conn.close();
  });

  it('close releases the mic, the data channel, the connection and the audio (idempotent)', async () => {
    const s = setup();
    const onFailed = vi.fn();
    const conn = await connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed }, s.deps);
    const pc = s.pcs[0];
    conn.close();
    conn.close();
    expect(s.track.stop).toHaveBeenCalled();
    expect(pc.dc!.close).toHaveBeenCalledTimes(1);
    expect(pc.close).toHaveBeenCalledTimes(1);
    expect(s.audio.pause).toHaveBeenCalled();
    expect(s.audio.srcObject).toBeNull();
    pc.setState('failed');
    expect(onFailed).not.toHaveBeenCalled();
  });

  it('reports a failed connection after it was established once', async () => {
    const s = setup();
    const onFailed = vi.fn();
    const conn = await connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed }, s.deps);
    s.pcs[0].setState('disconnected');
    expect(onFailed).not.toHaveBeenCalled();
    s.pcs[0].setState('failed');
    s.pcs[0].setState('failed');
    expect(onFailed).toHaveBeenCalledTimes(1);
    conn.close();
  });

  it('plays the remote stream; a refused play() is reported so the page can offer a button', async () => {
    const s = setup();
    const onAudioBlocked = vi.fn();
    const conn = await connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed: vi.fn(), onAudioBlocked }, s.deps);
    const remote = { id: 'remote' } as unknown as MediaStream;
    s.pcs[0].ontrack?.({ streams: [remote] });
    await Promise.resolve();
    expect(s.audio.play).toHaveBeenCalledTimes(1);
    expect(onAudioBlocked).not.toHaveBeenCalled();
    (s.audio.play as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new DOMException('blocked', 'NotAllowedError'));
    s.pcs[0].ontrack?.({ streams: [remote] });
    await vi.waitFor(() => expect(onAudioBlocked).toHaveBeenCalledTimes(1));
    conn.close();
  });

  it('the data channel closing after the connect counts as a failure (once)', async () => {
    const s = setup();
    const onFailed = vi.fn();
    const conn = await connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed }, s.deps);
    s.pcs[0].dc!.onclose?.();
    s.pcs[0].dc!.onclose?.();
    s.pcs[0].setState('closed');
    expect(onFailed).toHaveBeenCalledTimes(1);
    conn.close();
  });

  it('the connection state turning closed after the connect counts as a failure', async () => {
    const s = setup();
    const onFailed = vi.fn();
    const conn = await connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed }, s.deps);
    s.pcs[0].setState('closed');
    expect(onFailed).toHaveBeenCalledTimes(1);
    conn.close();
  });

  it('closing locally is not a failure, even when the close events arrive afterwards', async () => {
    const s = setup();
    const onFailed = vi.fn();
    const conn = await connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed }, s.deps);
    const dc = s.pcs[0].dc!;
    const handler = dc.onclose;
    conn.close();
    handler?.();
    dc.onclose?.();
    s.pcs[0].setState('closed');
    expect(onFailed).not.toHaveBeenCalled();
  });

  it('the channel closing before it ever opened is a failed start', async () => {
    const s = setup({ autoOpen: false });
    const p = connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed: vi.fn() }, s.deps).catch((e) => e);
    await vi.waitFor(() => expect(s.pcs[0]?.remote).not.toBeNull());
    s.pcs[0].dc!.onclose?.();
    const err = await p;
    expect(err).toBeInstanceOf(LiveError);
    expect(err.kind).toBe('failed');
    expect(s.track.stop).toHaveBeenCalled();
  });

  it('mic denied: a denied LiveError and no peer connection', async () => {
    const s = setup({ mic: async () => { throw Object.assign(new Error('no'), { name: 'NotAllowedError' }); } });
    const err = await connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed: vi.fn() }, s.deps).catch((e) => e);
    expect(err).toBeInstanceOf(LiveError);
    expect(err.kind).toBe('denied');
    expect(s.pcs).toHaveLength(0);
  });

  it('a missing mic is a plain failure', async () => {
    const s = setup({ mic: async () => { throw Object.assign(new Error('no'), { name: 'NotFoundError' }); } });
    const err = await connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed: vi.fn() }, s.deps).catch((e) => e);
    expect(err.kind).toBe('failed');
  });

  it('SDP rejected: failed, everything released', async () => {
    const s = setup({ sdp: () => new Response('nope', { status: 401 }) });
    const err = await connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed: vi.fn() }, s.deps).catch((e) => e);
    expect(err).toBeInstanceOf(LiveError);
    expect(err.kind).toBe('failed');
    expect(s.track.stop).toHaveBeenCalled();
    expect(s.pcs[0].close).toHaveBeenCalled();
  });

  it('SDP request throws (network): failed', async () => {
    const s = setup();
    s.deps.fetch = vi.fn(async () => { throw new TypeError('network'); }) as unknown as typeof fetch;
    const err = await connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed: vi.fn() }, s.deps).catch((e) => e);
    expect(err.kind).toBe('failed');
    expect(s.track.stop).toHaveBeenCalled();
  });

  it('connection failing before the channel opens: failed', async () => {
    const s = setup({ autoOpen: false });
    const p = connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed: vi.fn() }, s.deps).catch((e) => e);
    await vi.waitFor(() => expect(s.pcs[0]?.remote).not.toBeNull());
    s.pcs[0].setState('failed');
    const err = await p;
    expect(err.kind).toBe('failed');
    expect(s.track.stop).toHaveBeenCalled();
  });

  it('times out after 15 s without an open channel', async () => {
    const s = setup({ autoOpen: false });
    const timeouts: number[] = [];
    const fire: (() => void)[] = [];
    s.deps.setTimeout = ((fn: () => void, ms: number) => { timeouts.push(ms); fire.push(fn); return 1; }) as unknown as typeof setTimeout;
    const p = connect({ clientSecret: 'ek', model: 'm', audio: s.audio, onTranscript: vi.fn(), onFailed: vi.fn() }, s.deps).catch((e) => e);
    await vi.waitFor(() => expect(s.pcs[0]?.remote).not.toBeNull());
    expect(timeouts).toEqual([15_000]);
    fire[0]();
    const err = await p;
    expect(err.kind).toBe('failed');
    expect(s.pcs[0].close).toHaveBeenCalled();
    expect(s.track.stop).toHaveBeenCalled();
  });
});
