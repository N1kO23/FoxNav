package app.nullpointr.foxnav.shared.reports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportsTest {
    private fun report(type: String, bearing: Double? = null) =
        Report(id = "r", type = type, lat = 60.17, lon = 24.94, bearing = bearing, reported = "2026-10-10T18:00:00Z", expires = "2026-10-10T19:00:00Z")

    @Test
    fun feedRoundTripsAndDropsUnknownTypes() {
        val json = """
            {"format":1,"reports":[
              {"id":"a","type":"police","lat":60.1,"lon":24.9,"bearing":90,"reported":"2026-10-10T18:00:00Z","expires":"2026-10-10T19:00:00Z","confirmations":2},
              {"id":"b","type":"ufo_landing","lat":60.2,"lon":24.8,"reported":"2026-10-10T18:00:00Z","expires":"2026-10-10T19:00:00Z"},
              {"id":"c","type":"roadworks","lat":60.3,"lon":24.7,"reported":"2026-10-10T18:00:00Z","expires":"2026-10-11T18:00:00Z","source":{"name":"Fintraffic","licence":"CC BY 4.0"},"text":"Tie 51, Espoo. Tietyö."}
            ]}
        """.trimIndent()
        val feed = ReportFeed.parse(json)
        assertEquals(listOf("a", "c"), feed.reports.map { it.id })
        assertEquals(2, feed.reports[0].confirmations)
        assertEquals("Fintraffic", feed.reports[1].source?.name)
        assertEquals(feed, ReportFeed.parse(feed.toJson()))
    }

    @Test
    fun theFormatAndConfirmationsAreAlwaysWritten() {
        // Other clients shouldn't have to know our defaults.
        val json = ReportFeed(reports = listOf(report(ReportType.HAZARD))).toJson()
        assertTrue("\"format\":1" in json, json)
        assertTrue("\"confirmations\":0" in json, json)
    }

    @Test
    fun otherFormatsAreRefused() {
        assertFailsWith<IllegalArgumentException> { ReportFeed.parse("""{"format":2,"reports":[]}""") }
    }

    @Test
    fun directionalReportsConcernOnlyTheirDirection() {
        val police = report(ReportType.POLICE, bearing = 90.0)
        assertTrue(police.concerns(100.0))
        assertFalse(police.concerns(270.0))
        // Without a bearing it concerns both directions.
        assertTrue(report(ReportType.POLICE).concerns(270.0))
    }

    @Test
    fun wholeRoadReportsConcernBothDirections() {
        // A closed road or a moose is a problem whichever way you drive.
        assertTrue(report(ReportType.ROAD_CLOSED, bearing = 90.0).concerns(270.0))
        assertTrue(report(ReportType.ANIMAL, bearing = 90.0).concerns(270.0))
    }

    @Test
    fun policeReportsFollowTheCameraRules() {
        assertFalse(report(ReportType.SPEED_CHECK).allowedIn("DE"))
        assertFalse(report(ReportType.POLICE).allowedIn("FR"))
        assertTrue(report(ReportType.POLICE).allowedIn("FI"))
        assertTrue(report(ReportType.ACCIDENT).allowedIn("DE"))
    }
}
