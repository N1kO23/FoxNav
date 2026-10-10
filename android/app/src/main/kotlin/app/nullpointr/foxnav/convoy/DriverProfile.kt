package app.nullpointr.foxnav.convoy

import android.content.Context
import androidx.core.content.edit
import app.nullpointr.foxnav.protocol.convoy.v0.Car
import app.nullpointr.foxnav.protocol.convoy.v0.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Colours to pick for your car on the others' maps, from design/tokens.json (`color.convoy`). */
val ConvoyPalette = listOf(0x4C8DFF, 0x3DDC97, 0xFF5FA2, 0xB79BFF, 0xA8E05F)

/** How the others in a convoy see us. Kept on this device; sent only to convoy members, encrypted. */
data class DriverProfile(
    val name: String = "",
    val carMake: String = "",
    val carModel: String = "",
    val colorRgb: Int = ConvoyPalette.first(),
) {
    fun toProtocol() = Profile(
        display_name = name.trim(),
        car = Car(make = carMake.trim(), model = carModel.trim(), color_rgb = colorRgb),
    )
}

class ProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<DriverProfile> = _profile.asStateFlow()

    fun save(profile: DriverProfile) {
        preferences.edit {
            putString(KEY_NAME, profile.name.trim())
            putString(KEY_CAR_MAKE, profile.carMake.trim())
            putString(KEY_CAR_MODEL, profile.carModel.trim())
            putInt(KEY_COLOR, profile.colorRgb)
        }
        _profile.value = profile
    }

    private fun load(): DriverProfile {
        // A random colour to start with, so a convoy of fresh installs isn't all one colour.
        val color = if (preferences.contains(KEY_COLOR)) {
            preferences.getInt(KEY_COLOR, ConvoyPalette.first())
        } else {
            ConvoyPalette.random().also { preferences.edit { putInt(KEY_COLOR, it) } }
        }
        return DriverProfile(
            name = preferences.getString(KEY_NAME, null).orEmpty(),
            carMake = preferences.getString(KEY_CAR_MAKE, null).orEmpty(),
            carModel = preferences.getString(KEY_CAR_MODEL, null).orEmpty(),
            colorRgb = color,
        )
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_CAR_MAKE = "car_make"
        const val KEY_CAR_MODEL = "car_model"
        const val KEY_COLOR = "color_rgb"
    }
}
