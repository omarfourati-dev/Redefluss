import { describe, expect, it } from 'vitest';
import { segments } from './highlight';

describe('segments', () => {
  it('marks wrong parts case-insensitively', () => {
    expect(segments('Ich habe den ganzen Zeit gearbeitet.', ['Den ganzen Zeit'])).toEqual([
      { text: 'Ich habe ', wrong: false }, { text: 'den ganzen Zeit', wrong: true }, { text: ' gearbeitet.', wrong: false },
    ]);
  });
  it('ignores wrongs that do not occur, empty ones and regex characters', () => {
    expect(segments('Alles gut (wirklich).', ['nicht da', '', '(wirklich'])).toEqual([
      { text: 'Alles gut ', wrong: false }, { text: '(wirklich', wrong: true }, { text: ').', wrong: false },
    ]);
    expect(segments('Hallo', ['x'])).toEqual([{ text: 'Hallo', wrong: false }]);
  });
  it('does not overlap and keeps order', () => {
    expect(segments('du muss das muss', ['du muss', 'muss'])).toEqual([
      { text: 'du muss', wrong: true }, { text: ' das ', wrong: false }, { text: 'muss', wrong: true },
    ]);
  });
  it('handles an empty transcript', () => {
    expect(segments('', ['a'])).toEqual([]);
  });
});
