package de.omarfourati.redefluss.overview

import java.time.LocalDate

object Streak {
    /** Consecutive practice days ending today – or ending yesterday, so the streak survives until today's practice. */
    fun days(active: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in active) today else today.minusDays(1)
        var count = 0
        while (day in active) { count++; day = day.minusDays(1) }
        return count
    }
}
