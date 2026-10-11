package app.nullpointr.foxnav.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.shared.search.Place
import app.nullpointr.foxnav.shared.search.SavedPlace
import app.nullpointr.foxnav.shared.search.SavedPlaces
import app.nullpointr.foxnav.ui.icons.FoxIcons
import app.nullpointr.foxnav.ui.theme.ChakraPetch

/** "Home 14 min", "Work 22 min", favourites: one tap to a route, as in the free-drive mockup. */
@Composable
fun ShortcutChips(viewModel: DriveViewModel, modifier: Modifier = Modifier) {
    val places by viewModel.savedPlaces.collectAsState()
    val minutes by viewModel.shortcutMinutes.collectAsState()
    val shortcuts = SavedPlaces.shortcuts(places)
    if (shortcuts.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shortcuts.forEach { saved ->
            Surface(
                onClick = { viewModel.preview(saved.place) },
                shape = RoundedCornerShape(20.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outline),
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(saved.kind.icon, contentDescription = null, tint = if (saved.kind == SavedPlace.Kind.FAVOURITE) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    // The row scrolls sideways, so a long favourite's name is cut short rather than squeezed.
                    Text(saved.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                    minutes[saved.place]?.let { Text("$it min", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = colors.onSurfaceVariant) }
                }
            }
        }
    }
}

/** Save the place being previewed: as home, work or a favourite, or no longer. */
@Composable
fun SavePlaceButton(viewModel: DriveViewModel, place: Place) {
    val places by viewModel.savedPlaces.collectAsState()
    val saved = SavedPlaces.savedAs(places, place)
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(
                saved?.kind?.icon ?: FoxIcons.Star,
                contentDescription = if (saved != null) "Saved as ${saved.kindName}" else "Save this place",
                tint = if (saved != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            listOf(SavedPlace.Kind.HOME to "Save as home", SavedPlace.Kind.WORK to "Save as work", SavedPlace.Kind.FAVOURITE to "Add to favourites")
                .filter { (kind, _) -> saved?.kind != kind }
                .forEach { (kind, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { Icon(kind.icon, contentDescription = null) },
                        onClick = {
                            menu = false
                            if (saved != null) viewModel.removePlace(saved)
                            viewModel.savePlace(place, kind)
                        },
                    )
                }
            if (saved != null) {
                DropdownMenuItem(
                    text = { Text("Remove from saved places", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menu = false
                        viewModel.removePlace(saved)
                    },
                )
            }
        }
    }
}

val SavedPlace.Kind.icon: ImageVector
    get() = when (this) {
        SavedPlace.Kind.HOME -> FoxIcons.Home
        SavedPlace.Kind.WORK -> FoxIcons.Work
        SavedPlace.Kind.FAVOURITE -> FoxIcons.Star
        SavedPlace.Kind.RECENT -> FoxIcons.Recent
    }

/** "Home", "Work", or the place's own name. */
val SavedPlace.label: String
    get() = when (kind) {
        SavedPlace.Kind.HOME -> "Home"
        SavedPlace.Kind.WORK -> "Work"
        else -> place.title
    }

val SavedPlace.kindName: String
    get() = when (kind) {
        SavedPlace.Kind.HOME -> "home"
        SavedPlace.Kind.WORK -> "work"
        SavedPlace.Kind.FAVOURITE -> "a favourite"
        SavedPlace.Kind.RECENT -> "recent"
    }
