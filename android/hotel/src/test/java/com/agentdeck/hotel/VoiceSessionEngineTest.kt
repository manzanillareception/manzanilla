package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VoiceSessionEngineTest {
    private class Ui : VoiceSessionUi {
        private val thread = Thread.currentThread()
        val pending = ConcurrentLinkedQueue<() -> Unit>()
        val delayed = ConcurrentLinkedQueue<Pair<Long, () -> Unit>>()
        override fun isOnThread() = Thread.currentThread() === thread
        override fun post(action: () -> Unit): Boolean { pending.add(action); return true }
        override fun postDelayed(action: () -> Unit, delayMillis: Long): Boolean { delayed.add(delayMillis to action); return true }
        fun drain() { while (true) (pending.poll() ?: break).invoke() }
        fun runDelay(delayMillis: Long) {
            val task = delayed.first { it.first == delayMillis }
            assertTrue(delayed.remove(task))
            task.second.invoke()
        }
    }
    private class Audio(private val callbacks: VoiceAudioCallbacks, delayed: Boolean) : VoiceAudioDevice {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(if (delayed) 1 else 0)
        val started = CountDownLatch(1)
        val queue = PcmPlaybackQueue(16000 * 2 * 8)
        @Volatile var running = false
        @Volatile var stopped = 0
        var releaseSucceeds = true
        override fun start() {
            entered.countDown()
            check(gate.await(3, TimeUnit.SECONDS))
            running = true
            started.countDown()
        }
        override fun enqueue(pcm: ByteArray) {
            if (!queue.offer(pcm)) callbacks.failure("bounded playback overflow")
        }
        override fun clearPlayback() = queue.clear()
        override fun setMuted(muted: Boolean) {}
        override fun stop(): Boolean { stopped++; running = false; queue.clear(); return releaseSucceeds }
        fun capture() = callbacks.pcm(ByteArray(640))
        fun meter(value: Float) = callbacks.level(value)
        fun play(): ByteArray? {
            check(running)
            val packet = queue.poll(0) ?: return null
            val bytes = packet.pcm.copyOf()
            queue.consumed(packet, packet.pcm.size)
            queue.complete(packet)
            return bytes
        }
    }
    private class Connection(val listener: VoiceTransport.Listener) : VoiceTransport {
        override val inputSampleRate = 16000
        override val outputSampleRate = 16000
        var connected = false
        var closed = false
        var uploaded = 0
        override fun connect() { connected = true }
        override fun sendAudio(pcm: ByteArray) { uploaded += pcm.size }
        override fun close() { closed = true }
    }
    private class Fixture(delayedAudio: Boolean = false) {
        val ui = Ui()
        val events = ArrayList<JSONObject>()
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }
        lateinit var connection: Connection
        lateinit var audio: Audio
        val engine = VoiceSessionEngine("translation", { listener -> Connection(listener).also { connection = it } },
            { _, _, callback -> callback(JSONObject(), true) }, { events.add(JSONObject(it.toString())) }, ui, worker,
            { _, _, callbacks -> Audio(callbacks, delayedAudio).also { audio = it } })
        init { engine.start(); assertTrue(audio.entered.await(2, TimeUnit.SECONDS)); assertTrue(connection.connected) }
        fun await(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!predicate() && System.nanoTime() < deadline) { ui.drain(); Thread.yield() }
            ui.drain()
            assertTrue(predicate())
        }
        fun stop(): Boolean {
            var result: Boolean? = null
            engine.stop { result = it }
            audio.gate.countDown()
            await { result != null }
            return result!!
        }
        fun errors() = events.filter { it.optString("phase") == "error" }
        fun listening() = events.count { it.optString("phase") == "listening" }
    }

    @Test fun delayedDeviceAndStalledUiAcceptOneThreeSecondGreetingWithoutDelayingFirstPlayback() {
        val f = Fixture(delayedAudio = true)
        try {
            f.connection.listener.onReady()
            val greeting = ByteArray(16000 * 2 * 3) { 7 }
            f.connection.listener.onAudio(greeting)
            greeting.fill(0) // Provider ownership ends when its callback returns.
            assertEquals(96000, f.audio.queue.pendingBytes())
            assertEquals(1, f.ui.pending.size) // Ready only; no PCM retained by the main queue.
            assertEquals(0, f.listening())
            f.audio.gate.countDown()
            assertTrue(f.audio.started.await(2, TimeUnit.SECONDS))
            assertArrayEquals(ByteArray(96000) { 7 }, f.audio.play()) // Works even before UI drains.
            f.await { f.listening() == 1 }
            assertTrue(f.errors().isEmpty())
        } finally { assertTrue(f.stop()) }
    }

    @Test fun microphoneIsNotUploadedUntilProviderReadyAndListeningNeedsBothSides() {
        val f = Fixture()
        try {
            assertTrue(f.audio.started.await(2, TimeUnit.SECONDS))
            f.await { f.events.any { it.optString("state") == "recording" } }
            f.audio.capture()
            assertEquals(0, f.connection.uploaded)
            assertEquals(0, f.listening())
            f.connection.listener.onReady()
            f.audio.capture() // Readiness takes effect directly, without a main-thread scheduling gap.
            assertEquals(640, f.connection.uploaded)
            f.ui.drain()
            assertEquals(1, f.listening())
        } finally { assertTrue(f.stop()) }
    }

    @Test fun interruptionDuringStartupClearsOldBurstWithoutWaitingForUi() {
        val f = Fixture(delayedAudio = true)
        try {
            f.connection.listener.onReady()
            f.connection.listener.onAudio(ByteArray(96000) { 1 })
            f.connection.listener.onInterrupted()
            f.connection.listener.onAudio(ByteArray(640) { 2 })
            assertEquals(640, f.audio.queue.pendingBytes())
            f.audio.gate.countDown()
            assertTrue(f.audio.started.await(2, TimeUnit.SECONDS))
            assertArrayEquals(ByteArray(640) { 2 }, f.audio.play())
            assertNull(f.audio.play())
        } finally { assertTrue(f.stop()) }
    }

    @Test fun stopDuringInitializationCannotReviveOrUploadFromLateCallbacks() {
        val f = Fixture(delayedAudio = true)
        var off: Boolean? = null
        f.engine.stop { off = it }
        f.connection.listener.onReady()
        f.connection.listener.onAudio(ByteArray(96000))
        f.connection.listener.onTranscriptSegment("late", "late text", true, null, null)
        f.audio.capture()
        assertNull(off)
        assertEquals(0, f.connection.uploaded)
        assertEquals(0, f.audio.queue.pendingBytes())
        f.audio.gate.countDown()
        f.await { off != null }
        assertEquals(true, off)
        assertEquals(1, f.audio.stopped)
        assertTrue(f.connection.closed)
        assertFalse(f.audio.running)
        assertEquals(0, f.listening())
        assertTrue(f.events.none { it.optString("state") == "recording" || it.optString("turn_id") == "late" })
    }

    @Test fun finiteOverflowAndFailedCleanupRemainVisibleAndCannotLookEnded() {
        val f = Fixture()
        assertTrue(f.audio.started.await(2, TimeUnit.SECONDS))
        f.connection.listener.onReady()
        f.connection.listener.onAudio(ByteArray(16000 * 2 * 8))
        f.connection.listener.onAudio(ByteArray(2))
        f.ui.drain()
        assertEquals("bounded playback overflow", f.errors().single().optString("text"))
        f.connection.listener.onClosed()
        f.ui.drain()
        assertTrue(f.events.none { it.optString("phase") == "ended" })
        f.audio.releaseSucceeds = false
        assertFalse(f.stop())
        assertEquals(2, f.audio.stopped)
        var repeated: Boolean? = null
        f.engine.stop { repeated = it }
        assertEquals(false, repeated)
    }

    @Test fun structuredTranscriptPreservesTurnIdentityOffsetsAndEmptyFinalSeal() {
        val f = Fixture()
        try {
            f.connection.listener.onTranscriptSegment("turn-a", "Hello", false, 100, null)
            f.connection.listener.onTranscriptSegment("turn-a", "", true, 100, 300)
            f.ui.drain()
            val captions = f.events.filter { it.optString("type") == "translation_text" }
            assertEquals(2, captions.size)
            assertEquals("turn-a", captions[0].getString("turn_id"))
            assertEquals("append", captions[0].getString("operation"))
            assertEquals(100L, captions[0].getLong("start_ms"))
            assertFalse(captions[0].has("end_ms"))
            assertEquals("", captions[1].getString("text"))
            assertTrue(captions[1].getBoolean("final"))
            assertEquals(300L, captions[1].getLong("end_ms"))
        } finally { assertTrue(f.stop()) }
    }

    @Test fun reconnectDoesNotLetOldTransportFlushOrContaminateFreshAudio() {
        val f = Fixture()
        try {
            val old = f.connection
            val oldAudio = f.audio
            old.listener.onReady()
            old.listener.onAudio(ByteArray(640) { 1 })
            old.listener.onError("temporary disconnect", retryable = true)
            f.ui.drain()
            f.await { oldAudio.stopped == 1 && f.ui.delayed.any { it.first == 1000L } }
            f.ui.runDelay(1000)
            assertNotSame(old, f.connection)
            assertNotSame(oldAudio, f.audio)
            assertTrue(f.audio.started.await(2, TimeUnit.SECONDS))
            f.connection.listener.onReady()
            f.connection.listener.onAudio(ByteArray(640) { 2 })
            old.listener.onInterrupted()
            old.listener.onAudio(ByteArray(640) { 3 })
            old.listener.onTranscriptSegment("stale", "stale", true, null, null)
            f.ui.drain()
            assertArrayEquals(ByteArray(640) { 2 }, f.audio.play())
            assertNull(f.audio.play())
            assertTrue(f.events.none { it.optString("turn_id") == "stale" })
            assertTrue(old.closed)
        } finally { assertTrue(f.stop()) }
    }

    @Test fun startupDeadlineReportsFailureAndNeverAcknowledgesStoppedBeforeNativeReturns() {
        val f = Fixture(delayedAudio = true)
        f.connection.listener.onReady()
        f.ui.runDelay(10000)
        assertEquals("Android audio took too long to start. Check the audio route and retry.",
            f.errors().single().getString("text"))
        var off: Boolean? = null
        f.engine.stop { off = it }
        f.ui.drain()
        assertNull(off)
        f.audio.gate.countDown()
        f.await { off != null }
        assertEquals(true, off)
        assertEquals(0, f.listening())
        assertFalse(f.audio.running)
    }
}
