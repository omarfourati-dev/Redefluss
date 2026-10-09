package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.TestDb
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.*

class VocabRepoTest {
    private val repo = VocabRepo(TestDb.reset())
    private val today = LocalDate.of(2026, 10, 9)
    private fun card(word: String, article: String = "die") =
        NewCard(word, article, "-en", "Bedeutung von $word", "Beispiel mit $word.", "it", "daily")

    @Test fun keyIgnoresCaseSpacesAndArticles() {
        assertEquals("kündigungsfrist", VocabRepo.key("  Die Kündigungsfrist "))
        assertEquals("ansprechpartner", VocabRepo.key("der Ansprechpartner"))
        assertEquals("etwas in angriff nehmen", VocabRepo.key("etwas in Angriff nehmen"))
    }

    @Test fun insertIfNewSkipsDuplicates() = runBlocking {
        assertNotNull(repo.insertIfNew(card("Kündigungsfrist"), today))
        assertNull(repo.insertIfNew(card("die kündigungsfrist"), today))
        assertEquals(1, repo.createdOn(today).size)
        assertEquals(listOf("Kündigungsfrist"), repo.recentWords())
    }

    @Test fun dueAndSchedule() = runBlocking {
        val a = repo.insertIfNew(card("Ansprechpartner", "der"), today.minusDays(3))!!
        val b = repo.insertIfNew(card("Frist"), today)!!
        repo.insertIfNew(card("Zukunft"), today.plusDays(1))
        assertEquals(listOf(a.id, b.id), repo.due(today).map { it.id })
        assertEquals(2, repo.dueCount(today))
        assertTrue(repo.schedule(a.id, Schedule(2.6, 1, 1), 5, today.plusDays(1)))
        assertEquals(listOf(b.id), repo.due(today).map { it.id })
        val updated = repo.find(a.id)!!
        assertEquals(2.6, updated.ease, 1e-9); assertEquals(1, updated.intervalDays); assertEquals(5, updated.lastGrade)
        assertFalse(repo.schedule(999_999, Schedule(2.5, 1, 1), 3, today))
    }
}
