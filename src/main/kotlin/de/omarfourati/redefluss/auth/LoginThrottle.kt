package de.omarfourati.redefluss.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Counts attempts before bcrypt runs (reserve), so a flood cannot burn CPU; a success clears its email+IP bucket. */
class LoginThrottle(private val clock: Clock) {
    private val window = Duration.ofMinutes(15)
    private val attempts = HashMap<String, ArrayDeque<Instant>>()

    @Synchronized fun reserve(email: String, ip: String): Boolean {
        val now = clock.instant()
        val pair = bucket("p:$email|$ip", now)
        val perIp = bucket("i:$ip", now)
        if (pair.size >= 5 || perIp.size >= 20) return false
        pair.addLast(now); perIp.addLast(now)
        return true
    }

    @Synchronized fun success(email: String, ip: String) { attempts.remove("p:$email|$ip") }

    private fun bucket(key: String, now: Instant) = attempts.getOrPut(key) { ArrayDeque() }.also { q ->
        while (q.isNotEmpty() && q.first().plus(window) <= now) q.removeFirst()
    }
}
