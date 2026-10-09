package de.omarfourati.redefluss.speech

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class OpenAiTest {
    private val requests = mutableListOf<Pair<String, String>>() // url path → body text

    private fun client(vararg responses: Pair<HttpStatusCode, String>, delayMs: Long = 0): HttpClient {
        var i = 0
        return HttpClient(MockEngine { req ->
            requests += req.url.encodedPath to String((req.body as OutgoingContent).toByteArray())
            if (delayMs > 0) delay(delayMs)
            val (status, body) = responses[minOf(i++, responses.lastIndex)]
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
    }
    private fun chat(content: String) = buildJsonObject {
        putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content", content) } } }
    }.toString()
    private val good = """{"corrections":[{"wrong":"den ganzen Zeit","right":"die ganze Zeit","rule":"Zeit ist feminin.","category":"artikel"}],"natural":"Ich habe die ganze Zeit gearbeitet.","reply":"Woran hast du gearbeitet?"}"""
    private val input = CoachInput("Ich habe den ganzen Zeit gearbeitet.", "Arbeit",
        listOf(HistoryTurn("assistant", "Was hast du heute gemacht?")), listOf(KnownMistake("du muss", "du musst", "konjugation")))

    @Test fun fileNames() {
        assertEquals("audio.m4a", fileNameFor("audio/mp4"))
        assertEquals("audio.m4a", fileNameFor("audio/x-m4a"))
        assertEquals("audio.webm", fileNameFor("audio/webm;codecs=opus"))
        assertEquals("audio.ogg", fileNameFor("audio/ogg; codecs=opus"))
        assertEquals("audio.mp3", fileNameFor("audio/mpeg"))
        assertEquals("audio.wav", fileNameFor("audio/wav"))
    }

    @Test fun transcribeSendsGermanVerbatimPromptAndM4aName() = runBlocking {
        val t = OpenAiTranscriber(client(HttpStatusCode.OK to """{"text":"  Ich habe den ganzen Zeit gearbeitet. "}"""), "sk", "gpt-4o-transcribe")
        assertEquals("Ich habe den ganzen Zeit gearbeitet.", t.transcribe(Audio(byteArrayOf(1, 2, 3), "audio/mp4")))
        val (path, body) = requests.single()
        assertEquals("/v1/audio/transcriptions", path)
        for (part in listOf("gpt-4o-transcribe", "name=\"language\"", "de", "filename=\"audio.m4a\"", "nicht korrigieren")) assertTrue(part in body, part)
    }

    @Test fun coachUsesStrictSchemaAndKnownMistakes() = runBlocking {
        val coach = OpenAiCoach(client(HttpStatusCode.OK to chat(good)), "sk", "gpt-4o-mini")
        val reply = coach.respond(input)
        assertEquals("die ganze Zeit", reply.corrections.single().right)
        assertEquals("Woran hast du gearbeitet?", reply.reply)
        val body = Json.parseToJsonElement(requests.single().second).jsonObject
        val format = body["response_format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertTrue(format["json_schema"]!!.jsonObject["strict"]!!.jsonPrimitive.boolean)
        val all = body.toString()
        assertTrue("du muss" in all && "Arbeit" in all && "Was hast du heute gemacht?" in all)
    }

    @Test fun coachRetriesOnceOnInvalidJson() = runBlocking {
        val coach = OpenAiCoach(client(HttpStatusCode.OK to chat("kein json"), HttpStatusCode.OK to chat(good)), "sk", "gpt-4o-mini")
        assertEquals("Woran hast du gearbeitet?", coach.respond(input).reply)
        assertEquals(2, requests.size)
    }

    @Test fun coachFailsAfterTwoInvalidAnswers() = runBlocking {
        val coach = OpenAiCoach(client(HttpStatusCode.OK to chat("{}")), "sk", "gpt-4o-mini")
        val e = assertFailsWith<UpstreamException> { coach.respond(input) }
        assertEquals("coach", e.stage)
    }

    @Test fun upstreamErrorNeverLeaksTheBody() = runBlocking {
        val t = OpenAiTranscriber(client(HttpStatusCode.Unauthorized to """{"error":{"message":"Incorrect API key sk-abc123"}}"""), "sk", "m")
        val e = assertFailsWith<UpstreamException> { t.transcribe(Audio(byteArrayOf(1), "audio/webm")) }
        assertFalse("sk-abc123" in (e.message ?: ""))
        assertFalse(e.timeout)
    }

    @Test fun timeoutIsReportedAsTimeout() = runBlocking {
        val v = OpenAiVoice(client(HttpStatusCode.OK to "x", delayMs = 500), "sk", "gpt-4o-mini-tts", "coral", timeoutMs = 50)
        val e = assertFailsWith<UpstreamException> { v.speak("Hallo") }
        assertTrue(e.timeout)
        assertEquals("voice", e.stage)
    }

    @Test fun voiceReturnsMp3() = runBlocking {
        val v = OpenAiVoice(client(HttpStatusCode.OK to "MP3DATA"), "sk", "gpt-4o-mini-tts", "coral")
        val audio = v.speak("Woran hast du gearbeitet?")
        assertEquals("audio/mpeg", audio.contentType)
        assertEquals("MP3DATA", String(audio.bytes))
        val body = requests.single().second
        assertTrue("\"voice\":\"coral\"" in body && "\"response_format\":\"mp3\"" in body && "Hochdeutsch" in body)
    }

    @Test fun cleanReplyNormalizes() {
        val raw = CoachReply(listOf(
            Correction(" den ganzen Zeit ", "die ganze Zeit", "Zeit ist feminin.", "Artikel"),
            Correction("gut", "gut", "gleich", "wortwahl"),          // no real change → dropped
            Correction("", "x", "leer", "kasus"),                    // empty → dropped
            Correction("a", "b", "r", "grammar"),                    // unknown category → sonstiges
        ) + List(10) { Correction("w$it", "r$it", "r", "kasus") }, " Natürlich. ", " Antwort ")
        val c = cleanReply(raw)
        assertEquals("artikel", c.corrections.first().category)
        assertEquals("den ganzen Zeit", c.corrections.first().wrong)
        assertEquals("sonstiges", c.corrections[1].category)
        assertEquals(6, c.corrections.size)
        assertEquals("Natürlich.", c.natural)
        assertEquals("Antwort", c.reply)
    }
}
