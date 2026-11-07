package com.agentdeck.hotel

import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

internal interface VoiceSessionUi {
    fun isOnThread(): Boolean
    fun post(action: () -> Unit): Boolean
    fun postDelayed(action: () -> Unit, delayMillis: Long): Boolean
}

internal interface VoiceAudioDevice {
    fun start()
    /** Accepts bounded PCM even while native initialization is in progress. */
    fun enqueue(pcm: ByteArray)
    fun clearPlayback()
    fun setMuted(muted: Boolean)
    fun stop(): Boolean
}

internal data class VoiceAudioCallbacks(val pcm: (ByteArray) -> Unit, val level: (Float) -> Unit,
    val failure: (String) -> Unit, val diagnostics: (JSONObject) -> Unit)

/** A terminal attempt cannot upload microphone frames or revive following a late ready callback. */
internal class NativeVoiceAttempt {
    @Volatile var audio: VoiceAudioDevice? = null
    @Volatile private var terminal = false
    @Volatile var failed = false
        private set
    @Volatile var ready = false
        private set
    private val energyPending = AtomicBoolean(false)
    @Volatile var level = 0f
    private var lastEnergyNanos = Long.MIN_VALUE

    fun active() = !terminal
    @Synchronized fun markReady(): Boolean {
        if (terminal) return false
        ready = true
        return true
    }
    @Synchronized fun finish(error: Boolean = false): Boolean {
        if (terminal) return false
        failed = error
        terminal = true
        ready = false
        return true
    }
    @Synchronized fun reserveEnergy(value: Float, nowNanos: Long): Boolean {
        level = value
        if (terminal || (lastEnergyNanos != Long.MIN_VALUE && nowNanos - lastEnergyNanos < 50_000_000L) ||
            !energyPending.compareAndSet(false, true)) return false
        lastEnergyNanos = nowNanos
        return true
    }
    fun releaseEnergy() { energyPending.set(false) }
}

