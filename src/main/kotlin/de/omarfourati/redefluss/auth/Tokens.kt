package de.omarfourati.redefluss.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import de.omarfourati.redefluss.db.User
import java.time.Clock
import java.time.Duration
import java.util.Date

class Tokens(secret: String, private val clock: Clock, private val ttl: Duration = Duration.ofDays(30)) {
    private val algorithm = Algorithm.HMAC256(secret)
    val verifier: JWTVerifier = (JWT.require(algorithm).withIssuer(ISSUER) as JWTVerifier.BaseVerification).build(clock)

    fun issue(user: User): String = JWT.create()
        .withIssuer(ISSUER)
        .withSubject(user.id.toString())
        .withClaim("tv", user.tokenVersion)
        .withIssuedAt(Date.from(clock.instant()))
        .withExpiresAt(Date.from(clock.instant().plus(ttl)))
        .sign(algorithm)

    companion object { const val ISSUER = "redefluss" }
}
