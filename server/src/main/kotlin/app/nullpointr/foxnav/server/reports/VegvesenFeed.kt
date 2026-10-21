package app.nullpointr.foxnav.server.reports

import app.nullpointr.foxnav.shared.reports.Report
import app.nullpointr.foxnav.shared.reports.ReportSource
import app.nullpointr.foxnav.shared.reports.ReportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.time.Duration.Companion.hours

/**
 * Official reports for Norway from Statens vegvesen's traffic messages (DATEX II 3.1, NLOD 2.0):
 * accidents, closed roads, animals, broken-down vehicles, slippery roads, hazards such as rockfalls,
 * and roadworks while they hold up traffic. The feed needs an account: Statens vegvesen gives out
 * a user name and password on request (vegvesen.no, "Bestille tilgang til DATEX").
 */
class VegvesenFeed(
    private val store: ReportStore,
    private val username: String,
    private val password: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    /** Fetches the current situations and replaces the previous ones. On failure the previous ones stay. */
    suspend fun refresh() {
        val now = clock()
        val reports = withContext(Dispatchers.IO) { fetch { VegvesenSituations.parse(it, now) } }
        store.replaceOfficial(SOURCE.name, reports)
    }

    private fun <T> fetch(read: (InputStream) -> T): T {
        val credentials = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        val request = HttpRequest.newBuilder(URI(API))
            .header("Authorization", "Basic $credentials")
            .header("Accept-Encoding", "gzip")
            .timeout(Duration.ofSeconds(60))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "Statens vegvesen answered HTTP ${response.statusCode()}" }
        val body = if (response.headers().firstValue("Content-Encoding").orElse("") == "gzip") GZIPInputStream(response.body()) else response.body()
        return body.use(read)
    }

    companion object {
        val SOURCE = ReportSource(name = "Statens vegvesen", licence = "NLOD 2.0", url = "https://www.vegvesen.no")
        private const val API = "https://datex-server-get-v3-1.atlas.vegvesen.no/datexapi/GetSituation/pullsnapshotdata"
    }
}

/**
 * Turns a DATEX II 3 SituationPublication into reports, one per situation at most: what it is
 * comes from its records that are in force, the most pressing first (an accident before the lane
 * closure that comes with it). Roadworks count only with a record that holds up traffic, such as a
 * lane closure, traffic lights or a lower limit, as Fintraffic's do only in phases that restrict
 * traffic. Situations of other kinds (events, ferries, diversions alone) are left out.
 */
object VegvesenSituations {
    /** The reports in [xml] going on at [nowMs], with when each expires. */
    fun parse(xml: InputStream, nowMs: Long): List<Pair<Report, Long>> {
        val reports = mutableListOf<Pair<Report, Long>>()
        forEachSituation(xml) { situation -> runCatching { report(situation, nowMs) }.getOrNull()?.let(reports::add) }
        return reports
    }

    private fun report(situation: Node, nowMs: Long): Pair<Report, Long>? {
        val records = situation.children("situationRecord").filter { it.isInForce(nowMs) }
        val (type, record) = classify(records) ?: return null
        val (lat, lon) = records.sortedBy { it !== record }.firstNotNullOfOrNull { start(it) } ?: return null
        val ends = record.find("overallEndTime")?.text?.let(::millis) ?: (nowMs + OPEN_ENDED_MS)
        val place = record.find("locationDescription")?.firstValue()
        val comment = record.findAll("comment").firstNotNullOfOrNull { it.firstValue() }
        val report = Report(
            id = "vegvesen:" + (situation.attributes["id"] ?: return null),
            type = type,
            lat = lat,
            lon = lon,
            reported = ReportStore.iso(record.child("situationRecordCreationTime")?.text?.let(::millis) ?: nowMs),
            expires = ReportStore.iso(ends),
            source = VegvesenFeed.SOURCE,
            text = listOfNotNull(place, comment).joinToString(". ") { it.trim().trimEnd('.') }.ifEmpty { null }?.let { "$it." },
        )
        return report to ends
    }

    /** What the situation is, and the record that says so. */
    private fun classify(records: List<Node>): Pair<String, Node>? {
        fun ofType(vararg types: String) = records.filter { it.type in types }
        fun Node.says(element: String, values: Set<String>) = findAll(element).any { it.text in values }

        ofType("Accident").firstOrNull()?.let { return ReportType.ACCIDENT to it }
        ofType("RoadOrCarriagewayOrLaneManagement").firstOrNull { it.says("roadOrCarriagewayOrLaneManagementType", setOf("roadClosed", "carriagewayClosures")) }
            ?.let { return ReportType.ROAD_CLOSED to it }
        ofType("AnimalPresenceObstruction").firstOrNull()?.let { return ReportType.ANIMAL to it }
        ofType("VehicleObstruction").firstOrNull { it.says("vehicleObstructionType", STOPPED) }?.let { return ReportType.STOPPED_VEHICLE to it }
        (ofType("NonWeatherRelatedRoadConditions").firstOrNull { it.says("nonWeatherRelatedRoadConditionType", setOf("slipperyRoad", "oilOnRoad", "fuelOnRoad", "mudOnRoad", "leavesOnRoad")) }
            ?: ofType("WeatherRelatedRoadConditions").firstOrNull { it.says("weatherRelatedRoadConditionType", SLIPPERY) })
            ?.let { return ReportType.SLIPPERY_ROAD to it }
        (ofType("EnvironmentalObstruction", "InfrastructureDamageObstruction").firstOrNull()
            ?: ofType("GeneralObstruction").firstOrNull { it.says("obstructionType", OBSTRUCTIONS) }
            ?: ofType("WeatherRelatedRoadConditions").firstOrNull())
            ?.let { return ReportType.HAZARD to it }
        val works = ofType("MaintenanceWorks", "ConstructionWorks").firstOrNull()
        if (works != null && ofType("RoadOrCarriagewayOrLaneManagement", "GeneralNetworkManagement", "SpeedManagement").isNotEmpty()) return ReportType.ROADWORKS to works
        return null
    }

