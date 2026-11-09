package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementTelemetryTest {
    private fun event(kind: String, id: String) =
        JSONObject().put("eventId", id).put("kind", kind).put("occurredAt", "2026-11-10T00:00:00Z")

    @Test fun `envelope has schema version 1 and event list`() {
        val envelope = ManagementTelemetry.envelope(listOf(event("session.started", "e1")))
        assertEquals(1, envelope.getInt("schemaVersion"))
        assertEquals(1, envelope.getJSONArray("events").length())
    }

    @Test fun `batch respects the 100 event limit`() {
        val queue = (1..150).map { event("session.started", "e$it") }
        assertEquals(100, ManagementTelemetry.pickBatch(queue, null).size)
    }

    @Test fun `batch respects the byte budget`() {
        val queue = (1..500).map { event("session.started", "e$it") }
        val batch = ManagementTelemetry.pickBatch(queue, null)
        assertTrue(ManagementTelemetry.serializedBytes(ManagementTelemetry.envelope(batch)) <= ManagementTelemetry.MAX_BATCH_BYTES)
    }

    @Test fun `blocked filter keeps only policy ack and diagnostic`() {
        val queue = listOf(
            event("session.started", "e1"), event("policy.ack", "e2"),
            event("diagnostic.snapshot", "e3"), event("connection.failed", "e4"))
        val batch = ManagementTelemetry.pickBatch(queue, ManagementTelemetry.BLOCKED_ALLOWED)
        assertEquals(listOf("e2", "e3"), batch.map { it.getString("eventId") })
    }

    @Test fun `event timestamps are ISO offset not epoch`() {
        val e = ManagementTelemetry.sessionStarted("b", 1, "17.0.45", 1_762_752_000_000L, "s", "a", "translation")
        assertTrue(e.getString("occurredAt").contains("T"))
        assertFalse(e.has("payload"))
        assertFalse(e.has("guestText"))
    }
}
