package com.agentdeck.hotel

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Single-use OpenAI translator; the owner creates a fresh instance for reconnect. */
class OpenAiTranslationTransport(
    key: String,
    private val languageA: String,
    private val languageB: String,
    private val listener: VoiceTransport.Listener,
    client: OkHttpClient = DEFAULT_CLIENT,
    private val socketFactory: WebSocket.Factory = client,
    private val startupTimeoutMs: Long = 25_000,
    private val closeTimeoutMs: Long = 3_000,
    private val nanoTime: () -> Long = System::nanoTime
) : VoiceTransport {
    companion object {
        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        // OkHttp counts encoded WebSocket bytes. This conservatively caps queued
        // microphone audio at 300ms rather than allowing several seconds of lag.
        private const val MAX_UPLOAD_QUEUE_BYTES = 16_000 * 2L * 4 / 3 * 300 / 1000
    }

    private enum class State { NEW, CONNECTING, STARTING, READY, CLOSING, CLOSED }
    private val lock = Any()
    private var state = State.NEW
    private var generation = 0L
    private var credential = key.trim()
    private var socket: WebSocket? = null
    private var timer: ScheduledExecutorService? = null
    private var startupDeadline: ScheduledFuture<*>? = null
    private var closeDeadline: ScheduledFuture<*>? = null
    private val transcript = OpenAiTranscriptSegments()
    private var connectedAt = 0L
    private var readyMs: Long? = null
    private var firstInputAt: Long? = null
    private var firstOutputMs: Long? = null
    private var maximumUploadBytes = 0L
    private var maximumDispatchMs = 0L

    override val inputSampleRate = OpenAiTranslationProtocol.SAMPLE_RATE
    override val outputSampleRate = OpenAiTranslationProtocol.SAMPLE_RATE

    override fun connect() {
        val epoch: Long
        val key: String
        synchronized(lock) {
            if (state != State.NEW) return
            state = State.CONNECTING
            epoch = ++generation
            key = credential
            connectedAt = nanoTime()
        }
        if (key.isBlank() || key.any(Char::isWhitespace)) {
            fail(epoch, "Enter your OpenAI API key in Translation setup.", false)
            return
        }
        val start = runCatching { OpenAiTranslationProtocol.start(languageA, languageB).toString() }
            .getOrElse {
                fail(epoch, "Choose two different translation languages.", false)
                return
            }
        val request = runCatching { Request.Builder().url(OpenAiTranslationProtocol.ENDPOINT)
            .header("Authorization", "Bearer $key").build() }.getOrElse {
            fail(epoch, "The saved OpenAI key is invalid. Replace it in Translation setup.", false)
            return
        }
        synchronized(lock) {
            if (generation != epoch || state != State.CONNECTING) return
            timer = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "OpenAI-translation-timeout").apply { isDaemon = true }
            }
            startupDeadline = timer!!.schedule({
                fail(epoch, "The OpenAI connection took too long. Check your network and retry.", true,
                    startupOnly = true)
            }, startupTimeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        }
        try {
            val created = socketFactory.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val sent = synchronized(lock) {
                        if (generation != epoch || state != State.CONNECTING) null
                        else {
                            socket = webSocket
                            state = State.STARTING
                            // Serialize the first write with close() so cancelled startup
                            // cannot create a session after its ownership is released.
                            webSocket.send(start)
                        }
                    }
                    if (sent == null) { webSocket.cancel(); return }
                    if (!sent) fail(epoch, "The OpenAI connection closed before translation started.", true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val receivedAt = nanoTime()
                    receive(epoch, OpenAiTranslationProtocol.receive(text))
                    synchronized(lock) {
                        if (generation == epoch && state == State.READY)
                            maximumDispatchMs = maxOf(maximumDispatchMs, elapsedMs(receivedAt))
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    fail(epoch, "OpenAI returned an unsupported voice response. Restart translation.", false)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (isClosing(epoch)) finishClose(epoch)
                    else OpenAiTranslationProtocol.httpError(response?.code).let { fail(epoch, it.message, it.retryable) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    if (isClosing(epoch)) finishClose(epoch)
                    else fail(epoch, "The OpenAI voice session closed. Retry translation.", true)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (isClosing(epoch)) finishClose(epoch)
                    else fail(epoch, "The OpenAI voice session closed. Retry translation.", true)
                }
            })
            val keep = synchronized(lock) {
                if (generation != epoch || state == State.CLOSED) false
                else { if (socket == null) socket = created; true }
            }
            if (!keep) created.cancel()
        } catch (_: Exception) {
            fail(epoch, "The OpenAI voice connection could not open. Check your network.", true)
        }
    }

    private fun receive(epoch: Long, event: OpenAiTranslationProtocol.Event) {
        val active = synchronized(lock) { generation == epoch && state != State.CLOSED }
        if (!active) return
        if (event === OpenAiTranslationProtocol.Event.Closed) {
            if (isClosing(epoch)) finishClose(epoch)
            else fail(epoch, "The OpenAI voice session ended. Retry translation.", true)
            return
        }
        if (isClosing(epoch)) return
        when (event) {
            OpenAiTranslationProtocol.Event.Started -> {
                val ready = synchronized(lock) {
                    if (generation != epoch || state != State.STARTING) false
                    else {
                        state = State.READY
                        readyMs = elapsedMs(connectedAt)
                        startupDeadline?.cancel(false)
                        true
                    }
                }
                if (ready) { listener.onReady(); diagnostics(epoch) }
            }
            is OpenAiTranslationProtocol.Event.Audio -> if (isReady(epoch)) {
                val first = synchronized(lock) {
                    if (firstOutputMs == null && firstInputAt != null) {
                        firstOutputMs = elapsedMs(firstInputAt!!)
                        true
                    } else false
                }
                listener.onAudio(event.pcm)
                if (first) diagnostics(epoch)
            }
            is OpenAiTranslationProtocol.Event.Transcript -> {
                val parts = synchronized(lock) {
                    if (generation == epoch && state == State.READY) transcript.append(event) else emptyList()
                }
                parts.forEach { if (isReady(epoch)) deliver(it) }
            }
            is OpenAiTranslationProtocol.Event.Failure -> fail(epoch, event.message, event.retryable)
            else -> Unit
        }
    }

    override fun sendAudio(pcm: ByteArray) {
        val epoch: Long
        val target: WebSocket
        synchronized(lock) {
            if (state != State.READY) return
            epoch = generation
            target = socket ?: return
        }
        if (pcm.isEmpty()) return
        val event = runCatching { OpenAiTranslationProtocol.audio(pcm) }.getOrElse {
            fail(epoch, "Microphone audio has an invalid format. Restart translation.", false)
            return
        }
        val accepted = synchronized(lock) {
            if (generation != epoch || state != State.READY || socket !== target) null
            else {
                val queued = target.queueSize()
                maximumUploadBytes = maxOf(maximumUploadBytes, queued)
                if (queued + event.length > MAX_UPLOAD_QUEUE_BYTES) false
                else target.send(event).also { sent -> if (sent && firstInputAt == null) firstInputAt = nanoTime() }
            }
        }
        if (accepted == false) {
            fail(epoch, "Audio upload fell behind. Check your network and retry translation.", true)
        }
    }

    override fun close() {
        val epoch: Long
        val target: WebSocket?
        val graceful: Boolean
        val final: OpenAiTranscriptSegments.Segment?
        synchronized(lock) {
            if (state == State.CLOSED || state == State.CLOSING) return
            epoch = generation
            graceful = state == State.READY
            state = State.CLOSING
            startupDeadline?.cancel(false)
            target = socket
            final = transcript.finish()
        }
        final?.let(::deliver)
        if (!graceful || target == null) { finishClose(epoch); return }
        if (!target.send("{\"type\":\"session.close\"}")) { finishClose(epoch); return }
        synchronized(lock) {
            if (generation != epoch || state != State.CLOSING) return
            closeDeadline = timer?.schedule({ finishClose(epoch) },
                closeTimeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        }
    }

    private fun isReady(epoch: Long) = synchronized(lock) { generation == epoch && state == State.READY }
    private fun isClosing(epoch: Long) = synchronized(lock) { generation == epoch && state == State.CLOSING }

    private fun deliver(part: OpenAiTranscriptSegments.Segment) = listener.onTranscriptSegment(
        part.turnId, part.text, part.final, part.startMs, part.endMs)

    private fun elapsedMs(from: Long) = ((nanoTime() - from).coerceAtLeast(0) / 1_000_000)

    private fun diagnostics(epoch: Long) {
        val metrics = synchronized(lock) {
            if (generation != epoch || state != State.READY) return
            JSONObject().put("provider", "OpenAI").put("model", OpenAiTranslationProtocol.MODEL)
                .put("host", "api.openai.com").put("transport", "websocket")
                .put("connection_ready_ms", readyMs)
                .put("max_upload_queue_audio_ms", maximumUploadBytes * 3 / 4 * 1000 / (inputSampleRate * 2))
                .put("max_local_receive_dispatch_ms", maximumDispatchMs)
                .apply {
                    firstOutputMs?.let { put("stream_start_to_first_output_event_ms", it) }
                    // Output events can contain silence. This is a startup transport
                    // observation, not speech-to-translation or inference latency.
                    put("output_event_may_be_silent", true)
                    put("startup_timing_scope", "input_stream_start_to_first_output_event")
                }
        }
        listener.onDiagnostics(metrics)
    }

    private fun finishClose(epoch: Long) {
        val target: WebSocket?
        synchronized(lock) {
            if (generation != epoch || state != State.CLOSING) return
            state = State.CLOSED
            generation++
            credential = ""
            target = socket.also { socket = null }
            shutdownTimer()
        }
        target?.cancel()
        listener.onClosed()
    }

    private fun fail(epoch: Long, message: String, retryable: Boolean, startupOnly: Boolean = false) {
        val target: WebSocket?
        val final: OpenAiTranscriptSegments.Segment?
        synchronized(lock) {
            if (generation != epoch || state == State.CLOSED || state == State.CLOSING) return
            if (startupOnly && state != State.CONNECTING && state != State.STARTING) return
            state = State.CLOSED
            generation++
            credential = ""
            target = socket.also { socket = null }
            final = transcript.finish()
            shutdownTimer()
        }
        target?.cancel()
        final?.let(::deliver)
        listener.onError(message, retryable)
    }

    /** Only called with lock held. Never shuts down an injected/shared OkHttp client. */
    private fun shutdownTimer() {
        startupDeadline?.cancel(false)
        closeDeadline?.cancel(false)
        startupDeadline = null
        closeDeadline = null
        timer?.shutdownNow()
        timer = null
    }
}
