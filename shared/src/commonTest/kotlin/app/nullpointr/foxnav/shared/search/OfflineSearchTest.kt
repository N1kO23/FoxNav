package app.nullpointr.foxnav.shared.search

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineSearchTest {
    private val manifest = SearchPackManifest.parse(
        """{"format":1,"region":"FI","build":"9e48d299fc92c27e","generated":"2026-10-03T23:01:00Z","size":0.25,
            "cells":[[864819,48408,923414],[902262,1783,23661]],"somethingFromTheFuture":true}""",
    )

    // A Helsinki cell, as the reference builder writes it.
    private val cell = SearchEntry.parseCell(
        """{"format":1,"entries":[
            ["street","Mannerheimintie","Helsinki",60.18,24.928,0.1],
            ["address","Mannerheimintie 5","Helsinki",60.1696,24.9405,0,["Mannerheimvägen 5"]],
            ["address","Mannerheimintie 51","Helsinki",60.1802,24.9283,0],
            ["place","Kamppi","Helsinki",60.1683,24.9306,0.25,["Kampen"]],
            ["poi","Kampin keskus","Urho Kekkosen katu 1, Helsinki",60.1690,24.9330,0.2],
            ["poi","Mannerheimin patsas","Helsinki",60.1717,24.9378,0.3],
            ["something","new","",60.0,24.0,0],
            ["address","broken"]
        ]}""",
    )

    @Test
    fun readsTheManifest() {
        assertTrue(manifest.isUsable)
        assertEquals(23_661, manifest.downloadOf(902262))
        assertNull(manifest.downloadOf(1))
        assertEquals(1783L * SearchPackManifest.INDEX_BYTES_PER_ENTRY, manifest.indexBytesOf(902262))
        assertEquals("https://maps.example.org/datapacks/FI/search/9e48d299fc92c27e/864819.json.gz", manifest.urlOf("https://maps.example.org/datapacks/FI/search/", 864819))
        assertFalse(manifest.copy(size = 1.0).isUsable)
    }

    @Test
    fun cellsAreLocalRoadTiles() {
        assertEquals(864819, SearchPackManifest.cellAt(GeoPoint(60.1699, 24.9410)))
    }

    @Test
    fun readsACellAndSkipsWhatItDoesntKnow() {
        assertEquals(6, cell.size)
        assertEquals(SearchEntry(SearchKind.ADDRESS, "Mannerheimintie 5", "Helsinki", 60.1696, 24.9405, 0.0, listOf("Mannerheimvägen 5")), cell[1])
        assertEquals(emptyList(), SearchEntry.parseCell("""{"format":2,"entries":[["place","Kamppi","",60.1,24.9,0]]}"""))
    }

    @Test
    fun foldsCaseAccentsAndPunctuation() {
        assertEquals("aanekoski", SearchText.fold("Äänekoski"))
        assertEquals("urho kekkosen katu 1 helsinki", SearchText.fold("Urho Kekkosen katu 1, Helsinki"))
        assertEquals("ohcejohka", SearchText.fold("Ohcejohka"))
        assertEquals("cahcesuolu", SearchText.fold("Čáhcesuolu"))
    }

    @Test
    fun queriesMatchTheStartsOfWords() {
        assertEquals("mannerh* 5*", SearchText.ftsQuery("Mannerh 5"))
        assertNull(SearchText.ftsQuery(" , "))
        assertEquals("mannerheimintie 5 helsinki mannerheimvagen 5", SearchText.indexed(cell[1]))
    }

    @Test
    fun aStreetNameFindsTheStreet() {
        assertEquals("Mannerheimintie", rank("mannerheimintie").first().title)
    }

    @Test
    fun aHouseNumberFindsTheAddress() {
        assertEquals(listOf("Mannerheimintie 5", "Mannerheimintie 51"), rank("Mannerheimintie 5").take(2).map { it.title })
    }

    @Test
    fun otherNamesCountToo() {
        assertEquals("Kamppi", rank("kampen").first().title)
        assertEquals("Mannerheimintie 5", rank("mannerheimvagen 5").first().title)
    }

    @Test
    fun aPlaceBeatsTheLandmarksNamedAfterIt() {
        assertEquals(listOf("Kamppi", "Kampin keskus"), rank("kamp").take(2).map { it.title })
    }

    @Test
    fun theNearerOfTwoEqualsComesFirst() {
        val north = SearchEntry(SearchKind.STREET, "Koulukatu", "Rovaniemi", 66.5, 25.72, 0.05)
        val south = SearchEntry(SearchKind.STREET, "Koulukatu", "Tampere", 61.5, 23.76, 0.05)

        assertEquals(listOf(north, south), SearchText.rank("koulukatu", listOf(south, north), near = GeoPoint(66.49, 25.7)))
        assertEquals(listOf(south, north), SearchText.rank("koulukatu", listOf(south, north), near = GeoPoint(61.49, 23.7)))
    }

    /** Ranks the entries that have every word, as the index would hand them over. */
    private fun rank(query: String): List<SearchEntry> {
        val words = SearchText.words(query)
        val candidates = cell.filter { entry -> words.all { word -> SearchText.indexed(entry).split(' ').any { it.startsWith(word) } } }
        return SearchText.rank(query, candidates, near = GeoPoint(60.1699, 24.9410))
    }
}
