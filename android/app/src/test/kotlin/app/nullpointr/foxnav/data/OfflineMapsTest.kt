package app.nullpointr.foxnav.data

import org.maplibre.spatialk.geojson.BoundingBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineMapsTest {
    @Test
    fun aSpotNeedsOneTilePerZoom() {
        // A few metres in Helsinki, inside one tile at every zoom up to street level.
        val spot = BoundingBox(west = 24.94001, south = 60.16801, east = 24.94002, north = 60.16802)
        assertEquals(OfflineMaps.MAX_ZOOM + 1L, OfflineMaps.estimate(spot).tiles)
    }

    @Test
    fun aCityNeedsHundredsAndTheFirstAreaBringsTheFonts() {
        // Roughly central and eastern Helsinki, about 9 by 6 km.
        val helsinki = BoundingBox(west = 24.93, south = 60.16, east = 25.08, north = 60.215)
        val estimate = OfflineMaps.estimate(helsinki)
        assertTrue(estimate.tiles in 50..200, "${estimate.tiles}")
        assertTrue(estimate.bytes in 2_000_000L..15_000_000L, "${estimate.bytes}")
        assertEquals(estimate.bytes + 6_000_000L, OfflineMaps.estimate(helsinki, first = true).bytes)
    }

    @Test
    fun aWholeCountryIsTooBig() {
        val finland = BoundingBox(west = 19.0, south = 59.3, east = 31.6, north = 70.1)
        assertTrue(OfflineMaps.estimate(finland).tiles > OfflineMaps.MAX_TILES)
    }
}
