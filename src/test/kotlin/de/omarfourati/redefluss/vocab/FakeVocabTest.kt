package de.omarfourati.redefluss.vocab

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class FakeVocabTest {
    @Test fun generatorSkipsAvoidedWords() = runBlocking {
        val words = FakeVocabGenerator().generate(3, avoid = listOf("Kündigungsfrist"), themes = THEMES)
        assertEquals(3, words.size)
        assertTrue(words.none { VocabRepo.key(it.word) == VocabRepo.key("Kündigungsfrist") })
    }

    @Test fun checkerGradesByWordPresence() = runBlocking {
        val ok = FakeVocabChecker().check("Frist", "Zeitraum", "Die Frist endet morgen.")
        assertEquals(4, ok.grade); assertTrue(ok.usedCorrectly)
        val bad = FakeVocabChecker().check("Frist", "Zeitraum", "Das Wetter ist schön.")
        assertEquals(2, bad.grade); assertFalse(bad.usedCorrectly); assertTrue("Frist" in bad.feedback)
    }
}
