package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test

class SessionOwnerTest {
    private fun request(id: String = "guest-a", mode: String = "translation", guest: String = "en", staff: String = "es") =
        SessionOwner.Request(id, mode, guest, staff)

    @Test fun releaseMustFinishBeforeAnotherGuestCanStart() {
        val owner = SessionOwner()
        val first = owner.acquire(request())!!
        assertNull(owner.acquire(request("guest-b")))
        assertEquals(first, owner.beginClose())
        assertFalse(owner.accepts(first.request.id, first.generation))
        assertTrue(owner.accepts(first.request.id, first.generation, duringClose = true))
        assertNull(owner.acquire(request("guest-b")))
        assertTrue(owner.released(first, knownOff = true))
        assertNotNull(owner.acquire(request("guest-b")))
    }

    @Test fun lateCallbacksCannotReviveOrCloseTheNextSession() {
        val owner = SessionOwner()
        val first = owner.acquire(request())!!
        owner.beginClose()
        owner.released(first, knownOff = true)
        val next = owner.acquire(request("guest-b"))!!
        assertFalse(owner.accepts(first.request.id, first.generation, duringClose = true))
        assertFalse(owner.accepts(next.request.id, first.generation))
        assertFalse(owner.released(first, knownOff = true))
        assertTrue(owner.accepts(next.request.id, next.generation))
    }

    @Test fun unconfirmedMicrophoneReleaseBlocksAnotherCapture() {
        val owner = SessionOwner()
        val lease = owner.acquire(request())!!
        owner.beginClose()
        assertTrue(owner.released(lease, knownOff = false))
        assertTrue(owner.audioUnknown)
        assertNull(owner.acquire(request("guest-b")))
    }

    @Test fun onlyTheMatchingLateReleaseCanClearUnknownAudio() {
        val owner = SessionOwner()
        val lease = owner.acquire(request())!!
        owner.beginClose()
        owner.released(lease, knownOff = false)
        assertFalse(owner.confirmLateRelease("another-guest", lease.generation))
        assertFalse(owner.confirmLateRelease(lease.request.id, lease.generation + 1))
        assertTrue(owner.confirmLateRelease(lease.request.id, lease.generation))
        val next = owner.acquire(request("guest-b"))!!
        assertFalse(owner.confirmLateRelease(lease.request.id, lease.generation))
        assertTrue(owner.accepts(next.request.id, next.generation))
    }

    @Test fun beginFailurePathResetsUnknownAudioSoTheNextLeaseCanStart() {
        val owner = SessionOwner()
        val lease = owner.acquire(request())!!
        owner.beginClose()
        val knownOff = owner.released(lease, knownOff = false)
        assertTrue(knownOff)
        assertTrue(owner.audioUnknown)
        val last = owner.lastReleasedLease!!
        assertTrue(owner.confirmLateRelease(last.request.id, last.generation))
        assertFalse(owner.audioUnknown)
        assertFalse(owner.confirmLateRelease(request("guest-b").id, 0))
        val next = owner.acquire(request("guest-b"))
        assertNotNull(next)
        assertEquals(lease.generation + 1, next!!.generation)
        // A later real lease event carries the real generation and still matches:
        assertTrue(owner.accepts(next.request.id, next.generation))
    }

    @Test fun duplicateReleasedCallStaysIdempotent() {
        val owner = SessionOwner()
        val lease = owner.acquire(request())!!
        owner.beginClose()
        assertTrue(owner.released(lease, knownOff = true))
        assertFalse(owner.released(lease, knownOff = true))
        assertFalse(owner.audioUnknown)
        val last = owner.lastReleasedLease!!
        assertFalse(owner.confirmLateRelease(last.request.id, last.generation))
        assertFalse(owner.audioUnknown)
        assertNotNull(owner.acquire(request("guest-b")))
    }

    @Test fun duplicateTerminalAcknowledgementDoesNotChangeOwnership() {
        val owner = SessionOwner()
        val lease = owner.acquire(request())!!
        assertFalse(owner.released(lease, knownOff = true))
        owner.beginClose()
        assertNull(owner.beginClose())
        assertTrue(owner.released(lease, knownOff = true))
        assertFalse(owner.released(lease, knownOff = false))
        assertFalse(owner.audioUnknown)
    }

    @Test fun sameLanguageAssistanceIsNotAnInterpreter() {
        val owner = SessionOwner()
        assertNotNull(owner.acquire(request(mode = "reception", guest = "es", staff = "es")))
        val interpreter = SessionOwner()
        assertThrows(IllegalArgumentException::class.java) {
            interpreter.acquire(request(guest = "es", staff = "es"))
        }
        assertNull(interpreter.current)
    }

    @org.junit.Test fun cancellationBeforeGenerationStillMatchesOnlyTheCurrentUuid() {
        val owner = SessionOwner()
        val lease = owner.acquire(SessionOwner.Request("current", "translation", "en", "es"))!!
        org.junit.Assert.assertTrue(owner.acceptsCancellation("current", null))
        org.junit.Assert.assertTrue(owner.acceptsCancellation("current", lease.generation))
        org.junit.Assert.assertFalse(owner.acceptsCancellation("another", null))
        org.junit.Assert.assertFalse(owner.acceptsCancellation("current", lease.generation + 1))
        owner.beginClose(); owner.released(lease, true)
        org.junit.Assert.assertFalse(owner.acceptsCancellation("current", null))
    }
}
