# Redefluss Etappe 3 (Aussprache) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Omar practises pronunciation: he picks a sentence (from his own corrected mistakes, his vocabulary examples, or fixed sentences for difficult German sounds), listens to it (normal or slow), records it, and Azure Speech scores every word and sound; weak words become mistakes – strictly within Azure's free F0 quota.

**Architecture:** Backend `pronunciation` package: a `PronunciationScorer` interface with an Azure REST implementation (short-audio recognition with the `Pronunciation-Assessment` header) and a fake; a WAV header validator (16 kHz, mono, 16-bit PCM, ≤ 30 s); a monthly Azure-seconds quota (atomic, Europe/Berlin calendar month, cap 16 200 s) on top of Azure's own F0 limit; a `PronunciationService` with exercise list, TTS „Vorsprechen“ (normal/slow) and assessment routes. The browser converts the recording to 16 kHz mono WAV before upload. Svelte gets an `/aussprache` page with coloured words and per-word sound details.

**Tech Stack:** unchanged (Kotlin/Ktor/Exposed/Flyway/Testcontainers; SvelteKit 3/Svelte 5/Vitest/Playwright). Azure Speech REST API for short audio, region `germanywestcentral`, language `de-DE`.

**Spec:** `docs/superpowers/specs/2026-10-08-redefluss-design.md` §3.3 (Aussprache incl. „Nur das kostenlose Kontingent“), §3.4 (Aussprache in der Wortschatz-Wiederholung → here: „Beispielsatz nachsprechen“), §5 (limits, `azure_seconds`), §9 (metrics, alert at 80 %).

## Global Constraints

