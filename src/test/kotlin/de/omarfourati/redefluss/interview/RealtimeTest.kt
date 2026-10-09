package de.omarfourati.redefluss.interview

import de.omarfourati.redefluss.speech.KnownMistake
import de.omarfourati.redefluss.speech.UpstreamException
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class RealtimeTest {
    private class Seen(val url: String, val auth: String?, val body: String)
    private val seen = mutableListOf<Seen>()
    private val jobAd = "Backend-Entwickler (m/w/d) Kotlin – Firma Beispiel GmbH, München\nWir suchen Verstärkung für unser Team."
    private val setup = LiveSetup(jobAd, InterviewRole.recruiter, 15, listOf(KnownMistake("du muss", "du musst", "konjugation")))

    private fun client(status: HttpStatusCode, body: String, delayMs: Long = 0) = HttpClient(MockEngine { req ->
        seen += Seen(req.url.toString(), req.headers[HttpHeaders.Authorization], String(req.body.toByteArray()))
        if (delayMs > 0) delay(delayMs)
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    @Test fun createsClientSecretWithExactRequest() = runBlocking {
        val rt = OpenAiRealtime(client(HttpStatusCode.OK, """{"value":"ek_abc","expires_at":1234,"session":{}}"""), "sk-test", "gpt-realtime", "marin")
        assertEquals(ClientSecret("ek_abc", 1234), rt.create(setup))
        val req = seen.single()
        assertEquals("https://api.openai.com/v1/realtime/client_secrets", req.url)
        assertEquals("Bearer sk-test", req.auth)
        val json = Json.parseToJsonElement(req.body).jsonObject
        val exp = json["expires_after"]!!.jsonObject
        assertEquals("created_at", exp["anchor"]!!.jsonPrimitive.content)
        assertEquals(60, exp["seconds"]!!.jsonPrimitive.int)
        val s = json["session"]!!.jsonObject
        assertEquals("realtime", s["type"]!!.jsonPrimitive.content)
        assertEquals("gpt-realtime", s["model"]!!.jsonPrimitive.content)
        assertEquals(interviewerInstructions(setup), s["instructions"]!!.jsonPrimitive.content)
        val audio = s["audio"]!!.jsonObject
        val input = audio["input"]!!.jsonObject
        assertEquals("gpt-4o-transcribe", input["transcription"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals("de", input["transcription"]!!.jsonObject["language"]!!.jsonPrimitive.content)
        assertEquals("server_vad", input["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("marin", audio["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
    }

    @Test fun upstreamErrorCarriesNoBodyOrKey() = runBlocking {
        val rt = OpenAiRealtime(client(HttpStatusCode.InternalServerError, """{"error":"geheimer-body sk-test"}"""), "sk-test", "gpt-realtime", "marin")
        val e = assertFailsWith<UpstreamException> { rt.create(setup) }
        assertFalse(e.timeout)
        assertEquals("realtime", e.stage)
        assertFalse("geheimer-body" in e.message!! || "sk-test" in e.message!!)
    }

    @Test fun malformedResponseIsUpstreamError() = runBlocking {
        val rt = OpenAiRealtime(client(HttpStatusCode.OK, """{"nope":true}"""), "sk-test", "gpt-realtime", "marin")
        assertFailsWith<UpstreamException> { rt.create(setup) }
        Unit
    }

    @Test fun timeoutIsUpstreamTimeout() = runBlocking {
        val rt = OpenAiRealtime(client(HttpStatusCode.OK, """{"value":"ek","expires_at":1}""", delayMs = 500), "sk-test", "gpt-realtime", "marin", timeoutMs = 50)
        assertTrue(assertFailsWith<UpstreamException> { rt.create(setup) }.timeout)
    }

    @Test fun fakeReturnsNull() = runBlocking {
        assertNull(FakeRealtime().create(setup))
    }

    @Test fun instructionsContainJobAdRoleDurationClosingAndMistakes() {
        val text = interviewerInstructions(setup)
        assertTrue(jobAd in text)
        assertTrue("Sie sind Recruiterin bei dem Unternehmen aus der Stellenanzeige" in text, text)
        assertTrue("15 Minuten" in text)
        assertTrue("Haben Sie noch Fragen an uns?" in text)
        assertTrue("du muss" in text && "du musst" in text)
        assertTrue("KERAVONOS" in text && "18b" in text && "München" in text)
        assertTrue("- Korrigieren Sie Herrn Fouratis Deutsch während des Gesprächs nicht und erwähnen Sie keine Sprachfehler – die Auswertung kommt danach." in text, text)
        val lead = interviewerInstructions(setup.copy(role = InterviewRole.teamlead))
        assertTrue("technische Teamleitung" in lead)
        assertFalse("Sie sind Recruiterin" in lead)
        val mix = interviewerInstructions(setup.copy(role = InterviewRole.mix, minutes = 20))
        assertTrue("Recruiterin" in mix && "Teamleitung" in mix && "20 Minuten" in mix)
        assertFalse("du muss" in interviewerInstructions(setup.copy(knownMistakes = emptyList())))
    }

    @Test fun interviewerSaysHerrFouratiNeverOmar() {
        for (role in InterviewRole.entries) {
            val text = interviewerInstructions(setup.copy(role = role))
            assertTrue("- Siezen Sie den Kandidaten wie in einem echten Vorstellungsgespräch und sprechen Sie ihn mit „Herr Fourati“ an – nennen Sie ihn niemals „Omar“." in text, text)
            // „Omar“ appears only in the profile's full name and in the rule forbidding it, nowhere as a form of address.
            val withOmar = text.lines().filter { "Omar" in it }
            assertEquals(2, withOmar.size, withOmar.joinToString("\n"))
            assertTrue(withOmar.any { it.startsWith("Profil: Herr Omar Fourati ist") }, withOmar.joinToString("\n"))
            assertTrue(withOmar.any { "niemals „Omar“" in it }, withOmar.joinToString("\n"))
        }
    }

    @Test fun closingAnswersQuestionsThenSaysGoodbye() {
        val text = interviewerInstructions(setup)
        assertTrue("Stellen Sie gegen Ende (spätestens nach 15 Minuten) die Abschlussfrage ‚Haben Sie noch Fragen an uns?‘, " +
            "beantworten Sie seine Fragen kurz, bedanken Sie sich und verabschieden Sie sich. Beginnen Sie danach kein neues Thema." in text, text)
        assertFalse("Beenden Sie das Gespräch" in text, text)
    }

    @Test fun neutralCompanyNoInventedFactsShortTurnsGermanOnly() {
        val text = interviewerInstructions(setup)
        assertTrue("- Nennt die Stellenanzeige kein Unternehmen, verwenden Sie eine neutrale Beschreibung wie „unser Unternehmen“. " +
            "Erfinden Sie keine Fakten über das Unternehmen oder die Stelle, die nicht in der Stellenanzeige stehen." in text, text)
        assertTrue("- Halten Sie Ihre eigenen Wortbeiträge kurz: 1–3 Sätze, keine Monologe." in text, text)
        assertTrue("- Sprechen Sie immer Deutsch, auch wenn Herr Fourati in eine andere Sprache wechselt." in text, text)
    }

    @Test fun longKnownMistakesAreTruncated() {
        val text = interviewerInstructions(setup.copy(knownMistakes = listOf(KnownMistake("a".repeat(500), "b".repeat(500), "kasus"))))
        assertFalse("a".repeat(121) in text)
    }
}
