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
}
