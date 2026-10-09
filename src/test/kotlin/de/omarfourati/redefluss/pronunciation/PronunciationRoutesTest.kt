package de.omarfourati.redefluss.pronunciation

import de.omarfourati.redefluss.*
import de.omarfourati.redefluss.db.*
import de.omarfourati.redefluss.speech.*
import de.omarfourati.redefluss.vocab.NewCard
import de.omarfourati.redefluss.vocab.VocabRepo
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PronunciationRoutesTest {
    private val pw = "mein-sicheres-passwort"
    private val sentence = "Über die Brücke fahren fünf grüne Busse."
    private val monthText = "Das kostenlose Aussprache-Kontingent für diesen Monat ist aufgebraucht – ab dem 1. geht es weiter."
    private val busyText = "Der Aussprache-Dienst ist gerade ausgelastet oder das kostenlose Kontingent ist aufgebraucht – bitte versuch es später noch einmal."

    private class SpyVoice : Voice {
        val calls = mutableListOf<Pair<String, Boolean>>()
        override suspend fun speak(text: String, slow: Boolean): Audio { calls += text to slow; return FakeVoice().speak(text) }
    }

    /** Throws the given exception (or delegates to FakeScorer) and counts calls. */
    private class SpyScorer(private val fail: Exception? = null) : PronunciationScorer {
        val calls = AtomicInteger()
        @Volatile var lastSize = -1
        override suspend fun assess(wav: ByteArray, reference: String): Assessment {
            calls.incrementAndGet(); lastSize = wav.size
            fail?.let { throw it }
            return FakeScorer().assess(wav, reference)
        }
    }

    private fun setup(env: Map<String, String> = emptyMap(), scorer: PronunciationScorer? = SpyScorer(), voice: Voice = FakeVoice(),
                      clock: Clock = TEST_CLOCK, useDefaultScorer: Boolean = false): Deps {
        val config = testConfig(*env.toList().toTypedArray())
        val deps = if (useDefaultScorer) testDeps(config = config, db = TestDb.reset(), clock = clock, speech = Speech(FakeTranscriber(), FakeCoach(), voice))
                   else testDeps(config = config, db = TestDb.reset(), clock = clock, speech = Speech(FakeTranscriber(), FakeCoach(), voice), scorer = scorer)
        runBlocking { deps.auth.bootstrapOwner("omar@example.de", pw, reset = false) }
        return deps
    }

    private suspend fun ApplicationTestBuilder.login(): Pair<HttpClient, String> {
        val c = createClient { install(ContentNegotiation) { json() } }
        val token = c.post("/api/auth/login") { contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "omar@example.de", "password" to pw)) }.body<JsonObject>()["token"]!!.jsonPrimitive.content
        return c to token
    }

    private fun seconds(s: Double, rate: Int = 16000) = wavBytes((s * rate).toInt(), rate = rate)

    private suspend fun HttpClient.assess(token: String, text: String? = sentence, audio: ByteArray? = seconds(2.0), type: String = "audio/wav") =
        submitFormWithBinaryData("/api/pronunciation/assess", formData {
            text?.let { append("text", it) }
            audio?.let { append("audio", it, Headers.build {
                append(HttpHeaders.ContentType, type); append(HttpHeaders.ContentDisposition, "filename=\"aufnahme.wav\"") }) }
        }) { bearerAuth(token) }

    private suspend fun HttpClient.speak(token: String, text: String, slow: Boolean) = post("/api/pronunciation/speak") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("text", text); put("slow", slow) })
    }

    private suspend fun HttpResponse.detail() = body<JsonObject>()["detail"]!!.jsonPrimitive.content

    @Test fun exercisesHaveThreeGroups() = testApplication {
        val deps = setup()
        val db = TestDb.db
        runBlocking {
            val m = MistakeRepo(db)
            repeat(2) { m.record(NewMistake("artikel", "den ganzen Zeit", "die ganze Zeit", "„Zeit“ ist feminin.", "Ich habe Den ganzen Zeit gewartet."), TEST_CLOCK.instant()) }
            m.record(NewMistake("aussprache", "Brücke", "Brücke", "Aussprache von „Brücke“ üben", sentence), TEST_CLOCK.instant())
            val v = VocabRepo(db)
            val today = LocalDate.now(TEST_CLOCK)
            v.insertIfNew(NewCard("Termin", "der", "die Termine", "m", "Ich habe morgen einen Termin.", "alltag", "daily"), today.minusDays(2))
            v.insertIfNew(NewCard("Frist", "die", "die Fristen", "m", "Die Frist endet bald.", "it", "daily"), today.plusDays(3))
        }
        application { redefluss(deps) }
        val (c, token) = login()
        val res = c.get("/api/pronunciation/exercises") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.body<JsonObject>()
        assertTrue(body["enabled"]!!.jsonPrimitive.boolean)
        val quota = body["quota"]!!.jsonObject
        assertEquals(16200, quota["secondsLeft"]!!.jsonPrimitive.int)
        assertEquals(16200, quota["secondsPerMonth"]!!.jsonPrimitive.int)
        assertEquals(100, quota["todayLeft"]!!.jsonPrimitive.int)
        val groups = body["groups"]!!.jsonObject

        val mistakes = groups["mistakes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, mistakes.size) // the aussprache mistake is not offered
        assertEquals("Ich habe die ganze Zeit gewartet.", mistakes[0]["text"]!!.jsonPrimitive.content)
        assertEquals("mistake", mistakes[0]["source"]!!.jsonPrimitive.content)
        assertEquals("„Zeit“ ist feminin.", mistakes[0]["hint"]!!.jsonPrimitive.content)
        assertTrue(mistakes[0]["id"]!!.jsonPrimitive.content.startsWith("mistake-"))

        val vocab = groups["vocab"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Ich habe morgen einen Termin.", "Die Frist endet bald."), vocab.map { it["text"]!!.jsonPrimitive.content }) // due first
        assertEquals(listOf("der Termin", "die Frist"), vocab.map { it["hint"]!!.jsonPrimitive.content })
        assertEquals("card-1", vocab[0]["id"]!!.jsonPrimitive.content)
        assertTrue(vocab.all { it["source"]!!.jsonPrimitive.content == "vocab" })

        val sounds = groups["sounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(12, sounds.size)
        assertEquals(sentence, sounds[0]["text"]!!.jsonPrimitive.content)
        assertEquals("ü/u", sounds[0]["hint"]!!.jsonPrimitive.content)
        assertEquals("sound-12", sounds[11]["id"]!!.jsonPrimitive.content)
        assertEquals("Die Bewerbung habe ich gestern abgeschickt.", sounds[11]["text"]!!.jsonPrimitive.content)
        assertTrue(sounds.all { it["source"]!!.jsonPrimitive.content == "sound" })
    }

    @Test fun cardParameterAddsThatCardFirstWithoutDuplicates() = testApplication {
        val deps = setup()
        val db = TestDb.db
        val ids = runBlocking {
            val v = VocabRepo(db)
            val today = LocalDate.now(TEST_CLOCK)
            val a = v.insertIfNew(NewCard("Termin", "der", "die Termine", "m", "Ich habe morgen einen Termin.", "alltag", "daily"), today.minusDays(2))!!
            val b = v.insertIfNew(NewCard("Frist", "die", "die Fristen", "m", "Die Frist endet bald.", "it", "daily"), today.plusDays(3))!!
            val long = v.insertIfNew(NewCard("Langes", "das", "-", "m", "x".repeat(301), "it", "daily"), today.plusDays(9))!!
            Triple(a.id, b.id, long.id)
        }
        application { redefluss(deps) }
        val (c, token) = login()
        suspend fun vocab(query: String) = c.get("/api/pronunciation/exercises$query") { bearerAuth(token) }.body<JsonObject>()["groups"]!!
            .jsonObject["vocab"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

        assertEquals(listOf("card-${ids.first}", "card-${ids.second}"), vocab(""))
        assertEquals(listOf("card-${ids.second}", "card-${ids.first}"), vocab("?card=${ids.second}")) // wanted card first, no duplicate
        assertEquals(listOf("card-${ids.first}", "card-${ids.second}"), vocab("?card=${ids.first}"))
        assertEquals(listOf("card-${ids.first}", "card-${ids.second}"), vocab("?card=${ids.third}")) // too long: not offered
        assertEquals(listOf("card-${ids.first}", "card-${ids.second}"), vocab("?card=99999")) // unknown
        assertEquals(listOf("card-${ids.first}", "card-${ids.second}"), vocab("?card=abc")) // not a number
    }

    @Test fun speakReturnsAudioAndCountsATurn() = testApplication {
        val voice = SpyVoice()
        val deps = setup(voice = voice, env = mapOf("TURNS_PER_DAY" to "2"))
        application { redefluss(deps) }
        val (c, token) = login()
        val res = c.speak(token, "Brücke", slow = true)
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("audio/wav", res.contentType()?.withoutParameters()?.toString())
        assertTrue(res.readRawBytes().isNotEmpty())
        assertEquals(HttpStatusCode.OK, c.speak(token, sentence, slow = false).status)
        assertEquals(listOf("Brücke" to true, sentence to false), voice.calls)
        assertEquals(2, UsageRepo(TestDb.db).turnsOn(LocalDate.now(TEST_CLOCK)))
        val limited = c.speak(token, "Hallo", slow = false)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue("Tageslimit erreicht" in limited.detail())
        assertEquals(HttpStatusCode.BadRequest, c.speak(token, "", slow = false).status)
        assertEquals(HttpStatusCode.BadRequest, c.speak(token, "x".repeat(301), slow = false).status)
        assertEquals(HttpStatusCode.BadRequest, c.post("/api/pronunciation/speak") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("kaputt") }.status)
    }

    @Test fun assessHappyPathRecordsWeakWordsAsMistakes() = testApplication {
        val deps = setup()
        application { redefluss(deps) }
        val (c, token) = login()
        val res = c.assess(token)
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.body<JsonObject>()
        val assessment = body["assessment"]!!.jsonObject
        assertEquals(7, assessment["words"]!!.jsonArray.size)
        assertEquals(listOf("Über", "Brücke", "fünf", "grüne"), body["weakWords"]!!.jsonArray.map { it.jsonPrimitive.content })
        val quota = body["quota"]!!.jsonObject
        assertEquals(16198, quota["secondsLeft"]!!.jsonPrimitive.int)
        assertEquals(99, quota["todayLeft"]!!.jsonPrimitive.int)

        val mistakes = MistakeRepo(TestDb.db).list(MistakeStatus.ALL)
        assertEquals(setOf("Über", "Brücke", "fünf", "grüne"), mistakes.map { it.wrong }.toSet())
        assertTrue(mistakes.all { it.category == "aussprache" && it.right == it.wrong && it.example == sentence })
        assertEquals("Aussprache von „Brücke“ üben – schwache Laute: ü", mistakes.single { it.wrong == "Brücke" }.rule)
        assertEquals(0, VocabRepo(TestDb.db).dueCount(LocalDate.now(TEST_CLOCK).plusDays(10))) // never vocab cards

        val scrape = deps.metrics.scrape()
        assertTrue("redefluss_pronunciations_total{outcome=\"ok\"} 1.0" in scrape)
        assertTrue("redefluss_azure_seconds_month 2.0" in scrape)
        assertTrue("redefluss_mistakes_total{category=\"aussprache\"} 4.0" in scrape)

        val q = c.get("/api/pronunciation/quota") { bearerAuth(token) }.body<JsonObject>()
        assertEquals(16198, q["secondsLeft"]!!.jsonPrimitive.int)
        assertTrue(q["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun omittedWordsGetTheOmissionRule() = testApplication {
        val omitting = object : PronunciationScorer {
            override suspend fun assess(wav: ByteArray, reference: String) = Assessment("Guten", 50.0, 80.0, 50.0, 50.0, listOf(
                WordScore("guten", 95.0, "None", emptyList()),
                WordScore("morgen", 0.0, "Omission", emptyList()),
                WordScore("äh", 0.0, "Insertion", emptyList())))
        }
        application { redefluss(setup(scorer = omitting)) }
        val (c, token) = login()
        val body = c.assess(token, text = "Guten Morgen.").body<JsonObject>()
        assertEquals(listOf("morgen"), body["weakWords"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Aussprache von „morgen“ üben – das Wort fehlte", MistakeRepo(TestDb.db).top(5).single().rule)
    }

    @Test fun badAudioIsRejectedBeforeAnyCount() = testApplication {
        val scorer = SpyScorer()
        val deps = setup(scorer = scorer)
        application { redefluss(deps) }
        val (c, token) = login()
        val notWav = c.assess(token, audio = "das ist kein wav, sondern einfach nur text".toByteArray(), type = "audio/webm")
        assertEquals(HttpStatusCode.UnsupportedMediaType, notWav.status)
        assertEquals("Dieses Audioformat wird nicht unterstützt.", notWav.detail())
        assertEquals(HttpStatusCode.UnsupportedMediaType, c.assess(token, audio = seconds(2.0, rate = 44100)).status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, c.assess(token, audio = wavBytes(16000, channels = 2)).status)
        val short = c.assess(token, audio = seconds(0.2))
        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertEquals("Die Aufnahme muss 0,5 bis 30 Sekunden lang sein.", short.detail())
        assertEquals(HttpStatusCode.BadRequest, c.assess(token, audio = seconds(31.0)).status) // 992 KB, still under the 1 MB cap
        assertEquals(HttpStatusCode.PayloadTooLarge, c.assess(token, audio = seconds(40.0)).status)          // Content-Length check
        assertEquals(HttpStatusCode.PayloadTooLarge, c.assess(token, audio = wavBytes((MAX_WAV + 1000) / 2)).status) // part limit
        assertEquals(HttpStatusCode.BadRequest, c.assess(token, audio = null).status)
        assertEquals(HttpStatusCode.BadRequest, c.assess(token, text = null).status)
        assertEquals(HttpStatusCode.BadRequest, c.assess(token, text = "  ").status)
        assertEquals(HttpStatusCode.BadRequest, c.assess(token, text = "x".repeat(301)).status)
        assertEquals(0, scorer.calls.get())
        assertEquals(0, UsageRepo(TestDb.db).azureSecondsBetween(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)))
        assertTrue("redefluss_pronunciations_total{outcome=\"bad_audio\"} 5.0" in deps.metrics.scrape())
    }

    @Test fun scorerGetsOnlyHeaderPlusCountedSamples() = testApplication {
        val scorer = SpyScorer()
        application { redefluss(setup(scorer = scorer)) }
        val (c, token) = login()
        val full = seconds(2.0)
        val at = String(full, Charsets.ISO_8859_1).indexOf("data")
        val headerEnd = at + 8
        val declared = 16000 * 2 // 1 s declared, 2 s follow
        java.nio.ByteBuffer.wrap(full).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(at + 4, declared)
        assertEquals(HttpStatusCode.OK, c.assess(token, audio = full).status)
        assertEquals(headerEnd + declared, scorer.lastSize)
        assertEquals(1, UsageRepo(TestDb.db).azureSecondsBetween(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)))
    }

    @Test fun monthCapIs429() = testApplication {
        val deps = setup(env = mapOf("AZURE_SECONDS_PER_MONTH" to "10"))
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.OK, c.assess(token, audio = seconds(6.0)).status)
        val second = c.assess(token, audio = seconds(6.0))
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
        assertEquals(monthText, second.detail())
        assertEquals(4, c.get("/api/pronunciation/quota") { bearerAuth(token) }.body<JsonObject>()["secondsLeft"]!!.jsonPrimitive.int)
        assertTrue("redefluss_pronunciations_total{outcome=\"quota\"} 1.0" in deps.metrics.scrape())
    }

    @Test fun parallelClipsNearTheCapLetExactlyOneThrough() = testApplication {
        val scorer = SpyScorer()
        application { redefluss(setup(scorer = scorer, env = mapOf("AZURE_SECONDS_PER_MONTH" to "10"))) }
        val (c, token) = login()
        val results = coroutineScope { List(2) { async { c.assess(token, audio = seconds(6.0)) } }.awaitAll() }
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.TooManyRequests), results.map { it.status }.sortedBy { it.value })
        assertEquals(1, scorer.calls.get())
        assertEquals(6, UsageRepo(TestDb.db).azureSecondsBetween(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)))
    }

    @Test fun dailyLimitIs429() = testApplication {
        val deps = setup(env = mapOf("PRONUNCIATIONS_PER_DAY" to "1"))
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.OK, c.assess(token).status)
        val second = c.assess(token)
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
        assertEquals("Tageslimit für Aussprache erreicht. Morgen geht's weiter.", second.detail())
        assertEquals(0, c.get("/api/pronunciation/quota") { bearerAuth(token) }.body<JsonObject>()["todayLeft"]!!.jsonPrimitive.int)
        assertTrue("redefluss_pronunciations_total{outcome=\"limit\"} 1.0" in deps.metrics.scrape())
    }

    @Test fun nothingRecognizedIs422AndStillCounts() = testApplication {
        application { redefluss(setup(scorer = SpyScorer(NothingRecognizedException()))) }
        val (c, token) = login()
        val res = c.assess(token, audio = seconds(1.2))
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
        assertEquals("Ich habe nichts verstanden – bitte noch einmal.", res.detail())
        assertEquals(2, UsageRepo(TestDb.db).azureSecondsBetween(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))) // ceil(1.2)
        assertEquals(1, UsageRepo(TestDb.db).pronunciationsOn(LocalDate.now(TEST_CLOCK)))
    }

    @Test fun nothingRecognizedCountsAsNoSpeechMetric() = testApplication {
        val deps = setup(scorer = SpyScorer(NothingRecognizedException()))
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.UnprocessableEntity, c.assess(token).status)
        assertTrue("redefluss_pronunciations_total{outcome=\"no_speech\"} 1.0" in deps.metrics.scrape())
    }

    @Test fun azureQuotaIs429WithBusyText() = testApplication {
        val deps = setup(scorer = SpyScorer(QuotaExceededException()))
        application { redefluss(deps) }
        val (c, token) = login()
        val res = c.assess(token)
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
        assertEquals(busyText, res.detail())
        assertTrue("redefluss_pronunciations_total{outcome=\"quota\"} 1.0" in deps.metrics.scrape())
    }

    @Test fun upstreamErrorsAre502And504() = testApplication {
        val deps = setup(scorer = SpyScorer(UpstreamException("azure", timeout = false)))
        application { redefluss(deps) }
        val (c, token) = login()
        val res = c.assess(token)
        assertEquals(HttpStatusCode.BadGateway, res.status)
        assertTrue("Sprachdienst" in res.detail())
        assertTrue("redefluss_pronunciations_total{outcome=\"upstream_error\"} 1.0" in deps.metrics.scrape())
    }

    @Test fun timeoutIs504() = testApplication {
        val deps = setup(scorer = SpyScorer(UpstreamException("azure", timeout = true)))
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.GatewayTimeout, c.assess(token).status)
        assertTrue("redefluss_pronunciations_total{outcome=\"timeout\"} 1.0" in deps.metrics.scrape())
    }

    @Test fun disabledWithoutKeyAnswers503Everywhere() = testApplication {
        val deps = setup(env = mapOf("PRONUNCIATION" to "azure"), useDefaultScorer = true)
        application { redefluss(deps) }
        val (c, token) = login()
        val responses = listOf(
            c.get("/api/pronunciation/exercises") { bearerAuth(token) },
            c.get("/api/pronunciation/quota") { bearerAuth(token) },
            c.speak(token, "Hallo", slow = false),
            c.assess(token),
        )
        for (r in responses) {
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            assertEquals("Aussprache ist noch nicht eingerichtet.", r.detail())
        }
        assertEquals(0, UsageRepo(TestDb.db).turnsOn(LocalDate.now(TEST_CLOCK)))
        val overview = c.get("/api/overview") { bearerAuth(token) }.body<JsonObject>()
        assertFalse(overview["pronunciationEnabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun monthBoundaryUsesBerlin() = testApplication {
        val clock = Clock.fixed(Instant.parse("2026-10-31T23:30:00Z"), TEST_ZONE) // 1 November, 00:30 in Berlin
        val deps = setup(clock = clock, env = mapOf("AZURE_SECONDS_PER_MONTH" to "16200"))
        runBlocking { UsageRepo(TestDb.db).tryCountAzure(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1), 16000, 16200, 100) }
        application { redefluss(deps) }
        val (c, token) = login()
        val res = c.assess(token, audio = seconds(6.0))
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(16194, res.body<JsonObject>()["quota"]!!.jsonObject["secondsLeft"]!!.jsonPrimitive.int)
        assertTrue("redefluss_azure_seconds_month 6.0" in deps.metrics.scrape())
    }

    @Test fun quotaAndExercisesRefreshTheGaugeAfterAMonthChange() = testApplication {
        val clock = Clock.fixed(Instant.parse("2026-10-31T23:30:00Z"), TEST_ZONE) // 1 November in Berlin
        val deps = setup(clock = clock)
        runBlocking {
            UsageRepo(TestDb.db).tryCountAzure(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1), 500, 16200, 100)
            deps.metrics.azureSecondsMonth(500) // value left over from October
        }
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.OK, c.get("/api/pronunciation/quota") { bearerAuth(token) }.status)
        assertTrue("redefluss_azure_seconds_month 0.0" in deps.metrics.scrape())
        deps.metrics.azureSecondsMonth(500)
        assertEquals(HttpStatusCode.OK, c.get("/api/pronunciation/exercises") { bearerAuth(token) }.status)
        assertTrue("redefluss_azure_seconds_month 0.0" in deps.metrics.scrape())
    }

    @Test fun requiresLogin() = testApplication {
        application { redefluss(setup()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/pronunciation/exercises").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/pronunciation/quota").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/pronunciation/speak").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/pronunciation/assess").status)
    }
}
