package app.nullpointr.foxnav.shared.reports

import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.convoy.moved
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportWatchTest {
    private val here = GeoPoint(60.1699, 24.9384)

    private fun report(id: String, at: GeoPoint, type: String = ReportType.POLICE, bearing: Double? = null) =
        Report(id = id, type = type, lat = at.latitude, lon = at.longitude, bearing = bearing, reported = "", expires = "")

    /** Driving east at 72 km/h, [metres] along from [here]. */
    private fun east(metres: Double, speedMps: Double = 20.0) = Fix(here.moved(metres, 90.0), speedMps = speedMps, headingDeg = 90.0, timeMs = 0)

    @Test
    fun findsReportsForOurDirectionAhead() {
        val ours = report("ours", here.moved(400.0, 90.0), bearing = 92.0)
        val oncoming = report("oncoming", here.moved(300.0, 90.0), bearing = 270.0)
        val moose = report("moose", here.moved(450.0, 90.0), type = ReportType.ANIMAL, bearing = 270.0)
        val watch = ReportWatch(listOf(oncoming, ours, moose))
        assertEquals("ours", watch.ahead(east(0.0))?.report?.id)
        // A moose is a problem whichever way its reporter drove.
        assertEquals("moose", ReportWatch(listOf(oncoming, moose)).ahead(east(0.0))?.report?.id)
    }

    @Test
    fun drivingPastAReportAsksAboutItOnce() {
        val police = report("p", here.moved(400.0, 90.0))
        val watch = ReportWatch(listOf(police))
        val passed = PassedReports()
        val asked = (0..30).mapNotNull { step ->
            val me = east(step * 20.0)
            passed.next(me, watch.ahead(me))
        }
        assertEquals(listOf("p"), asked.map { it.id })
    }

    @Test
    fun stoppingNextToAReportIsntPassingIt() {
        val accident = report("a", here.moved(100.0, 90.0), type = ReportType.ACCIDENT)
        val watch = ReportWatch(listOf(accident))
        val passed = PassedReports()
        val approach = east(0.0)
        assertNull(passed.next(approach, watch.ahead(approach)))
        // Stuck in the queue just short of it, then crawling past at walking pace.
        val queued = east(90.0, speedMps = 0.0)
        assertNull(passed.next(queued, watch.ahead(queued)))
        val crawling = east(120.0, speedMps = 1.0)
        assertNull(passed.next(crawling, watch.ahead(crawling)))
        val away = east(160.0)
        assertEquals("a", passed.next(away, watch.ahead(away))?.id)
    }

    @Test
    fun turningOffBeforeAReportDoesntAsk() {
        val hazard = report("h", here.moved(250.0, 90.0), type = ReportType.HAZARD)
        val watch = ReportWatch(listOf(hazard))
        val passed = PassedReports()
        val approach = east(0.0)
        passed.next(approach, watch.ahead(approach))
        // Turned north 100 m before it and drove on.
        val asked = (1..40).mapNotNull { step ->
            val me = Fix(here.moved(150.0, 90.0).moved(step * 20.0, 0.0), speedMps = 20.0, headingDeg = 0.0, timeMs = 0)
            passed.next(me, watch.ahead(me))
        }
        assertEquals(emptyList(), asked)
    }

    @Test
    fun ourOwnReportsArentAskedAbout() {
        val mine = report("mine", here.moved(300.0, 90.0))
        val watch = ReportWatch(listOf(mine))
        val passed = PassedReports().apply { skip("mine") }
        val asked = (0..30).mapNotNull { step -> east(step * 20.0).let { passed.next(it, watch.ahead(it)) } }
        assertEquals(emptyList(), asked)
    }
}
