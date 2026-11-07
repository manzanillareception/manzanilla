package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

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

    @Test fun aSingleThreeSecondGreetingIsAvailableImmediatelyAndPartialWritesFreeBudget() {
        val rate = 16000
        val queue = PcmPlaybackQueue(rate * 2 * 8)
        val greeting = ByteArray(rate * 2 * 3) { (it % 127).toByte() }
        assertTrue(queue.offer(greeting))
        val playing = queue.poll(0)!! // No prefill delay, even without any second packet.
        assertArrayEquals(greeting, playing.pcm)
        val frameBytes = rate / 50 * 2
        repeat(150) { index ->
            queue.consumed(playing, frameBytes)
            assertEquals(greeting.size - (index + 1) * frameBytes, queue.pendingBytes())
        }
        queue.complete(playing)
        assertEquals(0, queue.pendingBytes())
    }

    @Test fun concurrentFlushFencesAnOldWriterWhileAllowingFreshSpeech() {
        val queue = PcmPlaybackQueue(32000)
        assertTrue(queue.offer(ByteArray(16000) { 1 }))
        val writing = queue.poll(0)!!
        val writeStarted = CountDownLatch(1)
        val finishOld = CountDownLatch(1)
        val oldWriter = Thread {
            queue.consumed(writing, 640)
            writeStarted.countDown()
            finishOld.await()
            queue.complete(writing)
        }.apply { start() }
        assertTrue(writeStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
        queue.clear()
        assertFalse(queue.isCurrent(writing))
        assertTrue(queue.offer(ByteArray(32000) { 2 }))
        finishOld.countDown()
        oldWriter.join(2000)
        assertFalse(oldWriter.isAlive)
        assertEquals(32000, queue.pendingBytes())
        assertArrayEquals(ByteArray(32000) { 2 }, queue.poll(0)!!.pcm)
    }

    @Test fun finiteBurstBudgetDoesNotBecomeAnUnlimitedLatencyQueue() {
        val queue = PcmPlaybackQueue(16000 * 2 * 8)
        repeat(400) { assertTrue(queue.offer(ByteArray(640))) }
        assertFalse(queue.offer(ByteArray(2)))
        assertEquals(256000, queue.pendingBytes())
        val first = queue.poll(0)!!
        queue.consumed(first, 640)
        assertTrue(queue.offer(ByteArray(640)))
        queue.complete(first)
        queue.clear()
        assertEquals(0, queue.pendingBytes())
        assertNull(queue.poll(0))
    }
}
