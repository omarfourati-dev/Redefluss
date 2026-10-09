import type { TranscriptEntry } from '#lib/types';

/** The scripted interviewer for test mode (`fake: true`): two questions, then a goodbye. */
export const FAKE_SCRIPT = [
  'Guten Tag, Herr Fourati. Erzählen Sie mir bitte kurz etwas über sich.',
  'Danke. Warum interessieren Sie sich für diese Stelle?',
  'Vielen Dank für das Gespräch, Herr Fourati. Wir melden uns bei Ihnen.'
] as const;

export interface FakeLive {
  readonly transcript: TranscriptEntry[];
  /** The interviewer has asked everything; further answers are still recorded. */
  readonly done: boolean;
  /** Omar's typed answer; the next scripted line follows. Blank answers are ignored. */
  answer(text: string): void;
}

/** A conversation without WebRTC: typed answers instead of the microphone, scripted questions as subtitles. Starts with the first question in `transcript`. */
export function createFakeLive(onTranscript: (entries: TranscriptEntry[]) => void): FakeLive {
  let next = 0;
  let transcript: TranscriptEntry[] = [];
  const ask = () => {
    if (next < FAKE_SCRIPT.length) transcript = [...transcript, { role: 'interviewer', text: FAKE_SCRIPT[next++] }];
  };
  ask();
  return {
    get transcript() { return transcript; },
    get done() { return next >= FAKE_SCRIPT.length; },
    answer(text: string) {
      const t = text.trim().slice(0, 2000);
      if (!t) return;
      transcript = [...transcript, { role: 'omar', text: t }];
      ask();
      onTranscript(transcript);
    }
  };
}
