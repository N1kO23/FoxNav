package app.nullpointr.foxnav.server.reports

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.sin
import kotlin.math.sinh

/**
 * ETRS89 / UTM (EPSG:258xx) easting and northing to latitude and longitude, by Krüger's series to
 * the third order, which is good to a millimetre within a zone. Statens vegvesen gives road
 * stretches in zone 33 (EPSG:25833).
 */
object Utm {
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257222101 // GRS 80
    private const val K0 = 0.9996
    private const val FALSE_EASTING = 500_000.0
    private val N = F / (2 - F)
    private val RECTIFYING_RADIUS = A / (1 + N) * (1 + N * N / 4 + N * N * N * N / 64)
    private val BETA = doubleArrayOf(N / 2 - 2 * N * N / 3 + 37 * N * N * N / 96, N * N / 48 + N * N * N / 15, 17 * N * N * N / 480)
    private val DELTA = doubleArrayOf(2 * N - 2 * N * N / 3 - 2 * N * N * N, 7 * N * N / 3 - 8 * N * N * N / 5, 56 * N * N * N / 15)

    /** (latitude, longitude) in degrees of a point in the northern hemisphere's [zone]. */
    fun toWgs84(easting: Double, northing: Double, zone: Int = 33): Pair<Double, Double> {
        val xi = northing / (K0 * RECTIFYING_RADIUS)
        val eta = (easting - FALSE_EASTING) / (K0 * RECTIFYING_RADIUS)
        var xiPrime = xi
        var etaPrime = eta
        for (j in 1..3) {
            xiPrime -= BETA[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            etaPrime -= BETA[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val chi = asin(sin(xiPrime) / cosh(etaPrime))
        var latitude = chi
        for (j in 1..3) latitude += DELTA[j - 1] * sin(2 * j * chi)
        val centralMeridian = Math.toRadians(zone * 6.0 - 183)
        val longitude = centralMeridian + atan2(sinh(etaPrime), cos(xiPrime))
        return Math.toDegrees(latitude) to Math.toDegrees(longitude)
    }
}
