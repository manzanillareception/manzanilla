package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FleetCommandExecutorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val command get() = FleetCommand("cmd_1", "check_update", Long.MAX_VALUE)

    @Test fun acknowledgedRunningPrecedesEffectAndDuplicateDeliveryOnlyReplaysReceipt() {
        val ledger = FleetLedger(File(temporary.root, "ledger"))
        val order = ArrayList<String>()
        var effects = 0
        val executor = FleetCommandExecutor(ledger, { _, result -> order.add(result.getString("state")) },
            { true }, { false }, {
                assertEquals("running", ledger.receipt(it.id)!!.getString("state"))
                assertEquals("running", order.last())
                effects++; order.add("effect"); FleetEffect(true, "update_check_requested")
            })
        executor.handle(command)
        assertEquals(listOf("received", "running", "effect", "succeeded"), order)
        executor.handle(command)
        assertEquals(1, effects)
        assertEquals("succeeded", order.last())
    }
    @Test fun busyDeferralIsAcknowledgedBeforeLaterRunningWithoutRegressingToReceived() {
        val ledger = FleetLedger(File(temporary.root, "ledger"))
        val order = ArrayList<String>()
        var busy = true
        var effects = 0
        val executor = FleetCommandExecutor(ledger, { _, result -> order.add(result.getString("state")) },
            { true }, { busy }, { effects++; FleetEffect(true, "update_check_requested") })
        executor.handle(command)
        assertEquals(listOf("received", "deferred"), order)
        assertEquals(0, effects)
        busy = false
        executor.handle(command)
        assertEquals(listOf("received", "deferred", "running", "succeeded"), order)
        assertEquals(1, effects)
    }
    @Test fun lostRunningAcknowledgmentRequiresReconciliationAndNeverExecutesEffect() {
        val path = File(temporary.root, "ledger")
        var effects = 0
        val first = FleetCommandExecutor(FleetLedger(path), { _, result ->
            if (result.getString("state") == "running") throw java.io.IOException("Synthetic lost reply")
        }, { true }, { false }, { effects++; FleetEffect(true, "update_check_requested") })
        assertThrows(java.io.IOException::class.java) { first.handle(command) }
        val replies = ArrayList<String>()
        val recovered = FleetCommandExecutor(FleetLedger(path), { _, result -> replies.add(result.getString("state")) },
            { true }, { false }, { effects++; FleetEffect(true, "update_check_requested") })
        recovered.handle(command)
        assertEquals(listOf("reconciliation_required"), replies)
        assertEquals(0, effects)
    }
    @Test fun lostFinalReplyAfterEffectReplaysCommittedSuccessWithoutRepeatingEffect() {
        val path = File(temporary.root, "ledger")
        var effects = 0
        val first = FleetCommandExecutor(FleetLedger(path), { _, result ->
            if (result.getString("state") == "succeeded") throw java.io.IOException("Synthetic lost final reply")
        }, { true }, { false }, { effects++; FleetEffect(true, "update_check_requested") })
        assertThrows(java.io.IOException::class.java) { first.handle(command) }
        val replies = ArrayList<String>()
        FleetCommandExecutor(FleetLedger(path), { _, result -> replies.add(result.getString("state")) },
            { true }, { false }, { effects++; FleetEffect(true, "update_check_requested") }).handle(command)
        assertEquals(1, effects)
        assertEquals(listOf("succeeded"), replies)
    }
}
