package com.agentdeck.hotel

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Builds the exact frozen /api/v1 telemetry envelope (schemaVersion 1). Allowlisted fields
 * only: never audio, captions, transcripts, prompts, guest identities, room numbers,
 * free-form messages, provider bodies or keys.
 */
object ManagementTelemetry {
    const val SCHEMA_VERSION = 1
    const val MAX_EVENTS_PER_BATCH = 100
    const val MAX_BATCH_BYTES = 65_536

    const val SESSION_STARTED = "session.started"
    const val SESSION_CONNECTED = "session.connected"
    const val SESSION_ENDED = "session.ended"
    const val CONNECTION_FAILED = "connection.failed"
    const val POLICY_ACK = "policy.ack"
    const val DIAGNOSTIC_SNAPSHOT = "diagnostic.snapshot"

    /** Kinds a service-blocked installation may still send per the frozen contract. */
    val BLOCKED_ALLOWED = setOf(POLICY_ACK, DIAGNOSTIC_SNAPSHOT)

    val ERROR_CODES = setOf("CONNECTION_TIMEOUT", "MIC_PERMISSION_DENIED", "PROVIDER_UNAVAILABLE",
        "AUDIO_STARTUP_TIMEOUT", "POLICY_SYNC_FAILED")

    fun timestamp(epochMs: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault()))

    private fun base(bootId: String, sequence: Int, build: String, atMs: Long) = JSONObject()
        .put("eventId", UUID.randomUUID().toString())
        .put("bootId", bootId)
        .put("sequence", sequence)
        .put("occurredAt", timestamp(atMs))
        .put("build", build)

    private fun session(bootId: String, sequence: Int, build: String, atMs: Long,
                        sessionId: String, attemptId: String, feature: String) =
        base(bootId, sequence, build, atMs)
            .put("sessionId", sessionId)
            .put("attemptId", attemptId)
            .put("feature", feature)

    fun sessionStarted(bootId: String, sequence: Int, build: String, atMs: Long,
                       sessionId: String, attemptId: String, feature: String) =
        session(bootId, sequence, build, atMs, sessionId, attemptId, feature).put("kind", SESSION_STARTED)

    fun sessionConnected(bootId: String, sequence: Int, build: String, atMs: Long,
                         sessionId: String, attemptId: String, feature: String, firstAudioMs: Int?) =
        session(bootId, sequence, build, atMs, sessionId, attemptId, feature)
            .put("kind", SESSION_CONNECTED)
            .put("firstAudioMs", firstAudioMs?.coerceIn(0, 120_000) ?: JSONObject.NULL)

    fun sessionEnded(bootId: String, sequence: Int, build: String, atMs: Long,
                     sessionId: String, attemptId: String, feature: String,
                     durationMs: Long, observed: Boolean) =
        session(bootId, sequence, build, atMs, sessionId, attemptId, feature)
            .put("kind", SESSION_ENDED)
            .put("durationMs", durationMs.coerceIn(0, 86_400_000))
            .put("durationProvenance", if (observed) "observed" else "estimated")

    fun connectionFailed(bootId: String, sequence: Int, build: String, atMs: Long,
                         sessionId: String, attemptId: String, feature: String, code: String): JSONObject {
        require(code in ERROR_CODES) { "Unknown connection failure code: $code" }
        return session(bootId, sequence, build, atMs, sessionId, attemptId, feature)
            .put("kind", CONNECTION_FAILED)
            .put("code", code)
    }

    fun policyAck(bootId: String, sequence: Int, build: String, atMs: Long, revision: Int) =
        base(bootId, sequence, build, atMs).put("kind", POLICY_ACK).put("revision", revision)

    fun diagnosticSnapshot(bootId: String, sequence: Int, build: String, atMs: Long,
                           microphone: String, batteryPercent: Int?, freeMemoryMb: Int?) =
        base(bootId, sequence, build, atMs)
            .put("kind", DIAGNOSTIC_SNAPSHOT)
            .put("microphone", microphone)
            .put("batteryPercent", batteryPercent?.coerceIn(0, 100) ?: JSONObject.NULL)
            .put("freeMemoryMb", freeMemoryMb?.coerceIn(0, 1_048_576) ?: JSONObject.NULL)

    fun kindOf(event: JSONObject): String = event.optString("kind")

    fun envelope(events: List<JSONObject>): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("events", JSONArray(events))

    fun serializedBytes(envelope: JSONObject): Int = envelope.toString().toByteArray(Charsets.UTF_8).size

    /**
     * Picks a leading run of queue events allowed by [allowedKinds] that fits one envelope.
     * Disallowed kinds are skipped (left in the queue) so blocked devices still send
     * policy ACKs and diagnostics while session history waits for unblock.
     */
    fun pickBatch(queue: List<JSONObject>, allowedKinds: Set<String>?,
                  maxEvents: Int = MAX_EVENTS_PER_BATCH, maxBytes: Int = MAX_BATCH_BYTES): List<JSONObject> {
        val picked = ArrayList<JSONObject>()
        var bytes = 24 // envelope framing estimate
        for (event in queue) {
            if (picked.size >= maxEvents) break
            val kind = kindOf(event)
            if (allowedKinds != null && kind !in allowedKinds) continue
            val size = event.toString().toByteArray(Charsets.UTF_8).size + 1
            if (picked.isNotEmpty() && bytes + size > maxBytes) continue
            if (size > maxBytes) continue // a single over-sized event is never sendable; caller drops it
            picked.add(event)
            bytes += size
        }
        return picked
    }
}
