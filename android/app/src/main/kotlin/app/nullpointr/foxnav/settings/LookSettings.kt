package app.nullpointr.foxnav.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nullpointr.foxnav.shared.speed.SpeedReading
import app.nullpointr.foxnav.shared.theme.ColorMath
import app.nullpointr.foxnav.shared.theme.Palette
import app.nullpointr.foxnav.shared.theme.PaletteColors
import app.nullpointr.foxnav.shared.theme.Tokens
import app.nullpointr.foxnav.ui.components.JapaneseAccent
import app.nullpointr.foxnav.ui.components.SpeedCluster
import app.nullpointr.foxnav.ui.icons.FoxIcons
import app.nullpointr.foxnav.ui.theme.LocalHudScale
import app.nullpointr.foxnav.ui.theme.LocalNight
import app.nullpointr.foxnav.ui.theme.accentColors
import app.nullpointr.foxnav.ui.theme.rgb
import app.nullpointr.foxnav.ui.theme.themeColors
import kotlin.math.roundToInt

/** What the colour picker is choosing. */
private enum class Picking { ACCENT, ROUTE }

/**
 * The Look section: the colour theme, the accent and route colours (any colour, with a picker),
 * and the text and driving display sizes. Everything shows in the current day or night colours.
 */
@Composable
fun SettingsScope.LookSettings(settings: Settings, palettes: List<Palette>, update: ((Settings) -> Settings) -> Unit) {
    val night = LocalNight.current
    val palette = palettes.firstOrNull { it.id == settings.palette } ?: palettes.first()
    val shown = themeColors(palette, night, settings)
    var picking by remember { mutableStateOf<Picking?>(null) }

    PaletteRow(palettes, palette.id, settings, night) { id -> update { it.copy(palette = id) } }
    AccentRow(settings.accent, palette.colors(night)[Tokens.BRAND], settings.customAccent, night) { accent ->
        if (accent == Accent.CUSTOM) picking = Picking.ACCENT else update { it.copy(accent = accent) }
    }
    ColourChoiceRow(
        title = "Route colour",
        options = listOf(
            Triple(RouteColor.THEME, rgb(palette.colors(night)[Tokens.ROUTE]), "The theme's own"),
            Triple(RouteColor.CUSTOM, rgb(ColorMath.withContrast(settings.customRoute, palette.colors(night)[Tokens.MAP_LAND], 3.0)), "Your own"),
        ),
        selected = settings.routeColor,
        custom = RouteColor.CUSTOM,
        onSelect = { choice -> if (choice == RouteColor.CUSTOM) picking = Picking.ROUTE else update { it.copy(routeColor = choice) } },
    )
    // Applied when the slider is let go: a new text size makes Compose make this dialog again.
    ScaleRow("Text size", "Menus, lists and the map's labels, beyond the phone's own text size.", settings.textScale, onChange = { scale -> update { it.copy(textScale = scale) } }, live = false) { scale ->
        Text("Aa · Mannerheimintie 350 m", fontSize = MaterialTheme.typography.bodyLarge.fontSize * (scale / settings.textScale))
    }
    ScaleRow("Driving display size", "The speedometer, the next turn and the arrival time.", settings.hudScale, onChange = { scale -> update { it.copy(hudScale = scale) } }) { scale ->
        CompositionLocalProvider(LocalHudScale provides scale) { SpeedCluster(SpeedReading(speed = 62, limit = 60)) }
    }

    when (picking) {
        Picking.ACCENT -> ColourPickerDialog(
            title = "Accent colour",
            initial = settings.customAccent.takeIf { settings.accent == Accent.CUSTOM } ?: (shown[Tokens.BRAND]),
            palette = palette,
            forRoute = false,
            onDismiss = { picking = null },
            onPick = { color ->
                update { it.copy(accent = Accent.CUSTOM, customAccent = color) }
                picking = null
            },
        )
        Picking.ROUTE -> ColourPickerDialog(
            title = "Route colour",
            initial = settings.customRoute.takeIf { settings.routeColor == RouteColor.CUSTOM } ?: (shown[Tokens.ROUTE]),
            palette = palette,
            forRoute = true,
            onDismiss = { picking = null },
            onPick = { color ->
                update { it.copy(routeColor = RouteColor.CUSTOM, customRoute = color) }
                picking = null
            },
        )
        null -> Unit
    }
}

/** The colour themes side by side, each as a little map with a panel, in day or night colours. */
@Composable
private fun PaletteRow(palettes: List<Palette>, selected: String, settings: Settings, night: Boolean, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Colour theme", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            palettes.forEach { palette ->
                val chosen = palette.id == selected
                Column(
                    Modifier
                        .width(132.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(palette.id) }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = chosen
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // With the driver's accent and route colour, as it would look.
                    PalettePreview(
                        themeColors(palette, night, settings),
                        Modifier.border(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(palette.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (palette.japanese.isNotEmpty()) JapaneseAccent(palette.japanese)
                }
            }
        }
    }
}

