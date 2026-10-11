package app.nullpointr.foxnav.settings

import android.content.Context
import android.content.SharedPreferences
import app.nullpointr.foxnav.shared.format.UnitSystem
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SpeedometerStyle { MODERN, SEGMENT }

/** What sounds when going over the limit: built in (design/sounds), the user's own file, or nothing. */
enum class OverLimitSound { KIN_KON, DING, BEEP, CUSTOM, OFF }

/** When the app and map go dark. */
enum class ThemeMode { SUN, SYSTEM, NIGHT, DAY }

enum class MapOrientation { HEADING_UP, NORTH_UP }

/** How much the app says about cameras and reports ahead. */
enum class Announcements { FULL, SHORT, TONE, OFF }

/** Accent colours for buttons and highlights. Warnings keep amber and red, the route keeps cyan. */
enum class Accent { VERMILION, SAKURA, OCEAN, LIME, VIOLET, MONO }

/** Everything the user chooses about how FoxNav looks, sounds and behaves. */
data class Settings(
    // Driving
    val units: UnitSystem = UnitSystem.METRIC,
    /** Over the limit by this much before the warning: in km/h, or mph with imperial units. */
    val overLimitMargin: Int = 3,
    val overLimitSound: OverLimitSound = OverLimitSound.KIN_KON,
    val speedometer: SpeedometerStyle = SpeedometerStyle.MODERN,
    // Map
    val theme: ThemeMode = ThemeMode.SUN,
    val orientation: MapOrientation = MapOrientation.HEADING_UP,
    val tilt: Boolean = true,
    val zoomButtons: Boolean = false,
    // Look
    val accent: Accent = Accent.VERMILION,
    // Voice
    /** A Valhalla guidance language like "fi-FI"; empty follows the phone's language. */
    val voiceLanguage: String = "",
    /** A TextToSpeech voice name; empty is the engine's default for the language. */
    val voiceName: String = "",
    val speechRate: Float = 1.0f,
    val announcements: Announcements = Announcements.FULL,
    /** The ID of a voice pack; empty speaks with the phone's text-to-speech. */
    val voicePack: String = "",
)

/** [Settings], kept on the phone. */
class UserSettings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(preferences.read())

    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(change: (Settings) -> Settings) {
        _settings.update(change)
        preferences.write(_settings.value)
    }

    private fun SharedPreferences.read(): Settings {
        val defaults = Settings()
        return Settings(
            units = enum("units", defaults.units),
            overLimitMargin = getInt("over_limit_margin", defaults.overLimitMargin),
            // Before the choice of sounds, the chime was on or off.
            overLimitSound = enum("over_limit_sound", if (getBoolean("over_limit_chime", true)) defaults.overLimitSound else OverLimitSound.OFF),
            speedometer = enum("speedometer", defaults.speedometer),
            theme = enum("theme", defaults.theme),
            orientation = enum("orientation", defaults.orientation),
            tilt = getBoolean("tilt", defaults.tilt),
            zoomButtons = getBoolean("zoom_buttons", defaults.zoomButtons),
            accent = enum("accent", defaults.accent),
            voiceLanguage = getString("voice_language", null) ?: defaults.voiceLanguage,
            voiceName = getString("voice_name", null) ?: defaults.voiceName,
            speechRate = getFloat("speech_rate", defaults.speechRate),
            announcements = enum("announcements", defaults.announcements),
            voicePack = getString("voice_pack", null) ?: defaults.voicePack,
        )
    }

    private fun SharedPreferences.write(settings: Settings) = edit {
        putString("units", settings.units.name)
        putInt("over_limit_margin", settings.overLimitMargin)
        putString("over_limit_sound", settings.overLimitSound.name)
        remove("over_limit_chime")
        putString("speedometer", settings.speedometer.name)
        putString("theme", settings.theme.name)
        putString("orientation", settings.orientation.name)
        putBoolean("tilt", settings.tilt)
        putBoolean("zoom_buttons", settings.zoomButtons)
        putString("accent", settings.accent.name)
        putString("voice_language", settings.voiceLanguage)
        putString("voice_name", settings.voiceName)
        putFloat("speech_rate", settings.speechRate)
        putString("announcements", settings.announcements.name)
        putString("voice_pack", settings.voicePack)
    }

    private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, default: T): T =
        getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
}
