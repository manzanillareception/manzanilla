package com.agentdeck.hotel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FleetProtocolTest {
    @Test fun credentialsOnlyGoToAnExplicitHttpsOrigin() {
        assertEquals("https://example.test", FleetProtocol.base(" https://example.test/ "))
        for (url in listOf("http://example.test", "https://user:password@example.test", "https://example.test/api",
            "https://example.test?token=secret", "https://example.test/#secret")) {
            assertThrows(IllegalArgumentException::class.java) { FleetProtocol.base(url) }
        }
    }
    @Test fun diagnosticBoundaryDropsAllContentAndUnknownNumericFields() {
        val result = FleetProtocol.event("voice_timing", JSONObject().put("native_audio_start_ms", 17)
            .put("queued_audio_ms", -10).put("microphone_permission", true).put("charging", "private-value")
            .put("text", "private-speech").put("key", "secret-marker").put("guest_name", "private-name")
            .put("room_number", 203).put("arbitrary_number", 123), "event_1", 1700000000000)
        val metrics = result.getJSONObject("metrics")
        assertEquals(2, metrics.length())
        assertEquals(17, metrics.getInt("native_audio_start_ms"))
        assertTrue(metrics.getBoolean("microphone_permission"))
        assertFalse(result.toString().contains("private"))
        assertFalse(result.toString().contains("secret-marker"))
        assertEquals(1700000000.0, result.getDouble("occurred_at"), 0.0)
    }
    @Test fun unknownOrMutedMicrophoneNeverClaimsRecorderRelease() {
        assertEquals("on", FleetProtocol.mic("muted"))
        assertEquals("on", FleetProtocol.mic("recording"))
        assertEquals("unknown", FleetProtocol.mic("unexpected"))
        assertEquals("stopping", FleetProtocol.mic("stopping"))
        assertEquals("off", FleetProtocol.mic("off"))
    }
    @Test fun publicConfigIsStrictAndPartialUpdatesPreserveApprovedFacts() {
        val old = NightProfile(name = "Fixture hotel", facts = "Breakfast at seven", area = "Fixture district")
        val update = FleetProtocol.publicProfile(JSONObject().put("name", "Renamed fixture"), old)
        assertEquals("Renamed fixture", update.getString("name"))
        assertEquals("Breakfast at seven", update.getString("facts"))
        assertEquals("Fixture district", update.getString("area"))
        assertThrows(IllegalArgumentException::class.java) {
            FleetProtocol.publicProfile(JSONObject().put("api_key", "secret-marker"), old)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FleetProtocol.publicProfile(JSONObject().put("facts", JSONObject().put("text", "nested")), old)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FleetProtocol.publicProfile(JSONObject().put("latitude", 91).put("longitude", 2), old)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FleetProtocol.publicProfile(JSONObject().put("default_language", "en")
                .put("supported_languages", JSONArray().put("es")), old)
        }
    }
    @Test fun commandBoundaryRejectsExpiredUnknownOrParameterizedEffects() {
        fun command() = JSONObject().put("id", "cmd_1").put("type", "check_update")
            .put("arguments", JSONObject()).put("expires_at", 2000)
        assertEquals("check_update", FleetProtocol.command(command(), 1000_000).type)
        assertThrows(IllegalArgumentException::class.java) { FleetProtocol.command(command(), 2000_000) }
        assertThrows(IllegalArgumentException::class.java) { FleetProtocol.command(command().put("type", "start_microphone"), 1000_000) }
        assertThrows(IllegalArgumentException::class.java) {
            FleetProtocol.command(command().put("arguments", JSONObject().put("url", "https://example.test/evil.apk")), 1000_000)
        }
        assertThrows(IllegalArgumentException::class.java) { FleetProtocol.command(command().put("secret", "value"), 1000_000) }
        assertThrows(IllegalArgumentException::class.java) { FleetProtocol.command(command().put("id", "../other"), 1000_000) }
    }
    @Test fun explicitLocationRemovalAppliesAndUnsupportedSettingsCannotBeAcknowledged() {
        val profile = NightProfile(name = "Fixture", latitude = 41.0, longitude = 2.0)
        val cleared = FleetProtocol.publicProfile(JSONObject().put("latitude", JSONObject.NULL)
            .put("longitude", JSONObject.NULL), profile)
        assertTrue(cleared.isNull("latitude"))
        assertTrue(cleared.isNull("longitude"))
        assertNull(NightProfile.parse(cleared).coordinatesJson())
        for (field in listOf("timezone", "default_language", "supported_languages")) {
            assertThrows(IllegalArgumentException::class.java) {
                FleetProtocol.publicProfile(JSONObject().put(field, if (field == "supported_languages") JSONArray().put("en") else "en"), profile)
            }
        }
    }
}
