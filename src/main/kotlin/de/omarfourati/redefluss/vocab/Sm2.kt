package de.omarfourati.redefluss.vocab

import kotlin.math.max
import kotlin.math.roundToInt

data class Schedule(val ease: Double, val intervalDays: Int, val reps: Int)

/** SuperMemo-2, as in Anki's ancestor: grade 0–5, failed cards start over, ease never below 1.3. */
object Sm2 {
    const val START_EASE = 2.5

    fun next(prev: Schedule, grade: Int): Schedule {
        val g = grade.coerceIn(0, 5)
        val ease = max(1.3, prev.ease + 0.1 - (5 - g) * (0.08 + (5 - g) * 0.02))
        if (g < 3) return Schedule(ease, 1, 0)
        val reps = prev.reps + 1
        val interval = when (reps) { 1 -> 1; 2 -> 6; else -> (prev.intervalDays * prev.ease).roundToInt() }
        return Schedule(ease, interval, reps)
    }
}
