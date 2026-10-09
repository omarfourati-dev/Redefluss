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

class VocabRepo(private val db: Db) {
    companion object {
        private val ARTICLE = Regex("""^(der|die|das|ein|eine)\s+""", RegexOption.IGNORE_CASE)
        private val SPACE = Regex("""\s+""")
        fun key(word: String): String = word.trim().replace(SPACE, " ").replace(ARTICLE, "").lowercase(java.util.Locale.GERMAN)
    }

    suspend fun insertIfNew(card: NewCard, today: LocalDate): Card? = db.tx {
        sql("""
            INSERT INTO vocab_card (word, word_key, article, plural, meaning, example, theme, source, due_on, created_on)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (word_key) DO NOTHING
            RETURNING $COLS
        """.trimIndent(), card.word.trim().replace(ARTICLE, ""), key(card.word), card.article.trim(), card.plural.trim(),
            card.meaning.trim(), card.example.trim(), card.theme, card.source, today, today) { if (it.next()) it.card() else null }
    }

    suspend fun createdOn(day: LocalDate): List<Card> =
        db.tx { sql("SELECT $COLS FROM vocab_card WHERE created_on = ? AND source = 'daily' ORDER BY id", day) { it.cards() } }

    suspend fun due(today: LocalDate, limit: Int = 30): List<Card> =
        db.tx { sql("SELECT $COLS FROM vocab_card WHERE due_on <= ? ORDER BY due_on, id LIMIT ?", today, limit) { it.cards() } }

    suspend fun dueCount(today: LocalDate): Int =
        db.tx { sql("SELECT count(*) FROM vocab_card WHERE due_on <= ?", today) { rs -> rs.next(); rs.getInt(1) } }

    suspend fun find(id: Long): Card? = db.tx { sql("SELECT $COLS FROM vocab_card WHERE id = ?", id) { if (it.next()) it.card() else null } }

    suspend fun recentWords(limit: Int = 300): List<String> =
        db.tx { sql("SELECT word FROM vocab_card ORDER BY id DESC LIMIT ?", limit) { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    suspend fun schedule(id: Long, s: Schedule, grade: Int, due: LocalDate): Boolean = db.tx {
        update("UPDATE vocab_card SET ease = ?, interval_days = ?, reps = ?, last_grade = ?, due_on = ? WHERE id = ?",
            s.ease, s.intervalDays, s.reps, grade, due, id) == 1
    }
}
