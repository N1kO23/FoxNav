package app.nullpointr.foxnav.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nullpointr.foxnav.ui.icons.FoxIcons
import app.nullpointr.foxnav.ui.theme.ChakraPetch
import app.nullpointr.foxnav.ui.theme.LocalNight
import app.nullpointr.foxnav.ui.theme.accentColors

/** A full-screen page over the map: everything the user can set, in sections. Changes apply at once. */
@Composable
fun SettingsScreen(onClose: () -> Unit, content: @Composable SettingsScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The dialog has its own window: its status bar icons follow our colours, not the phone's.
        val view = LocalView.current
        val night = LocalNight.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !night
                    isAppearanceLightNavigationBars = !night
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // On a tablet or a phone on its side, a column of readable width rather than lines across the screen.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(FoxIcons.Back, contentDescription = "Back") }
                        Text("Settings", style = MaterialTheme.typography.titleLarge)
                    }
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        SettingsScope.content()
                    }
                }
            }
        }
    }
}

/** The building blocks of [SettingsScreen]. */
object SettingsScope {
    @Composable
    fun Section(title: String) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.size(6.dp))
            Text(
                title.uppercase(),
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }

    @Composable
    fun SwitchRow(title: String, summary: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                summary?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    /** One choice out of a few, as chips. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun <T> ChoiceRow(title: String, summary: String? = null, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            summary?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (value, label) ->
                    FilterChip(
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }
    }

    /** A row that opens more: a sheet with its own settings. */
    @Composable
    fun LinkRow(title: String, summary: String, action: String = "Open", onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun AccentRow(selected: Accent, onSelect: (Accent) -> Unit) {
        val night = LocalNight.current
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Accent colour", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Accent.entries.forEach { accent ->
                    val chosen = accent == selected
                    Box(
                        Modifier
                            .size(44.dp)
                            .border(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
                            .padding(6.dp)
                            .background(accentColors(accent, night).first, CircleShape)
                            .clickable { onSelect(accent) }
                            .semantics {
                                role = Role.RadioButton
                                contentDescription = accent.label
                            },
                    )
                }
            }
            Text(selected.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    fun Note(text: String) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    fun Swatch(color: Color, modifier: Modifier = Modifier) {
        Box(modifier.size(14.dp).background(color, CircleShape))
    }
}

val Accent.label: String
    get() = when (this) {
        Accent.VERMILION -> "Vermilion (torii red)"
        Accent.SAKURA -> "Sakura pink"
        Accent.OCEAN -> "Ocean blue"
        Accent.LIME -> "Lime"
        Accent.VIOLET -> "Violet"
        Accent.MONO -> "Monochrome"
    }
