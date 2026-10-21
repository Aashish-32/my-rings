package com.aashish.myrings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.*
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A quiet glass panel: a faint vertical gradient and a hairline, no shadow. Used sparingly, not for every block. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val a = MaterialTheme.aura
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, a.hairline),
    ) {
        Column(Modifier.background(Brush.verticalGradient(listOf(a.glassTop, a.glassBottom))).padding(20.dp), content = content)
    }
}

fun batteryColor(pct: Int): Color = when {
    pct <= 15 -> Palette.Bad
    pct <= 30 -> Palette.Warn
    else -> Palette.Good
}

/** Circular battery gauge: the arc sweeps in, the percentage counts up. */
@Composable
fun Gauge(pct: Int?, size: Dp = 140.dp) {
    val a = MaterialTheme.aura
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = if (pct == null) a.ink3 else batteryColor(pct)
    val p = animatedProgress((pct ?: 0) / 100f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (pct != null && p > 0.002f) drawArc(color, -90f, 360f * p, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CountUpText(pct?.toDouble(), AuraType.Display, MaterialTheme.colorScheme.onSurface)
            Text("battery", fontSize = 12.sp, color = a.ink3)
        }
    }
}

/**
 * Small filled trend line; draws itself in from the left. Press or drag to pick a point: [onScrub] receives its index
 * (null when the finger lifts), so the caller can show the exact date and value.
 */
@Composable
fun Spark(
    values: List<Double>, color: Color, modifier: Modifier = Modifier, height: Dp = 56.dp, emptyText: String = "More days needed for a trend",
    onScrub: ((Int?) -> Unit)? = null,
) {
    val muted = MaterialTheme.aura.ink3
    if (values.size < 2) {
        Box(modifier.height(height), contentAlignment = Alignment.CenterStart) { Text(if (values.isEmpty()) "No data yet" else emptyText, fontSize = 12.sp, color = muted) }
        return
    }
    val reveal = animatedProgress(1f, durationMs = 1100)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var sel by remember { mutableStateOf<Int?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    fun idxAt(x: Float) = ((x / widthPx).coerceIn(0f, 1f) * (values.size - 1)).roundToInt()
    fun pick(i: Int?) { if (i != sel) { sel = i; onScrub?.invoke(i); if (i != null) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) } }
    val interactive = if (onScrub == null) Modifier else Modifier
        .pointerInput(values) { detectTapGestures(onPress = { o -> pick(idxAt(o.x)); tryAwaitRelease(); pick(null) }) }
        .pointerInput(values) { detectHorizontalDragGestures(onDragStart = { pick(idxAt(it.x)) }, onDragEnd = { pick(null) }, onDragCancel = { pick(null) }) { c, _ -> c.consume(); pick(idxAt(c.position.x)) } }
    Canvas(modifier.fillMaxWidth().height(height).onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }.then(interactive)) {
        val mn = values.min(); val mx = values.max()
        val pad = (if (mx - mn == 0.0) 1.0 else mx - mn) * 0.25
        val lo = mn - pad; val hi = mx + pad
        fun x(i: Int) = 3.dp.toPx() + i * (size.width - 6.dp.toPx()) / (values.size - 1)
        fun y(v: Double) = (size.height - 4.dp.toPx() - ((v - lo) / (hi - lo)) * (size.height - 8.dp.toPx())).toFloat()
        val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val fill = Path().apply { addPath(line); lineTo(x(values.size - 1), size.height); lineTo(x(0), size.height); close() }
        clipRect(right = size.width * reveal) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val s = sel
        if (s != null) {
            drawLine(color.copy(alpha = 0.45f), Offset(x(s), 0f), Offset(x(s), size.height), 1.dp.toPx())
            drawCircle(Color.White.copy(alpha = 0.9f), 5.dp.toPx(), Offset(x(s), y(values[s]))); drawCircle(color, 3.5.dp.toPx(), Offset(x(s), y(values[s])))
        } else if (reveal > 0.98f) drawCircle(color, 3.5.dp.toPx(), Offset(x(values.size - 1), y(values.last())))
    }
}

/** Horizontal sleep-stage bar: one segment per stage, sized by minutes, with a 2dp gap between fills. */
@Composable
fun StageBar(minutes: Map<String, Double>) {
    val order = listOf("deep" to Palette.Deep, "light" to Palette.StageLight, "rem" to Palette.Rem, "awake" to Palette.Awake)
    val grow = animatedProgress(1f)
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        order.filter { (k, _) -> (minutes[k] ?: 0.0) > 0 }.forEach { (k, c) ->
            Box(Modifier.weight((minutes.getValue(k).toFloat() * grow).coerceAtLeast(0.001f)).fillMaxHeight().background(c))
        }
    }
}

fun ago(unixSec: Long?, nowMs: Long): String {
    if (unixSec == null) return "never"
    val s = ((nowMs / 1000) - unixSec).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 172800 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}
