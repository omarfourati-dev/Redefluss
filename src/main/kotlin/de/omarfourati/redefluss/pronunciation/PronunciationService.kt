package de.omarfourati.redefluss.pronunciation

import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.conversation.toApiException
import de.omarfourati.redefluss.db.AzureCount
import de.omarfourati.redefluss.db.MistakeRepo
import de.omarfourati.redefluss.db.NewMistake
import de.omarfourati.redefluss.db.UsageRepo
import de.omarfourati.redefluss.http.ApiException
import de.omarfourati.redefluss.http.badRequest
import de.omarfourati.redefluss.metrics.Metrics
import de.omarfourati.redefluss.speech.Audio
import de.omarfourati.redefluss.speech.UpstreamException
import de.omarfourati.redefluss.speech.Voice
import de.omarfourati.redefluss.vocab.VocabRepo
import io.ktor.client.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate
import kotlin.math.ceil

@Serializable data class QuotaDto(val enabled: Boolean, val secondsLeft: Int, val secondsPerMonth: Int, val todayLeft: Int)
@Serializable data class ExerciseGroups(val mistakes: List<Exercise>, val vocab: List<Exercise>, val sounds: List<Exercise>)
@Serializable data class ExercisesDto(val enabled: Boolean, val quota: QuotaDto, val groups: ExerciseGroups)
@Serializable data class AssessResponse(val assessment: Assessment, val quota: QuotaDto, val weakWords: List<String>)

const val MAX_REFERENCE = 300
/** 30 s of 16 kHz mono 16-bit PCM is 960 000 bytes plus header. */
const val MAX_WAV = 1024 * 1024
private const val MIN_SECONDS = 0.5
private const val MAX_SECONDS = 30.0
private const val WEAK = 60.0
private const val MONTH_TEXT = "Das kostenlose Aussprache-Kontingent für diesen Monat ist aufgebraucht – ab dem 1. geht es weiter."
private const val BUSY_TEXT = "Der Aussprache-Dienst ist gerade ausgelastet oder das kostenlose Kontingent ist aufgebraucht – bitte versuch es später noch einmal."
private val REGION = Regex("[a-z0-9-]+")
private val EDGE = Regex("""^[\s.,!?;:"'„“‚‘»«()\-–]+|[\s.,!?;:"'„“‚‘»«()\-–]+$""")

/**
 * The scorer for the configured PRONUNCIATION mode, or null when pronunciation is disabled
 * (azure without key, or a region that is not a plain host label). [warn] never receives the key.
 */
fun scorerFor(config: Config, http: () -> HttpClient, warn: (String) -> Unit): PronunciationScorer? {
    if (config.pronunciation == "fake") return FakeScorer()
    val key = config.azureKey
    if (key == null) { warn("Aussprache deaktiviert: AZURE_SPEECH_KEY fehlt."); return null }
    if (!REGION.matches(config.azureRegion)) { warn("Aussprache deaktiviert: AZURE_SPEECH_REGION ist ungültig."); return null }
    return AzureScorer(http(), key, config.azureRegion)
}

private fun tooMany(detail: String) = ApiException(HttpStatusCode.TooManyRequests, "Too Many Requests", detail)

