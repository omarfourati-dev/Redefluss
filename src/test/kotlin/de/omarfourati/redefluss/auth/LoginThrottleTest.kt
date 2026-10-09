package de.omarfourati.redefluss.auth

import java.time.*
import kotlin.test.*

class LoginThrottleTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }

    @Test fun fivePerEmailAndIpThenBlockedForFifteenMinutes() {
        val clock = MutableClock(Instant.parse("2026-10-08T10:00:00Z"))
        val t = LoginThrottle(clock)
        repeat(5) { assertTrue(t.reserve("a@b.de", "1.1.1.1")) }
        assertFalse(t.reserve("a@b.de", "1.1.1.1"))
        assertTrue(t.reserve("a@b.de", "2.2.2.2"))
        clock.now = clock.now.plus(Duration.ofMinutes(15)).plusSeconds(1)
        assertTrue(t.reserve("a@b.de", "1.1.1.1"))
    }

    @Test fun twentyPerIp() {
        val t = LoginThrottle(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))
        repeat(20) { assertTrue(t.reserve("user$it@b.de", "3.3.3.3")) }
        assertFalse(t.reserve("new@b.de", "3.3.3.3"))
    }

    @Test fun expiredAndEmptyBucketsAreDropped() {
        val clock = MutableClock(Instant.parse("2026-10-08T10:00:00Z"))
        val t = LoginThrottle(clock)
        repeat(30) { t.reserve("user$it@b.de", "9.9.9.$it") }
        t.reserveKey("user:1")
        assertTrue(t.size() > 0)
        clock.now = clock.now.plus(Duration.ofMinutes(16))
        assertEquals(0, t.size())
    }

    @Test fun rejectedRequestsDoNotAllocateBuckets() {
        val t = LoginThrottle(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))
        repeat(20) { t.reserve("user$it@b.de", "3.3.3.3") }
        val before = t.size()
        repeat(50) { assertFalse(t.reserve("spray$it@b.de", "3.3.3.3")) }
        assertEquals(before, t.size())
    }

    @Test fun successClearsTheEmailIpBucket() {
        val t = LoginThrottle(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))
        repeat(5) { t.reserve("a@b.de", "1.1.1.1") }
        t.success("a@b.de", "1.1.1.1")
        assertTrue(t.reserve("a@b.de", "1.1.1.1"))
    }
}
