package de.omarfourati.redefluss.auth

import de.omarfourati.redefluss.db.UserRepo
import de.omarfourati.redefluss.http.ApiException
import de.omarfourati.redefluss.http.badRequest
import de.omarfourati.redefluss.metrics.Metrics
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Clock

@Serializable data class LoginResponse(val token: String, val email: String)
data class UserPrincipal(val id: Long, val email: String)

class AuthService(
    private val users: UserRepo, val tokens: Tokens, private val throttle: LoginThrottle,
    private val metrics: Metrics, private val clock: Clock,
) {
    suspend fun login(email: String, password: String, ip: String): LoginResponse {
        val mail = email.trim().lowercase()
        if (!throttle.reserve(mail, ip)) {
            metrics.login("throttled")
            throw ApiException(HttpStatusCode.TooManyRequests, "Too Many Requests", "Zu viele Versuche. Bitte versuch es in 15 Minuten noch einmal.")
        }
        val user = users.findByEmail(mail)
        // verify even for unknown emails so both cases take the same time
        val ok = Passwords.verify(password, user?.passwordHash ?: DUMMY_HASH) && user != null
        if (!ok) {
            metrics.login("failed")
            throw ApiException(HttpStatusCode.Unauthorized, "Unauthorized", "E-Mail oder Passwort ist falsch.")
        }
        throttle.success(mail, ip)
        metrics.login("ok")
        return LoginResponse(tokens.issue(user), user.email)
    }

    suspend fun changePassword(id: Long, current: String, next: String): LoginResponse {
        val user = users.findById(id) ?: throw ApiException(HttpStatusCode.Unauthorized, "Unauthorized", "Bitte melde dich an.")
        val key = "pw-change:$id"
        if (!throttle.reserveKey(key)) {
            throw ApiException(HttpStatusCode.TooManyRequests, "Too Many Requests", "Zu viele Versuche. Bitte versuch es in 15 Minuten noch einmal.")
        }
        if (!Passwords.verify(current, user.passwordHash)) throw badRequest("Das aktuelle Passwort stimmt nicht.")
        throttle.clearKey(key)
        Passwords.problem(next)?.let { throw badRequest(it) }
        val updated = users.setPassword(id, Passwords.hash(next))
        return LoginResponse(tokens.issue(updated), updated.email)
    }

    /** The single account comes from OWNER_EMAIL/OWNER_PASSWORD; a password changed in the app is kept unless reset=true. */
    suspend fun bootstrapOwner(email: String, password: String, reset: Boolean) {
        Passwords.problem(password)?.let { error("OWNER_PASSWORD: $it") }
        val existing = users.findByEmail(email)
        when {
            existing == null && users.count() > 0 ->
                LoggerFactory.getLogger("redefluss").warn("OWNER_EMAIL differs from the existing account; keeping the existing single account unchanged")
            existing == null -> users.create(email, Passwords.hash(password), clock.instant())
            reset && !Passwords.verify(password, existing.passwordHash) -> users.setPassword(existing.id, Passwords.hash(password))
        }
    }

    suspend fun principalFor(userId: Long, tokenVersion: Int): UserPrincipal? =
        users.findById(userId)?.takeIf { it.tokenVersion == tokenVersion }?.let { UserPrincipal(it.id, it.email) }

    private companion object { val DUMMY_HASH: String = Passwords.hash("dummy-password-for-timing") }
}
