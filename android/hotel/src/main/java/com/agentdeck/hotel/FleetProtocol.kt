package com.agentdeck.hotel

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Fixed, content-free V2 wire contract. No provider key or utterance is accepted here. */
internal object FleetProtocol {
    const val PREFIX = "/api/v2"
    const val MAX_RESPONSE_BYTES = 128 * 1024
    const val MAX_EVENTS = 100
    val timingFields = setOf("connection_ready_ms", "native_audio_start_ms", "max_upload_queue_audio_ms",
        "max_local_receive_dispatch_ms", "stream_start_to_first_output_event_ms", "pcm_arrival_to_first_write_ms",
        "pcm_arrival_to_playback_head_ms", "queued_audio_ms", "interruption_flush_ms", "session_duration_ms",
        "tool_duration_ms", "committed_task_count", "storage_available_bytes", "battery_percent")
    private val booleanFields = setOf("success", "microphone_permission", "audio_released", "charging")
    private val eventTypes = setOf("app_start", "voice_timing", "diagnostics", "audio_error",
        "session_started", "session_ended", "tool_outcome")
    private val profileFields = setOf("name", "address", "area", "opening_hours", "facts", "latitude", "longitude",
        "local_pack", "availability", "availability_updated_at_ms")
    private val configFields = profileFields

    fun base(value: String): String {
        val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTPS service URL")
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null && url.encodedPath == "/") {
            "Use the HTTPS service origin, without a path, login, query or fragment"
        }
        return url.toString().trimEnd('/')
    }

    fun secret(value: String): String = value.also {
        require(it.length in 32..4096 && it.none { c -> c.isWhitespace() || c.isISOControl() })
    }

    fun identifier(value: String): String = value.also {
        require(it.length in 1..128 && it.matches(Regex("[A-Za-z0-9_-]+")))
    }

    fun safeMetrics(input: JSONObject): JSONObject = JSONObject().apply {
        for (name in timingFields) {
            if (!input.has(name) || input.isNull(name)) continue
            val raw = input.opt(name)
            if (raw is Number) {
                val value = raw.toDouble()
                val maximum = when (name) {
                    "battery_percent" -> 100.0
                    "storage_available_bytes" -> Long.MAX_VALUE.toDouble()
                    else -> 86_400_000.0
                }
                if (value.isFinite() && value >= 0 && value <= maximum) put(name, raw)
            }
        }
        for (name in booleanFields) if (input.opt(name) is Boolean) put(name, input.getBoolean(name))
    }

    fun event(type: String, metrics: JSONObject, id: String, nowMillis: Long): JSONObject {
        require(type in eventTypes)
        return JSONObject().put("id", identifier(id)).put("type", type)
            .put("occurred_at", nowMillis / 1000.0).put("metrics", safeMetrics(metrics))
    }

    fun mic(raw: String): String = when (raw) {
        "recording", "on", "muted" -> "on" // Muting does not prove the physical recorder is released.
        "off" -> "off"
        "stopping" -> "stopping"
        else -> "unknown"
    }

    fun mode(raw: String): String = if (raw in setOf("translation", "reception")) raw else "idle"

    /** Strict validation before any public profile mutation; preserve unspecified local fields. */
    fun publicProfile(config: JSONObject, current: NightProfile): JSONObject {
        require(config.keys().asSequence().all { it in configFields }) { "Unsupported hotel configuration field" }
        val out = JSONObject().put("name", current.name).put("address", current.address).put("area", current.area)
            .put("opening_hours", current.openingHours).put("facts", current.facts)
            .put("latitude", current.latitude ?: JSONObject.NULL).put("longitude", current.longitude ?: JSONObject.NULL)
            .put("local_pack", JSONArray(current.localPack.map { it.toJson() }))
            .put("availability", current.availability).put("availability_updated_at_ms", current.availabilityUpdatedAtMillis)
        for (name in profileFields) if (config.has(name)) out.put(name, config.get(name))
        for (name in setOf("name", "address", "area", "opening_hours", "facts", "availability")) require(out.opt(name) is String)
        for (name in setOf("latitude", "longitude")) require(out.isNull(name) || out.opt(name) is Number)
        require(out.opt("local_pack") is JSONArray)
        if (out.has("availability_updated_at_ms")) require(out.opt("availability_updated_at_ms") is Number)
        NightProfile.parse(out)
        return out
    }

    fun command(value: JSONObject, nowMillis: Long): FleetCommand {
        require(value.keys().asSequence().all { it in setOf("id", "type", "arguments", "expires_at", "state", "result_code", "created_at") })
        val arguments = value.getJSONObject("arguments")
        require(arguments.length() == 0) { "This device supports only parameter-free commands" }
        val type = value.getString("type")
        require(type in setOf("refresh_config", "check_update", "diagnostics"))
        val expires = value.getDouble("expires_at")
        require(expires.isFinite() && expires > 0)
        return FleetCommand(identifier(value.getString("id")), type, (expires * 1000).toLong()).also {
            require(it.expiresAtMillis > nowMillis) { "Command expired" }
        }
    }
}

internal data class FleetCommand(val id: String, val type: String, val expiresAtMillis: Long)
internal data class FleetRuntimeState(val foreground: Boolean = false, val mode: String = "idle",
    val micState: String = "off", val busy: Boolean = false, val updateState: String = "idle")
