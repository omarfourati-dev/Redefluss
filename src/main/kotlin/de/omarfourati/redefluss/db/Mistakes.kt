package de.omarfourati.redefluss.db

import de.omarfourati.redefluss.mistakes.MistakeKey
import java.sql.ResultSet
import java.time.Instant

data class NewMistake(val category: String, val wrong: String, val right: String, val rule: String, val example: String)
data class Mistake(
    val id: Long, val category: String, val wrong: String, val right: String, val rule: String, val example: String,
    val count: Int, val lastSeen: Instant, val resolved: Boolean,
)
enum class MistakeStatus { OPEN, RESOLVED, ALL }

private const val COLUMNS = "id, category, wrong, correct, rule, example, count, last_seen, resolved"
private fun ResultSet.toMistake() = Mistake(getLong("id"), getString("category"), getString("wrong"), getString("correct"),
    getString("rule"), getString("example"), getInt("count"), instant("last_seen"), getBoolean("resolved"))
private fun ResultSet.all(): List<Mistake> = buildList { while (next()) add(toMistake()) }

/** Every query is scoped to one account (userId): nobody ever sees another account's mistakes. */
class MistakeRepo(private val db: Db) {
    /** Same normalized mistake → count + 1, newest wording/rule/example, re-opened. */
    suspend fun record(userId: Long, m: NewMistake, now: Instant): Long = db.tx {
        val category = MistakeKey.category(m.category)
        sql("""
            INSERT INTO mistake (user_id, category, wrong, correct, rule, example, normalized, first_seen, last_seen)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, normalized) DO UPDATE SET count = mistake.count + 1, wrong = EXCLUDED.wrong,
              correct = EXCLUDED.correct, rule = EXCLUDED.rule, example = EXCLUDED.example,
              last_seen = EXCLUDED.last_seen, resolved = false
            RETURNING id
        """.trimIndent(), userId, category, m.wrong.trim(), m.right.trim(), m.rule.trim(), m.example.take(300),
            MistakeKey.normalize(category, m.wrong, m.right), now, now) { rs -> rs.next(); rs.getLong(1) }
    }

    suspend fun top(userId: Long, limit: Int): List<Mistake> = db.tx {
        sql("SELECT $COLUMNS FROM mistake WHERE user_id = ? AND NOT resolved ORDER BY count DESC, last_seen DESC LIMIT ?", userId, limit) { it.all() }
    }

    suspend fun topExcept(userId: Long, category: String, limit: Int): List<Mistake> = db.tx {
        sql("SELECT $COLUMNS FROM mistake WHERE user_id = ? AND NOT resolved AND category <> ? ORDER BY count DESC, last_seen DESC LIMIT ?",
            userId, category, limit) { it.all() }
    }

    suspend fun list(userId: Long, status: MistakeStatus, limit: Int = 200): List<Mistake> = db.tx {
        val where = when (status) { MistakeStatus.OPEN -> "AND NOT resolved"; MistakeStatus.RESOLVED -> "AND resolved"; MistakeStatus.ALL -> "" }
        sql("SELECT $COLUMNS FROM mistake WHERE user_id = ? $where ORDER BY count DESC, last_seen DESC LIMIT ?", userId, limit) { it.all() }
    }

    suspend fun setResolved(userId: Long, id: Long, resolved: Boolean): Boolean =
        db.tx { update("UPDATE mistake SET resolved = ? WHERE id = ? AND user_id = ?", resolved, id, userId) == 1 }
}
