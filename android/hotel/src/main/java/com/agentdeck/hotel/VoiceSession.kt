package com.agentdeck.hotel

import org.json.JSONObject

/** One immutable guest's ownership. Closing fences producers before resource teardown starts. */
internal class VoiceSession(
    val id: String,
    val generation: Int,
    val guest: String,
    val staff: String,
    val mode: String,
    private val push: (JSONObject) -> Unit
) {
    private enum class State { ACTIVE, STOPPING, CLOSED }
    private var state = State.ACTIVE
    private var ready = false
    private var capturing = false
    private val stopped = ArrayList<() -> Unit>()

    @Synchronized fun active(): Boolean = state == State.ACTIVE
    @Synchronized fun use(block: () -> Unit): Boolean {
        if (state != State.ACTIVE) return false
        block()
        return true
    }
    @Synchronized fun emit(event: JSONObject) {
        if (state == State.ACTIVE) push(scoped(event))
    }
    fun scoped(event: JSONObject): JSONObject = event.put("session_id", id).put("generation", generation)
    fun phase(phase: String, text: String? = null) {
        val event = JSONObject().put("type", "reception_event").put("phase", phase)
        if (text != null) event.put("text", text)
        emit(event)
    }
    @Synchronized fun ready(open: () -> Unit) { ready = true; capture(open) }
    private fun capture(open: () -> Unit) {
        if (state == State.ACTIVE && ready && !capturing) {
            capturing = true
            open()
        }
    }
    /** Returns true only to the caller responsible for teardown. Every caller gets an acknowledgement. */
    @Synchronized fun close(onStopped: () -> Unit = {}): Boolean {
        if (state == State.CLOSED) { onStopped(); return false }
        stopped.add(onStopped)
        if (state == State.STOPPING) return false
        state = State.STOPPING
        return true
    }
    /** Called only after recorder, playback and transport have detached (or release failure is known). */
    fun detached(audioOff: Boolean, terminal: JSONObject? = null) {
        val callbacks: List<() -> Unit>
        synchronized(this) {
            if (state == State.CLOSED) return
            check(state == State.STOPPING)
            state = State.CLOSED
            push(scoped(JSONObject().put("type", "audio_state").put("state", if (audioOff) "off" else "unknown")))
            if (terminal != null) push(scoped(terminal))
            callbacks = stopped.toList()
            stopped.clear()
        }
        callbacks.forEach { it() }
    }
}
