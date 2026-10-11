package app.nullpointr.foxnav.shared.time

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Where the sun is, for switching the map between day and night (a simplified NOAA algorithm, good to a fraction of a degree). */
object Sun {
    /** Degrees above the horizon at [point] at [epochMs]; negative below. */
    fun elevation(point: GeoPoint, epochMs: Long): Double {
        val days = epochMs / 86_400_000.0 + 2_440_587.5 - 2_451_545.0 // since J2000
        val meanLongitude = (280.460 + 0.9856474 * days).mod(360.0)
        val meanAnomaly = radians((357.528 + 0.9856003 * days).mod(360.0))
        val eclipticLongitude = radians(meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly))
        val obliquity = radians(23.439 - 0.0000004 * days)
        val rightAscension = atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val siderealDegrees = (280.46061837 + 360.98564736629 * days).mod(360.0)
        val hourAngle = radians(siderealDegrees + point.longitude) - rightAscension
        val latitude = radians(point.latitude)
        return degrees(asin(sin(latitude) * sin(declination) + cos(latitude) * cos(declination) * cos(hourAngle)))
    }

    /** Whether it's day: between sunrise and sunset, when the sun's upper edge is above the horizon. */
    fun isUp(point: GeoPoint, epochMs: Long): Boolean = elevation(point, epochMs) > SUNRISE_ELEVATION

    private const val SUNRISE_ELEVATION = -0.833 // refraction and the sun's radius

    private fun radians(degrees: Double) = degrees * PI / 180
    private fun degrees(radians: Double) = radians * 180 / PI
}
