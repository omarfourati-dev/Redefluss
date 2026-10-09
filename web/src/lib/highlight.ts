export interface Segment {
  text: string;
  wrong: boolean;
}

/** Splits the transcript into plain and "wrong" parts; the coach's wrong text is matched case-insensitively, first free match. */
export function segments(transcript: string, wrongs: string[]): Segment[] {
  const lower = transcript.toLocaleLowerCase('de');
  const ranges: [number, number][] = [];
  for (const w of wrongs) {
    const needle = w.trim().toLocaleLowerCase('de');
    if (!needle) continue;
    let from = 0;
    while (from <= lower.length) {
      const at = lower.indexOf(needle, from);
      if (at < 0) break;
      const end = at + needle.length;
      if (!ranges.some(([s, e]) => at < e && end > s)) {
        ranges.push([at, end]);
        break;
      }
      from = at + 1;
    }
  }
  ranges.sort((a, b) => a[0] - b[0]);
  const out: Segment[] = [];
  let pos = 0;
  for (const [s, e] of ranges) {
    if (s > pos) out.push({ text: transcript.slice(pos, s), wrong: false });
    out.push({ text: transcript.slice(s, e), wrong: true });
    pos = e;
  }
  if (pos < transcript.length) out.push({ text: transcript.slice(pos), wrong: false });
  return out;
}
