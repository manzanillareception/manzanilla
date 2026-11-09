package com.agentdeck.hotel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test fun `exponential growth with deterministic jitter is capped`() {
        val backoff = Backoff(baseMs = 1_000, capMs = 10_000, random = { 0.5 })
        val first = backoff.nextDelayMs()
        val second = backoff.nextDelayMs()
        val tenth = (1..8).fold(second) { acc, _ -> backoff.nextDelayMs() }
        assertTrue(first in 250..1_000)
        assertTrue(second > first)
        assertTrue(tenth <= 10_000)
    }

    @Test fun `server retryAfter wins and is capped`() {
        val backoff = Backoff(baseMs = 1_000, capMs = 10_000, random = { 0.0 })
        assertEquals((5_000 * 0.75).toLong(), backoff.nextDelayMs(retryAfterMs = 5_000))
        assertEquals(7_500L, backoff.nextDelayMs(retryAfterMs = 60_000))
    }

    @Test fun `reset returns to base`() {
        val backoff = Backoff(baseMs = 1_000, capMs = 10_000, random = { 0.0 })
        backoff.nextDelayMs(); backoff.nextDelayMs()
        backoff.reset()
        assertEquals(750L, backoff.nextDelayMs())
    }
}
