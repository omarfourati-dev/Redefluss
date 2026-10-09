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

test('public basics: robots disallow all, health is up, the API requires a login', async ({ request }) => {
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
  const turn = page.locator('article').last(); // the newest turn; older ones from earlier runs stay above
  await expect(turn.locator('mark', { hasText: 'den ganzen Zeit' })).toBeVisible();
  await expect(turn.locator('strong', { hasText: 'die ganze Zeit' })).toBeVisible();
  await expect(turn.getByText('Interessant! Woran hast du genau gearbeitet?')).toBeVisible();

  await page.getByRole('link', { name: 'Übersicht' }).click();
  await expect(page.locator('li').filter({ hasText: 'den ganzen Zeit' }).first()).toBeVisible();
  await page.getByRole('link', { name: 'Fehler', exact: true }).click();
  await expect(page.getByText('„Zeit“ ist feminin: die Zeit.').first()).toBeVisible();
});

test('Aussprache: record a sentence, see the coloured score, open the sounds of a weak word', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: 'Aussprache', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Aussprache', level: 1 })).toBeVisible();
  await expect(page.getByText(/Aussprache-Kontingent/)).toBeVisible();
  // Earlier runs may have added mistakes/vocab sentences; the fixed sound sentence is always there.
  await page.getByRole('heading', { name: 'Schwierige Laute' }).waitFor();
  await page.getByRole('button', { name: 'Über die Brücke fahren fünf grüne Busse.', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Dein Satz' })).toContainText('Über die Brücke fahren fünf grüne Busse.');

  const talk = page.getByRole('button', { name: /Halten und sprechen/ });
  await talk.scrollIntoViewIfNeeded(); // the sentence list was scrolled to click a sound sentence
  const box = (await talk.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await expect(page.getByText(/Ich höre zu/)).toBeVisible();
  await page.waitForTimeout(1500);
  await page.mouse.up();

  const score = page.getByRole('region', { name: 'Bewertung' });
  await expect(score).toBeVisible(); // the browser converted the fake-mic recording to WAV and the fake scorer answered
  const red = score.locator('button span.text-red-700').first(); // FakeScorer: words with ü are weak
  await expect(red).toBeVisible();
  await red.click();
  await expect(page.getByRole('region', { name: /^Laute in/ })).toBeVisible();
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
  await expect(page.locator('article').last().locator('strong', { hasText: 'du musst' })).toBeVisible();
});

test('Wortschatz: words of the day, review by typing a sentence', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: 'Wortschatz', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Wortschatz' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Heute neu' })).toBeVisible({ timeout: 30_000 });
  expect(await page.locator('article').count()).toBeGreaterThanOrEqual(5);

  const review = page.getByRole('button', { name: /^Wiederholen/ });
  if (!(await review.isVisible())) return; // rerun on the same day: everything is already reviewed
  await review.click();
  await expect(page.getByText('Sprich einen Satz mit diesem Wort.')).toBeVisible();
  const word = (await page.locator('article p.text-2xl span').last().innerText()).trim();
  await page.getByRole('button', { name: 'Lieber tippen' }).click();
  await page.getByLabel('Dein Satz').fill(`Ich benutze heute das Wort ${word} in einem Satz.`);
  await page.getByRole('button', { name: 'Senden' }).click();
  await expect(page.getByLabel('Ergebnis')).toBeVisible();
  await expect(page.getByText(/Nächste Wiederholung/)).toBeVisible();
  await page.getByRole('button', { name: 'Weiter' }).click();
});

test('Vorstellungsgespräch live (Testmodus): Anzeige einfügen, zwei Fragen beantworten, Bericht, weniger Live-Minuten', async ({ page }) => {
  await login(page);
  const tile = page.getByRole('link', { name: /Live-Minuten heute/ });
  const minutesLeft = async () => Number((await tile.innerText()).match(/Live-Minuten heute: (\d+)/)![1]);
  const before = await minutesLeft();
  await page.clock.install(); // the call is timed with Date.now: a minute passes in the test without waiting for it

  await page.getByRole('link', { name: 'Interview', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Vorstellungsgespräch' })).toBeVisible();
  await page.getByLabel('Stellenanzeige').fill('Backend-Entwickler (m/w/d) Kotlin, Ktor, PostgreSQL. Du baust APIs und betreibst sie selbst.');
  await page.getByRole('radio', { name: '10 Minuten' }).check();
  await page.getByRole('button', { name: 'Gespräch starten' }).click();

  const subtitles = page.getByRole('log', { name: 'Untertitel' });
  await expect(page.getByText('Testmodus', { exact: true })).toBeVisible();
  await expect(subtitles).toContainText('Erzählen Sie mir bitte kurz etwas über sich.');
  const answer = page.getByLabel('Antwort (Testmodus)');
  await answer.fill('Ich bin Backend-Entwickler und baue seit Jahren APIs mit Kotlin.');
  await page.getByRole('button', { name: 'Antwort senden' }).click();
  await expect(subtitles).toContainText('Warum interessieren Sie sich für diese Stelle?');
  await answer.fill('Ich möchte Systeme bauen, die ich auch selbst betreibe.');
  await page.getByRole('button', { name: 'Antwort senden' }).click();
  await expect(subtitles).toContainText('Wir melden uns bei Ihnen.');

  await page.clock.fastForward('01:10'); // 70 s of talking: at least one live minute is used up, the rest of the 10 is given back
  await page.getByRole('button', { name: 'Gespräch beenden' }).click();
  await expect(page.getByRole('region', { name: 'Das lief gut' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Gesamteindruck' })).toBeVisible();

  await page.getByRole('link', { name: 'Übersicht' }).click();
  await expect(page.getByRole('heading', { name: 'Übersicht' })).toBeVisible();
  await expect(tile).toBeVisible();
  await expect.poll(minutesLeft).toBeLessThan(before);
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
