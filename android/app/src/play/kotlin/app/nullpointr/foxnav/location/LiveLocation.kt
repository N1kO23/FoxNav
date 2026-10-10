package app.nullpointr.foxnav.location

import android.content.Context
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import com.stadiamaps.ferrostar.googleplayservices.FusedNavigationLocationProvider

/** The Play Store build uses Google's fused location provider. */
fun liveLocationProvider(context: Context): NavigationLocationProviding = FusedNavigationLocationProvider(context)
