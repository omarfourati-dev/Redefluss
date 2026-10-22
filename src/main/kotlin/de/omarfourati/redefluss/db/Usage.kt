package de.omarfourati.redefluss.db

import java.time.LocalDate

enum class AzureCount { OK, MONTH, DAY }

/** Result of counting against an account's own limit and the cap over all accounts together. */
enum class LimitCount { OK, USER, TOTAL }

/** Advisory-lock keys: each serializes the checks of one shared budget, so parallel requests cannot overshoot it. */
private const val AZURE_LOCK = 4242
private const val TURN_LOCK = 4243
private const val LIVE_LOCK = 4244

/** Usage per account and day (primary key user_id, day). Caps over all accounts are checked under an advisory lock. */
class UsageRepo(private val db: Db) {
    /** Counts one turn if the account is below [limit] and all accounts together below [totalLimit] today. */
    suspend fun tryCountTurn(userId: Long, day: LocalDate, limit: Int, totalLimit: Int): LimitCount {
        if (limit <= 0) return LimitCount.USER
        if (totalLimit <= 0) return LimitCount.TOTAL
        return db.tx {
            sql("SELECT pg_advisory_xact_lock($TURN_LOCK)") { it.next() }
            val total = sql("SELECT COALESCE(SUM(turns), 0) FROM usage_day WHERE day = ?", day) { rs -> rs.next(); rs.getLong(1) }
            if (total >= totalLimit) return@tx LimitCount.TOTAL
            val counted = sql("""
                INSERT INTO usage_day (user_id, day, turns) VALUES (?, ?, 1)
                ON CONFLICT (user_id, day) DO UPDATE SET turns = usage_day.turns + 1 WHERE usage_day.turns < ?
                RETURNING turns
            """.trimIndent(), userId, day, limit) { it.next() }
            if (counted) LimitCount.OK else LimitCount.USER
        }
    }

    suspend fun turnsOn(userId: Long, day: LocalDate): Int =
        db.tx { sql("SELECT turns FROM usage_day WHERE user_id = ? AND day = ?", userId, day) { if (it.next()) it.getInt(1) else 0 } }

    suspend fun activeDaysSince(userId: Long, day: LocalDate): Set<LocalDate> = db.tx {
        sql("""
            SELECT day FROM usage_day
            WHERE user_id = ? AND day >= ? AND (turns > 0 OR live_seconds > 0 OR pronunciations > 0 OR vocab_reviews > 0)
        """.trimIndent(), userId, day) { rs -> buildSet { while (rs.next()) add(rs.getObject(1, LocalDate::class.java)) } }
    }

    /** One word generation per account and day – atomic, so two tabs opening /wortschatz at once generate only once. */
    suspend fun tryCountVocabGeneration(userId: Long, day: LocalDate): Boolean = db.tx {
        sql("""
            INSERT INTO usage_day (user_id, day, vocab_generated) VALUES (?, ?, 1)
            ON CONFLICT (user_id, day) DO UPDATE SET vocab_generated = usage_day.vocab_generated + 1 WHERE usage_day.vocab_generated < 1
            RETURNING vocab_generated
        """.trimIndent(), userId, day) { it.next() }
    }

    suspend fun tryCountVocabReview(userId: Long, day: LocalDate, limit: Int): Boolean {
        if (limit <= 0) return false
        return db.tx {
            sql("""
                INSERT INTO usage_day (user_id, day, vocab_reviews) VALUES (?, ?, 1)
                ON CONFLICT (user_id, day) DO UPDATE SET vocab_reviews = usage_day.vocab_reviews + 1 WHERE usage_day.vocab_reviews < ?
                RETURNING vocab_reviews
            """.trimIndent(), userId, day, limit) { it.next() }
        }
    }

    /** Gives the day's generation back after a failed generation, so the next request can try again. */
    suspend fun undoVocabGeneration(userId: Long, day: LocalDate) {
        db.tx { update("UPDATE usage_day SET vocab_generated = 0 WHERE user_id = ? AND day = ?", userId, day) }
    }

    suspend fun vocabReviewsOn(userId: Long, day: LocalDate): Int = db.tx {
        sql("SELECT vocab_reviews FROM usage_day WHERE user_id = ? AND day = ?", userId, day) { if (it.next()) it.getInt(1) else 0 }
    }

