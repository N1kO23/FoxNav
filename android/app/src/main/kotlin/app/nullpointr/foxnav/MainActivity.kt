package app.nullpointr.foxnav

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.nullpointr.foxnav.drive.DriveRoute
import app.nullpointr.foxnav.ui.ServerGate
import app.nullpointr.foxnav.ui.theme.FoxNavTheme
import app.nullpointr.foxnav.voice.guidanceLanguage
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import java.util.Locale

class MainActivity : ComponentActivity(), AndroidTtsStatusListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appGraph.voice.statusObserver = this

        setContent {
            FoxNavTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ServerGate(appGraph.serverSettings) { document -> DriveRoute(document) }
                }
            }
        }
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
