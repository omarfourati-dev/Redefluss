package de.omarfourati.redefluss.invite

import de.omarfourati.redefluss.*
import de.omarfourati.redefluss.db.MistakeRepo
import de.omarfourati.redefluss.db.NewMistake
import de.omarfourati.redefluss.db.sql
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Duration
import kotlin.test.*

class InvitationRoutesTest {
    private val pw = "mein-sicheres-passwort"
    private val guestPw = "gast-passwort-123"

    private fun setup(vararg env: Pair<String, String>): Deps {
        val deps = testDeps(config = testConfig(*env), db = TestDb.reset())
        runBlocking { deps.auth.bootstrapOwner("omar@example.de", pw, reset = false) }
        return deps
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }
    private suspend fun HttpResponse.obj() = body<JsonObject>()
    private suspend fun HttpResponse.detail() = obj()["detail"]!!.jsonPrimitive.content
    private suspend fun HttpClient.login(email: String, password: String): String =
        post("/api/auth/login") { contentType(ContentType.Application.Json); setBody(mapOf("email" to email, "password" to password)) }
            .obj()["token"]!!.jsonPrimitive.content
    private suspend fun HttpClient.invite(token: String, email: String) =
        post("/api/invitations") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(mapOf("email" to email)) }
    private suspend fun HttpClient.preview(invite: String) =
        post("/api/invitations/preview") { contentType(ContentType.Application.Json); setBody(mapOf("token" to invite)) }
    private suspend fun HttpClient.accept(invite: String, password: String) =
        post("/api/invitations/accept") { contentType(ContentType.Application.Json); setBody(mapOf("token" to invite, "password" to password)) }

    /** Owner invites [email], the guest accepts; returns (owner token, guest token). */
    private suspend fun HttpClient.ownerAndGuest(email: String = "gast@example.de"): Pair<String, String> {
        val owner = login("omar@example.de", pw)
        val invite = invite(owner, email).obj()["token"]!!.jsonPrimitive.content
        val guest = accept(invite, guestPw).obj()["token"]!!.jsonPrimitive.content
        return owner to guest
    }

    @Test fun ownerInvitesAndTheGuestSetsAPasswordAndIsSignedIn() = testApplication {
        application { redefluss(setup()) }
        val c = jsonClient()
        val owner = c.login("omar@example.de", pw)
        val created = c.invite(owner, " Gast@Example.DE ")
        assertEquals(HttpStatusCode.Created, created.status)
        val body = created.obj()
        assertEquals("gast@example.de", body["email"]!!.jsonPrimitive.content)
        val invite = body["token"]!!.jsonPrimitive.content
        assertTrue(invite.length >= 40)
        assertEquals(TEST_CLOCK.instant().plus(Duration.ofDays(7)).toString(), body["expiresAt"]!!.jsonPrimitive.content)

        val listed = c.get("/api/invitations") { bearerAuth(owner) }.body<JsonArray>().single().jsonObject
        assertEquals("open", listed["status"]!!.jsonPrimitive.content)
        assertNull(listed["token"], "the token is shown only once")

        val preview = c.preview(invite)
        assertEquals(HttpStatusCode.OK, preview.status)
        assertEquals("gast@example.de", preview.obj()["email"]!!.jsonPrimitive.content)

        val short = c.accept(invite, "zu-kurz")
        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertEquals("Das Passwort braucht mindestens 12 Zeichen.", short.detail())

        val accepted = c.accept(invite, guestPw)
        assertEquals(HttpStatusCode.Created, accepted.status)
        assertEquals("guest", accepted.obj()["role"]!!.jsonPrimitive.content)
        val guest = accepted.obj()["token"]!!.jsonPrimitive.content
        val me = c.get("/api/auth/me") { bearerAuth(guest) }.obj()
        assertEquals("gast@example.de", me["email"]!!.jsonPrimitive.content)
        assertEquals("guest", me["role"]!!.jsonPrimitive.content)
        assertEquals("owner", c.get("/api/auth/me") { bearerAuth(owner) }.obj()["role"]!!.jsonPrimitive.content)

        // the link works exactly once
        assertEquals(HttpStatusCode.Gone, c.accept(invite, guestPw).status)
        assertEquals(HttpStatusCode.Gone, c.preview(invite).status)
        assertEquals("used", c.get("/api/invitations") { bearerAuth(owner) }.body<JsonArray>().single().jsonObject["status"]!!.jsonPrimitive.content)
        // and the guest can sign in normally afterwards
        assertNotNull(c.login("gast@example.de", guestPw))
    }

    @Test fun aGuestSeesNeitherTheOwnersDataNorItsMistakes() = testApplication {
        application { redefluss(setup()) }
        val c = jsonClient()
        val (owner, guest) = c.ownerAndGuest()
        val ownerMistake = runBlocking { MistakeRepo(TestDb.db).record(OWNER, NewMistake("artikel", "den ganzen Zeit", "die ganze Zeit", "r", "e"), TEST_CLOCK.instant()) }

        assertEquals(1, c.get("/api/mistakes?status=all") { bearerAuth(owner) }.body<JsonArray>().size)
        assertEquals(0, c.get("/api/mistakes?status=all") { bearerAuth(guest) }.body<JsonArray>().size)
        assertEquals(0, c.get("/api/overview") { bearerAuth(guest) }.obj()["topMistakes"]!!.jsonArray.size)
        val patch = c.patch("/api/mistakes/$ownerMistake") { bearerAuth(guest); contentType(ContentType.Application.Json); setBody(mapOf("resolved" to true)) }
        assertEquals(HttpStatusCode.NotFound, patch.status)

        // a conversation of the owner is unknown to the guest
        val session = c.post("/api/sessions") { bearerAuth(owner); contentType(ContentType.Application.Json); setBody(mapOf("topic" to "Privat")) }
            .obj()["id"]!!.jsonPrimitive.content
        val turn = c.submitFormWithBinaryData("/api/turns", formData { append("sessionId", session); append("text", "Hallo"); append("speak", "false") }) { bearerAuth(guest) }
        assertEquals(HttpStatusCode.NotFound, turn.status)
    }

    @Test fun guestsHaveSmallerLimitsAndAllAccountsShareATotalCap() = testApplication {
        application { redefluss(setup("GUEST_TURNS_PER_DAY" to "1", "TOTAL_TURNS_PER_DAY" to "3", "GUEST_LIVE_MINUTES_PER_DAY" to "10")) }
        val c = jsonClient()
        val (owner, guest) = c.ownerAndGuest()
        suspend fun turn(token: String): HttpResponse {
            val id = c.post("/api/sessions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(mapOf("topic" to "x")) }
                .obj()["id"]!!.jsonPrimitive.content
            return c.submitFormWithBinaryData("/api/turns", formData { append("sessionId", id); append("text", "Hallo"); append("speak", "false") }) { bearerAuth(token) }
        }
        val overview = c.get("/api/overview") { bearerAuth(guest) }.obj()
        assertEquals(1, overview["turnsLeft"]!!.jsonPrimitive.int)
        assertEquals(10, overview["liveMinutesLeft"]!!.jsonPrimitive.int)

        assertEquals(HttpStatusCode.OK, turn(guest).status)
        val limited = turn(guest)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("Tageslimit erreicht (1 Runden). Morgen geht's weiter.", limited.detail())

        assertEquals(HttpStatusCode.OK, turn(owner).status)
        assertEquals(HttpStatusCode.OK, turn(owner).status)
        val full = turn(owner) // 1 + 2 = 3 turns: the total cap is reached for everyone
        assertEquals(HttpStatusCode.TooManyRequests, full.status)
        assertEquals("Redefluss ist für heute ausgelastet. Morgen geht's weiter.", full.detail())
    }

    @Test fun onlyTheOwnerManagesInvitationsAndGuests() = testApplication {
        application { redefluss(setup()) }
        val c = jsonClient()
        val (owner, guest) = c.ownerAndGuest()
        assertEquals(HttpStatusCode.Forbidden, c.get("/api/invitations") { bearerAuth(guest) }.status)
        assertEquals(HttpStatusCode.Forbidden, c.invite(guest, "freund@example.de").status)
        assertEquals(HttpStatusCode.Forbidden, c.get("/api/users") { bearerAuth(guest) }.status)
        assertEquals(HttpStatusCode.Forbidden, c.delete("/api/users/1") { bearerAuth(guest) }.status)
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/invitations").status)

        val accounts = c.get("/api/users") { bearerAuth(owner) }.body<JsonArray>().map { it.jsonObject }
        assertEquals(listOf("owner", "guest"), accounts.map { it["role"]!!.jsonPrimitive.content })
        val guestId = accounts.last()["id"]!!.jsonPrimitive.long

        assertEquals(HttpStatusCode.NotFound, c.delete("/api/users/$OWNER") { bearerAuth(owner) }.status) // never the owner
        assertEquals(HttpStatusCode.NoContent, c.delete("/api/users/$guestId") { bearerAuth(owner) }.status)
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/auth/me") { bearerAuth(guest) }.status)
        assertEquals(1, c.get("/api/users") { bearerAuth(owner) }.body<JsonArray>().size)
    }

    @Test fun invalidEmailsAndExistingAccountsAreRejected() = testApplication {
        application { redefluss(setup()) }
        val c = jsonClient()
        val owner = c.login("omar@example.de", pw)
        val bad = c.invite(owner, "keine-mail")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("Bitte gib eine gültige E-Mail-Adresse ein.", bad.detail())
        val taken = c.invite(owner, "OMAR@example.de")
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertEquals("Für diese E-Mail gibt es schon ein Konto.", taken.detail())
    }

    @Test fun unknownExpiredAndWithdrawnLinksAreGone() = testApplication {
        val deps = setup()
        application { redefluss(deps) }
        val c = jsonClient()
        val owner = c.login("omar@example.de", pw)
        assertEquals(HttpStatusCode.Gone, c.preview("ausgedacht").status)
        assertEquals(HttpStatusCode.Gone, c.accept("ausgedacht", guestPw).status)

        // expired: stored with an end in the past
        runBlocking {
            InvitationRepo(TestDb.db).create("alt@example.de", InvitationService.hash("abgelaufen"), OWNER,
                TEST_CLOCK.instant().minus(Duration.ofDays(8)), TEST_CLOCK.instant().minusSeconds(1))
        }
        val expired = c.preview("abgelaufen")
        assertEquals(HttpStatusCode.Gone, expired.status)
        assertEquals("Diese Einladung ist ungültig, abgelaufen oder wurde schon benutzt.", expired.detail())
        assertEquals("expired", c.get("/api/invitations") { bearerAuth(owner) }.body<JsonArray>().single().jsonObject["status"]!!.jsonPrimitive.content)

        // withdrawn
        val created = c.invite(owner, "zurueck@example.de").obj()
        val id = created["id"]!!.jsonPrimitive.long
        assertEquals(HttpStatusCode.NoContent, c.delete("/api/invitations/$id") { bearerAuth(owner) }.status)
        assertEquals(HttpStatusCode.Gone, c.accept(created["token"]!!.jsonPrimitive.content, guestPw).status)
        assertEquals(HttpStatusCode.NotFound, c.delete("/api/invitations/$id") { bearerAuth(owner) }.status)
    }

    @Test fun guessingTokensIsThrottledPerIp() = testApplication {
        application { redefluss(setup()) }
        val c = jsonClient()
        val owner = c.login("omar@example.de", pw)
        val invite = c.invite(owner, "gast@example.de").obj()["token"]!!.jsonPrimitive.content
        repeat(5) { assertEquals(HttpStatusCode.Gone, c.preview("rate-$it").status) }
        assertEquals(HttpStatusCode.TooManyRequests, c.preview(invite).status) // even the valid link waits now
    }

    @Test fun theTokenIsStoredOnlyAsHash() = testApplication {
        application { redefluss(setup()) }
        val c = jsonClient()
        val owner = c.login("omar@example.de", pw)
        val invite = c.invite(owner, "gast@example.de").obj()["token"]!!.jsonPrimitive.content
        val stored = runBlocking { TestDb.db.tx { sql("SELECT token_hash FROM invitation") { it.next(); it.getString(1) } } }
        assertNotEquals(invite, stored)
        assertEquals(InvitationService.hash(invite), stored)
    }
}
