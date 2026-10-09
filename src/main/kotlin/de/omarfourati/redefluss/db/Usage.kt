package de.omarfourati.redefluss.db

import java.time.LocalDate

enum class AzureCount { OK, MONTH, DAY }

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

    /** Gives the day's generation back after a failed generation, so the next request can try again. */
    suspend fun undoVocabGeneration(day: LocalDate) {
        db.tx { update("UPDATE usage_day SET vocab_generated = 0 WHERE day = ?", day) }
    }

    suspend fun vocabReviewsOn(day: LocalDate): Int =
        db.tx { sql("SELECT vocab_reviews FROM usage_day WHERE day = ?", day) { if (it.next()) it.getInt(1) else 0 } }

    /**
     * One transaction under an advisory lock, so parallel clips near the cap cannot both pass:
     * month sum + this clip must stay within the cap, today's count below the limit; then count the seconds and the clip.
     */
    suspend fun tryCountAzure(day: LocalDate, monthStart: LocalDate, seconds: Int, monthCap: Int, dayLimit: Int): AzureCount = db.tx {
        sql("SELECT pg_advisory_xact_lock(4242)") { it.next() }
        val used = sql("SELECT COALESCE(SUM(azure_seconds), 0) FROM usage_day WHERE day >= ? AND day < ?",
            monthStart, monthStart.plusMonths(1)) { rs -> rs.next(); rs.getLong(1) }
        if (used + seconds > monthCap) return@tx AzureCount.MONTH
        val today = sql("SELECT pronunciations FROM usage_day WHERE day = ?", day) { if (it.next()) it.getInt(1) else 0 }
        if (today >= dayLimit) return@tx AzureCount.DAY
        update("""
            INSERT INTO usage_day (day, pronunciations, azure_seconds) VALUES (?, 1, ?)
            ON CONFLICT (day) DO UPDATE SET pronunciations = usage_day.pronunciations + 1,
              azure_seconds = usage_day.azure_seconds + EXCLUDED.azure_seconds
        """.trimIndent(), day, seconds)
        AzureCount.OK
    }

    suspend fun azureSecondsBetween(from: LocalDate, toExclusive: LocalDate): Int = db.tx {
        sql("SELECT COALESCE(SUM(azure_seconds), 0) FROM usage_day WHERE day >= ? AND day < ?", from, toExclusive) { rs -> rs.next(); rs.getInt(1) }
    }

    suspend fun pronunciationsOn(day: LocalDate): Int =
        db.tx { sql("SELECT pronunciations FROM usage_day WHERE day = ?", day) { if (it.next()) it.getInt(1) else 0 } }
}
