package app.nullpointr.foxnav.shared.routing

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadSelectionsTest {
    private val manifest = FINLAND_SAMPLE
    private val helsinki = setOf(RoutingTile(0, 3381), RoutingTile(1, 54204), RoutingTile(2, 864819), RoutingTile(2, 864820))
    private val area = RoadSelection.of("area", RoadSelectionKind.AREA, "Helsinki", createdMs = 0, helsinki)

    @Test
    fun serverWideSelectionsTakeWhateverTheBuildHas() {
        val all = RoadSelection("all", RoadSelectionKind.ALL, "All roads", 0).tilesIn(manifest)
        val main = RoadSelection("main", RoadSelectionKind.MAIN_ROADS, "Main roads", 0).tilesIn(manifest)

        assertEquals(manifest.all, all)
        assertEquals(setOf(RoutingTile(0, 3381), RoutingTile(1, 54204)), main)
    }

    @Test
    fun areasKeepOnlyTilesTheBuildHas() {
        // A tile a later build dropped, and a key from who knows where.
        val stale = area.copy(tiles = area.tiles + "2/999999" + "not a tile")

        assertEquals(helsinki, stale.tilesIn(manifest))
    }

    @Test
    fun theWantedTilesAreEverySelectionsTogether() {
        val trip = RoadSelection.of("trip", RoadSelectionKind.TRIP, "Hyvinkää", 0, setOf(RoutingTile(2, 864819), RoutingTile(2, 867699)))
        val selections = RoadSelections().with(area).with(trip)

        assertEquals(helsinki + RoutingTile(2, 867699), selections.wanted(manifest))
        assertEquals(helsinki, selections.without("trip").wanted(manifest))
    }

    @Test
    fun addingAgainReplaces() {
        val renamed = area.copy(name = "Central Helsinki")

        assertEquals(listOf(renamed), RoadSelections().with(area).with(renamed).selections)
    }

    @Test
    fun tripsGoAfterAMonth() {
        val day = 24 * 60 * 60 * 1000L
        val trip = RoadSelection.of("trip", RoadSelectionKind.TRIP, "Tampere", createdMs = 0, emptySet())
        val selections = RoadSelections(listOf(area, trip))

        assertEquals(selections, selections.withoutExpiredTrips(nowMs = 30 * day))
        assertEquals(listOf(area), selections.withoutExpiredTrips(nowMs = 31 * day).selections)
    }

    @Test
    fun survivesBeingStored() {
        val selections = RoadSelections(listOf(area, RoadSelection("all", RoadSelectionKind.ALL, "All roads", 5)))

        assertEquals(selections, RoadSelections.parse(selections.toJson()))
    }

    @Test
    fun coverageCountsWhatsThereAndWhatsLeftToDownload() {
        val coverage = manifest.coverage(helsinki, present = setOf(RoutingTile(0, 3381), RoutingTile(2, 864819)))

        assertEquals(71_900_000, coverage.bytes)
        assertEquals(30_900_000, coverage.presentBytes)
        // Level 1's tile and the second local tile.
        assertEquals(14_000_000, coverage.missingDownload)
        assertFalse(coverage.complete)
        assertTrue(manifest.coverage(helsinki, present = manifest.all).complete)
    }

    @Test
    fun tilesHaveKeys() {
        assertEquals("2/864819", RoutingTile(2, 864819).key)
        assertEquals(RoutingTile(2, 864819), RoutingTile.parse("2/864819"))
        assertNull(RoutingTile.parse("2/"))
        assertNull(RoutingTile.parse("864819"))
    }

    @Test
    fun findsTheLocalTileAtAPoint() {
        assertEquals(RoutingTile(2, 864819), manifest.localTileAt(GeoPoint(60.1699, 24.9410)))
        assertEquals(2, manifest.localLevel)
    }
}
