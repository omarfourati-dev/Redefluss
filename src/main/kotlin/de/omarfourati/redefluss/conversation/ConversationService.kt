package de.omarfourati.redefluss.conversation

import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.db.*
import de.omarfourati.redefluss.http.ApiException
import de.omarfourati.redefluss.http.badRequest
import de.omarfourati.redefluss.metrics.Metrics
import de.omarfourati.redefluss.mistakes.MistakeKey
import de.omarfourati.redefluss.speech.*
import de.omarfourati.redefluss.vocab.NewCard
import de.omarfourati.redefluss.vocab.VocabRepo
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

@Serializable data class TurnResponse(
    val transcript: String, val corrections: List<Correction>, val natural: String,
    val reply: String, val replyAudio: String?, val replyAudioType: String?, val turnsLeft: Int,
)
data class TurnRequest(val sessionId: UUID, val text: String?, val audio: Audio?, val history: List<HistoryTurn>, val speak: Boolean)

const val MAX_TEXT = 1000
const val MAX_AUDIO = 10 * 1024 * 1024
val AUDIO_TYPES = setOf("audio/webm", "audio/ogg", "audio/mp4", "audio/x-m4a", "audio/mpeg", "audio/wav", "audio/x-wav")
private const val UPSTREAM_DETAIL = "Der Sprachdienst antwortet gerade nicht. Bitte versuch es noch einmal."
private val WHITESPACE = Regex("""\s+""")

/** Upstream failure → 502, timeout → 504; always the same neutral detail (upstream bodies may contain keys or user text). */
fun UpstreamException.toApiException() = ApiException(
    if (timeout) HttpStatusCode.GatewayTimeout else HttpStatusCode.BadGateway,
    if (timeout) "Gateway Timeout" else "Bad Gateway", UPSTREAM_DETAIL)

/** Short word-choice corrections ("machen" → "treffen") are worth learning as vocabulary cards. */
fun Correction.isVocabWorthy(): Boolean {
    val right = right.trim()
    return MistakeKey.category(category) == "wortwahl" && right.isNotEmpty() && right.length <= 40 && right.split(WHITESPACE).size <= 4
}

class ConversationService(
    private val sessions: SessionRepo, private val mistakes: MistakeRepo, private val usage: UsageRepo,
    private val vocab: VocabRepo, private val speech: Speech, private val metrics: Metrics, private val config: Config, private val clock: Clock,
) {
    suspend fun startSession(topic: String?): UUID =
        sessions.create("conversation", topic?.trim()?.take(80)?.ifBlank { null } ?: "Freies Gespräch", clock.instant())

    suspend fun turn(req: TurnRequest): TurnResponse {
        if ((req.text == null) == (req.audio == null)) throw badRequest("Bitte sprich oder schreib einen Satz.")
        if (req.text != null && req.text.length > MAX_TEXT) throw badRequest("Bitte höchstens $MAX_TEXT Zeichen.")
        if (!sessions.exists(req.sessionId)) throw ApiException(HttpStatusCode.NotFound, "Not Found", "Dieses Gespräch gibt es nicht mehr. Bitte starte ein neues.")
        val today = LocalDate.now(clock)
        if (!usage.tryCountTurn(today, config.turnsPerDay)) {
            metrics.turn("limit")
            throw ApiException(HttpStatusCode.TooManyRequests, "Too Many Requests", "Tageslimit erreicht (${config.turnsPerDay} Runden). Morgen geht's weiter.")
        }
        try {
            val transcript = req.text?.trim() ?: metrics.timed("transcribe") { speech.transcriber.transcribe(req.audio!!) }
            if (transcript.isBlank()) {
                metrics.turn("no_speech")
                throw ApiException(HttpStatusCode.UnprocessableEntity, "Unprocessable Content", "Ich habe nichts verstanden – bitte noch einmal.")
            }
            // Pronunciation mistakes ("Brücke" → "Brücke") would crowd out the grammar the coach can actually practise.
            val known = mistakes.topExcept("aussprache", 5).map { KnownMistake(it.wrong, it.right, it.category) }
            val topic = topicOf(req.sessionId)
            val coach = metrics.timed("coach") { speech.coach.respond(CoachInput(transcript, topic, clean(req.history), known)) }
            val voice = if (req.speak) try { metrics.timed("voice") { speech.voice.speak(coach.reply) } } catch (_: UpstreamException) { null } else null

            val now = clock.instant()
            coach.corrections.forEach {
                mistakes.record(NewMistake(it.category, it.wrong, it.right, it.rule, transcript), now)
                metrics.mistake(it.category)
            }
            coach.corrections.filter { it.isVocabWorthy() }.forEach {
                vocab.insertIfNew(NewCard(word = it.right.trim(), article = "", plural = "", meaning = it.rule,
                    example = coach.natural, theme = "alltag", source = "mistake"), today)
            }
            sessions.touch(req.sessionId, now, coach.corrections.size)
            metrics.turn("ok")
            return TurnResponse(transcript, coach.corrections, coach.natural, coach.reply,
                voice?.let { Base64.getEncoder().encodeToString(it.bytes) }, voice?.contentType,
                (config.turnsPerDay - usage.turnsOn(today)).coerceAtLeast(0))
        } catch (e: UpstreamException) {
            metrics.turn(if (e.timeout) "timeout" else "upstream_error")
            throw e.toApiException()
        }
    }

    private val topics = java.util.concurrent.ConcurrentHashMap<UUID, String>()
    private suspend fun topicOf(id: UUID): String = topics[id] ?: sessions.topic(id).also { topics[id] = it }

    /** Only the last 20 turns, only user/assistant, each at most 500 characters – never a client-supplied system prompt. */
    private fun clean(history: List<HistoryTurn>) = history
        .filter { it.role == "user" || it.role == "assistant" }
        .takeLast(20)
        .map { HistoryTurn(it.role, it.text.take(500)) }
}
