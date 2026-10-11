package app.nullpointr.foxnav.shared.search

import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A place the driver keeps: home, work, a favourite, or somewhere they drove to lately. */
@Serializable
data class SavedPlace(val kind: Kind, val place: Place) {
    enum class Kind { HOME, WORK, FAVOURITE, RECENT }

    val point: GeoPoint get() = GeoPoint(place.latitude, place.longitude)
}

/**
 * The rules for saved places, kept on the phone only: one home and one work, each place saved
 * once, and a short list of recent destinations, newest first.
 */
object SavedPlaces {
    /** Two places this close are the same place (a pin dropped twice, a search result and its address). */
    const val SAME_PLACE_M = 50.0
    const val MAX_RECENT = 8

    /** [place] saved as [kind]: a new home or work replaces the old one; a place saved again moves rather than repeats. */
    fun save(places: List<SavedPlace>, place: Place, kind: SavedPlace.Kind): List<SavedPlace> {
        val saved = SavedPlace(kind, place)
        val others = places.filterNot { it.kind == kind && (kind == SavedPlace.Kind.HOME || kind == SavedPlace.Kind.WORK || same(it.place, place)) }
        return if (kind == SavedPlace.Kind.RECENT) {
            (listOf(saved) + others.filterNot { it.kind == kind && same(it.place, place) }).let(::trimRecent)
        } else {
            // Saved for good, it needn't also be recent.
            others.filterNot { it.kind == SavedPlace.Kind.RECENT && same(it.place, place) } + saved
        }
    }

    /** A destination just driven to, at the top of the recent ones, unless it's saved already. */
    fun driveTo(places: List<SavedPlace>, place: Place): List<SavedPlace> =
        if (places.any { it.kind != SavedPlace.Kind.RECENT && same(it.place, place) }) places else save(places, place, SavedPlace.Kind.RECENT)

    fun remove(places: List<SavedPlace>, saved: SavedPlace): List<SavedPlace> = places - saved

    fun forgetRecent(places: List<SavedPlace>): List<SavedPlace> = places.filterNot { it.kind == SavedPlace.Kind.RECENT }

    /** What's saved for [place], if anything (recent doesn't count). */
    fun savedAs(places: List<SavedPlace>, place: Place): SavedPlace? =
        places.firstOrNull { it.kind != SavedPlace.Kind.RECENT && same(it.place, place) }

    /** Home, work, then favourites in the order they were saved: the shortcuts under the search. */
    fun shortcuts(places: List<SavedPlace>): List<SavedPlace> =
        places.filter { it.kind == SavedPlace.Kind.HOME } + places.filter { it.kind == SavedPlace.Kind.WORK } + places.filter { it.kind == SavedPlace.Kind.FAVOURITE }

    fun recent(places: List<SavedPlace>): List<SavedPlace> = places.filter { it.kind == SavedPlace.Kind.RECENT }

    fun encode(places: List<SavedPlace>): String = FoxNavJson.encodeToString(ListSerializer(SavedPlace.serializer()), places)

    fun decode(json: String): List<SavedPlace> = runCatching { FoxNavJson.decodeFromString(ListSerializer(SavedPlace.serializer()), json) }.getOrDefault(emptyList())

    private fun same(a: Place, b: Place) = GeoPoint(a.latitude, a.longitude).distanceTo(GeoPoint(b.latitude, b.longitude)) < SAME_PLACE_M

    private fun trimRecent(places: List<SavedPlace>): List<SavedPlace> {
        var recent = 0
        return places.filter { it.kind != SavedPlace.Kind.RECENT || ++recent <= MAX_RECENT }
    }
}
