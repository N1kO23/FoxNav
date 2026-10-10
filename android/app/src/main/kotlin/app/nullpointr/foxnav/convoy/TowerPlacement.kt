package app.nullpointr.foxnav.convoy

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the user put the convoy tower: which side of the screen, how far down the space between
 * the top and bottom panels (0 = right under the top panel), and whether it's folded to its header.
 */
data class TowerPlacement(val rightSide: Boolean = false, val fraction: Float = 0f, val collapsed: Boolean = false)

class TowerPlacementStore(context: Context) {
    private val preferences = context.getSharedPreferences("convoy_ui", Context.MODE_PRIVATE)

    private val _placement = MutableStateFlow(
        TowerPlacement(
            rightSide = preferences.getBoolean(KEY_RIGHT_SIDE, false),
            fraction = preferences.getFloat(KEY_FRACTION, 0f).coerceIn(0f, 1f),
            collapsed = preferences.getBoolean(KEY_COLLAPSED, false),
        ),
    )
    val placement: StateFlow<TowerPlacement> = _placement.asStateFlow()

    fun save(placement: TowerPlacement) {
        preferences.edit {
            putBoolean(KEY_RIGHT_SIDE, placement.rightSide)
            putFloat(KEY_FRACTION, placement.fraction)
            putBoolean(KEY_COLLAPSED, placement.collapsed)
        }
        _placement.value = placement
    }

    private companion object {
        const val KEY_RIGHT_SIDE = "tower_right_side"
        const val KEY_FRACTION = "tower_fraction"
        const val KEY_COLLAPSED = "tower_collapsed"
    }
}
