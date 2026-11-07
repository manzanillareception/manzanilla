package com.agentdeck.hotel

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NearbyPlacesTest {
    private val start = 10_000_000L
    private fun node(id: Int, lat: Double = 40.001, name: String = "Synthetic cafe $id") = JSONObject()
        .put("type", "node").put("id", id).put("lat", lat).put("lon", -3.0)
        .put("tags", JSONObject().put("name", name).put("amenity", "cafe"))
    private fun payload(vararg elements: JSONObject) = JSONObject().put("elements", JSONArray(elements.toList())).toString()
    private fun response(request: Request, body: String, code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic response")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun staticCategoryQueryUsesOnlyFixedHttpsProviderAndRoundedCoordinates() {
        var sent: Request? = null
        val nearby = NearbyPlaces(clock = { start }, requestOverride = {
            sent = it; response(it, payload(node(1)))
        })
        val result = nearby.lookup(40.0000123, -3.0000123, " Restaurants ")
        assertEquals("AVAILABLE", result.getString("status"))
        assertEquals("https://overpass-api.de/api/interpreter", sent!!.url.toString())
        assertEquals("POST", sent!!.method)
        val form = sent!!.body as FormBody
        assertEquals("data", form.name(0))
        val query = form.value(0)
        assertTrue(query.contains("around:3000,40.0,-3.0"))
        assertTrue(query.contains("[timeout:5]"))
        assertTrue(query.endsWith("out body center 64;"))
        assertFalse(query.contains("Restaurants"))
        assertFalse(query.contains("40.0000123"))
        assertEquals("© OpenStreetMap contributors", result.getString("attribution"))
        assertEquals(start, result.getLong("fetched_at_ms"))
    }

    @Test fun invalidCoordinatesOrUnlistedGuestCategoryNeverPerformHttp() {
        var calls = 0
        val nearby = NearbyPlaces(requestOverride = { calls++; response(it, payload(node(1))) })
        for ((lat, lon) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY, 90.1 to 0.0, 0.0 to -180.1)) {
            assertEquals("INVALID_LOCATION", nearby.lookup(lat, lon, "all").getString("code"))
        }
        for (category in listOf("https://guest.invalid/", "all];out;", "free text night market")) {
            assertEquals("UNSUPPORTED_CATEGORY", nearby.lookup(40.0, -3.0, category).getString("code"))
        }
        assertEquals(0, calls)
    }

    @Test fun onlyNamedMatchingActualMapPointsWithinRadiusAreReturnedAndCapped() {
        val elements = (1..12).map { node(it, 40.0 + it * 0.001) }.toMutableList()
        elements += node(20, 42.0)
        elements += node(21, name = " ")
        elements += node(22).put("lat", 900)
        elements += node(23).put("type", "arbitrary")
        elements += node(24).put("tags", JSONObject().put("name", "Synthetic unrelated").put("shop", "clothes"))
        val way = JSONObject().put("type", "way").put("id", 25)
            .put("center", JSONObject().put("lat", 40.0001).put("lon", -3.0))
            .put("tags", JSONObject().put("name", "Synthetic centered cafe").put("amenity", "cafe"))
        elements += way
        elements += elements[0]
        val nearby = NearbyPlaces(clock = { start }, requestOverride = { response(it, payload(*elements.toTypedArray())) })
        val result = nearby.lookup(40.0, -3.0, "cafe")
        val places = result.getJSONArray("places")
        assertEquals(8, places.length())
        assertEquals("Synthetic centered cafe", places.getJSONObject(0).getString("name"))
        assertTrue(places.getJSONObject(0).getBoolean("map_point_is_approximate"))
        assertEquals("https://www.openstreetmap.org/way/25", places.getJSONObject(0).getString("source_url"))
        for (index in 1 until places.length()) assertTrue(places.getJSONObject(index - 1).getInt("distance_meters") <= places.getJSONObject(index).getInt("distance_meters"))
        assertFalse(result.toString().contains("Synthetic unrelated"))
    }

    @Test fun mapOpeningHoursRemainUnverifiedWithoutAnOpenNowClaim() {
        val place = node(1)
        place.getJSONObject("tags").put("opening_hours", "24/7")
            .put("addr:housenumber", "12").put("addr:street", "Synthetic Road")
        val nearby = NearbyPlaces(requestOverride = { response(it, payload(place)) })
        val result = nearby.lookup(40.0, -3.0, "coffee").getJSONArray("places").getJSONObject(0)
        assertEquals("24/7", result.getString("opening_hours_raw"))
        assertFalse(result.getBoolean("opening_hours_verified"))
        assertEquals("12 Synthetic Road", result.getString("address"))
        assertFalse(result.has("open_now"))
    }

    @Test fun redirectsRateLimitsFailuresAndIncompleteResponsesHaveNoSuggestions() {
        val cases = listOf(302 to payload(node(1)), 429 to payload(node(1)), 503 to "private provider error",
            200 to "not json", 200 to "{}", 200 to JSONObject().put("remark", "timeout").put("elements", JSONArray().put(node(1))).toString())
        for ((code, body) in cases) {
            val nearby = NearbyPlaces(requestOverride = { response(it, body, code) })
            val result = nearby.lookup(40.0, -3.0, "all")
            assertEquals("UNAVAILABLE", result.getString("status"))
            assertEquals(0, result.getJSONArray("places").length())
            assertFalse(result.toString().contains("private provider error"))
        }
        val thrown = NearbyPlaces(requestOverride = { throw IOException("Synthetic failure") })
        assertEquals("UNAVAILABLE", thrown.lookup(40.0, -3.0, "all").getString("status"))
    }

    @Test fun bodyLargerThanOneMebibyteIsRejected() {
        val nearby = NearbyPlaces(requestOverride = { response(it, " ".repeat(1_048_577)) })
        assertEquals("INVALID_RESPONSE", nearby.lookup(40.0, -3.0, "all").getString("code"))
    }

    @Test fun cachedResponsesExpireAfterFiveMinutesAndCannotBeMutatedByCallers() {
        var now = start
        var calls = 0
        val nearby = NearbyPlaces(clock = { now }, requestOverride = { calls++; response(it, payload(node(1))) })
        val initial = nearby.lookup(40.0, -3.0, "cafe")
        initial.getJSONArray("places").getJSONObject(0).put("name", "Mutated")
        now += 299_999
        val cached = nearby.lookup(40.0004, -3.0004, "coffee")
        assertEquals(1, calls)
        assertTrue(cached.getBoolean("cached"))
        assertEquals("Synthetic cafe 1", cached.getJSONArray("places").getJSONObject(0).getString("name"))
        assertEquals(start, cached.getLong("fetched_at_ms"))
        now++
        assertFalse(nearby.lookup(40.0, -3.0, "cafe").getBoolean("cached"))
        assertEquals(2, calls)
    }

    @Test fun failuresPauseAllProviderRequestsForThirtySecondsWithoutInventingData() {
        var now = start
        var calls = 0
        val nearby = NearbyPlaces(clock = { now }, requestOverride = { calls++; response(it, "", 429) })
        nearby.lookup(40.0, -3.0, "cafe")
        now += 29_999
        val paused = nearby.lookup(40.01, -3.02, "pharmacy")
        assertEquals(1, calls)
        assertEquals("RATE_LIMITED", paused.getString("code"))
        assertTrue(paused.isNull("fetched_at_ms"))
        assertEquals(0, paused.getJSONArray("places").length())
        now++
        nearby.lookup(40.0, -3.0, "cafe")
        assertEquals(2, calls)
    }

    @Test fun cacheKeepsAtMostSixteenLocationsAndClockRollbackCannotMakeStaleDataFresh() {
        var now = start
        var calls = 0
        val nearby = NearbyPlaces(clock = { now }, requestOverride = { calls++; response(it, "{\"elements\":[]}") })
        for (index in 0..16) nearby.lookup(40.0 + index * 0.01, -3.0, "all")
        nearby.lookup(40.0, -3.0, "all")
        assertEquals(18, calls)
        now--
        nearby.lookup(40.0, -3.0, "all")
        assertEquals(19, calls)
    }

    @Test fun allCategoryAliasesAreStaticAndUnknownDoesNotBecomeAll() {
        val queries = mutableListOf<String>()
        val nearby = NearbyPlaces(requestOverride = {
            queries += (it.body as FormBody).value(0)
            response(it, "{\"elements\":[]}")
        })
        for (category in listOf("", "restaurant", "cafe", "tourism", "pharmacy", "transit", "supermarket"))
            nearby.lookup(40.0, -3.0, category)
        assertEquals(7, queries.size)
        assertTrue(queries[3].contains("[tourism~"))
        assertTrue(queries[4].contains("[amenity=pharmacy]"))
        assertTrue(queries[5].contains("[railway=station]"))
        assertTrue(queries[6].contains("[shop~"))
        assertEquals("UNSUPPORTED_CATEGORY", nearby.lookup(40.0, -3.0, "anything").getString("code"))
        assertEquals(7, queries.size)
    }
}
