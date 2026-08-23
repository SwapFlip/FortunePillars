package com.swapflip.fortunepillars.util

interface Ticking {
    fun tick(tick: Tick)

    data class Tick(val number: Int) {
        fun isSecond(startingTick: Int): Boolean = isInInterval(startingTick, 20)
        // Fires on the starting tick and then every interval. Intervals of 0 or less (misconfigured
        // configs) must never crash the tick loop with a modulo-by-zero, so they are clamped to 1.
        // Callers offset the starting tick by their own delay, so firing on the starting tick
        // itself is the intended "fires right when the delay is over" behavior.
        fun isInInterval(startingTick: Int, interval: Int): Boolean {
            // The delta guard is essential: without it, ticks BEFORE the anchor whose distance
            // happens to be an exact multiple of the interval would also match, breaking every
            // modifier's start-delay grace period (hazards firing at t=0).
            val delta = number - startingTick
            return delta >= 0 && delta % interval.coerceAtLeast(1) == 0
        }
    }
}

// Convenience wrapper for the common "fire every <interval> seconds starting <delay> seconds after
// the game anchor" pattern used by modifiers. Keeps the *20 (ticks-per-second) math in one place.
fun Ticking.Tick.inModifierWindow(anchorTick: Int, startDelaySecs: Int, intervalSecs: Int) =
    isInInterval(anchorTick + startDelaySecs * 20, intervalSecs * 20)
