package app.nullpointr.foxnav.convoy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import app.nullpointr.foxnav.ui.PanelBounds
import app.nullpointr.foxnav.ui.icons.FoxIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.shared.convoy.ConvoyMember
import app.nullpointr.foxnav.shared.convoy.ConvoyStatus
import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.convoy.Signal
import app.nullpointr.foxnav.shared.convoy.roadGap
import app.nullpointr.foxnav.shared.convoy.signal
import app.nullpointr.foxnav.shared.format.formatDistance
import app.nullpointr.foxnav.shared.format.kilometersPerHour
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.ui.theme.ChakraPetch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import java.text.DecimalFormatSymbols
import kotlin.math.abs

/**
 * The convoy on top of the map, in free drive and while navigating: the timing tower and the
 * latest notice (quick message, regroup point, someone leaving).
 *
 * The tower floats between the screen's top and bottom [panels]. Drag it anywhere: it snaps to
 * the nearer side and stays where it was put, also after a restart. Its chevron folds it to a
 * header. It steps aside while [hidden], e.g. while searching.
 */
@Composable
fun BoxScope.ConvoyOverlay(
    convoy: ConvoyController,
    placements: TowerPlacementStore,
    panels: PanelBounds,
    hidden: Boolean,
    route: List<GeoPoint>?,
    onOpen: () -> Unit,
) {
    val active by convoy.active.collectAsState()
    val placement by placements.placement.collectAsState()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val scope = rememberCoroutineScope()

    var origin by remember { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var towerSize by remember { mutableStateOf(IntSize.Zero) }
    val position = remember { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
    var placed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    // The free space for the tower, in this overlay's coordinates.
    val margin = with(density) { EDGE_MARGIN.toPx() }
    val left = insets.getLeft(density, direction) + margin
    val right = area.width - insets.getRight(density, direction) - margin
    val top = (if (panels.top > 0f) panels.top - origin.y else insets.getTop(density).toFloat()) + margin
    val bottom = minOf(panels.bottom - origin.y, area.height - insets.getBottom(density).toFloat()) - with(density) { BOTTOM_CLEARANCE.toPx() }
    val travel = (bottom - top - towerSize.height).coerceAtLeast(0f)
    val resting = IntOffset(
        x = (if (placement.rightSide) right - towerSize.width else left).roundToInt(),
        y = (top + placement.fraction * travel).roundToInt(),
    )
    val ready = area != IntSize.Zero && towerSize != IntSize.Zero

    LaunchedEffect(resting, dragging, ready) {
        if (!ready || dragging) return@LaunchedEffect
        if (placed) {
            position.animateTo(resting)
        } else {
            position.snapTo(resting)
            placed = true
        }
    }

    Box(
        Modifier
            .matchParentSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                area = it.size
            },
    ) {
        val session = active?.session
        if (session != null) {
            AnimatedVisibility(visible = !hidden, enter = fadeIn(), exit = fadeOut()) {
                val members by session.members.collectAsState()
                val status by session.status.collectAsState()
                val me by convoy.myFix.collectAsState()
                val profile by convoy.profiles.profile.collectAsState()
                TimingTower(
                    members = members,
                    status = status,
                    me = me,
                    myColorRgb = profile.colorRgb,
                    route = route,
                    collapsed = placement.collapsed,
                    onToggleCollapsed = { placements.save(placement.copy(collapsed = !placement.collapsed)) },
                    onClick = onOpen,
                    modifier = Modifier
                        .offset { position.value }
                        .graphicsLayer { alpha = if (placed) 1f else 0f }
                        .onSizeChanged { towerSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = {
                                    // Snap to the nearer side, and remember how far down it went.
                                    val current = position.value
                                    val rightSide = current.x + towerSize.width / 2f > area.width / 2f
                                    val fraction = if (travel > 0f) ((current.y - top) / travel).coerceIn(0f, 1f) else 0f
                                    placements.save(placements.placement.value.copy(rightSide = rightSide, fraction = fraction))
                                    dragging = false
                                },
                                onDragCancel = { dragging = false },
                            ) { change, amount ->
                                change.consume()
                                scope.launch { position.snapTo(position.value + amount.round()) }
                            }
                        },
                )
            }
        }
        // Notices go on top of everything, under the top panel.
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, top.roundToInt()) }
                .padding(horizontal = EDGE_MARGIN),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (!hidden) NoticeBanner(convoy.notices, Modifier.widthIn(max = 360.dp))
        }
    }
}

private data class TowerRow(
    val key: Long,
    val name: String,
    val colorRgb: Int,
    /** Metres ahead of us (negative: behind); null when unknown. */
    val offsetM: Double?,
    val speedKmh: Int?,
    val signal: Signal,
    val isMe: Boolean = false,
)

