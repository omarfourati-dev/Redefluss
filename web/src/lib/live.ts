import type { TranscriptEntry } from '#lib/types';

/** Without an open data channel after this long (from the granted mic on), the call counts as failed. */
export const CONNECT_TIMEOUT_MS = 15_000;
const MAX_ENTRIES = 200;
const MAX_CHARS = 2000;
const CALLS_URL = 'https://api.openai.com/v1/realtime/calls';

export const LIVE_TEXT = {
  failed: 'Die Verbindung zum Interview hat nicht geklappt – deine Minuten wurden nicht verbraucht.',
  denied: 'Ich darf das Mikrofon nicht benutzen. Erlaube es in den Browser-Einstellungen – deine Minuten wurden nicht verbraucht.'
} as const;

/** A Realtime server event from the `oai-events` data channel (only the fields used here). */
export interface LiveEvent {
  type: string;
  item_id?: string;
  transcript?: string;
  item?: { id?: string };
}

const OMAR_DONE = 'conversation.item.input_audio_transcription.completed';
const INTERVIEWER_DONE = 'response.output_audio_transcript.done';
const ANNOUNCED = new Set(['conversation.item.created', 'conversation.item.added']);

/** Wanted events only: the transcript is rebuilt from these, deltas and audio events are dropped. */
function relevant(e: LiveEvent): boolean {
  return ANNOUNCED.has(e.type) || e.type === OMAR_DONE || e.type === INTERVIEWER_DONE;
}

/**
 * The finished transcript: only completed transcriptions (no partial deltas), one entry per item (the last text wins),
 * ordered by the item's first appearance (`conversation.item.created`/`added`; arrival order for items never announced),
 * trimmed, blank entries skipped, capped at 200 entries × 2000 characters.
 */
export function collectTranscript(events: readonly LiveEvent[]): TranscriptEntry[] {
  const order = new Map<string, number>();
  const texts = new Map<string, TranscriptEntry>();
  const see = (id: string) => { if (!order.has(id)) order.set(id, order.size); };
  for (const e of events) {
    if (ANNOUNCED.has(e.type) && typeof e.item?.id === 'string') see(e.item.id);
    else if ((e.type === OMAR_DONE || e.type === INTERVIEWER_DONE) && typeof e.item_id === 'string') {
      see(e.item_id);
      const text = (e.transcript ?? '').trim();
      if (text) texts.set(e.item_id, { role: e.type === OMAR_DONE ? 'omar' : 'interviewer', text: text.slice(0, MAX_CHARS) });
      else texts.delete(e.item_id);
    }
  }
  return [...texts.entries()]
    .sort(([a], [b]) => order.get(a)! - order.get(b)!)
    .map(([, entry]) => entry)
    .slice(0, MAX_ENTRIES);
}

export class LiveError extends Error {
  constructor(public kind: 'denied' | 'failed', message: string = LIVE_TEXT[kind]) {
    super(message);
  }
}

export interface LiveDeps {
  createPeerConnection(): RTCPeerConnection;
  getUserMedia(c: MediaStreamConstraints): Promise<MediaStream>;
  fetch: typeof fetch;
  setTimeout: typeof setTimeout;
  clearTimeout: typeof clearTimeout;
}

/** Swappable in tests. */
export const liveDeps: LiveDeps = {
  createPeerConnection: () => new RTCPeerConnection(),
  getUserMedia: (c) => navigator.mediaDevices.getUserMedia(c),
  fetch: (...a) => fetch(...a),
  setTimeout: ((fn: () => void, ms?: number) => globalThis.setTimeout(fn, ms)) as typeof setTimeout,
  clearTimeout: ((id?: ReturnType<typeof setTimeout>) => globalThis.clearTimeout(id)) as typeof clearTimeout
};

export interface ConnectOptions {
  clientSecret: string;
  model: string;
  /** Plays the interviewer's voice (a hidden `<audio autoplay>`). */
  audio: HTMLAudioElement | null;
  /** The whole transcript so far, after every completed transcription. */
  onTranscript: (entries: TranscriptEntry[]) => void;
  /** The established connection failed or was closed from the far side (once). */
  onFailed: () => void;
  /** The browser refused to play the interviewer's voice without a tap; the page offers a button that calls `audio.play()` again. */
  onAudioBlocked?: () => void;
}

export interface LiveConnection {
  readonly transcript: TranscriptEntry[];
  /** Ends the call: data channel, peer connection, mic and audio; safe to call twice. */
  close(): void;
}

