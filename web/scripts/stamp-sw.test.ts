import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
// @ts-expect-error plain JS module
import { stamp } from './stamp-sw.mjs';

describe('stamp-sw', () => {
  it('fills build id and the hashed asset list, nothing else', () => {
    const dir = mkdtempSync(join(tmpdir(), 'sw-'));
    mkdirSync(join(dir, '_app/immutable/entry'), { recursive: true });
    writeFileSync(join(dir, '_app/immutable/entry/start.Ab1-_x9Z.js'), '');
    writeFileSync(join(dir, '_app/immutable/app.Cc2.css'), '');
    writeFileSync(join(dir, '_app/immutable/entry/readme.txt'), '');
    writeFileSync(join(dir, 'sw.js'), "const BUILD = '__BUILD__';\nconst ASSETS = [/*__ASSETS__*/];");
    expect(stamp(dir)).toBe(2);
    const out = readFileSync(join(dir, 'sw.js'), 'utf8');
    expect(out).not.toContain('__BUILD__');
    expect(out).toContain('["/_app/immutable/app.Cc2.css","/_app/immutable/entry/start.Ab1-_x9Z.js"]');
  });

  it('refuses a sw.js without placeholders', () => {
    const dir = mkdtempSync(join(tmpdir(), 'sw-'));
    mkdirSync(join(dir, '_app/immutable'), { recursive: true });
    writeFileSync(join(dir, 'sw.js'), 'already stamped');
    expect(() => stamp(dir)).toThrow('placeholders');
  });
});
