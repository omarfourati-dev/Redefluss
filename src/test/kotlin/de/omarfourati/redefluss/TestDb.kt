package de.omarfourati.redefluss

import de.omarfourati.redefluss.db.Db
import de.omarfourati.redefluss.db.update
import kotlinx.coroutines.runBlocking
import org.testcontainers.postgresql.PostgreSQLContainer

/** One Postgres container for the whole test run; reset() empties all tables between tests. */
object TestDb {
    private val container = PostgreSQLContainer("postgres:17-alpine").apply { start() }
    val db: Db by lazy { Db.connect(container.jdbcUrl, container.username, container.password) }

    fun reset(): Db = db.also {
        runBlocking { it.tx { update("TRUNCATE app_user, mistake, practice_session, usage_day RESTART IDENTITY") } }
    }
}
