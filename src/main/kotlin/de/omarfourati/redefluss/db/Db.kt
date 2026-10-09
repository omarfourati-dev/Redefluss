package de.omarfourati.redefluss.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Db(val dataSource: HikariDataSource, val database: Database) {
    companion object {
        fun connect(url: String, user: String, password: String): Db {
            val ds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = url; username = user; this.password = password
                maximumPoolSize = 5; poolName = "redefluss"
            })
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            return Db(ds, Database.connect(ds))
        }
    }

    fun ping() { dataSource.connection.use { it.isValid(2) || error("database not valid") } }

    suspend fun <T> tx(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(database) { block() } }
}

/** Plain JDBC inside an Exposed transaction – for upserts with RETURNING, where SQL reads clearer than the DSL. */
fun <T> JdbcTransaction.sql(query: String, vararg params: Any?, read: (ResultSet) -> T): T {
    val jdbc = connection.connection as java.sql.Connection
    jdbc.prepareStatement(query).use { st ->
        params.forEachIndexed { i, p -> st.setObject(i + 1, jdbcValue(p)) }
        st.executeQuery().use { return read(it) }
    }
}

fun JdbcTransaction.update(query: String, vararg params: Any?): Int {
    val jdbc = connection.connection as java.sql.Connection
    jdbc.prepareStatement(query).use { st ->
        params.forEachIndexed { i, p -> st.setObject(i + 1, jdbcValue(p)) }
        return st.executeUpdate()
    }
}

/** The Postgres driver takes OffsetDateTime, not Instant. */
private fun jdbcValue(p: Any?): Any? = if (p is Instant) OffsetDateTime.ofInstant(p, ZoneOffset.UTC) else p

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()
