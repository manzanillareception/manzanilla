package com.agentdeck.hotel

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.agentdeck.audio.PcmEnvelope
import java.util.concurrent.atomic.AtomicBoolean

/** One native duplex owner. Permission and foreground/session ownership belong to the caller. */
@SuppressLint("MissingPermission")
class DuplexAudio(
    context: Context,
    private val inputRate: Int,
    private val outputRate: Int,
    private val onPcm: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onFailure: (String) -> Unit,
    private val handset: Boolean = false
) {
    companion object {
        private val resourceLock = Any()
        private var resourceOwner: DuplexAudio? = null

        /** Includes failed/stopping owners until capture, playback and route/focus cleanup is confirmed. */
        @JvmStatic fun hasUnreleasedResources(): Boolean = synchronized(resourceLock) { resourceOwner != null }
    }

    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
        ?: error("Android audio service is unavailable.")
    private val stateLock = Any()
    private val teardownLock = Any()
    private val playbackLock = Any()
    private val failureReported = AtomicBoolean(false)
    private val cleanupStarted = AtomicBoolean(false)
    private val playback = PcmPlaybackQueue(outputRate * 2)
    private val envelope = PcmEnvelope()
    @Volatile private var running = false
    @Volatile private var closing = false
    @Volatile private var closed = false
    @Volatile private var muted = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var inputLevel = 0f
    @Volatile private var lastInput = 0L
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeakerphone = false
    private var oldCommunicationDevice: AudioDeviceInfo? = null
    private var modeChanged = false
    private var speakerChanged = false
    private var communicationChanged = false
    private var inputDeviceId: Int? = null
    private var outputDeviceId: Int? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            fail("Audio was interrupted by another app. Restart the conversation to continue.")
        }
    }

    init {
        require(inputRate in 8000..48000 && inputRate % 50 == 0)
        require(outputRate in 8000..48000 && outputRate % 50 == 0)
    }

    /** Streaming state only. Call stop() for proof that native resources were detached. */
    val isRunning: Boolean get() = running && !closing

    fun start() {
        try {
            synchronized(stateLock) {
                check(!closed && !closing) { "This audio session has ended." }
                if (running) return
                synchronized(resourceLock) {
                    check(resourceOwner == null || resourceOwner === this) {
                        "The previous microphone release is not confirmed. Wait before restarting."
                    }
                    resourceOwner = this
                }
                check(!manager.isMicrophoneMute) { "Microphone access is switched off in Android." }
                check(manager.mode != AudioManager.MODE_IN_CALL) { "Finish the phone call before starting reception." }
                oldMode = manager.mode
                @Suppress("DEPRECATION")
                oldSpeakerphone = manager.isSpeakerphoneOn
                if (Build.VERSION.SDK_INT >= 31) oldCommunicationDevice = manager.communicationDevice
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper())).build()
                focusRequest = request
                check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    "Another app is using audio. Finish it and try again."
                }
                focusHeld = true
                check(!failureReported.get()) { "Audio focus was interrupted during setup." }
                modeChanged = true
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                configureRoute()
                prepareRecorder()
                preparePlayer()
                check(!closing && !failureReported.get()) { "Audio setup was interrupted." }
                recorder!!.startRecording()
                check(recorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "The microphone could not start. Check Android microphone permissions."
                }
                player!!.play()
                running = true
                inputThread = Thread(::capture, "ManzanillaVoiceCapture").apply { isDaemon = true }
                outputThread = Thread(::play, "ManzanillaVoicePlayout").apply { isDaemon = true }
                inputThread!!.start()
                outputThread!!.start()
            }
        } catch (failure: Exception) {
            stop()
            throw IllegalStateException(when (failure) {
                is SecurityException -> "Allow microphone access in Android settings, then try again."
                is IllegalStateException -> failure.message ?: "Android audio could not start."
                else -> "Android audio could not start. Check the device audio route and retry."
            })
        }
    }

    private fun configureRoute() {
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val target = if (handset) outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: error("Handset audio is unavailable. Use the on-screen reception button.")
        else outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (Build.VERSION.SDK_INT >= 31 && target != null) {
            val communication = manager.availableCommunicationDevices.firstOrNull { it.id == target.id }
            if (communication != null) {
                check(manager.setCommunicationDevice(communication)) { "Android could not select the reception speaker." }
                communicationChanged = true
            }
        }
        speakerChanged = true
        @Suppress("DEPRECATION")
        manager.isSpeakerphoneOn = !handset
    }

    private fun prepareRecorder() {
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(inputRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minimum = AudioRecord.getMinBufferSize(inputRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "This microphone does not support the requested voice format." }
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum * 2, inputRate / 50 * 2 * 4)).build()
        recorder = record
        check(record.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not initialize." }
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val input = if (handset) inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        else inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (input != null) {
            check(record.setPreferredDevice(input)) { "Android could not select the microphone." }
            inputDeviceId = input.id
        }
        if (AcousticEchoCanceler.isAvailable()) runCatching {
            echo = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) runCatching {
            noise = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        }
    }

    private fun preparePlayer() {
        val minimum = AudioTrack.getMinBufferSize(outputRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "This speaker does not support the requested voice format." }
        val track = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(outputRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, outputRate / 50 * 2 * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        player = track
        check(track.state == AudioTrack.STATE_INITIALIZED) { "The speaker could not initialize." }
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val target = if (handset) outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        else outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (target != null) {
            check(track.setPreferredDevice(target)) { "Android could not select the reception speaker." }
            outputDeviceId = target.id
        }
    }

    private fun capture() {
        val frame = ByteArray(inputRate / 50 * 2)
        var filled = 0
        try {
            while (running && !closing) {
                val record = recorder ?: break
                if (manager.mode == AudioManager.MODE_IN_CALL) error("phone call owns audio")
                if (manager.isMicrophoneMute || (Build.VERSION.SDK_INT >= 29 && record.activeRecordingConfiguration?.isClientSilenced == true))
                    error("microphone disabled")
                val actualInput = record.routedDevice
                if (inputDeviceId != null && actualInput != null && actualInput.id != inputDeviceId)
                    error("microphone route changed")
                val count = record.read(frame, filled, frame.size - filled, AudioRecord.READ_NON_BLOCKING)
                if (count < 0) { if (running) error("microphone read failed") else break }
                if (count % 2 != 0) error("invalid microphone frame")
                if (count == 0) { Thread.sleep(5); continue }
                filled += count
                if (filled == frame.size) {
                    if (muted) frame.fill(0)
                    inputLevel = PcmEnvelope.rms16le(frame)
                    lastInput = SystemClock.elapsedRealtime()
                    if (running && !closing) onPcm(frame.copyOf())
                    frame.fill(0)
                    filled = 0
                }
            }
        } catch (_: InterruptedException) { }
        catch (_: SecurityException) { if (running) fail("Microphone permission was revoked. Allow access in Android settings.") }
        catch (_: Exception) { if (running) fail("Microphone capture stopped. Check Android audio settings and restart the conversation.") }
        finally { frame.fill(0); inputLevel = 0f }
    }

    private fun play() {
        try {
            while (running && !closing) {
                val packet = playback.poll(20)
                if (packet != null) {
                    try {
                        var offset = 0
                        var lastProgress = SystemClock.elapsedRealtime()
                        while (running && !closing && offset < packet.pcm.size && playback.isCurrent(packet)) {
                            val written = synchronized(playbackLock) {
                                if (!running || closing || !playback.isCurrent(packet)) 0 else {
                                    val track = player
                                    if (track == null) 0 else {
                                        val count = track.write(packet.pcm, offset, minOf(outputRate / 50 * 2, packet.pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                                        if (count > 0 && count % 2 != 0) error("invalid speaker frame")
                                        val actualOutput = track.routedDevice
                                        if (outputDeviceId != null && actualOutput != null && actualOutput.id != outputDeviceId)
                                            error("speaker route changed")
                                        if (count > 0) envelope.written(packet.pcm, offset, count)
                                        count
                                    }
                                }
                            }
                            if (written < 0) error("speaker write failed")
                            if (written > 0) {
                                offset += written
                                lastProgress = SystemClock.elapsedRealtime()
                            } else {
                                if (SystemClock.elapsedRealtime() - lastProgress > 2000) error("speaker stalled")
                                Thread.sleep(3)
                            }
                            publishLevel()
                        }
                    } finally { playback.complete(packet) }
                }
                publishLevel()
            }
        } catch (_: InterruptedException) { }
        catch (_: Exception) { if (running) fail("Speaker playback stopped. Check the audio route and restart the conversation.") }
        finally { runCatching { onLevel(0f) } }
    }

    private fun publishLevel() {
        val track = player ?: return
        val heard = envelope.currentEnergy(track.playbackHeadPosition.toLong() and 0xffffffffL, SystemClock.elapsedRealtimeNanos())
        val captured = if (SystemClock.elapsedRealtime() - lastInput < 100) inputLevel else 0f
        if (running && !closing) onLevel(maxOf(heard, captured).coerceIn(0f, 1f))
    }

    fun enqueue(pcm: ByteArray) {
        if (pcm.isEmpty() || !running || closing) return
        val accepted = synchronized(playbackLock) { !closing && running && playback.offer(pcm) }
        if (!accepted && running && !closing) fail("Voice playback fell behind. Check the connection and restart the conversation.")
    }

    /** The same lock fences both in-flight writes and the native buffer flush. */
    fun clearPlayback() {
        try {
            synchronized(playbackLock) {
                playback.clear()
                envelope.reset()
                player?.let { track ->
                    track.pause()
                    track.flush()
                    if (running && !closing) track.play()
                }
            }
        } catch (_: Exception) { if (running && !closing) fail("Audio interruption could not be applied. Restart the conversation.") }
    }

    fun setMuted(value: Boolean) { muted = value; if (value) inputLevel = 0f }

    private fun fail(message: String) {
        if (closing || closed || !failureReported.compareAndSet(false, true)) return
        running = false
        runCatching { recorder?.stop() }
        runCatching { onFailure(message) }
    }

    /** Call off the main thread. False means ownership must remain blocked until a retry confirms release. */
    fun stop(): Boolean = synchronized(teardownLock) {
        if (closed) return@synchronized true
        val threads: List<Thread>
        synchronized(stateLock) {
            closing = true
            running = false
            threads = listOfNotNull(inputThread, outputThread)
            runCatching { recorder?.stop() }
            synchronized(playbackLock) {
                playback.clear()
                runCatching { player?.pause() }
                runCatching { player?.flush() }
            }
        }
        threads.forEach { it.interrupt() }
        for (thread in threads) {
            if (thread !== Thread.currentThread()) runCatching { thread.join(1500) }
        }
        if (threads.any { it.isAlive }) {
            cleanupEventually(threads)
            return@synchronized false
        }
        val detached = synchronized(stateLock) {
            var ok = true
            fun release(block: () -> Unit) { try { block() } catch (_: Exception) { ok = false } }
            release { noise?.release(); noise = null }
            release { echo?.release(); echo = null }
            release { recorder?.release(); recorder = null }
            synchronized(playbackLock) {
                release { player?.release(); player = null }
                playback.clear()
                envelope.reset()
            }
            if (communicationChanged && Build.VERSION.SDK_INT >= 31) release {
                val previous = oldCommunicationDevice
                if (previous == null) manager.clearCommunicationDevice()
                else if (!manager.setCommunicationDevice(previous)) manager.clearCommunicationDevice()
                communicationChanged = false
            }
            if (speakerChanged) release {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = oldSpeakerphone
                speakerChanged = false
            }
            if (modeChanged) release { manager.mode = oldMode; modeChanged = false }
            if (focusHeld) release {
                val request = focusRequest
                check(request != null && manager.abandonAudioFocusRequest(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                focusHeld = false
                focusRequest = null
            }
            if (ok) {
                inputThread = null
                outputThread = null
                closed = true
                synchronized(resourceLock) { if (resourceOwner === this) resourceOwner = null }
            }
            ok
        }
        inputLevel = 0f
        runCatching { onLevel(0f) }
        detached
    }

    private fun cleanupEventually(threads: List<Thread>) {
        if (!cleanupStarted.compareAndSet(false, true)) return
        Thread({
            try {
                threads.forEach { if (it !== Thread.currentThread()) it.join() }
                stop()
            } catch (_: InterruptedException) { }
            finally { cleanupStarted.set(false) }
        }, "ManzanillaAudioRelease").apply { isDaemon = true; start() }
    }
}
