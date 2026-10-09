package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.speech.OpenAiCoach
import de.omarfourati.redefluss.speech.UpstreamException
import de.omarfourati.redefluss.speech.chatJson
import io.ktor.client.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true }

private val THEME_HINTS = mapOf(
    "it" to "IT und Bewerbung (Arbeitswelt, Software, Vorstellungsgespräch)",
    "alltag" to "Alltag (Wohnen, Behörden, Einkaufen, Freizeit)",
    "redewendung" to "Redewendungen und feste Ausdrücke",
)

private val WORDS_SCHEMA: JsonObject = Json.parseToJsonElement("""
    {"type":"object","additionalProperties":false,"required":["words"],"properties":{"words":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["word","article","plural","meaning","example","theme"],"properties":{"word":{"type":"string"},"article":{"type":"string","enum":["der","die","das",""]},"plural":{"type":"string"},"meaning":{"type":"string"},"example":{"type":"string"},"theme":{"type":"string","enum":["it","alltag","redewendung"]}}}}}}
""").jsonObject

private val CHECK_SCHEMA: JsonObject = Json.parseToJsonElement("""
    {"type":"object","additionalProperties":false,"required":["grade","usedCorrectly","feedback","corrections","better"],"properties":{"grade":{"type":"integer"},"usedCorrectly":{"type":"boolean"},"feedback":{"type":"string"},"corrections":${OpenAiCoach.SCHEMA["properties"]!!.jsonObject["corrections"]},"better":{"type":"string"}}}
""").jsonObject

class OpenAiVocabGenerator(private val http: HttpClient, private val key: String, private val model: String,
                           private val timeoutMs: Long = 30_000) : VocabGenerator {
    override suspend fun generate(count: Int, avoid: List<String>, themes: List<String>): List<GeneratedWord> {
        val system = buildString {
            append("Erzeuge genau $count nützliche deutsche Wörter oder Redewendungen für Omar (Niveau B2–C1, Full-Stack-Entwickler auf Jobsuche in Deutschland). ")
            append("Themen: ${themes.joinToString("; ") { "$it = ${THEME_HINTS[it] ?: it}" }}. ")
            append("Nomen mit Artikel (der/die/das) und Plural mit Artikel („die Fristen“), Verben im Infinitiv mit Perfekt im Feld plural („hat … gekündigt“), Redewendungen ohne Artikel. ")
            append("meaning: einfache deutsche Erklärung in einem Satz. example: ein natürlicher Beispielsatz aus Omars Alltag oder Bewerbung. ")
            append("Nicht verwenden (schon gelernt): ${avoid.take(300).joinToString(", ")}.")
        }
        repeat(2) {
            val content = chatJson(http, key, model, "vocab_generate", timeoutMs, system, listOf("user" to "Bitte $count Wörter."), "daily_words", WORDS_SCHEMA)
            val parsed = runCatching { json.decodeFromString(WordBatch.serializer(), content) }.getOrNull()
            if (parsed != null) return cleanWords(parsed.words).take(count)
        }
        throw UpstreamException("vocab_generate", timeout = false)
    }
}

class OpenAiVocabChecker(private val http: HttpClient, private val key: String, private val model: String,
                         private val timeoutMs: Long = 30_000) : VocabChecker {
    override suspend fun check(word: String, meaning: String, sentence: String): CheckResult {
        val system = "Omar übt das Wort „$word“ ($meaning). Bewerte seinen Satz: grade 0–5 (5 = natürlich und korrekt mit dem Wort, 3 = verständlich mit kleinen Fehlern, ≤ 2 = Wort fehlt oder falsch verwendet). " +
            "feedback: 1–2 kurze Sätze auf Deutsch, freundlich, du-Form. corrections: echte Fehler wie im Gespräch (wrong exakt aus dem Satz). " +
            "better: der Satz so, wie ein Muttersprachler ihn sagen würde."
        repeat(2) {
            val content = chatJson(http, key, model, "vocab_check", timeoutMs, system, listOf("user" to sentence), "vocab_check", CHECK_SCHEMA)
            val parsed = runCatching { json.decodeFromString(CheckResult.serializer(), content) }.getOrNull()
            if (parsed != null) return cleanCheck(parsed)
        }
        throw UpstreamException("vocab_check", timeout = false)
    }
}