/** Native initialization and handshake overlap; PCM never waits on the main/UI dispatcher. */
internal class VoiceSessionEngine(
    private val mode: String,
    private val factory: (VoiceTransport.Listener) -> VoiceTransport,
    private val tool: (String, JSONObject, (JSONObject, Boolean) -> Unit) -> Unit,
    private val push: (JSONObject) -> Unit,
    private val ui: VoiceSessionUi,
    private val worker: ExecutorService,
    private val audioFactory: (Int, Int, VoiceAudioCallbacks) -> VoiceAudioDevice
) {
    @Volatile private var closed = false
    @Volatile private var attempt: NativeVoiceAttempt? = null
    @Volatile private var transport: VoiceTransport? = null
    @Volatile private var audio: VoiceAudioDevice? = null
    private var retiringAudio: VoiceAudioDevice? = null
    private var audioReady = false
    private var listening = false
    private var muted = false
    private var reconnects = 0
    private var started = false
    private var stopping = false
    private var releaseResult: Boolean? = null
    private var unreleasedAudio = emptyList<VoiceAudioDevice>()
    private val stopped = ArrayList<(Boolean) -> Unit>()

    private fun emit(type: String, body: JSONObject = JSONObject()) {
        if (!closed) push(body.put("type", type))
    }
    private fun phase(value: String, text: String = "") = emit("reception_event",
        JSONObject().put("phase", value).put("text", text))
    private fun current(epoch: NativeVoiceAttempt) = !closed && attempt === epoch && epoch.active()
    private fun dispatch(epoch: NativeVoiceAttempt, action: () -> Unit) { ui.post { if (current(epoch)) action() } }
    private fun terminalError(epoch: NativeVoiceAttempt, message: String) {
        if (current(epoch) && epoch.finish(error = true)) phase("error", message)
    }
    private fun announceListening(epoch: NativeVoiceAttempt) {
        if (current(epoch) && epoch.ready && audioReady && !listening) {
            listening = true
            phase("listening")
        }
    }

    fun start() {
        check(ui.isOnThread())
        if (started || closed) return
        started = true
        connect()
    }

    private fun connect() {
        if (closed) return
        attempt?.finish()
        val epoch = NativeVoiceAttempt().also { attempt = it }
        audioReady = false
        listening = false
        phase(if (reconnects == 0) "connecting" else "reconnecting",
            if (reconnects == 0) "Connecting voice…" else "Connection lost. Reconnecting…")
        if (!current(epoch)) return
        try {
            val connection = factory(object : VoiceTransport.Listener {
                override fun onReady() {
                    if (current(epoch) && epoch.markReady()) dispatch(epoch) { announceListening(epoch) }
                }
                override fun onAudio(pcm: ByteArray) {
                    if (!current(epoch) || pcm.isEmpty()) return
                    // One native queue owns/copies the bytes; a stalled UI cannot retain PCM.
                    epoch.audio?.enqueue(pcm)
                }
                override fun onInterrupted() {
                    if (current(epoch)) epoch.audio?.clearPlayback()
                }
                override fun onTranscript(text: String, final: Boolean) {
                    if (text.isNotEmpty()) dispatch(epoch) {
                        emit(if (mode == "translation") "translation_text" else "assistant_text",
                            JSONObject().put("text", text.take(12000)).put("final", final))
                    }
                }
                override fun onTranscriptSegment(turnId: String, text: String, final: Boolean, startMs: Long?, endMs: Long?) = dispatch(epoch) {
                    emit(if (mode == "translation") "translation_text" else "assistant_text",
                        JSONObject().put("turn_id", turnId.take(256)).put("text", text.take(12000))
                            .put("final", final).put("operation", "append").apply {
                                if (startMs != null) put("start_ms", startMs)
                                if (endMs != null) put("end_ms", endMs)
                            })
                }
                override fun onDiagnostics(metrics: JSONObject) = dispatch(epoch) { emit("voice_diagnostics", metrics) }
                override fun onError(message: String, retryable: Boolean) = dispatch(epoch) {
                    if (retryable && reconnects < 3) reconnect(epoch) else terminalError(epoch, message)
                }
                override fun onClosed() = dispatch(epoch) {
                    if (!epoch.failed && epoch.finish()) phase("ended", "Conversation ended.")
                }
                override fun onToolCall(id: String, name: String, parameters: JSONObject) = dispatch(epoch) {
                    if (mode != "reception") return@dispatch
                    tool(name, parameters) { result, error -> dispatch(epoch) {
                        if (name == "search_nearby" && result.has("attribution")) emit("local_source", JSONObject()
                            .put("attribution", result.optString("attribution"))
                            .put("source_url", result.optString("attribution_url", "https://www.openstreetmap.org/copyright")))
                        transport?.completeToolCall(id, result, error)
                    } }
                }
            })
            transport = connection
            if (!current(epoch)) { runCatching { connection.close() }; return }
            val device = audioFactory(connection.inputSampleRate, connection.outputSampleRate, VoiceAudioCallbacks(
                pcm = { bytes -> if (current(epoch) && epoch.ready) connection.sendAudio(bytes) },
                level = { value ->
                    if (current(epoch) && epoch.reserveEnergy(value, System.nanoTime())) {
                        if (!ui.post {
                            epoch.releaseEnergy()
                            if (current(epoch)) emit("energy", JSONObject().put("level", epoch.level.toDouble()))
                        }) epoch.releaseEnergy()
                    }
                },
                failure = { message -> dispatch(epoch) { terminalError(epoch, message) } },
                diagnostics = { metrics -> dispatch(epoch) { emit("voice_diagnostics", metrics) } }))
            device.setMuted(muted)
            epoch.audio = device
            audio = device
            // Explicit Start and microphone permission have already been authorized by the Activity.
            // Capture can initialize during handshake; frames are discarded until provider readiness.
            worker.execute {
                val prepared = runCatching { if (current(epoch)) device.start() else return@execute }.isSuccess
                ui.post {
                    if (!current(epoch)) return@post
                    if (!prepared) {
                        terminalError(epoch, "Microphone could not start. Check microphone access and retry.")
                        return@post
                    }
                    audioReady = true
                    emit("audio_state", JSONObject().put("state", if (muted) "muted" else "recording"))
                    announceListening(epoch)
                }
            }
            ui.postDelayed({ if (current(epoch) && !audioReady)
                terminalError(epoch, "Android audio took too long to start. Check the audio route and retry.") }, 10_000)
            if (current(epoch)) connection.connect()
        } catch (_: Exception) { terminalError(epoch, "Voice connection could not start. Check setup and retry.") }
    }

    private fun reconnect(epoch: NativeVoiceAttempt) {
        if (!current(epoch)) return
        epoch.finish(error = true)
        val reconnectEpoch = NativeVoiceAttempt().also { attempt = it }
        reconnects++
        val oldTransport = transport.also { transport = null }
        val oldAudio = audio.also { audio = null }
        retiringAudio = oldAudio
        audioReady = false
        phase("reconnecting", "Connection lost. Reconnecting…")
        emit("audio_state", JSONObject().put("state", "stopping"))
        worker.execute {
            runCatching { oldTransport?.close() }
            val off = runCatching { oldAudio?.stop() ?: true }.getOrDefault(false)
            ui.post {
                if (off && retiringAudio === oldAudio) retiringAudio = null
                if (!current(reconnectEpoch)) return@post
                if (!off) { terminalError(reconnectEpoch, "Audio cleanup could not be confirmed. Reopen the app before restarting."); return@post }
                emit("audio_state", JSONObject().put("state", "off"))
                ui.postDelayed({ if (current(reconnectEpoch)) connect() }, (1000L shl (reconnects - 1)))
            }
        }
    }

    fun setMuted(value: Boolean) {
        check(ui.isOnThread())
        if (!closed && attempt?.active() != false) {
            muted = value
            audio?.setMuted(value)
            if (audioReady) emit("audio_state", JSONObject().put("state", if (value) "muted" else "recording"))
        }
    }

    fun stop(done: (Boolean) -> Unit) {
        check(ui.isOnThread())
        releaseResult?.let { done(it); return }
        stopped.add(done)
        if (stopping) return
        stopping = true
        closed = true
        attempt?.finish()
        audioReady = false
        val oldTransport = transport.also { transport = null }
        val oldAudio = listOfNotNull(audio, retiringAudio).distinct()
        audio = null
        retiringAudio = null
        worker.execute {
            runCatching { oldTransport?.close() }
            var off = oldAudio.map { runCatching { it.stop() }.getOrDefault(false) }.all { it }
            if (!off) off = oldAudio.map { runCatching { it.stop() }.getOrDefault(false) }.all { it }
            ui.post {
                releaseResult = off
                unreleasedAudio = if (off) emptyList() else oldAudio
                val callbacks = stopped.toList()
                stopped.clear()
                worker.shutdown()
                callbacks.forEach { callback -> runCatching { callback(off) } }
            }
        }
    }
}
