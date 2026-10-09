package de.omarfourati.redefluss.pronunciation

import de.omarfourati.redefluss.speech.UpstreamException
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.install
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.Base64
import kotlin.test.*

class AzureScorerTest {
    private val seen = mutableListOf<HttpRequestData>()
    private var sentBody = ByteArray(0)

    private fun client(status: HttpStatusCode, body: String, delayMs: Long = 0) = HttpClient(MockEngine { req ->
        seen += req
        sentBody = (req.body as OutgoingContent).toByteArray()
        if (delayMs > 0) delay(delayMs)
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    private fun scorer(status: HttpStatusCode, body: String, delayMs: Long = 0, timeoutMs: Long = 15_000) =
        AzureScorer(client(status, body, delayMs), "secret-key", "germanywestcentral", timeoutMs)

    private val wav = wavBytes(1600)

    private val shapeA = """{"RecognitionStatus":"Success","NBest":[{"Display":"Guten Morgen.","PronunciationAssessment":{"AccuracyScore":91.04,"FluencyScore":88.0,"CompletenessScore":100.0,"PronScore":90.25},
 "Words":[{"Word":"guten","PronunciationAssessment":{"AccuracyScore":95.0,"ErrorType":"None"},"Phonemes":[{"Phoneme":"g","PronunciationAssessment":{"AccuracyScore":98.0}},{"Phoneme":"u","PronunciationAssessment":{"AccuracyScore":52.55}}]},
 {"Word":"morgen","PronunciationAssessment":{"AccuracyScore":40.0,"ErrorType":"Mispronunciation"}}]}]}"""
    private val shapeB = """{"RecognitionStatus":"Success","NBest":[{"Lexical":"guten morgen","AccuracyScore":91.0,"FluencyScore":88.0,"CompletenessScore":100.0,"PronScore":90.2,
 "Words":[{"Word":"guten","AccuracyScore":95.0,"ErrorType":"None","Phonemes":[{"Phoneme":"g","AccuracyScore":98.0}]}]}]}"""

    @Test fun requestShape() = runBlocking {
        val a = scorer(HttpStatusCode.OK, shapeA).assess(wav, "Guten Morgen.")
        val r = seen.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("https://germanywestcentral.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=de-DE&format=detailed", r.url.toString())
        assertEquals("secret-key", r.headers["Ocp-Apim-Subscription-Key"])
        assertEquals("audio/wav; codecs=audio/pcm; samplerate=16000", r.body.headers[HttpHeaders.ContentType])
        assertEquals("application/json", r.headers[HttpHeaders.Accept])
        val cfg = Json.parseToJsonElement(String(Base64.getDecoder().decode(r.headers["Pronunciation-Assessment"]!!), Charsets.UTF_8)).jsonObject
        assertEquals("Guten Morgen.", cfg["ReferenceText"]!!.jsonPrimitive.content)
        assertEquals("HundredMark", cfg["GradingSystem"]!!.jsonPrimitive.content)
        assertEquals("Phoneme", cfg["Granularity"]!!.jsonPrimitive.content)
        assertEquals("Comprehensive", cfg["Dimension"]!!.jsonPrimitive.content)
        assertTrue(cfg["EnableMiscue"]!!.jsonPrimitive.boolean)
        assertContentEquals(wav, sentBody)
        assertEquals("Guten Morgen.", a.recognized)
    }

    /** The MockEngine cannot show what a real engine writes, so send the content through CIO to a local server. */
    @Test fun contentTypeGoesOverTheWireUnquoted() = runBlocking {
        var received: String? = null
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, port = 0) {
            install(io.ktor.server.application.createApplicationPlugin("capture") {
                onCall { received = it.request.headers[HttpHeaders.ContentType] }
            })
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = HttpClient(io.ktor.client.engine.cio.CIO)
        try {
            http.post("http://127.0.0.1:$port/") { setBody(RawTypeContent(wav, WAV_CONTENT_TYPE)) }
        } finally { http.close(); server.stop(0, 0) }
        assertEquals("audio/wav; codecs=audio/pcm; samplerate=16000", received)
    }

    @Test fun parsesShapeA() {
        val a = parseAzure(shapeA)
        assertEquals("Guten Morgen.", a.recognized)
        assertEquals(91.0, a.accuracy); assertEquals(88.0, a.fluency); assertEquals(100.0, a.completeness); assertEquals(90.3, a.pronunciation)
        assertEquals(listOf("guten", "morgen"), a.words.map { it.word })
        assertEquals(95.0, a.words[0].score); assertEquals("None", a.words[0].errorType)
        assertEquals(listOf(PhonemeScore("g", 98.0), PhonemeScore("u", 52.6)), a.words[0].phonemes)
        assertEquals("Mispronunciation", a.words[1].errorType)
        assertTrue(a.words[1].phonemes.isEmpty())
    }

    @Test fun parsesShapeB() {
        val a = parseAzure(shapeB)
        assertEquals("guten morgen", a.recognized)
        assertEquals(90.2, a.pronunciation); assertEquals(100.0, a.completeness)
        assertEquals(PhonemeScore("g", 98.0), a.words.single().phonemes.single())
        assertEquals("None", a.words.single().errorType)
    }

    @Test fun nothingRecognized() {
        for (body in listOf("""{"RecognitionStatus":"NoMatch"}""", """{"RecognitionStatus":"InitialSilenceTimeout"}""",
            """{"RecognitionStatus":"Success","NBest":[]}""")) {
            assertFailsWith<NothingRecognizedException>(body) { parseAzure(body) }
        }
    }

    @Test fun recognitionErrorIsUpstreamNotNothingRecognized() {
        val e = assertFailsWith<de.omarfourati.redefluss.speech.UpstreamException> { parseAzure("""{"RecognitionStatus":"Error"}""") }
        assertEquals("azure", e.stage)
        assertFalse(e.timeout)
        assertFailsWith<de.omarfourati.redefluss.speech.UpstreamException> {
            runBlocking { scorer(HttpStatusCode.OK, """{"RecognitionStatus":"Error"}""").assess(wav, "x") }
        }
    }

    @Test fun nothingRecognizedIsNotSwallowedByUpstreamWrapper() {
        assertFailsWith<NothingRecognizedException> { runBlocking { scorer(HttpStatusCode.OK, """{"RecognitionStatus":"NoMatch"}""").assess(wav, "x") } }
    }

    @Test fun quotaOn429And403() {
        for (s in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.Forbidden)) {
            assertFailsWith<QuotaExceededException>(s.toString()) { runBlocking { scorer(s, "quota text with secret-key").assess(wav, "x") } }
        }
    }

    @Test fun serverErrorIsUpstreamWithoutBody() {
        val e = assertFailsWith<UpstreamException> { runBlocking { scorer(HttpStatusCode.InternalServerError, "boom secret-key").assess(wav, "x") } }
        assertEquals("azure", e.stage); assertFalse(e.timeout)
        assertFalse("boom" in e.message.orEmpty() || "secret" in e.message.orEmpty())
    }

    @Test fun garbageBodyIsUpstreamError() {
        val e = assertFailsWith<UpstreamException> { runBlocking { scorer(HttpStatusCode.OK, "<html>nope</html>").assess(wav, "x") } }
        assertFalse(e.timeout)
    }

    @Test fun slowResponseIsTimeout() {
        val e = assertFailsWith<UpstreamException> { runBlocking { scorer(HttpStatusCode.OK, shapeA, delayMs = 500, timeoutMs = 50).assess(wav, "x") } }
        assertEquals("azure", e.stage); assertTrue(e.timeout)
    }

    @Test fun callerCancellationIsRethrown() = runBlocking {
        val e = assertFails { withTimeout(50) { scorer(HttpStatusCode.OK, shapeA, delayMs = 5_000, timeoutMs = 10_000).assess(wav, "x") } }
        assertTrue(e is TimeoutCancellationException && e !is UpstreamException, e.toString())
    }
}
