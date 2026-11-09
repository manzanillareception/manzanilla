package com.agentdeck.hotel

import org.json.JSONObject
import java.util.UUID

/**
 * Turns the native voice lifecycle into contract telemetry events. Pure: clock and sink are
 * injected. One logical session has stable sessionId/feature; each connection attempt gets a
 * fresh attemptId. Emission rules (kept in sync with android/hotel/MANAGEMENT.md):
 *  - session.started once per logical session, only after admission (or unmanaged start).
 *  - session.connected once per attempt that reaches listening; firstAudioMs null unless measured.
 *  - connection.failed once per failed attempt, with a contract error code.
 *  - session.ended exactly once per started session, observed wall duration.
 *  - Policy-blocked starts emit only connection.failed (POLICY_SYNC_FAILED or nothing when the
 *    server itself denied); no fabricated session.
 */
class SessionReporter(
    private val bootId: String,
    private val build: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sequence: () -> Int,
    private val sink: (JSONObject) -> Unit
) {
    private class Attempt(val id: String) {
        var connected = false
        var closed = false
    }
    private class Session(val id: String, val feature: String, val startedAtMs: Long, var attempt: Attempt) {
        var ended = false
    }

    private var current: Session? = null
    private var pendingFirstAudioMs: Int? = null

    fun newAttemptId(): String = UUID.randomUUID().toString()

    /** Begins a managed-admitted or unmanaged logical session; emits session.started. */
    fun beginSession(sessionId: String, feature: String, attemptId: String) {
        endCurrent(clock())
        val session = Session(sessionId, feature, clock(), Attempt(attemptId))
        current = session
        sink(ManagementTelemetry.sessionStarted(bootId, sequence(), build, clock(), sessionId, attemptId, feature))
    }

    /** A new attempt started inside the current session (reconnect). Returns the new attemptId. */
    fun attemptReconnecting(errorCode: String?): String {
        val session = current ?: return ""
        failAttempt(session, errorCode ?: "PROVIDER_UNAVAILABLE")
        val attempt = Attempt(newAttemptId())
        session.attempt = attempt
        return attempt.id
    }

    fun attemptListening() {
        val session = current ?: return
        val attempt = session.attempt
        if (session.ended || attempt.connected || attempt.closed) return
        attempt.connected = true
        sink(ManagementTelemetry.sessionConnected(bootId, sequence(), build, clock(),
            session.id, attempt.id, session.feature, pendingFirstAudioMs))
        pendingFirstAudioMs = null
    }

    /** Allowlisted timing sample from the voice diagnostics channel; never acoustic content. */
    fun firstAudioMeasured(ms: Int) {
        if (ms in 0..120_000) pendingFirstAudioMs = ms
    }

    /** Terminal transport/audio failure of the current attempt, then the session ends. */
    fun attemptFailed(errorCode: String) {
        val session = current ?: return
        failAttempt(session, errorCode)
        endCurrent(clock())
    }

    /** Normal/local end of the logical session (user stop, timeout, provider close). */
    fun sessionEnded() = endCurrent(clock())

    /** Microphone permission was denied before audio started. */
    fun permissionDenied() {
        val session = current ?: return
        failAttempt(session, "MIC_PERMISSION_DENIED")
        endCurrent(clock())
    }

    /** A managed start blocked because policy could not be synced: no session is fabricated. */
    fun policySyncFailed(sessionId: String, attemptId: String, feature: String) {
        sink(ManagementTelemetry.connectionFailed(bootId, sequence(), build, clock(),
            sessionId, attemptId, feature, "POLICY_SYNC_FAILED"))
    }

    fun activeSessionId(): String? = current?.takeUnless { it.ended }?.id

    fun activeFeature(): String? = current?.takeUnless { it.ended }?.feature

    private fun failAttempt(session: Session, errorCode: String) {
        val attempt = session.attempt
        if (attempt.closed || attempt.connected) return
        attempt.closed = true
        sink(ManagementTelemetry.connectionFailed(bootId, sequence(), build, clock(),
            session.id, attempt.id, session.feature, errorCode))
    }

    private fun endCurrent(atMs: Long) {
        val session = current ?: return
        if (session.ended) return
        session.ended = true
        // An attempt that never connected and did not fail (local cancel during setup) has no
        // failure event; the session still closes with its observed duration.
        sink(ManagementTelemetry.sessionEnded(bootId, sequence(), build, atMs,
            session.id, session.attempt.id, session.feature,
            (atMs - session.startedAtMs).coerceAtLeast(0), observed = true))
        current = null
    }
}

/** Maps fixed engine failure strings to contract error codes; transport text is classified, never uploaded. */
object ManagementErrorCodes {
    fun fromTransportMessage(message: String): String {
        val lower = message.lowercase()
        return if ("timeout" in lower || "timed out" in lower) "CONNECTION_TIMEOUT" else "PROVIDER_UNAVAILABLE"
    }
    const val AUDIO_STARTUP = "AUDIO_STARTUP_TIMEOUT"
    const val PROVIDER = "PROVIDER_UNAVAILABLE"
}
