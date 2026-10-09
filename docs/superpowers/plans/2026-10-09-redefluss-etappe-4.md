# Redefluss Etappe 4 (Vorstellungsgespräch live) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Omar pastes a job ad, picks the interviewer role and a duration, and has a realistic spoken job interview with an AI interviewer over WebRTC (OpenAI Realtime). Afterwards he gets a report: language mistakes (into the mistake memory), feedback on each answer with a better phrasing, strengths, improvements and an overall verdict – with a hard daily cost cap.

**Architecture:** Backend `interview` package: `RealtimeSessions` mints a short-lived OpenAI client secret (`POST /v1/realtime/client_secrets`) with the interviewer instructions; the browser connects directly to OpenAI via WebRTC (`POST /v1/realtime/calls` with SDP) and collects both sides' transcripts from the data channel; at the end it posts the transcript to `POST /api/live/report`, where the coach (strict-JSON chat via `chatJson`) writes the report. A daily live budget (`usage_day.live_seconds`, 30 min) is reserved on start and settled on report. `SPEECH=fake` returns a fake session; the frontend then runs a scripted conversation without WebRTC (for local runs and e2e).

**Tech Stack:** unchanged + OpenAI Realtime API (GA): model `gpt-realtime`, transcription `gpt-4o-transcribe` (language `de`), voice `marin`.

**Spec:** `docs/superpowers/specs/2026-10-08-redefluss-design.md` §3.2 (Vorstellungsgespräch live), §2 (RealtimeSessions), §4, §5 (30 Live-Minuten), §9.

## Global Constraints

- Repo `C:\Users\ABUS Dev\redefluss`, branch `etappe-4` from `main`; backend code in `interview/`.
- All earlier constraints hold (du-form German UI, Problem details with 4 keys, no logging of bodies/transcripts/keys/query strings, neutral 502/504 detail, `#lib/...` imports for .ts + relative .svelte imports, `npm run check`/`build` 0 warnings, Docker caution – never restart Docker Desktop, local stack `POSTGRES_PORT=55434 APP_PORT=18082`, Gradle `JAVA_TOOL_OPTIONS`, commit identity + trailer, never commit `.superpowers/`).
- New env: `LIVE_MINUTES_PER_DAY` (default 30, clamp 0..120), `REALTIME_MODEL` (default `gpt-realtime`), `REALTIME_VOICE` (default `marin`).
- Durations: 10, 15 or 20 minutes only. Start reserves `minutes × 60` seconds atomically against the daily budget; 429 „Für heute sind die Live-Minuten aufgebraucht (30 Minuten). Morgen geht's weiter.“ (use the configured number). Report settles: used = min(reported seconds, reserved); the rest is given back. A session never reported keeps its full reservation (no refund without a report).
- Client hard stop: the browser ends the call at the chosen duration + 2 minutes at the latest; a warning „Noch 1 Minute“ at duration − 1 min; the interviewer is told the duration and wraps up.
- Client secret: expires after 60 s (`expires_after: {anchor: "created_at", seconds: 60}`), never logged, returned only in the start response. The OpenAI API key never reaches the browser.
- CSP: `connect-src 'self' https://api.openai.com` (needed for the SDP POST); everything else unchanged.
- Job ad ≤ 6000 characters, transcript ≤ 200 entries × ≤ 2000 characters; role ∈ `recruiter` | `teamlead` | `mix`.
- The interviewer does **not** correct Omar during the call; speaks natural Hochdeutsch at normal speed; asks one question at a time; follows up on vague answers; mixes behavioural (STAR) and technical questions fitting the job ad and Omar's profile (fixed profile text in code, no secrets).
- Report language: German; mistakes recorded with the existing categories; session stored as `practice_session` mode `interview` with `summary` = the report's one-sentence overall verdict.
- Metrics: `redefluss_live_sessions_total{outcome}` (`started`, `limit`, `upstream_error`, `reported`), `redefluss_live_seconds_total` (settled seconds).

## Review Focus

