package com.agentdeck.hotel

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.Executors

/** Callback ownership and the bytes retained by pending main-thread audio callbacks. */
internal class NativeVoiceAttempt {
    enum class Reservation { ACCEPTED, OVERFLOW, DROPPED }
    @Volatile private var terminal = false
    @Volatile var failed = false
        private set
    private var pendingBytes = 0
    private var overflow = false

    fun active() = !terminal

    @Synchronized fun finish(error: Boolean = false): Boolean {
        if (terminal) return false
        failed = error
        terminal = true
        return true
    }

    @Synchronized fun reservePcm(bytes: Int, limit: Int): Reservation {
        if (terminal || overflow) return Reservation.DROPPED
        if (bytes <= 0 || bytes % 2 != 0 || bytes > limit || pendingBytes > limit - bytes) {
            overflow = true
            return Reservation.OVERFLOW
        }
        pendingBytes += bytes
        return Reservation.ACCEPTED
    }

    @Synchronized fun releasePcm(bytes: Int) {
        pendingBytes = (pendingBytes - bytes).coerceAtLeast(0)
    }
}

/** Owns one mic/speaker pair; every transport callback is fenced to its connection attempt. */
internal class NativeVoiceSession(
    private val context: Context,
    private val mode: String,
    private val factory: (VoiceTransport.Listener) -> VoiceTransport,
    private val tools: NightTools,
    private val handset: Boolean = false,
    private val push: (JSONObject) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val cleanup = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ManzanillaVoiceCleanup").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var attempt: NativeVoiceAttempt? = null
    @Volatile private var transport: VoiceTransport? = null
    private var audio: DuplexAudio? = null
    private var retiringAudio: DuplexAudio? = null
    private var audioReady = false
    private var muted = false
    private var reconnects = 0
    private var started = false
    private var stopping = false
    private var releaseResult: Boolean? = null
    private var unreleasedAudio = emptyList<DuplexAudio>()
    private val stopped = ArrayList<(Boolean) -> Unit>()
    private val earlyAudio = ArrayDeque<ByteArray>()
    private var earlyBytes = 0

    private fun emit(type: String, body: JSONObject = JSONObject()) {
        if (!closed) push(body.put("type", type))
    }
    private fun phase(value: String, text: String = "") = emit("reception_event",
        JSONObject().put("phase", value).put("text", text))
    private fun current(epoch: NativeVoiceAttempt) = !closed && attempt === epoch && epoch.active()
    private fun dispatch(epoch: NativeVoiceAttempt, block: () -> Unit) { ui.post { if (current(epoch)) block() } }
    private fun terminalError(epoch: NativeVoiceAttempt, message: String) {
        if (current(epoch) && epoch.finish(error = true)) phase("error", message)
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started || closed) return
        started = true
        connect()
    }

    private fun connect() {
        if (closed) return
        attempt?.finish()
        val epoch = NativeVoiceAttempt().also { attempt = it }
        audioReady = false
        earlyAudio.clear(); earlyBytes = 0
        phase(if (reconnects == 0) "connecting" else "reconnecting",
            if (reconnects == 0) "Connecting voice…" else "Connection lost. Reconnecting…")
        if (!current(epoch)) return
        try {
            val connection = factory(object : VoiceTransport.Listener {
                override fun onReady() = dispatch(epoch) {
                    if (audio != null) return@dispatch
                    val connection = transport ?: return@dispatch
                    val device = runCatching {
                        DuplexAudio(context, connection.inputSampleRate, connection.outputSampleRate,
                            onPcm = { bytes -> if (current(epoch)) connection.sendAudio(bytes) },
                            onLevel = { level -> dispatch(epoch) {
                                emit("energy", JSONObject().put("level", level.toDouble()))
                            } },
                            onFailure = { dispatch(epoch) { terminalError(epoch,
                                "Microphone or audio focus was interrupted. Please start again.") } },
                            handset = handset)
                    }.getOrElse {
                        terminalError(epoch, "Android audio could not initialize. Check microphone access and retry.")
                        return@dispatch
                    }
                    device.setMuted(muted)
                    audio = device
                    cleanup.execute {
                        val prepared = runCatching { device.start() }.isSuccess
                        ui.post {
                            if (!current(epoch)) return@post
                            if (!prepared) { terminalError(epoch, "Microphone could not start. Check microphone access and retry."); return@post }
                            audioReady = true
                            while (earlyAudio.isNotEmpty()) device.enqueue(earlyAudio.removeFirst())
                            earlyBytes = 0
                            emit("audio_state", JSONObject().put("state", if (muted) "muted" else "recording"))
                            phase("listening")
                        }
                    }
                }
                override fun onAudio(pcm: ByteArray) {
                    if (!current(epoch) || pcm.isEmpty()) return
                    val limit = (transport?.outputSampleRate ?: 16000).coerceIn(8000, 48000) * 2
                    // Bound PCM before posting it: a busy UI must not retain an unbounded
                    // number of provider frames outside the native playback queue.
                    when (epoch.reservePcm(pcm.size, limit)) {
                        NativeVoiceAttempt.Reservation.DROPPED -> return
                        NativeVoiceAttempt.Reservation.OVERFLOW -> {
                            dispatch(epoch) { terminalError(epoch, "Voice playback fell behind. Please retry.") }
                            return
                        }
                        NativeVoiceAttempt.Reservation.ACCEPTED -> Unit
                    }
                    val posted = ui.post {
                        epoch.releasePcm(pcm.size)
                        if (!current(epoch)) return@post
                        val device = audio
                        if (audioReady && device != null) device.enqueue(pcm)
                        else if (pcm.size > limit - earlyBytes) {
                            terminalError(epoch, "Audio startup fell behind. Please retry.")
                        } else { earlyAudio.addLast(pcm); earlyBytes += pcm.size }
                    }
                    if (!posted) epoch.releasePcm(pcm.size)
                }
                override fun onTranscript(text: String, final: Boolean) = dispatch(epoch) {
                    if (text.isNotEmpty()) emit(if (mode == "translation") "translation_text" else "assistant_text",
                        JSONObject().put("text", text.take(12000)).put("final", final))
                }
                override fun onInterrupted() = dispatch(epoch) {
                    earlyAudio.clear(); earlyBytes = 0
                    audio?.clearPlayback()
                }
                override fun onError(message: String, retryable: Boolean) = dispatch(epoch) {
                    if (retryable && reconnects < 3) reconnect(epoch) else terminalError(epoch, message)
                }
                override fun onClosed() = dispatch(epoch) {
                    // Error is terminal for this attempt. A following provider close must
                    // never replace its error with a successful-looking ended state.
                    if (!epoch.failed && epoch.finish()) phase("ended", "Conversation ended.")
                }
                override fun onToolCall(id: String, name: String, parameters: JSONObject) {
                    dispatch(epoch) {
                        if (mode != "reception") return@dispatch
                        tools.handle(name, parameters) { result, error -> dispatch(epoch) {
                            if (name == "search_nearby" && result.has("attribution")) {
                                emit("local_source", JSONObject()
                                    .put("attribution", result.optString("attribution"))
                                    .put("source_url", result.optString("attribution_url", "https://www.openstreetmap.org/copyright")))
                            }
                            transport?.completeToolCall(id, result, error)
                        } }
                    }
                }
            })
            transport = connection
            connection.connect()
        } catch (_: Exception) { terminalError(epoch, "Voice connection could not start. Check setup and retry.") }
    }

    private fun reconnect(epoch: NativeVoiceAttempt) {
        if (!current(epoch)) return
        epoch.finish(error = true) // Fence late audio and tool results before teardown begins.
        val reconnectEpoch = NativeVoiceAttempt().also { attempt = it }
        reconnects++
        val oldTransport = transport.also { transport = null }
        val oldAudio = audio.also { audio = null }
        retiringAudio = oldAudio
        audioReady = false; earlyAudio.clear(); earlyBytes = 0
        phase("reconnecting", "Connection lost. Reconnecting…")
        emit("audio_state", JSONObject().put("state", "stopping"))
        cleanup.execute {
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
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!closed && attempt?.active() != false) {
            muted = value
            audio?.setMuted(value)
            if (audioReady) emit("audio_state", JSONObject().put("state", if (value) "muted" else "recording"))
        }
    }

    fun stop(done: (Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        releaseResult?.let { done(it); return }
        stopped.add(done)
        if (stopping) return
        stopping = true; closed = true; attempt?.finish()
        earlyAudio.clear(); earlyBytes = 0; audioReady = false
        val oldTransport = transport.also { transport = null }
        val oldAudio = listOfNotNull(audio, retiringAudio).distinct()
        audio = null; retiringAudio = null
        cleanup.execute {
            runCatching { oldTransport?.close() }
            var off = oldAudio.map { runCatching { it.stop() }.getOrDefault(false) }.all { it }
            // A stopped driver may need one further release attempt after its read unblocks.
            if (!off) off = oldAudio.map { runCatching { it.stop() }.getOrDefault(false) }.all { it }
            ui.post {
                releaseResult = off
                // Keep failed native owners alive and report false. The Activity must
                // retain its ownership block for new sessions and update installation.
                unreleasedAudio = if (off) emptyList() else oldAudio
                val callbacks = stopped.toList(); stopped.clear()
                cleanup.shutdown()
                callbacks.forEach { callback -> runCatching { callback(off) } }
            }
        }
    }
}
