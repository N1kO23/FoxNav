package app.nullpointr.foxnav.shared.search

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotonTest {
    @Test
    fun searchPathEncodesTheQueryAndAddsTheBias() {
        assertEquals(
            "/api?q=Mannerheimintie%201%2FH%C3%A4meentie&limit=5&lat=60.17&lon=24.94&lang=fi",
            Photon.searchPath("Mannerheimintie 1/Hämeentie", near = GeoPoint(60.17, 24.94), limit = 5, language = "fi"),
        )
        assertEquals("/api?q=Tampere&limit=8", Photon.searchPath("Tampere"))
    }

    @Test
    fun parsesCitiesAddressesAndNamedPlaces() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"geometry":{"type":"Point","coordinates":[23.76,61.49]},"type":"Feature",
               "properties":{"name":"Tampere","state":"Pirkanmaa","country":"Finland","osm_value":"city"}},
              {"geometry":{"type":"Point","coordinates":[24.94,60.17]},"type":"Feature",
               "properties":{"street":"Mannerheimintie","housenumber":"1","city":"Helsinki","country":"Finland"}},
              {"geometry":{"type":"Point","coordinates":[24.93,60.17]},"type":"Feature",
               "properties":{"name":"Stockmann","street":"Aleksanterinkatu","housenumber":"52","city":"Helsinki"}},
              {"geometry":{"type":"Point","coordinates":[25.0,60.2]},"type":"Feature","properties":{"country":"Finland"}}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                Place("Tampere", "Pirkanmaa, Finland", 61.49, 23.76),
                Place("Mannerheimintie 1", "Helsinki", 60.17, 24.94),
                Place("Stockmann", "Aleksanterinkatu 52, Helsinki", 60.17, 24.93),
            ),
            Photon.parse(json),
        )
    }

    @Test
    fun emptyResponses() {
        assertEquals(emptyList(), Photon.parse("""{"features":[]}"""))
        assertEquals(emptyList(), Photon.parse("{}"))
    }
}
