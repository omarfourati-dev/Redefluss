export interface Correction {
  wrong: string;
  right: string;
  rule: string;
  category: string;
}
export interface TurnResponse {
  transcript: string;
  corrections: Correction[];
  natural: string;
  reply: string;
  replyAudio: string | null;
  replyAudioType: string | null;
  turnsLeft: number;
}
export interface Mistake {
  id: number;
  category: string;
  wrong: string;
  right: string;
  rule: string;
  example: string;
  count: number;
  lastSeen: string;
  resolved: boolean;
}
export interface Overview {
  streakDays: number;
  minutesToday: number;
  turnsToday: number;
  turnsLeft: number;
  topMistakes: Mistake[];
  vocabDue: number;
}
export interface Card {
  id: number;
  word: string;
  /** der/die/das, empty for words without article (e.g. idioms) */
  article: string;
  plural: string;
  meaning: string;
  example: string;
  theme: string;
  source: string;
  dueOn: string;
  intervalDays: number;
  reps: number;
}
export interface Today {
  newCards: Card[];
  dueCount: number;
  reviewsLeft: number;
}
export interface ReviewResponse {
  transcript: string;
  /** 0–5 (SM-2) */
  grade: number;
  usedCorrectly: boolean;
  feedback: string;
  corrections: Correction[];
  better: string;
  nextDue: string;
  intervalDays: number;
  dueCount: number;
}
