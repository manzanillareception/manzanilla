package com.agentdeck.hotel

import android.Manifest
import android.content.pm.PackageManager
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.agentdeck.ui.ReceptionView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Native-only credentials and audio; one visible session owns the microphone and speaker. */
class HotelActivity : Activity() {
    private var reception: ReceptionView? = null
    private val deck by lazy { getSharedPreferences("deck", MODE_PRIVATE) }
    private val owner = SessionOwner()
    private val ui = Handler(Looper.getMainLooper())
    private val ringing = AtomicBoolean(false)
    private val updater by lazy { Updater(this,
        onStatus = { status -> runOnUiThread { onUpdate(status) } },
        canInstall = ::canInstallUpdate) }
    private val updatePoll = object : Runnable {
        override fun run() {
            if (!foreground || destroyed) return
            updater.check()
            ui.postDelayed(this, 10 * 60_000L)
        }
    }
    private val hotelConnection by lazy { HotelConnection(this) }
    private var gateway: HotelGateway? = null
    private val openAiStore by lazy { SecretStore(this, "openai") }
    private val profileStore by lazy { NightProfileStore(this) }
    private val location by lazy { DeviceLocation(this, profileStore) }
    private val nightTools by lazy { NightTools(profileStore, location) }
    private var voice: NativeVoiceSession? = null
    private var pendingMicrophone: SessionOwner.Lease? = null
    private var permissionDialog = false
    private var connectingLease: SessionOwner.Lease? = null
    private fun openAiConfigured() = openAiStore.status() == SecretStore.Status.READY
    private fun cartesiaConfigured() = BuildConfig.CARTESIA_API_KEY.isNotBlank() &&
        BuildConfig.CARTESIA_AGENT_ID.matches(Regex("agent_[A-Za-z0-9_-]+"))
    private val afterRelease = ArrayList<() -> Unit>()
    private var night = false
    private var foreground = false
    private var destroyed = false
    private var backCallback: OnBackInvokedCallback? = null
    private var returnToTranslation = false
    private var audioState = "off"
    private var lastActivity = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (owner.current != null && !owner.closing && SystemClock.elapsedRealtime() - lastActivity >= 120_000)
                stopSession("timeout", "Conversation ended after two minutes without guest activity.")
            updater.tryInstallIfIdle()
            ui.postDelayed(this, 1_000)
        }
    }

    private fun backendConfigured() = hotelConnection.load() != null
    private fun canInstallUpdate(): Boolean = foreground && !destroyed && owner.current == null &&
        !owner.closing && audioState == "off" && !DuplexAudio.hasUnreleasedResources()
    private fun current(lease: SessionOwner.Lease) = owner.accepts(lease.request.id, lease.generation)
    private fun scoped(lease: SessionOwner.Lease, message: JSONObject) = message
        .put("session_id", lease.request.id).put("generation", lease.generation)
    private fun push(lease: SessionOwner.Lease, message: JSONObject) { reception?.push(scoped(lease, message)) }
    private fun phase(lease: SessionOwner.Lease, phase: String, text: String = "") =
        push(lease, JSONObject().put("type", "reception_event").put("phase", phase).put("text", text))
    private fun audio(lease: SessionOwner.Lease, state: String) =
        push(lease, JSONObject().put("type", "audio_state").put("state", state))
    private fun notice(text: String) { reception?.push(JSONObject().put("type", "notice").put("text", text)) }

    private fun pushState() {
        val page = reception ?: return
        val translationReady = openAiConfigured()
        val receptionReady = cartesiaConfigured()
        page.push(JSONObject().put("type", "appearance").put("value", deck.getString("reception_appearance", "dark")))
        page.push(JSONObject().put("type", "night_mode").put("enabled", night))
        page.push(JSONObject().put("type", "reception_info").put("hotel_name", profileStore.load().name.ifBlank { "Hotel reception" })
            .put("sample_hotel", false).put("configured", receptionReady)
            .put("translation_available", translationReady).put("reception_available", receptionReady)
            .put("openai_configured", translationReady).put("audio_available", translationReady)
            .put("local_audio_state", audioState)
            .put("audio_gate", AudioPolicy.translationBlockReason(translationReady))
            .put("reception_gate", AudioPolicy.receptionBlockReason(receptionReady))
            .apply { java.util.Locale.getDefault().language.takeIf(DeskLanguages::has)?.let { put("staff_language", it) } })
        val guest = deck.getString("reception_guest_language", null)
        val staff = deck.getString("reception_staff_language", null)
        if (guest != null && staff != null) page.push(JSONObject().put("type", "language_preferences")
            .put("language", guest).put("staff_language", staff))
        page.push(JSONObject().put("type", "prep").put("status", if (translationReady || receptionReady) "ready" else "nokey").put("version", BuildConfig.VERSION_NAME))
        page.push(JSONObject().put("type", "connection").put("connected", translationReady || receptionReady))
    }

    private fun begin(request: SessionOwner.Request) {
        if (updater.isInstalling()) {
            reception?.push(JSONObject().put("type", "audio_state").put("state", "off")
                .put("session_id", request.id).put("generation", 0))
            reception?.push(JSONObject().put("type", "reception_event").put("phase", "error")
                .put("text", "An app update is being installed. Please wait for it to finish before starting a conversation.")
                .put("session_id", request.id).put("generation", 0))
            return
        }
        val blocked = AudioPolicy.blockReason(request.mode, openAiConfigured(), cartesiaConfigured())
        if (blocked.isNotEmpty()) {
            reception?.push(JSONObject().put("type", "audio_state").put("state", "off")
                .put("session_id", request.id).put("generation", 0))
            reception?.push(JSONObject().put("type", "reception_event").put("phase", "error")
                .put("text", blocked).put("session_id", request.id).put("generation", 0))
            return
        }
        val lease = owner.acquire(request) ?: run { notice("Wait until the previous microphone release is confirmed before starting again."); return }
        lastActivity = SystemClock.elapsedRealtime()
        audioState = "off"
        phase(lease, "connecting")
        audio(lease, "off")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicrophone = lease
            permissionDialog = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5)
        } else connect(lease)
    }

    private fun connect(lease: SessionOwner.Lease) {
        if (!current(lease) || !foreground || connectingLease == lease) return
        connectingLease = lease
        pendingMicrophone = null
        val request = lease.request
        val key = if (request.mode == "translation") openAiStore.load().orEmpty() else BuildConfig.CARTESIA_API_KEY
        val context = profileStore.load().contextJson().toString()
        voice = NativeVoiceSession(this, request.mode, { listener ->
            if (request.mode == "translation") OpenAiTranslationTransport(key,
                DeskLanguages.english(request.guest), DeskLanguages.english(request.staff), listener)
            else CartesiaNightTransport(key, BuildConfig.CARTESIA_AGENT_ID, context, listener)
        }, nightTools, push = { event ->
            if (current(lease)) {
                val type = event.optString("type")
                if (type == "audio_state") audioState = event.optString("state", "unknown")
                if (type == "translation_text" || type == "assistant_text" ||
                    (type == "energy" && event.optDouble("level") > 0.05)) lastActivity = SystemClock.elapsedRealtime()
                if (type == "reception_event" && event.optString("phase") in listOf("error", "ended")) {
                    stopSession(if (event.optString("phase") == "ended") "stopped" else "error", event.optString("text"))
                } else push(lease, event)
            }
        })
        voice?.start()
        if (!current(lease)) return
        // Optional staff backend operations remain separate from native voice readiness.
        if (request.mode == "reception") hotelConnection.load()?.let { credentials ->
            gateway?.dispose()
            gateway = HotelGateway(credentials.base, credentials.token,
                { currentLease, event -> runOnUiThread { if (current(currentLease)) push(currentLease, event) } },
                { currentLease, event -> runOnUiThread { if (current(currentLease)) dialOperation(currentLease, event) } })
                .also { it.begin(lease) }
        }
    }

    private fun stopSession(reason: String = "stopped", text: String = "", done: (() -> Unit)? = null) {
        if (done != null) afterRelease.add(done)
        val lease = owner.beginClose()
        if (lease == null) { if (owner.current == null) finishReleaseActions(); return }
        pendingMicrophone = null
        connectingLease = null
        gateway?.end()
        phase(lease, "ending")
        audioState = "stopping"
        audio(lease, "stopping")
        val oldVoice = voice.also { voice = null }
        val released: (Boolean) -> Unit = { knownOff ->
            runOnUiThread {
                if (owner.current == lease && owner.closing) {
                    audioState = if (knownOff) "off" else "unknown"
                    audio(lease, audioState)
                    owner.released(lease, knownOff)
                    phase(lease, if (knownOff) reason else "error", if (knownOff) text else "Audio cleanup could not be confirmed. Reopen the app before restarting.")
                    if (knownOff) finishReleaseActions() else afterRelease.clear()
                }
            }
        }
        if (oldVoice != null) oldVoice.stop(released) else released(true)
    }

    private fun finishReleaseActions() {
        while (afterRelease.isNotEmpty()) afterRelease.removeAt(0).invoke()
        updater.tryInstallIfIdle()
    }


    private fun ringBell() {
        if (!ringing.compareAndSet(false, true)) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 350)
            ui.postDelayed({ tone.release(); ringing.set(false) }, 400)
        } catch (_: Exception) { ringing.set(false) }
    }

    private fun openStaffSetup(voiceOnly: Boolean = false, nightOnly: Boolean = false) = stopSession(done = {
        if (!destroyed && foreground) {
            val setup = Intent(this, KeyEntryActivity::class.java).putExtra("voice_only", voiceOnly).putExtra("night_only", nightOnly).putExtra("v1_setup", !voiceOnly && !nightOnly)
            if (voiceOnly) {
                @Suppress("DEPRECATION")
                startActivityForResult(setup, 41)
            } else startActivity(setup)
        }
    })

    private fun openDialer(number: String): String {
        if (!number.matches(Regex("\\+[1-9][0-9]{6,14}"))) return "failed"
        return try { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))); "dialer_opened" }
        catch (_: Exception) { "failed" }
    }
    private fun dialOperation(lease: SessionOwner.Lease, response: JSONObject) {
        val number = response.optString("number", response.optJSONObject("result")?.optString("number").orEmpty())
        val status = openDialer(number)
        push(lease, JSONObject(response.toString()).put("type", "operation_result").put("status", status)
            .put("text", if (status == "dialer_opened") "The phone dialer is open. No answered call or taxi booking is confirmed." else "This device could not open the dialer. Ask reception to call for you."))
    }

    private fun onUpdate(status: String) {
        val page = reception ?: return
        when {
            status.startsWith("checking:") -> page.push(JSONObject().put("type", "update_status").put("phase", "checking").put("text", "Checking for updates…"))
            status.startsWith("current:") -> page.push(JSONObject().put("type", "update_status").put("phase", "current").put("text", "Up to date · ${BuildConfig.VERSION_NAME}"))
            status.startsWith("offer:") -> page.push(JSONObject().put("type", "update_status").put("phase", "ready")
                .put("version", status.removePrefix("offer:"))
                .put("text", "Update ${status.removePrefix("offer:")} downloaded. It will apply when this device is idle."))
            status.startsWith("waiting:") -> page.push(JSONObject().put("type", "update_status").put("phase", "ready")
                .put("text", status.removePrefix("waiting:")))
            status.startsWith("progress:") -> {
                val value = status.removePrefix("progress:")
                val percent = value.toIntOrNull()
                val text = if (percent != null) { if (percent >= 0) "Downloading $percent%" else "Starting download…" }
                    else if (value.startsWith("m") && value.removePrefix("m").toLongOrNull() != null) "Downloading ${value.removePrefix("m")} MB"
                    else value.ifBlank { "Starting download…" }
                page.push(JSONObject().put("type", "update_status").put("phase", "progress").put("percent", percent ?: -1).put("text", text))
            }
            status.startsWith("confirm:") -> page.push(JSONObject().put("type", "update_status").put("phase", "confirm").put("text", status.removePrefix("confirm:")))
            status.startsWith("failed:") -> page.push(JSONObject().put("type", "update_status").put("phase", "failed").put("text", status.removePrefix("failed:")))
            status.startsWith("installed") -> page.push(JSONObject().put("type", "update_status").put("phase", "installed").put("text", "Updated"))
        }
    }

    private fun handle(message: JSONObject) {
        val action = message.optString("action")
        when (action) {
            "stop", "silence" -> if (owner.current != null && !owner.acceptsCancellation(
                message.optString("session_id"), if (message.has("generation")) message.optInt("generation", -1) else null)) return
            "message", "confirm", "checkin", "operator", "taxi", "question" ->
                if (!owner.accepts(message.optString("session_id"), message.optInt("generation", -1), duringClose = true)) return
            "activity", "mute" -> if (owner.current != null &&
                !owner.accepts(message.optString("session_id"), message.optInt("generation", -1))) return
        }
        when (action) {
            "appearance" -> {
                val value = message.optString("value")
                if (value == "dark" || value == "light") setAppearance(value)
            }
            "night" -> if (owner.current == null) {
                night = message.optBoolean("enabled", !night)
                deck.edit().putBoolean("reception_night", night).apply()
                reception?.push(JSONObject().put("type", "night_mode").put("enabled", night))
            }
            "languages" -> if (owner.current == null) {
                val guest = message.optString("language"); val staff = message.optString("staff_language")
                if (DeskLanguages.has(guest) && DeskLanguages.has(staff)) deck.edit()
                    .putString("reception_guest_language", guest).putString("reception_staff_language", staff).apply()
            }
            "bell" -> ringBell()
            "settings" -> openStaffSetup()
            "voice_setup" -> openStaffSetup(voiceOnly = true)
            "night_setup" -> openStaffSetup(nightOnly = true)
            "request_location" -> {
                if (!foreground) return
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    permissionDialog = true
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 6)
                } else showLocation()
            }
            "mute" -> voice?.setMuted(message.optBoolean("muted"))
            "info" -> pushState()
            "start" -> {
                val id = message.optString("session_id")
                val mode = message.optString("mode")
                val guest = message.optString("language"); val staff = message.optString("staff_language")
                val validId = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
                if (!validId || !DeskLanguages.has(guest) || !DeskLanguages.has(staff) || (mode != "translation" && mode != "reception") || (mode == "translation" && guest == staff)) {
                    notice("Choose a supported mode and languages; translation needs two different languages.")
                    return
                }
                begin(SessionOwner.Request(id, mode, guest, staff, message.optString("greet")))
            }
            "stop" -> stopSession()
            "message" -> owner.current?.takeIf { current(it) && it.request.mode == "reception" }?.let {
                gateway?.action(it, "message", message.optJSONObject("arguments") ?: JSONObject())
            }
            "confirm" -> owner.current?.takeIf { current(it) }?.let { lease ->
                val id = message.optString("draft_id"); val approved = message.optBoolean("approved")
                if (id.startsWith("operation:")) gateway?.confirm(lease, id, approved)
                else notice("This request is no longer available. Review a new request.")
            }
            "checkin", "operator", "taxi", "question" -> owner.current?.takeIf { current(it) && it.request.mode == "reception" }?.let {
                if (gateway == null) notice(if (action == "taxi") "Taxi booking is unavailable until the hotel's taxi API is connected." else "Ask the night receptionist by voice, or contact hotel staff.")
                else gateway?.action(it, message.optString("action"), message.optJSONObject("arguments") ?: JSONObject())
            }
            "activity" -> lastActivity = SystemClock.elapsedRealtime()
            "silence" -> if (owner.current != null) stopSession()
            "exit" -> stopSession(done = { finish() })
            "check_updates" -> updater.check()
            "apply_update" -> if (canInstallUpdate()) updater.installOffered()
                else onUpdate("confirm:The downloaded update will wait until the conversation and audio cleanup finish.")
        }
    }

    private fun setAppearance(value: String) {
        deck.edit().putString("reception_appearance", value).apply()
        window.setBackgroundDrawable(ColorDrawable(if (value == "dark") Color.rgb(8, 15, 21) else Color.rgb(243, 245, 242)))
        reception?.push(JSONObject().put("type", "appearance").put("value", value))
    }

    private fun navigateBack() {
        val event = JSONObject().put("type", "navigation").put("action", "back").put("source", "android")
        val lease = owner.current
        reception?.push(if (lease != null) scoped(lease, event) else event)
        if (lease != null) stopSession()
    }

    @Deprecated("Legacy system Back dispatch")
    override fun onBackPressed() = navigateBack()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        RetiredCredentials.remove(this)
        val dark = deck.getString("reception_appearance", "dark") != "light"
        window.setBackgroundDrawable(ColorDrawable(if (dark) Color.rgb(8, 15, 21) else Color.rgb(243, 245, 242)))
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { navigateBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        reception = ReceptionView(this, ::handle) { stopSession("error", "The reception screen stopped. Return to the launcher and reopen Reception.") }
        setContentView(FrameLayout(this).apply { addView(reception, FrameLayout.LayoutParams(-1, -1)) })
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        night = deck.getBoolean("reception_night", false)
        ui.post(watchdog)
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        reception?.setShown(true)
        pushState()
        if (returnToTranslation) {
            returnToTranslation = false
            reception?.push(JSONObject().put("type", "translation_setup_return"))
        }
        pendingMicrophone?.takeIf { current(it) && !permissionDialog &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }?.let(::connect)
        updater.onForeground()
        updater.check()
        ui.removeCallbacks(updatePoll)
        ui.postDelayed(updatePoll, 10 * 60_000L)
    }
    @Deprecated("Receives the internal translation setup result for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Restore only non-sensitive language selection, never a prior conversation.
        // Both Save and Cancel return here; neither starts microphone capture.
        if (requestCode == 41) returnToTranslation = true
    }
    override fun onPause() {
        foreground = false
        ui.removeCallbacks(updatePoll)
        if (!permissionDialog) {
            stopSession()
            reception?.setShown(false)
        }
        super.onPause()
    }
    private fun showLocation() {
        location.request { result -> runOnUiThread {
            if (!destroyed && foreground) reception?.push(JSONObject().put("type", "notice").put("text",
                if (result.optString("status") == "available") "Location is ready for nearby suggestions."
                else "Location unavailable. Add the hotel coordinates in Night setup."))
        } }
    }

    @Deprecated("Android API 26 permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionDialog = false
        if (requestCode == 5) {
            val lease = pendingMicrophone ?: return
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (foreground) connect(lease)
            } else stopSession("error", "Microphone access is required for voice. You can enable it in Android settings.")
        } else if (requestCode == 6 && foreground) showLocation()
    }

    override fun onStop() {
        // A system permission sheet may pause us; actual backgrounding always releases audio.
        stopSession()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        ui.removeCallbacks(watchdog)
        ui.removeCallbacks(updatePoll)
        stopSession(done = {
            gateway?.dispose()
        })
        nightTools.close()
        location.close()
        reception?.dispose(); reception = null
        super.onDestroy()
    }
}
