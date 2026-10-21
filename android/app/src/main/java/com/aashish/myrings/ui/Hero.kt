package com.aashish.myrings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One arc of the hero: its value (0..100, null = nothing yet), and its two gradient colors. */
class RingSpec(val value: Int?, val from: Color, val to: Color)

/**
 * The hero of the Today screen: three concentric rings, like a ring seen end-on.
 * Outer = readiness, middle = sleep, inner = calm. They sweep in together; with no data they breathe softly.
 */
@Composable
fun ConcentricRings(rings: List<RingSpec>, modifier: Modifier = Modifier, size: Dp = 270.dp, center: @Composable BoxScope.() -> Unit) {
    val a = MaterialTheme.aura
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = if (a.isDark) 0.07f else 0.06f)
    val progress = rings.mapIndexed { i, r -> animatedProgress((r.value ?: 0) / 100f, durationMs = Motion.Sweep + i * 180, delayMs = i * 120) }
    val pulse = rememberPulse()
    val anyData = rings.any { it.value != null }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 15.dp.toPx()
            val gap = 9.dp.toPx()
            rings.forEachIndexed { i, r ->
                val inset = i * (stroke + gap) + stroke / 2
                val arc = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val tl = Offset(inset, inset)
                drawArc(track, 0f, 360f, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                val sweep = if (r.value == null) (if (anyData) 0f else 360f * (0.18f + 0.1f * pulse)) else 360f * progress[i]
                if (sweep > 0.5f) {
                    // butt-capped gradient arc, then a round dot at each end in that end's own color
                    // (a round cap on a sweep gradient picks up the wrong color where the gradient wraps)
                    val brush = Brush.sweepGradient(0f to r.from, (sweep / 360f).coerceIn(0.02f, 1f) to r.to, 1f to r.to, center = this.center)
                    rotate(-90f, this.center) {
                        if (r.value != null) drawArc(brush, 0f, sweep, false, tl, arc, alpha = 0.16f, style = Stroke(stroke + 8.dp.toPx(), cap = StrokeCap.Butt))
                        drawArc(brush, 0f, sweep, false, tl, arc, alpha = if (r.value == null) 0.35f else 1f, style = Stroke(stroke, cap = StrokeCap.Butt))
                    }
                    val radius = arc.width / 2f
                    val cx = this.center.x; val cy = this.center.y
                    val dotAlpha = if (r.value == null) 0.35f else 1f
                    fun dot(deg: Float, c: Color) {
                        val rad = Math.toRadians((deg - 90f).toDouble())
                        drawCircle(c, stroke / 2f, Offset(cx + (radius * Math.cos(rad)).toFloat(), cy + (radius * Math.sin(rad)).toFloat()), alpha = dotAlpha)
                    }
                    dot(0f, r.from)
                    dot(sweep, r.to)
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(size * 0.2f), contentAlignment = Alignment.Center, content = center)
    }
}

/** A tappable score line under the hero: a color dot, the name, and the value in the serif. */
@Composable
fun ScoreKey(name: String, value: Int?, color: Color, caption: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val a = MaterialTheme.aura
    Column(modifier.pressScale(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(name, fontSize = 12.sp, color = a.ink2, fontWeight = FontWeight.Medium)
        }
        CountUpText(value?.toDouble(), AuraType.Figure, MaterialTheme.colorScheme.onSurface, delayMs = 300)
        Text(caption, fontSize = 11.sp, color = a.ink3, maxLines = 1)
    }
}
