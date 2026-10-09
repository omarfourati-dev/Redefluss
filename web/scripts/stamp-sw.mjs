// Runs after `vite build`: stamps build/sw.js with a build id and the list of all hashed JS/CSS files.
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

function walk(dir) {
  return readdirSync(dir).flatMap((f) => { const p = join(dir, f); return statSync(p).isDirectory() ? walk(p) : [p]; });
}

export function stamp(dir = 'build') {
  const assets = walk(join(dir, '_app', 'immutable')).filter((f) => /\.(js|css)$/.test(f))
    .map((f) => '/' + relative(dir, f).split(sep).join('/')).sort();
  const file = join(dir, 'sw.js');
  const src = readFileSync(file, 'utf8');
  if (!src.includes('__BUILD__') || !src.includes('[/*__ASSETS__*/]')) throw new Error('sw.js placeholders missing');
  writeFileSync(file, src.replace('__BUILD__', String(Date.now())).replace('[/*__ASSETS__*/]', JSON.stringify(assets)));
  return assets.length;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) console.log(`sw.js stamped with ${stamp(process.argv[2])} assets`);
