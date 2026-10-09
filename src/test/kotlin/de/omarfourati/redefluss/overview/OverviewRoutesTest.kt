package de.omarfourati.redefluss.overview

import de.omarfourati.redefluss.*
import de.omarfourati.redefluss.db.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.*
import kotlin.test.*

class OverviewRoutesTest {
    private val pw = "mein-sicheres-passwort"

    @Test fun overviewAndMistakes() = testApplication {
        val db = TestDb.reset()
        val deps = testDeps(config = testConfig("TURNS_PER_DAY" to "10"), db = db)
        runBlocking {
            deps.auth.bootstrapOwner("omar@example.de", pw, reset = false)
            val today = LocalDate.now(TEST_CLOCK)
            val usage = UsageRepo(db)
            usage.tryCountTurn(today, 10); usage.tryCountTurn(today, 10)
            usage.tryReserveLive(today, 630, 1800)                                   // 1170 s left → 19 whole minutes
            usage.tryCountTurn(today.minusDays(1), 10)
            usage.tryCountTurn(today.minusDays(3), 10)
            usage.tryCountAzure(today, today.withDayOfMonth(1), 200, 16200, 100)
            usage.tryCountAzure(today.withDayOfMonth(1).minusDays(1), today.withDayOfMonth(1).minusMonths(1), 999, 16200, 100) // last month
            val sessions = SessionRepo(db)
            val s = sessions.create("conversation", "Arbeit", TEST_CLOCK.instant().minusSeconds(600))
            sessions.touch(s, TEST_CLOCK.instant().minusSeconds(60), 1)            // 9 minutes today
            val mistakes = MistakeRepo(db)
            repeat(3) { mistakes.record(NewMistake("artikel", "den ganzen Zeit", "die ganze Zeit", "r", "e"), TEST_CLOCK.instant()) }
            mistakes.record(NewMistake("konjugation", "du muss", "du musst", "r", "e"), TEST_CLOCK.instant())
            val vocab = de.omarfourati.redefluss.vocab.VocabRepo(db)
            vocab.insertIfNew(de.omarfourati.redefluss.vocab.NewCard("Termin", "der", "die Termine", "m", "e", "alltag", "daily"), today)
            vocab.insertIfNew(de.omarfourati.redefluss.vocab.NewCard("Frist", "die", "die Fristen", "m", "e", "it", "daily"), today.plusDays(1))
        }
        application { redefluss(deps) }
        val c = createClient { install(ContentNegotiation) { json() } }
        val token = c.post("/api/auth/login") { contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "omar@example.de", "password" to pw)) }.body<JsonObject>()["token"]!!.jsonPrimitive.content

        val o = c.get("/api/overview") { bearerAuth(token) }.body<JsonObject>()
        assertEquals(2, o["streakDays"]!!.jsonPrimitive.int)
        assertEquals(9, o["minutesToday"]!!.jsonPrimitive.int)
        assertEquals(2, o["turnsToday"]!!.jsonPrimitive.int)
        assertEquals(8, o["turnsLeft"]!!.jsonPrimitive.int)
        assertEquals(1, o["vocabDue"]!!.jsonPrimitive.int) // the card created tomorrow is not due yet
        assertEquals(16000, o["azureSecondsLeft"]!!.jsonPrimitive.int)
        assertTrue(o["pronunciationEnabled"]!!.jsonPrimitive.boolean)
        assertEquals(19, o["liveMinutesLeft"]!!.jsonPrimitive.int)
        val top = o["topMistakes"]!!.jsonArray
        assertEquals(listOf(3, 1), top.map { it.jsonObject["count"]!!.jsonPrimitive.int })

        val id = top[0].jsonObject["id"]!!.jsonPrimitive.long
        assertEquals(HttpStatusCode.NoContent, c.patch("/api/mistakes/$id") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(mapOf("resolved" to true)) }.status)
        assertEquals(1, c.get("/api/mistakes") { bearerAuth(token) }.body<JsonArray>().size)
        assertEquals(1, c.get("/api/mistakes?status=resolved") { bearerAuth(token) }.body<JsonArray>().size)
        assertEquals(2, c.get("/api/mistakes?status=all") { bearerAuth(token) }.body<JsonArray>().size)
        assertEquals(HttpStatusCode.BadRequest, c.get("/api/mistakes?status=kaputt") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, c.patch("/api/mistakes/999999") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(mapOf("resolved" to true)) }.status)
        assertEquals(HttpStatusCode.BadRequest, c.patch("/api/mistakes/abc") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(mapOf("resolved" to true)) }.status)
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/overview").status)
    }
}
