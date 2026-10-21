package app.nullpointr.foxnav

import android.graphics.Color as AndroidColor
import androidx.activity.SystemBarStyle
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.nullpointr.foxnav.convoy.JoinInviteDialog
import app.nullpointr.foxnav.drive.DriveRoute
import app.nullpointr.foxnav.shared.convoy.Invite
import app.nullpointr.foxnav.ui.ServerGate
import app.nullpointr.foxnav.ui.theme.FoxNavTheme
import app.nullpointr.foxnav.ui.theme.LocalHudScale
import app.nullpointr.foxnav.ui.theme.themeColors
import app.nullpointr.foxnav.ui.format.LocalSpeedometerStyle
import app.nullpointr.foxnav.ui.format.LocalUnits
import app.nullpointr.foxnav.ui.components.LocalJapaneseAccents
import app.nullpointr.foxnav.ui.theme.rememberNight
import app.nullpointr.foxnav.voice.applyVoiceSettings
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import kotlinx.coroutines.flow.MutableStateFlow

/** An invite link the app was opened with; [invite] is null when the link is broken. */
private class OpenedInvite(val invite: Invite?)

class MainActivity : ComponentActivity(), AndroidTtsStatusListener {
    private val openedInvite = MutableStateFlow<OpenedInvite?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appGraph.voice.statusObserver = this
        if (savedInstanceState == null) handleIntent(intent)
        // The chosen voice, language and speed, whenever they change.
        lifecycleScope.launch {
            appGraph.settings.settings.collect { settings ->
                appGraph.voice.tts?.takeIf { appGraph.voice.isInitializedSuccessfully }?.let { applyVoiceSettings(it, settings) }
            }
        }

        setContent {
            val settings by appGraph.settings.settings.collectAsState()
            val night = rememberNight(settings.theme)
            // Status and navigation bar icons follow our colours, which can differ from the phone's.
            LaunchedEffect(night) {
                val bars = if (night) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            val palette = remember(settings.palette) { appGraph.palette(settings.palette) }
            val colors = remember(palette, night, settings) { themeColors(palette, night, settings) }
            FoxNavTheme(night = night, colors = colors) {
                // The text size: the phone's, times the driver's own setting.
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, density.fontScale * settings.textScale),
                    LocalHudScale provides settings.hudScale,
                    LocalUnits provides settings.units,
                    LocalSpeedometerStyle provides settings.speedometer,
                    LocalJapaneseAccents provides settings.japaneseAccents,
                ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ServerGate(appGraph.serverSettings) { document -> DriveRoute(document) }
                    val opened by openedInvite.collectAsState()
                    opened?.let { JoinInviteDialog(it.invite, onDone = { openedInvite.value = null }) }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Invite links (`foxnav://join#...`). Never log them: they carry the convoy's key. */
    private fun handleIntent(intent: Intent?) {
        val link = intent?.data?.takeIf { it.scheme == "foxnav" && it.host == "join" } ?: return
        openedInvite.value = OpenedInvite(Invite.parse(link.toString()))
    }

    override fun onStart() {
        super.onStart()
        appGraph.voice.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        appGraph.voice.shutdown()
    }

    override fun onTtsInitialized(tts: TextToSpeech?, status: Int) {
        tts?.let { applyVoiceSettings(it, appGraph.settings.settings.value) }
        Log.i(TAG, "Text-to-speech ready (status $status)")
    }

    override fun onTtsSpeakError(utteranceId: String, status: Int) {
        Log.w(TAG, "Couldn't speak $utteranceId: status $status")
    }

    override fun onTtsShutdownAndRelease() = Unit

    private companion object {
        const val TAG = "FoxNav"
    }
}
