package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class NightToolsTest {
    private val now = 100L * 24 * 60 * 60 * 1000
    private val direct = Executor { it.run() }
    private class Location(private val present: Boolean = true) : NightLocationProvider {
        var requests = 0
        override fun request(callback: (JSONObject) -> Unit) {
            requests++
            callback(if (present) JSONObject().put("status", "available").put("latitude", 40.0)
                .put("longitude", -3.0).put("source", "hotel_configuration")
                else JSONObject().put("status", "unavailable").put("reason", "permission_not_granted"))
        }
    }
    private class Taxi(var now: Long) : TaxiGateway {
        override val configured = true
        var quotes = 0
        var books = 0
        var unknown = false
        var pickup: TaxiRequest? = null
        var bookingKey: String? = null
        override fun quote(request: TaxiRequest, callback: (TaxiQuote?, String?) -> Unit) {
            quotes++; pickup = request
            callback(TaxiQuote("provider-quote-1", "Synthetic quote for the requested ride", now + 60_000), null)
        }
        override fun book(quote: TaxiQuote, idempotencyKey: String, callback: (TaxiReceipt?, String?) -> Unit) {
            books++; bookingKey = idempotencyKey
            if (unknown) callback(null, "timeout") else callback(TaxiReceipt("synthetic-booking-1", "Provider confirmed synthetic booking"), null)
        }
    }
    private fun call(tools: NightTools, name: String, args: JSONObject = JSONObject()): Pair<JSONObject, Boolean> {
        var result: Pair<JSONObject, Boolean>? = null
        tools.handle(name, args) { value, error -> check(result == null); result = value to error }
        return requireNotNull(result)
    }
    private fun tools(profile: NightProfile = NightProfile(), location: Location = Location(), taxi: TaxiGateway = UnavailableTaxiGateway,
                      clock: () -> Long = { now }) = NightTools({ profile }, location, taxi, clock, direct, object : NearbyLookup {
        override fun lookup(latitude: Double, longitude: Double, category: String) = JSONObject()
            .put("status", "UNAVAILABLE").put("code", "NO_REVIEWED_LOCAL_SUGGESTIONS")
    })

    @Test fun missingHotelConfigurationNeverInventsDemoFactsOrAvailability() {
        val result = call(tools(), "get_hotel_context")
        assertTrue(result.second)
        assertEquals("UNAVAILABLE", result.first.getString("status"))
        assertEquals("", result.first.getJSONObject("hotel").getString("hotel_name"))
        assertEquals("unknown", result.first.getJSONObject("hotel").getJSONObject("room_availability").getString("status"))
    }

    @Test fun onlyFreshOwnerSuppliedAvailabilityIsReported() {
        val current = NightProfile(availability = "Owner supplied availability", availabilityUpdatedAtMillis = now - 1)
        assertEquals("provided", current.contextJson(now).getJSONObject("room_availability").getString("status"))
        assertEquals("unknown", current.copy(availabilityUpdatedAtMillis = now - NightProfile.AVAILABILITY_MAX_AGE_MS - 1)
            .contextJson(now).getJSONObject("room_availability").getString("status"))
        assertEquals("unknown", current.copy(availabilityUpdatedAtMillis = now + 1)
            .contextJson(now).getJSONObject("room_availability").getString("status"))
    }

    @Test fun absentTaxiApiCannotQuoteBookOrEvenRequestDeviceLocation() {
        val location = Location()
        val tools = tools(location = location)
        for (name in listOf("request_taxi", "confirm_taxi")) {
            val result = call(tools, name, JSONObject().put("destination", "Synthetic station").put("confirmed", true))
            assertTrue(result.second)
            assertEquals("TAXI_API_NOT_CONFIGURED", result.first.getString("code"))
            assertEquals("UNAVAILABLE", result.first.getString("status"))
        }
        assertEquals(0, location.requests)
    }

    @Test fun staleAndFutureLocalRecordsDoNotBecomeRecommendations() {
        val location = Location()
        val stale = LocalPlace("Synthetic place", "food", "Synthetic address", "Description", "Owner review", now - LocalPlace.MAX_REVIEW_AGE_MS - 1)
        val future = stale.copy(reviewedAtMillis = now + 1)
        val result = call(tools(NightProfile(localPack = listOf(stale, future)), location), "search_nearby")
        assertTrue(result.second)
        assertEquals("NO_REVIEWED_LOCAL_SUGGESTIONS", result.first.getString("code"))
        assertEquals(1, location.requests)
    }

    @Test fun nearbyUsesOnlyReviewedPackAndSortsActualCoordinateDistances() {
        val far = LocalPlace("Synthetic far", "food", "Address A", "Description", "Owner review", now - 1, 41.0, -3.0)
        val near = far.copy(name = "Synthetic near", latitude = 40.001)
        val unrelated = near.copy(name = "Synthetic museum", category = "culture")
        val result = call(tools(NightProfile(localPack = listOf(far, near, unrelated))), "search_nearby", JSONObject().put("category", "food"))
        assertFalse(result.second)
        val places = result.first.getJSONArray("places")
        assertEquals(2, places.length())
        assertEquals("Synthetic near", places.getJSONObject(0).getString("name"))
        assertTrue(places.getJSONObject(0).getInt("distance_meters") < places.getJSONObject(1).getInt("distance_meters"))
        assertEquals("owner_reviewed_local_pack", result.first.getString("source"))
    }

    @Test fun modelSuppliedConfirmationCannotAuthorizeBooking() {
        val taxi = Taxi(now)
        val tools = tools(taxi = taxi)
        val quote = call(tools, "request_taxi", JSONObject().put("destination", "Synthetic station").put("latitude", 0))
        assertEquals("REQUIRES_CONFIRMATION", quote.first.getString("status"))
        val id = quote.first.getString("confirmation_id")
        assertEquals(40.0, taxi.pickup!!.latitude, 0.0)
        val blocked = call(tools, "confirm_taxi", JSONObject().put("confirmation_id", id).put("confirmed", true))
        assertEquals("GUEST_CONFIRMATION_REQUIRED", blocked.first.getString("code"))
        assertEquals(0, taxi.books)
        assertFalse(tools.approveTaxi("unrelated-id"))
        assertTrue(tools.approveTaxi(id))
        val booked = call(tools, "confirm_taxi", JSONObject().put("confirmation_id", id))
        assertEquals("BOOKED", booked.first.getString("status"))
        assertEquals(id, taxi.bookingKey)
        assertEquals(1, taxi.books)
        val replay = call(tools, "confirm_taxi", JSONObject().put("confirmation_id", id))
        assertEquals("synthetic-booking-1", replay.first.getString("booking_id"))
        assertEquals(1, taxi.books)
    }

    @Test fun expiredQuoteCannotBeApprovedOrBooked() {
        var time = now
        val taxi = Taxi(now)
        val tools = tools(taxi = taxi, clock = { time })
        val id = call(tools, "request_taxi", JSONObject().put("destination", "Synthetic station")).first.getString("confirmation_id")
        time += 60_001
        assertFalse(tools.approveTaxi(id))
        val result = call(tools, "confirm_taxi", JSONObject().put("confirmation_id", id))
        assertEquals("QUOTE_EXPIRED", result.first.getString("code"))
        assertEquals(0, taxi.books)
    }

    @Test fun unknownBookingOutcomeNeverRetriesTheChargeableAction() {
        val taxi = Taxi(now).apply { unknown = true }
        val tools = tools(taxi = taxi)
        val id = call(tools, "request_taxi", JSONObject().put("destination", "Synthetic station")).first.getString("confirmation_id")
        assertTrue(tools.approveTaxi(id))
        assertEquals("BOOKING_OUTCOME_UNKNOWN", call(tools, "confirm_taxi", JSONObject().put("confirmation_id", id)).first.getString("code"))
        assertEquals("BOOKING_OUTCOME_PENDING", call(tools, "confirm_taxi", JSONObject().put("confirmation_id", id)).first.getString("code"))
        assertEquals(1, taxi.books)
    }

    @Test fun absentPickupLocationCannotReachFutureTaxiProvider() {
        val taxi = Taxi(now)
        val result = call(tools(location = Location(false), taxi = taxi), "request_taxi", JSONObject().put("destination", "Synthetic station"))
        assertEquals("LOCATION_REQUIRED", result.first.getString("code"))
        assertEquals(0, taxi.quotes)
        assertEquals(0, taxi.books)
    }

    @Test fun unknownToolNeverEnablesArbitraryUrlOrExternalActions() {
        val result = call(tools(), "fetch_url", JSONObject().put("url", "https://example.invalid"))
        assertEquals("UNKNOWN_TOOL", result.first.getString("code"))
        assertTrue(result.second)
    }

    @Test fun profileRejectsPartialCoordinatesAndUnreviewedPlaces() {
        for (value in listOf(JSONObject().put("latitude", 40), JSONObject().put("latitude", 91).put("longitude", 0),
            JSONObject().put("local_pack", org.json.JSONArray().put(JSONObject().put("name", "Synthetic place"))))) {
            try { NightProfile.parse(value); fail("Invalid owner configuration was accepted") } catch (_: IllegalArgumentException) {}
            catch (_: org.json.JSONException) {}
        }
        assertNull(NightProfile().coordinatesJson())
        assertEquals("[]", NightProfile().localPackJson)
    }

    @Test fun closingToolSurfacePreventsFurtherActions() {
        val location = Location()
        val tools = tools(location = location)
        tools.close()
        assertEquals("SESSION_CLOSED", call(tools, "get_location").first.getString("code"))
        assertEquals(0, location.requests)
    }
    @Test fun oversizedArgumentsAndRepeatedLocationCallbacksAreBounded() {
        val location = Location()
        val result = call(tools(location = location), "get_location", JSONObject().put("extra", "x".repeat(16_385)))
        assertEquals("INVALID_ARGUMENTS", result.first.getString("code"))
        assertEquals(0, location.requests)
        val duplicate = object : NightLocationProvider {
            override fun request(callback: (JSONObject) -> Unit) {
                repeat(2) { callback(JSONObject().put("status", "unavailable")) }
            }
        }
        val tools = NightTools({ NightProfile() }, duplicate, executor = direct)
        var replies = 0
        tools.handle("get_location", JSONObject()) { _, _ -> replies++ }
        assertEquals(1, replies)
    }

}
