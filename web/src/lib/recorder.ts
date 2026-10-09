export const MIN_MS = 400;
export const MAX_MS = 60_000;
const TYPES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'];

export class MicDeniedError extends Error {}
export class MicUnavailableError extends Error {}

const STOP_TIMEOUT_MS = 3000;

export function pickMimeType(isSupported: (t: string) => boolean): string | undefined {
  return TYPES.find((t) => {
    try {
      return isSupported(t);
    } catch {
      return false;
    }
  });
}

export interface RecorderEnv {
  getUserMedia(c: MediaStreamConstraints): Promise<MediaStream>;
  MediaRecorder: typeof MediaRecorder;
  now(): number;
  setTimeout: typeof setTimeout;
  clearTimeout: typeof clearTimeout;
}

const browserEnv = (): RecorderEnv => ({
  getUserMedia: (c) => navigator.mediaDevices.getUserMedia(c),
  MediaRecorder: globalThis.MediaRecorder,
  now: () => performance.now(),
  setTimeout: globalThis.setTimeout.bind(globalThis),
  clearTimeout: globalThis.clearTimeout.bind(globalThis)
});

/** Hold-to-talk recording: start on press, stop on release; too-short taps give null, 60 s stop automatically. */
export class Recorder {
  private rec: MediaRecorder | null = null;
  private stream: MediaStream | null = null;
  private chunks: Blob[] = [];
  private startedAt = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private env: RecorderEnv = browserEnv(),
    private onAutoStop?: (blob: Blob | null) => void
  ) {}

  get recording() {
    return this.rec !== null;
  }

  private release() {
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
  }

  async start(): Promise<void> {
    let stream: MediaStream;
    try {
      stream = await this.env.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true }
      });
    } catch (e) {
      const name = (e as Error | undefined)?.name;
      const message = (e as Error | undefined)?.message ?? '';
      throw name === 'NotAllowedError' || name === 'SecurityError'
        ? new MicDeniedError(message)
        : new MicUnavailableError(message);
    }
    this.stream = stream;
    try {
      const mimeType = pickMimeType((t) => this.env.MediaRecorder.isTypeSupported(t));
      this.chunks = [];
      const rec = new this.env.MediaRecorder(stream, mimeType ? { mimeType } : {});
      rec.ondataavailable = (e) => {
        if (e.data.size > 0) this.chunks.push(e.data);
      };
      rec.onerror = () => {
        this.release();
        this.rec = null;
      };
      rec.start();
      this.rec = rec;
    } catch (e) {
      this.release();
      this.rec = null;
      throw e;
    }
    this.startedAt = this.env.now();
    this.timer = this.env.setTimeout(() => {
      void this.stop().then((b) => this.onAutoStop?.(b));
    }, MAX_MS);
  }

  stop(): Promise<Blob | null> {
    const rec = this.rec;
    if (!rec) return Promise.resolve(null);
    this.rec = null;
    if (this.timer !== null) this.env.clearTimeout(this.timer);
    const duration = this.env.now() - this.startedAt;
    return new Promise((resolve) => {
      let done = false;
      let safety: ReturnType<typeof setTimeout> | undefined;
      const finish = (blob: Blob | null) => {
        if (done) return;
        done = true;
        if (safety !== undefined) this.env.clearTimeout(safety);
        this.release();
        resolve(blob);
      };
      rec.onstop = () => {
        const type = rec.mimeType || this.chunks[0]?.type || 'audio/webm';
        finish(duration < MIN_MS || this.chunks.length === 0 ? null : new Blob(this.chunks, { type }));
      };
      rec.onerror = () => finish(null);
      safety = this.env.setTimeout(() => finish(null), STOP_TIMEOUT_MS);
      try {
        rec.stop();
      } catch {
        finish(null);
      }
    });
  }
}

/** Swappable in tests. */
export const recorderFactory = {
  create: (onAutoStop?: (blob: Blob | null) => void): Recorder => new Recorder(undefined, onAutoStop)
};
