package com.agentdeck.hotel

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventQueueStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun event(id: String, kind: String = "session.started") =
        JSONObject().put("eventId", id).put("kind", kind).put("occurredAt", "2026-11-10T00:00:00Z")

    @Test fun `append persists across instances and remove deletes`() {
        val file = File(folder.root, "queue.jsonl")
        EventQueueStore(file).apply { append(event("a")); append(event("b")) }
        val reloaded = EventQueueStore(file)
        assertEquals(listOf("a", "b"), reloaded.events().map { it.getString("eventId") })
        reloaded.remove(setOf("a"))
        assertEquals(listOf("b"), EventQueueStore(file).events().map { it.getString("eventId") })
    }

    @Test fun `bounded by count dropping oldest`() {
        val file = File(folder.root, "queue.jsonl")
        val queue = EventQueueStore(file, maxEvents = 5)
        repeat(8) { queue.append(event("e$it")) }
        assertEquals(listOf("e3", "e4", "e5", "e6", "e7"), queue.events().map { it.getString("eventId") })
    }

    @Test fun `bounded by bytes dropping oldest`() {
        val file = File(folder.root, "queue.jsonl")
        val queue = EventQueueStore(file, maxEvents = 1_000, maxBytes = 400)
        repeat(20) { queue.append(event("event-$it")) }
        val ids = queue.events().map { it.getString("eventId") }
        assertTrue(ids.size < 20)
        assertEquals("event-19", ids.last())
        assertTrue(queue.droppedOldest > 0)
    }

    @Test fun `corrupt lines are skipped`() {
        val file = File(folder.root, "queue.jsonl")
        file.writeText("{broken\n" + event("ok").toString() + "\n")
        assertEquals(1, EventQueueStore(file).size())
    }
}
