package com.agentdeck.hotel

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

/** Real Android capture/playout with one synthetic tone burst; no provider or credential. */
internal object NativeAudioFixture {
    fun verify(test: Instrumentation) {
        check(Build.HARDWARE == "ranchu") { "Disposable emulator required" }
        if (Build.VERSION.SDK_INT < 28) throw IllegalStateException("API 28 or newer emulator required")
        ProviderBuildFixture.verifyDebug(test)
        check(!DuplexAudio.hasUnreleasedResources()) { "An earlier audio owner was not released" }
        val context = test.targetContext
        check(SecretStore(context, "openai").status() == SecretStore.Status.ABSENT)
        // Revocation may terminate the instrumented process, so the host runner revokes after completion.
        test.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val activity = test.startActivitySync(Intent(context, HotelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HotelActivity
        test.waitForIdleSync()
        val foreground = HotelActivity::class.java.getDeclaredField("foreground").apply { isAccessible = true }
        val tools = NightTools({ NightProfile() }, object : NightLocationProvider {
            override fun request(callback: (JSONObject) -> Unit) = error("No tools are used by this fixture")
        })
        val connected = AtomicBoolean(false)
        val transportClosed = AtomicBoolean(false)
        val recording = AtomicBoolean(false)
        val listening = AtomicBoolean(false)
        val played = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val off = AtomicReference<Boolean?>(null)
        var session: NativeVoiceSession? = null
        try {
            test.runOnMainSync {
                check(!activity.isFinishing && !activity.isDestroyed)
                check(foreground.getBoolean(activity)) { "Native audio fixture requires a foreground Activity" }
                session = NativeVoiceSession(activity, "translation", { listener ->
                    object : VoiceTransport {
                        override val inputSampleRate = 16000
                        override val outputSampleRate = 16000
                        override fun connect() {
                            check(connected.compareAndSet(false, true)) { "Unexpected second connection" }
                            val tone = ByteArray(outputSampleRate * 2 * 3)
                            repeat(outputSampleRate * 3) { frame ->
                                val sample = (1600 * sin(2 * Math.PI * 440 * frame / outputSampleRate)).toInt()
                                tone[frame * 2] = sample.toByte()
                                tone[frame * 2 + 1] = (sample shr 8).toByte()
                            }
                            listener.onReady()
                            listener.onAudio(tone) // One 3s packet, without waiting for native startup.
                            tone.fill(0)
                        }
                        override fun sendAudio(pcm: ByteArray) { /* Discard microphone frames locally. */ }
                        override fun close() { transportClosed.set(true) }
                    }
                }, tools, push = { event ->
                    if (event.optString("type") == "audio_state" && event.optString("state") == "recording") recording.set(true)
                    if (event.optString("phase") == "listening") listening.set(true)
                    if (event.optString("type") == "voice_diagnostics" &&
                        event.optString("stage") == "native_playback_head_started") played.set(true)
                    if (event.optString("phase") == "error") { failed.set(true); ready.countDown() }
                    if (recording.get() && listening.get() && played.get()) ready.countDown()
                })
                session!!.start()
            }
            check(ready.await(20, TimeUnit.SECONDS)) { "Native audio did not become functional" }
            check(!failed.get() && recording.get() && listening.get() && played.get()) { "Native audio reported a failure" }
        } finally {
            test.runOnMainSync {
                session?.stop { knownOff -> off.set(knownOff); stopped.countDown() } ?: run { off.set(true); stopped.countDown() }
            }
            val released = stopped.await(20, TimeUnit.SECONDS)
            tools.close()
            test.runOnMainSync { activity.finish() }
            check(released && off.get() == true && !DuplexAudio.hasUnreleasedResources()) { "Native audio release was not confirmed" }
        }
        check(!failed.get() && connected.get() && transportClosed.get())
        test.sendStatus(0, Bundle().apply {
            putString("stream", "NATIVE_AUDIO_BURST recording=true listening=true playback_head=true released=true provider_calls=0\n")
        })
    }
}
