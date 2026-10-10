package app.nullpointr.foxnav.shared.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
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

    /** Initial great-circle bearing towards [other], in degrees clockwise from north (0 until 360). */
    fun bearingTo(other: GeoPoint): Double {
        val lat1 = latitude.toRadians()
        val lat2 = other.latitude.toRadians()
        val dLon = (other.longitude - longitude).toRadians()
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (atan2(y, x) * 180 / PI + 360) % 360
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_008.8

        fun Double.toRadians() = this * PI / 180
    }
}
