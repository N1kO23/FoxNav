package app.nullpointr.foxnav.server.reports

import app.nullpointr.foxnav.shared.reports.NewReport
import app.nullpointr.foxnav.shared.reports.Report
import app.nullpointr.foxnav.shared.reports.ReportType
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportStoreTest {
    private var now = 1_791_000_000_000L
    private val minute = 60_000L

    private fun store(snapshot: java.nio.file.Path? = null) = ReportStore(snapshot) { now }

    @Test
    fun reportsExpireAfterTheirTypesLifetime() = runTest {
        val store = store()
        assertNotNull(store.add(NewReport(ReportType.ANIMAL, 60.2, 24.9)))
        assertNotNull(store.add(NewReport(ReportType.ROADWORKS, 60.2, 24.9)))
        now += 31 * minute
        assertEquals(listOf(ReportType.ROADWORKS), store.feed().second.reports.map { it.type })
    }

    @Test
    fun invalidReportsAreRefused() = runTest {
        val store = store()
        assertNull(store.add(NewReport("ufo_landing", 60.2, 24.9)))
        assertNull(store.add(NewReport(ReportType.POLICE, 95.0, 24.9)))
        assertNull(store.add(NewReport(ReportType.POLICE, 60.2, 24.9, bearing = 400.0)))
        assertTrue(store.feed().second.reports.isEmpty())
    }

    @Test
    fun positionsAreRoundedToAboutAMetre() = runTest {
        val store = store()
        store.add(NewReport(ReportType.POLICE, 60.172243187, 24.920127456, bearing = 345.1993474112308))
        val report = store.feed().second.reports.single()
        assertEquals(60.17224, report.lat)
        assertEquals(24.92013, report.lon)
        assertEquals(345.2, report.bearing)
    }

    @Test
    fun stillThereKeepsAReportLonger() = runTest {
        val store = store()
        val id = store.add(NewReport(ReportType.POLICE, 60.2, 24.9))!!
        now += 50 * minute
        assertTrue(store.vote(id, stillThere = true))
        now += 25 * minute // 75 min in: past its first hour, but within half an hour of the vote
        val report = store.feed().second.reports.single()
        assertEquals(1, report.confirmations)
        now += 10 * minute
        assertTrue(store.feed().second.reports.isEmpty())
    }

    @Test
    fun noReportLastsForever() = runTest {
        val store = store()
        val id = store.add(NewReport(ReportType.POLICE, 60.2, 24.9))!!
        repeat(6) {
            now += 29 * minute
            assertTrue(store.vote(id, stillThere = true))
        }
        // Confirmed every half hour, but three lifetimes (180 min) are the most.
        now += 7 * minute
        assertTrue(store.feed().second.reports.isEmpty())
    }

    @Test
    fun goneVotesRemoveAReportOnceTheyOutnumberConfirmations() = runTest {
        val store = store()
        val id = store.add(NewReport(ReportType.HAZARD, 60.2, 24.9))!!
        store.vote(id, stillThere = true)
        store.vote(id, stillThere = false)
        assertEquals(1, store.feed().second.reports.size)
        store.vote(id, stillThere = false)
        assertTrue(store.feed().second.reports.isEmpty())
        assertFalse(store.vote(id, stillThere = true))
    }

    @Test
    fun theVersionChangesWithTheFeed() = runTest {
        val store = store()
        val (empty, _) = store.feed()
        assertEquals(empty, store.feed().first)
        store.add(NewReport(ReportType.POLICE, 60.2, 24.9))
        assertNotEquals(empty, store.feed().first)
    }

    @Test
    fun officialReportsAreReplacedButKeepConfirmations() = runTest {
        val store = store()
        fun official(id: String) = Report(id = id, type = ReportType.ACCIDENT, lat = 60.2, lon = 24.9, reported = ReportStore.iso(now), expires = "", source = FintrafficFeed.SOURCE) to now + 60 * minute
        store.replaceOfficial("Fintraffic", listOf(official("fintraffic:1"), official("fintraffic:2")))
        store.vote("fintraffic:1", stillThere = true)
        // Noes don't remove official reports: the source says when they end.
        repeat(3) { store.vote("fintraffic:2", stillThere = false) }
        val version = store.feed().first
        store.replaceOfficial("Fintraffic", listOf(official("fintraffic:1"), official("fintraffic:2")))
        assertEquals(version, store.feed().first, "nothing changed")
        store.replaceOfficial("Fintraffic", listOf(official("fintraffic:1")))
        val reports = store.feed().second.reports
        assertEquals(listOf("fintraffic:1"), reports.map { it.id })
        assertEquals(1, reports.single().confirmations)
    }

    @Test
    fun driversReportsSurviveARestart() = runTest {
        val snapshot = Files.createTempDirectory("foxnav-reports").resolve("reports.json")
        val first = store(snapshot)
        val id = first.add(NewReport(ReportType.POLICE, 60.2, 24.9, bearing = 45.0))!!
        first.replaceOfficial("Fintraffic", listOf(Report(id = "fintraffic:1", type = ReportType.ACCIDENT, lat = 60.0, lon = 25.0, reported = "", expires = "", source = FintrafficFeed.SOURCE) to now + minute))
        assertFalse("fintraffic" in snapshot.readText(), "official reports come back with the next fetch")

        val second = store(snapshot)
        val report = second.feed().second.reports.single()
        assertEquals(id, report.id)
        assertEquals(45.0, report.bearing)
        now += 61 * minute
        assertTrue(store(snapshot).feed().second.reports.isEmpty())
    }
}