/** A palette in miniature: map land, water and a park, roads with the route along one, and a panel. */
@Composable
private fun PalettePreview(colors: PaletteColors, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().aspectRatio(1.55f).clip(RoundedCornerShape(16.dp))) {
        val w = size.width
        val h = size.height
        fun color(token: String) = rgb(colors[token])
        drawRect(color(Tokens.MAP_LAND))
        drawCircle(color(Tokens.MAP_WATER), radius = h * 0.5f, center = Offset(w * 0.08f, h * 0.05f))
        drawRoundRect(color(Tokens.MAP_PARK), topLeft = Offset(w * 0.64f, h * 0.08f), size = Size(w * 0.28f, h * 0.28f), cornerRadius = CornerRadius(h * 0.06f))
        drawLine(color(Tokens.MAP_ROAD), Offset(w * 0.42f, 0f), Offset(w * 0.56f, h * 0.7f), strokeWidth = h * 0.05f)
        drawLine(color(Tokens.MAP_ROAD_CASING), Offset(-w * 0.05f, h * 0.6f), Offset(w * 1.05f, h * 0.32f), strokeWidth = h * 0.11f)
        drawLine(color(Tokens.MAP_ROAD_MAJOR), Offset(-w * 0.05f, h * 0.6f), Offset(w * 1.05f, h * 0.32f), strokeWidth = h * 0.08f)
        drawLine(color(Tokens.ROUTE), Offset(-w * 0.05f, h * 0.6f), Offset(w * 0.6f, h * 0.43f), strokeWidth = h * 0.06f, cap = StrokeCap.Round)
        drawRoundRect(color(Tokens.RAISED), topLeft = Offset(w * 0.06f, h * 0.68f), size = Size(w * 0.88f, h * 0.26f), cornerRadius = CornerRadius(h * 0.08f))
        drawRoundRect(color(Tokens.LINE), topLeft = Offset(w * 0.06f, h * 0.68f), size = Size(w * 0.88f, h * 0.26f), cornerRadius = CornerRadius(h * 0.08f), style = Stroke(1.dp.toPx()))
        drawCircle(color(Tokens.BRAND), radius = h * 0.07f, center = Offset(w * 0.16f, h * 0.81f))
        drawRoundRect(color(Tokens.TEXT), topLeft = Offset(w * 0.27f, h * 0.75f), size = Size(w * 0.38f, h * 0.05f), cornerRadius = CornerRadius(h * 0.03f))
        drawRoundRect(color(Tokens.TEXT_SECONDARY), topLeft = Offset(w * 0.27f, h * 0.84f), size = Size(w * 0.24f, h * 0.04f), cornerRadius = CornerRadius(h * 0.02f))
        drawRoundRect(color(Tokens.WARNING), topLeft = Offset(w * 0.74f, h * 0.76f), size = Size(w * 0.14f, h * 0.1f), cornerRadius = CornerRadius(h * 0.05f))
    }
}

/** The accent: the theme's own, a preset, or the driver's (which opens the picker). */
@Composable
private fun AccentRow(selected: Accent, themeAccent: Int, customAccent: Int, night: Boolean, onSelect: (Accent) -> Unit) {
    ColourChoiceRow(
        title = "Accent colour",
        options = Accent.entries.map { accent ->
            val color = when (accent) {
                Accent.THEME -> rgb(themeAccent)
                Accent.CUSTOM -> rgb(customAccent)
                else -> accentColors(accent, night).first
            }
            Triple(accent, color, accent.label)
        },
        selected = selected,
        custom = Accent.CUSTOM,
        onSelect = onSelect,
    )
}

/** Colours as round swatches; the [custom] one carries a plus and opens the picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ColourChoiceRow(title: String, options: List<Triple<T, Color, String>>, selected: T, custom: T, onSelect: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, color, label) ->
                val chosen = value == selected
                Box(
                    Modifier
                        .size(44.dp)
                        .border(if (chosen) 3.dp else 1.dp, if (chosen) colors.onSurface else colors.outline, CircleShape)
                        .padding(6.dp)
                        .background(color, CircleShape)
                        .clickable { onSelect(value) }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = chosen
                            contentDescription = label
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (value == custom) Icon(FoxIcons.Plus, contentDescription = null, tint = rgb(ColorMath.onColor(color.toArgb() and 0xFFFFFF)), modifier = Modifier.size(18.dp))
                }
            }
        }
        Text(options.firstOrNull { it.first == selected }?.third.orEmpty(), color = colors.onSurfaceVariant)
    }
}

/**
 * A size from 90 % to 130 %, in steps of 5, with a [preview] at the size the slider shows. [live]
 * applies it while sliding; otherwise when the slider is let go.
 */
@Composable
private fun ScaleRow(title: String, summary: String, scale: Float, onChange: (Float) -> Unit, live: Boolean = true, preview: (@Composable (Float) -> Unit)? = null) {
    var shown by remember(scale) { mutableFloatStateOf(scale) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = shown,
                onValueChange = { value ->
                    shown = (value * 20).roundToInt() / 20f
                    if (live) onChange(shown)
                },
                onValueChangeFinished = { if (!live && shown != scale) onChange(shown) },
                valueRange = 0.9f..1.3f,
                steps = 7,
                modifier = Modifier.weight(1f),
            )
            Text("${(shown * 100).roundToInt()} %", Modifier.padding(start = 12.dp))
        }
        preview?.invoke(shown)
    }
}

