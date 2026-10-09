package de.omarfourati.redefluss.pronunciation

/** Deterministic scorer for PRONUNCIATION=fake and tests: words containing ü, ö or ch are weak. */
class FakeScorer : PronunciationScorer {
    override suspend fun assess(wav: ByteArray, reference: String): Assessment {
        val tokens = reference.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) throw NothingRecognizedException()
        val words = tokens.map { token ->
            val lower = token.lowercase()
            val weak = listOf("ü", "ö", "ch").firstOrNull { it in lower }
            if (weak != null) WordScore(token, 55.0, "Mispronunciation", listOf(PhonemeScore(weak, 40.0)))
            else WordScore(token, 90.0, "None", listOf(PhonemeScore(lower.take(1), 90.0)))
        }
        val avg = round1(words.map { it.score }.average())
        return Assessment(reference.trim(), avg, 85.0, 100.0, avg, words)
    }
}
