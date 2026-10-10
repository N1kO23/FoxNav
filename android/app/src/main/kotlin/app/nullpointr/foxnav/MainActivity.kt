package app.nullpointr.foxnav

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.nullpointr.foxnav.convoy.JoinInviteDialog
import app.nullpointr.foxnav.drive.DriveRoute
import app.nullpointr.foxnav.shared.convoy.Invite
import app.nullpointr.foxnav.ui.ServerGate
import app.nullpointr.foxnav.ui.theme.FoxNavTheme
import app.nullpointr.foxnav.voice.guidanceLanguage
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** An invite link the app was opened with; [invite] is null when the link is broken. */
private class OpenedInvite(val invite: Invite?)

class MainActivity : ComponentActivity(), AndroidTtsStatusListener {
    private val openedInvite = MutableStateFlow<OpenedInvite?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appGraph.voice.statusObserver = this
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            FoxNavTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ServerGate(appGraph.serverSettings) { document -> DriveRoute(document) }
                    val opened by openedInvite.collectAsState()
                    opened?.let { JoinInviteDialog(it.invite, onDone = { openedInvite.value = null }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Invite links (`foxnav://join#…`). Never log them: they carry the convoy's key. */
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
        val result = tts?.setLanguage(Locale.forLanguageTag(guidanceLanguage()))
        Log.i(TAG, "Text-to-speech ready (status $status, language result $result)")
    }

    override fun onTtsSpeakError(utteranceId: String, status: Int) {
        Log.w(TAG, "Couldn't speak $utteranceId: status $status")
    }

    override fun onTtsShutdownAndRelease() = Unit

    private companion object {
        const val TAG = "FoxNav"
    }
}
