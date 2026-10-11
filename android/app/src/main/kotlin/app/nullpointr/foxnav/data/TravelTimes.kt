package app.nullpointr.foxnav.data

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * How long the drive to a place takes, from the server's Valhalla: a route without its
 * directions, just the time. For "Home 14 min" on the shortcuts.
 */
class TravelTimes(private val client: OkHttpClient, routingUrl: String) {
    private val endpoint = routingUrl.trimEnd('/') + "/route"

    /** Seconds by car from [from] to [to], or null when the server can't say (offline, no road). */
    suspend fun seconds(from: GeoPoint, to: GeoPoint): Double? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("locations", JSONArray().put(location(from)).put(location(to)))
            .put("costing", "auto")
            .put("directions_type", "none")
        runCatching {
            client.newCall(Request.Builder().url(endpoint).post(body.toString().toRequestBody(JSON)).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                JSONObject(response.body.string()).getJSONObject("trip").getJSONObject("summary").getDouble("time")
            }
        }.getOrNull()
    }

    private fun location(point: GeoPoint) = JSONObject().put("lat", point.latitude).put("lon", point.longitude)

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
