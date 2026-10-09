# Redefluss

Redefluss ist ein privater Sprechtrainer für Deutsch: Gesprochene Sätze werden transkribiert, von einem KI-Coach
korrigiert und beantwortet, die Antwort wird vorgelesen. Wiederkehrende Fehler landen im Fehler-Gedächtnis und fließen
in die nächsten Runden ein. **Aufnahmen werden nie gespeichert – gespeichert werden nur Fehler (mit Beispielsatz) und
ein Tageszähler.**

**Live:** https://redefluss.omarfourati.de · ein einziges Konto (der Besitzer) · installierbar als PWA.

Ein Portfolio-Projekt von [Omar Fourati](https://omarfourati.de), gebaut mit **Kotlin/Ktor** und **Svelte 5**.
Gebaut sind bisher Etappe 1 (Gespräch, Fehler-Gedächtnis, Übersicht) und Etappe 2 (Wortschatz); die weiteren Etappen stehen unter „Ausblick“.

## Datenschutz-Ablauf: eine Runde

```
Browser ──► POST /api/sessions       (Gespräch starten, Thema optional)
Browser ──► POST /api/turns          (multipart: Aufnahme ≤ 10 MB ODER Text ≤ 1000 Zeichen, Verlauf, speak)
              ▼  Tageslimit prüfen (atomar in der Datenbank)
              Transkription (OpenAI)      Audio nur im Arbeitsspeicher – nie auf Platte, nie in der Datenbank
              ▼
              Coach (OpenAI, festes JSON-Schema): Korrekturen, natürlichere Fassung, Antwort
              ▼
              Sprachausgabe (OpenAI, mp3) – fällt sie aus, kommt die Runde trotzdem, nur ohne Ton
              ▼
              Fehler-Gedächtnis: je Korrektur ein Eintrag (falsch, richtig, Regel, Beispielsatz, Zähler)
◄──────────── transcript, corrections, natural, reply, replyAudio (Base64), turnsLeft
```

Die fünf häufigsten Fehler gibt der Server dem Coach in der nächsten Runde mit. In der Datenbank liegen nur das
Konto, die Fehler, Gesprächs-Kopfdaten und der Tageszähler (`src/main/resources/db/migration/V1__init.sql`). Der
Verlauf kommt vom Browser, wird aber auf die letzten 20 Beiträge (je 500 Zeichen) und die Rollen user/assistant
begrenzt – ein System-Prompt vom Client wird nie übernommen.

**Limits:** 300 Runden pro Tag (`TURNS_PER_DAY`, Standard 300), danach HTTP 429. Mit `SPEECH=fake` laufen Transkription,
Coach und Sprachausgabe ohne Schlüssel und ohne Kosten (lokal und in der CI); im Betrieb gilt `SPEECH=openai`.

## Wortschatz

Unter „Wortschatz“ erzeugt die KI einmal pro Tag neue Wörter (Artikel, Plural, Bedeutung, Beispielsatz) zu den Themen
IT und Bewerbung, Alltag und Redewendungen; jedes Wort kommt nur einmal vor. Die Karten lassen sich anhören.

- **Wiederholen nach SM-2:** Zu einer fälligen Karte sprichst (oder tippst) du einen Satz mit dem Wort; die KI bewertet
  ihn mit 0–5 Sternen, daraus berechnet SM-2 das nächste Fälligkeitsdatum („Weiß ich nicht“ zählt als schwache Wertung).
- **Fehler werden zu Karten:** Korrekturen aus Gesprächen und Wiederholungen können als Karte (Quelle `mistake`)
  wiederkommen, bis sie sitzen.
- **Limits:** `VOCAB_PER_DAY` (Standard 7, erlaubt 5–10) neue Wörter pro Tag, `VOCAB_REVIEWS_PER_DAY` (Standard 60)
  KI-geprüfte Wiederholungen pro Tag; danach HTTP 429.

## Kotlin-/Ktor-Konzepte im Projekt

| Konzept | Wo |
|---|---|
| Coroutines, `withTimeoutOrNull` je Stufe → `UpstreamException` (Timeout vs. Fehler) | `src/main/kotlin/de/omarfourati/redefluss/speech/OpenAi.kt` |
| Extension Functions auf `Route` / `Application` (`Route.conversationRoutes`, `Route.authRoutes`) | `…/conversation/ConversationRoutes.kt`, `…/auth/AuthRoutes.kt` |
| `@Serializable` Data Classes (kotlinx.serialization) | `…/conversation/ConversationService.kt`, `…/speech/Speech.kt` |
| Interfaces + Fakes (`Transcriber`, `Coach`, `Voice`; `SPEECH=fake`) | `…/speech/Speech.kt`, `…/speech/Fakes.kt` |
| Multipart-Upload mit Größen- und Typprüfung | `…/conversation/ConversationRoutes.kt` |
| Exposed-Transaktionen + schmaler SQL-Helfer (`sql`, `update`) für Upserts mit `RETURNING` | `…/db/Db.kt`, `…/db/Mistakes.kt` |
| Atomares Tageslimit (`INSERT … ON CONFLICT … WHERE turns < ?`) | `…/db/Usage.kt` |
| Flyway-Migrationen beim Start | `…/db/Db.kt`, `src/main/resources/db/migration/` |
| Ktor-Plugins mit `createApplicationPlugin` (Sicherheits-Header, Request-Log ohne `/healthz`, `/metrics`) | `…/http/Plugins.kt` |
| JWT-Auth, bcrypt, Login-Drosselung | `…/auth/Tokens.kt`, `…/auth/Passwords.kt`, `…/auth/LoginThrottle.kt` |
| Konfiguration aus Umgebungsvariablen mit klaren Fehlern | `…/config/Config.kt` |
| Micrometer/Prometheus-Kennzahlen (`redefluss_*`) | `…/metrics/Metrics.kt`, `…/Module.kt` |
| Einzelseiten-App aus dem Classpath, Fallback auf `index.html` | `…/http/StaticFiles.kt` |
| `testApplication`, Ktor `MockEngine`, Testcontainers (Postgres) | `src/test/kotlin/de/omarfourati/redefluss/TestSupport.kt`, `TestDb.kt`, `…/speech/OpenAiTest.kt` |

(`…` steht für `src/main/kotlin/de/omarfourati/redefluss`.)

## Svelte-5-Konzepte im Projekt

| Konzept | Wo |
|---|---|
| Runes `$state`, `$derived`, `$effect`, `$props` | `web/src/routes/gespraech/+page.svelte`, `web/src/lib/components/TurnView.svelte` |
| Klassen mit Runes in `.svelte.ts` (Anmeldung, PWA-Zustand) | `web/src/lib/auth.svelte.ts`, `web/src/lib/pwa.svelte.ts` |
| Snippets / `{@render children()}` im Layout | `web/src/routes/+layout.svelte` |
| SvelteKit als SPA mit `adapter-static` (`ssr = false`, Fallback `index.html`) | `web/vite.config.ts`, `web/src/routes/+layout.ts` |
| Pointer-Events für die Sprechtaste (halten, loslassen) | `web/src/lib/components/TalkButton.svelte` |
| `MediaRecorder` hinter einer testbaren Hülle (Mikrofon verweigert/fehlt) | `web/src/lib/recorder.ts` |
| Reine Logik getrennt und getestet (Fehlerstellen im Satz markieren, API-Client) | `web/src/lib/highlight.ts`, `web/src/lib/api.ts` |
| PWA: Manifest, eigener Service Worker, Update-Hinweis | `web/static/sw.js`, `web/static/manifest.webmanifest`, `web/scripts/stamp-sw.mjs` |

## Lokal entwickeln

Datenbank im Container, Backend mit Gradle und Fake-Sprachdiensten:

```bash
docker compose up -d postgres

DATABASE_URL=jdbc:postgresql://localhost:5432/redefluss DB_USER=redefluss DB_PASSWORD=redefluss \
JWT_SECRET=$(openssl rand -hex 32) OWNER_EMAIL=owner@redefluss.test OWNER_PASSWORD=ein-langes-lokales-passwort \
SPEECH=fake ./gradlew run
```

Frontend mit Hot Reload (Vite, `/api` wird an `:8080` weitergereicht):

```bash
cd web && npm ci && npm run dev
```

Der ganze Stack wie in der CI (App auf `:8080`, Fake-Sprachdienste):

```bash
JWT_SECRET=$(openssl rand -hex 32) docker compose --profile app up --build
```

Anmeldung lokal mit `OWNER_EMAIL` / `OWNER_PASSWORD` (Standardwerte stehen in `docker-compose.yml`, nur für lokal).

## Tests

```bash
./gradlew test                                                      # Backend (Postgres per Testcontainers, braucht Docker)
cd web && npm run check && npx vitest run                           # Svelte: Typen + Komponenten-/Logik-Tests
cd web && E2E_BASE_URL=http://localhost:8080 npx playwright test   # E2E gegen den laufenden Stack
```

- Kotlin: Konfiguration, Passwörter, Login-Drosselung, Auth-API, Gespräch (Limit, Timeout, Upstream-Fehler), Fehler-
  und Überblicks-Repositories, Serie, OpenAI-Clients gegen `MockEngine`, Fakes, Server (`/healthz`, `/metrics`,
  Header, Log), statische Dateien
- Svelte (Vitest): Auth, API-Client, Aufnahme, Markierung, Gespräch, Fehler, Konto, Login, PWA, Service-Worker-Stempel
- Playwright (`web/e2e/redefluss.spec.ts`): Grundlagen (`robots.txt`, `/healthz`, API nur mit Login), Satz sprechen mit
  Korrektur und Übersicht, Tippen und ignoriertes Kurz-Tippen, Wortschatz (Wörter des Tages, Wiederholung), PWA (Manifest, Service Worker, keine API-Antworten im
  Cache, Offline-Start), Abmelden

## App installieren (PWA)

Redefluss lässt sich auf Handy und Desktop installieren. Der Service Worker (`web/static/sw.js`) speichert nur das
App-Gerüst – **nie** etwas unter `/api`. Jeder Deploy stempelt einen neuen Service Worker
(`web/scripts/stamp-sw.mjs`), die App bietet dann „Neu laden“ an.

## Betrieb

GitHub Actions (`.github/workflows/`): `ci.yml` läuft auf GitHub-Runnern (Kotlin-Tests, Svelte-Check/-Tests/-Build,
Playwright gegen den Fake-Stack). `deploy.yml` startet bei Push auf `main` zuerst die CI und deployt danach auf dem
Self-hosted-Runner – ohne Pull-Request-Trigger, damit fremder Code dort nie läuft.

Docker Compose (`docker-compose.prod.yml`): ein Container `redefluss` mit `SPEECH=openai`, geteilte PostgreSQL-Instanz
des Servers, Netzwerk `web`; Geheimnisse kommen aus GitHub-Secrets in eine `.env` mit Rechten 600. Caddy terminiert
HTTPS. Kennzahlen unter `/metrics` sind nur im Docker-Netz erreichbar (öffentlich antwortet Caddy mit 404).
Prometheus/Grafana (Repo `server-monitoring`) haben Erreichbarkeit, Dashboard und Alarme für gehäuft fehlgeschlagene
Runden und viele gedrosselte Anmeldungen.

Passwort-Notfall: `OWNER_RESET_PASSWORD=true` zusammen mit dem neuen `OWNER_PASSWORD` setzen, einmal starten und die
Variable danach wieder entfernen – nur für Notfälle.

## Ausblick

Aus dem Entwurf `docs/superpowers/specs/2026-10-08-redefluss-design.md`; jede Etappe ist einzeln lauffähig:

- **Etappe 2 – Wortschatz (erledigt):** Wörter des Tages, Wiederholung nach SM-2, Karten-Ablauf, vollständige Übersicht
- **Etappe 3 – Aussprache:** Bewertung über Azure, ausschließlich im kostenlosen F0-Kontingent (höchstens 4,5 Stunden
  pro Monat), Laute-Anzeige, Aussprache in der Wortschatz-Wiederholung
- **Etappe 4 – Vorstellungsgespräch live:** Echtzeit-Gespräch (Realtime/WebRTC) mit Bericht und Kostenbremse
