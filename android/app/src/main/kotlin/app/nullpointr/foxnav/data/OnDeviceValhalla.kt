package app.nullpointr.foxnav.data

import android.content.Context
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.road.Reachability
import com.valhalla.config.models.ValhallaConfig
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import java.io.File

/**
 * Valhalla on the phone (valhalla-mobile, ADR 0005), over the routing tiles [RoadData] keeps. It
 * takes the same JSON requests as the server's Valhalla and answers them the same way, so
 * Ferrostar's adapter and [app.nullpointr.foxnav.shared.road.TraceAttributes] read its answers
 * unchanged.
 */
class OnDeviceValhalla(private val context: Context, private val roadData: RoadData) {
    private var engine: Pair<File, Valhalla>? = null

    /** The answer to a `/route` [request], or null without road data. Throws Valhalla's error when it finds no route. */
    fun route(request: String): String? = engine()?.routeRaw(request)

    /**
     * The answer to a `/trace_attributes` [request] whose newest point is [at], or null when the
     * phone has no road data there: finding the road needs the local roads' tile, even on a motorway.
     */
    fun traceAttributes(request: String, at: GeoPoint): String? =
        if (roadData.covers(at)) engine()?.traceAttributesRaw(request) else null

    /**
     * For each of [targets], whether the phone's road data has a way there from [from], in one
     * matrix request; null without road data where the car is. Throws Valhalla's error when it
     * can't place them on a road.
     */
    fun reachable(from: GeoPoint, targets: List<GeoPoint>): List<Boolean>? {
        if (targets.isEmpty() || !roadData.covers(from)) return null
        return engine()?.matrixRaw(Reachability.request(from, targets))?.let { Reachability.parse(it, targets.size) }
    }

    /** One engine for the current build's tiles; a new build gets a new one. */
    @Synchronized
    private fun engine(): Valhalla? {
        val tiles = roadData.tilesDir ?: return null
        engine?.let { (dir, valhalla) ->
            if (dir == tiles) return valhalla
            valhalla.close()
        }
        return Valhalla(context, config(tiles)).also { engine = tiles to it }
    }

    companion object {
        fun config(tiles: File): ValhallaConfig = ValhallaConfigFactory.usingTilesDir(tiles.absolutePath).let { config ->
            // Valhalla's connectivity map comes from the tiles present, so with only some areas it
            // calls places in different areas unconnected even where the main roads join them.
            config.copy(loki = config.loki?.copy(useConnectivity = false))
        }
    }
}
