package app.nullpointr.foxnav.shared.search

import app.nullpointr.foxnav.shared.geo.GeoPoint

/**
 * Names for places from the search data around them, for naming things without a connection: a
 * downloaded area after its town or district, a dropped pin after the address it's at.
 */
object PlaceNames {
    /** The town [point] is in: the one the nearest addresses and streets name. */
    fun town(point: GeoPoint, nearby: List<SearchEntry>): String? =
        nearby.filter { it.kind == SearchKind.ADDRESS || it.kind == SearchKind.STREET }
            .minByOrNull { it.point.distanceTo(point) }
            ?.detail?.takeIf { it.isNotBlank() }

    /** Like "Rovaniemi", or for a [small] area the district in its town, like "Ounasrinne, Rovaniemi". */
    fun areaName(point: GeoPoint, nearby: List<SearchEntry>, small: Boolean): String? {
        val town = town(point, nearby) ?: return nearestPlace(point, nearby)?.title
        if (!small) return town
        val district = nearby.filter { it.kind == SearchKind.PLACE && it.detail == town && it.title != town }
            .minByOrNull { it.point.distanceTo(point) }
            ?.takeIf { it.point.distanceTo(point) <= DISTRICT_M }
        return district?.let { "${it.title}, $town" } ?: town
    }

    /**
     * A name for a pin dropped at [point]: the address or place right there, or else a dropped pin
     * in its district, so it's still clear where.
     */
    fun pinAt(point: GeoPoint, nearby: List<SearchEntry>): Place {
        val here = nearby.filter { it.kind == SearchKind.ADDRESS || it.kind == SearchKind.POI }
            .minByOrNull { it.point.distanceTo(point) }
            ?.takeIf { it.point.distanceTo(point) <= PIN_M }
        return Place(
            title = here?.title ?: DROPPED_PIN,
            detail = here?.let { town(point, nearby) ?: it.detail } ?: areaName(point, nearby, small = true).orEmpty(),
            latitude = point.latitude,
            longitude = point.longitude,
        )
    }

    private fun nearestPlace(point: GeoPoint, nearby: List<SearchEntry>) =
        nearby.filter { it.kind == SearchKind.PLACE }.minByOrNull { it.point.distanceTo(point) }

    const val DROPPED_PIN = "Dropped pin"

    /** How close an address must be for a pin to take its name. */
    const val PIN_M = 40.0

    /** How far a district's centre may be for an area to take its name. */
    const val DISTRICT_M = 3_000.0

    /** How far around to look for names. */
    const val NEARBY_M = 3_000.0
}

/**
 * The search results a car can be routed to with the road data on the phone: road data where they
 * are, and where the car is known and has road data too, a way there ([reachable], one call for
 * the lot). Further than [matrixRadiusM], where the check would be slow, road data there will do.
 */
fun navigable(
    entries: List<SearchEntry>,
    near: GeoPoint?,
    covered: (GeoPoint) -> Boolean,
    reachable: (from: GeoPoint, targets: List<GeoPoint>) -> List<Boolean>?,
    matrixRadiusM: Double = 300_000.0,
): List<SearchEntry> {
    val there = entries.filter { covered(it.point) }
    if (near == null || !covered(near)) return there
    val close = there.filter { it.point.distanceTo(near) <= matrixRadiusM }
    if (close.isEmpty()) return there
    val ways = runCatching { reachable(near, close.map { it.point }) }.getOrNull()?.takeIf { it.size == close.size } ?: return there
    val unreachable = close.filterIndexed { index, _ -> !ways[index] }.toSet()
    return there.filterNot { it in unreachable }
}
