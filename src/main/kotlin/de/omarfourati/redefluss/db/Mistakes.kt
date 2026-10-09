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

class MistakeRepo(private val db: Db) {
    /** Same normalized mistake → count + 1, newest wording/rule/example, re-opened. */
    suspend fun record(m: NewMistake, now: Instant): Long = db.tx {
        val category = MistakeKey.category(m.category)
        sql("""
            INSERT INTO mistake (category, wrong, correct, rule, example, normalized, first_seen, last_seen)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (normalized) DO UPDATE SET count = mistake.count + 1, wrong = EXCLUDED.wrong,
              correct = EXCLUDED.correct, rule = EXCLUDED.rule, example = EXCLUDED.example,
              last_seen = EXCLUDED.last_seen, resolved = false
            RETURNING id
        """.trimIndent(), category, m.wrong.trim(), m.right.trim(), m.rule.trim(), m.example.take(300),
            MistakeKey.normalize(category, m.wrong, m.right), now, now) { rs -> rs.next(); rs.getLong(1) }
    }

    suspend fun top(limit: Int): List<Mistake> = db.tx {
        sql("SELECT $COLUMNS FROM mistake WHERE NOT resolved ORDER BY count DESC, last_seen DESC LIMIT ?", limit) { it.all() }
    }

    suspend fun list(status: MistakeStatus, limit: Int = 200): List<Mistake> = db.tx {
        val where = when (status) { MistakeStatus.OPEN -> "WHERE NOT resolved"; MistakeStatus.RESOLVED -> "WHERE resolved"; MistakeStatus.ALL -> "" }
        sql("SELECT $COLUMNS FROM mistake $where ORDER BY count DESC, last_seen DESC LIMIT ?", limit) { it.all() }
    }

    suspend fun setResolved(id: Long, resolved: Boolean): Boolean =
        db.tx { update("UPDATE mistake SET resolved = ? WHERE id = ?", resolved, id) == 1 }
}
