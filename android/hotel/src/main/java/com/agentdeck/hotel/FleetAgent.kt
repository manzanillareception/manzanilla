package com.agentdeck.hotel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Independent foreground management client; owns no microphone, speaker or provider connection. */
internal class FleetAgent(context: Context,
    private val reserveIdle: () -> Boolean,
    private val releaseIdle: () -> Unit,
    private val checkUpdate: () -> Boolean
) {
    private val app = context.applicationContext
    private val identity = FleetIdentityStore(app)
    private val ledger = FleetLedger(File(app.noBackupFilesDir, "fleet-v2-metadata.json"))
    private val profile = NightProfileStore(app)
    private val ui = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "fleet-metadata").apply { isDaemon = true } }
    private val epoch = AtomicLong()
    private val sequence = AtomicLong()
    // One agent instance is one reporting epoch. Restarts cannot replay an old sequence as fresh.
    private val bootId = UUID.randomUUID().toString()
    private var bootGeneration: Long? = null // Fleet worker only; committed before any heartbeat POST.
    @Volatile private var runtime = FleetRuntimeState()
    @Volatile private var closed = false
    private var scheduled: ScheduledFuture<*>? = null

    fun update(state: FleetRuntimeState) {
        val old = runtime
        runtime = state
        if (old.mode != state.mode) execute {
            ledger.enqueue(if (state.mode == "idle") "session_ended" else "session_started", JSONObject())
        }
    }
    fun foreground() {
        if (closed) return
        val generation = epoch.incrementAndGet()
        scheduled?.cancel(false)
        scheduled = worker.schedule({ cycle(generation) }, 0, TimeUnit.MILLISECONDS)
    }
    fun background() {
        epoch.incrementAndGet()
        scheduled?.cancel(false)
        // A final snapshot is best effort and never schedules background polling.
        execute {
            val credentials = identity.load()?.credentials ?: return@execute
            if (!credentials.activationPending) heartbeat(FleetClient(credentials.base, credentials.token), runtime.copy(foreground = false))
        }
    }
    fun recordMetrics(value: JSONObject) {
        val sanitized = FleetProtocol.safeMetrics(value)
        if (sanitized.length() > 0) execute { ledger.enqueue("voice_timing", sanitized) }
    }
    fun recordAudioFailure() = execute { ledger.enqueue("audio_error", JSONObject().put("success", false)) }
    private fun execute(action: () -> Unit) {
        if (closed) return
        try { worker.execute { if (!closed) runCatching(action) } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
    private fun current(generation: Long) = !closed && epoch.get() == generation && runtime.foreground
    private fun cycle(generation: Long) {
        if (!current(generation)) return
        var delay = 30_000L + Random.nextLong(-3_000L, 3_001L)
        try {
            var snapshot = identity.load() ?: return
            var credentials = snapshot.credentials ?: return
            val rotation = snapshot.rotation
            if (rotation != null && !credentials.activationPending) {
                try {
                    val value = FleetClient(credentials.base, credentials.token).post("/device/credentials/rotate",
                        JSONObject().put("idempotency_key", rotation.operationId))
                    snapshot = identity.rotated(rotation.operationId, value.getString("device_id"), value.getString("device_token"))
                    credentials = snapshot.credentials ?: error("Replacement credential unavailable")
                } catch (error: FleetHttpException) {
                    if (error.status == 409) identity.retryRotation(rotation.operationId)
                    throw error
                }
            }
            val client = FleetClient(credentials.base, credentials.token)
            if (credentials.activationPending) {
                try { client.post("/device/activate") }
                catch (error: FleetHttpException) {
                    if (error.status in setOf(401, 403) && snapshot.rotation != null)
                        identity.recoverExpiredRotation(credentials.deviceId, credentials.token)
                    throw error
                }
                identity.activated(credentials.deviceId, credentials.token)
            }
            if (!current(generation)) return
            heartbeat(client, runtime)
            if (!current(generation)) return
            flushEvents(client)
            if (!current(generation)) return
            if (!runtime.busy) refreshConfig(client, generation)
            if (!current(generation)) return
            val commands = client.get("/device/commands").getJSONArray("items")
            require(commands.length() <= FleetLedger.MAX_RECEIPTS)
            for (i in 0 until commands.length()) {
                if (!current(generation)) return
                handleCommand(client, commands.getJSONObject(i), generation)
            }
        } catch (error: FleetHttpException) {
            // Revocation does not erase keys/local memory or fall back to another hotel's identity.
            delay = if (error.status == 401 || error.status == 403) 120_000 else 60_000
        } catch (_: Exception) { delay = 60_000 }
        finally {
            if (current(generation)) scheduled = worker.schedule({ cycle(generation) }, delay, TimeUnit.MILLISECONDS)
        }
    }
    private fun health(state: FleetRuntimeState): JSONObject {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return JSONObject().put("microphone_permission", app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .put("audio_released", state.micState == "off" && !state.busy && !DuplexAudio.hasUnreleasedResources())
            .put("storage_available_bytes", app.filesDir.usableSpace.coerceAtLeast(0))
            .put("charging", (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            .apply { if (level >= 0 && scale > 0) put("battery_percent", (100 * level / scale).coerceIn(0, 100)) }
    }
    private fun heartbeat(client: FleetClient, state: FleetRuntimeState) {
        val generation = bootGeneration ?: ledger.nextBootGeneration().also { bootGeneration = it }
        client.post("/device/heartbeat", JSONObject().put("schema_version", 1).put("boot_id", bootId)
            .put("boot_generation", generation)
            .put("sequence", sequence.incrementAndGet()).put("app_version", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE).put("foreground", state.foreground)
            .put("mode", FleetProtocol.mode(state.mode)).put("mic_state", FleetProtocol.mic(state.micState))
            .put("config_version", ledger.configVersion()).put("update_state", state.updateState)
            .put("health", health(state)))
    }
    private fun flushEvents(client: FleetClient) {
        val events = ledger.events()
        if (events.length() == 0) return
        client.post("/device/events", JSONObject().put("events", events))
        // The server commits an idempotent batch before its success response.
        ledger.acknowledgedEvents((0 until events.length()).map { events.getJSONObject(it).getString("id") }.toSet())
    }
    private fun onUiBoolean(generation: Long, action: () -> Boolean, reservation: Boolean = false): Boolean {
        val task = FutureTask(Callable { if (current(generation)) action() else false })
        ui.post(task)
        return try { task.get(3, TimeUnit.SECONDS) } catch (_: Exception) {
            task.cancel(false)
            // If a running UI task reserved idle at the timeout boundary, clear that
            // reservation on the same UI queue. The single fleet worker cannot reserve
            // another operation before this queued release.
            if (reservation) ui.post(releaseIdle)
            false
        }
    }
    private fun refreshConfig(client: FleetClient, generation: Long): Boolean {
        val response = client.get("/device/config")
        require(response.keys().asSequence().all { it in setOf("version", "config") })
        val version = response.getInt("version")
        require(version >= 0)
        if (version < ledger.configVersion()) return false
        if (version > ledger.configVersion()) {
            val config = response.getJSONObject("config")
            val value = FleetProtocol.publicProfile(config, profile.load())
            if (!onUiBoolean(generation, reserveIdle, reservation = true)) return false
            try {
                if (!current(generation)) return false
                check(profile.save(value)) { "Public hotel profile could not be saved" }
                ledger.saveConfig(version, config)
            } finally { ui.post(releaseIdle) }
        }
        if (!current(generation)) return false
        client.post("/device/config/ack", JSONObject().put("version", version))
        return true
    }
    private fun sendReceipt(client: FleetClient, id: String, value: JSONObject) {
        client.post("/device/commands/$id/receipt", JSONObject().put("state", value.getString("state"))
            .put("result_code", value.getString("result_code")))
    }
    private fun handleCommand(client: FleetClient, input: JSONObject, generation: Long) {
        val command = FleetProtocol.command(input, System.currentTimeMillis())
        FleetCommandExecutor(ledger,
            send = { actual, receipt -> sendReceipt(client, actual.id, receipt) },
            current = { current(generation) }, busy = { runtime.busy }, effect = { actual ->
                when (actual.type) {
                    "refresh_config" -> FleetEffect(refreshConfig(client, generation), "config_applied")
                    "check_update" -> FleetEffect(onUiBoolean(generation, checkUpdate), "update_check_requested")
                    "diagnostics" -> { ledger.enqueue("diagnostics", health(runtime)); FleetEffect(true, "diagnostics_queued") }
                    else -> error("Unsupported command")
                }
            }).handle(command)
    }
    fun close() {
        closed = true
        epoch.incrementAndGet()
        scheduled?.cancel(false)
        worker.shutdownNow()
    }
}