class PronunciationService(
    private val scorer: PronunciationScorer?, private val mistakes: MistakeRepo, private val vocab: VocabRepo,
    private val usage: UsageRepo, private val voice: Voice, private val metrics: Metrics, private val config: Config, private val clock: Clock,
) {
    val enabled: Boolean get() = scorer != null

    /** 503 for every pronunciation route while Azure is not set up; the rest of the app keeps working. */
    fun requireEnabled(): PronunciationScorer = scorer
        ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "Service Unavailable", "Aussprache ist noch nicht eingerichtet.")

    private fun today(): LocalDate = LocalDate.now(clock)
    private fun monthStart(): LocalDate = today().withDayOfMonth(1)

    suspend fun monthSecondsUsed(): Int = monthStart().let { usage.azureSecondsBetween(it, it.plusMonths(1)) }

    /** Sets the month gauge from the database (at startup and after each counted clip). */
    suspend fun refreshGauge() = metrics.azureSecondsMonth(monthSecondsUsed())

    /** Also refreshes the gauge, so it drops to the new month's sum after the 1st without waiting for a counted clip. */
    suspend fun quota(): QuotaDto {
        requireEnabled()
        val used = monthSecondsUsed()
        metrics.azureSecondsMonth(used)
        return QuotaDto(true, (config.azureSecondsPerMonth - used).coerceAtLeast(0), config.azureSecondsPerMonth,
            (config.pronunciationsPerDay - usage.pronunciationsOn(today())).coerceAtLeast(0))
    }

    /** With [cardId] that card's example sentence is always the first vocab exercise (when it exists and fits), without duplicates. */
    suspend fun exercises(cardId: Long? = null): ExercisesDto {
        requireEnabled()
        val fromMistakes = mistakes.topExcept("aussprache", 20).mapNotNull { m ->
            val text = Exercises.corrected(m.example, m.wrong, m.right)?.takeIf { it.isNotBlank() && it.length <= MAX_REFERENCE }
                ?: m.right.takeIf { it.isNotBlank() && it.length <= MAX_REFERENCE }
            text?.let { Exercise("mistake-${m.id}", "mistake", it.trim(), m.rule) }
        }.take(5)
        val wanted = cardId?.let { vocab.find(it) }
        val fromVocab = (listOfNotNull(wanted) + vocab.forPronunciation(today(), 5).filter { it.id != wanted?.id })
            .filter { it.example.trim().length <= MAX_REFERENCE }
            .map { Exercise("card-${it.id}", "vocab", it.example.trim(), "${it.article} ${it.word}".trim()) }
        return ExercisesDto(true, quota(), ExerciseGroups(fromMistakes, fromVocab, Exercises.SOUNDS))
    }

    suspend fun speak(rawText: String, slow: Boolean): Audio {
        requireEnabled()
        val text = reference(rawText)
        if (!usage.tryCountTurn(today(), config.turnsPerDay))
            throw tooMany("Tageslimit erreicht (${config.turnsPerDay} Runden). Morgen geht's weiter.")
        return try { metrics.timed("voice") { voice.speak(text, slow) } } catch (e: UpstreamException) { throw e.toApiException() }
    }

    suspend fun assess(rawText: String?, audio: Audio?): AssessResponse {
        val scorer = requireEnabled()
        val text = reference(rawText)
        if (audio == null) throw badRequest("Bitte nimm den Satz auf.")
        val seconds = clipSeconds(audio.bytes)

        val today = today()
        val monthStart = today.withDayOfMonth(1)
        when (usage.tryCountAzure(today, monthStart, seconds, config.azureSecondsPerMonth, config.pronunciationsPerDay)) {
            AzureCount.MONTH -> { metrics.pronunciation("quota"); throw tooMany(MONTH_TEXT) }
            AzureCount.DAY -> { metrics.pronunciation("limit"); throw tooMany("Tageslimit für Aussprache erreicht. Morgen geht's weiter.") }
            AzureCount.OK -> refreshGauge()
        }

        val assessment = try {
            metrics.timed("pronunciation") { scorer.assess(audio.bytes, text) }
        } catch (e: NothingRecognizedException) {
            throw ApiException(HttpStatusCode.UnprocessableEntity, "Unprocessable Content", "Ich habe nichts verstanden – bitte noch einmal.")
        } catch (e: QuotaExceededException) {
            metrics.pronunciation("quota")
            throw tooMany(BUSY_TEXT)
        } catch (e: UpstreamException) {
            metrics.pronunciation(if (e.timeout) "timeout" else "upstream_error")
            throw e.toApiException()
        }

        val weak = weakWords(assessment)
        val now = clock.instant()
        weak.forEach { (word, rule) ->
            mistakes.record(NewMistake("aussprache", word, word, rule, text), now)
            metrics.mistake("aussprache")
        }
        metrics.pronunciation("ok")
        return AssessResponse(assessment, quota(), weak.map { it.first })
    }

    /** Validates the format first (so hostile header values never reach the duration maths), then the duration; whole seconds, rounded up. */
    private fun clipSeconds(bytes: ByteArray): Int {
        val wav = WavInfo.parse(bytes)
        if (wav == null || wav.sampleRate != 16000 || wav.channels != 1 || wav.bitsPerSample != 16) {
            metrics.pronunciation("bad_audio")
            throw ApiException(HttpStatusCode.UnsupportedMediaType, "Unsupported Media Type", "Dieses Audioformat wird nicht unterstützt.")
        }
        val seconds = wav.seconds
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            metrics.pronunciation("bad_audio")
            throw badRequest("Die Aufnahme muss 0,5 bis 30 Sekunden lang sein.")
        }
        return ceil(seconds).toInt()
    }

    private fun reference(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_REFERENCE) throw badRequest("Der Satz muss 1 bis $MAX_REFERENCE Zeichen lang sein.")
        return text
    }

    /** Words below 60 or marked Mispronunciation/Omission, once each, in sentence order, with the rule for the mistake list. */
    private fun weakWords(a: Assessment): List<Pair<String, String>> {
        val seen = HashSet<String>()
        return a.words.mapNotNull { w ->
            val word = w.word.replace(EDGE, "")
            if (word.isEmpty() || w.errorType == "Insertion") return@mapNotNull null
            val omitted = w.errorType == "Omission"
            if (!omitted && w.score >= WEAK && w.errorType != "Mispronunciation") return@mapNotNull null
            if (!seen.add(word.lowercase())) return@mapNotNull null
            val sounds = w.phonemes.filter { it.score < WEAK }.map { it.phoneme }.distinct()
            val rule = when {
                omitted -> "Aussprache von „$word“ üben – das Wort fehlte"
                sounds.isNotEmpty() -> "Aussprache von „$word“ üben – schwache Laute: ${sounds.joinToString(", ")}"
                else -> "Aussprache von „$word“ üben"
            }
            word to rule
        }
    }
}