/**
 * Any colour: saturation and brightness on a square, hue on a bar, or typed as hex. It shows how
 * the colour comes out at night and by day in the [palette], adjusted where it needs to be to read.
 * A route can't be amber or red: those mean warnings.
 */
@Composable
private fun ColourPickerDialog(title: String, initial: Int, palette: Palette, forRoute: Boolean, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val start = remember { ColorMath.toHsv(initial) }
    var hue by remember { mutableFloatStateOf(start.first.toFloat()) }
    var saturation by remember { mutableFloatStateOf(start.second.toFloat()) }
    var value by remember { mutableFloatStateOf(start.third.toFloat()) }
    val color = ColorMath.fromHsv(hue.toDouble(), saturation.toDouble(), value.toDouble())
    var typed by remember { mutableStateOf(ColorMath.hex(initial)) }
    val warning = forRoute && ColorMath.looksLikeWarning(color)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SaturationValueSquare(hue, saturation, value) { s, v ->
                    saturation = s
                    value = v
                    typed = ColorMath.hex(ColorMath.fromHsv(hue.toDouble(), s.toDouble(), v.toDouble()))
                }
                HueBar(hue) { h ->
                    hue = h
                    typed = ColorMath.hex(ColorMath.fromHsv(h.toDouble(), saturation.toDouble(), value.toDouble()))
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { text ->
                        typed = text
                        ColorMath.parseHex(text)?.let { parsed ->
                            val (h, s, v) = ColorMath.toHsv(parsed)
                            hue = h.toFloat()
                            saturation = s.toFloat()
                            value = v.toFloat()
                        }
                    },
                    label = { Text("Hex") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (night in listOf(true, false)) ColourInUse(color, palette.colors(night), forRoute, if (night) "Night" else "Day", Modifier.weight(1f))
                }
                if (warning) {
                    Text("Amber and red mean warnings in FoxNav, so the route can't take them.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }, enabled = !warning) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** [color] as the app would use it on [colors]' backgrounds: a route on the map, or a button. */
@Composable
private fun ColourInUse(color: Int, colors: PaletteColors, forRoute: Boolean, label: String, modifier: Modifier = Modifier) {
    val background = if (forRoute) colors[Tokens.MAP_LAND] else colors[Tokens.RAISED]
    val adjusted = ColorMath.withContrast(color, background, 3.0)
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = rgb(background), border = BorderStroke(1.dp, rgb(colors[Tokens.LINE]))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = rgb(colors[Tokens.TEXT_SECONDARY]))
            if (forRoute) {
                Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                    drawLine(rgb(colors[Tokens.MAP_ROAD_MAJOR]), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height * 0.8f)
                    drawLine(rgb(adjusted), Offset(size.height / 2, size.height / 2), Offset(size.width - size.height / 2, size.height / 2), strokeWidth = size.height * 0.5f, cap = StrokeCap.Round)
                }
            } else {
                Surface(shape = RoundedCornerShape(10.dp), color = rgb(adjusted)) {
                    Text("Go", Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = rgb(ColorMath.onColor(adjusted)), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun SaturationValueSquare(hue: Float, saturation: Float, value: Float, onChange: (Float, Float) -> Unit) {
    val pure = rgb(ColorMath.fromHsv(hue.toDouble(), 1.0, 1.0))
    fun pick(at: Offset, width: Float, height: Float) = onChange((at.x / width).coerceIn(0f, 1f), 1f - (at.y / height).coerceIn(0f, 1f))
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Saturation and brightness" }
            .pointerInput(Unit) { detectTapGestures { pick(it, size.width.toFloat(), size.height.toFloat()) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> pick(change.position, size.width.toFloat(), size.height.toFloat()) } },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val at = Offset(saturation * size.width, (1 - value) * size.height)
        drawCircle(Color.White, radius = 11.dp.toPx(), center = at, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black, radius = 13.dp.toPx(), center = at, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val rainbow = (0..6).map { rgb(ColorMath.fromHsv(it * 60.0, 1.0, 1.0)) }
    fun pick(x: Float, width: Float) = onChange((x / width).coerceIn(0f, 1f) * 359.9f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .semantics { contentDescription = "Hue" }
            .pointerInput(Unit) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> pick(change.position.x, size.width.toFloat()) } },
    ) {
        drawRect(Brush.horizontalGradient(rainbow))
        val x = hue / 360f * size.width
        drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
    }
}

val Accent.label: String
    get() = when (this) {
        Accent.THEME -> "The theme's own"
        Accent.VERMILION -> "Vermilion (torii red)"
        Accent.SAKURA -> "Sakura pink"
        Accent.OCEAN -> "Ocean blue"
        Accent.LIME -> "Lime"
        Accent.VIOLET -> "Violet"
        Accent.MONO -> "Monochrome"
        Accent.CUSTOM -> "Your own"
    }
