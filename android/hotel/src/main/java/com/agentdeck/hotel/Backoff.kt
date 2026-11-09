package com.agentdeck.hotel

/**
 * Exponential backoff with jitter for management-plane calls. Deterministic under test:
 * the random source and clock are injected. retryAfterMs from the server takes precedence.
 */
class Backoff(
    private val baseMs: Long = 5_000,
    private val capMs: Long = 300_000,
    private val random: () -> Double = { Math.random() }
) {
    private var failures = 0

    fun reset() { failures = 0 }

    /** Delay before the next attempt after a failure. */
    fun nextDelayMs(retryAfterMs: Long? = null): Long {
        failures += 1
        if (retryAfterMs != null && retryAfterMs >= 0) {
            return jitter(retryAfterMs.coerceAtMost(capMs))
        }
        val shift = (failures - 1).coerceAtMost(20)
        val exponential = baseMs.shlBounded(shift).coerceAtMost(capMs)
        return jitter(exponential)
    }

    private fun jitter(value: Long): Long {
        val factor = 0.75 + random().coerceIn(0.0, 1.0) * 0.5
        return (value * factor).toLong().coerceAtLeast(250)
    }

    private fun Long.shlBounded(shift: Int): Long {
        var value = this
        repeat(shift) {
            if (value >= capMs) return capMs
            value = (value * 2).coerceAtMost(capMs)
        }
        return value
    }
}
