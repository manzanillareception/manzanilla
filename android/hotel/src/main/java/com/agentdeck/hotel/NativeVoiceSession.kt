package com.agentdeck.hotel

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

/** Android bindings; the same session engine is exercised offline with delayed native devices. */
internal class NativeVoiceSession(
    context: Context,
    mode: String,
    factory: (VoiceTransport.Listener) -> VoiceTransport,
    tools: NightTools,
    handset: Boolean = false,
    push: (JSONObject) -> Unit
) {
    private val engine = VoiceSessionEngine(mode, factory, tools::handle, push,
        object : VoiceSessionUi {
            private val handler = Handler(Looper.getMainLooper())
            override fun isOnThread() = Looper.myLooper() == Looper.getMainLooper()
            override fun post(action: () -> Unit) = handler.post { action() }
            override fun postDelayed(action: () -> Unit, delayMillis: Long) = handler.postDelayed({ action() }, delayMillis)
        },
        Executors.newSingleThreadExecutor { task -> Thread(task, "ManzanillaVoiceAudio").apply { isDaemon = true } },
        { inputRate, outputRate, callbacks ->
            val native = DuplexAudio(context, inputRate, outputRate, callbacks.pcm, callbacks.level, callbacks.failure,
                handset, callbacks.diagnostics)
            object : VoiceAudioDevice {
                override fun start() = native.start()
                override fun enqueue(pcm: ByteArray) = native.enqueue(pcm)
                override fun clearPlayback() = native.clearPlayback()
                override fun setMuted(muted: Boolean) = native.setMuted(muted)
                override fun stop() = native.stop()
            }
        })

    fun start() = engine.start()
    fun setMuted(value: Boolean) = engine.setMuted(value)
    fun stop(done: (Boolean) -> Unit) = engine.stop(done)
}
