package app.nullpointr.foxnav.shared.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS 84 position in degrees. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    /** Great-circle distance in metres (haversine; plenty accurate at driving distances). */
    fun distanceTo(other: GeoPoint): Double {
        val dLat = (other.latitude - latitude).toRadians()
        val dLon = (other.longitude - longitude).toRadians()
        val a = sin(dLat / 2).let { it * it } +
            cos(latitude.toRadians()) * cos(other.latitude.toRadians()) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_008.8

        fun Double.toRadians() = this * PI / 180
    }
}
