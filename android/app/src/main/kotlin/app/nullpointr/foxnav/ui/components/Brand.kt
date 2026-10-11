package app.nullpointr.foxnav.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.ui.theme.ChakraPetch

/** The kitsune mark from the design's direction board: a white fox mask with vermilion markings. */
@Composable
fun KitsuneMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).semantics { contentDescription = "FoxNav" }) {
        val unit = this.size.width / 120f
        fun polygon(vararg points: Int) = Path().apply {
            moveTo(points[0] * unit, points[1] * unit)
            for (i in 2 until points.size step 2) lineTo(points[i] * unit, points[i + 1] * unit)
            close()
        }
        drawPath(polygon(14, 6, 46, 36, 60, 30, 74, 36, 106, 6, 100, 62, 60, 114, 20, 62), FACE)
        drawPath(polygon(20, 15, 39, 36, 25, 42), MARKINGS)
        drawPath(polygon(100, 15, 81, 36, 95, 42), MARKINGS)
        drawPath(polygon(60, 38, 66, 47, 60, 56, 54, 47), MARKINGS)
        drawLine(MARKINGS, Offset(33 * unit, 60 * unit), Offset(52 * unit, 69 * unit), strokeWidth = 5 * unit, cap = StrokeCap.Round)
        drawLine(MARKINGS, Offset(87 * unit, 60 * unit), Offset(68 * unit, 69 * unit), strokeWidth = 5 * unit, cap = StrokeCap.Round)
        drawPath(polygon(55, 100, 65, 100, 60, 108), NOSE)
    }
}

/** FOXNAV in Chakra Petch, with 狐ナビ as its Japanese accent. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, detail: String? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        KitsuneMark(56.dp)
        Column {
            Text("FOXNAV", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 0.08.em, lineHeight = 30.sp)
            JapaneseAccent("狐ナビ", fontSize = 14.sp, letterSpacing = 0.2.em)
            detail?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// The mark keeps its own colours in both themes, like a logo.
private val FACE = Color(0xFFF4F1EA)
private val MARKINGS = Color(0xFFE5462E)
private val NOSE = Color(0xFF0B0D12)