/**
 * Starts the WebRTC call with OpenAI Realtime: mic → peer connection → `oai-events` data channel → SDP offer to OpenAI
 * with the short-lived client secret → answer. Resolves once the data channel is open; rejects with a {@link LiveError}
 * (mic denied, SDP rejected, connection failed, 15 s timeout) after releasing everything.
 */
export async function connect(opts: ConnectOptions, deps: LiveDeps = liveDeps): Promise<LiveConnection> {
  let stream: MediaStream;
  try {
    stream = await deps.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
  } catch (e) {
    const name = (e as Error | undefined)?.name;
    throw new LiveError(name === 'NotAllowedError' || name === 'SecurityError' ? 'denied' : 'failed');
  }

  let pc: RTCPeerConnection | null = null;
  let dc: RTCDataChannel | null = null;
  let closed = false;
  let connected = false;
  let failedOnce = false;
  let events: LiveEvent[] = [];
  let transcript: TranscriptEntry[] = [];

  const close = () => {
    if (closed) return;
    closed = true;
    if (pc) pc.onconnectionstatechange = null;
    if (dc) dc.onclose = null;
    try { dc?.close(); } catch { /* already closed */ }
    try { pc?.close(); } catch { /* already closed */ }
    stream.getTracks().forEach((t) => t.stop());
    if (opts.audio) {
      try { opts.audio.pause(); } catch { /* ignore */ }
      opts.audio.srcObject = null;
    }
  };

  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const opened = new Promise<void>((resolve, reject) => {
      timer = deps.setTimeout(() => reject(new LiveError('failed')), CONNECT_TIMEOUT_MS);
      pc = deps.createPeerConnection();
      stream.getTracks().forEach((t) => pc!.addTrack(t, stream));
      pc.ontrack = (e) => {
        if (!opts.audio || closed) return;
        opts.audio.srcObject = e.streams[0] ?? null;
        try {
          Promise.resolve(opts.audio.play()).catch(() => opts.onAudioBlocked?.());
        } catch { opts.onAudioBlocked?.(); }
      };
      // the connection broke or the far side hung up while we did not close: a failure (before the connect: a failed start)
      const lost = () => {
        if (closed) return;
        if (!connected) reject(new LiveError('failed'));
        else if (!failedOnce) { failedOnce = true; opts.onFailed(); }
      };
      pc.onconnectionstatechange = () => {
        const state = pc?.connectionState;
        if (state === 'failed' || state === 'closed') lost();
      };
      dc = pc.createDataChannel('oai-events');
      dc.onclose = lost;
      dc.onopen = () => resolve();
      dc.onmessage = (e: MessageEvent) => {
        let event: LiveEvent;
        try { event = JSON.parse(String(e.data)); } catch { return; }
        if (!event || typeof event.type !== 'string' || !relevant(event)) return;
        events = [...events, event];
        const next = collectTranscript(events);
        if (JSON.stringify(next) === JSON.stringify(transcript)) return;
        transcript = next;
        opts.onTranscript(transcript);
      };
    });
    opened.catch(() => { /* handled by the race below */ });

    const negotiated = (async () => {
      const offer = await pc!.createOffer();
      await pc!.setLocalDescription(offer);
      let res: Response;
      try {
        res = await deps.fetch(`${CALLS_URL}?model=${encodeURIComponent(opts.model)}`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${opts.clientSecret}`, 'Content-Type': 'application/sdp' },
          body: offer.sdp
        });
      } catch {
        throw new LiveError('failed');
      }
      if (!res.ok) throw new LiveError('failed');
      const answer = await res.text();
      if (closed) throw new LiveError('failed');
      await pc!.setRemoteDescription({ type: 'answer', sdp: answer });
      await opened;
    })();
    await Promise.race([negotiated, opened.then(() => negotiated)]);
    if (closed) throw new LiveError('failed');
  } catch (e) {
    close();
    throw e instanceof LiveError ? e : new LiveError('failed');
  } finally {
    if (timer !== undefined) deps.clearTimeout(timer);
  }

  connected = true;
  try {
    // the interviewer greets first (the session instructions say how)
    (dc as RTCDataChannel | null)?.send(JSON.stringify({ type: 'response.create' }));
  } catch { /* the channel closed meanwhile; the state handler takes over */ }

  return {
    get transcript() { return transcript; },
    close
  };
}
