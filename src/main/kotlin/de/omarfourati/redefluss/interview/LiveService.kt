package de.omarfourati.redefluss.interview

import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.conversation.toApiException
import de.omarfourati.redefluss.db.MistakeRepo
import de.omarfourati.redefluss.db.NewMistake
import de.omarfourati.redefluss.db.SessionRepo
import de.omarfourati.redefluss.db.UsageRepo
import de.omarfourati.redefluss.http.ApiException
import de.omarfourati.redefluss.http.badRequest
import de.omarfourati.redefluss.http.notFound
import de.omarfourati.redefluss.metrics.Metrics
import de.omarfourati.redefluss.speech.KnownMistake
import de.omarfourati.redefluss.speech.UpstreamException
import io.ktor.http.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Serializable data class StartRequest(val jobAd: String = "", val role: String = "", val minutes: Int = 0)
@Serializable data class StartResponse(val sessionId: String, val fake: Boolean, val clientSecret: String?, val expiresAt: Long?,
    val model: String, val maxSeconds: Int, val minutes: Int) {
    override fun toString(): String = "StartResponse(sessionId=$sessionId, fake=$fake, clientSecret=${clientSecret?.let { "***" }}, " +
        "expiresAt=$expiresAt, model=$model, maxSeconds=$maxSeconds, minutes=$minutes)"
}
@Serializable data class CancelRequest(val sessionId: String = "")

const val MAX_JOB_AD = 6000
const val MAX_TRANSCRIPT_ENTRIES = 200
const val MAX_ENTRY_CHARS = 2000
private val TRANSCRIPT_ROLES = setOf("interviewer", "omar")
val LIVE_MINUTES = setOf(10, 15, 20)
private const val CACHE_SIZE = 20
/** A cancel refunds only this soon after the start: secret lifetime 60 s + 15 s connect + slack. */
val CANCEL_REFUND_WINDOW: Duration = Duration.ofSeconds(90)

/**
 * A started live interview, kept only in memory (the job ad must never reach the database or logs).
 * [claim] hands out the reservation exactly once – to the report (settle) or to a cancel (full refund).
 */
class LiveSession(val id: UUID, val day: LocalDate, val reservedSeconds: Int, jobAd: String, val role: InterviewRole, val minutes: Int,
                  val startedAt: Instant) {
    /** The job ad lives only until the session is final (reported or cancelled); then it is dropped from memory. */
    @Volatile var jobAd: String = jobAd; private set
    fun dropJobAd() { jobAd = "" }
    private val claimed = AtomicBoolean(false)
    /** The settle-once guard shared by cancel and report: whoever claims first settles the reservation. */
    fun claim(): Boolean = claimed.compareAndSet(false, true)
    val isClaimed: Boolean get() = claimed.get()

    // Report state. A failed report attempt (coach down, timeout, DB error) can be retried: the time stays settled once,
    // only a finished report (or a cancel) makes the session final. [beginReport] keeps two report calls from overlapping.
    private val inFlight = AtomicBoolean(false)
    @Volatile var claimedByReport = false; private set
    @Volatile var settled = false; private set
    @Volatile var mistakesRecorded = false; private set
    @Volatile var reported = false; private set
    fun beginReport(): Boolean = inFlight.compareAndSet(false, true)
    fun endReport() = inFlight.set(false)
    fun claimForReport(): Boolean = (claimedByReport || claim()).also { if (it) claimedByReport = true }
    fun markSettled() { settled = true }
    fun markMistakesRecorded() { mistakesRecorded = true }
    fun markReported() { reported = true }
    /** Cancelled, or claimed by a report that finished. */
    val isFinal: Boolean get() = reported || (isClaimed && !claimedByReport)
    override fun toString(): String = "LiveSession(id=$id, day=$day, reservedSeconds=$reservedSeconds, role=$role, minutes=$minutes)"
}

