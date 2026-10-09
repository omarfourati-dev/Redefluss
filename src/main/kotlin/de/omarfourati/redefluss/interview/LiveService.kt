package de.omarfourati.redefluss.interview

import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.conversation.toApiException
import de.omarfourati.redefluss.db.MistakeRepo
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
val LIVE_MINUTES = setOf(10, 15, 20)
private const val CACHE_SIZE = 20

/**
 * A started live interview, kept only in memory (the job ad must never reach the database or logs).
 * [claim] hands out the reservation exactly once – to the report (settle) or to a cancel (full refund).
 */
class LiveSession(val id: UUID, val day: LocalDate, val reservedSeconds: Int, val jobAd: String, val role: InterviewRole, val minutes: Int) {
    private val claimed = AtomicBoolean(false)
    fun claim(): Boolean = claimed.compareAndSet(false, true)
    override fun toString(): String = "LiveSession(id=$id, day=$day, reservedSeconds=$reservedSeconds, role=$role, minutes=$minutes)"
}

class LiveService(
    private val realtime: RealtimeSessions, private val sessions: SessionRepo, private val mistakes: MistakeRepo,
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
            synchronized(cache) { cache[id] = LiveSession(id, day, seconds, jobAd, role, req.minutes) }
            metrics.liveSession("started")
            return StartResponse(id.toString(), secret == null, secret?.value, secret?.expiresAt, config.realtimeModel,
                (req.minutes + 2) * 60, req.minutes)
        } catch (e: Exception) {
            // The browser never got a session: give the reservation back (also when the client went away meanwhile).
            withContext(NonCancellable) {
                usage.settleLive(day, seconds)
                id?.let { runCatching { sessions.markCancelled(it) } }
            }
            if (e is UpstreamException) {
                metrics.liveSession("upstream_error")
                throw e.toApiException()
            }
            throw e
        }
    }

    /** Gives the whole reservation back once (mic denied, WebRTC failed, …); repeated or late cancels refund nothing. */
    suspend fun cancel(id: UUID) {
        val entry = session(id)
        if (entry == null && sessions.mode(id) != "interview") throw notFound("Dieses Gespräch gibt es nicht mehr.")
        if (entry != null && entry.claim()) {
            withContext(NonCancellable) {
                usage.settleLive(entry.day, entry.reservedSeconds)
                sessions.markCancelled(id)
            }
        }
    }

    private fun topic(jobAd: String): String =
        jobAd.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(80)
}
