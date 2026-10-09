package de.omarfourati.redefluss.interview

import de.omarfourati.redefluss.*
import de.omarfourati.redefluss.db.*
import de.omarfourati.redefluss.speech.UpstreamException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class LiveRoutesTest {
    private val pw = "mein-sicheres-passwort"
    private val today: LocalDate = LocalDate.now(TEST_CLOCK)
    private val jobAd = "  Backend-Entwickler (m/w/d) Kotlin bei der Beispiel GmbH in München mit sehr langem Titel, der abgeschnitten wird\nWir suchen dich."

    /** Records each setup; returns [secret] or throws [fail]. */
    private class SpyRealtime(private val secret: ClientSecret? = null, private val fail: Exception? = null) : RealtimeSessions {
        val setups = mutableListOf<LiveSetup>()
        override suspend fun create(setup: LiveSetup): ClientSecret? { setups += setup; fail?.let { throw it }; return secret }
    }

    private lateinit var db: Db
    private val usage get() = UsageRepo(db)

    private fun setup(realtime: RealtimeSessions = FakeRealtime(), lines: MutableList<String> = mutableListOf(),
                      vararg env: Pair<String, String>): Deps {
        db = TestDb.reset()
        val deps = testDeps(config = testConfig(*env), db = db, realtime = realtime, log = { lines += it })
        runBlocking { deps.auth.bootstrapOwner("omar@example.de", pw, reset = false) }
        return deps
    }

    private suspend fun ApplicationTestBuilder.login(): Pair<HttpClient, String> {
        val c = createClient { install(ContentNegotiation) { json() } }
        val token = c.post("/api/auth/login") { contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "omar@example.de", "password" to pw)) }.body<JsonObject>()["token"]!!.jsonPrimitive.content
        return c to token
    }

    private suspend fun HttpClient.start(token: String, ad: String = jobAd, role: String = "recruiter", minutes: Int = 10) =
        post("/api/live/session") { bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("jobAd", ad); put("role", role); put("minutes", minutes) }) }

    private suspend fun HttpClient.cancel(token: String, id: String) =
        post("/api/live/cancel") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("sessionId", id) }) }

    private suspend fun HttpResponse.detail() = body<JsonObject>()["detail"]!!.jsonPrimitive.content
    private fun live() = runBlocking { usage.liveSecondsOn(today) }

    @Test fun fakeStartReservesAndCreatesInterviewSession() = testApplication {
        val deps = setup()
        application { redefluss(deps) }
        val (c, token) = login()
        val res = c.start(token)
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.body<JsonObject>()
        assertTrue(body["fake"]!!.jsonPrimitive.boolean)
        assertTrue(body["clientSecret"] is JsonNull)
        assertTrue(body["expiresAt"] is JsonNull)
        assertEquals("gpt-realtime", body["model"]!!.jsonPrimitive.content)
        assertEquals(720, body["maxSeconds"]!!.jsonPrimitive.int)
        assertEquals(10, body["minutes"]!!.jsonPrimitive.int)
        assertEquals(600, live())
        val id = UUID.fromString(body["sessionId"]!!.jsonPrimitive.content)
        val (mode, topic) = runBlocking { db.tx { sql("SELECT mode, topic FROM practice_session WHERE id = ?", id) { it.next(); it.getString(1) to it.getString(2) } } }
        assertEquals("interview", mode)
        assertEquals(jobAd.trim().lines().first().take(80), topic)
        assertTrue("redefluss_live_sessions_total{outcome=\"started\"" in deps.metrics.scrape())
        // The job ad itself is never stored – only kept in memory for the report.
        val stored = runBlocking { db.tx { sql("SELECT count(*) FROM practice_session WHERE topic LIKE '%Wir suchen%' OR summary LIKE '%Wir suchen%'") { it.next(); it.getInt(1) } } }
        assertEquals(0, stored)
        assertEquals(jobAd.trim(), deps.live.session(id)!!.jobAd)
    }

    @Test fun realStartReturnsSecretAndPassesSetup() = testApplication {
        val lines = mutableListOf<String>()
        val spy = SpyRealtime(secret = ClientSecret("ek_geheim123", 1_900_000_000))
        val deps = setup(spy, lines)
        runBlocking { MistakeRepo(db).record(NewMistake("konjugation", "du muss", "du musst", "r", "Du muss."), TEST_CLOCK.instant()) }
        application { redefluss(deps) }
        val (c, token) = login()
        val body = c.start(token, role = "teamlead", minutes = 20).body<JsonObject>()
        assertFalse(body["fake"]!!.jsonPrimitive.boolean)
        assertEquals("ek_geheim123", body["clientSecret"]!!.jsonPrimitive.content)
        assertEquals(1_900_000_000L, body["expiresAt"]!!.jsonPrimitive.long)
        assertEquals(1320, body["maxSeconds"]!!.jsonPrimitive.int)
        val s = spy.setups.single()
        assertEquals(InterviewRole.teamlead, s.role)
        assertEquals(20, s.minutes)
        assertEquals(jobAd.trim(), s.jobAd)
        assertEquals(listOf("du muss"), s.knownMistakes.map { it.wrong })
        assertFalse(lines.any { "ek_geheim" in it || "Wir suchen" in it }, lines.joinToString("\n"))
    }

    @Test fun dailyBudgetIsEnforced() = testApplication {
        val deps = setup()
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.OK, c.start(token, minutes = 20).status)
        val res = c.start(token, minutes = 15)
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
        assertEquals("Für heute sind die Live-Minuten aufgebraucht (30 Minuten). Morgen geht's weiter.", res.detail())
        assertEquals(HttpStatusCode.OK, c.start(token, minutes = 10).status) // exactly at the cap
        assertEquals(1800, live())
        assertTrue("redefluss_live_sessions_total{outcome=\"limit\"" in deps.metrics.scrape())
    }

    @Test fun twoFifteenMinuteSessionsFitAThirdDoesNot() = testApplication {
        val deps = setup(FakeRealtime(), mutableListOf(), "LIVE_MINUTES_PER_DAY" to "40")
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.OK, c.start(token, minutes = 15).status)
        assertEquals(HttpStatusCode.OK, c.start(token, minutes = 15).status)
        val res = c.start(token, minutes = 15)
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
        assertTrue("(40 Minuten)" in res.detail())
    }

    @Test fun zeroBudgetAlwaysRejects() = testApplication {
        val deps = setup(FakeRealtime(), mutableListOf(), "LIVE_MINUTES_PER_DAY" to "0")
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.TooManyRequests, c.start(token).status)
        assertEquals(0, live())
    }

    @Test fun validation() = testApplication {
        val deps = setup()
        application { redefluss(deps) }
        val (c, token) = login()
        suspend fun bad(res: HttpResponse, detail: String) {
            assertEquals(HttpStatusCode.BadRequest, res.status); assertEquals(detail, res.detail())
            assertEquals(4, res.body<JsonObject>().size)
        }
        bad(c.start(token, ad = "   "), "Bitte füg eine Stellenanzeige ein.")
        bad(c.start(token, ad = "x".repeat(6001)), "Die Stellenanzeige ist zu lang (höchstens 6000 Zeichen).")
        bad(c.start(token, role = "ceo"), "Bitte wähl eine Rolle: Recruiterin, Teamleitung oder beides.")
        bad(c.start(token, minutes = 12), "Bitte wähl 10, 15 oder 20 Minuten.")
        bad(c.post("/api/live/session") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("kein json") }, "Die Anfrage ist ungültig.")
        assertEquals(0, live())
        assertEquals(HttpStatusCode.OK, c.start(token, ad = "x".repeat(6000)).status)
        assertEquals(600, live())
    }

    @Test fun upstreamFailureRefunds() = testApplication {
        val deps = setup(SpyRealtime(fail = UpstreamException("realtime", timeout = false)))
        application { redefluss(deps) }
        val (c, token) = login()
        val res = c.start(token)
        assertEquals(HttpStatusCode.BadGateway, res.status)
        assertEquals(0, live())
        assertTrue("redefluss_live_sessions_total{outcome=\"upstream_error\"" in deps.metrics.scrape())
    }

    @Test fun upstreamTimeoutRefunds() = testApplication {
        val deps = setup(SpyRealtime(fail = UpstreamException("realtime", timeout = true)))
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.GatewayTimeout, c.start(token).status)
        assertEquals(0, live())
    }

    @Test fun unexpectedFailureRefundsToo() = testApplication {
        val deps = setup(SpyRealtime(fail = IllegalStateException("kaputt")))
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.InternalServerError, c.start(token).status)
        assertEquals(0, live())
    }

    @Test fun cancelRefundsOnce() = testApplication {
        val deps = setup()
        application { redefluss(deps) }
        val (c, token) = login()
        val first = c.start(token).body<JsonObject>()["sessionId"]!!.jsonPrimitive.content
        c.start(token, minutes = 15)
        assertEquals(1500, live())
        assertEquals(HttpStatusCode.NoContent, c.cancel(token, first).status)
        assertEquals(900, live())
        assertEquals(HttpStatusCode.NoContent, c.cancel(token, first).status)
        assertEquals(900, live())
        val summary = runBlocking { db.tx { sql("SELECT summary FROM practice_session WHERE id = ?", UUID.fromString(first)) { it.next(); it.getString(1) } } }
        assertEquals("abgebrochen", summary)
    }

    @Test fun cancelAfterClaimRefundsNothing() = testApplication {
        val deps = setup()
        application { redefluss(deps) }
        val (c, token) = login()
        val id = c.start(token).body<JsonObject>()["sessionId"]!!.jsonPrimitive.content
        val entry = deps.live.session(UUID.fromString(id))!!
        assertTrue(entry.claim())   // what the report (Task 2) does before settling
        assertFalse(entry.claim())
        assertEquals(HttpStatusCode.NoContent, c.cancel(token, id).status)
        assertEquals(600, live())
    }

    @Test fun cancelUnknownOrInvalidSession() = testApplication {
        val deps = setup()
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.NotFound, c.cancel(token, UUID.randomUUID().toString()).status)
        val conversation = runBlocking { SessionRepo(db).create("conversation", "Arbeit", TEST_CLOCK.instant()) }
        assertEquals(HttpStatusCode.NotFound, c.cancel(token, conversation.toString()).status)
        assertEquals(HttpStatusCode.BadRequest, c.cancel(token, "kein-uuid").status)
    }

    @Test fun cancelAfterRestartRefundsNothing() = testApplication {
        val deps = setup()
        val id = runBlocking {
            usage.tryReserveLive(today, 600, 1800)
            SessionRepo(db).create("interview", "Alt", TEST_CLOCK.instant())
        }
        application { redefluss(deps) }
        val (c, token) = login()
        assertEquals(HttpStatusCode.NoContent, c.cancel(token, id.toString()).status)
        assertEquals(600, live())
    }

    @Test fun cacheKeepsAtMostTwentySessions() = runBlocking {
        val deps = setup(FakeRealtime(), mutableListOf(), "LIVE_MINUTES_PER_DAY" to "120")
        val ids = (1..21).map {
            if (it % 12 == 0) usage.settleLive(today, 7200)
            UUID.fromString(deps.live.start(StartRequest(jobAd, "mix", 10)).sessionId)
        }
        assertNull(deps.live.session(ids.first()))
        assertNotNull(deps.live.session(ids.last()))
        assertEquals(20, ids.count { deps.live.session(it) != null })
    }

    @Test fun authRequired() = testApplication {
        val deps = setup()
        application { redefluss(deps) }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/live/session").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/live/cancel").status)
    }
}
