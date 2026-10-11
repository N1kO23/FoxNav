package app.nullpointr.foxnav.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.shared.map.MapCredit
import app.nullpointr.foxnav.shared.map.MapCredits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Each style's credits, read once a session: switching between day and night doesn't flicker. */
private val creditsByStyle = ConcurrentHashMap<String, List<MapCredit>>()

/** The credits the map style asks for. Until it's read, or when it can't be (offline), OpenStreetMap's. */
@Composable
fun rememberMapCredits(styleUrl: String): List<MapCredit> {
    val client = LocalContext.current.appGraph.httpClient
    val credits by produceState(creditsByStyle[styleUrl] ?: MapCredits.DEFAULT, styleUrl) {
        if (creditsByStyle.containsKey(styleUrl)) return@produceState
        val read = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(styleUrl).build()).execute().use { response ->
                    if (response.isSuccessful) MapCredits.parse(response.body.string()) else null
                }
            }.getOrNull()
        }
        if (!read.isNullOrEmpty()) {
            creditsByStyle[styleUrl] = read
            value = read
        }
    }
    return credits
}

/**
 * The map's credits as a small tag at the edge of the map, as in the mockups, instead of
 * MapLibre's logo and info button. Tapping it lists them with their links.
 */
@Composable
fun MapCreditTag(credits: List<MapCredit>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    // The tap area is a little taller than the tag, which is only as tall as its small type.
    Box(modifier.clickable(role = Role.Button, onClickLabel = "Show the map credits") { open = true }.padding(vertical = 4.dp)) {
        Text(
            MapCredits.line(credits),
            Modifier.background(colors.surface.copy(alpha = 0.92f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            color = colors.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (open) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Map credits") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    credits.forEach { credit ->
                        val url = credit.url
                        if (url != null) {
                            // No side padding: links line up with the lines of text below them.
                            TextButton(onClick = { runCatching { uriHandler.openUri(url) } }, contentPadding = PaddingValues(vertical = 8.dp)) { Text(credit.text) }
                        } else {
                            Text(credit.text)
                        }
                    }
                    Text("Drawn with MapLibre.", color = colors.onSurfaceVariant)
                    Text("Speed limits, cameras and reports credit their sources in Settings.", color = colors.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}
