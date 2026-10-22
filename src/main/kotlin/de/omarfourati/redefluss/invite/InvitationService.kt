package de.omarfourati.redefluss.invite

import de.omarfourati.redefluss.auth.LoginResponse
import de.omarfourati.redefluss.auth.LoginThrottle
import de.omarfourati.redefluss.auth.Passwords
import de.omarfourati.redefluss.auth.Tokens
import de.omarfourati.redefluss.auth.UserPrincipal
import de.omarfourati.redefluss.auth.loginResponse
import de.omarfourati.redefluss.db.UserRepo
import de.omarfourati.redefluss.http.ApiException
import de.omarfourati.redefluss.http.badRequest
import de.omarfourati.redefluss.http.notFound
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64

@Serializable data class InvitationDto(val id: Long, val email: String, val createdAt: String, val expiresAt: String, val status: String)
/** [token] is returned exactly once, right after creating; only its hash is stored. */
@Serializable data class CreatedInvitationDto(val id: Long, val email: String, val token: String, val expiresAt: String)
@Serializable data class AccountDto(val id: Long, val email: String, val role: String, val createdAt: String)

val INVITATION_TTL: Duration = Duration.ofDays(7)
private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
private const val GONE = "Diese Einladung ist ungültig, abgelaufen oder wurde schon benutzt."

/** Creates and redeems invitations; only the owner manages them and the guest accounts. */
class InvitationService(
    private val invitations: InvitationRepo, private val users: UserRepo, private val tokens: Tokens,
    private val throttle: LoginThrottle, private val clock: Clock,
) {
    private val random = SecureRandom()

    suspend fun create(user: UserPrincipal, rawEmail: String): CreatedInvitationDto {
        requireOwner(user)
        val email = rawEmail.trim().lowercase()
        if (email.length > 254 || !EMAIL.matches(email)) throw badRequest("Bitte gib eine gültige E-Mail-Adresse ein.")
        if (users.findByEmail(email) != null) throw ApiException(HttpStatusCode.Conflict, "Conflict", "Für diese E-Mail gibt es schon ein Konto.")
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val now = clock.instant()
        val invitation = invitations.create(email, hash(token), user.id, now, now.plus(INVITATION_TTL))
        return CreatedInvitationDto(invitation.id, invitation.email, token, invitation.expiresAt.toString())
    }

    suspend fun list(user: UserPrincipal): List<InvitationDto> {
        requireOwner(user)
        val now = clock.instant()
        return invitations.list().map {
            val status = when { it.usedAt != null -> "used"; it.expiresAt <= now -> "expired"; else -> "open" }
            InvitationDto(it.id, it.email, it.createdAt.toString(), it.expiresAt.toString(), status)
        }
    }

    suspend fun revoke(user: UserPrincipal, id: Long) {
        requireOwner(user)
        if (!invitations.deleteOpen(id)) throw notFound("Diese Einladung gibt es nicht mehr oder sie wurde schon benutzt.")
    }

    /** The invited email for an open link (shown before the password is set). */
    suspend fun preview(token: String, ip: String): String {
        reserve(ip)
        val invitation = invitations.findOpen(hash(token), clock.instant()) ?: throw gone()
        throttle.clearKey(key(ip))
        return invitation.email
    }

    /** Creates the guest account with the chosen password and signs it in. */
    suspend fun accept(token: String, password: String, ip: String): LoginResponse {
        Passwords.problem(password)?.let { throw badRequest(it) }
        reserve(ip)
        return when (val result = invitations.accept(hash(token), Passwords.hash(password), clock.instant())) {
            is AcceptResult.Accepted -> { throttle.clearKey(key(ip)); result.user.loginResponse(tokens) }
            AcceptResult.EmailTaken -> throw ApiException(HttpStatusCode.Conflict, "Conflict", "Für diese E-Mail gibt es schon ein Konto. Bitte melde dich an.")
            AcceptResult.Invalid -> throw gone()
        }
    }

    suspend fun accounts(user: UserPrincipal): List<AccountDto> {
        requireOwner(user)
        return users.list().map { AccountDto(it.id, it.email, it.role.db, it.createdAt.toString()) }
    }

    /** Deletes a guest with all of its data (mistakes, sessions, words, usage). */
    suspend fun removeGuest(user: UserPrincipal, id: Long) {
        requireOwner(user)
        if (!users.deleteGuest(id)) throw notFound("Dieses Gastkonto gibt es nicht.")
    }

    private fun requireOwner(user: UserPrincipal) {
        if (!user.isOwner) throw ApiException(HttpStatusCode.Forbidden, "Forbidden", "Nur der Besitzer von Redefluss kann Einladungen und Gäste verwalten.")
    }

    /** At most 5 token attempts per IP in 15 minutes; a valid token clears the bucket. */
    private fun reserve(ip: String) {
        if (!throttle.reserveKey(key(ip)))
            throw ApiException(HttpStatusCode.TooManyRequests, "Too Many Requests", "Zu viele Versuche. Bitte versuch es in 15 Minuten noch einmal.")
    }

    private fun key(ip: String) = "invite:$ip"
    private fun gone() = ApiException(HttpStatusCode.Gone, "Gone", GONE)

    companion object {
        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
