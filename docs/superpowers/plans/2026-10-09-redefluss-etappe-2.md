# Redefluss Etappe 2 (Wortschatz) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every day Omar gets 5–10 new German words (article, plural, meaning, example, audio) from his themes, reviews due cards by speaking a sentence with the word, the coach grades the usage, and SM-2 schedules the next review; words he gets wrong in conversations become cards automatically.

**Architecture:** New `vocab` package in the Ktor backend: a pure SM-2 scheduler, a `VocabRepo` (Flyway V2 table `vocab_card`), two AI roles behind interfaces (`VocabGenerator`, `VocabChecker`) with OpenAI implementations that reuse a shared strict-JSON chat helper extracted from `OpenAiCoach`, and fakes for `SPEECH=fake`. A `VocabService` exposes today/due/review/audio routes; the conversation service turns vocabulary mistakes into cards. The Svelte app gets a `/wortschatz` page (today's words + review flow reusing `TalkButton`/`Recorder`) and an overview tile.

**Tech Stack:** unchanged from Etappe 1 (Kotlin 2.4.21, Ktor 3.6.0, Exposed 1.5.0 + raw SQL helpers, Flyway, Testcontainers; SvelteKit 3, Svelte 5 runes, Vitest 4.1, Playwright 1.64).

**Spec:** `docs/superpowers/specs/2026-10-08-redefluss-design.md` §3.4 (Wortschatz täglich), §3.5 (Übersicht), §5 (Limits, `vocab_card`), §8, §10 Etappe 2. Aussprache in the review is Etappe 3 – not here.

## Global Constraints

- Repo `C:\Users\ABUS Dev\redefluss`, branch `etappe-2` from `main`; merged in Task 5. Package root `de.omarfourati.redefluss`; new backend code in `vocab/`.
- All Etappe-1 Global Constraints still hold (see `docs/superpowers/plans/2026-10-08-redefluss-etappe-1.md`): German du-form UI, English code; RFC 9457 problems; never log bodies/transcripts/keys; upstream failure → 502, timeout → 504 with „Der Sprachdienst antwortet gerade nicht. Bitte versuch es noch einmal.“; commits with `git -c user.name="Omar Fourati"` + trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Established conventions (binding, from Etappe 1): web imports `#lib/<module>` for `.ts`/`.svelte.ts` under `web/src/lib`, relative imports for `.svelte` components; `npm run check` and `npm run build` with 0 warnings; Problem bodies always have `{type,title,status,detail}`; the production `HttpClient` comes from `openAiHttpClient()`; never restart Docker Desktop, other projects' containers use ports 5432/8080/8081/8090 → local stack `POSTGRES_PORT=55434 APP_PORT=18082`; Gradle needs `export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/tmp -Djavax.net.ssl.trustStoreType=Windows-ROOT"`.
- New env vars: `VOCAB_PER_DAY` (default 7, allowed 5–10, clamp), `VOCAB_REVIEWS_PER_DAY` (default 60).
- Themes (exact codes): `it` (IT und Bewerbung), `alltag` (Alltag), `redewendung` (Redewendungen). Card sources: `daily`, `mistake`.
- SM-2 exactly: grade 0–5; grade < 3 → reps = 0, interval = 1; else reps += 1, interval = 1 (reps 1), 6 (reps 2), else round(interval × ease); ease' = max(1.3, ease + 0.1 − (5 − g) × (0.08 + (5 − g) × 0.02)); start ease 2.5; due = today + interval.
- Reviews and generated words count against cost limits: one generation per day (`usage_day.vocab_generated`), at most `VOCAB_REVIEWS_PER_DAY` AI-checked reviews per day (counted in `usage_day.pronunciations`? **no** – new column `vocab_reviews` in V2).
- Words never duplicate: unique `word_key` = lowercase word without article, trimmed.

## Review Focus

1. **The AI returns a word that already exists** (or the same word twice in one batch, with/without article): no duplicate cards, no crash, the day still gets the remaining words – pinned in Task 1 (`insertIfNew`) and Task 3 (generation test).
2. **Opening /wortschatz twice on the same day / in two tabs at once**: words are generated only once – pinned in Task 3 (atomic `tryCountVocabGeneration` + test calling `today()` twice).
3. **A review answer that does not contain the word at all, or an empty recording**: grade ≤ 2 with a clear feedback, 422 for empty speech – never a 500 – pinned in Task 3.
4. **No cards due**: the review screen shows „Alles wiederholt – morgen geht's weiter“, not an empty card – pinned in Task 4.
5. **Day boundary in Europe/Berlin** (late-night review after 23:00 UTC+2): due dates and "today" use the clock's zone – pinned in Task 3 with TEST_CLOCK in Berlin.

---

### Task 1: SM-2 scheduler, V2 migration and VocabRepo

**Files:**
- Create: `src/main/kotlin/de/omarfourati/redefluss/vocab/Sm2.kt`, `vocab/VocabRepo.kt`, `src/main/resources/db/migration/V2__vocab.sql`
- Modify: `db/Usage.kt` (vocab counters), `src/test/kotlin/.../TestDb.kt` (TRUNCATE also `vocab_card`)
- Test: `vocab/Sm2Test.kt`, `vocab/VocabRepoTest.kt`, extend `db/RepositoriesTest.kt` (usage counters)

**Interfaces – Produces:**
```kotlin
data class Schedule(val ease: Double, val intervalDays: Int, val reps: Int)
object Sm2 { const val START_EASE = 2.5; fun next(prev: Schedule, grade: Int): Schedule }
data class NewCard(val word: String, val article: String, val plural: String, val meaning: String,
                   val example: String, val theme: String, val source: String)
data class Card(val id: Long, val word: String, val article: String, val plural: String, val meaning: String,
                val example: String, val theme: String, val source: String, val ease: Double, val intervalDays: Int,
                val reps: Int, val dueOn: LocalDate, val createdOn: LocalDate, val lastGrade: Int?)
class VocabRepo(db: Db) {
  suspend fun insertIfNew(card: NewCard, today: LocalDate): Card?     // null when word_key exists
  suspend fun createdOn(day: LocalDate): List<Card>                  // today's new daily cards, oldest first
  suspend fun due(today: LocalDate, limit: Int = 30): List<Card>      // due_on <= today, oldest due first, then id
  suspend fun dueCount(today: LocalDate): Int
  suspend fun find(id: Long): Card?
  suspend fun recentWords(limit: Int = 300): List<String>            // newest first, for "do not repeat" prompts
  suspend fun schedule(id: Long, s: Schedule, grade: Int, due: LocalDate): Boolean
  companion object { fun key(word: String): String }                 // lowercase, trim, strip leading der/die/das/ein/eine
}
// UsageRepo additions:
suspend fun tryCountVocabGeneration(day: LocalDate): Boolean          // at most 1 per day, atomic
suspend fun tryCountVocabReview(day: LocalDate, limit: Int): Boolean  // atomic like tryCountTurn
```

- [ ] **Step 1: Failing tests**

`vocab/Sm2Test.kt`:
```kotlin
package de.omarfourati.redefluss.vocab

import kotlin.test.*

class Sm2Test {
    private val start = Schedule(Sm2.START_EASE, 0, 0)

    @Test fun firstReviewsFollowOneSixThenEase() {
        val a = Sm2.next(start, 5); assertEquals(1, a.intervalDays); assertEquals(1, a.reps)
        val b = Sm2.next(a, 5); assertEquals(6, b.intervalDays); assertEquals(2, b.reps)
        val c = Sm2.next(b, 5); assertEquals(Math.round(6 * b.ease).toInt(), c.intervalDays); assertEquals(3, c.reps)
    }

    @Test fun easeFormula() {
        assertEquals(2.6, Sm2.next(start, 5).ease, 1e-9)
        assertEquals(2.5, Sm2.next(start, 4).ease, 1e-9)
        assertEquals(2.36, Sm2.next(start, 3).ease, 1e-9)
    }

    @Test fun failingResetsRepsButKeepsEaseFloor() {
        val s = Sm2.next(Schedule(1.35, 20, 5), 0)
        assertEquals(0, s.reps); assertEquals(1, s.intervalDays); assertEquals(1.3, s.ease, 1e-9)
    }

    @Test fun gradeIsClamped() {
        assertEquals(Sm2.next(start, 5), Sm2.next(start, 9))
        assertEquals(Sm2.next(start, 0), Sm2.next(start, -3))
    }
}
```

`vocab/VocabRepoTest.kt`:
```kotlin
package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.TestDb
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.*

class VocabRepoTest {
    private val repo = VocabRepo(TestDb.reset())
    private val today = LocalDate.of(2026, 10, 9)
    private fun card(word: String, article: String = "die") =
        NewCard(word, article, "-en", "Bedeutung von $word", "Beispiel mit $word.", "it", "daily")

    @Test fun keyIgnoresCaseSpacesAndArticles() {
        assertEquals("kündigungsfrist", VocabRepo.key("  Die Kündigungsfrist "))
        assertEquals("ansprechpartner", VocabRepo.key("der Ansprechpartner"))
        assertEquals("etwas in angriff nehmen", VocabRepo.key("etwas in Angriff nehmen"))
    }

    @Test fun insertIfNewSkipsDuplicates() = runBlocking {
        assertNotNull(repo.insertIfNew(card("Kündigungsfrist"), today))
        assertNull(repo.insertIfNew(card("die kündigungsfrist"), today))
        assertEquals(1, repo.createdOn(today).size)
        assertEquals(listOf("Kündigungsfrist"), repo.recentWords())
    }

    @Test fun dueAndSchedule() = runBlocking {
        val a = repo.insertIfNew(card("Ansprechpartner", "der"), today.minusDays(3))!!
        val b = repo.insertIfNew(card("Frist"), today)!!
        repo.insertIfNew(card("Zukunft"), today.plusDays(1))
        assertEquals(listOf(a.id, b.id), repo.due(today).map { it.id })
        assertEquals(2, repo.dueCount(today))
        assertTrue(repo.schedule(a.id, Schedule(2.6, 1, 1), 5, today.plusDays(1)))
        assertEquals(listOf(b.id), repo.due(today).map { it.id })
        val updated = repo.find(a.id)!!
        assertEquals(2.6, updated.ease, 1e-9); assertEquals(1, updated.intervalDays); assertEquals(5, updated.lastGrade)
        assertFalse(repo.schedule(999_999, Schedule(2.5, 1, 1), 3, today))
    }
}
```

Extend `db/RepositoriesTest.kt` with:
```kotlin
    @Test fun vocabCounters() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 9)
        assertTrue(usage.tryCountVocabGeneration(day))
        assertFalse(usage.tryCountVocabGeneration(day))
        assertTrue(usage.tryCountVocabReview(day, 2)); assertTrue(usage.tryCountVocabReview(day, 2))
        assertFalse(usage.tryCountVocabReview(day, 2))
    }
```
Run `./gradlew test` → compilation FAILS.

- [ ] **Step 2: Implement**

`src/main/resources/db/migration/V2__vocab.sql`:
```sql
CREATE TABLE vocab_card (
    id            BIGSERIAL PRIMARY KEY,
    word          TEXT    NOT NULL,
    word_key      TEXT    NOT NULL UNIQUE,
    article       TEXT    NOT NULL DEFAULT '',
    plural        TEXT    NOT NULL DEFAULT '',
    meaning       TEXT    NOT NULL,
    example       TEXT    NOT NULL,
    theme         TEXT    NOT NULL CHECK (theme IN ('it', 'alltag', 'redewendung')),
    source        TEXT    NOT NULL CHECK (source IN ('daily', 'mistake')),
    ease          DOUBLE PRECISION NOT NULL DEFAULT 2.5,
    interval_days INT     NOT NULL DEFAULT 0,
    reps          INT     NOT NULL DEFAULT 0,
    due_on        DATE    NOT NULL,
    created_on    DATE    NOT NULL,
    last_grade    INT
);
CREATE INDEX vocab_card_due ON vocab_card (due_on, id);
CREATE INDEX vocab_card_created ON vocab_card (created_on);

ALTER TABLE usage_day ADD COLUMN vocab_reviews INT NOT NULL DEFAULT 0;
```

`vocab/Sm2.kt`:
```kotlin
package de.omarfourati.redefluss.vocab

import kotlin.math.max
import kotlin.math.roundToInt

data class Schedule(val ease: Double, val intervalDays: Int, val reps: Int)

/** SuperMemo-2, as in Anki's ancestor: grade 0–5, failed cards start over, ease never below 1.3. */
object Sm2 {
    const val START_EASE = 2.5

    fun next(prev: Schedule, grade: Int): Schedule {
        val g = grade.coerceIn(0, 5)
        val ease = max(1.3, prev.ease + 0.1 - (5 - g) * (0.08 + (5 - g) * 0.02))
        if (g < 3) return Schedule(ease, 1, 0)
        val reps = prev.reps + 1
        val interval = when (reps) { 1 -> 1; 2 -> 6; else -> (prev.intervalDays * prev.ease).roundToInt() }
        return Schedule(ease, interval, reps)
    }
}
```
(Note: the interval uses the *previous* ease, matching the test `round(6 * b.ease)` where `b` is the schedule before the third review.)

`vocab/VocabRepo.kt` – same style as `db/Mistakes.kt` (raw SQL via `sql`/`update` helpers):
```kotlin
package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.db.Db
import de.omarfourati.redefluss.db.sql
import de.omarfourati.redefluss.db.update
import java.sql.ResultSet
import java.time.LocalDate

data class NewCard(val word: String, val article: String, val plural: String, val meaning: String,
                   val example: String, val theme: String, val source: String)
data class Card(val id: Long, val word: String, val article: String, val plural: String, val meaning: String,
                val example: String, val theme: String, val source: String, val ease: Double, val intervalDays: Int,
                val reps: Int, val dueOn: LocalDate, val createdOn: LocalDate, val lastGrade: Int?)

private const val COLS = "id, word, article, plural, meaning, example, theme, source, ease, interval_days, reps, due_on, created_on, last_grade"
private fun ResultSet.card() = Card(getLong("id"), getString("word"), getString("article"), getString("plural"),
    getString("meaning"), getString("example"), getString("theme"), getString("source"), getDouble("ease"),
    getInt("interval_days"), getInt("reps"), getObject("due_on", LocalDate::class.java),
    getObject("created_on", LocalDate::class.java), getInt("last_grade").takeUnless { wasNull() })
private fun ResultSet.cards(): List<Card> = buildList { while (next()) add(card()) }

class VocabRepo(private val db: Db) {
    companion object {
        private val ARTICLE = Regex("""^(der|die|das|ein|eine)\s+""", RegexOption.IGNORE_CASE)
        private val SPACE = Regex("""\s+""")
        fun key(word: String): String = word.trim().replace(SPACE, " ").replace(ARTICLE, "").lowercase(java.util.Locale.GERMAN)
    }

    suspend fun insertIfNew(card: NewCard, today: LocalDate): Card? = db.tx {
        sql("""
            INSERT INTO vocab_card (word, word_key, article, plural, meaning, example, theme, source, due_on, created_on)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (word_key) DO NOTHING
            RETURNING $COLS
        """.trimIndent(), card.word.trim().replace(ARTICLE, ""), key(card.word), card.article.trim(), card.plural.trim(),
            card.meaning.trim(), card.example.trim(), card.theme, card.source, today, today) { if (it.next()) it.card() else null }
    }

    suspend fun createdOn(day: LocalDate): List<Card> =
        db.tx { sql("SELECT $COLS FROM vocab_card WHERE created_on = ? AND source = 'daily' ORDER BY id", day) { it.cards() } }

    suspend fun due(today: LocalDate, limit: Int = 30): List<Card> =
        db.tx { sql("SELECT $COLS FROM vocab_card WHERE due_on <= ? ORDER BY due_on, id LIMIT ?", today, limit) { it.cards() } }

    suspend fun dueCount(today: LocalDate): Int =
        db.tx { sql("SELECT count(*) FROM vocab_card WHERE due_on <= ?", today) { rs -> rs.next(); rs.getInt(1) } }

    suspend fun find(id: Long): Card? = db.tx { sql("SELECT $COLS FROM vocab_card WHERE id = ?", id) { if (it.next()) it.card() else null } }

    suspend fun recentWords(limit: Int = 300): List<String> =
        db.tx { sql("SELECT word FROM vocab_card ORDER BY id DESC LIMIT ?", limit) { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    suspend fun schedule(id: Long, s: Schedule, grade: Int, due: LocalDate): Boolean = db.tx {
        update("UPDATE vocab_card SET ease = ?, interval_days = ?, reps = ?, last_grade = ?, due_on = ? WHERE id = ?",
            s.ease, s.intervalDays, s.reps, grade, due, id) == 1
    }
}
```
(The stored `word` has no leading article – the article lives in its own column.)

`db/Usage.kt` additions:
```kotlin
    /** One word generation per day – atomic, so two tabs opening /wortschatz at once generate only once. */
    suspend fun tryCountVocabGeneration(day: LocalDate): Boolean = db.tx {
        sql("""
            INSERT INTO usage_day (day, vocab_generated) VALUES (?, 1)
            ON CONFLICT (day) DO UPDATE SET vocab_generated = usage_day.vocab_generated + 1 WHERE usage_day.vocab_generated < 1
            RETURNING vocab_generated
        """.trimIndent(), day) { it.next() }
    }

    suspend fun tryCountVocabReview(day: LocalDate, limit: Int): Boolean {
        if (limit <= 0) return false
        return db.tx {
            sql("""
                INSERT INTO usage_day (day, vocab_reviews) VALUES (?, 1)
                ON CONFLICT (day) DO UPDATE SET vocab_reviews = usage_day.vocab_reviews + 1 WHERE usage_day.vocab_reviews < ?
                RETURNING vocab_reviews
            """.trimIndent(), day, limit) { it.next() }
        }
    }
```
Also extend `activeDaysSince` so a day with only vocabulary reviews counts for the streak: add `OR vocab_reviews > 0` to its WHERE clause. `TestDb.reset()`: `TRUNCATE app_user, mistake, practice_session, usage_day, vocab_card RESTART IDENTITY`.

- [ ] **Step 3: Run and commit** – `./gradlew test` → PASS. Commit: `feat: Wortschatz-Grundlage – SM-2, Tabelle vocab_card und Repository`.

---

### Task 2: AI roles – shared strict-JSON chat helper, VocabGenerator, VocabChecker, fakes

**Files:**
- Modify: `speech/OpenAi.kt` (extract `chatJson`, make `upstream`/`okBytes` `internal`; `OpenAiCoach` uses `chatJson` – behaviour unchanged, existing OpenAiTest stays green)
- Create: `vocab/VocabAi.kt` (interfaces + DTOs + `cleanWords`/`cleanCheck`), `vocab/OpenAiVocab.kt`, `vocab/FakeVocab.kt`
- Test: `vocab/OpenAiVocabTest.kt`, `vocab/FakeVocabTest.kt`

**Interfaces – Produces:**
```kotlin
// speech/OpenAi.kt
internal suspend fun chatJson(http: HttpClient, key: String, model: String, stage: String, timeoutMs: Long,
                              system: String, messages: List<Pair<String, String>>, schemaName: String, schema: JsonObject): String
// vocab/VocabAi.kt
@Serializable data class GeneratedWord(val word: String, val article: String, val plural: String, val meaning: String,
                                       val example: String, val theme: String)
@Serializable data class CheckResult(val grade: Int, val usedCorrectly: Boolean, val feedback: String, val corrections: List<Correction>,
                                     val better: String)
interface VocabGenerator { suspend fun generate(count: Int, avoid: List<String>, themes: List<String>): List<GeneratedWord> }
interface VocabChecker { suspend fun check(word: String, meaning: String, sentence: String): CheckResult }
data class VocabAi(val generator: VocabGenerator, val checker: VocabChecker)
fun vocabAiFor(config: Config, http: HttpClient): VocabAi
fun cleanWords(raw: List<GeneratedWord>): List<GeneratedWord>   // trims, article ∈ {der,die,das,""}, theme ∈ codes else "alltag", drops blanks, dedups by VocabRepo.key
fun cleanCheck(raw: CheckResult): CheckResult                    // grade clamped 0..5, corrections cleaned like cleanReply, max 4
```

- [ ] **Step 1: Failing tests** – `vocab/OpenAiVocabTest.kt` (MockEngine, same helpers as `speech/OpenAiTest.kt`): (a) generator sends `json_schema` with `strict: true`, `name` `"daily_words"`, the avoid list (first 300) and the count in the system prompt, returns cleaned words; (b) generator retries once on invalid JSON then throws `UpstreamException("vocab_generate")`; (c) checker sends word, meaning and sentence, schema name `"vocab_check"`, returns cleaned result; (d) upstream 500 → `UpstreamException` without body text; (e) `cleanWords` drops duplicates `"die Frist"`/`"Frist"`, maps article `"Die"` → `"die"` and an unknown article to `""`, unknown theme → `"alltag"`; (f) `cleanCheck` clamps grade 7 → 5 and −1 → 0. `vocab/FakeVocabTest.kt`: `FakeVocabGenerator.generate(3, avoid = listOf("Kündigungsfrist"), …)` returns 3 words, none equal to an avoided key; `FakeVocabChecker.check("Frist", …, "Die Frist endet morgen.")` → grade 4, usedCorrectly; sentence without the word → grade 2, not usedCorrectly, feedback mentions the word.

Concrete assertions for (a):
```kotlin
    @Test fun generatorUsesStrictSchemaAndAvoidList() = runBlocking {
        val body = """{"words":[{"word":"Kündigungsfrist","article":"Die","plural":"die Kündigungsfristen","meaning":"Zeit bis zum Ende eines Vertrags","example":"Meine Kündigungsfrist beträgt drei Monate.","theme":"it"},
                       {"word":"die Kündigungsfrist","article":"die","plural":"","meaning":"x","example":"y","theme":"it"},
                       {"word":"Feierabend","article":"der","plural":"die Feierabende","meaning":"Ende des Arbeitstags","example":"Nach dem Feierabend gehe ich joggen.","theme":"weltall"}]}"""
        val gen = OpenAiVocabGenerator(client(HttpStatusCode.OK to chat(body)), "sk", "gpt-4o-mini")
        val words = gen.generate(7, avoid = listOf("Ansprechpartner"), themes = listOf("it", "alltag", "redewendung"))
        assertEquals(listOf("Kündigungsfrist", "Feierabend"), words.map { it.word })
        assertEquals("die", words[0].article); assertEquals("alltag", words[1].theme)
        val req = Json.parseToJsonElement(requests.single().second).jsonObject
        val format = req["response_format"]!!.jsonObject["json_schema"]!!.jsonObject
        assertEquals("daily_words", format["name"]!!.jsonPrimitive.content)
        assertTrue(format["strict"]!!.jsonPrimitive.boolean)
        assertTrue("Ansprechpartner" in req.toString() && "7" in req.toString())
    }
```

Run → FAIL.

- [ ] **Step 2: Implement**

In `speech/OpenAi.kt`: change `private suspend fun <T : Any> upstream` and `private suspend fun HttpResponse.okBytes` to `internal`; add
```kotlin
/** One strict-JSON chat completion; returns the message content (callers parse and validate it). */
internal suspend fun chatJson(http: HttpClient, key: String, model: String, stage: String, timeoutMs: Long,
                              system: String, messages: List<Pair<String, String>>, schemaName: String, schema: JsonObject): String =
    upstream(stage, timeoutMs) {
        val res = http.post("$BASE/chat/completions") {
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                put("temperature", 0.4)
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") { put("name", schemaName); put("strict", true); put("schema", schema) }
                }
                putJsonArray("messages") {
                    addJsonObject { put("role", "system"); put("content", system) }
                    messages.forEach { (role, text) -> addJsonObject { put("role", role); put("content", text) } }
                }
            }.toString())
        }
        json.parseToJsonElement(String(res.okBytes(stage, 1_000_000))).jsonObject["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
    }
```
and rewrite `OpenAiCoach.respond` to call `chatJson(http, key, model, "coach", timeoutMs, systemPrompt(input), history + user, "coach_reply", SCHEMA)` inside its existing retry loop (history roles mapped exactly as before). Run `OpenAiTest` – must stay green unchanged.

`vocab/VocabAi.kt`:
```kotlin
package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.speech.Correction
import de.omarfourati.redefluss.speech.cleanReply
import de.omarfourati.redefluss.speech.CoachReply
import io.ktor.client.*
import kotlinx.serialization.Serializable

val THEMES = listOf("it", "alltag", "redewendung")

@Serializable data class GeneratedWord(val word: String, val article: String, val plural: String, val meaning: String,
                                       val example: String, val theme: String)
@Serializable data class WordBatch(val words: List<GeneratedWord>)
@Serializable data class CheckResult(val grade: Int, val usedCorrectly: Boolean, val feedback: String,
                                     val corrections: List<Correction>, val better: String)

interface VocabGenerator { suspend fun generate(count: Int, avoid: List<String>, themes: List<String>): List<GeneratedWord> }
interface VocabChecker { suspend fun check(word: String, meaning: String, sentence: String): CheckResult }
data class VocabAi(val generator: VocabGenerator, val checker: VocabChecker)

fun vocabAiFor(config: Config, http: HttpClient): VocabAi = when (config.speech) {
    "fake" -> VocabAi(FakeVocabGenerator(), FakeVocabChecker())
    else -> VocabAi(OpenAiVocabGenerator(http, config.openAiKey!!, config.aiModel), OpenAiVocabChecker(http, config.openAiKey, config.aiModel))
}

private val ARTICLES = setOf("der", "die", "das", "")

fun cleanWords(raw: List<GeneratedWord>): List<GeneratedWord> {
    val seen = HashSet<String>()
    return raw.mapNotNull { w ->
        val word = w.word.trim().replace(Regex("""^(der|die|das)\s+""", RegexOption.IGNORE_CASE), "")
        val key = VocabRepo.key(word)
        if (word.isEmpty() || w.meaning.isBlank() || w.example.isBlank() || !seen.add(key)) return@mapNotNull null
        GeneratedWord(word, w.article.trim().lowercase().takeIf { it in ARTICLES } ?: "", w.plural.trim(),
            w.meaning.trim(), w.example.trim(), w.theme.trim().lowercase().takeIf { it in THEMES } ?: "alltag")
    }
}

fun cleanCheck(raw: CheckResult): CheckResult = CheckResult(
    grade = raw.grade.coerceIn(0, 5),
    usedCorrectly = raw.usedCorrectly,
    feedback = raw.feedback.trim(),
    corrections = cleanReply(CoachReply(raw.corrections, "x", "x")).corrections.take(4),
    better = raw.better.trim(),
)
```

`vocab/OpenAiVocab.kt` – two classes using `chatJson` with retry-once (same pattern as the coach), schemas:
- `daily_words`: `{"type":"object","additionalProperties":false,"required":["words"],"properties":{"words":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["word","article","plural","meaning","example","theme"],"properties":{"word":{"type":"string"},"article":{"type":"string","enum":["der","die","das",""]},"plural":{"type":"string"},"meaning":{"type":"string"},"example":{"type":"string"},"theme":{"type":"string","enum":["it","alltag","redewendung"]}}}}}}`
- `vocab_check`: `{"type":"object","additionalProperties":false,"required":["grade","usedCorrectly","feedback","corrections","better"],"properties":{"grade":{"type":"integer"},"usedCorrectly":{"type":"boolean"},"feedback":{"type":"string"},"corrections":{"type":"array","items":<same correction item schema as the coach>},"better":{"type":"string"}}}`
- Generator system prompt (German): „Erzeuge genau {count} nützliche deutsche Wörter oder Redewendungen für Omar (Niveau B2–C1, Full-Stack-Entwickler auf Jobsuche in Deutschland). Themen: {themes mit Erklärung}. Nomen mit Artikel (der/die/das) und Plural mit Artikel („die Fristen“), Verben im Infinitiv mit Perfekt im Feld plural („hat … gekündigt“), Redewendungen ohne Artikel. meaning: einfache deutsche Erklärung in einem Satz. example: ein natürlicher Beispielsatz aus Omars Alltag oder Bewerbung. Nicht verwenden (schon gelernt): {avoid, kommagetrennt}.“ – user message „Bitte {count} Wörter.“. Return `cleanWords(parsed.words).take(count)`. Stage name `vocab_generate`, timeout 30 s.
- Checker system prompt: „Omar übt das Wort „{word}“ ({meaning}). Bewerte seinen Satz: grade 0–5 (5 = natürlich und korrekt mit dem Wort, 3 = verständlich mit kleinen Fehlern, ≤ 2 = Wort fehlt oder falsch verwendet). feedback: 1–2 kurze Sätze auf Deutsch, freundlich, du-Form. corrections: echte Fehler wie im Gespräch (wrong exakt aus dem Satz). better: der Satz so, wie ein Muttersprachler ihn sagen würde.“ – user message = sentence. Return `cleanCheck(parsed)`. Stage `vocab_check`, timeout 30 s.

`vocab/FakeVocab.kt`:
```kotlin
package de.omarfourati.redefluss.vocab

/** SPEECH=fake: fixed word list and a simple "does the sentence contain the word" check. */
class FakeVocabGenerator : VocabGenerator {
    private val pool = listOf(
        GeneratedWord("Kündigungsfrist", "die", "die Kündigungsfristen", "Zeit bis ein Vertrag endet", "Meine Kündigungsfrist beträgt drei Monate.", "it"),
        GeneratedWord("Ansprechpartner", "der", "die Ansprechpartner", "Person, an die man sich wendet", "Wer ist mein Ansprechpartner im Team?", "it"),
        GeneratedWord("Feierabend", "der", "die Feierabende", "Ende des Arbeitstags", "Nach dem Feierabend gehe ich joggen.", "alltag"),
        GeneratedWord("etwas in Angriff nehmen", "", "", "mit etwas beginnen", "Morgen nehme ich das Projekt in Angriff.", "redewendung"),
        GeneratedWord("Gehaltsvorstellung", "die", "die Gehaltsvorstellungen", "gewünschtes Gehalt", "Was ist Ihre Gehaltsvorstellung?", "it"),
        GeneratedWord("Termin", "der", "die Termine", "verabredete Zeit", "Ich habe morgen einen Termin beim Arzt.", "alltag"),
        GeneratedWord("auf dem Laufenden halten", "", "", "regelmäßig informieren", "Bitte halte mich auf dem Laufenden.", "redewendung"),
        GeneratedWord("Einarbeitung", "die", "die Einarbeitungen", "Zeit, in der man eine neue Arbeit lernt", "Die Einarbeitung dauert zwei Wochen.", "it"),
        GeneratedWord("Nebenkosten", "die", "die Nebenkosten", "Kosten zusätzlich zur Miete", "Die Nebenkosten sind im Winter höher.", "alltag"),
        GeneratedWord("unter Zeitdruck", "", "", "mit wenig Zeit", "Ich arbeite gut unter Zeitdruck.", "redewendung"),
    )
    override suspend fun generate(count: Int, avoid: List<String>, themes: List<String>): List<GeneratedWord> {
        val avoided = avoid.map(VocabRepo::key).toSet()
        return pool.filter { VocabRepo.key(it.word) !in avoided }.take(count)
    }
}

class FakeVocabChecker : VocabChecker {
    override suspend fun check(word: String, meaning: String, sentence: String): CheckResult {
        val core = VocabRepo.key(word).split(" ").maxBy { it.length }
        return if (sentence.lowercase().contains(core))
            CheckResult(4, true, "Gut – „$word“ passt hier.", emptyList(), sentence.trim())
        else
            CheckResult(2, false, "Das Wort „$word“ fehlt in deinem Satz. Versuch es noch einmal damit.", emptyList(), sentence.trim())
    }
}
```

- [ ] **Step 3: Run and commit** – `./gradlew test` → PASS (incl. unchanged OpenAiTest). Commit: `feat: KI-Rollen für Wortschatz – Wörter des Tages und Satzprüfung`.

---

### Task 3: VocabService, routes, mistakes → cards, overview, metrics

**Files:**
- Create: `vocab/VocabService.kt`, `vocab/VocabRoutes.kt`
- Modify: `config/Config.kt` (+`vocabPerDay` clamp 5..10, default 7; +`vocabReviewsPerDay` default 60), `metrics/Metrics.kt` (+`vocabReview(grade)`, +`vocabGenerated(n)`), `conversation/ConversationService.kt` (mistake → card), `overview/OverviewService.kt` (+`vocabDue`), `Module.kt` (Deps + route), `Application.kt`, `TestSupport.kt`
- Test: `vocab/VocabRoutesTest.kt`, extend `conversation/ConversationRoutesTest.kt`, `overview/OverviewRoutesTest.kt`, `config/ConfigTest.kt`

**Interfaces – Produces (JSON):**
```kotlin
@Serializable data class CardDto(val id: Long, val word: String, val article: String, val plural: String, val meaning: String,
    val example: String, val theme: String, val source: String, val dueOn: String, val intervalDays: Int, val reps: Int)
@Serializable data class TodayDto(val newCards: List<CardDto>, val dueCount: Int, val reviewsLeft: Int)
@Serializable data class ReviewResponse(val transcript: String, val grade: Int, val usedCorrectly: Boolean, val feedback: String,
    val corrections: List<Correction>, val better: String, val nextDue: String, val intervalDays: Int, val dueCount: Int)
```
- `GET /api/vocab/today` → `TodayDto`. First call of the day (atomic `tryCountVocabGeneration`) generates `vocabPerDay` words via `VocabGenerator` (avoid = `recentWords(300)`), inserts with `insertIfNew(source="daily")`, records `metrics.vocabGenerated(n)`. If the generator fails: 502/504 as usual **and the day's generation counter is not consumed** (decrement in a `catch`: `usage.undoVocabGeneration(day)` = `UPDATE usage_day SET vocab_generated = 0 WHERE day = ?`), so a retry works.
- `GET /api/vocab/due` → `List<CardDto>` (max 30).
- `GET /api/vocab/cards/{id}/audio` → `audio/mpeg` (or the fake's `audio/wav`) of `"{article} {word}. {example}"` via `Voice`; 404 for unknown id; `Cache-Control: private, max-age=86400`.
- `POST /api/vocab/cards/{id}/review` multipart: `text` **or** `audio` (same limits/types as `/api/turns`), or `skip=true` (no AI call, grade 1: „Kein Problem – die Karte kommt morgen wieder.“). Flow: card exists (404) → limit `tryCountVocabReview(today, vocabReviewsPerDay)` (429 „Tageslimit für Wiederholungen erreicht. Morgen geht's weiter.“, not for skip) → transcript (422 on empty) → `checker.check` → `Sm2.next` → `schedule(due = today + interval)` → mistakes recorded like conversation turns (`NewMistake`, example = transcript) → `metrics.vocabReview(grade)` → `ReviewResponse`.
- Conversation hook: in `ConversationService.turn`, for each correction with category `wortwahl` whose `right` has ≤ 4 words and ≤ 40 chars, `vocab.insertIfNew(NewCard(word = right, article = "", plural = "", meaning = rule, example = coach.natural, theme = "alltag", source = "mistake"), today)` (ignore null). `ConversationService` gets a `VocabRepo` constructor parameter.
- Overview: `OverviewDto` gains `vocabDue: Int` (= `dueCount(today)`).

- [ ] **Step 1: Failing tests** – `vocab/VocabRoutesTest.kt` (testApplication + TestDb + fakes, login helper like ConversationRoutesTest):
  1. `todayGeneratesOnceAndDeduplicates`: first GET → 7 new cards (fake pool, VOCAB_PER_DAY default); second GET → same 7 ids, generator called once (wrap `FakeVocabGenerator` in a counting spy); `dueCount` 7.
  2. `twoParallelTodayCallsGenerateOnce`: `coroutineScope { awaitAll(async { GET }, async { GET }) }` → total cards 7, spy count 1.
  3. `generatorFailureDoesNotBurnTheDay`: spy throws UpstreamException once → 502; next GET → 200 with 7 cards.
  4. `reviewWithTextSchedulesAndRecords`: review card „Kündigungsfrist“ with text „Meine Kündigungsfrist ist lang.“ → grade 4, intervalDays 1, nextDue = 2026-10-09 (TEST_CLOCK 2026-10-08 Berlin + 1), dueCount 6.
  5. `reviewWithoutTheWordGetsLowGrade` → grade 2, intervalDays 1, usedCorrectly false.
  6. `skipNeedsNoAiAndGivesGrade1` (checker spy not called).
  7. `emptySpeechIs422`, `unknownCardIs404`, `reviewLimitIs429` (VOCAB_REVIEWS_PER_DAY=1), `audioEndpointServesVoice` (content type from FakeVoice, 404 unknown id), `requiresLogin`.
  8. `dayBoundaryUsesBerlin`: deps with clock `2026-10-08T22:30:00Z` (= 00:30 Berlin on the 9th) → generated cards have `dueOn` "2026-10-09".
  Extend ConversationRoutesTest: a fake coach returning a `wortwahl` correction (`wrong`="machen", `right`="treffen", in „eine Entscheidung machen“) → afterwards `GET /api/vocab/due` contains a card with word „treffen“ and source „mistake“. Extend OverviewRoutesTest: `vocabDue` present. ConfigTest: VOCAB_PER_DAY=20 → 10, =2 → 5, missing → 7.

- [ ] **Step 2: Implement** `VocabService` (constructor: `VocabRepo, MistakeRepo, UsageRepo, VocabAi, Speech, Metrics, Config, Clock`) and `vocabRoutes(service)` behind `authenticate("auth")`, reusing the multipart reading code from `ConversationRoutes` (extract the shared part into a small `readTurnForm(call): TurnForm` helper in `conversation/` and use it from both routes – no duplicated multipart loop). Errors map exactly like the conversation service (UpstreamException → 502/504 with the neutral detail). Wire Deps (`vocab` appended at the end), `TestSupport.testDeps(..., vocabAi: VocabAi = VocabAi(FakeVocabGenerator(), FakeVocabChecker()))`, `Application.kt` (`vocabAiFor(config, http)` with the same `openAiHttpClient()` instance as speech). Metrics: `registry.counter("redefluss.vocab.reviews", "grade", grade.toString())`, `registry.counter("redefluss.vocab.generated").increment(n.toDouble())`.

- [ ] **Step 3: Run and commit** – `./gradlew test` → PASS. Commit: `feat: Wortschatz-API – Wörter des Tages, Wiederholung mit SM-2, Fehler werden Karten`.

---

### Task 4: Svelte – Wortschatz page, overview tile, navigation

**Files:**
- Modify: `web/src/lib/types.ts` (+`Card`, `Today`, `ReviewResponse`; `Overview.vocabDue`), `web/src/routes/+layout.svelte` (nav item „Wortschatz“ → `/wortschatz` between Gespräch and Fehler), `web/src/routes/+page.svelte` (tile „Fällige Karten: N“ linking to `/wortschatz`)
- Create: `web/src/lib/components/WordCard.svelte`, `web/src/routes/wortschatz/+page.svelte`, `web/src/lib/articles.ts` (`ARTICLE_CLASS`: der → blue, die → red, das → green text classes)
- Test: `web/src/lib/components/WordCard.test.ts`, `web/src/routes/wortschatz/wortschatz.test.ts`, extend `web/src/routes/overview.test.ts`

**Behaviour:**
- `/wortschatz` loads `GET /api/vocab/today` (shows a spinner text „Deine Wörter für heute werden vorbereitet …“ while loading – the first call may take ~10 s) and renders section **„Heute neu“** with `WordCard`s: article in its colour + word (large), plural, meaning, example in italics, 🔊 button (plays `/api/vocab/cards/{id}/audio` via the authenticated `api` client as Blob → object URL, reusing one `HTMLAudioElement`, revoked after playback).
- Button **„Wiederholen ({dueCount})“** switches to the review flow: one card at a time showing article + word + meaning (not the example), prompt „Sprich einen Satz mit diesem Wort.“, the `TalkButton` (same press/release/permission logic as the Gespräch page – extract the recording logic from `gespraech/+page.svelte` into `web/src/lib/talk.svelte.ts` (`createTalk({ onBlob, onError })` returning `{ press, release, recording, seconds, destroy }`) and use it on both pages; keep all existing gespraech tests green), „Lieber tippen“ text input, and „Weiß ich nicht“ (`skip=true`).
- After a review: show grade as 1–5 stars („★★★★☆“; grade 0 → no star), feedback, corrections (reuse the correction list markup from `TurnView` – extract a `Corrections.svelte` component used by both), „💬 Besser:“ sentence, „Nächste Wiederholung in N Tagen“ (1 → „morgen“), button „Weiter“ → next card. When no cards remain: „Alles wiederholt – morgen geht's weiter. 🎉“.
- Errors (429/502/504/422) in `role="alert"`; busy state disables all controls (one request at a time).

- [ ] **Step 1: Failing tests** – WordCard renders article with its colour class, word, plural, meaning, example, and the 🔊 button calls the audio endpoint; wortschatz page: today's cards rendered from a mocked `/api/vocab/today`; review flow with typed sentence → stars + feedback + „morgen“; skip → next card; zero due → the „Alles wiederholt“ text; busy disables „Senden“; overview tile shows `vocabDue`. Existing gespraech tests must still pass after extracting `talk.svelte.ts`.
- [ ] **Step 2: Implement** as described (Svelte 5 runes, no `{@html}`).
- [ ] **Step 3:** `npx vitest run`, `npm run check` (0/0), `npm run build`; commit `feat: Wortschatz-Seite mit Wörtern des Tages und Wiederholung`.

---

### Task 5: E2E, monitoring, README, deploy

**Files:**
- Modify: `web/e2e/redefluss.spec.ts` (+test), `README.md` (Wortschatz section, Etappe 2 done), `C:\private\server-monitoring\grafana\gen_dashboards.py` (+2 panels in the Redefluss dashboard: „Wiederholungen je Note“ `sum by (grade) (increase(redefluss_vocab_reviews_total{job="redefluss"}[1h]))`, stat „Neue Wörter (24 h)“ `sum(increase(redefluss_vocab_generated_total{job="redefluss"}[24h]))`), regenerate JSON
- [ ] **Step 1: E2E test** (rerun-safe, shared DB): log in → nav „Wortschatz“ → at least 5 word cards visible (fake pool; on a rerun the same day they are the same cards) → „Wiederholen“ → if a card is shown, „Lieber tippen“ → type a sentence that contains the shown word (read it from the card) → „Senden“ → stars and „Nächste Wiederholung“ visible → „Weiter“. Run the full suite twice against one local stack (`POSTGRES_PORT=55434 APP_PORT=18082`), both green; tear down only this project's containers.
- [ ] **Step 2:** README + monitoring commits (monitoring repo: commit „Redefluss: Wortschatz-Kennzahlen im Dashboard“).
- [ ] **Step 3: Deploy** – merge `etappe-2` → `main` (ff), push, watch CI/Deploy; push monitoring; live smoke: `GET /api/vocab/today` (real OpenAI) returns 5–10 cards with articles/examples; review one card by text → grade + nextDue; `GET /api/vocab/cards/{id}/audio` → `audio/mpeg`.
