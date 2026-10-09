package de.omarfourati.redefluss.overview

import java.time.LocalDate
import kotlin.test.*

class StreakTest {
    private val today = LocalDate.of(2026, 10, 8)
    private fun d(daysAgo: Long) = today.minusDays(daysAgo)

    @Test fun cases() {
        assertEquals(0, Streak.days(emptySet(), today))
        assertEquals(1, Streak.days(setOf(d(0)), today))
        assertEquals(3, Streak.days(setOf(d(0), d(1), d(2), d(4)), today))
        // today not practised yet: the streak from yesterday still counts
        assertEquals(2, Streak.days(setOf(d(1), d(2)), today))
        assertEquals(0, Streak.days(setOf(d(2), d(3)), today))
    }
}
