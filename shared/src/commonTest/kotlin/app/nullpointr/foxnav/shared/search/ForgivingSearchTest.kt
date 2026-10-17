package app.nullpointr.foxnav.shared.search

import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.road.Reachability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForgivingSearchTest {
    private val koskikatu = SearchEntry(SearchKind.STREET, "Koskikatu", "Rovaniemi", 66.5000, 25.7240, 0.05)
    private val number23 = SearchEntry(SearchKind.ADDRESS, "Koskikatu 23", "Rovaniemi", 66.5003, 25.7262, 0.0)
    private val arktikum = SearchEntry(SearchKind.POI, "Arktikum", "Pohjoisranta 4, Rovaniemi", 66.5075, 25.7260, 0.3)
    private val ounasrinne = SearchEntry(SearchKind.PLACE, "Ounasrinne", "Rovaniemi", 66.5100, 25.7600, 0.1)
    private val rovaniemi = SearchEntry(SearchKind.PLACE, "Rovaniemi", "Lappi", 66.5030, 25.7290, 0.6)

    @Test
    fun countsTyposTheWayPeopleMakeThem() {
        assertEquals(1, SearchText.distance("rovaneimi", "rovaniemi", 2), "swapped")
        assertEquals(1, SearchText.distance("hameenlina", "hameenlinna", 2), "missing")
        assertEquals(1, SearchText.distance("koskikkatu", "koskikatu", 2), "extra")
        assertEquals(1, SearchText.distance("tamoere", "tampere", 2), "wrong")
        assertEquals(3, SearchText.distance("helsinki", "turku", 2), "gives up past the limit")
    }

    @Test
    fun longerWordsMayHaveMoreTypos() {
        assertTrue(SearchText.similar("mannerhem", "mannerheimintie"))
        assertTrue(SearchText.similar("koskkatu", "koskikatu"))
        assertTrue(SearchText.similar("rovaneimi", "rovaniemi"))
        // Short words must be right: one letter makes another word.
        assertFalse(SearchText.similar("tori", "tuli"))
        assertTrue(SearchText.similar("tori", "torikatu"))
    }

    @Test
    fun aLooseMatchNeedsEveryWord() {
        assertTrue(SearchText.matchesLoosely("koskkatu 23", number23))
        assertFalse(SearchText.matchesLoosely("koskkatu 24", number23))
        assertTrue(SearchText.matchesLoosely("arktikun rovaniemi", arktikum))
    }

    @Test
    fun rightSpellingsComeBeforeLooseOnes() {
        val koskela = SearchEntry(SearchKind.STREET, "Koskela", "Rovaniemi", 66.5001, 25.7241, 0.05)
        val results = SearchText.rank("koskel", listOf(koskikatu, koskela), near = GeoPoint(66.5, 25.724))

        assertEquals(koskela, results.first())
    }

    @Test
    fun readsWhichPlacesAreReachable() {
        val verbose = """{"sources_to_targets":[[{"distance":1.2,"time":140,"to_index":0,"from_index":0},
            {"distance":null,"time":null,"to_index":1,"from_index":0},{"distance":0.4,"time":60,"to_index":2,"from_index":0}]],"units":"kilometers"}"""
        val concise = """{"sources_to_targets":{"distances":[[1.2,null,0.4]],"durations":[[140,null,60]]}}"""

        assertEquals(listOf(true, false, true), Reachability.parse(verbose, 3))
        assertEquals(listOf(true, false, true), Reachability.parse(concise, 3))
        assertNull(Reachability.parse("""{"error_code":171,"error":"No suitable edges near location"}""", 3))
        assertEquals(
            """{"sources":[{"lat":66.5,"lon":25.7}],"targets":[{"lat":66.51,"lon":25.72}],"costing":"auto"}""",
            Reachability.request(GeoPoint(66.5, 25.7), listOf(GeoPoint(66.51, 25.72))),
        )
    }

    @Test
    fun onlyNavigableResultsStay() {
        val car = GeoPoint(66.5030, 25.7270)
        val island = SearchEntry(SearchKind.PLACE, "Saari", "Rovaniemi", 66.52, 25.70, 0.1)
        val helsinki = SearchEntry(SearchKind.STREET, "Koskikatu", "Helsinki", 60.17, 24.94, 0.05)
        val covered = { point: GeoPoint -> point.latitude > 66 }
        // No road to the island: the matrix says so.
        val reachable = { _: GeoPoint, targets: List<GeoPoint> -> targets.map { it.latitude < 66.515 } }

        assertEquals(listOf(koskikatu, arktikum), navigable(listOf(koskikatu, island, arktikum, helsinki), car, covered, reachable))
    }

    @Test
    fun withoutAWayToCheckRoadDataWhereTheyAreWillDo() {
        val covered = { point: GeoPoint -> point.longitude > 25.73 }

        // The car is outside the road data: nothing to route from, so road data at the place it is.
        assertEquals(listOf(ounasrinne), navigable(listOf(koskikatu, ounasrinne), GeoPoint(66.5, 25.72), covered, { _, _ -> error("not asked") }))
        // The matrix failed.
        assertEquals(listOf(ounasrinne), navigable(listOf(koskikatu, ounasrinne), GeoPoint(66.5, 25.74), covered, { _, _ -> null }))
    }

    @Test
    fun namesAnAreaAfterItsTownOrDistrict() {
        val nearby = listOf(koskikatu, number23, arktikum, ounasrinne, rovaniemi)

        assertEquals("Rovaniemi", PlaceNames.areaName(GeoPoint(66.505, 25.74), nearby, small = false))
        assertEquals("Ounasrinne, Rovaniemi", PlaceNames.areaName(GeoPoint(66.509, 25.755), nearby, small = true))
    }

    @Test
    fun namesAPinAfterTheAddressItsAt() {
        val nearby = listOf(koskikatu, number23, arktikum, ounasrinne, rovaniemi)

        assertEquals(Place("Koskikatu 23", "Rovaniemi", 66.50032, 25.72625), PlaceNames.pinAt(GeoPoint(66.50032, 25.72625), nearby))
        // Nothing right there: a dropped pin, in its district.
        val field = PlaceNames.pinAt(GeoPoint(66.5095, 25.758), nearby)
        assertEquals(PlaceNames.DROPPED_PIN to "Ounasrinne, Rovaniemi", field.title to field.detail)
    }
}