1. **The browser tab is closed mid-interview** (no report): the reservation stays used (cost cap holds), no crash on the next start – pinned in Task 1 (reserve without settle).
2. **Microphone denied / WebRTC fails / OpenAI rejects the SDP**: clear message, the reservation is given back immediately (`POST /api/live/cancel`), no dangling PeerConnection or mic – pinned in Tasks 1 + 3.
3. **Very long job ad pasted** (e.g. a whole PDF text): 400 with „Die Stellenanzeige ist zu lang (höchstens 6000 Zeichen).“ – pinned in Task 1.
4. **The interview ends after 1–2 exchanges** (Omar stops early): the report still works (short transcript), seconds settled to the real duration – pinned in Task 2.
5. **Transcript events arrive out of order / partial deltas**: only completed transcripts are kept, ordered by OpenAI `item_id` order of creation – pinned in Task 3 (`collectTranscript`).

---

### Task 1: Realtime client secret, live budget, start/cancel routes, CSP

**Files:** Create `interview/Realtime.kt` (`RealtimeSessions` interface, `OpenAiRealtime`, `FakeRealtime`, `interviewerInstructions(...)`), `interview/LiveService.kt`, `interview/LiveRoutes.kt`; modify `db/Usage.kt` (`tryReserveLive`, `settleLive`), `config/Config.kt` (+3 env vars), `http/Plugins.kt` (CSP connect-src), `metrics/Metrics.kt`, `Module.kt` (Deps `live` appended), `Application.kt`, `TestSupport.kt`, `overview/OverviewService.kt` (+`liveMinutesLeft`). Tests: `interview/RealtimeTest.kt`, `interview/LiveRoutesTest.kt`, extend `db/RepositoriesTest.kt`, `http/ServerTest.kt` (CSP), `config/ConfigTest.kt`, `overview/OverviewRoutesTest.kt`.

