package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test

class PcmPlaybackQueueTest {
    @Test fun dequeuedButUnwrittenAudioStillConsumesBudget() {
        val queue = PcmPlaybackQueue(8)
        assertTrue(queue.offer(byteArrayOf(1, 2, 3, 4, 5, 6)))
        val writing = queue.poll(0)!!
        assertFalse(queue.offer(byteArrayOf(7, 8, 9, 10)))
        assertEquals(6, queue.pendingBytes())
        queue.complete(writing)
        assertEquals(0, queue.pendingBytes())
        assertTrue(queue.offer(byteArrayOf(7, 8, 9, 10)))
    }

    @Test fun interruptionFencesInFlightAudioWithoutReducingNewBudget() {
        val queue = PcmPlaybackQueue(8)
        assertTrue(queue.offer(byteArrayOf(1, 2, 3, 4)))
        val old = queue.poll(0)!!
        queue.clear()
        assertFalse(queue.isCurrent(old))
        assertTrue(queue.offer(byteArrayOf(5, 6, 7, 8, 9, 10)))
        queue.complete(old)
        assertEquals(6, queue.pendingBytes())
        assertArrayEquals(ByteArray(4), old.pcm)
        assertFalse(queue.offer(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun inputArraysAreNotBorrowedAndMalformedFramesCannotUseBudget() {
        val queue = PcmPlaybackQueue(8)
        assertFalse(queue.offer(byteArrayOf(1)))
        assertFalse(queue.offer(ByteArray(10)))
        assertEquals(0, queue.pendingBytes())
        val incoming = byteArrayOf(1, 2)
        assertTrue(queue.offer(incoming))
        incoming.fill(0)
        assertArrayEquals(byteArrayOf(1, 2), queue.poll(0)!!.pcm)
    }

    @Test fun queueCapacityRejectionDoesNotLeakAccountingAndFlushClearsIt() {
        val queue = PcmPlaybackQueue(20, maximumPackets = 1)
        assertTrue(queue.offer(byteArrayOf(1, 2)))
        assertFalse(queue.offer(byteArrayOf(3, 4)))
        assertEquals(2, queue.pendingBytes())
        queue.clear()
        assertNull(queue.poll(0))
        assertEquals(0, queue.pendingBytes())
        assertTrue(queue.offer(ByteArray(20)))
    }
}
