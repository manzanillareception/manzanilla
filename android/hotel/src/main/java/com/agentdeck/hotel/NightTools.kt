package com.agentdeck.hotel

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** The host supplies the eventual trusted adapter, never an endpoint supplied by guest speech. */
interface TaxiGateway {
    val configured: Boolean
    fun quote(request: TaxiRequest, callback: (TaxiQuote?, String?) -> Unit)
    fun book(quote: TaxiQuote, idempotencyKey: String, callback: (TaxiReceipt?, String?) -> Unit)
}
data class TaxiRequest(val latitude: Double, val longitude: Double, val destination: String, val passengers: Int)
data class TaxiQuote(val id: String, val description: String, val expiresAtMillis: Long)
data class TaxiReceipt(val bookingId: String, val description: String)
object UnavailableTaxiGateway : TaxiGateway {
    override val configured = false
    override fun quote(request: TaxiRequest, callback: (TaxiQuote?, String?) -> Unit) = callback(null, "unavailable")
    override fun book(quote: TaxiQuote, idempotencyKey: String, callback: (TaxiReceipt?, String?) -> Unit) = callback(null, "unavailable")
}

/** Small allowlisted device tool surface. No arbitrary URL fetch, account login, or taxi simulation. */
class NightTools(
    private val profile: () -> NightProfile,
    private val location: NightLocationProvider,
    private val taxi: TaxiGateway = UnavailableTaxiGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val nearby: NearbyLookup = NearbyPlaces()
) {
    constructor(profileStore: NightProfileStore, location: NightLocationProvider,
                taxi: TaxiGateway = UnavailableTaxiGateway) : this(profileStore::load, location, taxi)

    private data class PendingTaxi(val quote: TaxiQuote, var approved: Boolean = false,
                                   var attempted: Boolean = false, var receipt: TaxiReceipt? = null)
    private val pendingTaxi = LinkedHashMap<String, PendingTaxi>()
    @Volatile private var closed = false

    fun handle(name: String, arguments: JSONObject, callback: (JSONObject, Boolean) -> Unit) {
        if (closed) { callback(unavailable("SESSION_CLOSED", "This reception session has ended."), true); return }
        val delivered = AtomicBoolean(false)
        val reply: (JSONObject, Boolean) -> Unit = { value, error ->
            if (!closed && delivered.compareAndSet(false, true)) runCatching {
                val bounded = JSONObject(value.toString())
                val places = bounded.optJSONArray("places")
                while (bounded.toString().length > MAX_RESULT_CHARACTERS && places != null && places.length() > 1) {
                    places.remove(places.length() - 1)
                    bounded.put("truncated", true)
                }
                if (bounded.toString().length > MAX_RESULT_CHARACTERS)
                    callback(unavailable("TOOL_RESPONSE_TOO_LARGE", "The configured information is too large. Ask hotel staff for help."), true)
                else callback(bounded, error)
            }
        }
        // Snapshot guest-controlled arguments before another thread can change them.
        val encoded = arguments.toString()
        if (encoded.length > 16_384) {
            reply(unavailable("INVALID_ARGUMENTS", "The requested reception action is too large."), true)
            return
        }
        val args = try { JSONObject(encoded) } catch (_: Exception) { JSONObject() }
        dispatch(reply) {
            when (name) {
                "get_hotel_context" -> {
                    val hotel = profile()
                    val configured = listOf(hotel.name, hotel.address, hotel.area, hotel.openingHours, hotel.facts).any { it.isNotBlank() }
                    reply(JSONObject().put("status", if (configured) "AVAILABLE" else "UNAVAILABLE")
                        .put("hotel", hotel.contextJson(clock()))
                        .put("message", if (configured) "Use only these owner-supplied facts; missing or stale information is unknown."
                            else "Hotel information has not been configured. Ask hotel staff; do not invent property facts."), !configured)
                }
                "get_location" -> location.request { value ->
                    val present = coordinates(value) != null
                    reply(JSONObject().put("status", if (present) "AVAILABLE" else "UNAVAILABLE")
                        .put("location", value), !present)
                }
                "search_nearby" -> nearby(args, reply)
                "request_taxi" -> requestTaxi(args, reply)
                "confirm_taxi" -> confirmTaxi(args, reply)
                else -> reply(unavailable("UNKNOWN_TOOL", "This action is not available on the reception device."), true)
            }
        }
    }

    private fun nearby(args: JSONObject, callback: (JSONObject, Boolean) -> Unit) {
        val query = args.optString("query").trim().take(120).lowercase()
        val category = args.optString("category").trim().take(80).lowercase()
        val local = profile().localPack.filter { place ->
            place.current(clock()) && (category.isBlank() || place.category.lowercase() == category) &&
                (query.isBlank() || listOf(place.name, place.category, place.address, place.summary)
                    .joinToString(" ").lowercase().contains(query))
        }
        location.request { value -> dispatch(callback) {
            val point = coordinates(value)
            if (local.isEmpty()) {
                if (point == null) {
                    callback(unavailable("LOCATION_REQUIRED", "A current device position or configured hotel coordinates are needed for nearby suggestions."), true)
                } else {
                    val found = nearby.lookup(point.first, point.second, category)
                    callback(found, found.optString("status") != "AVAILABLE")
                }
                return@dispatch
            }
            val ranked = if (point == null) local else local.sortedBy {
                if (NightProfile.validCoordinates(it.latitude, it.longitude)) distanceMeters(point.first, point.second, it.latitude!!, it.longitude!!)
                else Double.POSITIVE_INFINITY
            }
            val places = JSONArray(ranked.take(8).map { place -> place.toJson().apply {
                if (point != null && NightProfile.validCoordinates(place.latitude, place.longitude))
                    put("distance_meters", distanceMeters(point.first, point.second, place.latitude!!, place.longitude!!).roundToInt())
            } })
            callback(JSONObject().put("status", "AVAILABLE").put("places", places).put("location", value)
                .put("source", "owner_reviewed_local_pack")
                .put("message", "Suggestions are owner-reviewed records. Current opening or room availability is not implied."), false)
        } }
    }

    private fun requestTaxi(args: JSONObject, callback: (JSONObject, Boolean) -> Unit) {
        if (!taxi.configured) { callback(taxiUnavailable(), true); return }
        val destination = args.optString("destination").trim()
        val passengers = args.optInt("passengers", 1)
        if (destination.isBlank() || destination.length > 500 || passengers !in 1..8) {
            callback(unavailable("INVALID_TAXI_REQUEST", "Provide a destination and one to eight passengers."), true); return
        }
        location.request { value -> dispatch(callback) {
            val point = coordinates(value)
            if (point == null) { callback(unavailable("LOCATION_REQUIRED", "A pickup location is needed. No taxi has been requested."), true); return@dispatch }
            taxi.quote(TaxiRequest(point.first, point.second, destination, passengers)) { quote, _ -> dispatch(callback) {
                val now = clock()
                if (quote == null || quote.id.isBlank() || quote.id.length > 200 || quote.description.isBlank() || quote.description.length > 2000 ||
                    quote.expiresAtMillis <= now || quote.expiresAtMillis - now > MAX_QUOTE_LIFETIME_MS) {
                    callback(unavailable("QUOTE_UNAVAILABLE", "A valid taxi quote was not received. No taxi is booked."), true)
                } else {
                    val id = UUID.randomUUID().toString()
                    synchronized(pendingTaxi) {
                        pendingTaxi.entries.removeAll { it.value.quote.expiresAtMillis <= now }
                        if (pendingTaxi.size >= 8) pendingTaxi.remove(pendingTaxi.keys.first())
                        pendingTaxi[id] = PendingTaxi(quote)
                    }
                    callback(JSONObject().put("status", "REQUIRES_CONFIRMATION").put("confirmation_id", id)
                        .put("quote", quote.description).put("expires_at_ms", quote.expiresAtMillis)
                        .put("message", "No taxi is booked. Ask the guest to review and confirm this exact quote on the device."), false)
                }
            } }
        } }
    }

    /** Called only by an explicit host/device confirmation, never from model-supplied arguments. */
    fun approveTaxi(confirmationId: String): Boolean = synchronized(pendingTaxi) {
        val pending = pendingTaxi[confirmationId] ?: return@synchronized false
        if (closed || pending.attempted || pending.quote.expiresAtMillis <= clock()) return@synchronized false
        pending.approved = true
        true
    }

    private fun confirmTaxi(args: JSONObject, callback: (JSONObject, Boolean) -> Unit) {
        if (!taxi.configured) { callback(taxiUnavailable(), true); return }
        val id = args.optString("confirmation_id")
        val pending = synchronized(pendingTaxi) { pendingTaxi[id] }
        if (pending == null) {
            callback(unavailable("QUOTE_EXPIRED", "The taxi quote is missing or expired. No new booking was made."), true); return
        }
        synchronized(pendingTaxi) {
            pending.receipt?.let { callback(receipt(it), false); return }
            if (pending.quote.expiresAtMillis <= clock()) { callback(unavailable("QUOTE_EXPIRED", "The taxi quote expired. No new booking was made."), true); return }
            if (!pending.approved) { callback(unavailable("GUEST_CONFIRMATION_REQUIRED", "The guest must confirm the exact quote on the device before booking."), true); return }
            if (pending.attempted) { callback(unavailable("BOOKING_OUTCOME_PENDING", "A booking was already attempted. Do not create another taxi request until the provider outcome is checked."), true); return }
            // One attempt per quote. Unknown outcomes never silently retry a chargeable action.
            pending.attempted = true
        }
        taxi.book(pending.quote, id) { booked, _ -> dispatch(callback) {
            if (booked == null || booked.bookingId.isBlank() || booked.bookingId.length > 200 || booked.description.length > 2000) {
                callback(unavailable("BOOKING_OUTCOME_UNKNOWN", "The provider did not confirm a booking. Check its outcome before trying again."), true)
            } else {
                synchronized(pendingTaxi) { pending.receipt = booked }
                callback(receipt(booked), false)
            }
        } }
    }

    private fun receipt(value: TaxiReceipt) = JSONObject().put("status", "BOOKED")
        .put("booking_id", value.bookingId).put("information", value.description)

    private fun dispatch(callback: (JSONObject, Boolean) -> Unit, work: () -> Unit) {
        if (closed) return
        try { executor.execute {
            if (!closed) try { work() } catch (_: Exception) {
                callback(unavailable("TOOL_UNAVAILABLE", "This reception action is unavailable. No successful action is confirmed."), true)
            }
        } } catch (_: RuntimeException) {
            if (!closed) callback(unavailable("TOOL_UNAVAILABLE", "This reception action is unavailable."), true)
        }
    }

    fun close() {
        closed = true
        location.close()
        synchronized(pendingTaxi) { pendingTaxi.clear() }
        (executor as? ExecutorService)?.shutdownNow()
    }

    companion object {
        const val MAX_RESULT_CHARACTERS = 15_000
        const val MAX_QUOTE_LIFETIME_MS = 10 * 60 * 1000L
        private fun unavailable(code: String, message: String) = JSONObject().put("status", "UNAVAILABLE")
            .put("code", code).put("message", message)
        private fun taxiUnavailable() = unavailable("TAXI_API_NOT_CONFIGURED",
            "Taxi booking is not connected yet. Contact hotel staff; no taxi was called or booked.")
        private fun coordinates(value: JSONObject): Pair<Double, Double>? {
            if (value.optString("status") != "available") return null
            val latitude = value.optDouble("latitude", Double.NaN)
            val longitude = value.optDouble("longitude", Double.NaN)
            return if (NightProfile.validCoordinates(latitude, longitude)) latitude to longitude else null
        }
        fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
            return 6_371_000 * 2 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1 - a).coerceIn(0.0, 1.0)))
        }
    }
}