**Interfaces – Produces:**
```kotlin
enum class InterviewRole { recruiter, teamlead, mix }
data class LiveSetup(val jobAd: String, val role: InterviewRole, val minutes: Int, val knownMistakes: List<KnownMistake>)
@Serializable data class ClientSecret(val value: String, val expiresAt: Long)
interface RealtimeSessions { suspend fun create(setup: LiveSetup): ClientSecret? }   // null = fake mode (no WebRTC)
fun interviewerInstructions(setup: LiveSetup): String
@Serializable data class StartRequest(val jobAd: String, val role: String, val minutes: Int)
@Serializable data class StartResponse(val sessionId: String, val fake: Boolean, val clientSecret: String?, val expiresAt: Long?,
    val model: String, val maxSeconds: Int, val minutes: Int)
// UsageRepo
suspend fun tryReserveLive(day: LocalDate, seconds: Int, limitSeconds: Int): Boolean   // atomic upsert WHERE live_seconds + ? <= limit
suspend fun settleLive(day: LocalDate, refundSeconds: Int)                              // live_seconds = GREATEST(0, live_seconds - refund)
```
Routes (authenticated): `POST /api/live/session` → validate (400 texts: job ad empty „Bitte füg eine Stellenanzeige ein.“ / > 6000 / role / minutes ∉ {10,15,20}) → reserve (429) → `practice_session` mode `interview` (topic = first 80 chars of the job ad's first line) → `realtime.create` (UpstreamException → refund + 502/504) → `StartResponse` with `maxSeconds = (minutes + 2) × 60`. `POST /api/live/cancel` `{sessionId}` → refunds the whole reservation once (idempotent: a per-session flag in memory or a `practice_session.summary = 'abgebrochen'` marker; a second cancel or a cancel after a report refunds nothing) → 204.
`OpenAiRealtime.create`: `POST https://api.openai.com/v1/realtime/client_secrets`, `Authorization: Bearer <key>`, JSON
```json
{"expires_after":{"anchor":"created_at","seconds":60},
 "session":{"type":"realtime","model":"gpt-realtime","instructions":"<interviewerInstructions>",
   "audio":{"input":{"transcription":{"model":"gpt-4o-transcribe","language":"de"},"turn_detection":{"type":"server_vad"}},
            "output":{"voice":"marin"}}}}
```
response `{"value":"ek_…","expires_at":1234}` → `ClientSecret`. Use `upstream("realtime", 15_000)` + `okBytes`; never put the key or the secret into exceptions/logs. `interviewerInstructions`: German, role-specific opening („Sie sind Recruiterin bei …“ derived from the job ad; Teamlead = technical depth; mix = both), duration and wrap-up rule, profile of Omar (Full-Stack Developer bei KERAVONOS seit 03/2023, seit 10/2025 Vollzeit; Python/FastAPI, Vue/TypeScript, Java/Spring Boot, Go/Angular, Kotlin/Svelte Projekte; Arbeitserlaubnis § 18b; sucht Remote oder München), his frequent mistakes (to create natural chances, never to correct), siezen as in a real interview, no correction during the call, one question at a time, end with „Haben Sie noch Fragen an uns?“.

- [ ] **Step 1: Tests first** – RealtimeTest (MockEngine): exact URL, auth header, JSON shape incl. model/voice/transcription language/expiry 60; instructions contain the job ad, the role sentence, the minutes, „Haben Sie noch Fragen an uns?“ and a known mistake; 500 → UpstreamException without body; response parse. LiveRoutesTest: happy path (fake) → `fake=true`, `clientSecret=null`, maxSeconds 720 for 10 min, reservation 600 s visible in usage; 2× 15 min with limit 30 → second → 429; validation 400s; upstream failure refunds (spy RealtimeSessions throwing) → usage back to 0; cancel refunds once (second cancel no further refund); unknown session cancel → 404; auth required. RepositoriesTest: reserve/settle atomicity incl. a parallel reserve test (limit 1200, two 900-s reservations → exactly one OK). ServerTest: CSP contains `connect-src 'self' https://api.openai.com`. Overview: `liveMinutesLeft`.
- [ ] **Step 2: Implement.**
- [ ] **Step 3:** `./gradlew test` green; commit `feat: Live-Interview – Realtime-Schlüssel, Tagesbudget, Start und Abbruch`.

### Task 2: Report

**Files:** Create `interview/Report.kt` (DTOs, schema, `InterviewReporter` interface, `OpenAiReporter` via `chatJson`, `FakeReporter`); modify `interview/LiveService.kt` + `LiveRoutes.kt`. Tests: `interview/ReportTest.kt`, extend `LiveRoutesTest.kt`.

**Interfaces:**
```kotlin
@Serializable data class TranscriptEntry(val role: String, val text: String)          // role: interviewer | omar
@Serializable data class AnswerFeedback(val question: String, val answer: String, val feedback: String, val better: String)
@Serializable data class InterviewReport(val overall: String, val summary: String, val strengths: List<String>,
    val improvements: List<String>, val answers: List<AnswerFeedback>, val corrections: List<Correction>)
@Serializable data class ReportRequest(val sessionId: String, val seconds: Int, val transcript: List<TranscriptEntry>)
interface InterviewReporter { suspend fun report(jobAd: String, role: InterviewRole, transcript: List<TranscriptEntry>): InterviewReport }
```
`POST /api/live/report` → session must exist, be mode `interview` and not yet reported/cancelled (409 „Dieses Gespräch ist schon ausgewertet.“) → validate (≤ 200 entries, each ≤ 2000 chars, roles; empty transcript or no `omar` entry → 422 „Im Gespräch war nichts von dir zu hören – deshalb gibt es keinen Bericht.“, but still settle) → settle seconds (refund = reserved − min(seconds, reserved)) → reporter (schema `interview_report`, retry once, stage `interview_report`, timeout 60 s; the job ad comes from the server-side session cache keyed by sessionId – store it in memory at start, max 20 entries, never in the DB) → record `corrections` as mistakes (example = the answer containing `wrong`, else the first 300 chars of Omar's answers) → `practice_session.summary = overall` → metrics → `InterviewReport`. Coach system prompt (German): evaluate as an experienced German IT recruiter + language coach; `overall` one sentence verdict; `summary` 2–3 sentences; strengths/improvements 2–4 items each; per answer feedback incl. STAR structure, length, filler words and a native-sounding better answer (≤ 4 sentences); `corrections` only real German language mistakes from Omar's turns (wrong = exact excerpt).

- [ ] **Step 1: Tests first** (MockEngine for OpenAiReporter shape/strict schema/retry; routes with FakeReporter: happy path settles 5 min of a 10-min reservation → usage 300 s; 2-entry short interview works; empty/no-omar → 422 + settled; double report → 409; report after cancel → 409; corrections stored as mistakes; summary stored; transcript too long → 400).
- [ ] **Step 2: Implement.**  - [ ] **Step 3:** green; commit `feat: Bericht nach dem Live-Interview mit Feedback je Antwort und Fehler-Gedächtnis`.

### Task 3: Svelte – live client and /interview page

**Files:** Create `web/src/lib/live.ts` (WebRTC client with injectable deps: `RTCPeerConnection`, `getUserMedia`, `fetch`, timers; `collectTranscript(events)` pure), `web/src/lib/fakeLive.ts` (scripted fake conversation for `fake=true`), `web/src/routes/interview/+page.svelte`, `web/src/lib/components/InterviewReport.svelte`; modify `types.ts`, `+layout.svelte` (nav „Interview“ after Aussprache), `+page.svelte` (overview tile „Live-Minuten heute: N“). Tests: `live.test.ts`, `interview.test.ts`, `InterviewReport.test.ts`.

**Behaviour:**
- Setup form: textarea „Stellenanzeige“ (counter x/6000), role radios („Recruiter:in“, „Teamlead“, „Beide“), duration (10/15/20 min), button „Gespräch starten“; remaining live minutes today shown.
- Start: POST session → if `fake`: run `fakeLive` (two scripted interviewer questions shown as subtitles, Omar's answers typed in a text box „Antwort (Testmodus)“) → else `live.connect({clientSecret, model})`: getUserMedia (echoCancellation, noiseSuppression) → `new RTCPeerConnection()` → add mic track → data channel `oai-events` → remote track to a hidden `<audio autoplay>` → createOffer/setLocalDescription → `POST https://api.openai.com/v1/realtime/calls?model={model}` with `Authorization: Bearer {clientSecret}`, `Content-Type: application/sdp`, body offer SDP → setRemoteDescription(answer). Events: `conversation.item.input_audio_transcription.completed` (`item_id`, `transcript`) → omar; `response.output_audio_transcript.done` (`item_id`, `transcript`) → interviewer; ordering by first-seen `item_id` (`conversation.item.created`/`added` events give the order; fallback arrival order).
- Live view: timer mm:ss / chosen duration, „Noch 1 Minute“ banner, live subtitles (last 6 entries), buttons „Gespräch beenden“; hard stop at duration + 2 min.
- End (button, hard stop, or `pc` failed): close data channel + pc, stop mic tracks, pause audio → POST report `{sessionId, seconds (actual elapsed, rounded), transcript}` → show `InterviewReport` (overall big, summary, „Das lief gut“, „Daran kannst du arbeiten“, per answer: Frage, deine Antwort, Feedback, „💬 Besser:“, Sprachfehler via `Corrections.svelte`).
- Failure before connected (mic denied, SDP 4xx/5xx, timeout 15 s): POST `/api/live/cancel`, show „Die Verbindung zum Interview hat nicht geklappt – deine Minuten wurden nicht verbraucht.“ (mic denied: the existing mic hint). Navigating away mid-call (onDestroy): close everything, `navigator.sendBeacon`-free approach: fire POST report with `keepalive: true` if at least one Omar entry exists, else POST cancel with `keepalive: true`.
- [ ] **Step 1: Tests first** – `collectTranscript` ordering/partials; `live.connect` with fake RTCPeerConnection/getUserMedia/fetch: SDP POST URL/headers/body, data-channel events → transcript, close releases tracks; failure → cancel called; page: form validation (empty/too long), fake flow → report rendered; hard stop timer with fake timers; 429 alert; overview tile.
- [ ] **Step 2: Implement.**  - [ ] **Step 3:** vitest, check, build; commit `feat: Interview-Seite mit WebRTC-Live-Gespräch und Bericht`.

### Task 4: E2E, README, monitoring (no deploy)

- E2E (fake mode, rerun-safe): log in → „Interview“ → paste a short job ad → „Gespräch starten“ → answer the two scripted questions via the test text box → „Gespräch beenden“ → report visible with „Das lief gut“; the overview shows fewer live minutes. Suite twice on one local stack, both green.
- README section „Vorstellungsgespräch live“ (flow, cost cap, privacy: audio goes browser ↔ OpenAI directly, transcripts only for the report, job ad never stored), Etappe 4 done.
- Monitoring (`C:\private\server-monitoring`): panels „Live-Interviews je Ergebnis“, stat „Live-Minuten heute“ (`sum(increase(redefluss_live_seconds_total{job="redefluss"}[24h]))/60`); commit, no push.
- The controller runs the final review, merges, deploys, pushes monitoring; live smoke: `POST /api/live/session` returns a client secret (then cancel – refund), and a short real WebRTC call is left for Omar to try (it needs a microphone).
