package de.omarfourati.redefluss.pronunciation

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class FakeScorerTest {
    @Test fun scoresPerWord() = runBlocking {
        val a = FakeScorer().assess(ByteArray(0), "Guten Morgen, schöne Bücher")
        assertEquals(listOf("Guten", "Morgen,", "schöne", "Bücher"), a.words.map { it.word })
        assertEquals(listOf(90.0, 90.0, 55.0, 55.0), a.words.map { it.score })
        assertEquals(listOf(PhonemeScore("ö", 40.0)), a.words[2].phonemes.filter { it.score < 60 })
        assertTrue(a.words[0].phonemes.all { it.score >= 60 })
        assertEquals("Guten Morgen, schöne Bücher", a.recognized)
    }

    @Test fun chWordsAreWeak() = runBlocking {
        val w = FakeScorer().assess(ByteArray(0), "ich").words.single()
        assertEquals(55.0, w.score)
        assertEquals("ch", w.phonemes.single { it.score == 40.0 }.phoneme)
    }

    @Test fun emptyReferenceFails() {
        assertFailsWith<NothingRecognizedException> { runBlocking { FakeScorer().assess(ByteArray(0), "   ") } }
    }
}
