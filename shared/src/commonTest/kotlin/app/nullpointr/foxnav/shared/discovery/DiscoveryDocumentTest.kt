package app.nullpointr.foxnav.shared.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscoveryDocumentTest {
    private val sample = """
        {
          "foxnav": 1,
          "name": "FoxNav (local)",
          "realtime": "/v1/ws",
          "styles": { "night": "/styles/night.json", "day": "https://cdn.example.org/day.json" },
          "routing": { "type": "valhalla", "url": "/valhalla" },
          "search": { "type": "photon", "url": "/photon" },
          "features": ["convoy", "something-from-the-future"],
          "regions": ["FI"],
          "unknownField": { "ignored": true }
        }
    """.trimIndent()

    @Test
    fun parsesAndIgnoresUnknownFields() {
        val document = DiscoveryDocument.parse(sample)

        assertEquals(1, document.version)
        assertEquals("FoxNav (local)", document.name)
        assertEquals(ServiceEndpoint("valhalla", "/valhalla"), document.routing)
        assertEquals(setOf(Feature.CONVOY, "something-from-the-future"), document.features)
        assertNull(document.privacy)
    }

    @Test
    fun resolvesRelativeUrlsAgainstTheDocumentUrl() {
        val document = DiscoveryDocument.parse(sample).resolved("http://10.0.2.2:8080/.well-known/foxnav")

        assertEquals("ws://10.0.2.2:8080/v1/ws", document.realtime)
        assertEquals("http://10.0.2.2:8080/styles/night.json", document.styles["night"])
        assertEquals("https://cdn.example.org/day.json", document.styles["day"])
        assertEquals("http://10.0.2.2:8080/valhalla", document.routing?.url)
        assertEquals("http://10.0.2.2:8080/photon", document.search?.url)
    }

    @Test
    fun realtimeUsesSecureWebSocketsBehindHttps() {
        val document = DiscoveryDocument(version = 1, name = "x", realtime = "/v1/ws")
            .resolved("https://maps.example.org/.well-known/foxnav")

        assertEquals("wss://maps.example.org/v1/ws", document.realtime)
    }

    @Test
    fun roundTripsThroughJsonWithoutEmptyOptionalFields() {
        val document = DiscoveryDocument(version = 1, name = "Minimal")
        val json = document.toJson()

        assertEquals("""{"foxnav":1,"name":"Minimal"}""", json)
        assertEquals(document, DiscoveryDocument.parse(json))
    }
}
