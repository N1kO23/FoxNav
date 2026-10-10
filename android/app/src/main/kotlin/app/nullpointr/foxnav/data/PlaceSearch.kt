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

/** Searches places with the server's Photon instance. */
class PlaceSearch(private val client: OkHttpClient, private val endpoint: ServiceEndpoint) {
    /** Results for [query], biased towards [near] when given. */
    suspend fun search(query: String, near: GeoPoint?): List<Place> = withContext(Dispatchers.IO) {
        val language = Locale.getDefault().language.takeIf { it in SEARCH_LANGUAGES } ?: "en"
        val url = endpoint.url.trimEnd('/') + Photon.searchPath(query, near, language = language)
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Search failed: HTTP ${response.code}")
            Photon.parse(response.body.string())
        }
    }

    private companion object {
        // Languages the local stack imports into Photon (infra/compose.yaml).
        val SEARCH_LANGUAGES = setOf("fi", "sv", "en")
    }
}
