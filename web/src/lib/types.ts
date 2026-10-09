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
  azureSecondsLeft: number;
  pronunciationEnabled: boolean;
  liveMinutesLeft: number;
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
export interface Quota {
  enabled: boolean;
  secondsLeft: number;
  secondsPerMonth: number;
  todayLeft: number;
}
export interface Exercise {
  /** `mistake-{n}`, `card-{id}` or `sound-{n}` */
  id: string;
  source: string;
  text: string;
  hint: string;
}
export interface Exercises {
  enabled: boolean;
  quota: Quota;
  groups: { mistakes: Exercise[]; vocab: Exercise[]; sounds: Exercise[] };
}
export interface PhonemeScore {
  phoneme: string;
  score: number;
}
export interface WordScore {
  word: string;
  /** 0–100 */
  score: number;
  /** None, Mispronunciation, Omission, Insertion, … */
  errorType: string;
  phonemes: PhonemeScore[];
}
export interface Assessment {
  recognized: string;
  accuracy: number;
  fluency: number;
  completeness: number;
  pronunciation: number;
  words: WordScore[];
}
export interface AssessResponse {
  assessment: Assessment;
  quota: Quota;
  weakWords: string[];
}
export type LiveRole = 'recruiter' | 'teamlead' | 'mix';
export interface LiveStart {
  sessionId: string;
  /** true: no WebRTC, the page runs a scripted conversation (clientSecret/expiresAt are null). */
  fake: boolean;
  clientSecret: string | null;
  expiresAt: number | null;
  model: string;
  /** Hard stop: the chosen duration + 2 minutes. */
  maxSeconds: number;
  minutes: number;
}
export interface TranscriptEntry {
  role: 'interviewer' | 'omar';
  text: string;
}
export interface AnswerFeedback {
  question: string;
  answer: string;
  feedback: string;
  better: string;
}
export interface InterviewReport {
  /** One-sentence overall verdict. */
  overall: string;
  summary: string;
  strengths: string[];
  improvements: string[];
  answers: AnswerFeedback[];
  corrections: Correction[];
}
