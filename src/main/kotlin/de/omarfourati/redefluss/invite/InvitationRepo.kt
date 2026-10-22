package de.omarfourati.redefluss.invite

import de.omarfourati.redefluss.db.Db
import de.omarfourati.redefluss.db.Role
import de.omarfourati.redefluss.db.User
import de.omarfourati.redefluss.db.instant
import de.omarfourati.redefluss.db.sql
import de.omarfourati.redefluss.db.update
import java.sql.ResultSet
import java.time.Instant

data class Invitation(val id: Long, val email: String, val createdAt: Instant, val expiresAt: Instant, val usedAt: Instant?)

sealed interface AcceptResult {
    data class Accepted(val user: User) : AcceptResult
    data object Invalid : AcceptResult
    data object EmailTaken : AcceptResult
}

private const val COLS = "id, email, created_at, expires_at, used_at"
private fun ResultSet.invitation() = Invitation(getLong("id"), getString("email"), instant("created_at"), instant("expires_at"),
    if (getObject("used_at") == null) null else instant("used_at"))

/** Invitations store only the SHA-256 of their token; the token itself exists only in the link. */
class InvitationRepo(private val db: Db) {
    suspend fun create(email: String, tokenHash: String, createdBy: Long, now: Instant, expiresAt: Instant): Invitation = db.tx {
        sql("INSERT INTO invitation (email, token_hash, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?) RETURNING $COLS",
            email, tokenHash, createdBy, now, expiresAt) { rs -> rs.next(); rs.invitation() }
    }

    suspend fun list(): List<Invitation> = db.tx {
        sql("SELECT $COLS FROM invitation ORDER BY created_at DESC, id DESC LIMIT 100") { rs -> buildList { while (rs.next()) add(rs.invitation()) } }
    }

    /** The unused, unexpired invitation for [tokenHash], or null. */
    suspend fun findOpen(tokenHash: String, now: Instant): Invitation? = db.tx {
        sql("SELECT $COLS FROM invitation WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?", tokenHash, now) {
            if (it.next()) it.invitation() else null
        }
    }

    /** Withdraws an invitation that has not been used yet. */
    suspend fun deleteOpen(id: Long): Boolean = db.tx { update("DELETE FROM invitation WHERE id = ? AND used_at IS NULL", id) == 1 }

    /**
     * One transaction: the open invitation is locked (FOR UPDATE, so the same link cannot create two accounts),
     * a guest account with the invited email is created and the invitation is marked used.
     */
    suspend fun accept(tokenHash: String, passwordHash: String, now: Instant): AcceptResult = db.tx {
        val invitation = sql("SELECT id, email FROM invitation WHERE token_hash = ? AND used_at IS NULL AND expires_at > ? FOR UPDATE",
            tokenHash, now) { if (it.next()) it.getLong(1) to it.getString(2) else null } ?: return@tx AcceptResult.Invalid
        val (invitationId, email) = invitation
        if (sql("SELECT 1 FROM app_user WHERE email = ?", email) { it.next() }) return@tx AcceptResult.EmailTaken
        val userId = sql("""
            INSERT INTO app_user (email, password_hash, token_version, created_at, role) VALUES (?, ?, 0, ?, 'guest') RETURNING id
        """.trimIndent(), email, passwordHash, now) { rs -> rs.next(); rs.getLong(1) }
        update("UPDATE invitation SET used_at = ? WHERE id = ?", now, invitationId)
        AcceptResult.Accepted(User(userId, email, passwordHash, 0, Role.GUEST))
    }
}
