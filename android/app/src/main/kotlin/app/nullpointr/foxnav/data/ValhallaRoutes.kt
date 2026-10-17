package app.nullpointr.foxnav.data

import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.withJsonOptions
import com.valhalla.valhalla.ValhallaException
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
 * Without a connection, or when the server can't be reached, the phone's own Valhalla answers
 * the same request from its road data (ADR 0005).
 */
class ValhallaRoutes(
    private val client: OkHttpClient,
    private val endpointUrl: String,
    private val onDevice: OnDeviceValhalla?,
    private val online: () -> Boolean,
    private val options: () -> Map<String, Any>,
) : CustomRouteProvider {
    override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> = withContext(Dispatchers.IO) {
        val provider = WellKnownRouteProvider.Valhalla(endpointUrl = endpointUrl, profile = "auto").withJsonOptions(options())
        RouteAdapter.fromWellKnownRouteProvider(provider).use { adapter ->
            val request = adapter.generateRequest(userLocation, waypoints)
            val unreachable = if (online()) {
                try {
                    return@use adapter.parseResponse(fromServer(request))
                } catch (error: ServerUnreachable) {
                    error
                }
            } else {
                null
            }
            val body = (request as? RouteRequest.HttpPost)?.body?.decodeToString()
            val answer = try {
                body?.let { onDevice?.route(it) }
            } catch (error: ValhallaException) {
                throw IOException(if (unreachable != null) "${unreachable.message}, and no route from the road data on the phone" else offlineProblem(error), error)
            }
            answer?.let { adapter.parseResponse(it.toByteArray()) }
                ?: throw unreachable ?: IOException("No connection, and no road data on the phone. Download some in Offline maps.")
        }
    }

    /** The server's answer. Valhalla's own refusals (no route there) are answers too; only failing to get one falls back. */
    private fun fromServer(generated: RouteRequest): ByteArray {
        val request = when (generated) {
            is RouteRequest.HttpPost -> Request.Builder().url(generated.url).post(generated.body.toRequestBody(JSON)).apply {
                generated.headers.forEach { (name, value) -> header(name, value) }
            }
            is RouteRequest.HttpGet -> Request.Builder().url(generated.url).apply {
                generated.headers.forEach { (name, value) -> header(name, value) }
            }
        }.build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw ServerUnreachable("Couldn't reach the server", error)
        }
        return response.use {
            when {
                it.isSuccessful -> it.body.bytes()
                it.code >= 500 -> throw ServerUnreachable("The server's routing failed (HTTP ${it.code})", null)
                else -> throw IOException("Routing failed: HTTP ${it.code}")
            }
        }
    }

    private class ServerUnreachable(message: String, cause: Throwable?) : IOException(message, cause)

    private companion object {
        val JSON = "application/json".toMediaType()

        /** Valhalla's "no road near the location" codes: road data missing there, most likely. */
        val NO_ROADS_NEARBY = listOf("code=171", "code=172")

        fun offlineProblem(error: ValhallaException): String =
            if (NO_ROADS_NEARBY.any { it in error.message.orEmpty() }) "No connection, and no road data on the phone for this trip"
            else "No connection, and no route in the road data on the phone"
    }
}
