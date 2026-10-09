package de.omarfourati.redefluss.auth

import de.omarfourati.redefluss.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthRoutesTest {
    private val pw = "mein-sicheres-passwort"

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }
    private suspend fun io.ktor.client.HttpClient.login(email: String = "omar@example.de", password: String = pw) =
        post("/api/auth/login") { contentType(ContentType.Application.Json); setBody(mapOf("email" to email, "password" to password)) }

    private fun deps(): Deps {
        val deps = testDeps(db = TestDb.reset())
        runBlocking { deps.auth.bootstrapOwner("omar@example.de", pw, reset = false) }
        return deps
    }

    @Test fun loginMeAndCaseInsensitiveEmail() = testApplication {
        application { redefluss(deps()) }
        val c = jsonClient()
        val res = c.login(email = " Omar@Example.DE ")
        assertEquals(HttpStatusCode.OK, res.status)
        val token = res.body<JsonObject>()["token"]!!.jsonPrimitive.content
        val me = c.get("/api/auth/me") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("omar@example.de", me.body<JsonObject>()["email"]!!.jsonPrimitive.content)
    }

    @Test fun wrongPasswordAndUnknownEmailLookTheSame() = testApplication {
        application { redefluss(deps()) }
        val c = jsonClient()
        val a = c.login(password = "falsch-falsch-falsch")
        val b = c.login(email = "nobody@example.de")
        assertEquals(HttpStatusCode.Unauthorized, a.status)
        assertEquals(a.bodyAsText(), b.bodyAsText())
        assertTrue("E-Mail oder Passwort ist falsch." in a.bodyAsText())
    }

    @Test fun throttledAfterFiveFailures() = testApplication {
        application { redefluss(deps()) }
        val c = jsonClient()
        repeat(5) { c.login(password = "falsch-falsch-falsch") }
        val res = c.login() // even the right password is blocked now
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
    }

    @Test fun protectedRouteWithoutTokenIs401Problem() = testApplication {
        application { redefluss(deps()) }
        val res = client.get("/api/auth/me")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("application/problem+json", res.contentType()?.withoutParameters()?.toString())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/auth/me") { bearerAuth("kaputt") }.status)
    }

    @Test fun passwordChangeIssuesNewTokenAndKillsOldOnes() = testApplication {
        application { redefluss(deps()) }
        val c = jsonClient()
        val old = c.login().body<JsonObject>()["token"]!!.jsonPrimitive.content
        val wrong = c.post("/api/auth/password") { bearerAuth(old); contentType(ContentType.Application.Json)
            setBody(mapOf("current" to "falsch", "next" to "neues-passwort-123")) }
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        val short = c.post("/api/auth/password") { bearerAuth(old); contentType(ContentType.Application.Json)
            setBody(mapOf("current" to pw, "next" to "kurz")) }
        assertEquals(HttpStatusCode.BadRequest, short.status)
        val ok = c.post("/api/auth/password") { bearerAuth(old); contentType(ContentType.Application.Json)
            setBody(mapOf("current" to pw, "next" to "neues-passwort-123")) }
        assertEquals(HttpStatusCode.OK, ok.status)
        val fresh = ok.body<JsonObject>()["token"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/auth/me") { bearerAuth(old) }.status)
        assertEquals(HttpStatusCode.OK, c.get("/api/auth/me") { bearerAuth(fresh) }.status)
        assertEquals(HttpStatusCode.OK, c.login(password = "neues-passwort-123").status)
    }

    @Test fun bootstrapKeepsAChangedPasswordUnlessResetIsRequested() = runBlocking {
        val deps = testDeps(db = TestDb.reset())
        deps.auth.bootstrapOwner("omar@example.de", pw, reset = false)
        val user = deps.users.findByEmail("omar@example.de")!!
        deps.users.setPassword(user.id, Passwords.hash("in-der-app-geaendert"))
        deps.auth.bootstrapOwner("omar@example.de", pw, reset = false)
        assertTrue(Passwords.verify("in-der-app-geaendert", deps.users.findByEmail("omar@example.de")!!.passwordHash))
        deps.auth.bootstrapOwner("omar@example.de", pw, reset = true)
        assertTrue(Passwords.verify(pw, deps.users.findByEmail("omar@example.de")!!.passwordHash))
    }
}
