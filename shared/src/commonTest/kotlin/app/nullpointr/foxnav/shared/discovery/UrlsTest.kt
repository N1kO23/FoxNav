package app.nullpointr.foxnav.shared.discovery

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlsTest {
    private val base = "https://maps.example.org/a/b/doc.json?x=1#frag"

    @Test
    fun absoluteReferencesAreKept() {
        assertEquals("wss://other.example.org/ws", resolveUrl(base, "wss://other.example.org/ws"))
    }

    @Test
    fun schemeRelativeReferencesTakeTheBaseScheme() {
        assertEquals("https://cdn.example.org/s.json", resolveUrl(base, "//cdn.example.org/s.json"))
    }

    @Test
    fun absolutePathReferencesReplaceThePath() {
        assertEquals("https://maps.example.org/valhalla", resolveUrl(base, "/valhalla"))
    }

    @Test
    fun relativePathReferencesMergeWithTheBaseDirectory() {
        assertEquals("https://maps.example.org/a/b/styles/night.json", resolveUrl(base, "styles/night.json"))
        assertEquals("https://maps.example.org/a/c.json", resolveUrl(base, "../c.json"))
        assertEquals("https://maps.example.org/c.json", resolveUrl(base, "../../../c.json"))
        assertEquals("https://maps.example.org/a/b/", resolveUrl(base, "."))
    }

    @Test
    fun queryAndFragmentOnlyReferences() {
        assertEquals("https://maps.example.org/a/b/doc.json?y=2", resolveUrl(base, "?y=2"))
        assertEquals("https://maps.example.org/a/b/doc.json?x=1#top", resolveUrl(base, "#top"))
    }

    @Test
    fun baseWithoutPath() {
        assertEquals("http://localhost:8080/photon", resolveUrl("http://localhost:8080", "photon"))
    }

    @Test
    fun webSocketSchemes() {
        assertEquals("wss://h/ws", toWebSocketUrl("https://h/ws"))
        assertEquals("ws://h/ws", toWebSocketUrl("http://h/ws"))
        assertEquals("wss://h/ws", toWebSocketUrl("wss://h/ws"))
    }
}
