package app.nullpointr.foxnav.drive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nullpointr.foxnav.ui.theme.LocalNight
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.GeographicCoordinate

/** The route being driven. */
@Composable
@MaplibreComposable
fun NavigationRoute(geometry: List<GeographicCoordinate>) {
    if (geometry.size < 2) return
    val feature = remember(geometry) {
        Feature(LineString(geometry.map { Position(longitude = it.lng, latitude = it.lat) }), JsonObject(emptyMap()))
    }
    RouteLine("foxnav-route", rememberGeoJsonSource(GeoJsonData.Features(feature)))
}

/**
 * A route as the design draws it: a cyan line which, at night, glows like neon on the dark map,
 * a soft bloom around it and a bright core. The one place the map glows (design/README.md: glow
 * sparingly). By day the glow would only muddy the line, so it's the plain line.
 */
@Composable
@MaplibreComposable
fun RouteLine(id: String, source: Source) {
    val route = MaterialTheme.colorScheme.secondary
    if (LocalNight.current) {
        LineLayer(
            id = "$id-bloom",
            source = source,
            color = const(route),
            opacity = const(0.16f),
            width = const(30.dp),
            blur = const(18.dp),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
        )
        LineLayer(
            id = "$id-glow",
            source = source,
            color = const(route),
            opacity = const(0.45f),
            width = const(16.dp),
            blur = const(8.dp),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
        )
    }
    LineLayer(
        id = "$id-line",
        source = source,
        color = const(route),
        width = const(8.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
    if (LocalNight.current) {
        LineLayer(
            id = "$id-core",
            source = source,
            color = const(Color.White),
            opacity = const(0.55f),
            width = const(2.5.dp),
            blur = const(1.dp),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
        )
    }
}
