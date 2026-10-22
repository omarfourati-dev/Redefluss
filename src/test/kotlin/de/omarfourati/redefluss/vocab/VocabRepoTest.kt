package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.TestDb
import de.omarfourati.redefluss.db.Role
import de.omarfourati.redefluss.db.UserRepo
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class VocabRepoTest {
    private val db = TestDb.reset()
    private val repo = VocabRepo(db)
    private val today = LocalDate.of(2026, 10, 9)
    private val owner = runBlocking { UserRepo(db).create("omar@example.de", "hash", Instant.EPOCH, Role.OWNER) }.id
    private val guest = runBlocking { UserRepo(db).create("gast@example.de", "hash", Instant.EPOCH, Role.GUEST) }.id
    private fun card(word: String, article: String = "die") =
        NewCard(word, article, "-en", "Bedeutung von $word", "Beispiel mit $word.", "it", "daily")

    @Test fun keyIgnoresCaseSpacesAndArticles() {
        assertEquals("kündigungsfrist", VocabRepo.key("  Die Kündigungsfrist "))
        assertEquals("ansprechpartner", VocabRepo.key("der Ansprechpartner"))
        assertEquals("etwas in angriff nehmen", VocabRepo.key("etwas in Angriff nehmen"))
    }

    @Test fun keyKeepsIndefiniteArticleInIdioms() {
        assertEquals("ein auge zudrücken", VocabRepo.key("ein Auge zudrücken"))
    }

    @Test fun insertIfNewRejectsBlankOrArticleOnly() = runBlocking {
        assertNull(repo.insertIfNew(owner, card("die"), today))
        assertNull(repo.insertIfNew(owner, card("  "), today))
        assertEquals(0, repo.createdOn(owner, today).size)
    }

    @Test fun insertIfNewSkipsDuplicates() = runBlocking {
        assertNotNull(repo.insertIfNew(owner, card("Kündigungsfrist"), today))
        assertNull(repo.insertIfNew(owner, card("die kündigungsfrist"), today))
        assertEquals(1, repo.createdOn(owner, today).size)
        assertEquals(listOf("Kündigungsfrist"), repo.recentWords(owner))
    }

    @Test fun wordsBelongToOneAccount() = runBlocking {
        val mine = repo.insertIfNew(owner, card("Kündigungsfrist"), today)!!
        // the same word is new for the guest
        val theirs = assertNotNull(repo.insertIfNew(guest, card("Kündigungsfrist"), today))
        assertNotEquals(mine.id, theirs.id)
        assertEquals(1, repo.createdOn(guest, today).size)
        assertEquals(1, repo.dueCount(guest, today))
        assertNull(repo.find(guest, mine.id))
        assertFalse(repo.schedule(guest, mine.id, Schedule(2.6, 1, 1), 5, today.plusDays(1)))
        assertEquals(today, repo.find(owner, mine.id)!!.dueOn)
        assertEquals(listOf(theirs.id), repo.forPronunciation(guest, today, 5).map { it.id })
    }

    @Test fun dueAndSchedule() = runBlocking {
        val a = repo.insertIfNew(owner, card("Ansprechpartner", "der"), today.minusDays(3))!!
        val b = repo.insertIfNew(owner, card("Frist"), today)!!
        repo.insertIfNew(owner, card("Zukunft"), today.plusDays(1))
        assertEquals(listOf(a.id, b.id), repo.due(owner, today).map { it.id })
        assertEquals(2, repo.dueCount(owner, today))
        assertTrue(repo.schedule(owner, a.id, Schedule(2.6, 1, 1), 5, today.plusDays(1)))
        assertEquals(listOf(b.id), repo.due(owner, today).map { it.id })
        val updated = repo.find(owner, a.id)!!
        assertEquals(2.6, updated.ease, 1e-9); assertEquals(1, updated.intervalDays); assertEquals(5, updated.lastGrade)
        assertFalse(repo.schedule(owner, 999_999, Schedule(2.5, 1, 1), 3, today))
    }
}
