package com.agentdeck.hotel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Owner-supplied context only. Empty configuration never becomes a demo property. */
data class NightProfile(
    val name: String = "",
    val address: String = "",
    val area: String = "",
    val openingHours: String = "",
    val facts: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val localPack: List<LocalPlace> = emptyList(),
    val availability: String = "",
    val availabilityUpdatedAtMillis: Long = 0
) {
    val localPackJson: String get() = JSONArray(localPack.map { it.toJson() }).toString()

    fun contextJson(now: Long = System.currentTimeMillis()): JSONObject = JSONObject()
        .put("hotel_name", name).put("address", address).put("area", area)
        .put("opening_hours", openingHours).put("hotel_facts", facts)
        .put("source", "owner_configuration")
        .put("room_availability", if (availability.isNotBlank() && availabilityUpdatedAtMillis > 0 &&
            availabilityUpdatedAtMillis in (now - AVAILABILITY_MAX_AGE_MS)..now)
            JSONObject().put("status", "provided").put("information", availability)
                .put("updated_at_ms", availabilityUpdatedAtMillis)
            else JSONObject().put("status", "unknown"))
        .put("hotel_location", coordinatesJson() ?: JSONObject.NULL)

    fun coordinatesJson(): JSONObject? = if (validCoordinates(latitude, longitude)) JSONObject()
        .put("latitude", latitude).put("longitude", longitude).put("source", "hotel_configuration")
        .put("status", "available") else null

    companion object {
        const val AVAILABILITY_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        fun validCoordinates(latitude: Double?, longitude: Double?) = latitude != null && longitude != null &&
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

        fun parse(value: JSONObject): NightProfile {
            fun text(key: String, max: Int): String = value.optString(key).trim().also {
                require(it.length <= max) { "Hotel configuration is too long" }
            }
            val lat = if (value.has("latitude") && !value.isNull("latitude")) value.getDouble("latitude") else null
            val lon = if (value.has("longitude") && !value.isNull("longitude")) value.getDouble("longitude") else null
            require((lat == null && lon == null) || validCoordinates(lat, lon)) { "Enter a valid hotel latitude and longitude" }
            val places = value.optJSONArray("local_pack") ?: JSONArray()
            require(places.length() <= 100) { "The local guide supports at most 100 reviewed places" }
            return NightProfile(text("name", 160), text("address", 500), text("area", 250),
                text("opening_hours", 1000), text("facts", 12000), lat, lon,
                (0 until places.length()).map { LocalPlace.parse(places.getJSONObject(it)) },
                text("availability", 1000), value.optLong("availability_updated_at_ms", 0).also { require(it >= 0) })
        }
    }
}

data class LocalPlace(
    val name: String,
    val category: String,
    val address: String,
    val summary: String,
    val source: String,
    val reviewedAtMillis: Long,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("category", category)
        .put("address", address).put("summary", summary).put("source", source)
        .put("reviewed_at_ms", reviewedAtMillis).apply {
            if (latitude != null) put("latitude", latitude)
            if (longitude != null) put("longitude", longitude)
        }

    fun current(now: Long): Boolean = reviewedAtMillis > 0 && reviewedAtMillis <= now &&
        now - reviewedAtMillis <= MAX_REVIEW_AGE_MS && name.isNotBlank() && source.isNotBlank()

    companion object {
        const val MAX_REVIEW_AGE_MS = 30 * 24 * 60 * 60 * 1000L
        fun parse(value: JSONObject): LocalPlace {
            fun text(key: String, max: Int): String = value.optString(key).trim().also { require(it.length <= max) }
            val lat = if (value.has("latitude") && !value.isNull("latitude")) value.getDouble("latitude") else null
            val lon = if (value.has("longitude") && !value.isNull("longitude")) value.getDouble("longitude") else null
            require((lat == null && lon == null) || NightProfile.validCoordinates(lat, lon))
            val name = text("name", 160); val source = text("source", 500)
            require(name.isNotBlank() && source.isNotBlank()) { "A local suggestion needs a name and source" }
            return LocalPlace(name, text("category", 80), text("address", 500), text("summary", 1500),
                source, value.getLong("reviewed_at_ms").also { require(it > 0) }, lat, lon)
        }
    }
}

class NightProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("night_reception_profile", Context.MODE_PRIVATE)
    fun load(): NightProfile = try { NightProfile.parse(JSONObject(preferences.getString("profile", "{}") ?: "{}")) }
        catch (_: Exception) { NightProfile() }

    fun save(name: String, address: String, area: String, openingHours: String, facts: String,
             latitude: Double?, longitude: Double?, localPackJson: String = "[]",
             availability: String = "", availabilityUpdatedAtMillis: Long = 0): Boolean = try {
        save(JSONObject().put("name", name).put("address", address).put("area", area)
            .put("opening_hours", openingHours).put("facts", facts)
            .put("latitude", latitude ?: JSONObject.NULL).put("longitude", longitude ?: JSONObject.NULL)
            .put("local_pack", JSONArray(localPackJson)).put("availability", availability)
            .put("availability_updated_at_ms", availabilityUpdatedAtMillis))
    } catch (_: Exception) { false }

    fun save(value: JSONObject): Boolean = try {
        // Validate before writing so failed edits preserve the working owner configuration.
        val parsed = NightProfile.parse(value)
        val stored = JSONObject().put("name", parsed.name).put("address", parsed.address)
            .put("area", parsed.area).put("opening_hours", parsed.openingHours).put("facts", parsed.facts)
            .put("latitude", parsed.latitude ?: JSONObject.NULL).put("longitude", parsed.longitude ?: JSONObject.NULL)
            .put("local_pack", JSONArray(parsed.localPack.map { it.toJson() }))
            .put("availability", parsed.availability).put("availability_updated_at_ms", parsed.availabilityUpdatedAtMillis)
        preferences.edit().putString("profile", stored.toString()).commit()
    } catch (_: Exception) { false }
}
