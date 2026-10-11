package app.nullpointr.foxnav.drive

import app.nullpointr.foxnav.ui.format.distanceText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.shared.format.formatDistance
import app.nullpointr.foxnav.shared.reports.Report
import app.nullpointr.foxnav.shared.reports.ReportAhead
import app.nullpointr.foxnav.ui.icons.FoxIcons
import app.nullpointr.foxnav.ui.theme.ChakraPetch
import java.text.DecimalFormatSymbols

/** The round button that opens the report sheet: by the map controls in free drive, in the panel while navigating. */
@Composable
fun ReportButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = colors.tertiaryContainer,
        border = BorderStroke(1.dp, colors.tertiary),
        modifier = modifier.size(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(FoxIcons.Report, contentDescription = "Report", tint = colors.tertiary)
        }
    }
}

/** Big buttons, one tap each: what's on the road here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(onReport: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Report", style = MaterialTheme.typography.titleLarge)
            ReportButtonOrder.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { type ->
                        Surface(
                            onClick = { onReport(type) },
                            shape = RoundedCornerShape(18.dp),
                            color = colors.surfaceVariant,
                            contentColor = colors.onSurface,
                            border = BorderStroke(1.dp, colors.outline),
                            modifier = Modifier.weight(1f).height(104.dp),
                        ) {
                            Column(
                                Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(reportIcon(type), contentDescription = null, tint = reportColor(type), modifier = Modifier.size(40.dp))
                                Text(ReportLabels.label(type), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            }
            Text(
                "Reports are public and anonymous. They're placed where you are now, for the way you're heading.",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The report coming up, centred above the bottom panel like the camera warning. */
@Composable
fun ReportWarning(ahead: ReportAhead?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var shown by remember { mutableStateOf(ahead) }
    if (ahead != null) shown = ahead

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = ahead != null, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
            val warning = shown ?: return@AnimatedVisibility
            val accent = reportColor(warning.report.type)
            Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, border = BorderStroke(1.dp, accent)) {
                Row(
                    Modifier.padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(reportIcon(warning.report.type), contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
                    Column {
                        Text(
                            ReportLabels.label(warning.report.type).uppercase(),
                            fontFamily = ChakraPetch,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp,
                        )
                        // Official reports name their source: their licences ask for it.
                        warning.report.source?.let { source ->
                            Text(source.name, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        distanceText(warning.distanceM),
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                }
            }
        }
    }
}

/** "Police still there?" after passing a driver's report, with big yes and no buttons. */
@Composable
fun StillTherePrompt(report: Report?, onAnswer: (Boolean?) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var shown by remember { mutableStateOf(report) }
    if (report != null) shown = report

    AnimatedVisibility(visible = report != null, modifier = modifier.fillMaxWidth(), enter = fadeIn(), exit = fadeOut()) {
        val asked = shown ?: return@AnimatedVisibility
        Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outline)) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(reportIcon(asked.type), contentDescription = null, tint = reportColor(asked.type), modifier = Modifier.size(24.dp))
                    Text("${ReportLabels.label(asked.type)} still there?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onAnswer(null) }) { Icon(FoxIcons.Close, contentDescription = "Don't answer") }
                }
                Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Not there", fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = { onAnswer(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("Still there", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** "Reported: police. Thanks!" for a few seconds after reporting. */
@Composable
fun ReportNotice(text: String?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(text) }
    if (text != null) shown = text
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut()) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Text(shown.orEmpty(), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Everything about reports that floats above the bottom panel. */
@Composable
fun ReportStack(viewModel: DriveViewModel) {
    val reportAhead by viewModel.reportAhead.collectAsState()
    val stillThere by viewModel.stillThere.collectAsState()
    val notice by viewModel.reportNotice.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReportNotice(notice)
        StillTherePrompt(stillThere, onAnswer = viewModel::answerStillThere)
        if (stillThere == null) ReportWarning(reportAhead)
    }
}
