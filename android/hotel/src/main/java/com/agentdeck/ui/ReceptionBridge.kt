package com.agentdeck.ui

import org.json.JSONObject

/** Bounds the serialized local-page request before parsing, independently of field limits. */
internal object ReceptionBridge {
    // A valid note contains 4,000 + 100 + 20 characters. JSON can escape each
    // character as six code units (\u0001), plus the action/session envelope.
    // The old 8,192 limit silently discarded valid notes before native review.
    const val MAX_MESSAGE_CHARS = 32 * 1024
    private val actions = setOf(
        "info", "start", "stop", "appearance", "night", "bell", "settings", "voice_setup", "night_setup", "request_location", "languages",
        "checkin", "operator", "taxi", "question", "message", "confirm", "silence",
        "activity", "mute", "exit", "apply_update", "check_updates"
    )

    fun decode(raw: String): JSONObject? {
        if (raw.length > MAX_MESSAGE_CHARS) return null
        val message = try { JSONObject(raw) } catch (_: Exception) { return null }
        return message.takeIf { it.optString("action") in actions }
    }
}
