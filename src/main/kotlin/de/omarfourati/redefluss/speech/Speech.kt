package de.omarfourati.redefluss.speech

import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.mistakes.MistakeKey
import io.ktor.client.*
import kotlinx.serialization.Serializable

class Audio(val bytes: ByteArray, val contentType: String)

@Serializable data class HistoryTurn(val role: String, val text: String)
data class KnownMistake(val wrong: String, val right: String, val category: String)
data class CoachInput(val utterance: String, val topic: String, val history: List<HistoryTurn>, val known: List<KnownMistake>)
@Serializable data class Correction(val wrong: String, val right: String, val rule: String, val category: String)
@Serializable data class CoachReply(val corrections: List<Correction>, val natural: String, val reply: String)

interface Transcriber { suspend fun transcribe(audio: Audio): String }
interface Coach { suspend fun respond(input: CoachInput): CoachReply }
/** slow = true: slow, clear, word by word – for pronunciation practice. */
interface Voice { suspend fun speak(text: String, slow: Boolean = false): Audio }

/** The message is deliberately generic: upstream bodies may contain keys or user text. */
class UpstreamException(val stage: String, val timeout: Boolean) :
    RuntimeException("upstream $stage ${if (timeout) "timeout" else "error"}")

data class Speech(val transcriber: Transcriber, val coach: Coach, val voice: Voice)

fun speechFor(config: Config, http: HttpClient): Speech = when (config.speech) {
    "fake" -> Speech(FakeTranscriber(), FakeCoach(), FakeVoice())
    else -> {
        val key = config.openAiKey!!
        Speech(OpenAiTranscriber(http, key, config.transcribeModel), OpenAiCoach(http, key, config.aiModel),
            OpenAiVoice(http, key, config.ttsModel, config.ttsVoice))
    }
}

/** Only real corrections, known categories, at most 6, trimmed. */
fun cleanReply(raw: CoachReply): CoachReply = CoachReply(
    corrections = raw.corrections
        .map { Correction(it.wrong.trim(), it.right.trim(), it.rule.trim(), MistakeKey.category(it.category)) }
        .filter { it.wrong.isNotEmpty() && it.right.isNotEmpty() && !it.wrong.equals(it.right, ignoreCase = false) }
        .take(6),
    natural = raw.natural.trim(),
    reply = raw.reply.trim(),
)
