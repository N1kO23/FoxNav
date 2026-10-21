package app.nullpointr.foxnav.server.reports

import app.nullpointr.foxnav.shared.reports.ReportType
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VegvesenSituationsTest {
    /** When Statens vegvesen published the sample. */
    private val now = OffsetDateTime.parse("2025-09-11T13:55:02+02:00").toInstant().toEpochMilli()

    private fun parse() = VegvesenSituations.parse(javaClass.getResourceAsStream("/vegvesen-situations.xml")!!, now)

    @Test
    fun situationsBecomeReportsOfTheirKind() {
        val types = parse().associate { (report, _) -> report.id.removePrefix("vegvesen:") to report.type }
        assertEquals(
            mapOf(
                "NPRA_HBT_11-09-2025.145565" to ReportType.ACCIDENT,
                // A closure after a landslip: closed is what drivers need to know.
                "NPRA_HBT_11-04-2025.68022" to ReportType.ROAD_CLOSED,
                "NPRA_HBT_27-06-2025.107966" to ReportType.ANIMAL,
                "NPRA_HBT_11-09-2025.145553" to ReportType.STOPPED_VEHICLE,
                "NPRA_HBT_04-09-2025.141560" to ReportType.SLIPPERY_ROAD,
                "NPRA_HBT_11-09-2025.145568" to ReportType.HAZARD,
                "NPRA_HBT_10-09-2025.145097" to ReportType.HAZARD,
                "NPRA_HBT_11-09-2025.145550" to ReportType.HAZARD,
                // Roadworks with traffic lights hold traffic up; works by the roadside don't.
                "NPRA_HBT_12-05-2025.82468" to ReportType.ROADWORKS,
            ),
            types,
        )
    }

    @Test
    fun plannedAndMinorWorksAreLeftOut() {
        val ids = parse().map { it.first.id }
        // A night closure and a lane closure next week, and works with no restriction.
        for (left in listOf("NPRA_HBT_11-09-2025.145557", "NPRA_HBT_10-09-2025.144801", "NPRA_HBT_26-08-2025.136887")) {
            assertTrue("vegvesen:$left" !in ids, left)
        }
    }

    @Test
    fun theAccidentAsDriversSeeIt() {
        val (accident, ends) = parse().single { it.first.type == ReportType.ACCIDENT }
        assertEquals(OffsetDateTime.parse("2025-09-11T17:07:03+02:00").toInstant().toEpochMilli(), ends)
        assertEquals("Statens vegvesen", accident.source?.name)
        assertEquals("NLOD 2.0", accident.source?.licence)
        assertEquals("Fv. 2722 Stubberudveien 131A i DRAMMEN, Buskerud. Trafikkulykke, vegen er stengt.", accident.text)
        // Its point, in Drammen.
        assertNear(59.725075, accident.lat)
        assertNear(10.109173, accident.lon)
    }

    @Test
    fun stretchesBeginWhereTheirLineDoes() {
        // The landslip closure's line, given in UTM 33, begins at its first point, not its middle.
        val closed = parse().single { it.first.type == ReportType.ROAD_CLOSED }.first
        assertTrue(closed.lat in 57.9..71.2 && closed.lon in 4.0..31.2, "in Norway: ${closed.lat}, ${closed.lon}")
    }

    @Test
    fun utmComesOutAsLatitudeAndLongitude() {
        // EPSG:25833 points as PROJ converts them: central Oslo, the west coast and eastern Finnmark
        // (both far outside the zone's 6°, as Norway uses zone 33 throughout), and the zone's origin.
        for ((utm, expected) in listOf(
            (262_115.0 to 6_649_398.0) to (59.913235200 to 10.744308246),
            (-75_000.0 to 6_750_000.0) to (60.472325829 to 4.511443215),
            (1_100_000.0 to 7_800_000.0) to (69.615003212 to 30.580425647),
            (500_000.0 to 0.0) to (0.0 to 15.0),
        )) {
            val (lat, lon) = Utm.toWgs84(utm.first, utm.second)
            // A millionth of a degree: about 10 cm.
            assertNear(expected.first, lat)
            assertNear(expected.second, lon)
        }
    }

    private fun assertNear(expected: Double, actual: Double, tolerance: Double = 1e-6) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, was $actual")
}
