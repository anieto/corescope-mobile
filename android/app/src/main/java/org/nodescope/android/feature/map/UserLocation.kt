package org.nodescope.android.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.nodescope.android.core.model.Coordinate

/** Permissions asked for only when the person taps "Center on my location". */
internal val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

internal fun hasLocationPermission(context: Context) = LOCATION_PERMISSIONS.any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

internal sealed interface LocationResult {
    data class Found(val coordinate: Coordinate) : LocationResult
    data object ServicesOff : LocationResult
    data object Unavailable : LocationResult
}

/**
 * One position fix for centering the map, as on iOS: no tracking and nothing stored. A fix
 * from the last two minutes is used straight away; otherwise the best enabled provider is
 * asked once, giving up after 15 seconds.
 */
@SuppressLint("MissingPermission") // callers check hasLocationPermission first
internal suspend fun currentLocation(context: Context): LocationResult {
    if (!hasLocationPermission(context)) return LocationResult.Unavailable
    val manager = context.getSystemService(LocationManager::class.java) ?: return LocationResult.Unavailable
    val providers = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
    }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    if (providers.isEmpty()) return LocationResult.ServicesOff
    val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }.maxByOrNull { it.time }
    val location = recent ?: withTimeoutOrNull(15_000) { singleFix(context, manager, providers.first()) }
    return location?.let { Coordinate.valid(it.latitude, it.longitude) }?.let(LocationResult::Found) ?: LocationResult.Unavailable
}

@SuppressLint("MissingPermission")
private suspend fun singleFix(context: Context, manager: LocationManager, provider: String): Location? = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= 30) {
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            manager.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(context)) {
                if (continuation.isActive) continuation.resume(it)
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
                @Deprecated("Required on API < 29") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderDisabled(provider: String) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }
    }
}
