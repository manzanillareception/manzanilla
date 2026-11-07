package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class NativeVoiceAttemptTest {
    @Test fun errorIsTerminalAndCannotBecomeEndedOrReady() {
        val attempt = NativeVoiceAttempt()
        assertTrue(attempt.markReady())
        assertTrue(attempt.ready)
        assertTrue(attempt.finish(error = true))
        assertFalse(attempt.active())
        assertFalse(attempt.ready)
        assertTrue(attempt.failed)
        assertFalse(attempt.finish())
        assertFalse(attempt.markReady())
        assertTrue(attempt.failed)
    }

    @Test fun concurrentTerminationHasExactlyOneOwner() {
        val attempt = NativeVoiceAttempt()
        val start = CountDownLatch(1)
        val terminal = AtomicInteger()
        val workers = List(16) {
            Thread {
                start.await()
                if (attempt.finish()) terminal.incrementAndGet()
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertEquals(1, terminal.get())
        assertFalse(attempt.active())
    }

    @Test fun metersCoalesceWhileUiIsStalledAndKeepLatestLevel() {
        val attempt = NativeVoiceAttempt()
        assertTrue(attempt.reserveEnergy(0.1f, 0))
        repeat(200) { assertFalse(attempt.reserveEnergy(0.8f, (it + 1L) * 1_000_000)) }
        assertEquals(0.8f, attempt.level, 0f)
        attempt.releaseEnergy()
        assertTrue(attempt.reserveEnergy(0.2f, 201_000_000))
        attempt.releaseEnergy()
        assertFalse(attempt.reserveEnergy(0.3f, 202_000_000))
        attempt.finish()
        assertFalse(attempt.reserveEnergy(0.4f, 1_000_000_000))
    }
}
