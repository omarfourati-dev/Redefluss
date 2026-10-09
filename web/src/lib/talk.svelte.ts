import { MicDeniedError, MicUnavailableError, recorderFactory, type Recorder } from '#lib/recorder';

/** A release this long after the press means the user was answering the microphone prompt, not tapping too briefly. */
const PROMPT_MS = 1000;
const SILENT = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=';

export const TALK_TEXT = {
  denied: 'Ich darf das Mikrofon nicht benutzen. Erlaube es in den Browser-Einstellungen – oder tippe deinen Satz.',
  unavailable: 'Kein Mikrofon gefunden – du kannst deinen Satz auch tippen.',
  failed: 'Aufnahme nicht möglich.',
  short: 'Zu kurz – halte die Taste gedrückt, während du sprichst.',
  granted: 'Mikrofon ist freigegeben – halte die Taste jetzt gedrückt und sprich.'
} as const;

/** `denied`/`unavailable` mean: offer the text input instead. */
export type TalkErrorKind = 'denied' | 'unavailable' | 'failed' | 'short';

export interface TalkOptions {
  /** A finished recording (released or auto-stopped after `maxMs`). */
  onBlob: (blob: Blob) => void;
  onError: (message: string, kind: TalkErrorKind) => void;
  /** A friendly hint (show it with role="status"), e.g. after the permission prompt. */
  onInfo?: (message: string) => void;
  /** Automatic stop; the recorder's default (60 s) when omitted. */
  maxMs?: number;
}

export interface Talk {
  /** Start on press (pointer down / key down). Ignored while a press is in progress. */
  press(): Promise<void>;
  /** Stop on release and hand the blob to `onBlob`. */
  release(): Promise<void>;
  readonly recording: boolean;
  readonly seconds: number;
  /** A press is in progress (waiting for the microphone or recording). */
  readonly active: boolean;
  /** Discard a running recording and free the microphone; the talk stays usable. */
  destroy(): void;
}

/** Hold-to-talk state shared by the Gespräch and Wortschatz pages. */
export function createTalk(opts: TalkOptions): Talk {
  let recording = $state(false);
  let seconds = $state(0);
  let recorder: Recorder | null = null;
  let pressed = false;
  let ticker: ReturnType<typeof setInterval> | null = null;

  function stopTicker() {
    if (ticker) { clearInterval(ticker); ticker = null; }
  }

  function destroy() {
    pressed = false;
    recording = false;
    stopTicker();
    const r = recorder;
    recorder = null;
    if (r) void r.stop();
  }

  async function press() {
    if (recorder || pressed) return;
    pressed = true;
    const r = recorderFactory.create((blob) => { if (recorder === r) finish(blob); }, opts.maxMs);
    recorder = r;
    const t0 = performance.now();
    try {
      await r.start();
    } catch (e) {
      if (recorder !== r) return; // discarded meanwhile
      recorder = null;
      pressed = false;
      if (e instanceof MicDeniedError) opts.onError(TALK_TEXT.denied, 'denied');
      else if (e instanceof MicUnavailableError) opts.onError(TALK_TEXT.unavailable, 'unavailable');
      else opts.onError(TALK_TEXT.failed, 'failed');
      return;
    }
    if (recorder !== r) { void r.stop(); return; }
    if (!pressed) {
      // released before the permission prompt resolved: discard
      recorder = null;
      void r.stop();
      if (performance.now() - t0 > PROMPT_MS) opts.onInfo?.(TALK_TEXT.granted);
      else opts.onError(TALK_TEXT.short, 'short');
      return;
    }
    recording = true;
    seconds = 0;
    ticker = setInterval(() => (seconds += 1), 1000);
  }

  async function release() {
    pressed = false;
    const r = recorder;
    if (!r || !recording) return;
    const blob = await r.stop();
    if (recorder !== r) return;
    finish(blob);
  }

  function finish(blob: Blob | null) {
    recording = false;
    pressed = false;
    stopTicker();
    recorder = null;
    if (blob) opts.onBlob(blob);
    else opts.onError(TALK_TEXT.short, 'short');
  }

  return {
    press,
    release,
    destroy,
    get recording() { return recording; },
    get seconds() { return seconds; },
    get active() { return pressed || recorder !== null; }
  };
}

export interface Player {
  /** Call synchronously inside the user gesture (iOS only lets an element play later if it was started in one). */
  unlock(): void;
  /** Play the blob on the shared element; its object URL is revoked after playback. No-op after destroy(). */
  play(blob: Blob): void;
  destroy(): void;
}

/** One shared HTMLAudioElement per page. */
export function createPlayer(): Player {
  let el: HTMLAudioElement | null = null;
  let url: string | null = null;
  let destroyed = false;

  function revoke() {
    if (url) { URL.revokeObjectURL(url); url = null; }
  }

  function audio(): HTMLAudioElement {
    if (!el) {
      el = new Audio();
      el.onended = revoke;
      el.onerror = revoke;
    }
    return el;
  }

  return {
    unlock() {
      if (destroyed) return;
      try {
        const a = audio();
        a.src = SILENT;
        const p = a.play() as Promise<void> | undefined;
        a.pause();
        p?.catch(() => {});
      } catch { /* ignore */ }
    },
    play(blob: Blob) {
      if (destroyed) return; // e.g. the audio arrived after the page was left
      try {
        const a = audio();
        revoke();
        url = URL.createObjectURL(blob);
        a.src = url;
        const p = a.play() as Promise<void> | undefined;
        p?.catch(() => revoke());
      } catch { revoke(); }
    },
    destroy() {
      destroyed = true;
      if (el) { el.pause(); el.onended = null; el.onerror = null; }
      revoke();
    }
  };
}
