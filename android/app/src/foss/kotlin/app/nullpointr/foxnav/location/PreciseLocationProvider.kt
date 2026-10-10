package app.nullpointr.foxnav.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android's own location service, asking for GNSS accuracy. Ferrostar's `AndroidLocationProvider`
 * makes a plain request, which the fused provider answers at balanced accuracy: mostly Wi-Fi and
 * cell positions, several seconds apart and often without a speed. Not enough to drive by.
 *
 * Doesn't check permissions; callers ask for location access first.
 */
class PreciseLocationProvider(context: Context) : NavigationLocationProviding {
    private val locationManager = context.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission")
    override suspend fun lastLocation(): Location? = locationManager.getLastKnownLocation(provider())

    @SuppressLint("MissingPermission")
    override fun locationUpdates(intervalMillis: Long): Flow<Location> = callbackFlow {
        val provider = provider()
        val listener = LocationListenerCompat { location -> trySend(location) }
        // The last known position first, so the map doesn't wait for the first new fix.
        locationManager.getLastKnownLocation(provider)?.let { trySend(it) }
        val request = LocationRequestCompat.Builder(intervalMillis)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        LocationManagerCompat.requestLocationUpdates(locationManager, provider, request, listener, Looper.getMainLooper())
        awaitClose { LocationManagerCompat.removeUpdates(locationManager, listener) }
    }

    /** The fused provider where there is one (Android 12+), GNSS otherwise. Disabled ones resume when location is switched on. */
    private fun provider(): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManagerCompat.hasProvider(locationManager, LocationManager.FUSED_PROVIDER) ->
            LocationManager.FUSED_PROVIDER
        LocationManagerCompat.hasProvider(locationManager, LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> LocationManager.PASSIVE_PROVIDER
    }
}
