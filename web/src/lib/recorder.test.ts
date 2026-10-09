import { describe, expect, it, vi } from 'vitest';
import { MAX_MS, MicDeniedError, MicUnavailableError, pickMimeType, Recorder, type RecorderEnv } from './recorder';

class FakeMediaRecorder {
  static isTypeSupported = (t: string) => t === 'audio/mp4';
  state = 'inactive';
  ondataavailable: ((e: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  constructor(public stream: MediaStream, public options: { mimeType?: string }) {}
  start() { this.state = 'recording'; }
  stop() { this.state = 'inactive'; this.ondataavailable?.({ data: new Blob(['abc'], { type: this.options.mimeType }) }); this.onstop?.(); }
}

function env(overrides: Partial<RecorderEnv> = {}) {
  let t = 0;
  const track = { stop: vi.fn() };
  const e: RecorderEnv & { advance(ms: number): void; track: typeof track } = {
    getUserMedia: vi.fn(async () => ({ getTracks: () => [track] }) as unknown as MediaStream),
    MediaRecorder: FakeMediaRecorder as unknown as typeof MediaRecorder,
    now: () => t, setTimeout: vi.fn(() => 1) as unknown as typeof setTimeout, clearTimeout: vi.fn() as unknown as typeof clearTimeout,
    advance(ms) { t += ms; }, track, ...overrides,
  };
  return e;
}

describe('Recorder', () => {
  it('prefers webm/opus, then mp4 (iPhone), then ogg', () => {
    expect(pickMimeType((t) => t.startsWith('audio/webm'))).toBe('audio/webm;codecs=opus');
    expect(pickMimeType((t) => t === 'audio/mp4')).toBe('audio/mp4');
    expect(pickMimeType(() => false)).toBeUndefined();
  });

  it('records a blob and releases the microphone', async () => {
    const e = env();
    const r = new Recorder(e);
    await r.start();
    expect(r.recording).toBe(true);
    e.advance(1500);
    const blob = await r.stop();
    expect(blob?.type).toBe('audio/mp4');
    expect(e.track.stop).toHaveBeenCalled();
    expect(r.recording).toBe(false);
  });

  it('a tap shorter than MIN_MS gives null', async () => {
    const e = env();
    const r = new Recorder(e);
    await r.start();
    e.advance(200);
    expect(await r.stop()).toBeNull();
  });

  it('stops automatically after MAX_MS', async () => {
    let fire: () => void = () => {};
    const e = env({ setTimeout: vi.fn((fn: () => void, ms: number) => { if (ms === MAX_MS) fire = fn; return 1; }) as unknown as typeof setTimeout });
    const onAuto = vi.fn();
    const r = new Recorder(e, onAuto);
    await r.start();
    e.advance(MAX_MS);
    fire();
    await vi.waitFor(() => expect(onAuto).toHaveBeenCalledWith(expect.any(Blob)));
  });

  it('denied permission throws MicDeniedError', async () => {
    const e = env({ getUserMedia: vi.fn(async () => { throw new DOMException('no', 'NotAllowedError'); }) });
    await expect(new Recorder(e).start()).rejects.toBeInstanceOf(MicDeniedError);
  });

  it('stop without start gives null', async () => {
    expect(await new Recorder(env()).stop()).toBeNull();
  });

  it('maps a missing device or missing mediaDevices to MicUnavailableError', async () => {
    const nf = env({ getUserMedia: vi.fn(async () => { throw new DOMException('none', 'NotFoundError'); }) });
    await expect(new Recorder(nf).start()).rejects.toBeInstanceOf(MicUnavailableError);
    const none = env({ getUserMedia: vi.fn(async () => { throw new TypeError('mediaDevices undefined'); }) });
    await expect(new Recorder(none).start()).rejects.toBeInstanceOf(MicUnavailableError);
    const sec = env({ getUserMedia: vi.fn(async () => { throw new DOMException('x', 'SecurityError'); }) });
    await expect(new Recorder(sec).start()).rejects.toBeInstanceOf(MicDeniedError);
  });

  it('releases the microphone when the MediaRecorder cannot be created', async () => {
    const e = env({ MediaRecorder: class { static isTypeSupported = () => true; constructor() { throw new Error('boom'); } } as unknown as typeof MediaRecorder });
    const r = new Recorder(e);
    await expect(r.start()).rejects.toThrow('boom');
    expect(e.track.stop).toHaveBeenCalled();
    expect(r.recording).toBe(false);
  });

  it('stop resolves null via the safety timeout when onstop never fires', async () => {
    class Hanging extends FakeMediaRecorder { stop() { this.state = 'inactive'; } }
    const timers: Array<{ fn: () => void; ms: number }> = [];
    const e = env({
      MediaRecorder: Hanging as unknown as typeof MediaRecorder,
      setTimeout: vi.fn((fn: () => void, ms: number) => { timers.push({ fn, ms }); return timers.length; }) as unknown as typeof setTimeout,
    });
    const r = new Recorder(e);
    await r.start();
    e.advance(1500);
    const p = r.stop();
    const safety = timers.find((t) => t.ms !== MAX_MS)!;
    expect(safety.ms).toBeLessThanOrEqual(5000);
    safety.fn();
    expect(await p).toBeNull();
    expect(e.track.stop).toHaveBeenCalled();
  });

  it('a recorder error during stop resolves null and releases the microphone', async () => {
    class Failing extends FakeMediaRecorder { stop() { this.state = 'inactive'; this.onerror?.(); } onerror: (() => void) | null = null; }
    const e = env({ MediaRecorder: Failing as unknown as typeof MediaRecorder });
    const r = new Recorder(e);
    await r.start();
    e.advance(1500);
    expect(await r.stop()).toBeNull();
    expect(e.track.stop).toHaveBeenCalled();
  });
});
