package app.nullpointr.foxnav.shared.routing

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutingTilesTest {
    // Tile IDs and sizes from the local stack's Finland graph, rounded.
    private val manifest = RoutingTileManifest.parse(
        """
        {
          "format": 1, "engine": "valhalla", "version": "3.9.1", "build": "758e170c8b3cf751",
          "generated": "2026-10-09T23:57:26Z", "attribution": "© OpenStreetMap contributors, ODbL",
          "levels": [{ "level": 0, "size": 4 }, { "level": 1, "size": 1 }, { "level": 2, "size": 0.25 }],
          "tiles": {
            "0": [[3381, 4900000, 1800000]],
            "1": [[54204, 15000000, 5000000]],
            "2": [[864819, 26000000, 12000000], [864820, 26000000, 9000000], [866259, 5000000, 2000000], [867699, 7700000, 2900000]]
          },
          "somethingFromTheFuture": true
        }
        """.trimIndent(),
    )

    @Test
    fun pathsAreValhallas() {
        assertEquals("0/003/381.gph", manifest.pathOf(RoutingTile(0, 3381)))
        assertEquals("1/054/204.gph", manifest.pathOf(RoutingTile(1, 54204)))
        assertEquals("2/000/864/819.gph", manifest.pathOf(RoutingTile(2, 864819)))
    }

    @Test
    fun urlsNameTheBuild() {
        assertEquals(
            "https://maps.example.org/routing-tiles/758e170c8b3cf751/2/000/864/819.gph.gz",
            manifest.urlOf("https://maps.example.org/routing-tiles/", RoutingTile(2, 864819)),
        )
    }

    @Test
    fun anAreaNeedsItsTilesOnEveryLevel() {
        // Central Helsinki straddles two local-road tiles; level 1's tile east of it has no roads listed.
        val tiles = manifest.tilesIn(GeoPoint(60.15, 24.88), GeoPoint(60.22, 25.02))

        assertEquals(setOf(RoutingTile(0, 3381), RoutingTile(1, 54204), RoutingTile(2, 864819), RoutingTile(2, 864820)), tiles)
        assertEquals(TileSize(bytes = 71_900_000, download = 27_800_000), manifest.sizeOf(tiles))
    }

    @Test
    fun theSeaHasNoTiles() {
        assertEquals(emptySet(), manifest.tilesIn(GeoPoint(59.0, 21.0), GeoPoint(59.1, 21.1)))
    }

    @Test
    fun aRouteNeedsTheTilesAllAlongIt() {
        // From central Helsinki to the Tampere motorway at Hyvinkää: two points, three local-road tiles.
        val tiles = manifest.tilesAlong(listOf(GeoPoint(60.1699, 24.9410), GeoPoint(60.55, 24.836)))

        assertEquals(
            setOf(RoutingTile(0, 3381), RoutingTile(1, 54204), RoutingTile(2, 864819), RoutingTile(2, 866259), RoutingTile(2, 867699)),
            tiles,
        )
    }

    @Test
    fun aMarginReachesIntoTheNextTile() {
        // 1 km west of the line between local-road tiles 864819 and 864820 (25° east).
        val near = listOf(GeoPoint(60.17, 24.982), GeoPoint(60.18, 24.982))

        assertFalse(RoutingTile(2, 864820) in manifest.tilesAlong(near, marginMetres = 500.0))
        assertTrue(RoutingTile(2, 864820) in manifest.tilesAlong(near, marginMetres = 2_000.0))
    }

    @Test
    fun knowsWhichTilesItCanUse() {
        assertTrue(manifest.isUsable)
        assertFalse(manifest.copy(format = 2).isUsable)
        assertFalse(manifest.copy(version = "4.0.0").isUsable)
        assertFalse(manifest.copy(engine = "osrm").isUsable)
    }
}
