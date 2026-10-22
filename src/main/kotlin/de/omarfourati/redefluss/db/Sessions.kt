package de.omarfourati.redefluss.db

import java.time.Instant
import java.util.UUID

/** Every query is scoped to one account (userId): a session id of another account behaves like an unknown one. */
class SessionRepo(private val db: Db) {
    suspend fun create(userId: Long, mode: String, topic: String, now: Instant): UUID = db.tx {
        val id = UUID.randomUUID()
        update("INSERT INTO practice_session (id, user_id, mode, topic, started_at, last_at) VALUES (?, ?, ?, ?, ?, ?)",
            id, userId, mode, topic, now, now)
        id
    }

    suspend fun exists(userId: Long, id: UUID): Boolean =
        db.tx { sql("SELECT 1 FROM practice_session WHERE id = ? AND user_id = ?", id, userId) { it.next() } }

    suspend fun touch(userId: Long, id: UUID, now: Instant, mistakes: Int): Boolean = db.tx {
        update("UPDATE practice_session SET turns = turns + 1, mistakes = mistakes + ?, last_at = ? WHERE id = ? AND user_id = ?",
            mistakes, now, id, userId) == 1
    }

    suspend fun mode(userId: Long, id: UUID): String? = db.tx {
        sql("SELECT mode FROM practice_session WHERE id = ? AND user_id = ?", id, userId) { if (it.next()) it.getString(1) else null }
    }

    /** Marks a session as cancelled unless it already has a summary (a report or an earlier cancel). */
    suspend fun markCancelled(userId: Long, id: UUID): Boolean = db.tx {
        update("UPDATE practice_session SET summary = 'abgebrochen' WHERE id = ? AND user_id = ? AND summary = ''", id, userId) == 1
    }

    /** null when the session does not exist; '' while it is neither reported nor cancelled. */
    suspend fun summary(userId: Long, id: UUID): String? = db.tx {
        sql("SELECT summary FROM practice_session WHERE id = ? AND user_id = ?", id, userId) { if (it.next()) it.getString(1) else null }
    }

    /** Stores a live interview's result: one-sentence verdict, the learner's turns, recorded mistakes and the end time. */
    suspend fun finishInterview(userId: Long, id: UUID, summary: String, turns: Int, mistakes: Int, now: Instant): Boolean = db.tx {
        update("UPDATE practice_session SET summary = ?, turns = ?, mistakes = ?, last_at = ? WHERE id = ? AND user_id = ?",
            summary, turns, mistakes, now, id, userId) == 1
    }

    suspend fun topic(userId: Long, id: UUID): String = db.tx {
        sql("SELECT topic FROM practice_session WHERE id = ? AND user_id = ?", id, userId) { if (it.next()) it.getString(1) else "" }
    }

    /** Practice minutes of sessions started in [from, to): each session with at least one turn counts ≥ 1 minute. */
    suspend fun minutesBetween(userId: Long, from: Instant, to: Instant): Int = db.tx {
        sql("""
            SELECT COALESCE(SUM(GREATEST(1, CEIL(EXTRACT(EPOCH FROM (last_at - started_at)) / 60.0))), 0)
            FROM practice_session WHERE user_id = ? AND turns > 0 AND started_at >= ? AND started_at < ?
        """.trimIndent(), userId, from, to) { rs -> rs.next(); rs.getInt(1) }
    }
}