/**
 * Everyone in road order, the cars ahead of us at the top, with the gap to us and their live
 * speed. Cars we haven't heard from for a while fade; after a minute they show as lost.
 */
@Composable
private fun TimingTower(
    members: Map<Long, ConvoyMember>,
    status: ConvoyStatus,
    me: Fix?,
    myColorRgb: Int,
    route: List<GeoPoint>?,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val rows = buildList {
        add(TowerRow(key = -1, name = "You", colorRgb = myColorRgb, offsetM = 0.0, speedKmh = me?.let { kilometersPerHour(it.speedMps) }, signal = Signal.LIVE, isMe = true))
        for (member in members.values) {
            val fix = member.fix
            add(
                TowerRow(
                    key = member.id,
                    name = member.profile?.display_name?.ifBlank { null } ?: "Driver",
                    colorRgb = member.profile?.car?.color_rgb ?: ConvoyPalette.first(),
                    offsetM = if (me != null && fix != null) roadGap(me, fix.point, route) else null,
                    speedKmh = fix?.let { kilometersPerHour(it.speedMps) },
                    signal = member.signal(now),
                ),
            )
        }
    }.sortedByDescending { it.offsetM ?: Double.NEGATIVE_INFINITY }

    Surface(
        onClick = onClick,
        modifier = modifier.widthIn(max = 190.dp),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, colors.outline),
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(bottom = if (collapsed) 0.dp else 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (collapsed) "CONVOY · ${rows.size}" else "CONVOY",
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    color = colors.secondary,
                )
                Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
                val (label, color) = when (status) {
                    is ConvoyStatus.Live -> "LIVE" to colors.secondary
                    is ConvoyStatus.Reconnecting, ConvoyStatus.Connecting -> "OFFLINE" to colors.tertiary
                    is ConvoyStatus.Ended -> "ENDED" to colors.onSurfaceVariant
                }
                Text(label, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.sp, color = color)
                IconButton(onClick = onToggleCollapsed, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (collapsed) FoxIcons.ChevronDown else FoxIcons.ChevronUp,
                        contentDescription = if (collapsed) "Show the convoy" else "Fold the convoy away",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (!collapsed) {
                rows.take(MAX_ROWS).forEach { row -> TowerRowView(row) }
                if (rows.size > MAX_ROWS) {
                    Text("+${rows.size - MAX_ROWS} more", Modifier.padding(horizontal = 10.dp, vertical = 2.dp), fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TowerRowView(row: TowerRow) {
    val colors = MaterialTheme.colorScheme
    val fade = when (row.signal) {
        Signal.LIVE -> 1f
        Signal.WAITING, Signal.STALE -> 0.55f
        Signal.LOST -> 0.4f
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (row.isMe) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .alpha(fade),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Color(0xFF000000.toInt() or row.colorRgb), RoundedCornerShape(2.dp)))
        Text(
            row.name,
            Modifier.weight(1f),
            fontWeight = if (row.isMe) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            gapText(row),
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = if (row.signal == Signal.LOST) colors.error else colors.onSurfaceVariant,
        )
        Text(
            row.speedKmh?.takeIf { row.signal != Signal.LOST }?.toString() ?: "–",
            Modifier.width(30.dp),
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            textAlign = TextAlign.End,
        )
    }
}

private fun gapText(row: TowerRow): String {
    if (row.isMe) return ""
    return when (row.signal) {
        Signal.WAITING -> "joining"
        Signal.LOST -> "LOST"
        else -> {
            val offset = row.offsetM ?: return ""
            val distance = formatDistance(abs(offset), DecimalFormatSymbols.getInstance().decimalSeparator)
            if (offset >= 0) "+$distance" else "−$distance"
        }
    }
}

/** The latest convoy notice, for a few seconds. */
@Composable
private fun NoticeBanner(notices: SharedFlow<ConvoyNotice>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var current by remember { mutableStateOf<ConvoyNotice?>(null) }
    var shown by remember { mutableStateOf<ConvoyNotice?>(null) }
    LaunchedEffect(notices) {
        notices.collect { notice ->
            current = notice
            shown = notice
        }
    }
    LaunchedEffect(current) {
        if (current != null) {
            delay(NOTICE_DURATION_MS)
            current = null
        }
    }

    AnimatedVisibility(
        visible = current != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        val notice = shown ?: return@AnimatedVisibility
        val accent = when {
            notice.warning -> colors.tertiary
            notice.colorRgb != null -> Color(0xFF000000.toInt() or notice.colorRgb)
            else -> colors.secondary
        }
        Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, accent)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(18.dp).background(accent, RoundedCornerShape(2.dp)))
                Text(notice.text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val MAX_ROWS = 6
private const val NOTICE_DURATION_MS = 5_000L
private val EDGE_MARGIN = 12.dp

// Room left above the bottom panel for the map buttons and the road name.
private val BOTTOM_CLEARANCE = 72.dp