class LiveService(
    private val realtime: RealtimeSessions, private val reporter: InterviewReporter, private val sessions: SessionRepo, private val mistakes: MistakeRepo,
    private val usage: UsageRepo, private val metrics: Metrics, private val config: Config, private val clock: Clock,
) {
    private val cache = object : LinkedHashMap<UUID, LiveSession>(CACHE_SIZE + 1, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, LiveSession>?) = size > CACHE_SIZE
    }

    /** The in-memory session (for the report), or null after a restart or once 20 newer sessions pushed it out. */
    fun session(id: UUID): LiveSession? = synchronized(cache) { cache[id] }

    suspend fun start(req: StartRequest): StartResponse {
        val jobAd = req.jobAd.trim()
        if (jobAd.isEmpty()) throw badRequest("Bitte füg eine Stellenanzeige ein.")
        if (jobAd.length > MAX_JOB_AD) throw badRequest("Die Stellenanzeige ist zu lang (höchstens $MAX_JOB_AD Zeichen).")
        val role = InterviewRole.entries.firstOrNull { it.name == req.role }
            ?: throw badRequest("Bitte wähl eine Rolle: Recruiterin, Teamleitung oder beides.")
        if (req.minutes !in LIVE_MINUTES) throw badRequest("Bitte wähl 10, 15 oder 20 Minuten.")

        val day = LocalDate.now(clock)
        val seconds = req.minutes * 60
        if (!usage.tryReserveLive(day, seconds, config.liveMinutesPerDay * 60)) {
            metrics.liveSession("limit")
            throw ApiException(HttpStatusCode.TooManyRequests, "Too Many Requests",
                "Für heute sind die Live-Minuten aufgebraucht (${config.liveMinutesPerDay} Minuten). Morgen geht's weiter.")
        }
        var id: UUID? = null
        try {
            id = sessions.create("interview", topic(jobAd), clock.instant())
            val known = mistakes.topExcept("aussprache", 5).map { KnownMistake(it.wrong, it.right, it.category) }
            val secret = metrics.timed("realtime") { realtime.create(LiveSetup(jobAd, role, req.minutes, known)) }
            synchronized(cache) { cache[id] = LiveSession(id, day, seconds, jobAd, role, req.minutes, clock.instant()) }
            metrics.liveSession("started")
            return StartResponse(id.toString(), secret == null, secret?.value, secret?.expiresAt, config.realtimeModel,
                (req.minutes + 2) * 60, req.minutes)
        } catch (e: Exception) {
            // The browser never got a session: give the reservation back (also when the client went away meanwhile).
            withContext(NonCancellable) {
                // A database error here must not mask the original failure.
                runCatching { usage.settleLive(day, seconds) }
                id?.let { runCatching { sessions.markCancelled(it) } }
            }
            if (e is UpstreamException) {
                metrics.liveSession("upstream_error")
                throw e.toApiException()
            }
            throw e
        }
    }

    /**
     * Gives the whole reservation back once (mic denied, WebRTC failed, …) – but only within [CANCEL_REFUND_WINDOW] of the start.
     * Repeated or later cancels refund nothing (the call may have been connected; the report settles the real seconds).
     */
    suspend fun cancel(id: UUID) {
        val entry = session(id)
        if (entry == null && sessions.mode(id) != "interview") throw notFound("Dieses Gespräch gibt es nicht mehr.")
        if (entry != null && entry.claim()) {
            val refund = Duration.between(entry.startedAt, clock.instant()) <= CANCEL_REFUND_WINDOW
            withContext(NonCancellable) {
                if (refund) usage.settleLive(entry.day, entry.reservedSeconds)
                sessions.markCancelled(id)
            }
            entry.dropJobAd()
        }
    }

    /**
     * Settles the reservation (used = min(seconds, maxSeconds); the rest is given back), then asks the coach for the report,
     * records its corrections as mistakes and stores the one-sentence verdict as the session summary.
     * Only completed, non-blank transcript entries count; without anything from Omar there is no report (422), but the time is settled.
     * A failed attempt (upstream, timeout, DB) may be retried: the first attempt's settlement stands, later seconds are ignored.
     * 409 only once reported or cancelled, or while another report call for this session is running.
     */
    suspend fun report(id: UUID, seconds: Int, transcript: List<TranscriptEntry>): InterviewReport {
        val entry = session(id)
        if (entry == null) {
            val summary = if (sessions.mode(id) == "interview") sessions.summary(id) else null
            if (summary.isNullOrEmpty()) throw notFound("Dieses Gespräch gibt es nicht mehr.")
            throw alreadyReported()
        }
        if (entry.isFinal) throw alreadyReported()
        if (transcript.size > MAX_TRANSCRIPT_ENTRIES)
            throw badRequest("Das Gespräch ist zu lang für einen Bericht (höchstens $MAX_TRANSCRIPT_ENTRIES Einträge).")
        if (transcript.any { it.text.length > MAX_ENTRY_CHARS })
            throw badRequest("Ein Eintrag im Gespräch ist zu lang (höchstens $MAX_ENTRY_CHARS Zeichen).")
        if (transcript.any { it.role !in TRANSCRIPT_ROLES }) throw badRequest("Das Gespräch enthält eine unbekannte Rolle.")
        if (seconds < 0) throw badRequest("Die Gesprächsdauer ist ungültig.")
        if (!entry.beginReport()) throw ApiException(HttpStatusCode.Conflict, "Conflict", "Der Bericht wird gerade erstellt.")
        try {
            return report(entry, seconds, transcript)
        } finally {
            entry.endReport()
        }
    }

    /** Runs while [entry] is in flight: settles once (a retry keeps the first settlement), then reports. */
    private suspend fun report(entry: LiveSession, seconds: Int, transcript: List<TranscriptEntry>): InterviewReport {
        if (entry.isFinal || !entry.claimForReport()) throw alreadyReported()
        if (!entry.settled) {
            // Charge the actual seconds up to the hard stop ((minutes + 2) * 60): refund the rest, or add the overrun
            // (which may exceed the daily budget by at most 2 minutes).
            val used = seconds.coerceAtMost((entry.minutes + 2) * 60)
            withContext(NonCancellable) {
                if (used < entry.reservedSeconds) usage.settleLive(entry.day, entry.reservedSeconds - used)
                else usage.chargeLive(entry.day, used - entry.reservedSeconds)
            }
            entry.markSettled()
            metrics.liveSeconds(used)
        }

        val lines = transcript.map { TranscriptEntry(it.role, it.text.trim()) }.filter { it.text.isNotEmpty() }
        val answers = lines.filter { it.role == "omar" }.map { it.text }
        if (answers.isEmpty())
            throw ApiException(HttpStatusCode.UnprocessableEntity, "Unprocessable Content",
                "Im Gespräch war nichts von dir zu hören – deshalb gibt es keinen Bericht.")

        val report = try {
            metrics.timed("interview_report") { reporter.report(entry.jobAd, entry.role, lines) }
        } catch (e: UpstreamException) {
            metrics.liveSession("upstream_error")
            throw e.toApiException()
        }

        val now = clock.instant()
        val fallback = answers.joinToString(" ").take(300)
        if (!entry.mistakesRecorded) {
            report.corrections.forEach { c ->
                mistakes.record(NewMistake(c.category, c.wrong, c.right, c.rule, answers.firstOrNull { c.wrong in it } ?: fallback), now)
                metrics.mistake(c.category)
            }
            entry.markMistakesRecorded()
        }
        sessions.finishInterview(entry.id, report.overall, answers.size, report.corrections.size, now)
        entry.markReported()
        entry.dropJobAd()
        metrics.liveSession("reported")
        return report
    }

    private fun alreadyReported() = ApiException(HttpStatusCode.Conflict, "Conflict", "Dieses Gespräch ist schon ausgewertet.")

    private fun topic(jobAd: String): String =
        jobAd.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(80)
}
