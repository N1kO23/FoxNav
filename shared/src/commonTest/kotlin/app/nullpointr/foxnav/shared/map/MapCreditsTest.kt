package app.nullpointr.foxnav.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MapCreditsTest {
    @Test
    fun theFoxNavStylesCreditProtomapsAndOpenStreetMap() {
        // As in infra/styles/night.json.
        val style = """{"version": 8, "sources": {"protomaps": {"type": "vector",
            "attribution": "<a href=\"https://protomaps.com\">Protomaps</a> © <a href=\"https://openstreetmap.org/copyright\">OpenStreetMap</a>"}}}"""
        val credits = MapCredits.parse(style)
        assertEquals(
            listOf(MapCredit("Protomaps", "https://protomaps.com"), MapCredit("© OpenStreetMap", "https://openstreetmap.org/copyright")),
            credits,
        )
        assertEquals("Protomaps © OpenStreetMap", MapCredits.line(credits))
    }

    @Test
    fun plainTextAndEntitiesWork() {
        val style = """{"sources": {"a": {"attribution": "&copy; OpenStreetMap contributors"}, "b": {"attribution": "Terrain: <b>Example</b> &amp; friends"}}}"""
        assertEquals(listOf(MapCredit("© OpenStreetMap contributors", null), MapCredit("Terrain: Example & friends", null)), MapCredits.parse(style))
    }

    @Test
    fun sourcesSharingACreditShowItOnce() {
        val osm = """<a href='https://www.openstreetmap.org/copyright'>© OpenStreetMap</a>"""
        val style = """{"sources": {"a": {"attribution": "$osm"}, "b": {"attribution": "$osm"}, "c": {"type": "geojson"}}}"""
        assertEquals(1, MapCredits.parse(style).size)
    }

    @Test
    fun onlyWebLinksAreKept() {
        val style = """{"sources": {"a": {"attribution": "<a href=\"javascript:alert(1)\">Click</a>"}}}"""
        assertEquals(listOf(MapCredit("Click", null)), MapCredits.parse(style))
    }

    @Test
    fun anythingElseCreditsNothing() {
        assertEquals(emptyList(), MapCredits.parse("not json"))
        assertEquals(emptyList(), MapCredits.parse("""{"sources": {}}"""))
    }
}
