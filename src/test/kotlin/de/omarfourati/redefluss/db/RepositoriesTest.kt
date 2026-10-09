package de.omarfourati.redefluss.db

import de.omarfourati.redefluss.TestDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class RepositoriesTest {
    private val db = TestDb.reset()
    private val t0 = Instant.parse("2026-10-08T08:00:00Z")

    @Test fun users() = runBlocking {
        val users = UserRepo(db)
        assertEquals(0L, users.count())
        val u = users.create("omar@example.de", "hash1", t0)
        assertEquals(1L, users.count())
        assertEquals(0, u.tokenVersion)
        assertEquals(u, users.findByEmail("omar@example.de"))
        assertEquals(u, users.findById(u.id))
        assertNull(users.findByEmail("nobody@example.de"))
        val changed = users.setPassword(u.id, "hash2")
        assertEquals("hash2", changed.passwordHash)
        assertEquals(1, changed.tokenVersion)
    }

    @Test fun mistakesAreGroupedCountedAndRanked() = runBlocking {
        val repo = MistakeRepo(db)
        val zeit = NewMistake("artikel", "den ganzen Zeit", "die ganze Zeit", "Zeit ist feminin.", "Ich habe den ganzen Zeit gewartet.")
        val id1 = repo.record(zeit, t0)
        val id2 = repo.record(zeit.copy(wrong = "Den ganzen Zeit.", example = "Neuer Satz."), t0.plusSeconds(60))
        assertEquals(id1, id2)
        repo.record(NewMistake("konjugation", "du muss", "du musst", "du → -st", "Du muss gehen."), t0)
        val top = repo.top(5)
        assertEquals(listOf(2, 1), top.map { it.count })
        assertEquals("Neuer Satz.", top.first().example)
        assertEquals("die ganze Zeit", top.first().right)

        assertTrue(repo.setResolved(id1, true))
        assertEquals(listOf("du muss"), repo.top(5).map { it.wrong })
        assertEquals(1, repo.list(MistakeStatus.RESOLVED).size)
        assertEquals(2, repo.list(MistakeStatus.ALL).size)
        // the same mistake again re-opens it
        repo.record(zeit, t0.plusSeconds(120))
        assertEquals(3, repo.list(MistakeStatus.OPEN).first().count)
        assertFalse(repo.setResolved(999_999, true))
    }

    @Test fun sessionsAndMinutes() = runBlocking {
        val repo = SessionRepo(db)
        val id = repo.create("conversation", "Arbeit", t0)
        assertTrue(repo.exists(id))
        assertFalse(repo.exists(UUID.randomUUID()))
        assertTrue(repo.touch(id, t0.plusSeconds(150), mistakes = 2))
        assertFalse(repo.touch(UUID.randomUUID(), t0, 0))
        // 150 s → 3 minutes; a second session with one turn counts at least 1 minute
        val other = repo.create("conversation", "Alltag", t0.plusSeconds(3600))
        repo.touch(other, t0.plusSeconds(3600), 0)
        assertEquals(4, repo.minutesBetween(t0.minusSeconds(1), t0.plusSeconds(7200)))
        assertEquals(0, repo.minutesBetween(t0.plusSeconds(9000), t0.plusSeconds(9999)))
    }

    @Test fun usageLimitIsAtomic() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 8)
        assertTrue(usage.tryCountTurn(day, 2))
        assertTrue(usage.tryCountTurn(day, 2))
        assertFalse(usage.tryCountTurn(day, 2))
        assertEquals(2, usage.turnsOn(day))
        assertFalse(usage.tryCountTurn(day.plusDays(1), 0))
        assertEquals(setOf(day), usage.activeDaysSince(day.minusDays(30)))
    }

    @Test fun vocabCounters() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 9)
        assertTrue(usage.tryCountVocabGeneration(day))
        assertFalse(usage.tryCountVocabGeneration(day))
        assertTrue(usage.tryCountVocabReview(day, 2)); assertTrue(usage.tryCountVocabReview(day, 2))
        assertFalse(usage.tryCountVocabReview(day, 2))
    }

    @Test fun azureMonthQuotaAndDailyCounter() = runBlocking {
        val usage = UsageRepo(db)
        val oct = LocalDate.of(2026, 10, 1)
        val day = LocalDate.of(2026, 10, 9)
        assertEquals(AzureCount.OK, usage.tryCountAzure(day, oct, 6, monthCap = 10, dayLimit = 5))
        assertEquals(AzureCount.MONTH, usage.tryCountAzure(day, oct, 6, monthCap = 10, dayLimit = 5))
        assertEquals(AzureCount.OK, usage.tryCountAzure(day.plusDays(1), oct, 4, monthCap = 10, dayLimit = 5)) // exactly at the cap
        assertEquals(10, usage.azureSecondsBetween(oct, oct.plusMonths(1)))
        assertEquals(1, usage.pronunciationsOn(day))
        // a new month starts fresh
        val nov = LocalDate.of(2026, 11, 1)
        assertEquals(AzureCount.OK, usage.tryCountAzure(nov, nov, 6, monthCap = 10, dayLimit = 1))
        assertEquals(AzureCount.DAY, usage.tryCountAzure(nov, nov, 1, monthCap = 10, dayLimit = 1))
        assertEquals(6, usage.azureSecondsBetween(nov, nov.plusMonths(1)))
        assertEquals(10, usage.azureSecondsBetween(oct, oct.plusMonths(1)))
        // pronunciations make a day active for the streak
        assertTrue(nov in usage.activeDaysSince(oct))
    }

    @Test fun azureQuotaIsAtomicUnderParallelCalls() = runBlocking {
        val usage = UsageRepo(db)
        val oct = LocalDate.of(2026, 10, 1)
        val results = coroutineScope {
            List(8) { async(Dispatchers.IO) { usage.tryCountAzure(oct.plusDays(it % 3L), oct, 6, 20, 100) } }.awaitAll()
        }
        assertEquals(3, results.count { it == AzureCount.OK })
        assertEquals(18, usage.azureSecondsBetween(oct, oct.plusMonths(1)))
    }
    @Test fun liveBudgetReserveAndSettle() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 9)
        assertEquals(0, usage.liveSecondsOn(day))
        assertFalse(usage.tryReserveLive(day, 2400, 1800))   // larger than the whole budget, even on a fresh day
        assertEquals(0, usage.liveSecondsOn(day))
        assertTrue(usage.tryReserveLive(day, 1200, 1800))
        assertFalse(usage.tryReserveLive(day, 900, 1800))
        assertTrue(usage.tryReserveLive(day, 600, 1800))    // exactly at the cap
        assertEquals(1800, usage.liveSecondsOn(day))
        assertFalse(usage.tryReserveLive(day.plusDays(1), 600, 0))
        usage.settleLive(day, 500)
        assertEquals(1300, usage.liveSecondsOn(day))
        usage.settleLive(day, 5000)                          // never below zero
        assertEquals(0, usage.liveSecondsOn(day))
        usage.settleLive(day.plusDays(5), 100)               // no row: nothing happens
        assertEquals(0, usage.liveSecondsOn(day.plusDays(5)))
        // live seconds make a day active for the streak
        usage.tryReserveLive(day.plusDays(2), 600, 1800)
        assertTrue(day.plusDays(2) in usage.activeDaysSince(day))
    }

    @Test fun liveReserveIsAtomicUnderParallelCalls() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 10)
        repeat(5) { round ->
            val d = day.plusDays(round.toLong())
            val results = coroutineScope { List(2) { async(Dispatchers.IO) { usage.tryReserveLive(d, 900, 1200) } }.awaitAll() }
            assertEquals(1, results.count { it }, "round $round")
            assertEquals(900, usage.liveSecondsOn(d))
        }
    }
}
