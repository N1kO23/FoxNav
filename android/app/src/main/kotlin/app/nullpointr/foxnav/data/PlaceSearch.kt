package app.nullpointr.foxnav.data

import app.nullpointr.foxnav.shared.discovery.ServiceEndpoint
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.search.Photon
import app.nullpointr.foxnav.shared.search.Place
import app.nullpointr.foxnav.shared.search.PlaceNames
import app.nullpointr.foxnav.shared.search.navigable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/**
 * Searches places with the server's Photon instance. Without a connection, or when the server
 * can't be reached, the search data on the phone answers instead, with only the places the
 * phone's road data can route to: from the car, where it has road data too.
 */
class PlaceSearch(
    private val client: OkHttpClient,
    private val endpoint: ServiceEndpoint,
    private val offline: SearchIndex? = null,
    private val online: () -> Boolean = { true },
    /** Whether the phone has road data at a point. */
    private val covered: (GeoPoint) -> Boolean = { true },
    /** Whether the phone's road data has a way from a point to each of some others; null if it can't tell. */
    private val reachable: (GeoPoint, List<GeoPoint>) -> List<Boolean>? = { _, _ -> null },
) {
    /** Results for [query], biased towards [near] (where the car is) when given. */
    suspend fun search(query: String, near: GeoPoint?): List<Place> = withContext(Dispatchers.IO) {
        if (online()) {
            try {
                return@withContext fromPhoton(query, near)
            } catch (error: IOException) {
                if (offline == null) throw error
            }
        }
        val index = offline ?: return@withContext emptyList()
        // More than will show: some may be out of the road data's reach.
        navigable(index.search(query, near, OFFLINE_CANDIDATES), near, covered, reachable).take(RESULTS).map { it.toPlace() }
    }

    private fun fromPhoton(query: String, near: GeoPoint?): List<Place> {
        val url = endpoint.url.trimEnd('/') + Photon.searchPath(query, near, limit = RESULTS, language = language())
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Search failed: HTTP ${response.code}")
            Photon.parse(response.body.string())
        }
    }

    /** A name for the area around [point], like "Helsinki", or for a [small] area "Kluuvi, Helsinki". */
    suspend fun areaName(point: GeoPoint, small: Boolean): String? = withContext(Dispatchers.IO) {
        if (online()) runCatching { reverse(point) }.getOrNull()?.let { return@withContext Photon.parseArea(it, small) }
        offline?.let { PlaceNames.areaName(point, it.nearby(point, PlaceNames.NEARBY_M), small) }
    }

    /** A name for a pin dropped at [point]: the address or place there, or a dropped pin in its district. */
    suspend fun pinAt(point: GeoPoint): Place = withContext(Dispatchers.IO) {
        if (online()) {
            runCatching { reverse(point) }.getOrNull()?.let { json ->
                val here = Photon.parse(json).firstOrNull()?.takeIf { GeoPoint(it.latitude, it.longitude).distanceTo(point) <= PlaceNames.PIN_M }
                return@withContext Place(
                    title = here?.title ?: PlaceNames.DROPPED_PIN,
                    detail = here?.detail ?: Photon.parseArea(json, small = true).orEmpty(),
                    latitude = point.latitude,
                    longitude = point.longitude,
                )
            }
        }
        offline?.let { PlaceNames.pinAt(point, it.nearby(point, PlaceNames.NEARBY_M)) }
            ?: Place(PlaceNames.DROPPED_PIN, "", point.latitude, point.longitude)
    }

    /** Photon's reverse lookup at [point]: the nearest thing it knows. */
    private fun reverse(point: GeoPoint): String {
        val url = endpoint.url.trimEnd('/') + Photon.reversePath(point, language())
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Reverse lookup failed: HTTP ${response.code}")
            response.body.string()
        }
    }

    private fun language() = Locale.getDefault().language.takeIf { it in SEARCH_LANGUAGES } ?: "en"

    private companion object {
        // Languages the local stack imports into Photon (infra/compose.yaml).
        val SEARCH_LANGUAGES = setOf("fi", "sv", "en")
        const val RESULTS = 8
        const val OFFLINE_CANDIDATES = 20
    }
}
