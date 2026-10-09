/** What the pronunciation API accepts: WAV, PCM 16 bit, mono, 16 kHz, at most 30 s. */
export const WAV_RATE = 16_000;
const MAX_SECONDS = 30;
const CONVERT_FAILED = 'Die Aufnahme konnte nicht umgewandelt werden.';

function ascii(view: DataView, at: number, text: string) {
  for (let i = 0; i < text.length; i++) view.setUint8(at + i, text.charCodeAt(i));
}

/** 16-bit PCM mono WAV; samples outside [-1, 1] are clipped. */
export function encodeWav(samples: Float32Array, sampleRate: number): Blob {
  const dataSize = samples.length * 2;
  const view = new DataView(new ArrayBuffer(44 + dataSize));
  ascii(view, 0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true);
  ascii(view, 8, 'WAVE');
  ascii(view, 12, 'fmt ');
  view.setUint32(16, 16, true); // fmt chunk size
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // byte rate
  view.setUint16(32, 2, true); // block align
  view.setUint16(34, 16, true); // bits per sample
  ascii(view, 36, 'data');
  view.setUint32(40, dataSize, true);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i] || 0));
    view.setInt16(44 + 2 * i, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Blob([view.buffer], { type: 'audio/wav' });
}

/** Linear interpolation resampling (fallback when OfflineAudioContext is missing). */
export function resampleLinear(input: Float32Array, fromRate: number, toRate: number): Float32Array {
  if (fromRate === toRate) return input.slice();
  const ratio = fromRate / toRate;
  const out = new Float32Array(Math.floor((input.length * toRate) / fromRate));
  for (let i = 0; i < out.length; i++) {
    const pos = i * ratio;
    const j = Math.floor(pos);
    const frac = pos - j;
    const a = input[j] ?? 0;
    const b = input[j + 1] ?? a;
    out[i] = a + (b - a) * frac;
  }
  return out;
}

function downmix(buffer: AudioBuffer): Float32Array<ArrayBuffer> {
  const mono = new Float32Array(buffer.length);
  for (let c = 0; c < buffer.numberOfChannels; c++) {
    const data = buffer.getChannelData(c);
    for (let i = 0; i < mono.length; i++) mono[i] += data[i] / buffer.numberOfChannels;
  }
  return mono;
}

type AudioContextClass = typeof AudioContext;

async function decode(bytes: ArrayBuffer): Promise<AudioBuffer> {
  const Ctx: AudioContextClass | undefined =
    globalThis.AudioContext ?? (globalThis as unknown as { webkitAudioContext?: AudioContextClass }).webkitAudioContext;
  if (!Ctx) throw new Error('no AudioContext');
  const ctx = new Ctx();
  try {
    // callback form as well: older Safari does not return a promise
    return await new Promise<AudioBuffer>((resolve, reject) => {
      const p = ctx.decodeAudioData(bytes, resolve, reject) as Promise<AudioBuffer> | undefined;
      p?.then(resolve, reject);
    });
  } finally {
    void ctx.close?.().catch(() => {});
  }
}

async function to16k(mono: Float32Array<ArrayBuffer>, rate: number): Promise<Float32Array> {
  if (rate === WAV_RATE) return mono;
  const Offline = globalThis.OfflineAudioContext;
  if (Offline) {
    try {
      const length = Math.max(1, Math.ceil((mono.length * WAV_RATE) / rate));
      const offline = new Offline(1, length, WAV_RATE);
      const source = offline.createBufferSource();
      const input = offline.createBuffer(1, mono.length, rate);
      input.copyToChannel(mono, 0);
      source.buffer = input;
      source.connect(offline.destination);
      source.start();
      return (await offline.startRendering()).getChannelData(0);
    } catch {
      /* e.g. a rate the browser cannot create a buffer for: fall through */
    }
  }
  return resampleLinear(mono, rate, WAV_RATE);
}

/** Any recording the browser can decode (webm/opus, mp4, …) → 16 kHz mono 16-bit WAV, cut to 30 s. */
export async function toWav16k(blob: Blob): Promise<Blob> {
  try {
    const decoded = await decode(await blob.arrayBuffer());
    const samples = await to16k(downmix(decoded), decoded.sampleRate);
    return encodeWav(samples.subarray(0, MAX_SECONDS * WAV_RATE), WAV_RATE);
  } catch {
    throw new Error(CONVERT_FAILED);
  }
}

/** Swappable in tests (jsdom has no AudioContext). */
export const wavDeps = { toWav16k };
