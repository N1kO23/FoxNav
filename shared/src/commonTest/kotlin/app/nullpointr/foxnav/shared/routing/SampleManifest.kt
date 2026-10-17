package app.nullpointr.foxnav.shared.routing

/** Tile IDs and sizes from the local stack's Finland graph, rounded: around Helsinki, and north to Hyvinkää. */
internal val FINLAND_SAMPLE = RoutingTileManifest.parse(
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
