package com.aashish.myrings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashish.myrings.data.Analysis
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayStyle
import java.util.Locale
import kotlin.math.max

/**
 * Seven days on one panel: sleep (stacked by stage), readiness and resting heart rate, in aligned columns.
 * Press or drag across a day to read all three numbers for it at once.
 */
@Composable
fun WeekView(analysis: Analysis, endDate: String) {
    val a = MaterialTheme.aura
    val end = LocalDate.parse(endDate)
    val days = (6 downTo 0).map { end.minusDays(it.toLong()) }
    val nights = days.map { d -> analysis.nights.lastOrNull { it.date == d.toString() } }
    val sums = days.map { d -> analysis.days.firstOrNull { it.date == d.toString() } }

    val tm = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    var sel by remember { mutableStateOf<Int?>(null) }
    var touching by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    fun idxAt(x: Float) = ((x / widthPx).coerceIn(0f, 0.9999f) * 7).toInt()
    fun pick(i: Int?) { if (i != sel) { sel = i; if (i != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
    LaunchedEffect(sel, touching) { if (!touching && sel != null) { delay(3500); sel = null } }

    val sleepHours = nights.map { it?.totalSleepMin?.div(60.0) }
    val readiness = nights.map { it?.readiness?.value?.toDouble() }
    val resting = days.indices.map { sums[it]?.restingHr ?: nights[it]?.lowestHr }

    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Your week", style = AuraType.Title, modifier = Modifier.weight(1f))
            Text("${days.first().format(DateTimeFormatter.ofPattern("MMM d"))} – ${days.last().format(DateTimeFormatter.ofPattern("MMM d"))}", fontSize = 12.sp, color = a.ink3)
        }
        // the readout for the pressed day (or a hint), fixed height so the layout never jumps
        Box(Modifier.fillMaxWidth().height(52.dp).padding(top = 8.dp, bottom = 6.dp), contentAlignment = Alignment.CenterStart) {
            val s = sel
            if (s == null) Text("Press a day to see its numbers", fontSize = 12.sp, color = a.ink3)
            else {
                val n = nights[s]
                val sleep = sleepHours[s]?.let { "${it.toInt()}h ${((it % 1) * 60).toInt()}m asleep" } ?: "no sleep recorded"
                val rd = readiness[s]?.let { "readiness ${it.toInt()}" } ?: "no readiness"
                val rh = resting[s]?.let { "resting ${it.toInt()} bpm" } ?: "no resting HR"
                Column {
                    Text(days[s].format(DateTimeFormatter.ofPattern("EEEE, MMM d")), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("$sleep  ·  $rd  ·  $rh", fontSize = 11.5.sp, color = a.ink2, maxLines = 1)
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) { detectTapGestures(onPress = { o -> touching = true; pick(idxAt(o.x)); tryAwaitRelease(); touching = false }) }
                .pointerInput(Unit) { detectHorizontalDragGestures(onDragStart = { touching = true; pick(idxAt(it.x)) }, onDragEnd = { touching = false }, onDragCancel = { touching = false }) { c, _ -> c.consume(); pick(idxAt(c.position.x)) } },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WeekLabel(Glyph.Moon, "Sleep", Palette.Rem)
                if (sleepHours.none { it != null }) EmptyLine("Sleep bars appear after your first night.") else Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                    drawHighlight(sel, a.ink2)
                    val maxH = max(9.0, sleepHours.filterNotNull().maxOrNull() ?: 0.0)
                    val slotW = size.width / 7
                    val labelH = 16.dp.toPx()
                    val plotH = size.height - labelH
                    nights.forEachIndexed { i, n ->
                        val cx = (i + 0.5f) * slotW
                        val bw = slotW * 0.46f
                        val hours = sleepHours[i]
                        if (n == null || hours == null) { drawLabel(tm, "—", cx, plotH - 14.dp.toPx(), a.ink3, size.width); return@forEachIndexed }
                        val dim = if (sel == null || sel == i) 1f else 0.45f
                        val total = hours * 60
                        val stages = n.stages
                        var y = plotH
                        val parts = if (stages != null) listOf("deep" to Palette.Deep, "light" to Palette.StageLight, "rem" to Palette.Rem).map { (k, c) -> (stages[k] ?: 0.0) to c }
                                    else listOf(total to Palette.Accent)
                        val partsSum = parts.sumOf { it.first }.coerceAtLeast(1.0)
                        val barH = (hours / maxH * plotH).toFloat()
                        parts.forEachIndexed { k, (min, c) ->
                            val h = (min / partsSum * barH).toFloat()
                            if (h > 0.5f) {
                                val top = y - h
                                drawRoundRect(c.copy(alpha = dim), Offset(cx - bw / 2, top), Size(bw, h - if (k > 0) 1.5f else 0f), CornerRadius(if (k == parts.lastIndex) 5.dp.toPx() else 0f))
                                y = top
                            }
                        }
                        drawLabel(tm, "%.1f".format(hours), cx, plotH - barH - 15.dp.toPx(), a.ink2, size.width)
                    }
                    drawLine(a.hairline, Offset(0f, plotH), Offset(size.width, plotH), 1f)
                }

                WeekLabel(Glyph.Today, "Readiness", Palette.Good)
                if (readiness.none { it != null }) EmptyLine("Readiness needs a night of sleep.")
                else Canvas(Modifier.fillMaxWidth().height(84.dp)) { drawDots(tm, readiness, sel, a, valueColor = { scoreColor(it.toInt()) }, dec = 0, lo = 40.0, hi = 100.0) }

                WeekLabel(Glyph.Heart, "Resting heart rate", Palette.Bad)
                if (resting.none { it != null }) EmptyLine("Resting heart rate appears once the ring has worn data.")
                else Canvas(Modifier.fillMaxWidth().height(84.dp)) { drawDots(tm, resting, sel, a, valueColor = { Palette.Bad }, dec = 0) }

                Row(Modifier.fillMaxWidth()) {
                    days.forEachIndexed { i, d ->
                        Text(
                            d.dayOfWeek.getDisplayName(DayStyle.NARROW, Locale.getDefault()), Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp,
                            fontWeight = if (sel == i || (sel == null && i == 6)) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sel == i) MaterialTheme.colorScheme.primary else a.ink3,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("Deep" to Palette.Deep, "Light" to Palette.StageLight, "REM" to Palette.Rem).forEach { (n, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(5.dp)); Text(n, fontSize = 11.sp, color = a.ink3)
                }
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, Modifier.padding(start = 2.dp, bottom = 2.dp), fontSize = 12.sp, color = MaterialTheme.aura.ink3)
}

@Composable
private fun WeekLabel(glyph: Glyph, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AuraIcon(glyph, tint, size = 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.aura.ink2)
    }
}

private fun DrawScope.drawHighlight(sel: Int?, ink: Color) {
    if (sel == null) return
    val slotW = size.width / 7
    drawRoundRect(ink.copy(alpha = 0.08f), Offset(sel * slotW + 2.dp.toPx(), 0f), Size(slotW - 4.dp.toPx(), size.height), CornerRadius(10.dp.toPx()))
}

/** A small number centred on [cx], kept inside the canvas. */
private fun DrawScope.drawLabel(tm: TextMeasurer, text: String, cx: Float, y: Float, color: Color, width: Float) {
    val st = TextStyle(fontSize = 10.5.sp, color = color, fontWeight = FontWeight.Medium)
    val m = tm.measure(text, st)
    drawText(tm, text, Offset((cx - m.size.width / 2f).coerceIn(0f, width - m.size.width), y), st)
}

/** A seven-slot dot-and-line series with a value label over each dot; days without data leave a gap. */
private fun DrawScope.drawDots(
    tm: TextMeasurer, values: List<Double?>, sel: Int?, a: AuraColors, valueColor: (Double) -> Color, dec: Int,
    lo: Double? = null, hi: Double? = null,
) {
    drawHighlight(sel, a.ink2)
    val known = values.filterNotNull()
    val slotW = size.width / 7
    val labelH = 16.dp.toPx()
    val top = labelH; val bottom = size.height - 8.dp.toPx()
    if (known.isEmpty()) { drawLabel(tm, "No data yet", size.width / 2, size.height / 2 - 6.dp.toPx(), a.ink3, size.width); return }
    val mn = lo ?: (known.min() - 3.0); val mx = hi ?: (known.max() + 3.0)
    val span = (mx - mn).coerceAtLeast(1.0)
    fun y(v: Double) = (bottom - ((v - mn) / span) * (bottom - top)).toFloat()
    fun x(i: Int) = (i + 0.5f) * slotW
    drawLine(a.hairline, Offset(0f, bottom), Offset(size.width, bottom), 1f)
    // connect consecutive days that both have a value
    values.forEachIndexed { i, v ->
        val w = values.getOrNull(i + 1)
        if (v != null && w != null) {
            val path = Path().apply { moveTo(x(i), y(v)); lineTo(x(i + 1), y(w)) }
            drawPath(path, a.ink3.copy(alpha = 0.55f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    values.forEachIndexed { i, v ->
        if (v == null) { drawLabel(tm, "—", x(i), bottom - 16.dp.toPx(), a.ink3, size.width); return@forEachIndexed }
        val c = valueColor(v)
        val dim = if (sel == null || sel == i) 1f else 0.5f
        drawCircle(a.glassTop, 7.dp.toPx(), Offset(x(i), y(v)))
        drawCircle(c.copy(alpha = dim), 5.dp.toPx(), Offset(x(i), y(v)))
        drawLabel(tm, if (dec == 0) "%d".format(Math.round(v)) else "%.${dec}f".format(v), x(i), y(v) - 22.dp.toPx(), a.ink2.copy(alpha = dim), size.width)
    }
}