    /** Where a record's place begins: a stretch's first point, or the point. */
    private fun start(record: Node): Pair<Double, Double>? {
        val reference = record.child("locationReference") ?: return null
        val locations = reference.children("locationContainedInGroup").ifEmpty { listOf(reference) }
        for (location in locations) {
            location.find("posList")?.let { line ->
                val values = line.text.trim().split(Regex("\\s+")).mapNotNull(String::toDoubleOrNull)
                val srs = location.find("gmlLineString")?.attributes?.get("srsName").orEmpty()
                if (values.size >= 2) {
                    when {
                        srs.endsWith("25833") -> return Utm.toWgs84(values[0], values[1], zone = 33)
                        srs.endsWith("4326") -> return values[0] to values[1]
                    }
                }
            }
            (location.find("pointCoordinates") ?: location.find("coordinatesForDisplay"))?.let { point ->
                val lat = point.child("latitude")?.text?.toDoubleOrNull()
                val lon = point.child("longitude")?.text?.toDoubleOrNull()
                if (lat != null && lon != null) return lat to lon
            }
        }
        return null
    }

    /** Whether a record is in force at [nowMs]: active, or within its validity's overall times. */
    private fun Node.isInForce(nowMs: Long): Boolean {
        val validity = child("validity") ?: return true
        val ends = validity.find("overallEndTime")?.text?.let(::millis)
        return when (validity.child("validityStatus")?.text) {
            "suspended" -> false
            "active" -> ends == null || ends > nowMs
            else -> (validity.find("overallStartTime")?.text?.let(::millis) ?: Long.MIN_VALUE) <= nowMs && (ends == null || ends > nowMs)
        }
    }

    private fun Node.firstValue(): String? = find("value")?.text?.trim()?.takeIf { it.isNotEmpty() }

    private fun millis(iso: String) = runCatching { OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli() }.getOrNull()

    private val STOPPED = setOf("brokenDownVehicle", "abandonedVehicle", "vehicleOnFire", "damagedVehicle", "vehicleStuck", "unlitVehicleOnTheRoad")
    private val SLIPPERY = setOf("ice", "blackIce", "freezingOfWetRoads", "freezingRain", "freezingPavements", "snowOnTheRoad", "slushOnRoad", "looseSnow", "packedSnow", "wetIcyRoad", "iceBuildUp", "snowDrifts", "glaze", "rime")
    private val OBSTRUCTIONS = setOf("objectOnTheRoad", "obstructionOnTheRoad", "fallenTrees", "spillageOnTheRoad", "debris", "peopleOnRoadway", "rockfalls", "avalanches", "landslips", "flooding")

    /** Messages without an end are refreshed every few minutes anyway. */
    private val OPEN_ENDED_MS = 3.hours.inWholeMilliseconds

    /** An element of the publication: its local name, xsi:type (local part) and attributes, children and text. */
    private class Node(val name: String, val type: String?, val attributes: Map<String, String>) {
        val childNodes = mutableListOf<Node>()
        private val content = StringBuilder()
        val text: String get() = content.toString()

        fun append(characters: String) {
            content.append(characters)
        }

        fun child(name: String): Node? = childNodes.firstOrNull { it.name == name }

        fun children(name: String): List<Node> = childNodes.filter { it.name == name }

        /** The first element of that name at any depth. */
        fun find(name: String): Node? {
            for (node in childNodes) {
                if (node.name == name) return node
                node.find(name)?.let { return it }
            }
            return null
        }

        fun findAll(name: String): List<Node> = childNodes.flatMap { (if (it.name == name) listOf(it) else emptyList()) + it.findAll(name) }
    }

    /** Streams the publication, one situation at a time: a snapshot is tens of megabytes. */
    private fun forEachSituation(xml: InputStream, each: (Node) -> Unit) {
        val factory = XMLInputFactory.newFactory().apply {
            // A publication has no business with DTDs or entities from elsewhere.
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val reader = factory.createXMLStreamReader(xml)
        val open = ArrayDeque<Node>()
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val name = reader.localName
                    if (open.isEmpty() && name != "situation") continue
                    val attributes = (0 until reader.attributeCount).associate { reader.getAttributeLocalName(it) to reader.getAttributeValue(it) }
                    val type = reader.getAttributeValue(XSI, "type")?.substringAfter(':')
                    val node = Node(name, type, attributes - "type")
                    open.lastOrNull()?.childNodes?.add(node)
                    open.addLast(node)
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> open.lastOrNull()?.append(reader.text)
                XMLStreamConstants.END_ELEMENT -> {
                    if (open.isEmpty()) continue
                    val node = open.removeLast()
                    if (open.isEmpty()) each(node)
                }
            }
        }
        reader.close()
    }

    private const val XSI = "http://www.w3.org/2001/XMLSchema-instance"
}
