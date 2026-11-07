package com.agentdeck.hotel

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Direct managed-agent voice session. No audio, provider credentials or tool calls enter JavaScript. */
internal class CartesiaNightTransport(
    private val key: String,
    private val agentId: String,
    hotelContext: String,
    private val listener: VoiceTransport.Listener,
    client: OkHttpClient = defaultClient,
    private val socketFactory: WebSocket.Factory = client,
    private val readyTimeoutMillis: Long = 20_000,
    private val maxQueueBytes: Long = 256_000
) : VoiceTransport {
    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build()
        private val deadlines = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cartesia-ready-deadline").apply { isDaemon = true }
        }
        private val agentPattern = Regex("agent_[A-Za-z0-9_-]{1,128}")
    }

    override val inputSampleRate = CartesiaNightProtocol.SAMPLE_RATE
    // Managed Agents output uses the selected input_format rate (official agents-web client).
    override val outputSampleRate = CartesiaNightProtocol.SAMPLE_RATE
    private val lock = Any()
    private val protocol = CartesiaNightProtocol(hotelContext)
    private var socket: WebSocket? = null
    private var started = false
    private var closed = false
    private var ready = false
    private var readyDeadline: ScheduledFuture<*>? = null

    override fun connect() = synchronized(lock) {
        if (started || closed) return@synchronized
        started = true
        if (key.isBlank() || key.length > 512 || key.any { it.code !in 33..126 } || !agentPattern.matches(agentId)) {
            fail("The Cartesia night receptionist is not configured in this app build.", false)
            return@synchronized
        }
        val request = Request.Builder()
            .url("wss://api.cartesia.ai/v1/agents/websocket/$agentId?cartesia_version=${CartesiaNightProtocol.API_VERSION}")
            .header("X-API-Key", key).build()
        readyDeadline = deadlines.schedule({ synchronized(lock) {
            if (!closed && !ready) fail("Cartesia did not become ready. Check your connection and try again.", true)
        } }, readyTimeoutMillis, TimeUnit.MILLISECONDS)
        val callbacks = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
                if (closed) { webSocket.close(1000, "session ended"); return@synchronized }
                // Declare continuous microphone input and keep it flowing while the agent speaks.
                if (!webSocket.send(protocol.sessionCreate())) fail("Could not start the Cartesia session.", true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
                if (closed) return@synchronized
                for (event in protocol.receive(text)) {
                    if (closed) break
                    when (event) {
                        CartesiaNightProtocol.Event.Ready -> {
                            ready = true
                            readyDeadline?.cancel(false)
                            readyDeadline = null
                            listener.onReady()
                        }
                        is CartesiaNightProtocol.Event.Audio -> listener.onAudio(event.pcm)
                        CartesiaNightProtocol.Event.Interrupted -> listener.onInterrupted()
                        is CartesiaNightProtocol.Event.Transcript -> listener.onTranscript(event.text, event.final)
                        is CartesiaNightProtocol.Event.ToolCall -> listener.onToolCall(event.id, event.name, event.parameters)
                        is CartesiaNightProtocol.Event.Send -> send(event.frame)
                        is CartesiaNightProtocol.Event.Error -> {
                            if (event.fatal) fail(event.message, event.retryable)
                            else listener.onError(event.message, event.retryable)
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, "session ended")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
                finish()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
                if (closed) return@synchronized
                val status = response?.code
                val configuration = status == 401 || status == 403 || status == 404
                fail(if (configuration) "Cartesia could not authorize this night receptionist. Check the app configuration."
                    else "Cartesia connection lost. Please try again.", !configuration)
            }
        }
        try {
            val created = socketFactory.newWebSocket(request, callbacks)
            if (closed) created.cancel() else socket = created
        } catch (_: RuntimeException) {
            fail("Cartesia connection could not start. Please try again.", true)
        }
    }

    override fun sendAudio(pcm: ByteArray) {
        synchronized(lock) { protocol.audioInput(pcm)?.let { send(it) } }
    }

    override fun completeToolCall(id: String, result: JSONObject, isError: Boolean) {
        synchronized(lock) { protocol.toolResult(id, result, isError)?.let { send(it) } }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return@synchronized
            val ending = socket
            finish()
            ending?.close(1000, "session ended")
        }
    }

    private fun send(frame: String) {
        if (closed) return
        val current = socket ?: return
        // Bound queued microphone data when a mobile connection stops draining.
        if (current.queueSize() > maxQueueBytes || !current.send(frame)) {
            fail("Cartesia connection is too slow. Please reconnect.", true)
        }
    }

    private fun fail(message: String, retryable: Boolean) {
        if (closed) return
        val ending = socket
        listener.onError(message, retryable)
        finish()
        ending?.cancel()
    }

    private fun finish() {
        if (closed) return
        closed = true
        ready = false
        readyDeadline?.cancel(false)
        readyDeadline = null
        protocol.close()
        socket = null
        listener.onClosed()
    }
}
