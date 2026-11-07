package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class NativeVoiceAttemptTest {
    @Test fun errorIsTerminalAndCannotBecomeEnded() {
        val attempt = NativeVoiceAttempt()
        assertTrue(attempt.finish(error = true))
        assertFalse(attempt.active())
        assertTrue(attempt.failed)
        assertFalse(attempt.finish())
        assertTrue(attempt.failed)
        assertEquals(NativeVoiceAttempt.Reservation.DROPPED, attempt.reservePcm(640, 32000))
    }

    @Test fun releasedCallbacksRestoreOnlyTheirOwnBudget() {
        val attempt = NativeVoiceAttempt()
        assertEquals(NativeVoiceAttempt.Reservation.ACCEPTED, attempt.reservePcm(32000, 32000))
        attempt.releasePcm(32000)
        assertEquals(NativeVoiceAttempt.Reservation.ACCEPTED, attempt.reservePcm(32000, 32000))
        assertEquals(NativeVoiceAttempt.Reservation.OVERFLOW, attempt.reservePcm(2, 32000))
        attempt.releasePcm(32000)
        // Once overflow is reported, more provider callbacks cannot queue more errors.
        assertEquals(NativeVoiceAttempt.Reservation.DROPPED, attempt.reservePcm(2, 32000))
    }

    @Test fun concurrentProviderCallbacksCannotExceedThePcmLimit() {
        val attempt = NativeVoiceAttempt()
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val overflow = AtomicInteger()
        val workers = List(16) {
            Thread {
                start.await()
                when (attempt.reservePcm(4000, 32000)) {
                    NativeVoiceAttempt.Reservation.ACCEPTED -> accepted.incrementAndGet()
                    NativeVoiceAttempt.Reservation.OVERFLOW -> overflow.incrementAndGet()
                    NativeVoiceAttempt.Reservation.DROPPED -> Unit
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertEquals(8, accepted.get())
        assertEquals(1, overflow.get())
    }

    @Test fun oversizedOrInvalidFramesFailWithoutRetainingPcm() {
        for (bytes in listOf(-2, 0, 3, 32002, Int.MAX_VALUE)) {
            val attempt = NativeVoiceAttempt()
            assertEquals(NativeVoiceAttempt.Reservation.OVERFLOW, attempt.reservePcm(bytes, 32000))
            assertEquals(NativeVoiceAttempt.Reservation.DROPPED, attempt.reservePcm(640, 32000))
        }
    }
}
