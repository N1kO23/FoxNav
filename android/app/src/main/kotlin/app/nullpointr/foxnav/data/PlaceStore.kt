package app.nullpointr.foxnav.data

import android.content.Context
import androidx.core.content.edit
import app.nullpointr.foxnav.shared.search.Place
import app.nullpointr.foxnav.shared.search.SavedPlace
import app.nullpointr.foxnav.shared.search.SavedPlaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Home, work, favourites and recent destinations ([SavedPlaces]), kept on this phone only. */
class PlaceStore(context: Context) {
    private val preferences = context.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val _places = MutableStateFlow(SavedPlaces.decode(preferences.getString(KEY, null) ?: "[]"))

    val places: StateFlow<List<SavedPlace>> = _places.asStateFlow()

    fun save(place: Place, kind: SavedPlace.Kind) = change { SavedPlaces.save(it, place, kind) }

    fun remove(saved: SavedPlace) = change { SavedPlaces.remove(it, saved) }

    /** Navigating to [place]: it joins the recent destinations. */
    fun driveTo(place: Place) = change { SavedPlaces.driveTo(it, place) }

    fun forgetRecent() = change(SavedPlaces::forgetRecent)

    private fun change(transform: (List<SavedPlace>) -> List<SavedPlace>) {
        _places.update(transform)
        preferences.edit { putString(KEY, SavedPlaces.encode(_places.value)) }
    }

    private companion object {
        const val KEY = "saved"
    }
}
