// Renders the PNG app icons from static/favicon.svg with Playwright (exact sizes, transparent "any" icons, full-bleed maskable).
import { chromium } from '@playwright/test';
import { readFileSync } from 'node:fs';
const svg = readFileSync('static/favicon.svg', 'utf8');
const any = (s) => `<body style="margin:0;background:transparent">${svg.replace('<svg ', `<svg width="${s}" height="${s}" style="display:block" `)}</body>`;
const mask = (s) => `<body style="margin:0;background:#0f766e;display:flex;align-items:center;justify-content:center;width:${s}px;height:${s}px">${svg.replace('<svg ', `<svg width="${Math.round(s * 0.72)}" height="${Math.round(s * 0.72)}" `)}</body>`;
const browser = await chromium.launch();
for (const [file, size, html] of [['icon-192.png', 192, any], ['icon-512.png', 512, any], ['maskable-512.png', 512, mask], ['apple-touch-icon.png', 180, mask]]) {
  const page = await browser.newPage({ viewport: { width: size, height: size } });
  await page.setContent(html(size));
  await page.screenshot({ path: `static/icons/${file}`, omitBackground: true });
  await page.close();
}
await browser.close();
