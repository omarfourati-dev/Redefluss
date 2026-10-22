package de.omarfourati.redefluss.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

/** owner: the account from OWNER_EMAIL, may invite; guest: created through an invitation. */
enum class Role(val db: String) {
    OWNER("owner"), GUEST("guest");
    companion object { fun of(db: String): Role = entries.first { it.db == db } }
}

data class User(val id: Long, val email: String, val passwordHash: String, val tokenVersion: Int, val role: Role = Role.OWNER)
data class Account(val id: Long, val email: String, val role: Role, val createdAt: Instant)

object Users : Table("app_user") {
    val id = long("id").autoIncrement()
    val email = text("email")
    val passwordHash = text("password_hash")
    val tokenVersion = integer("token_version")
    val createdAt = timestamp("created_at")
    val role = text("role")
    override val primaryKey = PrimaryKey(id)
}

private fun ResultRow.toUser() =
    User(this[Users.id], this[Users.email], this[Users.passwordHash], this[Users.tokenVersion], Role.of(this[Users.role]))

class UserRepo(private val db: Db) {
    suspend fun findByEmail(email: String): User? =
        db.tx { Users.selectAll().where { Users.email eq email.trim().lowercase() }.singleOrNull()?.toUser() }

    suspend fun findById(id: Long): User? = db.tx { Users.selectAll().where { Users.id eq id }.singleOrNull()?.toUser() }

    suspend fun count(): Long = db.tx { sql("SELECT count(*) FROM app_user") { rs -> rs.next(); rs.getLong(1) } }

    suspend fun create(email: String, hash: String, now: Instant, role: Role = Role.OWNER): User = db.tx {
        val id = Users.insert {
            it[Users.email] = email.trim().lowercase(); it[passwordHash] = hash; it[tokenVersion] = 0; it[createdAt] = now
            it[Users.role] = role.db
        }[Users.id]
        User(id, email.trim().lowercase(), hash, 0, role)
    }

    /** New password and token_version + 1: every token issued before is invalid from now on. */
    suspend fun setPassword(id: Long, hash: String): User = db.tx {
        sql("UPDATE app_user SET password_hash = ?, token_version = token_version + 1 WHERE id = ? " +
            "RETURNING id, email, password_hash, token_version, role", hash, id) { rs ->
            rs.next() || error("user $id not found")
            User(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"), rs.getInt("token_version"), Role.of(rs.getString("role")))
        }
    }

    /** All accounts, owner first, then guests by age. */
    suspend fun list(): List<Account> = db.tx {
        sql("SELECT id, email, role, created_at FROM app_user ORDER BY (role = 'owner') DESC, created_at, id") { rs ->
            buildList { while (rs.next()) add(Account(rs.getLong("id"), rs.getString("email"), Role.of(rs.getString("role")), rs.instant("created_at"))) }
        }
    }

    /** Deletes a guest and – through ON DELETE CASCADE – all of its data. The owner can never be deleted here. */
    suspend fun deleteGuest(id: Long): Boolean = db.tx { update("DELETE FROM app_user WHERE id = ? AND role = 'guest'", id) == 1 }
}
