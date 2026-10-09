package de.omarfourati.redefluss.db

import java.time.LocalDate

class UsageRepo(private val db: Db) {
    /** Counts one turn if today's count is below the limit – atomically, so parallel requests cannot overshoot. */
    suspend fun tryCountTurn(day: LocalDate, limit: Int): Boolean {
        if (limit <= 0) return false
        return db.tx {
            sql("""
                INSERT INTO usage_day (day, turns) VALUES (?, 1)
                ON CONFLICT (day) DO UPDATE SET turns = usage_day.turns + 1 WHERE usage_day.turns < ?
                RETURNING turns
            """.trimIndent(), day, limit) { it.next() }
        }
    }

    suspend fun turnsOn(day: LocalDate): Int =
        db.tx { sql("SELECT turns FROM usage_day WHERE day = ?", day) { if (it.next()) it.getInt(1) else 0 } }

    suspend fun activeDaysSince(day: LocalDate): Set<LocalDate> = db.tx {
        sql("SELECT day FROM usage_day WHERE day >= ? AND (turns > 0 OR live_seconds > 0 OR pronunciations > 0 OR vocab_reviews > 0)", day) { rs ->
            buildSet { while (rs.next()) add(rs.getObject(1, LocalDate::class.java)) }
        }
    }

    /** One word generation per day – atomic, so two tabs opening /wortschatz at once generate only once. */
    suspend fun tryCountVocabGeneration(day: LocalDate): Boolean = db.tx {
        sql("""
            INSERT INTO usage_day (day, vocab_generated) VALUES (?, 1)
            ON CONFLICT (day) DO UPDATE SET vocab_generated = usage_day.vocab_generated + 1 WHERE usage_day.vocab_generated < 1
            RETURNING vocab_generated
        """.trimIndent(), day) { it.next() }
    }

    suspend fun tryCountVocabReview(day: LocalDate, limit: Int): Boolean {
        if (limit <= 0) return false
        return db.tx {
            sql("""
                INSERT INTO usage_day (day, vocab_reviews) VALUES (?, 1)
                ON CONFLICT (day) DO UPDATE SET vocab_reviews = usage_day.vocab_reviews + 1 WHERE usage_day.vocab_reviews < ?
                RETURNING vocab_reviews
            """.trimIndent(), day, limit) { it.next() }
        }
    }
}
