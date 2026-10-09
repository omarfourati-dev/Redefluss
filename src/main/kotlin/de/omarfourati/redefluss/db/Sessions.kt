package de.omarfourati.redefluss.db

import java.time.Instant
import java.util.UUID

class SessionRepo(private val db: Db) {
    suspend fun create(mode: String, topic: String, now: Instant): UUID = db.tx {
        val id = UUID.randomUUID()
        update("INSERT INTO practice_session (id, mode, topic, started_at, last_at) VALUES (?, ?, ?, ?, ?)", id, mode, topic, now, now)
        id
    }

    suspend fun exists(id: UUID): Boolean = db.tx { sql("SELECT 1 FROM practice_session WHERE id = ?", id) { it.next() } }

    suspend fun touch(id: UUID, now: Instant, mistakes: Int): Boolean = db.tx {
        update("UPDATE practice_session SET turns = turns + 1, mistakes = mistakes + ?, last_at = ? WHERE id = ?", mistakes, now, id) == 1
    }

    /** Practice minutes of sessions started in [from, to): each session with at least one turn counts ≥ 1 minute. */
    suspend fun minutesBetween(from: Instant, to: Instant): Int = db.tx {
        sql("""
            SELECT COALESCE(SUM(GREATEST(1, CEIL(EXTRACT(EPOCH FROM (last_at - started_at)) / 60.0))), 0)
            FROM practice_session WHERE turns > 0 AND started_at >= ? AND started_at < ?
        """.trimIndent(), from, to) { rs -> rs.next(); rs.getInt(1) }
    }
}
