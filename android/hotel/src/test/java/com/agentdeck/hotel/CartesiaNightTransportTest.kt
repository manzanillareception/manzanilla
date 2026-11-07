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
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CartesiaNightTransportTest {
    private class Listener : VoiceTransport.Listener {
        val events = ArrayList<String>()
        val errors = ArrayList<Pair<String, Boolean>>()
        val calls = ArrayList<String>()
        val audio = ArrayList<ByteArray>()
        val failed = CountDownLatch(1)
        override fun onReady() { events.add("ready") }
        override fun onAudio(pcm: ByteArray) { audio.add(pcm.copyOf()); events.add("audio") }
        override fun onTranscript(text: String, final: Boolean) { events.add("text") }
        override fun onInterrupted() { events.add("interrupted") }
        override fun onClosed() { events.add("closed") }
        override fun onError(message: String, retryable: Boolean) {
            errors.add(message to retryable); events.add("error"); failed.countDown()
        }
        override fun onToolCall(id: String, name: String, parameters: JSONObject) { calls.add(id) }
    }

    private class Socket(private val original: Request) : WebSocket {
        val frames = ArrayList<String>()
        var queued = 0L
        var cancelled = false
        var closed = false
        var accepts = true
        override fun request() = original
        override fun queueSize() = queued
        override fun send(text: String): Boolean { if (accepts) frames.add(text); return accepts }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { cancelled = true }
    }

    private class Factory : WebSocket.Factory {
        lateinit var socket: Socket
        lateinit var listener: WebSocketListener
        var connections = 0
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            connections++; this.listener = listener
            return Socket(request).also { socket = it }
        }
        fun response(code: Int = 101) = Response.Builder().request(socket.request())
            .protocol(Protocol.HTTP_1_1).code(code).message("fixture").build()
        fun open() = listener.onOpen(socket, response())
        fun receive(frame: String) = listener.onMessage(socket, frame)
        fun ready() { open(); receive("""{"type":"session_ready"}""") }
    }

    private class Fixture(timeout: Long = 20_000, key: String = "synthetic-fixture-key") : Closeable {
        val factory = Factory()
        val listener = Listener()
        val transport = CartesiaNightTransport(key, "agent_fixture", "Synthetic hotel context", listener,
            OkHttpClient(), factory, timeout, 100)
        override fun close() = transport.close()
    }

    @Test fun `key goes only in Cartesia header and ready gates microphone`() {
        Fixture().use { f ->
            f.transport.connect(); f.transport.connect()
            assertEquals(1, f.factory.connections)
            val request = f.factory.socket.request()
            assertEquals("api.cartesia.ai", request.url.host)
            assertEquals("/v1/agents/websocket/agent_fixture", request.url.encodedPath)
            assertEquals("2026-08-14", request.url.queryParameter("cartesia_version"))
            assertEquals("synthetic-fixture-key", request.header("X-API-Key"))
            assertFalse(request.url.toString().contains("fixture-key"))
            f.transport.sendAudio(byteArrayOf(1, 2))
            assertTrue(f.factory.socket.frames.isEmpty())
            f.factory.open()
            val initial = JSONObject(f.factory.socket.frames.single())
            assertEquals("session_create", initial.getString("type"))
            assertTrue(initial.getJSONObject("audio").getBoolean("continuous_input"))
            assertEquals("speaking_pace", initial.getJSONObject("audio").getString("output_delivery"))
            f.transport.sendAudio(byteArrayOf(1, 2))
            assertEquals(1, f.factory.socket.frames.size)
            f.factory.receive("""{"type":"session_ready"}""")
            f.factory.receive("""{"type":"session_ready"}""")
            f.transport.sendAudio(byteArrayOf(1, 2))
            assertEquals(listOf("ready"), f.listener.events)
            assertEquals("audio_input", JSONObject(f.factory.socket.frames.last()).getString("type"))
            assertEquals(16000, f.transport.outputSampleRate)
        }
    }

    @Test fun `stopping before socket opens fences every late callback`() {
        Fixture().use { f ->
            f.transport.connect(); f.transport.close(); f.factory.ready()
            f.factory.receive("""{"type":"audio_output","audio":"AQA="}""")
            f.factory.receive("""{"type":"client_tool_call","tool_call_id":"late","tool_name":"confirm_taxi","parameters":{},"expects_response":true}""")
            f.factory.listener.onFailure(f.factory.socket, IOException("never-show-fixture-key"), null)
            f.transport.connect()
            assertEquals(1, f.factory.connections)
            assertTrue(f.factory.socket.frames.isEmpty())
            assertEquals(listOf("closed"), f.listener.events)
            assertTrue(f.listener.calls.isEmpty())
            assertTrue(f.listener.errors.isEmpty())
        }
    }

    @Test fun `only requested tool ID can be completed and duplicate tool does not execute again`() {
        Fixture().use { f ->
            f.transport.connect(); f.factory.ready()
            val call = """{"type":"client_tool_call","tool_call_id":"location-1","tool_name":"get_location","parameters":{},"expects_response":true}"""
            f.factory.receive(call); f.factory.receive(call)
            assertEquals(listOf("location-1"), f.listener.calls)
            f.transport.completeToolCall("other-id", JSONObject().put("latitude", 12), false)
            assertEquals(1, f.factory.socket.frames.size)
            f.transport.completeToolCall("location-1", JSONObject().put("available", false), false)
            assertEquals("client_tool_result", JSONObject(f.factory.socket.frames.last()).getString("type"))
            f.factory.receive(call)
            assertEquals(3, f.factory.socket.frames.size)
            assertEquals(listOf("location-1"), f.listener.calls)
        }
    }

    @Test fun `immediate multi-second greeting is preserved and does not pause microphone input`() {
        Fixture().use { f ->
            f.transport.connect()
            f.factory.open()
            f.factory.receive("""{"type":"session_ready","audio":{"input_format":"pcm_16000","output_delivery":"speaking_pace"}}""")
            val greeting = ByteArray(16000 * 2 * 3) { (it % 251).toByte() }
            f.factory.receive(JSONObject().put("type", "audio_output")
                .put("audio", Base64.getEncoder().encodeToString(greeting)).toString())
            assertArrayEquals(greeting, f.listener.audio.single())
            f.transport.sendAudio(ByteArray(640))
            assertEquals("audio_input", JSONObject(f.factory.socket.frames.last()).getString("type"))
            f.factory.receive("""{"type":"audio_output_clear"}""")
            f.factory.receive("""{"type":"audio_output","audio":"BwA="}""")
            assertEquals(listOf("ready", "audio", "interrupted", "audio"), f.listener.events)
            assertArrayEquals(byteArrayOf(7, 0), f.listener.audio.last())
            assertTrue(f.listener.errors.isEmpty())
        }
    }

    @Test fun `provider failures are sanitized and authentication does not retry`() {
        Fixture().use { f ->
            f.transport.connect()
            f.factory.listener.onFailure(f.factory.socket, IOException("Authorization synthetic-fixture-key"), f.factory.response(401))
            assertEquals(listOf("error", "closed"), f.listener.events)
            assertEquals(false, f.listener.errors.single().second)
            assertFalse(f.listener.errors.single().first.contains("fixture-key"))
            assertTrue(f.factory.socket.cancelled)
        }
    }

    @Test fun `backpressure closes stalled socket instead of queuing unbounded microphone audio`() {
        Fixture().use { f ->
            f.transport.connect(); f.factory.ready()
            f.factory.socket.queued = 101
            f.transport.sendAudio(byteArrayOf(1, 2))
            assertEquals(listOf("ready", "error", "closed"), f.listener.events)
            assertTrue(f.listener.errors.single().second)
            assertTrue(f.factory.socket.cancelled)
        }
    }

    @Test fun `readiness timeout releases a connected but unready websocket`() {
        Fixture(timeout = 25).use { f ->
            f.transport.connect(); f.factory.open()
            assertTrue(f.listener.failed.await(2, TimeUnit.SECONDS))
            // close acquires the transport lock after all timeout callbacks have completed.
            f.transport.close()
            assertEquals(listOf("error", "closed"), f.listener.events)
            assertTrue(f.factory.socket.cancelled)
        }
    }

    @Test fun `invalid key never reaches a request header`() {
        Fixture(key = "bad\nkey").use { f ->
            f.transport.connect()
            assertEquals(0, f.factory.connections)
            assertEquals(false, f.listener.errors.single().second)
            assertEquals(listOf("error", "closed"), f.listener.events)
        }
    }
}
