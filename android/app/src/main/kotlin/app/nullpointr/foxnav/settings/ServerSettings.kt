package app.nullpointr.foxnav.settings

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import app.nullpointr.foxnav.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single services the user replaces with their own; blank keeps the server's. */
data class EndpointOverrides(val routing: String = "", val search: String = "", val style: String = "")

/** Which FoxNav server the app uses, and any single services overridden. */
class ServerSettings(context: Context) {
    private val preferences = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    private val _serverUrl = MutableStateFlow(preferences.getString(KEY_SERVER_URL, null) ?: defaultServerUrl())

    /** What the user typed, e.g. `maps.example.org`; empty until one is chosen. */
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _overrides = MutableStateFlow(
        EndpointOverrides(
            routing = preferences.getString(KEY_ROUTING, null).orEmpty(),
            search = preferences.getString(KEY_SEARCH, null).orEmpty(),
            style = preferences.getString(KEY_STYLE, null).orEmpty(),
        ),
    )
    val overrides: StateFlow<EndpointOverrides> = _overrides.asStateFlow()

    fun save(serverUrl: String, overrides: EndpointOverrides) {
        val trimmed = overrides.copy(routing = overrides.routing.trim(), search = overrides.search.trim(), style = overrides.style.trim())
        preferences.edit {
            putString(KEY_SERVER_URL, serverUrl.trim())
            putString(KEY_ROUTING, trimmed.routing)
            putString(KEY_SEARCH, trimmed.search)
            putString(KEY_STYLE, trimmed.style)
        }
        _serverUrl.value = serverUrl.trim()
        _overrides.value = trimmed
    }

    private companion object {
        /**
         * Debug builds in the emulator start on the developer's local stack (10.0.2.2 is the host
         * machine). Everywhere else there's no sensible default yet, so the app asks.
         */
        fun defaultServerUrl(): String = if (runsInEmulator()) BuildConfig.DEFAULT_SERVER_URL else ""

        fun runsInEmulator(): Boolean =
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.startsWith("sdk_")

        const val KEY_SERVER_URL = "server_url"
        const val KEY_ROUTING = "override_routing"
        const val KEY_SEARCH = "override_search"
        const val KEY_STYLE = "override_style"
    }
}
