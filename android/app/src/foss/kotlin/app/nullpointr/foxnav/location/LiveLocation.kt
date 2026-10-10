package app.nullpointr.foxnav.location

import android.content.Context
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding

/** The F-Droid build uses Android's own location service, no Google Play Services. */
fun liveLocationProvider(context: Context): NavigationLocationProviding = PreciseLocationProvider(context)
