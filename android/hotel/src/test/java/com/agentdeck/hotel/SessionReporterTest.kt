package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReporterTest {
    private fun reporter(sink: (JSONObject) -> Unit): Pair<SessionReporter, MutableList<JSONObject>> {
        val events = mutableListOf<JSONObject>()
        var now = 1_000_000L
        var seq = 0
        val reporter = SessionReporter("boot-1", "17.0.45",
            clock = { now }, sequence = { seq++ }, sink = { events.add(it); sink(it) })
        return reporter to events
    }

    @Test fun `connected session emits started connected ended in order`() {
        val sink: (JSONObject) -> Unit = {}
        val (reporter, events) = reporter(sink)
        reporter.beginSession("s1", "translation", "a1")
        reporter.firstAudioMeasured(800)
        reporter.attemptListening()
        reporter.sessionEnded()
        assertEquals(listOf("session.started", "session.connected", "session.ended"), events.map { it.getString("kind") })
        assertEquals(800, events[1].getInt("firstAudioMs"))
        assertTrue(events[2].getLong("durationMs") >= 0)
        assertEquals("observed", events[2].getString("durationProvenance"))
    }

    @Test fun `failed attempt emits connection failed then session ended`() {
        val (reporter, events) = reporter {}
        reporter.beginSession("s1", "translation", "a1")
        reporter.attemptFailed("PROVIDER_UNAVAILABLE")
        assertEquals(listOf("session.started", "connection.failed", "session.ended"), events.map { it.getString("kind") })
        assertEquals("PROVIDER_UNAVAILABLE", events[1].getString("code"))
    }

    @Test fun `reconnect creates new attempt with fresh id`() {
        val (reporter, events) = reporter {}
        reporter.beginSession("s1", "translation", "a1")
        val a2 = reporter.attemptReconnecting("CONNECTION_TIMEOUT")
        reporter.attemptListening()
        assertEquals(listOf("session.started", "connection.failed", "session.connected"), events.map { it.getString("kind") })
        assertEquals(a2, events[2].getString("attemptId"))
        assertTrue(a2 != "a1" && a2.isNotEmpty())
    }

    @Test fun `permission denied closes attempt and session`() {
        val (reporter, events) = reporter {}
        reporter.beginSession("s1", "night", "a1")
        reporter.permissionDenied()
        assertEquals(listOf("session.started", "connection.failed", "session.ended"), events.map { it.getString("kind") })
        assertEquals("MIC_PERMISSION_DENIED", events[1].getString("code"))
    }

    @Test fun `policy blocked start emits only connection failed`() {
        val (reporter, events) = reporter {}
        reporter.policySyncFailed("s1", "a1", "translation")
        assertEquals(1, events.size)
        assertEquals("connection.failed", events[0].getString("kind"))
        assertEquals("POLICY_SYNC_FAILED", events[0].getString("code"))
        assertNull(reporter.activeSessionId())
    }

    @Test fun `user stop before connect emits no failure event`() {
        val (reporter, events) = reporter {}
        reporter.beginSession("s1", "translation", "a1")
        reporter.sessionEnded()
        assertEquals(listOf("session.started", "session.ended"), events.map { it.getString("kind") })
    }
}
