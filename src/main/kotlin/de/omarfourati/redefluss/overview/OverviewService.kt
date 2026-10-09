package de.omarfourati.redefluss.overview

import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.db.*
import de.omarfourati.redefluss.http.ApiException
import de.omarfourati.redefluss.vocab.VocabRepo
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate

@Serializable data class MistakeDto(val id: Long, val category: String, val wrong: String, val right: String,
    val rule: String, val example: String, val count: Int, val lastSeen: String, val resolved: Boolean)
@Serializable data class OverviewDto(val streakDays: Int, val minutesToday: Int, val turnsToday: Int,
    val turnsLeft: Int, val topMistakes: List<MistakeDto>, val vocabDue: Int)
@Serializable data class ResolveRequest(val resolved: Boolean)

fun Mistake.dto() = MistakeDto(id, category, wrong, right, rule, example, count, lastSeen.toString(), resolved)

class OverviewService(
    private val usage: UsageRepo, private val sessions: SessionRepo, private val mistakes: MistakeRepo,
    private val vocab: VocabRepo, private val config: Config, private val clock: Clock,
) {
    suspend fun overview(): OverviewDto {
        val today = LocalDate.now(clock)
        val start = today.atStartOfDay(clock.zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(clock.zone).toInstant()
        val turns = usage.turnsOn(today)
        return OverviewDto(
            streakDays = Streak.days(usage.activeDaysSince(today.minusDays(400)), today),
            minutesToday = sessions.minutesBetween(start, end),
            turnsToday = turns,
            turnsLeft = (config.turnsPerDay - turns).coerceAtLeast(0),
            topMistakes = mistakes.top(5).map { it.dto() },
            vocabDue = vocab.dueCount(today),
        )
    }

    suspend fun mistakes(status: MistakeStatus) = mistakes.list(status).map { it.dto() }

    suspend fun resolve(id: Long, resolved: Boolean) {
        if (!mistakes.setResolved(id, resolved)) throw ApiException(HttpStatusCode.NotFound, "Not Found", "Diesen Fehler gibt es nicht.")
    }
}
