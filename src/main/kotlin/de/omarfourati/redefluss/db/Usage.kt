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
        sql("SELECT day FROM usage_day WHERE day >= ? AND (turns > 0 OR live_seconds > 0 OR pronunciations > 0) ", day) { rs ->
            buildSet { while (rs.next()) add(rs.getObject(1, LocalDate::class.java)) }
        }
    }
}
