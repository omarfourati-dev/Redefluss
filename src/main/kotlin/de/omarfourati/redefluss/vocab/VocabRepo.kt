package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.db.Db
import de.omarfourati.redefluss.db.sql
import de.omarfourati.redefluss.db.update
import java.sql.ResultSet
import java.time.LocalDate

data class NewCard(val word: String, val article: String, val plural: String, val meaning: String,
                   val example: String, val theme: String, val source: String)
data class Card(val id: Long, val word: String, val article: String, val plural: String, val meaning: String,
                val example: String, val theme: String, val source: String, val ease: Double, val intervalDays: Int,
                val reps: Int, val dueOn: LocalDate, val createdOn: LocalDate, val lastGrade: Int?)

private const val COLS = "id, word, article, plural, meaning, example, theme, source, ease, interval_days, reps, due_on, created_on, last_grade"
private fun ResultSet.card() = Card(getLong("id"), getString("word"), getString("article"), getString("plural"),
    getString("meaning"), getString("example"), getString("theme"), getString("source"), getDouble("ease"),
    getInt("interval_days"), getInt("reps"), getObject("due_on", LocalDate::class.java),
    getObject("created_on", LocalDate::class.java), getInt("last_grade").takeUnless { wasNull() })
private fun ResultSet.cards(): List<Card> = buildList { while (next()) add(card()) }

/** Every query is scoped to one account (userId). */
class VocabRepo(private val db: Db) {
    companion object {
        private val ARTICLE = Regex("""^(der|die|das)(\s+|$)""", RegexOption.IGNORE_CASE)
        private val SPACE = Regex("""\s+""")
        fun key(word: String): String = word.trim().replace(SPACE, " ").replace(ARTICLE, "").lowercase(java.util.Locale.GERMAN)
    }

    suspend fun insertIfNew(userId: Long, card: NewCard, today: LocalDate): Card? {
        if (key(card.word).isEmpty()) return null
        return insertKeyed(userId, card, today)
    }

    private suspend fun insertKeyed(userId: Long, card: NewCard, today: LocalDate): Card? = db.tx {
        sql("""
            INSERT INTO vocab_card (user_id, word, word_key, article, plural, meaning, example, theme, source, due_on, created_on)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, word_key) DO NOTHING
            RETURNING $COLS
        """.trimIndent(), userId, card.word.trim().replace(ARTICLE, "").trim(), key(card.word), card.article.trim(), card.plural.trim(),
            card.meaning.trim(), card.example.trim(), card.theme, card.source, today, today) { if (it.next()) it.card() else null }
    }

    suspend fun createdOn(userId: Long, day: LocalDate): List<Card> = db.tx {
        sql("SELECT $COLS FROM vocab_card WHERE user_id = ? AND created_on = ? AND source = 'daily' ORDER BY id", userId, day) { it.cards() }
    }

    suspend fun due(userId: Long, today: LocalDate, limit: Int = 30): List<Card> = db.tx {
        sql("SELECT $COLS FROM vocab_card WHERE user_id = ? AND due_on <= ? ORDER BY due_on, id LIMIT ?", userId, today, limit) { it.cards() }
    }

    /** Cards with an example sentence: due ones first (oldest due first), then the newest. */
    suspend fun forPronunciation(userId: Long, today: LocalDate, limit: Int): List<Card> = db.tx {
        sql("""
            SELECT $COLS FROM vocab_card WHERE user_id = ? AND btrim(example) <> ''
            ORDER BY (due_on <= ?) DESC, CASE WHEN due_on <= ? THEN due_on END, id DESC LIMIT ?
        """.trimIndent(), userId, today, today, limit) { it.cards() }
    }

    suspend fun dueCount(userId: Long, today: LocalDate): Int = db.tx {
        sql("SELECT count(*) FROM vocab_card WHERE user_id = ? AND due_on <= ?", userId, today) { rs -> rs.next(); rs.getInt(1) }
    }

    suspend fun find(userId: Long, id: Long): Card? = db.tx {
        sql("SELECT $COLS FROM vocab_card WHERE id = ? AND user_id = ?", id, userId) { if (it.next()) it.card() else null }
    }

    suspend fun recentWords(userId: Long, limit: Int = 300): List<String> = db.tx {
        sql("SELECT word FROM vocab_card WHERE user_id = ? ORDER BY id DESC LIMIT ?", userId, limit) { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }

    suspend fun schedule(userId: Long, id: Long, s: Schedule, grade: Int, due: LocalDate): Boolean = db.tx {
        update("UPDATE vocab_card SET ease = ?, interval_days = ?, reps = ?, last_grade = ?, due_on = ? WHERE id = ? AND user_id = ?",
            s.ease, s.intervalDays, s.reps, grade, due, id, userId) == 1
    }
}
