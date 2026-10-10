package app.nullpointr.foxnav.shared.reports

import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.safety.CameraRules
import app.nullpointr.foxnav.shared.safety.DIRECTION_TOLERANCE_DEG
import app.nullpointr.foxnav.shared.safety.angleBetween
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a report is about (protocol/SPEC.md §4). */
object ReportType {
    const val POLICE = "police"
    const val SPEED_CHECK = "speed_check"
    const val ACCIDENT = "accident"
    const val HAZARD = "hazard"
    const val STOPPED_VEHICLE = "stopped_vehicle"
    const val ROADWORKS = "roadworks"
    const val SLIPPERY_ROAD = "slippery_road"
    const val ANIMAL = "animal"
    const val ROAD_CLOSED = "road_closed"

    val ALL = listOf(POLICE, SPEED_CHECK, ACCIDENT, HAZARD, STOPPED_VEHICLE, ROADWORKS, SLIPPERY_ROAD, ANIMAL, ROAD_CLOSED)

    /** Types that concern one direction of travel, the reporter's; the rest concern the whole road. */
    val DIRECTIONAL = setOf(POLICE, SPEED_CHECK, ACCIDENT, HAZARD, STOPPED_VEHICLE)

    /** Traffic enforcement: hidden where warning about speed checks is forbidden, like cameras. */
    val ENFORCEMENT = setOf(POLICE, SPEED_CHECK)
}

/** Every current report of a server: `GET {reports}`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReportFeed(@EncodeDefault val format: Int = FORMAT, val reports: List<Report> = emptyList()) {
    fun toJson(): String = FoxNavJson.encodeToString(serializer(), this)

    companion object {
        const val FORMAT = 1

        /** Reads a feed; reports of types this version doesn't know are left out. */
        fun parse(json: String): ReportFeed {
            val feed = FoxNavJson.decodeFromString(serializer(), json)
            require(feed.format == FORMAT) { "Report feed format ${feed.format}, this app reads $FORMAT" }
            return feed.copy(reports = feed.reports.filter { it.type in ReportType.ALL })
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Report(
    val id: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    /** The direction of travel it concerns, degrees clockwise from north; absent for both. */
    val bearing: Double? = null,
    /** ISO 8601, UTC. */
    val reported: String,
    val expires: String,
    /** How many drivers said it's still there. */
    @EncodeDefault val confirmations: Int = 0,
    /** Where an official report comes from; absent for drivers' reports. */
    val source: ReportSource? = null,
    /** The official description, in the source's language. */
    val text: String? = null,
) {
    val point: GeoPoint get() = GeoPoint(lat, lon)

    /** Whether it may be shown in the country [countryCode] (unknown counts as allowed); see [CameraRules]. */
    fun allowedIn(countryCode: String?): Boolean = type !in ReportType.ENFORCEMENT || CameraRules.warningsAllowed(countryCode)

    /** Whether it concerns traffic heading towards [headingDeg]. */
    fun concerns(headingDeg: Double): Boolean =
        bearing == null || type !in ReportType.DIRECTIONAL || angleBetween(bearing, headingDeg) <= DIRECTION_TOLERANCE_DEG
}

@Serializable
data class ReportSource(val name: String, val licence: String? = null, val url: String? = null)

/** `POST {reports}` */
@Serializable
data class NewReport(val type: String, val lat: Double, val lon: Double, val bearing: Double? = null)

/** The answer to `POST {reports}`. */
@Serializable
data class ReportCreated(val id: String)

/** `POST {reports}/{id}/votes` */
@Serializable
data class Vote(@SerialName("still_there") val stillThere: Boolean)
