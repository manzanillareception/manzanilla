package com.agentdeck.hotel

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Staff-only one-time V2 enrollment. A guest cannot grant themselves staff access. */
class FleetSetupActivity : Activity() {
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val store by lazy { FleetIdentityStore(this) }
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fleet-enrollment").apply { isDaemon = true } }
    private val epoch = AtomicLong()
    @Volatile private var authorized = false
    @Volatile private var visible = false
    private var authInFlight = false
    private var authAccepted = false
    private var destroyed = false
    private var status: TextView? = null
    private var polling = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        locked("Confirm this tablet's screen lock to manage fleet enrollment.")
    }
    override fun onResume() {
        super.onResume()
        visible = true
        if (authAccepted && keyguard.isDeviceSecure && !keyguard.isKeyguardLocked) {
            authAccepted = false; authorized = true; render()
        } else if (!authInFlight) authenticate()
    }
    override fun onPause() {
        visible = false; authorized = false; authAccepted = false
        epoch.incrementAndGet(); polling = false
        ui.removeCallbacksAndMessages(null)
        locked("Fleet setup is locked.")
        super.onPause()
    }
    @Deprecated("Android device credential confirmation for API 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTH_REQUEST || !authInFlight) return
        authInFlight = false
        authAccepted = resultCode == RESULT_OK && keyguard.isDeviceSecure
        if (!authAccepted) finish()
    }
    @Suppress("DEPRECATION")
    private fun authenticate() {
        if (!keyguard.isDeviceSecure) {
            locked("A tablet administrator must first configure an Android screen-lock PIN, password or pattern. Guests cannot configure fleet access.")
            return
        }
        val intent = keyguard.createConfirmDeviceCredentialIntent("Fleet enrollment", "Authorize setup with this tablet's screen lock.")
        if (intent == null) { locked("Android credential confirmation is unavailable."); return }
        authInFlight = true
        try { startActivityForResult(intent, AUTH_REQUEST) }
        catch (_: Exception) { authInFlight = false; locked("Android could not open credential confirmation.") }
    }
    private fun allowed(generation: Long) = !destroyed && visible && authorized && epoch.get() == generation
    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(40, 24, 40, 24); isSaveEnabled = false
    }
    private fun LinearLayout.label(text: String): TextView = TextView(this@FleetSetupActivity).apply {
        this.text = text; textSize = 17f; setPadding(0, 10, 0, 10); isSaveEnabled = false
    }.also(::addView)
    private fun LinearLayout.button(text: String, action: () -> Unit) = Button(this@FleetSetupActivity).apply {
        this.text = text; filterTouchesWhenObscured = true; setOnClickListener { action() }
    }.also(::addView)
    private fun locked(message: String) {
        status = null
        setContentView(column().apply {
            label("Fleet enrollment")
            label(message)
            button("Authenticate") { if (visible && !authInFlight) authenticate() }
            button("Close") { finish() }
        })
    }
    private fun render() {
        if (!visible || !authorized || keyguard.isKeyguardLocked) return
        val column = column()
        column.label("Fleet enrollment")
        column.label("Pair this tablet once with its hotel's admin portal. No manager password or provider key is stored by this setup.")
        status = column.label("Loading enrollment…")
        val generation = epoch.incrementAndGet()
        setContentView(ScrollView(this).apply { isSaveEnabled = false; addView(column) })
        worker.execute {
            val result = runCatching { store.startIdentity() }
            ui.post {
                if (!allowed(generation)) return@post
                result.fold(onSuccess = { snapshot ->
                    if (snapshot.credentials != null) {
                        status?.text = if (snapshot.credentials.activationPending) "Enrollment was saved securely. Confirming activation…"
                            else "This tablet has a stored fleet enrollment. Its current connection status is shown in the hotel portal."
                        if (snapshot.credentials.activationPending) activate(snapshot.credentials, generation)
                        else {
                            column.button(if (snapshot.rotation != null) "Resume device credential rotation" else "Rotate device credential") {
                                if (allowed(generation) && !polling) rotate(generation)
                            }
                            if (snapshot.rotation != null) rotate(generation)
                        }
                    } else if (snapshot.pending != null && snapshot.pending.expiresAtMillis > System.currentTimeMillis()) {
                        showPending(snapshot.pending, generation)
                    } else if (snapshot.starting != null) {
                        status?.text = "Resuming the saved pairing request without creating another device…"
                        begin(snapshot.starting.base, snapshot.starting.deviceName, snapshot.installationId, generation)
                    } else {
                        status?.text = "Enter your organization's HTTPS service origin, then approve the displayed code in its admin portal."
                        val base = EditText(this).apply {
                            hint = "https://console.your-domain.example"; setSingleLine()
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                            isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        }
                        val name = EditText(this).apply {
                            hint = "Device label"; setText("Reception device"); setSingleLine(); isSaveEnabled = false
                            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        }
                        column.addView(base); column.addView(name)
                        column.button("Create pairing code") {
                            if (!allowed(generation)) return@button
                            try {
                                val origin = FleetProtocol.base(base.text.toString())
                                val label = name.text.toString().trim()
                                require(label.length in 1..100 && label.none(Char::isISOControl))
                                begin(origin, label, snapshot.installationId, generation)
                            } catch (_: Exception) { status?.text = "Enter a valid HTTPS origin and a short device label." }
                        }
                    }
                    column.button("Done") { finish() }
                }, onFailure = { status?.text = "Stored enrollment is unavailable. Ask your administrator; setup has not erased or replaced it." })
            }
        }
    }
    private fun begin(base: String, name: String, installationId: String, generation: Long) {
        if (polling) return
        polling = true
        status?.text = "Creating pairing code…"
        worker.execute {
            val result = runCatching {
                val saved = store.prepareStart(base, name)
                val capability = saved.starting ?: error("Pairing capability was not saved")
                val value = FleetClient(base).post("/enrollment/start", JSONObject().put("installation_id", installationId)
                    .put("device_name", name).put("poll_token", capability.pollToken))
                check(allowed(generation))
                val expires = value.getDouble("expires_at")
                require(expires.isFinite() && expires * 1000 > System.currentTimeMillis())
                val pending = FleetIdentityStore.Pending(base, FleetProtocol.identifier(value.getString("request_id")),
                    value.getString("user_code").also { require(it.length in 1..32 && it.none(Char::isISOControl)) },
                    FleetProtocol.secret(value.getString("poll_token")), (expires * 1000).toLong())
                store.savePending(pending) // Durable capability before showing a code or polling.
                pending
            }
            ui.post {
                if (!allowed(generation)) return@post
                polling = false
                result.fold({ showPending(it, generation) }, { status?.text = "Pairing could not be prepared securely. Retry after checking the service connection." })
            }
        }
    }
    private fun showPending(pending: FleetIdentityStore.Pending, generation: Long) {
        status?.text = "Pairing code: ${pending.userCode}\nOpen the admin portal and approve this code for the correct hotel. This code expires; keep this screen open."
        poll(pending, generation)
    }
    private fun poll(pending: FleetIdentityStore.Pending, generation: Long) {
        if (!allowed(generation) || polling) return
        if (pending.expiresAtMillis <= System.currentTimeMillis()) { status?.text = "Pairing expired. Reopen fleet setup to request a new code."; return }
        polling = true
        worker.execute {
            val result = runCatching {
                val value = FleetClient(pending.base).post("/enrollment/${pending.requestId}/poll", JSONObject().put("poll_token", pending.pollToken))
                check(allowed(generation))
                when (value.getString("status")) {
                    "approved" -> store.approved(pending.requestId, value.getString("device_id"), value.getString("hotel_id"), value.getString("device_token")).credentials
                    "pending" -> null
                    else -> error("Pairing no longer active")
                }
            }
            ui.post {
                if (!allowed(generation)) return@post
                polling = false
                result.fold(onSuccess = { credentials ->
                    if (credentials != null) activate(credentials, generation)
                    else ui.postDelayed({ poll(pending, generation) }, 5_000)
                }, onFailure = { error ->
                    if (error is FleetHttpException && error.status in setOf(401, 403, 404, 410)) {
                        status?.text = "Pairing is no longer valid. Ask the administrator to review this request in the portal."
                    } else {
                        status?.text = "Pairing code: ${pending.userCode}\nConnection interrupted. Retrying without creating another device…"
                        ui.postDelayed({ poll(pending, generation) }, 10_000)
                    }
                })
            }
        }
    }
    private fun activate(credentials: FleetIdentityStore.Credentials, generation: Long) {
        worker.execute {
            val result = runCatching {
                FleetClient(credentials.base, credentials.token).post("/device/activate")
                store.activated(credentials.deviceId, credentials.token)
            }.onFailure { error ->
                if (error is FleetHttpException && error.status in setOf(401, 403)) runCatching {
                    if (store.load()?.rotation != null) store.recoverExpiredRotation(credentials.deviceId, credentials.token)
                }
            }
            ui.post {
                if (!allowed(generation)) return@post
                status?.text = if (result.isSuccess) "Enrollment activated. Return to reception to begin automatic health reporting."
                    else "Enrollment is securely saved. Activation will retry when reception resumes; no second pairing is needed."
            }
        }
    }
    private fun rotate(generation: Long) {
        if (!allowed(generation) || polling) return
        polling = true
        status?.text = "Rotating this device credential. The prior credential is retained securely until activation succeeds…"
        worker.execute {
            val result = runCatching {
                check(allowed(generation))
                val prepared = store.prepareRotation() // Persist the operation ID before network side effects.
                val credentials = prepared.credentials ?: error("Device credential unavailable")
                val rotation = prepared.rotation ?: error("Rotation unavailable")
                val value = try {
                    FleetClient(credentials.base, credentials.token).post("/device/credentials/rotate",
                        JSONObject().put("idempotency_key", rotation.operationId))
                } catch (error: FleetHttpException) {
                    if (error.status == 409) store.retryRotation(rotation.operationId)
                    throw error
                }
                check(allowed(generation))
                store.rotated(rotation.operationId, value.getString("device_id"), value.getString("device_token")).credentials!!
            }
            ui.post {
                if (!allowed(generation)) return@post
                polling = false
                result.fold(onSuccess = { activate(it, generation) }, onFailure = {
                    status?.text = "Rotation has not been confirmed. The saved operation can resume when reception starts or this setup reopens."
                })
            }
        }
    }
    override fun onDestroy() {
        destroyed = true; authorized = false; epoch.incrementAndGet()
        ui.removeCallbacksAndMessages(null); worker.shutdownNow()
        super.onDestroy()
    }
    companion object { private const val AUTH_REQUEST = 82 }
}
