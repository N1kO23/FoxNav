package app.nullpointr.foxnav.server.reports

import app.nullpointr.foxnav.shared.reports.ReportType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FintrafficMessagesTest {
    private val now = Instant.parse("2026-10-10T20:00:00Z").toEpochMilli()

    private fun announcement(id: String, kind: String, names: List<String>, start: String?, end: String?, geometry: String) = """
        {"type":"Feature","geometry":$geometry,"properties":{
          "situationId":"$id","situationType":"TRAFFIC_ANNOUNCEMENT","trafficAnnouncementType":"$kind",
          "releaseTime":"2026-10-10T19:30:31.249Z",
          "announcements":[{"language":"FI","title":"Tie 51, Espoo. Liikennetiedote. ",
            "features":[${names.joinToString { """{"name":"$it"}""" }}],
            "timeAndDuration":{${listOfNotNull(start?.let { "\"startTime\":\"$it\"" }, end?.let { "\"endTime\":\"$it\"" }).joinToString()}}}]}}
    """.trimIndent()

    private fun roadWork(id: String, phases: List<Triple<String, String, String>>) = """
        {"type":"Feature","geometry":{"type":"Point","coordinates":[25.5,62.2]},"properties":{
          "situationId":"$id","situationType":"ROAD_WORK","releaseTime":"2026-10-01T08:00:00Z",
          "announcements":[{"title":"Tie 4, Jyväskylä. Tietyö.","features":[],
            "timeAndDuration":{"startTime":"2026-05-01T00:00:00Z","endTime":"2027-05-01T00:00:00Z"},
            "roadWorkPhases":[${phases.joinToString { (severity, start, end) -> """{"severity":"$severity","timeAndDuration":{"startTime":"$start","endTime":"$end"}}""" }}]}]}}
    """.trimIndent()

    private fun parse(vararg features: String) = FintrafficMessages.parse("""{"type":"FeatureCollection","features":[${features.joinToString()}]}""", now)

    @Test
    fun accidentsAndClosuresBecomeReports() {
        val line = """{"type":"MultiLineString","coordinates":[[[24.80,60.18],[24.81,60.19]],[[24.82,60.20],[24.83,60.21]]]}"""
        val reports = parse(
            announcement("GUID1", "PRELIMINARY_ACCIDENT_REPORT", listOf("Onnettomuus"), "2026-10-10T19:30:00Z", "2026-10-10T21:00:00Z", line),
            announcement("GUID2", "GENERAL", listOf("Tie on suljettu liikenteeltä", "Paikalla on kiertotieopastus"), "2026-10-10T18:00:00Z", null, line),
            announcement("GUID3", "GENERAL", listOf("Hirvi tiellä"), "2026-10-10T19:55:00Z", "2026-10-10T20:30:00Z", """{"type":"Point","coordinates":[26.0,61.0]}"""),
        )
        assertEquals(listOf(ReportType.ACCIDENT, ReportType.ROAD_CLOSED, ReportType.ANIMAL), reports.map { it.first.type })
        val (accident, ends) = reports[0]
        assertEquals("fintraffic:GUID1", accident.id)
        // Where the affected stretch begins.
        assertEquals(60.18, accident.lat)
        assertEquals(24.80, accident.lon)
        assertEquals(Instant.parse("2026-10-10T21:00:00Z").toEpochMilli(), ends)
        assertEquals("Fintraffic", accident.source?.name)
        assertEquals("Tie 51, Espoo. Liikennetiedote.", accident.text)
        // No end time: kept until a later fetch says otherwise.
        assertTrue(reports[1].second > now)
    }

    @Test
    fun otherMessagesAreLeftOut() {
        val point = """{"type":"Point","coordinates":[24.8,60.2]}"""
        val reports = parse(
            announcement("GUID4", "GENERAL", listOf("Liikenne ruuhkautuu"), "2026-10-10T19:00:00Z", null, point),
            announcement("GUID5", "GENERAL", listOf("Lautan kantavuus on muuttunut"), "2026-10-10T19:00:00Z", null, point),
            announcement("GUID6", "ENDED", listOf("Onnettomuus"), "2026-10-10T19:00:00Z", null, point),
            announcement("GUID7", "GENERAL", listOf("Onnettomuus"), "2026-10-11T08:00:00Z", null, point),
            announcement("GUID8", "GENERAL", listOf("Onnettomuus"), "2026-10-10T19:00:00Z", null, "null"),
        )
        assertTrue(reports.isEmpty(), reports.toString())
    }

    @Test
    fun roadworksCountOnlyDuringARestrictingPhase() {
        val reports = parse(
            roadWork("GUID9", listOf(Triple("HIGH", "2026-10-01T00:00:00Z", "2026-10-20T00:00:00Z"))),
            roadWork("GUID10", listOf(Triple("LOW", "2026-10-01T00:00:00Z", "2026-10-20T00:00:00Z"))),
            roadWork("GUID11", listOf(Triple("HIGHEST", "2026-11-01T00:00:00Z", "2026-11-20T00:00:00Z"))),
        )
        val (roadworks, ends) = reports.single()
        assertEquals("fintraffic:GUID9", roadworks.id)
        assertEquals(ReportType.ROADWORKS, roadworks.type)
        assertEquals(Instant.parse("2026-10-20T00:00:00Z").toEpochMilli(), ends)
    }

    @Test
    fun theLiveFeedsShapeParses() {
        // Unknown fields and nested structure as Digitraffic sends them don't break parsing.
        val reports = parse(
            """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[25.0,60.3],[25.1,60.4]]},"properties":{"situationId":"GUID12","situationType":"TRAFFIC_ANNOUNCEMENT","trafficAnnouncementType":"ACCIDENT_REPORT","version":3,"releaseTime":"2026-10-10T19:00:00Z","versionTime":"2026-10-10T19:10:00Z","announcements":[{"language":"FI","title":"Tie 7, Porvoo.","location":{"countryCode":6},"locationDetails":{},"features":[{"name":"Onnettomuus","quantity":1}],"roadWorkPhases":[],"timeAndDuration":{"startTime":"2026-10-10T18:50:00Z"},"sender":"Fintraffic Tieliikennekeskus Helsinki"}],"contact":{"phone":"02002100"},"dataUpdatedTime":"2026-10-10T19:10:30Z"}}""",
        )
        assertEquals(60.3, reports.single().first.lat)
    }
}