    /**
     * One transaction under an advisory lock, so parallel clips near the cap cannot both pass: the month sum over all
     * accounts plus this clip must stay within the cap (Azure F0 is one shared quota), the account's count today below
     * [dayLimit]; then count the seconds and the clip for the account.
     */
    suspend fun tryCountAzure(userId: Long, day: LocalDate, monthStart: LocalDate, seconds: Int, monthCap: Int, dayLimit: Int): AzureCount = db.tx {
        sql("SELECT pg_advisory_xact_lock($AZURE_LOCK)") { it.next() }
        val used = sql("SELECT COALESCE(SUM(azure_seconds), 0) FROM usage_day WHERE day >= ? AND day < ?",
            monthStart, monthStart.plusMonths(1)) { rs -> rs.next(); rs.getLong(1) }
        if (used + seconds > monthCap) return@tx AzureCount.MONTH
        val today = sql("SELECT pronunciations FROM usage_day WHERE user_id = ? AND day = ?", userId, day) { if (it.next()) it.getInt(1) else 0 }
        if (today >= dayLimit) return@tx AzureCount.DAY
        update("""
            INSERT INTO usage_day (user_id, day, pronunciations, azure_seconds) VALUES (?, ?, 1, ?)
            ON CONFLICT (user_id, day) DO UPDATE SET pronunciations = usage_day.pronunciations + 1,
              azure_seconds = usage_day.azure_seconds + EXCLUDED.azure_seconds
        """.trimIndent(), userId, day, seconds)
        AzureCount.OK
    }

    /** Azure seconds of all accounts together (the free quota is shared). */
    suspend fun azureSecondsBetween(from: LocalDate, toExclusive: LocalDate): Int = db.tx {
        sql("SELECT COALESCE(SUM(azure_seconds), 0) FROM usage_day WHERE day >= ? AND day < ?", from, toExclusive) { rs -> rs.next(); rs.getInt(1) }
    }

    /**
     * Reserves [seconds] of the day's live budget if they fit into the account's [limitSeconds] and the [totalSeconds]
     * of all accounts – under an advisory lock, so parallel starts cannot overshoot either budget.
     */
    suspend fun tryReserveLive(userId: Long, day: LocalDate, seconds: Int, limitSeconds: Int, totalSeconds: Int): LimitCount {
        if (seconds <= 0 || seconds > limitSeconds) return LimitCount.USER
        if (seconds > totalSeconds) return LimitCount.TOTAL
        return db.tx {
            sql("SELECT pg_advisory_xact_lock($LIVE_LOCK)") { it.next() }
            val total = sql("SELECT COALESCE(SUM(live_seconds), 0) FROM usage_day WHERE day = ?", day) { rs -> rs.next(); rs.getLong(1) }
            if (total + seconds > totalSeconds) return@tx LimitCount.TOTAL
            val reserved = sql("""
                INSERT INTO usage_day (user_id, day, live_seconds) VALUES (?, ?, ?)
                ON CONFLICT (user_id, day) DO UPDATE SET live_seconds = usage_day.live_seconds + EXCLUDED.live_seconds
                  WHERE usage_day.live_seconds + EXCLUDED.live_seconds <= ?
                RETURNING live_seconds
            """.trimIndent(), userId, day, seconds, limitSeconds) { it.next() }
            if (reserved) LimitCount.OK else LimitCount.USER
        }
    }

    /** Gives [refundSeconds] of a reservation back; never below zero. */
    suspend fun settleLive(userId: Long, day: LocalDate, refundSeconds: Int) {
        if (refundSeconds <= 0) return
        db.tx { update("UPDATE usage_day SET live_seconds = GREATEST(0, live_seconds - ?) WHERE user_id = ? AND day = ?", refundSeconds, userId, day) }
    }

    /** Adds [seconds] used beyond the reservation (may pass the daily cap by at most the 2 minutes of grace). */
    suspend fun chargeLive(userId: Long, day: LocalDate, seconds: Int) {
        if (seconds <= 0) return
        db.tx {
            update("""
                INSERT INTO usage_day (user_id, day, live_seconds) VALUES (?, ?, ?)
                ON CONFLICT (user_id, day) DO UPDATE SET live_seconds = usage_day.live_seconds + EXCLUDED.live_seconds
            """.trimIndent(), userId, day, seconds)
        }
    }

    suspend fun liveSecondsOn(userId: Long, day: LocalDate): Int = db.tx {
        sql("SELECT live_seconds FROM usage_day WHERE user_id = ? AND day = ?", userId, day) { if (it.next()) it.getInt(1) else 0 }
    }

    /** Live seconds of all accounts together today (for the shared cap in the overview). */
    suspend fun totalLiveSecondsOn(day: LocalDate): Int =
        db.tx { sql("SELECT COALESCE(SUM(live_seconds), 0) FROM usage_day WHERE day = ?", day) { rs -> rs.next(); rs.getInt(1) } }

    suspend fun pronunciationsOn(userId: Long, day: LocalDate): Int = db.tx {
        sql("SELECT pronunciations FROM usage_day WHERE user_id = ? AND day = ?", userId, day) { if (it.next()) it.getInt(1) else 0 }
    }
}
