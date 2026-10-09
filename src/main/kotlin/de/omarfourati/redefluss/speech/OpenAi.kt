package de.omarfourati.redefluss.speech

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

private const val BASE = "https://api.openai.com/v1"
private val json = Json { ignoreUnknownKeys = true }

fun fileNameFor(contentType: String): String = "audio." + when (contentType.substringBefore(';').trim().lowercase()) {
    "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac" -> "m4a"
    "audio/ogg" -> "ogg"
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
    else -> "webm"
}

/** Runs one upstream call with a timeout; any failure becomes an UpstreamException without details. */
private suspend fun <T> upstream(stage: String, timeoutMs: Long, block: suspend () -> T): T = try {
    withTimeout(timeoutMs) { block() }
} catch (e: TimeoutCancellationException) {
    throw UpstreamException(stage, timeout = true)
} catch (e: UpstreamException) {
    throw e
} catch (e: Exception) {
    throw UpstreamException(stage, timeout = false)
}

private suspend fun HttpResponse.okBytes(stage: String, max: Int): ByteArray {
    if (!status.isSuccess()) throw UpstreamException(stage, timeout = false)
    val bytes = bodyAsBytes()
    if (bytes.size > max) throw UpstreamException(stage, timeout = false)
    return bytes
}

class OpenAiTranscriber(private val http: HttpClient, private val key: String, private val model: String,
                        private val timeoutMs: Long = 20_000) : Transcriber {
    override suspend fun transcribe(audio: Audio): String = upstream("transcribe", timeoutMs) {
        val res = http.submitFormWithBinaryData("$BASE/audio/transcriptions", formData {
            append("model", model)
            append("language", "de")
            append("response_format", "json")
            append("prompt", "Wörtliche Transkription eines Deutschlerners. Grammatikfehler nicht korrigieren, nichts glätten, Füllwörter behalten.")
            append("file", audio.bytes, Headers.build {
                append(HttpHeaders.ContentType, audio.contentType.substringBefore(';'))
                append(HttpHeaders.ContentDisposition, "filename=\"${fileNameFor(audio.contentType)}\"")
            })
        }) { bearerAuth(key) }
        json.parseToJsonElement(String(res.okBytes("transcribe", 1_000_000))).jsonObject["text"]!!.jsonPrimitive.content.trim()
    }
}

class OpenAiCoach(private val http: HttpClient, private val key: String, private val model: String,
                  private val timeoutMs: Long = 30_000) : Coach {
    override suspend fun respond(input: CoachInput): CoachReply {
        repeat(2) {
            val content = upstream("coach", timeoutMs) {
                val res = http.post("$BASE/chat/completions") {
                    bearerAuth(key)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody(input).toString())
                }
                json.parseToJsonElement(String(res.okBytes("coach", 1_000_000))).jsonObject["choices"]!!.jsonArray[0]
                    .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            }
            val parsed = runCatching { json.decodeFromString(CoachReply.serializer(), content) }.getOrNull()
            if (parsed != null && parsed.reply.isNotBlank() && parsed.natural.isNotBlank()) return cleanReply(parsed)
        }
        throw UpstreamException("coach", timeout = false)
    }

    private fun requestBody(input: CoachInput) = buildJsonObject {
        put("model", model)
        put("temperature", 0.4)
        putJsonObject("response_format") {
            put("type", "json_schema")
            putJsonObject("json_schema") { put("name", "coach_reply"); put("strict", true); put("schema", SCHEMA) }
        }
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", systemPrompt(input)) }
            input.history.forEach { t -> addJsonObject { put("role", if (t.role == "assistant") "assistant" else "user"); put("content", t.text) } }
            addJsonObject { put("role", "user"); put("content", input.utterance) }
        }
    }

    companion object {
        val SCHEMA: JsonObject = Json.parseToJsonElement("""
        {"type":"object","additionalProperties":false,"required":["corrections","natural","reply"],
         "properties":{
          "corrections":{"type":"array","items":{"type":"object","additionalProperties":false,
            "required":["wrong","right","rule","category"],
            "properties":{"wrong":{"type":"string"},"right":{"type":"string"},"rule":{"type":"string"},
              "category":{"type":"string","enum":["artikel","kasus","verbstellung","konjugation","schreibung","praeposition","wortwahl","aussprache","sonstiges"]}}}},
          "natural":{"type":"string"},
          "reply":{"type":"string"}}}
        """).jsonObject

        fun systemPrompt(input: CoachInput): String = buildString {
            appendLine("Du bist Omars Deutsch-Coach und Gesprächspartner. Omar spricht Deutsch auf B2/C1-Niveau und will wie ein Muttersprachler klingen.")
            appendLine("Thema des Gesprächs: ${input.topic.ifBlank { "frei" }}.")
            appendLine("Für Omars letzte Äußerung (gesprochen und transkribiert):")
            appendLine("1. corrections: nur echte Fehler (Grammatik, Artikel, Kasus, Wortstellung, Konjugation, Wortwahl, Präposition, Groß-/Kleinschreibung nur bei getipptem Text). 'wrong' ist der exakte Ausschnitt aus Omars Satz, 'right' die Korrektur, 'rule' eine kurze Regel in einfachem Deutsch (max. 1 Satz). Natürliche Umgangssprache ist kein Fehler. Keine Fehler erfinden; ein fehlerfreier Satz hat eine leere Liste.")
            appendLine("2. natural: derselbe Inhalt so, wie ein Muttersprachler ihn im Gespräch sagen würde.")
            appendLine("3. reply: deine Antwort als Gesprächspartner, 1–3 kurze Sätze, natürliches Hochdeutsch, mit einer Rückfrage, damit das Gespräch weiterläuft. Baue ab und zu ein nützliches Wort oder eine Redewendung ein.")
            if (input.known.isNotEmpty()) {
                appendLine("Omars häufige Fehler (achte besonders darauf und gib ihm Gelegenheiten, es richtig zu machen):")
                input.known.forEach { appendLine("- „${it.wrong}“ → „${it.right}“ (${it.category})") }
            }
        }
    }
}

class OpenAiVoice(private val http: HttpClient, private val key: String, private val model: String, private val voice: String,
                  private val timeoutMs: Long = 20_000) : Voice {
    override suspend fun speak(text: String): Audio = upstream("voice", timeoutMs) {
        val res = http.post("$BASE/audio/speech") {
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model); put("voice", voice); put("input", text); put("response_format", "mp3")
                put("instructions", "Sprich natürliches Hochdeutsch, freundlich, in ruhigem, normalem Tempo.")
            }.toString())
        }
        Audio(res.okBytes("voice", 5_000_000), "audio/mpeg")
    }
}
