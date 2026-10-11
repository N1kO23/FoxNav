package app.nullpointr.foxnav.data

import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.withJsonOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WellKnownRouteProvider
import java.io.IOException

/**
 * Routes from the server's Valhalla, with the options as they are at each request: a new
 * guidance language or units apply from the next route (or reroute) on, without a new session.
 */
class ValhallaRoutes(
    private val client: OkHttpClient,
    private val endpointUrl: String,
    private val options: () -> Map<String, Any>,
) : CustomRouteProvider {
    override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> = withContext(Dispatchers.IO) {
        val provider = WellKnownRouteProvider.Valhalla(endpointUrl = endpointUrl, profile = "auto").withJsonOptions(options())
        RouteAdapter.fromWellKnownRouteProvider(provider).use { adapter ->
            val request = when (val generated = adapter.generateRequest(userLocation, waypoints)) {
                is RouteRequest.HttpPost -> Request.Builder().url(generated.url).post(generated.body.toRequestBody(JSON)).apply {
                    generated.headers.forEach { (name, value) -> header(name, value) }
                }
                is RouteRequest.HttpGet -> Request.Builder().url(generated.url).apply {
                    generated.headers.forEach { (name, value) -> header(name, value) }
                }
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Routing failed: HTTP ${response.code}")
                adapter.parseResponse(response.body.bytes())
            }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
