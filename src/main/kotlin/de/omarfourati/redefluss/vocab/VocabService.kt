package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.conversation.MAX_TEXT
import de.omarfourati.redefluss.conversation.toApiException
import de.omarfourati.redefluss.db.MistakeRepo
import de.omarfourati.redefluss.db.NewMistake
import de.omarfourati.redefluss.db.UsageRepo
import de.omarfourati.redefluss.http.ApiException
import de.omarfourati.redefluss.http.badRequest
import de.omarfourati.redefluss.http.notFound
import de.omarfourati.redefluss.metrics.Metrics
import de.omarfourati.redefluss.speech.Audio
import de.omarfourati.redefluss.speech.Correction
import de.omarfourati.redefluss.speech.Speech
import de.omarfourati.redefluss.speech.UpstreamException
import io.ktor.http.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate

@Serializable data class CardDto(val id: Long, val word: String, val article: String, val plural: String, val meaning: String,
    val example: String, val theme: String, val source: String, val dueOn: String, val intervalDays: Int, val reps: Int)
@Serializable data class TodayDto(val newCards: List<CardDto>, val dueCount: Int, val reviewsLeft: Int)
@Serializable data class ReviewResponse(val transcript: String, val grade: Int, val usedCorrectly: Boolean, val feedback: String,
    val corrections: List<Correction>, val better: String, val nextDue: String, val intervalDays: Int, val dueCount: Int)

/** A review answer: a typed sentence, a recording, or "skip" (no AI call). */
data class ReviewRequest(val text: String?, val audio: Audio?, val skip: Boolean)

fun Card.dto() = CardDto(id, word, article, plural, meaning, example, theme, source, dueOn.toString(), intervalDays, reps)

private const val SKIP_GRADE = 1
private const val SKIP_FEEDBACK = "Kein Problem – die Karte kommt morgen wieder."

class VocabService(
    private val vocab: VocabRepo, private val mistakes: MistakeRepo, private val usage: UsageRepo, private val ai: VocabAi,
    private val speech: Speech, private val metrics: Metrics, private val config: Config, private val clock: Clock,
) {
    /** Today's new words – generated on the first call of the day (counted atomically, so only one request generates). */
    suspend fun today(): TodayDto {
        val today = LocalDate.now(clock)
        if (usage.tryCountVocabGeneration(today)) generate(today)
        return TodayDto(
            newCards = vocab.createdOn(today).map { it.dto() },
            dueCount = vocab.dueCount(today),
            reviewsLeft = (config.vocabReviewsPerDay - usage.vocabReviewsOn(today)).coerceAtLeast(0),
        )
    }

    private suspend fun generate(today: LocalDate) {
        try {
            val words = metrics.timed("vocab_generate") { ai.generator.generate(config.vocabPerDay, vocab.recentWords(300), THEMES) }
            val inserted = words.mapNotNull {
                vocab.insertIfNew(NewCard(it.word, it.article, it.plural, it.meaning, it.example, it.theme, "daily"), today)
            }
            metrics.vocabGenerated(inserted.size)
        } catch (e: Exception) {
            // Give the day back so a retry can generate; NonCancellable so a dropped connection still releases it.
            withContext(NonCancellable) { usage.undoVocabGeneration(today) }
            if (e is UpstreamException) throw e.toApiException()
            throw e
        }
    }

    suspend fun due(): List<CardDto> = vocab.due(LocalDate.now(clock), 30).map { it.dto() }

    /** "{article} {word}. {example}" spoken by the voice of the conversation. */
    suspend fun audio(id: Long): Audio {
        val card = card(id)
        val text = "${card.article} ${card.word}".trim() + ". " + card.example
        return try { metrics.timed("voice") { speech.voice.speak(text.trim()) } } catch (e: UpstreamException) { throw e.toApiException() }
    }

    suspend fun review(id: Long, req: ReviewRequest): ReviewResponse {
        if (!req.skip) {
            if ((req.text == null) == (req.audio == null)) throw badRequest("Bitte sprich oder schreib einen Satz.")
            if (req.text != null && req.text.length > MAX_TEXT) throw badRequest("Bitte höchstens $MAX_TEXT Zeichen.")
        }
        val card = card(id)
        val today = LocalDate.now(clock)
        if (req.skip) {
            val next = schedule(card, SKIP_GRADE, today)
            return ReviewResponse("", SKIP_GRADE, false, SKIP_FEEDBACK, emptyList(), card.example,
                next.first.toString(), next.second.intervalDays, vocab.dueCount(today))
        }
        if (!usage.tryCountVocabReview(today, config.vocabReviewsPerDay))
            throw ApiException(HttpStatusCode.TooManyRequests, "Too Many Requests", "Tageslimit für Wiederholungen erreicht. Morgen geht's weiter.")
        try {
            val transcript = req.text?.trim() ?: metrics.timed("transcribe") { speech.transcriber.transcribe(req.audio!!) }.trim()
            if (transcript.isBlank()) throw ApiException(HttpStatusCode.UnprocessableEntity, "Unprocessable Content", "Ich habe nichts verstanden – bitte noch einmal.")
            val result = metrics.timed("vocab_check") { ai.checker.check(card.word, card.meaning, transcript) }
            val next = schedule(card, result.grade, today)
            val now = clock.instant()
            result.corrections.forEach {
                mistakes.record(NewMistake(it.category, it.wrong, it.right, it.rule, transcript), now)
                metrics.mistake(it.category)
            }
            return ReviewResponse(transcript, result.grade, result.usedCorrectly, result.feedback, result.corrections, result.better,
                next.first.toString(), next.second.intervalDays, vocab.dueCount(today))
        } catch (e: UpstreamException) {
            throw e.toApiException()
        }
    }

    private suspend fun schedule(card: Card, rawGrade: Int, today: LocalDate): Pair<LocalDate, Schedule> {
        val grade = rawGrade.coerceIn(0, 5)
        val next = Sm2.next(Schedule(card.ease, card.intervalDays, card.reps), grade)
        val due = today.plusDays(next.intervalDays.toLong())
        vocab.schedule(card.id, next, grade, due)
        metrics.vocabReview(grade)
        return due to next
    }

    private suspend fun card(id: Long): Card = vocab.find(id) ?: throw notFound("Diese Karte gibt es nicht.")
}
