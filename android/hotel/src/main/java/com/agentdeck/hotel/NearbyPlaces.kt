package com.agentdeck.hotel

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

interface NearbyLookup {
    /** Blocking operation: the caller must dispatch this away from the UI thread. */
    fun lookup(latitude: Double, longitude: Double, category: String): JSONObject
}

/**
 * Small, best-effort OpenStreetMap fallback for a guest's requested nearby suggestions.
 * Queries contain only numeric coordinates and the static categories below. Public Overpass
 * capacity is shared; a failed or empty lookup must never become an invented recommendation.
 */
class NearbyPlaces(
    client: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestOverride: ((Request) -> Response)? = null
) : NearbyLookup {
    private val http = client.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private data class Cached(val time: Long, val json: String, val lifetime: Long = CACHE_MS)
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 16
    }
    private var failureCooldown: Cached? = null

    @Synchronized
    override fun lookup(latitude: Double, longitude: Double, category: String): JSONObject {
        val now = clock()
        if (!validPoint(latitude, longitude))
            return unavailable("INVALID_LOCATION", "A valid latitude and longitude are required.", now)
        val canonical = categories[category.trim().lowercase(Locale.ROOT)]
            ?: return unavailable("UNSUPPORTED_CATEGORY", "Choose food, cafe, sights, pharmacy, transport, groceries, or all.", now)

        // Avoid sending more coordinate precision than this short-distance lookup needs.
        val lat = round(latitude * 10_000) / 10_000
        val lon = round(longitude * 10_000) / 10_000
        val key = "${round(lat * 1_000) / 1_000},${round(lon * 1_000) / 1_000}:$canonical"
        cache[key]?.let { entry ->
            if (now >= entry.time && now - entry.time < entry.lifetime)
                return JSONObject(entry.json).put("cached", true)
            cache.remove(key)
        }
        failureCooldown?.let { entry ->
            if (now >= entry.time && now - entry.time < entry.lifetime)
                return JSONObject(entry.json).put("cached", true)
            failureCooldown = null
        }
        val result = try {
            val query = buildQuery(lat, lon, canonical)
            val request = Request.Builder().url(ENDPOINT)
                .header("Accept", "application/json")
                .header("User-Agent", "Manzanilla-night-reception/1.0 (+https://danlab.dev)")
                .post(FormBody.Builder().add("data", query).build())
                .build()
            val response = requestOverride?.invoke(request) ?: http.newCall(request).execute()
            response.use {
                if (it.code == 429) unavailable("RATE_LIMITED", "Nearby map lookup is busy. Try again later or ask hotel staff.", now)
                else if (!it.isSuccessful) unavailable("LOOKUP_UNAVAILABLE", "Nearby map lookup is unavailable. Ask hotel staff for current suggestions.", now)
                else {
                    val body = it.body
                    if (body == null || body.contentLength() > MAX_BODY_BYTES) {
                        unavailable("INVALID_RESPONSE", "Nearby map lookup returned an unusable response.", now)
                    } else {
                        val source = body.source()
                        source.request(MAX_BODY_BYTES + 1)
                        if (source.buffer.size > MAX_BODY_BYTES)
                            unavailable("INVALID_RESPONSE", "Nearby map lookup returned an unusable response.", now)
                        else parse(source.readUtf8(), lat, lon, canonical, now)
                    }
                }
            }
        } catch (_: Exception) {
            unavailable("LOOKUP_UNAVAILABLE", "Nearby map lookup could not be completed. Ask hotel staff for current suggestions.", now)
        }
        result.put("cached", false)
        val failure = result.isNull("fetched_at_ms")
        val entry = Cached(now, result.toString(), if (failure) FAILURE_COOLDOWN_MS else CACHE_MS)
        cache[key] = entry
        if (failure) failureCooldown = entry
        return result
    }

    private fun parse(raw: String, latitude: Double, longitude: Double, category: String, now: Long): JSONObject {
        val data = JSONObject(raw)
        if (data.optString("remark").isNotBlank())
            return unavailable("INCOMPLETE_RESPONSE", "Nearby map lookup did not finish. Ask hotel staff for current suggestions.", now)
        val elements = data.optJSONArray("elements")
            ?: return unavailable("INVALID_RESPONSE", "Nearby map lookup returned an unusable response.", now)
        val places = LinkedHashMap<String, JSONObject>()
        for (index in 0 until min(elements.length(), 512)) {
            val element = elements.optJSONObject(index) ?: continue
            val type = element.optString("type")
            val id = element.optLong("id", -1)
            if (type !in setOf("node", "way", "relation") || id <= 0) continue
            val tags = element.optJSONObject("tags") ?: continue
            val name = tags.optString("name").trim().take(160)
            if (name.isBlank()) continue
            val kind = placeCategory(tags) ?: continue
            if (category != "all" && category != kind && !(category == "food" && kind == "cafe")) continue
            val point = if (type == "node") element else element.optJSONObject("center") ?: continue
            val lat = point.optDouble("lat", Double.NaN)
            val lon = point.optDouble("lon", Double.NaN)
            if (!validPoint(lat, lon)) continue
            val distance = distanceMeters(latitude, longitude, lat, lon)
            if (distance > RADIUS_METERS) continue
            val place = JSONObject().put("name", name).put("category", kind)
                .put("latitude", lat).put("longitude", lon)
                .put("distance_meters", distance.roundToInt())
                .put("distance_kind", "straight_line_to_map_point")
                .put("source", "openstreetmap")
                .put("source_url", "https://www.openstreetmap.org/$type/$id")
                .put("opening_hours_verified", false)
            val street = listOf(tags.optString("addr:housenumber"), tags.optString("addr:street"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val address = listOf(street, tags.optString("addr:city"))
                .filter { it.isNotBlank() }.joinToString(", ").take(500)
            if (address.isNotBlank()) place.put("address", address)
            val hours = tags.optString("opening_hours").trim().take(200)
            if (hours.isNotBlank()) place.put("opening_hours_raw", hours)
            if (type != "node") place.put("map_point_is_approximate", true)
            places["$type:$id"] = place
        }
        val ranked = places.values.sortedBy { it.getInt("distance_meters") }.take(8)
        return metadata(now).put("status", if (ranked.isEmpty()) "UNAVAILABLE" else "AVAILABLE")
            .put("code", if (ranked.isEmpty()) "NO_NEARBY_PLACES" else "MAP_SUGGESTIONS")
            .put("places", JSONArray(ranked)).put("category", category)
            .put("radius_meters", RADIUS_METERS)
            .put("location", JSONObject().put("latitude", latitude).put("longitude", longitude)
                .put("coordinate_precision_decimals", 4))
            .put("message", if (ranked.isEmpty()) "No named map suggestions were found in this limited lookup. Ask hotel staff."
                else "OpenStreetMap suggestions are not exhaustive or hotel-reviewed. Opening hours are unverified; confirm before travelling. Distances are straight-line estimates.")
    }

    private fun metadata(now: Long) = JSONObject().put("source", "openstreetmap")
        .put("source_url", ENDPOINT).put("attribution", "© OpenStreetMap contributors")
        .put("attribution_url", "https://www.openstreetmap.org/copyright")
        .put("fetched_at_ms", now)

    private fun unavailable(code: String, message: String, now: Long) = JSONObject()
        .put("status", "UNAVAILABLE").put("code", code).put("message", message)
        .put("source", "openstreetmap").put("places", JSONArray())
        .put("fetched_at_ms", JSONObject.NULL).put("attempted_at_ms", now)

    private fun buildQuery(latitude: Double, longitude: Double, category: String): String {
        val filters = when (category) {
            "food" -> listOf("[amenity~\"^(restaurant|cafe|fast_food|food_court)$\"]")
            "cafe" -> listOf("[amenity=cafe]")
            "sights" -> listOf("[tourism~\"^(attraction|museum|viewpoint)$\"]", "[leisure=park]")
            "pharmacy" -> listOf("[amenity=pharmacy]")
            "transport" -> listOf("[amenity~\"^(bus_station|taxi)$\"]", "[railway=station]")
            "groceries" -> listOf("[shop~\"^(supermarket|convenience)$\"]")
            else -> listOf("[amenity~\"^(restaurant|cafe|fast_food|food_court|pharmacy|bus_station|taxi)$\"]",
                "[tourism~\"^(attraction|museum|viewpoint)$\"]", "[leisure=park]", "[railway=station]",
                "[shop~\"^(supermarket|convenience)$\"]")
        }
        return "[out:json][timeout:5][maxsize:8388608];(" + filters.joinToString("") {
            "nwr(around:$RADIUS_METERS,$latitude,$longitude)$it[name];"
        } + ");out body center 64;"
    }

    private fun placeCategory(tags: JSONObject): String? = when {
        tags.optString("amenity") == "cafe" -> "cafe"
        tags.optString("amenity") in setOf("restaurant", "fast_food", "food_court") -> "food"
        tags.optString("tourism") in setOf("attraction", "museum", "viewpoint") || tags.optString("leisure") == "park" -> "sights"
        tags.optString("amenity") == "pharmacy" -> "pharmacy"
        tags.optString("amenity") in setOf("bus_station", "taxi") || tags.optString("railway") == "station" -> "transport"
        tags.optString("shop") in setOf("supermarket", "convenience") -> "groceries"
        else -> null
    }

    private fun validPoint(latitude: Double, longitude: Double) = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latDelta = Math.toRadians(lat2 - lat1)
        val lonDelta = Math.toRadians(lon2 - lon1)
        val a = sin(latDelta / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(lonDelta / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    companion object {
        private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        private const val MAX_BODY_BYTES = 1_048_576L
        private const val RADIUS_METERS = 3_000
        private const val CACHE_MS = 5 * 60 * 1_000L
        private const val FAILURE_COOLDOWN_MS = 30 * 1_000L
        private val categories = mapOf(
            "" to "all", "all" to "all", "nearby" to "all",
            "food" to "food", "restaurant" to "food", "restaurants" to "food", "eat" to "food",
            "cafe" to "cafe", "coffee" to "cafe",
            "sights" to "sights", "tourism" to "sights", "sightseeing" to "sights", "attractions" to "sights",
            "pharmacy" to "pharmacy", "pharmacies" to "pharmacy",
            "transport" to "transport", "station" to "transport", "transit" to "transport",
            "groceries" to "groceries", "supermarket" to "groceries"
        )
    }
}
