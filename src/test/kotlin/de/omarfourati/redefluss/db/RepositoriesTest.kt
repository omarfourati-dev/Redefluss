package de.omarfourati.redefluss.db

import de.omarfourati.redefluss.TestDb
import de.omarfourati.redefluss.vocab.NewCard
import de.omarfourati.redefluss.vocab.VocabRepo
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
    private val users = UserRepo(db)
    /** The owner (id 1) and a guest (id 2) – every data row needs an account. */
    private val owner = runBlocking { users.create("omar@example.de", "hash", t0, Role.OWNER) }.id
    private val guest = runBlocking { users.create("gast@example.de", "hash", t0, Role.GUEST) }.id
    private val big = 100_000

    @Test fun users() = runBlocking {
        assertEquals(2L, users.count())
        val u = users.findByEmail("omar@example.de")!!
        assertEquals(Role.OWNER, u.role)
        assertEquals(0, u.tokenVersion)
        assertEquals(u, users.findById(u.id))
        assertEquals(Role.GUEST, users.findByEmail("GAST@example.de ")!!.role)
        assertNull(users.findByEmail("nobody@example.de"))
        val changed = users.setPassword(u.id, "hash2")
        assertEquals("hash2", changed.passwordHash)
        assertEquals(1, changed.tokenVersion)
        assertEquals(Role.OWNER, changed.role)
        assertEquals(listOf("omar@example.de", "gast@example.de"), users.list().map { it.email })
    }

    @Test fun deletingAGuestRemovesAllItsDataButNeverTheOwner() = runBlocking {
        MistakeRepo(db).record(guest, NewMistake("artikel", "der Frau", "die Frau", "r", "e"), t0)
        SessionRepo(db).create(guest, "conversation", "x", t0)
        VocabRepo(db).insertIfNew(guest, NewCard("Termin", "der", "", "m", "e", "alltag", "daily"), LocalDate.of(2026, 10, 8))
        UsageRepo(db).tryCountTurn(guest, LocalDate.of(2026, 10, 8), 5, big)
        MistakeRepo(db).record(owner, NewMistake("artikel", "der Frau", "die Frau", "r", "e"), t0)

        assertFalse(users.deleteGuest(owner))
        assertTrue(users.deleteGuest(guest))
        assertFalse(users.deleteGuest(guest))
        assertNull(users.findById(guest))
        val left = db.tx { sql("SELECT (SELECT count(*) FROM mistake) + (SELECT count(*) FROM practice_session) + " +
            "(SELECT count(*) FROM vocab_card) + (SELECT count(*) FROM usage_day)") { rs -> rs.next(); rs.getInt(1) } }
        assertEquals(1, left) // only the owner's mistake
    }

    @Test fun mistakesAreGroupedCountedAndRanked() = runBlocking {
        val repo = MistakeRepo(db)
        val zeit = NewMistake("artikel", "den ganzen Zeit", "die ganze Zeit", "Zeit ist feminin.", "Ich habe den ganzen Zeit gewartet.")
        val id1 = repo.record(owner, zeit, t0)
        val id2 = repo.record(owner, zeit.copy(wrong = "Den ganzen Zeit.", example = "Neuer Satz."), t0.plusSeconds(60))
        assertEquals(id1, id2)
        repo.record(owner, NewMistake("konjugation", "du muss", "du musst", "du → -st", "Du muss gehen."), t0)
        val top = repo.top(owner, 5)
        assertEquals(listOf(2, 1), top.map { it.count })
        assertEquals("Neuer Satz.", top.first().example)
        assertEquals("die ganze Zeit", top.first().right)

        assertTrue(repo.setResolved(owner, id1, true))
        assertEquals(listOf("du muss"), repo.top(owner, 5).map { it.wrong })
        assertEquals(1, repo.list(owner, MistakeStatus.RESOLVED).size)
        assertEquals(2, repo.list(owner, MistakeStatus.ALL).size)
        // the same mistake again re-opens it
        repo.record(owner, zeit, t0.plusSeconds(120))
        assertEquals(3, repo.list(owner, MistakeStatus.OPEN).first().count)
        assertFalse(repo.setResolved(owner, 999_999, true))
    }

    @Test fun mistakesAreSeparatedByAccount() = runBlocking {
        val repo = MistakeRepo(db)
        val zeit = NewMistake("artikel", "den ganzen Zeit", "die ganze Zeit", "r", "e")
        val ownerId = repo.record(owner, zeit, t0)
        val guestId = repo.record(guest, zeit, t0) // same mistake, own row and own count
        assertNotEquals(ownerId, guestId)
        assertEquals(listOf(1), repo.top(guest, 5).map { it.count })
        repo.record(owner, NewMistake("konjugation", "du muss", "du musst", "r", "e"), t0)
        assertEquals(2, repo.list(owner, MistakeStatus.ALL).size)
        assertEquals(1, repo.list(guest, MistakeStatus.ALL).size)
        assertTrue(repo.topExcept(guest, "artikel", 5).isEmpty())
        // a guest cannot resolve the owner's mistake
        assertFalse(repo.setResolved(guest, ownerId, true))
        assertFalse(repo.list(owner, MistakeStatus.ALL).single { it.id == ownerId }.resolved)
    }

    @Test fun sessionsAndMinutes() = runBlocking {
        val repo = SessionRepo(db)
        val id = repo.create(owner, "conversation", "Arbeit", t0)
        assertTrue(repo.exists(owner, id))
        assertFalse(repo.exists(owner, UUID.randomUUID()))
        assertTrue(repo.touch(owner, id, t0.plusSeconds(150), mistakes = 2))
        assertFalse(repo.touch(owner, UUID.randomUUID(), t0, 0))
        // 150 s → 3 minutes; a second session with one turn counts at least 1 minute
        val other = repo.create(owner, "conversation", "Alltag", t0.plusSeconds(3600))
        repo.touch(owner, other, t0.plusSeconds(3600), 0)
        assertEquals(4, repo.minutesBetween(owner, t0.minusSeconds(1), t0.plusSeconds(7200)))
        assertEquals(0, repo.minutesBetween(owner, t0.plusSeconds(9000), t0.plusSeconds(9999)))
    }

    @Test fun sessionsOfAnotherAccountBehaveLikeUnknownOnes() = runBlocking {
        val repo = SessionRepo(db)
        val id = repo.create(owner, "interview", "Geheim", t0)
        assertFalse(repo.exists(guest, id))
        assertFalse(repo.touch(guest, id, t0, 1))
        assertNull(repo.mode(guest, id))
        assertNull(repo.summary(guest, id))
        assertEquals("", repo.topic(guest, id))
        assertFalse(repo.markCancelled(guest, id))
        assertFalse(repo.finishInterview(guest, id, "x", 1, 0, t0))
        assertEquals("", repo.summary(owner, id))
        assertEquals("Geheim", repo.topic(owner, id))
        assertEquals(0, repo.minutesBetween(guest, t0.minusSeconds(1), t0.plusSeconds(7200)))
    }

    @Test fun usageLimitIsAtomic() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 8)
        assertEquals(LimitCount.OK, usage.tryCountTurn(owner, day, 2, big))
        assertEquals(LimitCount.OK, usage.tryCountTurn(owner, day, 2, big))
        assertEquals(LimitCount.USER, usage.tryCountTurn(owner, day, 2, big))
        assertEquals(2, usage.turnsOn(owner, day))
        assertEquals(LimitCount.USER, usage.tryCountTurn(owner, day.plusDays(1), 0, big))
        assertEquals(setOf(day), usage.activeDaysSince(owner, day.minusDays(30)))
        // the guest has its own counter and its own streak
        assertEquals(0, usage.turnsOn(guest, day))
        assertEquals(emptySet(), usage.activeDaysSince(guest, day.minusDays(30)))
        assertEquals(LimitCount.OK, usage.tryCountTurn(guest, day, 2, big))
    }

    @Test fun totalTurnCapCoversAllAccounts() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 8)
        assertEquals(LimitCount.OK, usage.tryCountTurn(owner, day, 10, 3))
        assertEquals(LimitCount.OK, usage.tryCountTurn(owner, day, 10, 3))
        assertEquals(LimitCount.OK, usage.tryCountTurn(guest, day, 10, 3))
        assertEquals(LimitCount.TOTAL, usage.tryCountTurn(guest, day, 10, 3))
        assertEquals(LimitCount.TOTAL, usage.tryCountTurn(owner, day, 10, 3))
        assertEquals(1, usage.turnsOn(guest, day))
        // the next day starts fresh
        assertEquals(LimitCount.OK, usage.tryCountTurn(guest, day.plusDays(1), 10, 3))
    }

    @Test fun totalTurnCapHoldsUnderParallelCalls() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 9)
        val results = coroutineScope {
            List(10) { i -> async(Dispatchers.IO) { usage.tryCountTurn(if (i % 2 == 0) owner else guest, day, 100, 4) } }.awaitAll()
        }
        assertEquals(4, results.count { it == LimitCount.OK })
        assertEquals(4, usage.turnsOn(owner, day) + usage.turnsOn(guest, day))
    }

    @Test fun vocabCounters() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 9)
        assertTrue(usage.tryCountVocabGeneration(owner, day))
        assertFalse(usage.tryCountVocabGeneration(owner, day))
        assertTrue(usage.tryCountVocabGeneration(guest, day)) // every account gets its own words
        assertTrue(usage.tryCountVocabReview(owner, day, 2)); assertTrue(usage.tryCountVocabReview(owner, day, 2))
        assertFalse(usage.tryCountVocabReview(owner, day, 2))
        assertTrue(usage.tryCountVocabReview(guest, day, 2))
        usage.undoVocabGeneration(owner, day)
        assertTrue(usage.tryCountVocabGeneration(owner, day))
        assertEquals(2, usage.vocabReviewsOn(owner, day))
        assertEquals(1, usage.vocabReviewsOn(guest, day))
    }

    @Test fun azureMonthQuotaIsSharedAndTheDailyCounterIsPerAccount() = runBlocking {
        val usage = UsageRepo(db)
        val oct = LocalDate.of(2026, 10, 1)
        val day = LocalDate.of(2026, 10, 9)
        assertEquals(AzureCount.OK, usage.tryCountAzure(owner, day, oct, 6, monthCap = 10, dayLimit = 5))
        assertEquals(AzureCount.MONTH, usage.tryCountAzure(guest, day, oct, 6, monthCap = 10, dayLimit = 5)) // shared month quota
        assertEquals(AzureCount.OK, usage.tryCountAzure(guest, day.plusDays(1), oct, 4, monthCap = 10, dayLimit = 5)) // exactly at the cap
        assertEquals(10, usage.azureSecondsBetween(oct, oct.plusMonths(1)))
        assertEquals(1, usage.pronunciationsOn(owner, day))
        assertEquals(0, usage.pronunciationsOn(guest, day))
        // a new month starts fresh; the daily limit is the account's own
        val nov = LocalDate.of(2026, 11, 1)
        assertEquals(AzureCount.OK, usage.tryCountAzure(owner, nov, nov, 6, monthCap = 20, dayLimit = 1))
        assertEquals(AzureCount.DAY, usage.tryCountAzure(owner, nov, nov, 1, monthCap = 20, dayLimit = 1))
        assertEquals(AzureCount.OK, usage.tryCountAzure(guest, nov, nov, 1, monthCap = 20, dayLimit = 1))
        assertEquals(7, usage.azureSecondsBetween(nov, nov.plusMonths(1)))
        // pronunciations make a day active for the streak
        assertTrue(nov in usage.activeDaysSince(owner, oct))
    }

    @Test fun azureQuotaIsAtomicUnderParallelCalls() = runBlocking {
        val usage = UsageRepo(db)
        val oct = LocalDate.of(2026, 10, 1)
        val results = coroutineScope {
            List(8) { async(Dispatchers.IO) { usage.tryCountAzure(if (it % 2 == 0) owner else guest, oct.plusDays(it % 3L), oct, 6, 20, 100) } }.awaitAll()
        }
        assertEquals(3, results.count { it == AzureCount.OK })
        assertEquals(18, usage.azureSecondsBetween(oct, oct.plusMonths(1)))
    }

    @Test fun liveBudgetReserveAndSettle() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 9)
        assertEquals(0, usage.liveSecondsOn(owner, day))
        assertEquals(LimitCount.USER, usage.tryReserveLive(owner, day, 2400, 1800, big)) // larger than the whole budget
        assertEquals(0, usage.liveSecondsOn(owner, day))
        assertEquals(LimitCount.OK, usage.tryReserveLive(owner, day, 1200, 1800, big))
        assertEquals(LimitCount.USER, usage.tryReserveLive(owner, day, 900, 1800, big))
        assertEquals(LimitCount.OK, usage.tryReserveLive(owner, day, 600, 1800, big))    // exactly at the cap
        assertEquals(1800, usage.liveSecondsOn(owner, day))
        assertEquals(LimitCount.USER, usage.tryReserveLive(owner, day.plusDays(1), 600, 0, big))
        usage.settleLive(owner, day, 500)
        assertEquals(1300, usage.liveSecondsOn(owner, day))
        usage.settleLive(owner, day, 5000)                          // never below zero
        assertEquals(0, usage.liveSecondsOn(owner, day))
        usage.settleLive(owner, day.plusDays(5), 100)               // no row: nothing happens
        assertEquals(0, usage.liveSecondsOn(owner, day.plusDays(5)))
        usage.chargeLive(owner, day, 90)
        assertEquals(90, usage.liveSecondsOn(owner, day))
        // live seconds make a day active for the streak
        usage.tryReserveLive(owner, day.plusDays(2), 600, 1800, big)
        assertTrue(day.plusDays(2) in usage.activeDaysSince(owner, day))
    }

    @Test fun totalLiveCapCoversAllAccounts() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 9)
        assertEquals(LimitCount.OK, usage.tryReserveLive(owner, day, 1200, 1800, 2000))
        assertEquals(LimitCount.TOTAL, usage.tryReserveLive(guest, day, 900, 900, 2000))
        assertEquals(LimitCount.OK, usage.tryReserveLive(guest, day, 600, 900, 2000))
        assertEquals(1800, usage.totalLiveSecondsOn(day))
        assertEquals(LimitCount.TOTAL, usage.tryReserveLive(owner, day, 600, 1800, 1500))
        assertEquals(600, usage.liveSecondsOn(guest, day))
    }

    @Test fun liveReserveIsAtomicUnderParallelCalls() = runBlocking {
        val usage = UsageRepo(db)
        val day = LocalDate.of(2026, 10, 10)
        repeat(5) { round ->
            val d = day.plusDays(round.toLong())
            val results = coroutineScope { List(2) { async(Dispatchers.IO) { usage.tryReserveLive(owner, d, 900, 1200, big) } }.awaitAll() }
            assertEquals(1, results.count { it == LimitCount.OK }, "round $round")
            assertEquals(900, usage.liveSecondsOn(owner, d))
        }
    }
}
