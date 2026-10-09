package de.omarfourati.redefluss.vocab

import kotlin.test.*

class Sm2Test {
    private val start = Schedule(Sm2.START_EASE, 0, 0)

    @Test fun firstReviewsFollowOneSixThenEase() {
        val a = Sm2.next(start, 5); assertEquals(1, a.intervalDays); assertEquals(1, a.reps)
        val b = Sm2.next(a, 5); assertEquals(6, b.intervalDays); assertEquals(2, b.reps)
        val c = Sm2.next(b, 5); assertEquals(Math.round(6 * b.ease).toInt(), c.intervalDays); assertEquals(3, c.reps)
    }

    @Test fun easeFormula() {
        assertEquals(2.6, Sm2.next(start, 5).ease, 1e-9)
        assertEquals(2.5, Sm2.next(start, 4).ease, 1e-9)
        assertEquals(2.36, Sm2.next(start, 3).ease, 1e-9)
    }

    @Test fun failingResetsRepsButKeepsEaseFloor() {
        val s = Sm2.next(Schedule(1.35, 20, 5), 0)
        assertEquals(0, s.reps); assertEquals(1, s.intervalDays); assertEquals(1.3, s.ease, 1e-9)
    }

    @Test fun gradeIsClamped() {
        assertEquals(Sm2.next(start, 5), Sm2.next(start, 9))
        assertEquals(Sm2.next(start, 0), Sm2.next(start, -3))
    }
}
