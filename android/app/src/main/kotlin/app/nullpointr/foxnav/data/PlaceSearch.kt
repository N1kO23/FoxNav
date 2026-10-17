package app.nullpointr.foxnav.data

import app.nullpointr.foxnav.shared.discovery.ServiceEndpoint
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.search.Photon
import app.nullpointr.foxnav.shared.search.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/**
 * Searches places with the server's Photon instance. Without a connection, or when the server
 * can't be reached, the search data on the phone answers instead: wherever there's road data.
 */
class PlaceSearch(
    private val client: OkHttpClient,
    private val endpoint: ServiceEndpoint,
    private val offline: SearchIndex? = null,
    private val online: () -> Boolean = { true },
) {
    /** Results for [query], biased towards [near] when given. */
    suspend fun search(query: String, near: GeoPoint?): List<Place> = withContext(Dispatchers.IO) {
        if (online()) {
            try {
                return@withContext fromPhoton(query, near)
            } catch (error: IOException) {
                if (offline == null) throw error
            }
        }
        offline?.search(query, near)?.map { it.toPlace() }.orEmpty()
    }

    private fun fromPhoton(query: String, near: GeoPoint?): List<Place> {
        val url = endpoint.url.trimEnd('/') + Photon.searchPath(query, near, language = language())
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Search failed: HTTP ${response.code}")
            Photon.parse(response.body.string())
        }
    }

    /** A name for the area around [point], like "Helsinki", or for a [small] area "Kluuvi, Helsinki". */
    suspend fun areaName(point: GeoPoint, small: Boolean): String? = withContext(Dispatchers.IO) {
        val url = endpoint.url.trimEnd('/') + Photon.reversePath(point, language())
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) Photon.parseArea(response.body.string(), small) else null
        }
    }

    private fun language() = Locale.getDefault().language.takeIf { it in SEARCH_LANGUAGES } ?: "en"

    private companion object {
        // Languages the local stack imports into Photon (infra/compose.yaml).
        val SEARCH_LANGUAGES = setOf("fi", "sv", "en")
    }
}