- Repo `C:\Users\ABUS Dev\redefluss`, branch `etappe-3` from `main`. Backend code in `pronunciation/`.
- All earlier constraints hold (German du-form UI, RFC 9457 problems with all four keys, no logging of bodies/transcripts/keys/query strings, neutral 502/504 detail, `#lib/...` imports for .ts modules + relative .svelte imports, `npm run check`/`build` 0 warnings, Docker caution: never restart Docker Desktop, local stack on `POSTGRES_PORT=55434 APP_PORT=18082`, Gradle `JAVA_TOOL_OPTIONS`, commit identity + trailer).
- **Azure only within the free F0 quota (Omar's explicit wish):** app-side cap `AZURE_SECONDS_PER_MONTH` (default **16200** = 4,5 h) per calendar month in Europe/Berlin, counted with the real WAV duration (ceil to whole seconds) **before** the Azure call; an Azure 429/403-quota response is treated the same way (no retry). When exhausted: HTTP 429 with detail „Das kostenlose Aussprache-Kontingent für diesen Monat ist aufgebraucht – ab dem 1. geht es weiter.“; all other features keep working.
- Daily cap `PRONUNCIATIONS_PER_DAY` default **100** (column `usage_day.pronunciations`), 429 „Tageslimit für Aussprache erreicht. Morgen geht's weiter.“
- New env: `AZURE_SPEECH_KEY` (secret), `AZURE_SPEECH_REGION` (default `germanywestcentral`), `PRONUNCIATION` (`azure` | `fake`; default: `fake` when `SPEECH=fake`, else `azure`). Missing key with `PRONUNCIATION=azure` must **not** stop the app: the pronunciation routes answer 503 „Aussprache ist noch nicht eingerichtet.“ and the UI shows that text.
- Audio for assessment: WAV RIFF, PCM format 1, 1 channel, 16 000 Hz, 16 bit, duration 0.5–30 s; otherwise 400 „Die Aufnahme muss 0,5 bis 30 Sekunden lang sein.“ (duration) or 415 „Dieses Audioformat wird nicht unterstützt.“ (format). Max body 1 MB for this route.
- Score colours: ≥ 80 green, 60–79 amber, < 60 red, omitted words grey + strikethrough. Words < 60 (or ErrorType Mispronunciation/Omission) are recorded as mistakes with category `aussprache`, `wrong` = word, `right` = word, rule „Aussprache von „{word}“ üben – schwache Laute: {phonemes < 60, comma-separated}“ (or „… – das Wort fehlte“ for omissions), example = reference sentence.
- Metrics: `redefluss_pronunciations_total{outcome}` (`ok`, `quota`, `limit`, `upstream_error`, `timeout`, `bad_audio`), gauge `redefluss_azure_seconds_month` (current month's seconds, updated after each counted call and at startup).

## Review Focus

1. **iPhone/Android recordings are not WAV**: the browser must convert to 16 kHz mono PCM WAV before upload; a server-side check rejects anything else with 415, not a 502 from Azure – pinned in Task 1 (`WavInfo.parse`) and Task 3 (`encodeWav`/`toWav16k`).
2. **Quota exhausted in the middle of the month, or two clips at once near the cap**: never exceed 16 200 s; the second request gets 429 – pinned in Task 2 (atomic month counter under an advisory lock, parallel test).
3. **Azure returns `NoMatch`/`InitialSilenceTimeout`/an empty NBest** (mumbling, silence): friendly 422 „Ich habe nichts verstanden – bitte noch einmal.“, seconds still counted – pinned in Task 1 parser tests + Task 2 route test.
4. **Azure response shape differences** (scores directly in `NBest[0]` vs. under `PronunciationAssessment`; missing `Phonemes`): parser handles both and never crashes – pinned in Task 1.
5. **Month boundary in Europe/Berlin** (31st 23:30 UTC = 1st locally): the new month's quota starts fresh – pinned in Task 2 with a fixed clock.

---

### Task 1: Azure scorer, WAV validation, config

**Files:** Create `pronunciation/Wav.kt`, `pronunciation/Scorer.kt` (interface + DTOs + `parseAzure`), `pronunciation/AzureScorer.kt`, `pronunciation/FakeScorer.kt`; modify `config/Config.kt` (+`pronunciation`, `azureKey`, `azureRegion`, `azureSecondsPerMonth`, `pronunciationsPerDay`), `ConfigTest.kt`. Tests: `pronunciation/WavTest.kt`, `pronunciation/AzureScorerTest.kt`, `pronunciation/FakeScorerTest.kt`.

**Interfaces – Produces:**
```kotlin
data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataBytes: Int) {
    val seconds: Double get() = dataBytes / (sampleRate * channels * bitsPerSample / 8.0)
    companion object { fun parse(bytes: ByteArray): WavInfo? }      // null if not RIFF/WAVE PCM (format 1); walks chunks to find fmt + data
}
@Serializable data class PhonemeScore(val phoneme: String, val score: Double)
@Serializable data class WordScore(val word: String, val score: Double, val errorType: String, val phonemes: List<PhonemeScore>)
@Serializable data class Assessment(val recognized: String, val accuracy: Double, val fluency: Double, val completeness: Double,
                                    val pronunciation: Double, val words: List<WordScore>)
class NothingRecognizedException : RuntimeException("nothing recognized")
class QuotaExceededException : RuntimeException("azure quota")
interface PronunciationScorer { suspend fun assess(wav: ByteArray, reference: String): Assessment }
fun parseAzure(json: String): Assessment      // throws NothingRecognizedException for NoMatch/InitialSilenceTimeout/empty NBest
class AzureScorer(http: HttpClient, key: String, region: String, timeoutMs: Long = 15_000) : PronunciationScorer
class FakeScorer : PronunciationScorer           // deterministic, see below
```

- [ ] **Step 1: Tests first.**
  - `WavTest`: build a valid 16 kHz mono 16-bit WAV in the test (helper writing RIFF/fmt/data for N samples) → parse → sampleRate 16000, channels 1, bits 16, seconds = N/16000; a WAV with an extra `LIST` chunk before `data` parses; 44.1 kHz stereo parses with those values (validation is the service's job); non-RIFF bytes → null; format 3 (float) → null; truncated header → null.
  - `AzureScorerTest` (MockEngine): request goes to `https://germanywestcentral.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=de-DE&format=detailed`, headers `Ocp-Apim-Subscription-Key`, `Content-Type: audio/wav; codecs=audio/pcm; samplerate=16000`, `Accept: application/json`, `Pronunciation-Assessment` = base64 of JSON with `ReferenceText` (exact reference), `GradingSystem` `HundredMark`, `Granularity` `Phoneme`, `Dimension` `Comprehensive`, `EnableMiscue` true; parse response shape A (scores under `NBest[0].PronunciationAssessment` and per word `Words[i].PronunciationAssessment.{AccuracyScore,ErrorType}`, phonemes `Words[i].Phonemes[j].PronunciationAssessment.AccuracyScore`) and shape B (scores directly on NBest[0] / word / phoneme objects: `AccuracyScore`, `FluencyScore`, `CompletenessScore`, `PronScore`, `ErrorType`); missing `Phonemes` → empty list; `RecognitionStatus` `NoMatch` / `InitialSilenceTimeout` / empty NBest → `NothingRecognizedException`; HTTP 429 or 403 → `QuotaExceededException`; HTTP 500 → `UpstreamException("azure", false)` without body text; slow response beyond timeout → `UpstreamException("azure", true)`; caller cancellation rethrown (reuse `upstream()` from speech/OpenAi.kt – it is `internal`).
  - `FakeScorerTest`: every word gets score 90 except words containing „ü“, „ö“ or „ch“, which get 55 with one phoneme score 40 named after the letter; empty reference → `NothingRecognizedException`.
  - `ConfigTest`: defaults (`fake` when SPEECH=fake, `azure` otherwise; region `germanywestcentral`; 16200; 100); `PRONUNCIATION=azure` without key is allowed (key null).

- [ ] **Step 2: Implement.** Shape A example to support (abridged real Azure output):
```json
{"RecognitionStatus":"Success","NBest":[{"Display":"Guten Morgen.","PronunciationAssessment":{"AccuracyScore":91.0,"FluencyScore":88.0,"CompletenessScore":100.0,"PronScore":90.2},
 "Words":[{"Word":"guten","PronunciationAssessment":{"AccuracyScore":95.0,"ErrorType":"None"},"Phonemes":[{"Phoneme":"g","PronunciationAssessment":{"AccuracyScore":98.0}}]}]}]}
```
`recognized` = `NBest[0].Display` (or `Lexical`). Round scores to one decimal. `AzureScorer` builds the header with `Base64.getEncoder().encodeToString(json.toByteArray(UTF_8))`, posts the raw WAV bytes (`setBody(ByteArrayContent(wav, ContentType.parse("audio/wav")))` plus the explicit Content-Type header string above), caps the response at 2 MB via `okBytes`.
- [ ] **Step 3:** `./gradlew test` green; commit `feat: Aussprache-Bewertung mit Azure (REST) und WAV-Prüfung`.

---

### Task 2: Quota, PronunciationService, routes, mistakes, metrics

**Files:** Create `pronunciation/PronunciationService.kt`, `pronunciation/PronunciationRoutes.kt`, `pronunciation/Exercises.kt` (fixed sound sentences); modify `db/Usage.kt` (month quota + daily counter), `metrics/Metrics.kt`, `voice` (`Voice.speak(text, slow = false)` – OpenAiVoice passes „Sprich langsam und deutlich, Wort für Wort, für einen Deutschlerner.“ as instructions when slow; FakeVoice ignores it), `overview/OverviewService.kt` (+`azureSecondsLeft`, `pronunciationEnabled`), `Module.kt` (Deps `pronunciation` appended), `Application.kt`, `TestSupport.kt`. Tests: `pronunciation/PronunciationRoutesTest.kt`, extend `db/RepositoriesTest.kt`, `overview/OverviewRoutesTest.kt`.

**Interfaces – Produces (JSON):**
```kotlin
@Serializable data class Exercise(val id: String, val source: String, val text: String, val hint: String)   // source: mistake | vocab | sound
@Serializable data class QuotaDto(val enabled: Boolean, val secondsLeft: Int, val secondsPerMonth: Int, val todayLeft: Int)
@Serializable data class AssessResponse(val assessment: Assessment, val quota: QuotaDto, val weakWords: List<String>)
// UsageRepo:
suspend fun tryCountAzure(day: LocalDate, monthStart: LocalDate, seconds: Int, monthCap: Int, dayLimit: Int): AzureCount  // enum OK, MONTH, DAY
suspend fun azureSecondsBetween(from: LocalDate, toExclusive: LocalDate): Int
```
Routes (all `authenticate("auth")`):
- `GET /api/pronunciation/exercises` → `{ enabled, quota, groups: { mistakes: Exercise[], vocab: Exercise[], sounds: Exercise[] } }` – mistakes: top 5 open non-`aussprache` mistakes as corrected sentences (`example` with the first case-insensitive occurrence of `wrong` replaced by `right`; id `mistake-{id}`, hint = rule); vocab: up to 5 cards (due first, then newest) using `example` (id `card-{id}`, hint = `{article} {word}`); sounds: the fixed list (id `sound-{n}`).
- `POST /api/pronunciation/speak` JSON `{text, slow}` (text 1–300 chars) → audio bytes from `Voice` (counted against `TURNS_PER_DAY` via `tryCountTurn`, 429 as in conversation).
- `POST /api/pronunciation/assess` multipart `text` (reference, 1–300 chars) + `audio` (WAV) → order: 503 if disabled → WAV parse/validate (415/400, metric `bad_audio`) → `tryCountAzure(ceil(seconds))` (429 month/day, metrics `quota`/`limit`) → scorer (NothingRecognized → 422; QuotaExceeded → 429 month text, metric `quota`; Upstream → 502/504) → record weak words as `aussprache` mistakes → metrics `ok` + gauge → `AssessResponse`.
- `GET /api/pronunciation/quota` → `QuotaDto`.

Atomic month quota (`Usage.kt`):
```kotlin
enum class AzureCount { OK, MONTH, DAY }

/** One transaction under an advisory lock: month sum + this clip must stay within the cap; then count seconds and the clip. */
suspend fun tryCountAzure(day: LocalDate, monthStart: LocalDate, seconds: Int, monthCap: Int, dayLimit: Int): AzureCount = db.tx {
    sql("SELECT pg_advisory_xact_lock(4242)") { it.next() }
    val used = sql("SELECT COALESCE(SUM(azure_seconds), 0) FROM usage_day WHERE day >= ? AND day < ?",
        monthStart, monthStart.plusMonths(1)) { rs -> rs.next(); rs.getInt(1) }
    if (used + seconds > monthCap) return@tx AzureCount.MONTH
    val today = sql("SELECT pronunciations FROM usage_day WHERE day = ?", day) { if (it.next()) it.getInt(1) else 0 }
    if (today >= dayLimit) return@tx AzureCount.DAY
    update("""INSERT INTO usage_day (day, pronunciations, azure_seconds) VALUES (?, 1, ?)
              ON CONFLICT (day) DO UPDATE SET pronunciations = usage_day.pronunciations + 1,
              azure_seconds = usage_day.azure_seconds + EXCLUDED.azure_seconds""", day, seconds)
    AzureCount.OK
}
```
(`activeDaysSince` already counts `pronunciations > 0` for the streak.)

Fixed sound sentences (`Exercises.kt`, exactly these 12, hint in brackets):
1. „Über die Brücke fahren fünf grüne Busse.“ (ü/u) 2. „Können Sie mir bitte das Öl holen?“ (ö/o) 3. „Ich möchte nicht noch acht Nächte machen.“ (ich-Laut/ach-Laut) 4. „Rote Rosen riechen richtig gut.“ (r am Anfang) 5. „Der Lehrer hört immer besser zu.“ (r am Ende, -er) 6. „Die Mädchen lächeln, die Männer lachen.“ (ä/a, ch) 7. „Zwei zahme Ziegen ziehen zum Zaun.“ (z = ts) 8. „Schöne Schuhe schützen schwache Füße.“ (sch, ü) 9. „Wir wohnen in einer wunderschönen Wohnung.“ (w = v) 10. „Hast du heute Zeit für einen Kaffee?“ (Frage-Melodie) 11. „Ich spreche jeden Tag ein bisschen besser Deutsch.“ (sp/st = schp/scht) 12. „Die Bewerbung habe ich gestern abgeschickt.“ (Endungen -ung, -en, -t)

- [ ] **Step 1: Tests first** (`PronunciationRoutesTest` with FakeScorer + FakeVoice + TestDb; build test WAVs with the Task-1 helper): exercises groups (mistake sentence corrected, vocab card example, 12 sounds); speak returns audio and counts a turn; assess happy path → assessment + weakWords [„Brücke“, „grüne“, „fünf“, „Über“…] per FakeScorer and those recorded as `aussprache` mistakes (and not as vocab cards); non-WAV → 415; 44.1 kHz → 415; 0.2 s → 400; 31 s → 400; quota: `AZURE_SECONDS_PER_MONTH=10`, clip 6 s OK, second 6 s clip → 429 month text; **parallel**: cap 10, two 6-s clips concurrently → exactly one 200; daily limit 1 → second → 429 day text; NothingRecognized → 422 and seconds still counted; QuotaExceeded from scorer → 429; disabled (PRONUNCIATION=azure, no key) → 503 on all pronunciation routes, and overview `pronunciationEnabled=false`; month boundary: clock `2026-10-31T23:30:00Z` (= 1 Nov Berlin) with 16 000 s used on 2026-10-31 → a 6 s clip is OK (new month); metrics outcomes in the scrape; requires login.
- [ ] **Step 2: Implement**; `Deps` gets `pronunciation`; `Application.kt` builds `AzureScorer(openAiHttpClient(), key, region)` only when `PRONUNCIATION=azure` and the key is present, else disabled (or `FakeScorer` when `fake`); set the gauge at startup from `azureSecondsBetween(monthStart, monthStart+1)`.
- [ ] **Step 3:** `./gradlew test` green; commit `feat: Aussprache-API mit Monatskontingent, Vorsprechen und Fehler-Gedächtnis`.

---

### Task 3: Svelte – WAV conversion, /aussprache page, links

**Files:** Create `web/src/lib/wav.ts` (`encodeWav(samples: Float32Array, sampleRate: number): Blob`, `resampleLinear(input: Float32Array, fromRate: number, toRate: number): Float32Array`, `toWav16k(blob: Blob): Promise<Blob>` using `AudioContext.decodeAudioData` + mono downmix + `OfflineAudioContext(1, …, 16000)` when available, else `resampleLinear`), `web/src/lib/components/ScoreView.svelte`, `web/src/routes/aussprache/+page.svelte`; modify `types.ts`, `+layout.svelte` (nav „Aussprache“ after Wortschatz), `+page.svelte` (overview tile „Aussprache: noch X h Y min diesen Monat“ or „noch nicht eingerichtet“), `wortschatz/+page.svelte` (after a review result: link „Beispielsatz nachsprechen“ → `/aussprache?card={id}`). Tests: `wav.test.ts`, `ScoreView.test.ts`, `aussprache.test.ts`, extend overview/wortschatz tests.

**Behaviour:**
- `/aussprache`: loads exercises; three groups („Deine Fehler“, „Wortschatz“, „Schwierige Laute“) as selectable list; `?card={id}` or `?ex={id}` preselects. Selected sentence large, hint below, buttons „▶ Vorsprechen“ and „🐢 Langsam“ (POST speak, one shared audio element via the existing player from `talk.svelte.ts`), the TalkButton (max 30 s – pass a max to the recorder/talk if needed) → on blob: `toWav16k` → POST assess (multipart `text`, `audio` as `aufnahme.wav`) → `ScoreView`.
- `ScoreView`: four scores (Gesamt = pronunciation, Genauigkeit, Flüssigkeit, Vollständigkeit) as numbers 0–100; the reference sentence word by word coloured by score (classes per Global Constraints), omitted words grey strikethrough; tapping a word shows its phonemes with scores (red < 60) and the hint „Tipp: hör dir das Wort langsam an“ with a 🐢 button that speaks just that word slowly; „Nochmal“ resets for a new recording.
- Quota line: „Aussprache-Kontingent: noch 3 h 12 min diesen Monat · heute noch N Versuche“. Disabled → only the 503 text „Aussprache ist noch nicht eingerichtet.“, no recorder.
- Errors in `role="alert"`, busy guard as on the other pages.

- [ ] **Step 1: Tests first** – `encodeWav`: 44-byte header fields (RIFF size, `fmt ` PCM 1, channels 1, rate 16000, byte rate 32000, block align 2, bits 16, data size = 2 × samples), samples clipped to [-1, 1] and written little-endian; `resampleLinear` 48 kHz → 16 kHz length ⌊n/3⌋ and value interpolation; ScoreView colour classes per score, omitted word strikethrough, phoneme panel on click; page: groups rendered, preselection by `?card=`, speak buttons call the API with `slow` false/true, assess flow posts a `audio/wav` file (mock `toWav16k` via a swappable dep) and renders ScoreView, quota line, disabled state, 429 alert.
- [ ] **Step 2: Implement.**
- [ ] **Step 3:** `npx vitest run`, `npm run check` (0/0), `npm run build`; commit `feat: Aussprache-Seite mit WAV-Umwandlung, farbigen Wörtern und Lauten`.

---

### Task 4: Deploy wiring, E2E, README, monitoring

- **Deploy wiring:** `.github/workflows/deploy.yml` writes `AZURE_SPEECH_KEY` (secret) and `AZURE_SPEECH_REGION` (`vars.AZURE_SPEECH_REGION`) into `.env`; `docker-compose.prod.yml` passes `AZURE_SPEECH_KEY: ${AZURE_SPEECH_KEY:-}`, `AZURE_SPEECH_REGION: ${AZURE_SPEECH_REGION:-germanywestcentral}`, `PRONUNCIATION: azure`, `AZURE_SECONDS_PER_MONTH: "16200"`; dev compose `PRONUNCIATION: ${PRONUNCIATION:-fake}`.
- **E2E** (fake scorer, rerun-safe): log in → nav „Aussprache“ → choose „Schwierige Laute“ sentence 1 → hold the talk button 1.5 s (Chrome fake mic → browser converts to WAV) → ScoreView visible with at least one red word (FakeScorer: words with ü) → click it → phoneme panel visible. Suite twice on one local stack, both green.
- **README:** section „Aussprache“ (flow, F0-only quota, how to see the remaining quota, `PRONUNCIATION=fake` locally); Etappe 3 marked done.
- **Monitoring** (`C:\private\server-monitoring`): dashboard panels „Aussprache-Prüfungen je Ergebnis“ and stat „Azure-Kontingent (Monat)“ = `max(redefluss_azure_seconds_month{job="redefluss"}) / 16200 * 100` in percent; alert `RedeflussAzureQuota80` `max(redefluss_azure_seconds_month{job="redefluss"}) > 12960` (80 %) severity info with description „Azure-Aussprache: 80 % des kostenlosen Monatskontingents verbraucht.“; commit, do not push (controller pushes after deploy).
- Implementer does not merge/deploy; the controller runs the final review, merges, deploys, pushes monitoring and smoke-tests live with a generated WAV (Azure F0, a few seconds only).
