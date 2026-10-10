package app.nullpointr.foxnav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.nullpointr.foxnav.map.MapScreen
import app.nullpointr.foxnav.ui.theme.FoxNavTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FoxNavTheme {
                MapScreen(serverUrl = BuildConfig.DEFAULT_SERVER_URL)
            }
        }
    }
}
