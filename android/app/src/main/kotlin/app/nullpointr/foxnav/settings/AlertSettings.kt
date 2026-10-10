package app.nullpointr.foxnav.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the app warns about while driving. */
class AlertSettings(context: Context) {
    private val preferences = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    private val _cameraWarnings = MutableStateFlow(preferences.getBoolean(KEY_CAMERAS, true))

    /** Speed camera warnings; off regardless in countries that forbid them (see CameraRules). */
    val cameraWarnings: StateFlow<Boolean> = _cameraWarnings.asStateFlow()

    fun setCameraWarnings(on: Boolean) {
        preferences.edit { putBoolean(KEY_CAMERAS, on) }
        _cameraWarnings.value = on
    }

    private val _reportWarnings = MutableStateFlow(preferences.getBoolean(KEY_REPORTS, true))

    /** Spoken warnings about reports ahead (police, accidents…), and "still there?" questions. */
    val reportWarnings: StateFlow<Boolean> = _reportWarnings.asStateFlow()

    fun setReportWarnings(on: Boolean) {
        preferences.edit { putBoolean(KEY_REPORTS, on) }
        _reportWarnings.value = on
    }

    private companion object {
        const val KEY_CAMERAS = "camera_warnings"
        const val KEY_REPORTS = "report_warnings"
    }
}
