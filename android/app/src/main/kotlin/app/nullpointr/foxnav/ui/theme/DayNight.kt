package app.nullpointr.foxnav.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.settings.ThemeMode
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.time.Sun
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Roughly where the phone was last, to a tenth of a degree (about 10 km), so day and night follow
 * the local sunrise and sunset from the first frame. Kept on the phone only.
 */
class LastArea(context: Context) {
    private val preferences = context.getSharedPreferences("last_area", Context.MODE_PRIVATE)
    private val _area = MutableStateFlow(
        if (preferences.contains(KEY_LAT)) GeoPoint(preferences.getFloat(KEY_LAT, 0f).toDouble(), preferences.getFloat(KEY_LON, 0f).toDouble()) else null,
    )

    val area: StateFlow<GeoPoint?> = _area.asStateFlow()

    fun update(point: GeoPoint) {
        val rounded = GeoPoint((point.latitude * 10).roundToInt() / 10.0, (point.longitude * 10).roundToInt() / 10.0)
        if (rounded == _area.value) return
        _area.value = rounded
        preferences.edit {
            putFloat(KEY_LAT, rounded.latitude.toFloat())
            putFloat(KEY_LON, rounded.longitude.toFloat())
        }
    }

    private companion object {
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
    }
}

/** Whether to show night colours now: by the sun where we are, the system, or always one or the other. */
@Composable
fun rememberNight(mode: ThemeMode): Boolean {
    val system = isSystemInDarkTheme()
    val area by LocalContext.current.appGraph.lastArea.area.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    return when (mode) {
        ThemeMode.NIGHT -> true
        ThemeMode.DAY -> false
        ThemeMode.SYSTEM -> system
        ThemeMode.SUN -> area?.let { !Sun.isUp(it, now) } ?: system
    }
}
