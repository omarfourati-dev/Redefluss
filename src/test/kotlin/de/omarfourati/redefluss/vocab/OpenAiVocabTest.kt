package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.speech.Correction
import de.omarfourati.redefluss.speech.UpstreamException
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class OpenAiVocabTest {
    private val requests = mutableListOf<Pair<String, String>>()

    private fun client(vararg responses: Pair<HttpStatusCode, String>): HttpClient {
        var i = 0
        return HttpClient(MockEngine { req ->
            requests += req.url.encodedPath to String(req.body.toByteArray())
            val (status, body) = responses[minOf(i++, responses.lastIndex)]
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
    }
    private fun chat(content: String) = buildJsonObject {
        putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content", content) } } }
    }.toString()

    @Test fun generatorUsesStrictSchemaAndAvoidList() = runBlocking {
        val body = """{"words":[{"word":"Kündigungsfrist","article":"Die","plural":"die Kündigungsfristen","meaning":"Zeit bis zum Ende eines Vertrags","example":"Meine Kündigungsfrist beträgt drei Monate.","theme":"it"},
                       {"word":"die Kündigungsfrist","article":"die","plural":"","meaning":"x","example":"y","theme":"it"},
                       {"word":"Feierabend","article":"der","plural":"die Feierabende","meaning":"Ende des Arbeitstags","example":"Nach dem Feierabend gehe ich joggen.","theme":"weltall"}]}"""
        val gen = OpenAiVocabGenerator(client(HttpStatusCode.OK to chat(body)), "sk", "gpt-4o-mini")
        val words = gen.generate(7, avoid = listOf("Ansprechpartner"), themes = listOf("it", "alltag", "redewendung"))
        assertEquals(listOf("Kündigungsfrist", "Feierabend"), words.map { it.word })
        assertEquals("die", words[0].article); assertEquals("alltag", words[1].theme)
        val req = Json.parseToJsonElement(requests.single().second).jsonObject
        val format = req["response_format"]!!.jsonObject["json_schema"]!!.jsonObject
        assertEquals("daily_words", format["name"]!!.jsonPrimitive.content)
        assertTrue(format["strict"]!!.jsonPrimitive.boolean)
        assertTrue("Ansprechpartner" in req.toString() && "7" in req.toString())
    }

    @Test fun generatorSendsOnlyFirst300AvoidWords() = runBlocking {
        val gen = OpenAiVocabGenerator(client(HttpStatusCode.OK to chat("""{"words":[]}""")), "sk", "m")
        gen.generate(5, avoid = (1..350).map { "wort$it" }, themes = THEMES)
        val text = requests.single().second
        assertTrue("wort300" in text); assertFalse("wort301" in text)
    }

    @Test fun generatorRetriesOnceOnInvalidJsonThenThrows() = runBlocking {
        val gen = OpenAiVocabGenerator(client(HttpStatusCode.OK to chat("kein json")), "sk", "m")
        val e = assertFailsWith<UpstreamException> { gen.generate(3, emptyList(), THEMES) }
        assertEquals("vocab_generate", e.stage)
        assertEquals(2, requests.size)
    }

    @Test fun checkerSendsWordMeaningSentenceAndCleansResult() = runBlocking {
        val body = """{"grade":7,"usedCorrectly":true,"feedback":" Gut. ","corrections":[{"wrong":"der Frist","right":"die Frist","rule":"feminin","category":"artikel"}],"better":" Die Frist endet morgen. "}"""
        val checker = OpenAiVocabChecker(client(HttpStatusCode.OK to chat(body)), "sk", "m")
        val r = checker.check("Frist", "Zeitraum", "Der Frist endet morgen.")
        assertEquals(5, r.grade); assertEquals("Gut.", r.feedback); assertEquals("Die Frist endet morgen.", r.better)
        val req = Json.parseToJsonElement(requests.single().second).jsonObject
        assertEquals("vocab_check", req["response_format"]!!.jsonObject["json_schema"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val text = req.toString()
        assertTrue("Frist" in text && "Zeitraum" in text && "Der Frist endet morgen." in text)
    }

    @Test fun upstreamErrorHasNoBodyText() = runBlocking {
        val gen = OpenAiVocabGenerator(client(HttpStatusCode.InternalServerError to """{"error":"geheim-123"}"""), "sk", "m")
        val e = assertFailsWith<UpstreamException> { gen.generate(3, emptyList(), THEMES) }
        assertFalse("geheim" in (e.message ?: ""))
        val c = OpenAiVocabChecker(client(HttpStatusCode.InternalServerError to "boom"), "sk", "m")
        assertFailsWith<UpstreamException> { c.check("a", "b", "c") }
        Unit
    }

    @Test fun cleanWordsDedupsAndNormalises() {
        fun w(word: String, article: String = "die", theme: String = "it") = GeneratedWord(word, article, "", "m", "e", theme)
        val out = cleanWords(listOf(w("die Frist", "Die"), w("Frist"), w("Ding", "xyz", "weltall"), w("  "), w("ein Auge zudrücken", "")))
        assertEquals(listOf("Frist", "Ding", "ein Auge zudrücken"), out.map { it.word })
        assertEquals("die", out[0].article); assertEquals("", out[1].article); assertEquals("alltag", out[1].theme)
    }

    @Test fun cleanCheckClampsGrade() {
        fun r(g: Int) = CheckResult(g, true, "f", emptyList<Correction>(), "b")
        assertEquals(5, cleanCheck(r(7)).grade)
        assertEquals(0, cleanCheck(r(-1)).grade)
    }
}
