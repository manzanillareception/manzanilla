package com.agentdeck.hotel

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lifecycle-aware fleet-management client: heartbeat, policy apply/ACK, session admission,
 * and a bounded persisted telemetry queue with backoff. Foreground heartbeats only (pilot
 * target 30 s); process death restarts with a fresh bootId and the persisted queue intact.
 * v1 admission is FAIL CLOSED for managed starts: no cached-approval fallback.
 */
class ManagementSync(
    private val context: Context,
    private val onMain: (() -> Unit) -> Unit,
    private val onPolicyBlocked: () -> Unit = {},
    private val onAdmissionDenied: (String?) -> Unit = {},
    private val client: ManagementClient = ManagementClient(OkHttpManagementTransport()),
    private val backoff: Backoff = Backoff()
) {
    companion object {
        const val HEARTBEAT_MS = 30_000L
        const val DIAGNOSTIC_EVERY_N_HEARTBEATS = 10
        const val DEFAULT_BASE = "https://admin.manzanillareception.com"
    }

    private val prefs = context.getSharedPreferences("management", Context.MODE_PRIVATE)
    private val connection = ManagementConnection(context)
    private val queue = EventQueueStore(File(context.noBackupFilesDir, "management-queue.jsonl"))
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ManzanillaManagement").apply { isDaemon = true } }
    private val counter = AtomicInteger(0)
    private val bootId = UUID.randomUUID().toString()
    private val reporter = SessionReporter(bootId, BuildConfig.VERSION_NAME,
        clock = { System.currentTimeMillis() }, sequence = { counter.getAndIncrement() },
        sink = { event -> queue.append(event) })

    @Volatile private var foreground = false
    @Volatile private var disposed = false
    @Volatile private var heartbeatScheduled = false
    private var heartbeatsSinceDiagnostic = DIAGNOSTIC_EVERY_N_HEARTBEATS // first heartbeat sends one

    // Device-state suppliers, installed by the Activity. Defaults keep telemetry honest (unknown/null).
    var modeProvider: () -> String = { "idle" }
    var microphoneProvider: () -> String = { "unknown" }
    /** rev12: current updater state for update.status; null means nothing reported yet. */
    var updateStateProvider: () -> ManagementTelemetry.UpdateState? = { null }
    var batteryProvider: () -> Int? = {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) (level * 100) / scale else null
    }
    var freeMemoryProvider: () -> Int? = {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)
        if (info.availMem > 0) (info.availMem / (1024 * 1024)).coerceAtMost(1_048_576L).toInt() else null
    }

    private var desiredAccess: String
        get() = prefs.getString("desiredAccess", "pending") ?: "pending"
        set(value) = prefs.edit().putString("desiredAccess", value).apply()
    private var effectiveAccess: String
        get() = prefs.getString("effectiveAccess", "pending") ?: "pending"
        set(value) = prefs.edit().putString("effectiveAccess", value).apply()
    private var policyRevision: Int
        get() = prefs.getInt("policyRevision", 1)
        set(value) = prefs.edit().putInt("policyRevision", value).apply()
    private var ackRevision: Int
        get() = prefs.getInt("ackRevision", 0)
        set(value) = prefs.edit().putInt("ackRevision", value).apply()
    private var credentialState: String
        get() = prefs.getString("credentialState", "valid") ?: "valid"
        set(value) = prefs.edit().putString("credentialState", value).apply()
    private var lastSyncNote: String
        get() = prefs.getString("lastSyncNote", "never synced") ?: "never synced"
        set(value) = prefs.edit().putString("lastSyncNote", value).apply()

    fun enrolled(): Boolean = connection.load() != null

    fun statusSummary(): String {
        val conn = connection.load() ?: return "Not enrolled. This tablet runs unmanaged (legacy mode): voice works without fleet monitoring."
        val short = conn.deviceId.take(8)
        val access = if (credentialState == "revoked") "enrollment revoked - re-enroll required"
            else "access: $effectiveAccess (desired: $desiredAccess, policy rev $policyRevision, ack $ackRevision)"
        return "Enrolled as installation $short… on ${conn.base}. $access. Last sync: $lastSyncNote."
    }

    fun onForeground() {
        if (disposed) return
        foreground = true
        if (enrolled() && credentialState == "valid") scheduleHeartbeat(0)
    }

    fun onBackground() {
        foreground = false
        handler.removeCallbacks(heartbeatTick)
        heartbeatScheduled = false
    }

    fun dispose() {
        disposed = true
        onBackground()
        executor.shutdown()
    }

    // ---- session admission and voice instrumentation ----

    fun newAttemptId(): String = reporter.newAttemptId()

    fun beginSession(sessionId: String, feature: String, attemptId: String) =
        reporter.beginSession(sessionId, feature, attemptId)

    fun sessionEnded() = reporter.sessionEnded()

    fun permissionDenied() = reporter.permissionDenied()

    fun policySyncFailed(sessionId: String, attemptId: String, feature: String) =
        reporter.policySyncFailed(sessionId, attemptId, feature)

    /** Admission for a new managed voice start. Callback lands on the main thread. */
    fun admit(sessionId: String, attemptId: String, feature: String,
              callback: (AdmissionPolicy.Decision, String?) -> Unit) {
        val conn = connection.load()
        if (conn == null) { onMain { callback(AdmissionPolicy.Decision.UNMANAGED, null) }; return }
        if (credentialState == "revoked") { onMain { callback(AdmissionPolicy.Decision.DENY, "revoked") }; return }
        executor.execute {
            val decision: Pair<AdmissionPolicy.Decision, String?> =
                when (val result = client.admitSession(conn.base, conn.credential, sessionId, attemptId, feature)) {
                    is ManagementClient.Result.Ok ->
                        if (result.value.admit) AdmissionPolicy.Decision.ADMIT to null
                        else AdmissionPolicy.Decision.DENY to (result.value.reason ?: "blocked")
                    is ManagementClient.Result.Rejected ->
                        if (result.code == "CREDENTIAL_REVOKED") {
                            markRevoked()
                            AdmissionPolicy.Decision.DENY to "revoked"
                        } else AdmissionPolicy.Decision.UNAVAILABLE to null
                    is ManagementClient.Result.Unreachable -> AdmissionPolicy.Decision.UNAVAILABLE to null
                }
            if (!disposed) onMain { callback(decision.first, decision.second) }
        }
    }

    /** Engine-originated voice events: phases and allowlisted timing samples. */
    fun voiceEvent(event: JSONObject) {
        when (event.optString("type")) {
            "reception_event" -> when (event.optString("phase")) {
                "listening" -> reporter.attemptListening()
                "reconnecting" -> {
                    val code = event.optString("error_code").ifBlank { null }
                    val sessionId = reporter.activeSessionId()
                    val attemptId = reporter.attemptReconnecting(code)
                    // Continuation of an admitted session: reserve the new attempt; a denial
                    // ends the session, an outage does not (the start was already admitted).
                    val feature = reporter.activeFeature()
                    if (sessionId != null && feature != null && attemptId.isNotEmpty() && enrolled() && credentialState == "valid") {
                        val conn = connection.load() ?: return
                        executor.execute {
                            when (val result = client.admitSession(conn.base, conn.credential, sessionId, attemptId, feature)) {
                                is ManagementClient.Result.Ok ->
                                    if (!result.value.admit && !disposed) onMain { onAdmissionDenied(result.value.reason) }
                                is ManagementClient.Result.Rejected ->
                                    if (result.code == "CREDENTIAL_REVOKED") { markRevoked(); if (!disposed) onMain { onAdmissionDenied("revoked") } }
                                is ManagementClient.Result.Unreachable -> Unit
                            }
                        }
                    }
                }
                "error" -> reporter.attemptFailed(event.optString("error_code")
                    .ifBlank { ManagementErrorCodes.PROVIDER })
            }
            "voice_diagnostics" -> {
                val sample = event.opt("stream_start_to_first_output_event_ms") as? Number
                if (sample != null) reporter.firstAudioMeasured(sample.toInt())
            }
        }
    }

    // ---- enrollment ----

    fun signInAndEnroll(base: String, email: String, password: String, label: String,
                        callback: (Boolean, String) -> Unit) {
        val supabaseUrl = BuildConfig.SUPABASE_URL
        val anonKey = BuildConfig.SUPABASE_ANON_KEY
        if (supabaseUrl.isBlank() || anonKey.isBlank()) {
            onMain { callback(false, "Hotel sign-in is not configured in this build. Use a one-use setup code below, or update the app.") }
            return
        }
        executor.execute {
            when (val signIn = client.hotelSignIn(supabaseUrl, anonKey, email, password)) {
                is ManagementClient.Result.Ok -> enrollInternal(base, hotelJwt = signIn.value, setupToken = null, label = label, callback = callback)
                is ManagementClient.Result.Rejected -> onMain {
                    callback(false, if (signIn.status == 400 || signIn.status == 401)
                        "The hotel email or password was not accepted. Check both and try again."
                    else "Sign-in failed (${(signIn.code)}). Try again.") }
                is ManagementClient.Result.Unreachable -> onMain {
                    callback(false, "The hotel sign-in service could not be reached. Check the internet connection and retry.") }
            }
        }
    }

    fun enrollWithToken(base: String, setupToken: String, label: String, callback: (Boolean, String) -> Unit) =
        executor.execute { enrollInternal(base, hotelJwt = null, setupToken = setupToken, label = label, callback = callback) }

    private fun enrollInternal(base: String, hotelJwt: String?, setupToken: String?, label: String,
                               callback: (Boolean, String) -> Unit) {
        val validBase = ManagementCredentials.validBase(base)
        val validLabel = ManagementCredentials.validLabel(label)
        if (validBase == null || validLabel == null) {
            onMain { callback(false, "Enter a valid HTTPS backend URL and a device label.") }
            return
        }
        when (val result = client.enroll(validBase, hotelJwt, setupToken, validLabel, BuildConfig.VERSION_NAME, bootId)) {
            is ManagementClient.Result.Ok -> {
                val saved = connection.save(validBase, result.value.deviceId, result.value.deviceCredential)
                if (!saved) {
                    onMain { callback(false, connection.errorMessage ?: "The enrollment could not be stored securely.") }
                    return
                }
                // Fresh managed identity: unmanaged history never uploads; start with a clean queue.
                queue.clear()
                credentialState = "valid"
                desiredAccess = result.value.desiredAccess
                effectiveAccess = result.value.desiredAccess
                policyRevision = result.value.policyRevision
                ackRevision = 0
                lastSyncNote = "enrolled, awaiting approval"
                queueDiagnostic()
                backoff.reset()
                if (foreground) scheduleHeartbeat(0)
                onMain { callback(true, "Enrolled. Waiting for approval by the Manzanilla team.") }
            }
            is ManagementClient.Result.Rejected -> onMain {
                callback(false, when (result.code) {
                    "SETUP_TOKEN_INVALID" -> "That setup code was not recognized. Check it or request a new one."
                    "SETUP_TOKEN_CONSUMED" -> "That setup code was already used. Request a new one."
                    "FORBIDDEN" -> "That account is not a hotel account. Use the hotel login from the welcome email."
                    "UNAUTHORIZED" -> "Sign-in failed. Check the hotel email and password."
                    else -> "Enrollment failed (${result.code}). Try again."
                }) }
            is ManagementClient.Result.Unreachable -> onMain {
                callback(false, "The management service could not be reached. Check the URL and internet connection, then retry.") }
        }
    }

    fun clearEnrollment() {
        onBackground()
        connection.clear()
        queue.clear()
        prefs.edit().clear().apply()
    }

    fun syncNow() {
        if (enrolled() && credentialState == "valid") scheduleHeartbeat(0)
    }

    // ---- heartbeat / policy / flush (single-thread executor) ----

    private val heartbeatTick = object : Runnable {
        override fun run() {
            heartbeatScheduled = false
            if (!foreground || disposed || !enrolled() || credentialState != "valid") return
            executor.execute { heartbeatOnce() }
        }
    }

    private fun scheduleHeartbeat(delayMs: Long) {
        if (heartbeatScheduled || disposed) return
        heartbeatScheduled = true
        handler.postDelayed(heartbeatTick, delayMs)
    }

    private fun heartbeatOnce() {
        val conn = connection.load() ?: return
        when (val result = client.heartbeat(conn.base, conn.credential, bootId, counter.getAndIncrement(),
            BuildConfig.VERSION_NAME, modeProvider(), microphoneProvider(), System.currentTimeMillis())) {
            is ManagementClient.Result.Ok -> {
                backoff.reset()
                lastSyncNote = "ok"
                applyPolicy(conn, result.value)
                maybeQueueUpdateStatus()
                flush(conn)
                if (foreground && !disposed) scheduleHeartbeat(HEARTBEAT_MS)
            }
            is ManagementClient.Result.Rejected -> {
                if (result.code == "CREDENTIAL_REVOKED") { markRevoked(); return }
                lastSyncNote = "retrying (${result.code})"
                if (foreground && !disposed) scheduleHeartbeat(backoff.nextDelayMs(result.retryAfterMs))
            }
            is ManagementClient.Result.Unreachable -> {
                lastSyncNote = "unreachable, retrying"
                if (foreground && !disposed) scheduleHeartbeat(backoff.nextDelayMs(null))
            }
        }
    }

    private fun applyPolicy(conn: ManagementCredentials.Connection, snapshot: ManagementClient.PolicySnapshot) {
        val previousEffective = effectiveAccess
        desiredAccess = snapshot.desiredAccess
        effectiveAccess = snapshot.effectiveAccess
        policyRevision = snapshot.policyRevision
        if (snapshot.policyRevision > ackRevision) {
            when (val ack = client.policyAck(conn.base, conn.credential, snapshot.policyRevision,
                bootId, counter.getAndIncrement())) {
                is ManagementClient.Result.Ok ->
                    // rev11: a recorded stale ACK may report an older revision; never regress.
                    ackRevision = maxOf(ackRevision, ack.value.revision, snapshot.policyRevision)
                is ManagementClient.Result.Rejected ->
                    if (ack.code == "DEVICE_BLOCKED") {
                        // Blocked installations ACK through the telemetry channel instead.
                        queue.append(ManagementTelemetry.policyAck(bootId, counter.getAndIncrement(),
                            BuildConfig.VERSION_NAME, System.currentTimeMillis(), snapshot.policyRevision))
                        ackRevision = snapshot.policyRevision
                    } else if (ack.code == "CREDENTIAL_REVOKED") { markRevoked(); return }
                    else if (ack.code == "FUTURE_REVISION" || ack.code == "STALE_REVISION" || ack.code == "INVALID_STATE") {
                        // Contract 409s: never silently ignored. The mismatch is visible in the
                        // next diagnostic snapshot and sync note; the following heartbeat's
                        // snapshot re-drives a correct ACK.
                        lastSyncNote = "policy ack rejected (${ack.code})"
                    }
                is ManagementClient.Result.Unreachable -> Unit // retried with the next heartbeat
            }
        }
        heartbeatsSinceDiagnostic += 1
        if (heartbeatsSinceDiagnostic >= DIAGNOSTIC_EVERY_N_HEARTBEATS) {
            heartbeatsSinceDiagnostic = 0
            queueDiagnostic()
        }
        if (snapshot.effectiveAccess == "blocked" && previousEffective != "blocked" && !disposed) {
            onMain { onPolicyBlocked() }
        }
    }

    // Emit update.status on state change only (never more than once per heartbeat cycle);
    // blocked devices cannot send it (the flush filter excludes the kind server-side too).
    private fun maybeQueueUpdateStatus() {
        val snapshot = updateStateProvider() ?: return
        val canonical = snapshot.canonical()
        if (canonical == lastReportedUpdate) return
        queue.append(ManagementTelemetry.updateStatus(bootId, counter.getAndIncrement(),
            BuildConfig.VERSION_NAME, System.currentTimeMillis(), snapshot))
        lastReportedUpdate = canonical
    }

    private var lastReportedUpdate: String?
        get() = prefs.getString("lastUpdateStatus", null)
        set(value) = prefs.edit().putString("lastUpdateStatus", value).apply()

    private fun queueDiagnostic() {
        queue.append(ManagementTelemetry.diagnosticSnapshot(bootId, counter.getAndIncrement(),
            BuildConfig.VERSION_NAME, System.currentTimeMillis(),
            microphoneProvider(), batteryProvider(), freeMemoryProvider()))
    }

    private fun flush(conn: ManagementCredentials.Connection) {
        val allowed = if (effectiveAccess == "blocked") ManagementTelemetry.BLOCKED_ALLOWED else null
        var guard = 0
        while (guard++ < 10) {
            val batch = ManagementTelemetry.pickBatch(queue.events(), allowed)
            if (batch.isEmpty()) return
            when (val result = client.sendBatch(conn.base, conn.credential, batch)) {
                is ManagementClient.Result.Ok ->
                    queue.remove(batch.map { it.getString("eventId") }.toSet())
                is ManagementClient.Result.Rejected -> when (result.code) {
                    "CREDENTIAL_REVOKED" -> { markRevoked(); return }
                    "VALIDATION_FAILED" ->
                        // Poison batch: it can never be accepted; drop it and keep the rest moving.
                        queue.remove(batch.map { it.getString("eventId") }.toSet())
                    else -> return // rate-limited/unavailable: retry with the next heartbeat
                }
                is ManagementClient.Result.Unreachable -> return
            }
        }
    }

    private fun markRevoked() {
        credentialState = "revoked"
        lastSyncNote = "enrollment revoked by server"
        onMain { onAdmissionDenied("revoked") }
    }
}
