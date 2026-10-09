package de.omarfourati.redefluss.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Counts attempts before bcrypt runs (reserve), so a flood cannot burn CPU; a success clears its email+IP bucket.
 * Empty buckets are removed and the whole map is swept at most once a minute, so memory stays bounded.
 */
class LoginThrottle(private val clock: Clock) {
    private val window = Duration.ofMinutes(15)
    private val sweepEvery = Duration.ofMinutes(1)
    private val attempts = HashMap<String, ArrayDeque<Instant>>()
    private var lastSweep: Instant = clock.instant()

    @Synchronized fun reserve(email: String, ip: String): Boolean {
        val now = clock.instant()
        sweep(now)
        val perIp = trimmed("i:$ip", now)
        if ((perIp?.size ?: 0) >= 20) return false
        val pair = trimmed("p:$email|$ip", now)
        if ((pair?.size ?: 0) >= 5) return false
        attempts.getOrPut("i:$ip") { ArrayDeque() }.addLast(now)
        attempts.getOrPut("p:$email|$ip") { ArrayDeque() }.addLast(now)
        return true
    }

    @Synchronized fun success(email: String, ip: String) { attempts.remove("p:$email|$ip") }

    /** Single-key variant (5 per window), e.g. for password-change guessing per user. */
    @Synchronized fun reserveKey(key: String): Boolean {
        val now = clock.instant()
        sweep(now)
        val q = trimmed("k:$key", now)
        if ((q?.size ?: 0) >= 5) return false
        attempts.getOrPut("k:$key") { ArrayDeque() }.addLast(now)
        return true
    }

    @Synchronized fun clearKey(key: String) { attempts.remove("k:$key") }

    @Synchronized internal fun size(): Int { sweep(clock.instant(), force = true); return attempts.size }

    /** Returns the bucket with expired entries removed, or null (and no entry) if nothing is left. */
    private fun trimmed(key: String, now: Instant): ArrayDeque<Instant>? {
        val q = attempts[key] ?: return null
        while (q.isNotEmpty() && q.first().plus(window) <= now) q.removeFirst()
        if (q.isEmpty()) { attempts.remove(key); return null }
        return q
    }

    private fun sweep(now: Instant, force: Boolean = false) {
        if (!force && now < lastSweep.plus(sweepEvery)) return
        lastSweep = now
        attempts.keys.toList().forEach { trimmed(it, now) }
    }
}
