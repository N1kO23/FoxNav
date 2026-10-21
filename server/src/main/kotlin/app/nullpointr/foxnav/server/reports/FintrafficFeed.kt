package app.nullpointr.foxnav.server.reports

import app.nullpointr.foxnav.shared.reports.Report
import app.nullpointr.foxnav.shared.reports.ReportSource
import app.nullpointr.foxnav.shared.reports.ReportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.hours

/**
 * Official reports for Finland from Fintraffic's traffic messages (digitraffic.fi, CC BY 4.0):
 * accidents, road closures and roadworks, so the map is useful before many people report.
 */
class FintrafficFeed(private val store: ReportStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    /** Fetches the current messages and replaces the previous ones. On failure the previous ones stay. */
    suspend fun refresh() {
        val now = clock()
        val reports = withContext(Dispatchers.IO) { FEEDS.flatMap { feed -> FintrafficMessages.parse(fetch(feed), now) } }
        store.replaceOfficial(SOURCE.name, reports)
    }

    private fun fetch(feed: String): String {
        val request = HttpRequest.newBuilder(URI("$API/$feed"))
            .header("Digitraffic-User", "FoxNav-server")
            .header("Accept-Encoding", "gzip")
            .timeout(Duration.ofSeconds(30))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "Digitraffic answered HTTP ${response.statusCode()}" }
        val body = if (response.headers().firstValue("Content-Encoding").orElse("") == "gzip") GZIPInputStream(response.body()) else response.body()
        return body.use { it.readBytes().decodeToString() }
    }

    companion object {
        val SOURCE = ReportSource(name = "Fintraffic", licence = "CC BY 4.0", url = "https://www.digitraffic.fi")

        // The situations going on now, and those that ended within the hour (left out when parsing).
        // v1's /messages was switched off after 20 October 2026.
        private const val API = "https://tie.digitraffic.fi/api/traffic-message/v2"
        private val FEEDS = listOf("traffic-announcements", "roadworks")
    }
}

/** Turns Digitraffic traffic messages (GeoJSON) into reports. Messages of other kinds are left out. */
object FintrafficMessages {
    private val json = Json { ignoreUnknownKeys = true }

    /** The reports in [geoJson] still going on at [nowMs], with when each expires. */
    fun parse(geoJson: String, nowMs: Long): List<Pair<Report, Long>> =
        json.parseToJsonElement(geoJson).jsonObject["features"]?.jsonArray.orEmpty().mapNotNull { feature ->
            runCatching { report(feature.jsonObject, nowMs) }.getOrNull()
        }

    private fun report(feature: JsonObject, nowMs: Long): Pair<Report, Long>? {
        val properties = feature["properties"]?.jsonObject ?: return null
        val announcement = properties["announcements"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val kind = properties.enumValue("trafficAnnouncementType").orEmpty()
        if (kind == "ENDED" || kind == "RETRACTED") return null
        val names = announcement["features"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.string("name")?.lowercase() }
        val roadWork = properties.enumValue("situationType") == "ROAD_WORK"
        val type = when {
            roadWork -> ReportType.ROADWORKS
            "ACCIDENT" in kind || names.any { "onnettomuus" in it } -> ReportType.ACCIDENT
            names.any { it.startsWith("tie on suljettu") } -> ReportType.ROAD_CLOSED
            names.any { "eläin" in it || "hirvi" in it || "poro" in it } -> ReportType.ANIMAL
            names.any { "liuka" in it || "liukk" in it } -> ReportType.SLIPPERY_ROAD
            else -> return null // congestion, ferries, detours...
        }
        // Roadworks go on for months in phases; only a phase going on now, and one that
        // restricts traffic (not just a survey by the roadside), counts.
        val timing = if (roadWork) {
            announcement["roadWorkPhases"]?.jsonArray.orEmpty().map { it.jsonObject }
                .filter { it.enumValue("severity") != "LOW" }
                .mapNotNull { it["timeAndDuration"] as? JsonObject }
                .firstOrNull { it.isGoingOn(nowMs) }
                ?: return null
        } else {
            announcement["timeAndDuration"] as? JsonObject
        }
        val starts = timing?.string("startTime")?.let(::millis)
        if (starts != null && starts > nowMs) return null // planned, not yet
        val ends = timing?.string("endTime")?.let(::millis) ?: (nowMs + OPEN_ENDED_MS)
        if (ends <= nowMs) return null
        val (lon, lat) = firstPosition(feature["geometry"]) ?: return null
        val report = Report(
            id = "fintraffic:" + (properties.string("situationId") ?: return null),
            type = type,
            lat = lat,
            lon = lon,
            reported = ReportStore.iso(properties.string("releaseTime")?.let(::millis) ?: nowMs),
            expires = ReportStore.iso(ends),
            source = FintrafficFeed.SOURCE,
            text = announcement.string("title")?.trim(),
        )
        return report to ends
    }

    /** The first [lon, lat] of a Point, LineString or MultiLineString: where the affected stretch begins. */
    private fun firstPosition(geometry: JsonElement?): Pair<Double, Double>? {
        var coordinates: JsonElement = (geometry as? JsonObject)?.get("coordinates") ?: return null
        while (coordinates is JsonArray && coordinates.firstOrNull() is JsonArray) coordinates = coordinates.first()
        val position = coordinates as? JsonArray ?: return null
        val lon = position.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
        val lat = position.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
        return lon to lat
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    /** An enum value as v1 spelled it: v2 sends `road work` for `ROAD_WORK`, `low` for `LOW`. */
    private fun JsonObject.enumValue(key: String) = string(key)?.trim()?.uppercase()?.replace(' ', '_')

    /** Whether a `timeAndDuration` has started and not ended at [nowMs]; open ends count as going on. */
    private fun JsonObject.isGoingOn(nowMs: Long): Boolean {
        val started = string("startTime")?.let(::millis)?.let { it <= nowMs } ?: true
        val notEnded = string("endTime")?.let(::millis)?.let { it > nowMs } ?: true
        return started && notEnded
    }

    private fun millis(iso: String) = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    /** Messages without an end are refreshed every few minutes anyway. */
    private val OPEN_ENDED_MS = 3.hours.inWholeMilliseconds
}
