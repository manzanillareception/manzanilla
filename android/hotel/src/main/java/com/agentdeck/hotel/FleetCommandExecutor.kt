package com.agentdeck.hotel

import org.json.JSONObject

/** Durable native effect boundary, independent of Android so disconnect/crash cases are testable. */
internal class FleetCommandExecutor(
    private val ledger: FleetLedger,
    private val send: (FleetCommand, JSONObject) -> Unit,
    private val current: () -> Boolean,
    private val busy: () -> Boolean,
    private val effect: (FleetCommand) -> FleetEffect
) {
    fun handle(command: FleetCommand) {
        val previous = ledger.receipt(command.id)
        require(previous == null || (previous.optString("type") == command.type &&
            previous.optLong("expires_at_ms") == command.expiresAtMillis)) { "Command identity changed" }
        if (previous?.optString("state") in FleetLedger.TERMINAL) {
            send(command, previous!!)
            return
        }
        if (previous?.optString("state") == "running") {
            ledger.receipt(command, "reconciliation_required", "interrupted")
            send(command, ledger.receipt(command.id)!!)
            return
        }
        if (previous == null || previous.optString("state") == "received") {
            ledger.receipt(command, "received", "received")
            send(command, ledger.receipt(command.id)!!)
        }
        if (busy()) {
            ledger.receipt(command, "deferred", "voice_active")
            send(command, ledger.receipt(command.id)!!)
            return
        }
        if (!current()) return
        // Claim locally and obtain the server's running acknowledgment before an effect.
        // If this request or the process dies, later delivery requires reconciliation.
        ledger.receipt(command, "running", "running")
        send(command, ledger.receipt(command.id)!!)
        if (!current()) return
        val result = effect(command)
        ledger.receipt(command, if (result.completed) "succeeded" else "deferred",
            if (result.completed) result.code else "device_busy")
        send(command, ledger.receipt(command.id)!!)
    }
}

internal data class FleetEffect(val completed: Boolean, val code: String)
