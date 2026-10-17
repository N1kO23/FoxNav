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

/**
 * Asks Valhalla which road the car is on, for speed limits and camera rules. The phone's own
 * Valhalla answers wherever it has the road data: the same answer as the server's, in a fraction
 * of the time and without the network, every few seconds (ADR 0005). Elsewhere the server does.
 */
class RoadLookup(
    private val client: OkHttpClient,
    routing: ServiceEndpoint,
    private val onDevice: OnDeviceValhalla?,
    private val online: () -> Boolean,
) {
    private val url = routing.url.trimEnd('/') + "/trace_attributes"

    /** The road under the newest of [recent] (oldest first), or null when unknown. */
    suspend fun currentRoad(recent: List<GeoPoint>): RoadInfo? = withContext(Dispatchers.IO) {
        val request = TraceAttributes.request(recent)
        runCatching { onDevice?.traceAttributes(request, at = recent.last()) }.getOrNull()?.let { answer ->
            return@withContext TraceAttributes.parseCurrentRoad(answer)
        }
        if (!online()) return@withContext null
        client.newCall(Request.Builder().url(url).post(request.toRequestBody(JSON)).build()).execute().use { response ->
            if (response.isSuccessful) TraceAttributes.parseCurrentRoad(response.body.string()) else null
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
