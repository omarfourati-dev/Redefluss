import { expect, test, type Page } from '@playwright/test';

const EMAIL = process.env.E2E_OWNER_EMAIL ?? 'owner@redefluss.test';
const PASSWORD = process.env.E2E_OWNER_PASSWORD ?? 'local-owner-password-123';

async function login(page: Page) {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('E-Mail').fill(EMAIL);
  await page.getByLabel('Passwort').fill(PASSWORD);
  await page.getByRole('button', { name: 'Anmelden' }).click();
  await expect(page.getByRole('heading', { name: 'Übersicht' })).toBeVisible();
}

test('public basics: robots, health, no index, metrics only internally reachable on the app port', async ({ request }) => {
  expect(await (await request.get('/robots.txt')).text()).toContain('Disallow: /');
  expect((await request.get('/healthz')).ok()).toBeTruthy();
  expect((await request.get('/api/overview')).status()).toBe(401);
});

test('speaking a sentence gives a correction and fills the overview', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: 'Jetzt sprechen' }).click();
  await page.getByRole('button', { name: 'Arbeit' }).click();
  const talk = page.getByRole('button', { name: /Halten und sprechen/ });
  const box = (await talk.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await expect(page.getByText(/Ich höre zu/)).toBeVisible();
  await page.waitForTimeout(1500);
  await page.mouse.up();
  await expect(page.locator('mark', { hasText: 'den ganzen Zeit' })).toBeVisible();
  await expect(page.getByText('die ganze Zeit', { exact: true })).toBeVisible();
  await expect(page.getByText('Interessant! Woran hast du genau gearbeitet?')).toBeVisible();

  await page.getByRole('link', { name: 'Übersicht' }).click();
  await expect(page.getByText('die ganze Zeit', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Fehler' }).click();
  await expect(page.getByText('„Zeit“ ist feminin: die Zeit.')).toBeVisible();
});

test('typing works too, and a quick tap is ignored', async ({ page }) => {
  await login(page);
  await page.goto('/gespraech');
  await page.getByRole('button', { name: 'Freies Thema' }).click();
  await page.getByRole('button', { name: /Halten und sprechen/ }).click(); // tap
  await expect(page.getByRole('alert')).toContainText('Zu kurz');
  await page.getByRole('button', { name: 'Lieber tippen' }).click();
  await page.getByLabel('Dein Satz').fill('Ich glaube, du muss mir helfen.');
  await page.getByRole('button', { name: 'Senden' }).click();
  await expect(page.getByText('du musst', { exact: true })).toBeVisible();
});

test('installable app: manifest, service worker, no API responses in the cache, offline start', async ({ page, context, request }) => {
  const manifest = await (await request.get('/manifest.webmanifest')).json();
  expect(manifest).toMatchObject({ short_name: 'Redefluss', start_url: '/', scope: '/', display: 'standalone' });
  for (const icon of manifest.icons as { src: string }[]) expect((await request.get(icon.src)).ok()).toBeTruthy();
  const sw = await request.get('/sw.js');
  expect(sw.headers()['cache-control']).toBe('no-cache');
  expect(await sw.text()).not.toContain('__BUILD__');

  await login(page);
  await page.evaluate(async () => navigator.serviceWorker.ready);
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Übersicht' })).toBeVisible();
  const cached = await page.evaluate(async () => {
    const paths: string[] = [];
    for (const name of await caches.keys()) for (const r of await (await caches.open(name)).keys()) paths.push(new URL(r.url).pathname);
    return paths;
  });
  expect(cached).toContain('/');
  expect(cached.filter((p) => p.startsWith('/api'))).toEqual([]);

  await context.setOffline(true);
  await page.reload();
  await expect(page.getByRole('status').filter({ hasText: 'Keine Verbindung' })).toBeVisible();
  await context.setOffline(false);
});

test('logout returns to the login page', async ({ page }) => {
  await login(page);
  await page.getByRole('button', { name: 'Abmelden' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto('/fehler');
  await expect(page).toHaveURL(/\/login$/);
});
