package app.nullpointr.foxnav.shared.search

import app.nullpointr.foxnav.shared.search.SavedPlace.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedPlacesTest {
    private val station = Place("Central station", "Helsinki", 60.1710, 24.9414)
    private val stationAgain = Place("Rautatieasema", "", 60.17102, 24.94145) // a few metres away
    private val office = Place("Office", "Espoo", 60.1841, 24.8301)
    private val cabin = Place("Cabin", "Porvoo", 60.3923, 25.6650)

    @Test
    fun aNewHomeReplacesTheOld() {
        val places = SavedPlaces.save(SavedPlaces.save(emptyList(), station, Kind.HOME), office, Kind.HOME)
        assertEquals(listOf(SavedPlace(Kind.HOME, office)), places)
    }

    @Test
    fun theSamePlaceIsSavedOnce() {
        val places = SavedPlaces.save(SavedPlaces.save(emptyList(), station, Kind.FAVOURITE), stationAgain, Kind.FAVOURITE)
        assertEquals(listOf(SavedPlace(Kind.FAVOURITE, stationAgain)), places)
    }

    @Test
    fun recentDestinationsAreNewestFirstAndFew() {
        var places = emptyList<SavedPlace>()
        repeat(12) { i -> places = SavedPlaces.driveTo(places, Place("Stop $i", "", 60.0 + i * 0.01, 25.0)) }
        places = SavedPlaces.driveTo(places, Place("Stop 3 again", "", 60.03, 25.0))
        val recent = SavedPlaces.recent(places)
        assertEquals(SavedPlaces.MAX_RECENT, recent.size)
        assertEquals("Stop 3 again", recent.first().place.title)
        assertEquals(1, recent.count { it.place.latitude == 60.03 })
    }

    @Test
    fun savedPlacesDontAlsoShowAsRecent() {
        var places = SavedPlaces.driveTo(emptyList(), station)
        places = SavedPlaces.save(places, station, Kind.WORK)
        assertEquals(emptyList(), SavedPlaces.recent(places))
        assertEquals(places, SavedPlaces.driveTo(places, stationAgain))
    }

    @Test
    fun shortcutsAreHomeWorkThenFavourites() {
        var places = SavedPlaces.save(emptyList(), cabin, Kind.FAVOURITE)
        places = SavedPlaces.save(places, office, Kind.WORK)
        places = SavedPlaces.save(places, station, Kind.HOME)
        assertEquals(listOf(Kind.HOME, Kind.WORK, Kind.FAVOURITE), SavedPlaces.shortcuts(places).map { it.kind })
        assertEquals(Kind.WORK, SavedPlaces.savedAs(places, office)?.kind)
        assertNull(SavedPlaces.savedAs(SavedPlaces.forgetRecent(SavedPlaces.driveTo(emptyList(), office)), office))
    }

    @Test
    fun placesSurviveARoundTrip() {
        val places = SavedPlaces.driveTo(SavedPlaces.save(emptyList(), station, Kind.HOME), cabin)
        assertEquals(places, SavedPlaces.decode(SavedPlaces.encode(places)))
        assertEquals(emptyList(), SavedPlaces.decode("not json"))
    }
}
