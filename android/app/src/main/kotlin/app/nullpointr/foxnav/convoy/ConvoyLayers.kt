package app.nullpointr.foxnav.convoy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.shared.convoy.ConvoyMember
import app.nullpointr.foxnav.shared.convoy.MotionTrack
import app.nullpointr.foxnav.shared.convoy.Signal
import app.nullpointr.foxnav.shared.convoy.signal
import app.nullpointr.foxnav.ui.icons.FoxIcons
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * The other convoy cars, gliding between their once-a-second reports (see [MotionTrack]), and
 * the regroup point. Cars fade when their reports stop coming.
 */
@Composable
@MaplibreComposable
fun ConvoyLayers(members: Map<Long, ConvoyMember>, regroup: RegroupPoint?) {
    val colors = MaterialTheme.colorScheme
    val latestMembers by rememberUpdatedState(members)
    var cars by remember { mutableStateOf(FeatureCollection<Point, JsonObject>(emptyList())) }

    LaunchedEffect(Unit) {
        val tracks = HashMap<Long, MotionTrack>()
        while (true) {
            // Frames stop while the app is in the background, and so does this loop.
            withFrameMillis { }
            val now = System.currentTimeMillis()
            val current = latestMembers
            tracks.keys.retainAll(current.keys)
            cars = FeatureCollection(
                current.values.mapNotNull { member ->
                    val fix = member.fix ?: return@mapNotNull null
                    val track = tracks.getOrPut(member.id) { MotionTrack() }
                    track.update(fix, now)
                    val position = track.positionAt(now) ?: return@mapNotNull null
                    Feature(
                        geometry = Point(Position(longitude = position.longitude, latitude = position.latitude)),
                        properties = JsonObject(
                            mapOf(
                                "name" to JsonPrimitive(member.profile?.display_name.orEmpty()),
                                "color" to JsonPrimitive(member.colorHex()),
                                "heading" to JsonPrimitive(fix.headingDeg),
                                "opacity" to JsonPrimitive(
                                    when (member.signal(now)) {
                                        Signal.LIVE, Signal.WAITING -> 1.0
                                        Signal.STALE -> 0.5
                                        Signal.LOST -> 0.3
                                    },
                                ),
                            ),
                        ),
                    )
                },
            )
            delay(FRAME_INTERVAL_MS)
        }
    }

    val carSource = rememberGeoJsonSource(GeoJsonData.Features(cars))
    SymbolLayer(
        id = "foxnav-convoy-cars",
        source = carSource,
        iconImage = image(rememberVectorPainter(FoxIcons.CarMarker), size = DpSize(28.dp, 28.dp), drawAsSdf = true),
        iconColor = feature["color"].convertToColor(),
        iconHaloColor = const(colors.background),
        iconHaloWidth = const(2.dp),
        iconOpacity = feature["opacity"].asNumber(),
        iconRotate = feature["heading"].asNumber(),
        iconRotationAlignment = const(IconRotationAlignment.Map),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        textField = feature["name"].asString().cast(),
        textFont = const(listOf(LABEL_FONT)),
        textSize = const(13.sp),
        textColor = const(colors.onBackground),
        textHaloColor = const(colors.background),
        textHaloWidth = const(1.5.dp),
        textOpacity = feature["opacity"].asNumber(),
        textAnchor = const(SymbolAnchor.Top),
        textOffset = offset(0.em, 1.4.em),
        // Names give way when cars bunch up; the cars themselves always show.
        textOptional = const(true),
    )

    val pinFeatures = regroup?.let { point ->
        FeatureCollection(
            listOf(
                Feature(
                    geometry = Point(Position(longitude = point.point.longitude, latitude = point.point.latitude)),
                    properties = JsonObject(mapOf("name" to JsonPrimitive(point.name))),
                ),
            ),
        )
    } ?: FeatureCollection<Point, JsonObject>(emptyList())
    val pinSource = rememberGeoJsonSource(GeoJsonData.Features(pinFeatures))
    SymbolLayer(
        id = "foxnav-convoy-regroup",
        source = pinSource,
        iconImage = image(rememberVectorPainter(FoxIcons.FlagMarker), size = DpSize(30.dp, 30.dp), drawAsSdf = true),
        iconColor = const(colors.tertiary),
        iconHaloColor = const(colors.background),
        iconHaloWidth = const(2.dp),
        iconAnchor = const(SymbolAnchor.BottomLeft),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        textField = feature["name"].asString().cast(),
        textFont = const(listOf(LABEL_FONT)),
        textSize = const(13.sp),
        textColor = const(colors.tertiary),
        textHaloColor = const(colors.background),
        textHaloWidth = const(1.5.dp),
        textAnchor = const(SymbolAnchor.Top),
        textOffset = offset(0.em, 0.6.em),
        textOptional = const(true),
    )
}

/** The member's chosen colour as `#RRGGBB`. */
fun ConvoyMember.colorHex(): String = "#%06X".format((profile?.car?.color_rgb ?: ConvoyPalette.first()) and 0xFFFFFF)

// The FoxNav map styles' label font (infra/styles); other servers' styles may lack it, and then names don't show.
private const val LABEL_FONT = "Noto Sans Medium"
private const val FRAME_INTERVAL_MS = 33L
