package app.nullpointr.foxnav.data

import app.nullpointr.foxnav.shared.discovery.ServiceEndpoint
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.road.RoadInfo
import app.nullpointr.foxnav.shared.road.TraceAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Asks the server's Valhalla which road the car is on, for free drive (no route). */
class RoadLookup(private val client: OkHttpClient, routing: ServiceEndpoint) {
    private val url = routing.url.trimEnd('/') + "/trace_attributes"

    /** The road under the newest of [recent] (oldest first), or null when unknown. */
    suspend fun currentRoad(recent: List<GeoPoint>): RoadInfo? = withContext(Dispatchers.IO) {
        val body = TraceAttributes.request(recent).toRequestBody(JSON)
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            if (response.isSuccessful) TraceAttributes.parseCurrentRoad(response.body.string()) else null
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
