package de.omarfourati.redefluss.interview

import de.omarfourati.redefluss.speech.Correction
import de.omarfourati.redefluss.speech.UpstreamException
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class ReportTest {
    private class Seen(val url: String, val auth: String?, val body: String)
    private val seen = mutableListOf<Seen>()
    private val jobAd = "Backend-Entwickler (m/w/d) Kotlin – Beispiel GmbH\nWir suchen Verstärkung."
    private val transcript = listOf(
        TranscriptEntry("interviewer", "Guten Tag, Herr Fourati. Erzählen Sie etwas über sich."),
        TranscriptEntry("omar", "Ich arbeite seit drei Jahren als Entwickler und du muss wissen, ich liebe Kotlin."),
        TranscriptEntry("interviewer", "Haben Sie noch Fragen an uns?"),
        TranscriptEntry("omar", "Ja, wie groß ist das Team?"),
    )

    private fun client(vararg responses: Pair<HttpStatusCode, String>, delayMs: Long = 0): HttpClient {
        var i = 0
        return HttpClient(MockEngine { req ->
            seen += Seen(req.url.toString(), req.headers[HttpHeaders.Authorization], String(req.body.toByteArray()))
            if (delayMs > 0) delay(delayMs)
            val (status, body) = responses[minOf(i++, responses.lastIndex)]
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
    }

    private fun chat(content: String) = buildJsonObject {
        putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content", content) } } }
    }.toString()

    private val validReport = """{"overall":" Gutes Gespräch mit klarer Motivation. ","summary":"Du hast sicher gewirkt.",
        "strengths":[" Motivation ","","Beispiele","a","b","c"],"improvements":["STAR nutzen"],
        "answers":[{"question":" Erzählen Sie etwas über sich. ","answer":" Ich arbeite seit drei Jahren ","feedback":" Zu kurz. ","better":" Seit drei Jahren entwickle ich … "},
                   {"question":"x","answer":"   ","feedback":"y","better":"z"}],
        "corrections":[{"wrong":" du muss ","right":"du musst","rule":"-st bei du","category":"KONJUGATION"},
                       {"wrong":"gleich","right":"gleich","rule":"kein Fehler","category":"wortwahl"},
                       {"wrong":"x","right":"y","rule":"r","category":"erfunden"}]}"""

    @Test fun sendsStrictSchemaPromptJobAdAndTranscript() = runBlocking {
        val reporter = OpenAiReporter(client(HttpStatusCode.OK to chat(validReport)), "sk-test", "gpt-4o-mini")
        val report = reporter.report(jobAd, InterviewRole.teamlead, transcript)
        val req = seen.single()
        assertEquals("https://api.openai.com/v1/chat/completions", req.url)
        assertEquals("Bearer sk-test", req.auth)
        val body = Json.parseToJsonElement(req.body).jsonObject
        assertEquals("gpt-4o-mini", body["model"]!!.jsonPrimitive.content)
        val format = body["response_format"]!!.jsonObject["json_schema"]!!.jsonObject
        assertEquals("interview_report", format["name"]!!.jsonPrimitive.content)
        assertTrue(format["strict"]!!.jsonPrimitive.boolean)
        val schema = format["schema"]!!.jsonObject
        assertEquals(REPORT_SCHEMA, schema)
        assertEquals(listOf("overall", "summary", "strengths", "improvements", "answers", "corrections"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(schema["additionalProperties"]!!.jsonPrimitive.boolean)
        val answerItem = schema["properties"]!!.jsonObject["answers"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(listOf("question", "answer", "feedback", "better"), answerItem["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(answerItem["additionalProperties"]!!.jsonPrimitive.boolean)

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val system = messages[0]["content"]!!.jsonPrimitive.content
        assertTrue("IT-Recruiter" in system && "Sprachcoach" in system, system)
        assertTrue("STAR" in system && "Füllwörter" in system && "höchstens 4 Sätze" in system, system)
        assertTrue("2–4" in system && "2–3 Sätze" in system, system)
        assertTrue(jobAd in system)
        assertTrue("Teamleitung" in system)
        val user = messages[1]["content"]!!.jsonPrimitive.content
        assertEquals("Interviewer: Guten Tag, Herr Fourati. Erzählen Sie etwas über sich.\n" +
            "Omar: Ich arbeite seit drei Jahren als Entwickler und du muss wissen, ich liebe Kotlin.\n" +
            "Interviewer: Haben Sie noch Fragen an uns?\nOmar: Ja, wie groß ist das Team?", user)

        // Cleaned: trimmed, empty items dropped, at most 4 strengths, blank answers dropped, real corrections only, known categories.
        assertEquals("Gutes Gespräch mit klarer Motivation.", report.overall)
        assertEquals(listOf("Motivation", "Beispiele", "a", "b"), report.strengths)
        assertEquals(listOf(AnswerFeedback("Erzählen Sie etwas über sich.", "Ich arbeite seit drei Jahren", "Zu kurz.", "Seit drei Jahren entwickle ich …")), report.answers)
        assertEquals(listOf(Correction("du muss", "du musst", "-st bei du", "konjugation"), Correction("x", "y", "r", "sonstiges")), report.corrections)
    }

    @Test fun retriesOnceOnInvalidJsonThenSucceeds() = runBlocking {
        val reporter = OpenAiReporter(client(HttpStatusCode.OK to chat("kein json"), HttpStatusCode.OK to chat(validReport)), "sk", "m")
        assertEquals("Gutes Gespräch mit klarer Motivation.", reporter.report(jobAd, InterviewRole.recruiter, transcript).overall)
        assertEquals(2, seen.size)
    }

    @Test fun blankOverallCountsAsInvalid() = runBlocking {
        val blank = """{"overall":"  ","summary":"s","strengths":[],"improvements":[],"answers":[],"corrections":[]}"""
        val reporter = OpenAiReporter(client(HttpStatusCode.OK to chat(blank)), "sk", "m")
        val e = assertFailsWith<UpstreamException> { reporter.report(jobAd, InterviewRole.mix, transcript) }
        assertEquals("interview_report", e.stage)
        assertFalse(e.timeout)
        assertEquals(2, seen.size)
    }

    @Test fun upstreamErrorCarriesNoBody() = runBlocking {
        val reporter = OpenAiReporter(client(HttpStatusCode.InternalServerError to """{"error":"geheim-body sk-test"}"""), "sk-test", "m")
        val e = assertFailsWith<UpstreamException> { reporter.report(jobAd, InterviewRole.mix, transcript) }
        assertEquals("interview_report", e.stage)
        assertFalse("geheim" in e.message!! || "sk-test" in e.message!!)
    }

    @Test fun timeoutIsUpstreamTimeout() = runBlocking {
        val reporter = OpenAiReporter(client(HttpStatusCode.OK to chat(validReport), delayMs = 500), "sk", "m", timeoutMs = 50)
        assertTrue(assertFailsWith<UpstreamException> { reporter.report(jobAd, InterviewRole.mix, transcript) }.timeout)
    }

    @Test fun fakeReporterPairsQuestionsAndFindsKnownMistakes() = runBlocking {
        val r = FakeReporter().report(jobAd, InterviewRole.recruiter, transcript)
        assertTrue(r.overall.isNotBlank() && r.summary.isNotBlank())
        assertTrue(r.strengths.size in 2..4 && r.improvements.size in 2..4)
        assertEquals(listOf("Guten Tag, Herr Fourati. Erzählen Sie etwas über sich.", "Haben Sie noch Fragen an uns?"), r.answers.map { it.question })
        assertEquals(listOf(transcript[1].text, transcript[3].text), r.answers.map { it.answer })
        assertTrue("du musst" in r.answers[0].better)
        assertEquals(listOf("du muss"), r.corrections.map { it.wrong })
        assertTrue(FakeReporter().report(jobAd, InterviewRole.recruiter, listOf(TranscriptEntry("omar", "Hallo"))).answers.single().question.isEmpty())
    }
}
