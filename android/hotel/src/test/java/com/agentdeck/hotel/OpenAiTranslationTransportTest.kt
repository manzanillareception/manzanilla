package com.agentdeck.hotel

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenAiTranslationTransportTest {
    private class RecordingListener : VoiceTransport.Listener {
        val events = Collections.synchronizedList(ArrayList<String>())
        val text = Collections.synchronizedList(ArrayList<String>())
        val audio = Collections.synchronizedList(ArrayList<ByteArray>())
        val errors = Collections.synchronizedList(ArrayList<Pair<String, Boolean>>())
        val failed = CountDownLatch(1)
        override fun onReady() { events.add("ready") }
        override fun onAudio(pcm: ByteArray) { audio.add(pcm.copyOf()) }
        override fun onTranscript(text: String, final: Boolean) { this.text.add(text) }
        override fun onInterrupted() { events.add("interrupted") }
        override fun onClosed() { events.add("closed") }
        override fun onError(message: String, retryable: Boolean) {
            errors.add(message to retryable)
            events.add("error")
            failed.countDown()
        }
    }

    private class FakeSocket(private val originalRequest: Request) : WebSocket {
        val sent = Collections.synchronizedList(ArrayList<String>())
        var queued = 0L
        var accepts = true
        var cancelled = 0
        override fun request() = originalRequest
        override fun queueSize() = queued
        override fun send(text: String): Boolean { if (accepts) sent.add(text); return accepts }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled++ }
    }

    private class FakeFactory : WebSocket.Factory {
        lateinit var listener: WebSocketListener
        lateinit var socket: FakeSocket
        var connections = 0
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            connections++
            this.listener = listener
            return FakeSocket(request).also { socket = it }
        }
        fun response(code: Int = 101) = Response.Builder().request(socket.request())
            .protocol(Protocol.HTTP_1_1).code(code).message("fixture").build()
        fun open() { listener.onOpen(socket, response()) }
        fun receive(raw: String) { listener.onMessage(socket, raw) }
        fun ready() { open(); receive("""{"type":"session.started"}""") }
    }

    private class Fixture(key: String = "synthetic-fixture-key", timeout: Long = 25_000) : Closeable {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiTranslationTransport(key, "French", "Japanese", listener,
            OkHttpClient(), factory, timeout, 100)
        override fun close() {
            transport.close()
            if (factory.connections > 0) factory.receive("""{"type":"session.closed"}""")
        }
    }

    @Test fun authenticateOnlyToOpenAiAndWaitForSessionReadyBeforeSendingMicrophone() {
        Fixture().use { f ->
            f.transport.connect()
            f.transport.connect()
            assertEquals(1, f.factory.connections)
            val request = f.factory.socket.request()
            assertEquals("api.openai.com", request.url.host)
            assertEquals("/v1/live/sessions", request.url.encodedPath)
            assertEquals("Bearer synthetic-fixture-key", request.header("Authorization"))
            assertFalse(request.url.toString().contains("fixture-key"))
            f.transport.sendAudio(byteArrayOf(1, 0))
            assertTrue(f.factory.socket.sent.isEmpty())
            f.factory.open()
            assertEquals("session.start", JSONObject(f.factory.socket.sent.single()).getString("type"))
            f.transport.sendAudio(byteArrayOf(1, 0))
            assertEquals(1, f.factory.socket.sent.size)
            f.factory.receive("""{"type":"session.started"}""")
            f.factory.receive("""{"type":"session.started"}""")
            assertEquals(listOf("ready"), f.listener.events)
            f.transport.sendAudio(byteArrayOf(1, 0))
            assertEquals("session.input_audio.append", JSONObject(f.factory.socket.sent.last()).getString("type"))
        }
    }

    @Test fun receiveOnlyTranslatedOutputWhileReadyAndNeverInventSpeakerEvents() {
        Fixture().use { f ->
            f.transport.connect()
            f.factory.receive("""{"type":"session.output_audio.delta","delta":"AAA="}""")
            assertTrue(f.listener.audio.isEmpty())
            f.factory.ready()
            f.factory.receive("""{"type":"session.input_transcript.delta","delta":"original"}""")
            f.factory.receive("""{"type":"session.output_transcript.delta","delta":"訳"}""")
            f.factory.receive("""{"type":"session.output_transcript.delta","delta":" 文"}""")
            f.factory.receive("""{"type":"session.output_audio.delta","delta":"AQA="}""")
            assertEquals(listOf("訳", " 文"), f.listener.text)
            assertArrayEquals(byteArrayOf(1, 0), f.listener.audio.single())
            assertEquals(listOf("ready"), f.listener.events)
        }
    }

    @Test fun closeBeforeSocketOpenFencesEveryLateCallbackAndCannotRestart() {
        Fixture().use { f ->
            f.transport.connect()
            f.transport.close()
            f.factory.open()
            f.factory.receive("""{"type":"session.started"}""")
            f.factory.receive("""{"type":"session.output_transcript.delta","delta":"stale"}""")
            f.factory.listener.onFailure(f.factory.socket, IOException("private details"), f.factory.response(401))
            f.transport.connect()
            assertEquals(1, f.factory.connections)
            assertTrue(f.factory.socket.sent.isEmpty())
            assertTrue(f.listener.text.isEmpty())
            assertTrue(f.listener.errors.isEmpty())
            assertEquals(listOf("closed"), f.listener.events)
            assertTrue(f.factory.socket.cancelled > 0)
        }
    }

    @Test fun stoppingKeepsCloseHandshakeButSuppressesLatePlaybackAndTranscripts() {
        Fixture().use { f ->
            f.transport.connect()
            f.factory.ready()
            f.transport.close()
            assertEquals("session.close", JSONObject(f.factory.socket.sent.last()).getString("type"))
            f.factory.receive("""{"type":"session.output_audio.delta","delta":"AQA="}""")
            f.factory.receive("""{"type":"session.output_transcript.delta","delta":"stale"}""")
            f.factory.receive("""{"type":"session.closed"}""")
            f.factory.listener.onClosed(f.factory.socket, 1000, "closed")
            assertTrue(f.listener.audio.isEmpty())
            assertTrue(f.listener.text.isEmpty())
            assertEquals(listOf("ready", "closed"), f.listener.events)
        }
    }

    @Test fun rejectedKeyHasOneSanitizedTerminalErrorAndNoAutomaticRetry() {
        Fixture().use { f ->
            f.transport.connect()
            f.factory.listener.onFailure(f.factory.socket, IOException("synthetic-fixture-key"), f.factory.response(401))
            f.factory.ready()
            f.factory.listener.onClosed(f.factory.socket, 1008, "synthetic-fixture-key")
            assertEquals(1, f.listener.errors.size)
            assertFalse(f.listener.errors.single().second)
            assertFalse(f.listener.errors.single().first.contains("synthetic-fixture-key"))
            assertEquals(listOf("error"), f.listener.events)
            assertEquals(1, f.factory.connections)
        }
    }

    @Test fun hungStartupIsBoundedAndClosesItsOwnSocket() {
        Fixture(timeout = 40).use { f ->
            f.transport.connect()
            assertTrue(f.listener.failed.await(2, TimeUnit.SECONDS))
            assertEquals(1, f.listener.errors.size)
            assertTrue(f.listener.errors.single().second)
            assertTrue(f.factory.socket.cancelled > 0)
            f.factory.ready()
            assertEquals(listOf("error"), f.listener.events)
        }
    }

    @Test fun stalledUploadFailsRatherThanAccumulatingUnboundedAudio() {
        Fixture().use { f ->
            f.transport.connect()
            f.factory.ready()
            f.factory.socket.queued = 128_001
            f.transport.sendAudio(byteArrayOf(1, 0))
            assertEquals(1, f.factory.socket.sent.size)
            assertTrue(f.listener.errors.single().second)
            f.transport.sendAudio(byteArrayOf(1, 0))
            assertEquals(1, f.listener.errors.size)
        }
    }

    @Test fun emptyCredentialsFailLocallyWithoutProviderAccess() {
        Fixture(key = " ").use { f ->
            f.transport.connect()
            assertEquals(0, f.factory.connections)
            assertEquals(1, f.listener.errors.size)
            assertFalse(f.listener.errors.single().second)
        }
    }
}
