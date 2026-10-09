import type { Quota } from '#lib/types';

/** 11520 → „3 h 12 min“ */
export function hoursMinutes(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
}

export function quotaLine(q: Quota): string {
  const tries = q.todayLeft === 1 ? '1 Versuch' : `${q.todayLeft} Versuche`;
  return `Aussprache-Kontingent: noch ${hoursMinutes(q.secondsLeft)} diesen Monat · heute noch ${tries}`;
}
