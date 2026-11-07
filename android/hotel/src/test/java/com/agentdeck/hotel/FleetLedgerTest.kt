package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FleetLedgerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun committedEffectsSurviveReopenAndCannotRunAgain() {
        val path = File(temporary.root, "ledger.json")
        val first = FleetLedger(path)
        val command = FleetCommand("cmd_1", "check_update", 100_000)
        first.receipt(command, "running", "running", 1)
        val recovered = FleetLedger(path)
        assertEquals("running", recovered.receipt("cmd_1")!!.getString("state"))
        // A process that lost the effect result records an explicit uncertain outcome.
        recovered.receipt(command, "reconciliation_required", "interrupted", 2)
        assertThrows(IllegalArgumentException::class.java) { recovered.receipt(command, "running", "running", 3) }
        assertEquals("reconciliation_required", FleetLedger(path).receipt("cmd_1")!!.getString("state"))
    }
    @Test fun telemetryEvictionDoesNotDiscardAnUnexpiredCommandReceipt() {
        val path = File(temporary.root, "ledger.json")
        val ledger = FleetLedger(path)
        ledger.receipt(FleetCommand("cmd_1", "diagnostics", Long.MAX_VALUE), "succeeded", "diagnostics_queued", 1)
        repeat(FleetProtocol.MAX_EVENTS + 5) { ledger.enqueue("voice_timing", JSONObject().put("native_audio_start_ms", it), it.toLong()) }
        val reopened = FleetLedger(path)
        assertEquals(FleetProtocol.MAX_EVENTS, reopened.events().length())
        assertEquals(5, reopened.events().getJSONObject(0).getJSONObject("metrics").getInt("native_audio_start_ms"))
        assertEquals("succeeded", reopened.receipt("cmd_1")!!.getString("state"))
    }
    @Test fun uploadAcknowledgmentsRemoveOnlyTheEventsActuallySent() {
        val ledger = FleetLedger(File(temporary.root, "ledger.json"))
        ledger.enqueue("app_start", JSONObject(), 1)
        val sent = ledger.events().getJSONObject(0).getString("id")
        ledger.enqueue("diagnostics", JSONObject().put("charging", true), 2)
        ledger.acknowledgedEvents(setOf(sent))
        assertEquals(1, ledger.events().length())
        assertEquals("diagnostics", ledger.events().getJSONObject(0).getString("type"))
    }
    @Test fun staleConfigurationCannotOverwriteNewerAcknowledgedState() {
        val path = File(temporary.root, "ledger.json")
        val ledger = FleetLedger(path)
        ledger.saveConfig(3, JSONObject().put("name", "Fixture hotel"))
        assertThrows(IllegalArgumentException::class.java) { ledger.saveConfig(2, JSONObject()) }
        assertEquals(3, FleetLedger(path).configVersion())
    }
    @Test fun failedCommitDoesNotInventSuccessfulMetadataAndCorruptionIsNotAnEmptyLedger() {
        val path = File(temporary.root, "ledger.json")
        val ledger = FleetLedger(path)
        ledger.saveConfig(2, JSONObject())
        File(temporary.root, "ledger.json.new").mkdir()
        assertThrows(Exception::class.java) { ledger.saveConfig(3, JSONObject()) }
        assertEquals(2, FleetLedger(path).configVersion())
        path.writeText("corrupt")
        assertThrows(Exception::class.java) { FleetLedger(path).configVersion() }
    }
    @Test fun reporterGenerationIsMonotonicAcrossReopenInstancesAndOtherMetadataWrites() {
        val path = File(temporary.root, "ledger.json")
        val first = FleetLedger(path)
        assertEquals(1L, first.nextBootGeneration())
        assertEquals(2L, FleetLedger(path).nextBootGeneration())
        first.enqueue("app_start", JSONObject(), 1)
        first.saveConfig(2, JSONObject())
        first.receipt(FleetCommand("cmd_1", "diagnostics", Long.MAX_VALUE), "received", "received", 1)
        assertEquals(3L, first.nextBootGeneration())
        assertEquals(4L, FleetLedger(path).nextBootGeneration())
    }
    @Test fun generationCommitFailureReturnsNoValueForTransmissionAndDoesNotConsumeACounter() {
        val path = File(temporary.root, "ledger.json")
        val ledger = FleetLedger(path)
        assertEquals(1L, ledger.nextBootGeneration())
        val blocked = File(temporary.root, "ledger.json.new").apply { mkdir() }
        var transmitted = false
        assertThrows(Exception::class.java) {
            ledger.nextBootGeneration()
            transmitted = true // The caller cannot reach its POST after a failed commit.
        }
        assertFalse(transmitted)
        assertEquals(1L, JSONObject(path.readText()).getLong("boot_generation"))
        assertTrue(blocked.delete())
        assertEquals(2L, FleetLedger(path).nextBootGeneration())
    }
    @Test fun generationCannotWrapOrCoerceUntrustedStoredValues() {
        val path = File(temporary.root, "ledger.json")
        for (value in listOf("-1", "1.5", "\"2\"", FleetLedger.MAX_BOOT_GENERATION.toString())) {
            path.writeText("{\"boot_generation\":$value}")
            assertThrows(IllegalArgumentException::class.java) { FleetLedger(path).nextBootGeneration() }
        }
    }
}
