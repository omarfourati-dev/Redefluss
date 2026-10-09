package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.*
import de.omarfourati.redefluss.db.MistakeRepo
import de.omarfourati.redefluss.speech.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class VocabRoutesTest {
    private val pw = "mein-sicheres-passwort"

    private class SpyGenerator(private var failures: Int = 0, private val delayMs: Long = 0) : VocabGenerator {
        private val inner = FakeVocabGenerator()
        val calls = AtomicInteger()
        override suspend fun generate(count: Int, avoid: List<String>, themes: List<String>): List<GeneratedWord> {
            calls.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            if (failures > 0) { failures--; throw UpstreamException("vocab_generate", timeout = false) }
            return inner.generate(count, avoid, themes)
        }
    }

    private class SpyChecker(private val extra: List<Correction> = emptyList()) : VocabChecker {
        private val inner = FakeVocabChecker()
        val calls = AtomicInteger()
        override suspend fun check(word: String, meaning: String, sentence: String): CheckResult {
            calls.incrementAndGet()
            return inner.check(word, meaning, sentence).let { it.copy(corrections = it.corrections + extra) }
        }
    }

    private class SpyVoice : Voice {
        val texts = mutableListOf<String>()
        override suspend fun speak(text: String, slow: Boolean): Audio { texts += text; return FakeVoice().speak(text) }
    }

    private fun setup(generator: VocabGenerator = SpyGenerator(), checker: VocabChecker = SpyChecker(), voice: Voice = FakeVoice(),
                      clock: Clock = TEST_CLOCK, env: Map<String, String> = emptyMap()): Deps {
        val deps = testDeps(config = testConfig(*env.toList().toTypedArray()), db = TestDb.reset(), clock = clock,
            speech = Speech(FakeTranscriber(), FakeCoach(), voice), vocabAi = VocabAi(generator, checker))
        runBlocking { deps.auth.bootstrapOwner("omar@example.de", pw, reset = false) }
        return deps
    }

    private suspend fun ApplicationTestBuilder.login(): Pair<HttpClient, String> {
        val c = createClient { install(ContentNegotiation) { json() } }
        val token = c.post("/api/auth/login") { contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "omar@example.de", "password" to pw)) }.body<JsonObject>()["token"]!!.jsonPrimitive.content
        return c to token
    }

    private suspend fun HttpClient.today(token: String) = get("/api/vocab/today") { bearerAuth(token) }

    private suspend fun HttpClient.review(token: String, id: Long, text: String? = null, audio: ByteArray? = null,
                                          type: String = "audio/webm", skip: Boolean = false) =
        submitFormWithBinaryData("/api/vocab/cards/$id/review", formData {
            text?.let { append("text", it) }
            if (skip) append("skip", "true")
            audio?.let { append("audio", it, Headers.build {
                append(HttpHeaders.ContentType, type); append(HttpHeaders.ContentDisposition, "filename=\"a\"") }) }
        }) { bearerAuth(token) }

    private fun JsonObject.cards() = this["newCards"]!!.jsonArray.map { it.jsonObject }
    private fun List<JsonObject>.idOf(word: String) = single { it["word"]!!.jsonPrimitive.content == word }["id"]!!.jsonPrimitive.long

    @Test fun todayGeneratesOnceAndDeduplicates() = testApplication {
        val generator = SpyGenerator()
        val deps = setup(generator = generator)
        application { redefluss(deps) }
        val (c, token) = login()
        val first = c.today(token)
        assertEquals(HttpStatusCode.OK, first.status)
        val body = first.body<JsonObject>()
        val cards = body.cards()
        assertEquals(7, cards.size)
        assertTrue(cards.all { it["source"]!!.jsonPrimitive.content == "daily" && it["dueOn"]!!.jsonPrimitive.content == "2026-10-08" })
        assertEquals("die", cards.single { it["word"]!!.jsonPrimitive.content == "Kündigungsfrist" }["article"]!!.jsonPrimitive.content)
        assertEquals(7, body["dueCount"]!!.jsonPrimitive.int)
        assertEquals(60, body["reviewsLeft"]!!.jsonPrimitive.int)

        val second = c.today(token).body<JsonObject>()
        assertEquals(cards.map { it["id"] }, second.cards().map { it["id"] })
        assertEquals(1, generator.calls.get())
        assertEquals(7, second["dueCount"]!!.jsonPrimitive.int)
        assertTrue("redefluss_vocab_generated_total 7.0" in deps.metrics.scrape())
    }

    @Test fun twoParallelTodayCallsGenerateOnce() = testApplication {
        val generator = SpyGenerator(delayMs = 200)
        application { redefluss(setup(generator = generator)) }
        val (c, token) = login()
        val results = coroutineScope { listOf(async { c.today(token) }, async { c.today(token) }).awaitAll() }
        assertTrue(results.all { it.status == HttpStatusCode.OK })
        assertEquals(7, c.today(token).body<JsonObject>().cards().size)
        assertEquals(7, c.get("/api/vocab/due") { bearerAuth(token) }.body<JsonArray>().size)
        assertEquals(1, generator.calls.get())
    }

    @Test fun generatorFailureDoesNotBurnTheDay() = testApplication {
        val generator = SpyGenerator(failures = 1)
        application { redefluss(setup(generator = generator)) }
        val (c, token) = login()
        val failed = c.today(token)
        assertEquals(HttpStatusCode.BadGateway, failed.status)
        assertTrue("Sprachdienst" in failed.bodyAsText())
        val retry = c.today(token)
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(7, retry.body<JsonObject>().cards().size)
        assertEquals(2, generator.calls.get())
    }

    @Test fun reviewWithTextSchedulesAndRecords() = testApplication {
        val deps = setup()
        application { redefluss(deps) }
        val (c, token) = login()
        val id = c.today(token).body<JsonObject>().cards().idOf("Kündigungsfrist")
        val res = c.review(token, id, text = "Meine Kündigungsfrist ist lang.")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.body<JsonObject>()
        assertEquals("Meine Kündigungsfrist ist lang.", body["transcript"]!!.jsonPrimitive.content)
        assertEquals(4, body["grade"]!!.jsonPrimitive.int)
        assertTrue(body["usedCorrectly"]!!.jsonPrimitive.boolean)
        assertEquals(1, body["intervalDays"]!!.jsonPrimitive.int)
        assertEquals("2026-10-09", body["nextDue"]!!.jsonPrimitive.content)
        assertEquals(6, body["dueCount"]!!.jsonPrimitive.int)
        assertTrue(body["feedback"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(59, c.today(token).body<JsonObject>()["reviewsLeft"]!!.jsonPrimitive.int)
        assertTrue("redefluss_vocab_reviews_total{grade=\"4\"} 1.0" in deps.metrics.scrape())
    }

    @Test fun reviewByAudioUsesTheTranscript() = testApplication {
        application { redefluss(setup()) }
        val (c, token) = login()
        val id = c.today(token).body<JsonObject>().cards().idOf("Feierabend")
        val res = c.review(token, id, audio = "TEXT:Nach dem Feierabend koche ich.".toByteArray(), type = "audio/mp4;codecs=mp4a.40.2")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("Nach dem Feierabend koche ich.", res.body<JsonObject>()["transcript"]!!.jsonPrimitive.content)
    }

    @Test fun reviewWithoutTheWordGetsLowGrade() = testApplication {
        application { redefluss(setup()) }
        val (c, token) = login()
        val id = c.today(token).body<JsonObject>().cards().idOf("Kündigungsfrist")
        val body = c.review(token, id, text = "Ich gehe heute einkaufen.").body<JsonObject>()
        assertEquals(2, body["grade"]!!.jsonPrimitive.int)
        assertEquals(1, body["intervalDays"]!!.jsonPrimitive.int)
        assertFalse(body["usedCorrectly"]!!.jsonPrimitive.boolean)
    }

    @Test fun reviewCorrectionsBecomeMistakes() = testApplication {
        val checker = SpyChecker(extra = listOf(Correction("ist lang", "ist sehr lang", "Betonung.", "wortwahl")))
        application { redefluss(setup(checker = checker)) }
        val (c, token) = login()
        val id = c.today(token).body<JsonObject>().cards().idOf("Kündigungsfrist")
        c.review(token, id, text = "Meine Kündigungsfrist ist lang.")
        val m = MistakeRepo(TestDb.db).top(5).single()
        assertEquals("ist lang", m.wrong)
        assertEquals("Meine Kündigungsfrist ist lang.", m.example)
    }

    @Test fun skipNeedsNoAiAndGivesGrade1() = testApplication {
        val checker = SpyChecker()
        application { redefluss(setup(checker = checker, env = mapOf("VOCAB_REVIEWS_PER_DAY" to "1"))) }
        val (c, token) = login()
        val cards = c.today(token).body<JsonObject>().cards()
        assertEquals(HttpStatusCode.OK, c.review(token, cards.idOf("Termin"), text = "Ich habe einen Termin.").status)
        val res = c.review(token, cards.idOf("Kündigungsfrist"), skip = true)
        assertEquals(HttpStatusCode.OK, res.status) // skip does not count against the review limit
        val body = res.body<JsonObject>()
        assertEquals(1, body["grade"]!!.jsonPrimitive.int)
        assertEquals("Kein Problem – die Karte kommt morgen wieder.", body["feedback"]!!.jsonPrimitive.content)
        assertEquals("2026-10-09", body["nextDue"]!!.jsonPrimitive.content)
        assertEquals(5, body["dueCount"]!!.jsonPrimitive.int)
        assertEquals(1, checker.calls.get())
    }

    @Test fun emptySpeechIs422() = testApplication {
        application { redefluss(setup()) }
        val (c, token) = login()
        val id = c.today(token).body<JsonObject>().cards().idOf("Termin")
        val res = c.review(token, id, audio = "SILENCE".toByteArray())
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
        assertTrue("nichts verstanden" in res.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, c.review(token, id).status)
        assertEquals(HttpStatusCode.BadRequest, c.review(token, id, text = "x".repeat(1001)).status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, c.review(token, id, audio = byteArrayOf(1), type = "video/mp4").status)
    }

    @Test fun unknownCardIs404() = testApplication {
        application { redefluss(setup()) }
        val (c, token) = login()
        val res = c.review(token, 999_999, text = "Hallo")
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertTrue("Karte" in res.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, c.review(token, 999_999, skip = true).status)
    }

    @Test fun reviewLimitIs429() = testApplication {
        application { redefluss(setup(env = mapOf("VOCAB_REVIEWS_PER_DAY" to "1"))) }
        val (c, token) = login()
        val id = c.today(token).body<JsonObject>().cards().idOf("Termin")
        assertEquals(HttpStatusCode.OK, c.review(token, id, text = "Ich habe einen Termin.").status)
        val res = c.review(token, id, text = "Noch ein Termin.")
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
        assertTrue("Tageslimit für Wiederholungen erreicht. Morgen geht's weiter." in res.bodyAsText())
        assertEquals(0, c.today(token).body<JsonObject>()["reviewsLeft"]!!.jsonPrimitive.int)
    }

    @Test fun audioEndpointServesVoice() = testApplication {
        val voice = SpyVoice()
        application { redefluss(setup(voice = voice)) }
        val (c, token) = login()
        val cards = c.today(token).body<JsonObject>().cards()
        val res = c.get("/api/vocab/cards/${cards.idOf("Kündigungsfrist")}/audio") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("audio/wav", res.contentType()?.withoutParameters()?.toString())
        assertEquals("private, max-age=86400", res.headers[HttpHeaders.CacheControl])
        assertTrue(res.readRawBytes().isNotEmpty())
        c.get("/api/vocab/cards/${cards.idOf("etwas in Angriff nehmen")}/audio") { bearerAuth(token) }
        assertEquals(listOf("die Kündigungsfrist. Meine Kündigungsfrist beträgt drei Monate.",
            "etwas in Angriff nehmen. Morgen nehme ich das Projekt in Angriff."), voice.texts)
        assertEquals(HttpStatusCode.NotFound, c.get("/api/vocab/cards/999999/audio") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest, c.get("/api/vocab/cards/abc/audio") { bearerAuth(token) }.status)
    }

    @Test fun requiresLogin() = testApplication {
        application { redefluss(setup()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/vocab/today").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/vocab/due").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/vocab/cards/1/audio").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/vocab/cards/1/review").status)
    }

    @Test fun dayBoundaryUsesBerlin() = testApplication {
        val clock = Clock.fixed(Instant.parse("2026-10-08T22:30:00Z"), TEST_ZONE) // 00:30 in Berlin on the 9th
        application { redefluss(setup(clock = clock)) }
        val (c, token) = login()
        val cards = c.today(token).body<JsonObject>().cards()
        assertEquals(7, cards.size)
        assertTrue(cards.all { it["dueOn"]!!.jsonPrimitive.content == "2026-10-09" })
    }
}
