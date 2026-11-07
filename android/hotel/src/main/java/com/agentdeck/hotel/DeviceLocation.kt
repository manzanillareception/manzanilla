package com.agentdeck.hotel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

interface NightLocationProvider {
    fun request(callback: (JSONObject) -> Unit)
    fun close() {}
}

/** Permission is requested by the visible Activity, never by a guest tool call. */
class DeviceLocation(context: Context, private val profileStore: NightProfileStore) : NightLocationProvider {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pending = LinkedHashSet<() -> Unit>()

    override fun request(callback: (JSONObject) -> Unit) {
        main.post { requestOnMain(callback) }
    }

    private fun fallback(reason: String): JSONObject = profileStore.load().coordinatesJson()
        ?.put("device_location_status", reason)
        ?: JSONObject().put("status", "unavailable").put("reason", reason)
            .put("message", "No current device location or configured hotel coordinates are available.")

    private fun requestOnMain(callback: (JSONObject) -> Unit) {
        fun emit(value: JSONObject) { runCatching { callback(value) } }
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) { emit(fallback("permission_not_granted")); return }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) { emit(fallback("location_service_unavailable")); return }
        try {
            val providers = manager.getProviders(true)
            val last = providers.mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider)?.takeIf(::usable) } catch (_: SecurityException) { null }
            }.minByOrNull { it.accuracy }
            if (last != null) { emit(encode(last)); return }
            val provider = when {
                providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider == null || pending.size >= 3) { emit(fallback("no_recent_fix")); return }
            val complete = AtomicBoolean(false)
            lateinit var listener: LocationListener
            lateinit var timeout: Runnable
            lateinit var cancel: () -> Unit
            fun finish(value: JSONObject) {
                if (!complete.compareAndSet(false, true)) return
                main.removeCallbacks(timeout)
                try { manager.removeUpdates(listener) } catch (_: RuntimeException) {}
                pending.remove(cancel)
                emit(value)
            }
            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { if (usable(location)) finish(encode(location)) }
                override fun onProviderDisabled(provider: String) { finish(fallback("provider_disabled")) }
                override fun onProviderEnabled(provider: String) {}
                @Deprecated("Android legacy callback")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            timeout = Runnable { finish(fallback("location_timeout")) }
            cancel = { finish(fallback("request_cancelled")) }
            pending.add(cancel)
            main.postDelayed(timeout, LOCATION_TIMEOUT_MS)
            try { manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
            catch (_: SecurityException) { finish(fallback("permission_not_granted")) }
            catch (_: RuntimeException) { finish(fallback("location_service_unavailable")) }
        } catch (_: SecurityException) { emit(fallback("permission_not_granted")) }
        catch (_: RuntimeException) { emit(fallback("location_service_unavailable")) }
    }

    private fun usable(value: Location): Boolean {
        val age = (SystemClock.elapsedRealtimeNanos() - value.elapsedRealtimeNanos) / 1_000_000
        return NightProfile.validCoordinates(value.latitude, value.longitude) && value.hasAccuracy() &&
            value.accuracy.isFinite() && value.accuracy in 0f..MAX_ACCURACY_METERS && age in 0..MAX_FIX_AGE_MS
    }

    private fun encode(value: Location): JSONObject = JSONObject().put("status", "available")
        .put("latitude", value.latitude).put("longitude", value.longitude).put("source", "device_location")
        .put("accuracy_meters", value.accuracy.toDouble()).put("updated_at_ms", value.time)

    override fun close() { main.post { pending.toList().forEach { it() } } }

    companion object {
        const val LOCATION_TIMEOUT_MS = 8_000L
        const val MAX_FIX_AGE_MS = 5 * 60 * 1000L
        const val MAX_ACCURACY_METERS = 1_000f
    }
}
