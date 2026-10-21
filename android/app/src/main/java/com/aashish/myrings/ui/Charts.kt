package com.aashish.myrings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.withStyle
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
import androidx.compose.ui.graphics.*
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
import com.aashish.myrings.data.Intraday
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private val clockFmt = DateTimeFormatter.ofPattern("H:mm")
fun fmtClock(t: Long): String = Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault()).toLocalTime().format(clockFmt)

/** Index of the point nearest to [t], or null when the nearest reading is too far away (a gap in the data). */
private fun nearestIndex(points: List<Pair<Long, Double>>, t: Long): Int? {
    if (points.isEmpty()) return null
    var lo = 0; var hi = points.size - 1
    while (lo < hi) { val mid = (lo + hi) ushr 1; if (points[mid].first < t) lo = mid + 1 else hi = mid }
    val i = if (lo > 0 && abs(points[lo - 1].first - t) <= abs(points[lo].first - t)) lo - 1 else lo
    val typical = (points.last().first - points.first().first) / max(1, points.size - 1)
    return if (abs(points[i].first - t) <= max(typical * 3, 900)) i else null
}

private fun fmtValue(v: Double, dec: Int) = if (dec == 0) "%d".format(Math.round(v)) else "%.${dec}f".format(v)

/** What a MET value means in plain words. */
fun metWord(met: Double): String = when {
    met < 1.5 -> "Resting"
    met < 3.0 -> "Light activity"
    met < 6.0 -> "Moderate activity"
    else -> "Vigorous activity"
}

@Composable
private fun MiniStat(label: String, value: String, sub: String?) {
    val a = MaterialTheme.aura
    Column {
        Text(label, fontSize = 11.sp, color = a.ink3)
        Text(value, style = AuraType.Title.copy(fontSize = 16.sp))
        if (sub != null) Text(sub, fontSize = 11.sp, color = a.ink3)
    }
}

/**
 * A day chart you can touch: press or drag to see the exact value and time, with a crosshair, a dot on the line and a
 * haptic tick. [cursor]/[onCursor] let several charts share one time cursor, so a heart-rate spike can be lined up with
 * the movement that caused it. High and low are labelled, and a dashed line marks the average.
 */
@Composable
fun TimeChart(
    title: String, points: List<Pair<Long, Double>>, color: Color, unit: String, dec: Int = 0, bars: Boolean = false,
    marks: List<Long> = emptyList(), caption: String? = null,
    cursor: Long? = null, onCursor: (Long?) -> Unit = {}, describe: ((Double) -> String?)? = null,
    /** Time ranges (unix seconds) to shade, e.g. the hours asleep. */
    bands: List<Pair<Long, Long>> = emptyList(), bandLabel: String = "Asleep",
    /** Another day's points, already shifted onto this day's clock; shown only when the user turns the chip on. */
    compare: List<Pair<Long, Double>> = emptyList(), compareLabel: String = "Yesterday",
) {
    val a = MaterialTheme.aura
    Panel(Modifier.fillMaxWidth()) {
        var showCompare by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 12.sp, color = a.ink2)
            if (compare.size >= 2) CompareChip(showCompare, "vs ${compareLabel.lowercase()}") { showCompare = it }
        }
        if (points.size < 2) {
            Text("Not enough data for this day", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = a.ink3)
            return@Panel
        }
        val minP = points.minBy { it.second }; val maxP = points.maxBy { it.second }
        val avg = points.map { it.second }.average()
        val asleepLow = bands.firstOrNull()?.let { (bs, be) -> points.filter { it.first in bs..be }.minByOrNull { it.second } }
        val cmp = if (showCompare) compare else emptyList()
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (asleepLow != null) MiniStat("Asleep low", "${fmtValue(asleepLow.second, dec)} $unit", fmtClock(asleepLow.first))
            else MiniStat("Low", "${fmtValue(minP.second, dec)} $unit", fmtClock(minP.first))
            MiniStat("Average", "${fmtValue(avg, dec)} $unit", null)
            MiniStat("High", "${fmtValue(maxP.second, dec)} $unit", fmtClock(maxP.first))
        }

        val tm = rememberTextMeasurer()
        val haptic = LocalHapticFeedback.current
        var touching by remember { mutableStateOf(false) }
        var widthPx by remember { mutableFloatStateOf(1f) }
        val t0 = points.first().first; val t1 = points.last().first
        fun timeAt(x: Float) = t0 + ((x / widthPx).coerceIn(0f, 1f) * (t1 - t0)).toLong()

        val nearest = cursor?.let { nearestIndex(points, it) }
        LaunchedEffect(nearest) { if (nearest != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        LaunchedEffect(cursor, touching) { if (!touching && cursor != null) { delay(2800); onCursor(null) } }

        val ink2 = a.ink2; val ink3 = a.ink3; val hair = a.hairline; val surface = a.glassTop; val warn = Palette.Warn
        Canvas(
            Modifier.fillMaxWidth().height(190.dp).onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(points) {
                    detectTapGestures(onPress = { o -> touching = true; onCursor(timeAt(o.x)); tryAwaitRelease(); touching = false })
                }
                .pointerInput(points) {
                    detectHorizontalDragGestures(
                        onDragStart = { touching = true; onCursor(timeAt(it.x)) },
                        onDragEnd = { touching = false }, onDragCancel = { touching = false },
                    ) { change, _ -> change.consume(); onCursor(timeAt(change.position.x)) }
                },
        ) {
            val top = 34.dp.toPx(); val bottom = 22.dp.toPx()
            val plotH = size.height - top - bottom
            var lo = minOf(minP.second, cmp.minOfOrNull { it.second } ?: Double.MAX_VALUE)
            val hi0 = maxOf(maxP.second, cmp.maxOfOrNull { it.second } ?: -Double.MAX_VALUE)
            if (bars) lo = 0.0
            val pad = (hi0 - lo).coerceAtLeast(1.0) * 0.14
            val hi = hi0 + pad; val low = if (bars) 0.0 else lo - pad
            fun x(t: Long) = ((t - t0).toFloat() / (t1 - t0).coerceAtLeast(1)) * size.width
            fun y(v: Double) = top + ((1 - (v - low) / (hi - low)) * plotH).toFloat()
            val small = TextStyle(fontSize = 10.sp, color = ink3)
            bands.forEach { (bs, be) ->
                val sx = x(bs.coerceIn(t0, t1)); val ex = x(be.coerceIn(t0, t1))
                if (ex > sx) {
                    drawRect(Palette.Rem.copy(alpha = 0.12f), Offset(sx, top), Size(ex - sx, plotH))
                    drawText(tm, bandLabel, Offset(sx + 6.dp.toPx(), top + 2.dp.toPx()), TextStyle(fontSize = 10.sp, color = Palette.Rem))
                }
            }

            // gridlines with value labels
            for (f in listOf(0f, 0.5f, 1f)) {
                val gy = top + plotH * f
                drawLine(hair, Offset(0f, gy), Offset(size.width, gy), 1f)
                val v = hi - (hi - low) * f
                drawText(tm, fmtValue(v, dec), Offset(2.dp.toPx(), gy - 13.dp.toPx()), small)
            }
            // time axis
            drawTimeAxis(tm, t0, t1, top + plotH, small, hair)
            // move reminders
            marks.filter { it in t0..t1 }.forEach { drawLine(warn, Offset(x(it), top), Offset(x(it), top + plotH), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))) }
            // average
            if (!bars) {
                val ay = y(avg)
                drawLine(ink3.copy(alpha = 0.6f), Offset(0f, ay), Offset(size.width, ay), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            }

            if (cmp.isNotEmpty() && !bars) {
                val inRange = cmp.filter { it.first in t0..t1 }
                val segsC = ArrayList<MutableList<Pair<Long, Double>>>()
                inRange.forEachIndexed { i, p -> if (i == 0 || p.first - inRange[i - 1].first > 1200) segsC += mutableListOf(p) else segsC.last() += p }
                segsC.filter { it.size >= 2 }.forEach { seg ->
                    val path = Path().apply { seg.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) } }
                    drawPath(path, ink2.copy(alpha = 0.55f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f))))
                }
            }
            if (bars) {
                val w = (size.width / ((t1 - t0) / 60f).coerceAtLeast(1f) * 0.8f).coerceAtLeast(1.5f)
                points.forEachIndexed { i, (t, v) ->
                    val c = if (nearest == null || i == nearest) color else color.copy(alpha = 0.55f)
                    drawRect(c, Offset(x(t) - w / 2, y(v)), Size(w, top + plotH - y(v)))
                }
            } else {
                val segs = ArrayList<MutableList<Pair<Long, Double>>>()
                points.forEachIndexed { i, p -> if (i == 0 || p.first - points[i - 1].first > 1200) segs += mutableListOf(p) else segs.last() += p }
                segs.forEach { seg ->
                    if (seg.size < 2) { drawCircle(color, 2.5.dp.toPx(), Offset(x(seg[0].first), y(seg[0].second))); return@forEach }
                    val path = Path().apply { seg.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) } }
                    val fill = Path().apply { addPath(path); lineTo(x(seg.last().first), top + plotH); lineTo(x(seg.first().first), top + plotH); close() }
                    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)), startY = top, endY = top + plotH))
                    drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                // high / low markers
                for ((p, above) in listOf(maxP to true, minP to false)) {
                    val px = x(p.first); val py = y(p.second)
                    drawCircle(surface, 5.dp.toPx(), Offset(px, py)); drawCircle(color, 3.5.dp.toPx(), Offset(px, py))
                    val label = fmtValue(p.second, dec)
                    val m = tm.measure(label, TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = ink2))
                    val lx = (px - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
                    drawText(tm, label, Offset(lx, if (above) py - m.size.height - 6.dp.toPx() else py + 6.dp.toPx()), TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = ink2))
                }
            }

            // cursor: crosshair, dot and bubble
            if (cursor != null && cursor in t0..t1) {
                val cx = x(cursor)
                drawLine(ink2.copy(alpha = 0.55f), Offset(cx, top - 6.dp.toPx()), Offset(cx, top + plotH), 1.dp.toPx())
                val p = nearest?.let { points[it] }
                if (p != null && !bars) {
                    drawCircle(surface, 7.dp.toPx(), Offset(x(p.first), y(p.second)))
                    drawCircle(color, 5.dp.toPx(), Offset(x(p.first), y(p.second)))
                }
                val line1 = if (p != null) "${fmtValue(p.second, dec)} $unit" else "No reading"
                val asleepNow = bands.any { cursor in it.first..it.second }
                val line2 = (if (p != null) fmtClock(p.first) else fmtClock(cursor)) +
                    (p?.let { describe?.invoke(it.second) }?.let { "  ·  $it" } ?: "") + (if (asleepNow) "  ·  ${bandLabel.lowercase()}" else "")
                val line3 = cmp.takeIf { it.isNotEmpty() }?.let { nearestIndex(it, cursor) }?.let { "$compareLabel ${fmtValue(cmp[it].second, dec)} $unit" }
                drawBubble(tm, line1, line2, cx, color, surface, hair, ink3, line3)
            }
        }
        if (showCompare) {
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append("━━ ") }
                    append("Today     ")
                    withStyle(androidx.compose.ui.text.SpanStyle(color = a.ink2, fontWeight = FontWeight.Bold)) { append("┅┅ ") }
                    append(compareLabel)
                },
                Modifier.padding(top = 4.dp), fontSize = 11.sp, color = a.ink3,
            )
        }
        caption?.let { Text(it, Modifier.padding(top = 4.dp), fontSize = 11.sp, color = a.ink3) }
    }
}

/** A small on/off pill for optional overlays. */
@Composable
private fun CompareChip(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val a = MaterialTheme.aura
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(if (on) primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .pressScale({ onChange(!on) }).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) { AuraIcon(Glyph.Check, primary, size = 13.dp, strokeWidth = 2.2f); Spacer(Modifier.width(5.dp)) }
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (on) primary else a.ink2)
    }
}

/** Hourly (or finer) time labels along the bottom, aligned to local clock time. */
private fun DrawScope.drawTimeAxis(tm: TextMeasurer, t0: Long, t1: Long, baseY: Float, style: TextStyle, hair: Color) {
    val span = (t1 - t0).coerceAtLeast(1)
    val pxPerS = size.width / span
    val step = listOf(900L, 1800L, 3600L, 7200L, 10800L, 21600L, 43200L).firstOrNull { it * pxPerS >= 64.dp.toPx() } ?: 86400L
    val off = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochSecond(t0)).totalSeconds
    var tick = ceil((t0 + off).toDouble() / step).toLong() * step - off
    while (tick <= t1) {
        val tx = (tick - t0) * pxPerS
        val label = fmtClock(tick)
        val m = tm.measure(label, style)
        if (tx - m.size.width / 2f >= 0f && tx + m.size.width / 2f <= size.width) {
            drawLine(hair, Offset(tx, baseY), Offset(tx, baseY + 4.dp.toPx()), 1f)
            drawText(tm, label, Offset(tx - m.size.width / 2f, baseY + 5.dp.toPx()), style)
        }
        tick += step
    }
}

/** The floating readout above the cursor: value on top, time (and meaning) below. */
private fun DrawScope.drawBubble(tm: TextMeasurer, line1: String, line2: String, cx: Float, accent: Color, surface: Color, hair: Color, ink3: Color, line3: String? = null) {
    val s1 = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.95f).takeIf { surface.luminance() < 0.5f } ?: Color(0xFF16162D))
    val s2 = TextStyle(fontSize = 10.5.sp, color = ink3)
    val m1 = tm.measure(line1, s1); val m2 = tm.measure(line2, s2)
    val m3 = line3?.let { tm.measure(it, s2) }
    val padX = 10.dp.toPx(); val padY = 5.dp.toPx()
    val w = max(max(m1.size.width, m2.size.width), m3?.size?.width ?: 0) + padX * 2
    val h = m1.size.height + m2.size.height + (m3?.size?.height ?: 0) + padY * 2
    val bx = (cx - w / 2f).coerceIn(0f, size.width - w)
    val by = 0f
    val bg = if (surface.luminance() < 0.5f) Color(0xFF262B58) else Color.White
    drawRoundRect(bg, Offset(bx, by), Size(w, h), CornerRadius(12.dp.toPx()))
    drawRoundRect(hair, Offset(bx, by), Size(w, h), CornerRadius(12.dp.toPx()), style = Stroke(1f))
    drawText(tm, line1, Offset(bx + padX, by + padY), s1)
    drawText(tm, line2, Offset(bx + padX, by + padY + m1.size.height), s2)
    if (line3 != null && m3 != null) drawText(tm, line3, Offset(bx + padX, by + padY + m1.size.height + m2.size.height), s2)
}

// ---------------------------------------------------------------- highlights

/** Plain-language findings for a day, with the heart-rate and activity charts cross-referenced. */
@Composable
fun DayHighlights(intra: Intraday) {
    val a = MaterialTheme.aura
    val items = ArrayList<Triple<Glyph, Color, String>>()
    val hr = intra.hr; val met = intra.met
    fun metAt(t: Long): Double? = met.minByOrNull { abs(it.first - t) }?.takeIf { abs(it.first - t) <= 300 }?.second

    if (hr.isNotEmpty()) {
        val peak = hr.maxBy { it.second }; val low = hr.minBy { it.second }
        val m = metAt(peak.first)
        items += Triple(Glyph.Heart, Palette.Bad, "Highest heart rate ${peak.second.roundToInt()} bpm at ${fmtClock(peak.first)}" + (m?.let { " · ${metWord(it).lowercase()} (${"%.1f".format(it)} MET)" } ?: ""))
        if (low.first != peak.first) items += Triple(Glyph.Heart, Palette.Good, "Lowest heart rate ${low.second.roundToInt()} bpm at ${fmtClock(low.first)}")
    }
    if (met.size >= 5) {
        // most active 15 minutes
        val window = 15
        var best = -1.0; var bestT = 0L
        for (i in 0..met.size - window) {
            val s = met.subList(i, i + window).sumOf { it.second }
            if (s > best) { best = s; bestT = met[i].first }
        }
        if (best > 0) items += Triple(Glyph.Flame, Palette.Ember, "Most active stretch: 15 min from ${fmtClock(bestT)} (average ${"%.1f".format(best / window)} MET)")
        // longest still stretch (MET < 1.5 for consecutive minutes)
        var run = 0; var runStart = 0L; var bestRun = 0; var bestStart = 0L
        met.forEachIndexed { i, (t, v) ->
            val consecutive = i > 0 && t - met[i - 1].first <= 90
            if (v < 1.5) { if (run == 0 || !consecutive) { run = 1; runStart = t } else run++ } else run = 0
            if (run > bestRun) { bestRun = run; bestStart = runStart }
        }
        if (bestRun >= 20) items += Triple(Glyph.Moon, Palette.Accent, "Longest still stretch: ${if (bestRun >= 60) "${bestRun / 60}h ${bestRun % 60}m" else "${bestRun}m"} from ${fmtClock(bestStart)}")
    }
    if (intra.alerts.isNotEmpty()) items += Triple(Glyph.Alert, Palette.Warn, "${intra.alerts.size} move reminder${if (intra.alerts.size > 1) "s" else ""}, latest at ${fmtClock(intra.alerts.max())}")
    if (items.isEmpty()) return
    Panel(Modifier.fillMaxWidth()) {
        Text("Highlights", style = AuraType.Title)
        Spacer(Modifier.height(10.dp))
        items.forEach { (g, c, t) ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                AuraIcon(g, c, Modifier.padding(top = 2.dp), size = 17.dp)
                Spacer(Modifier.width(12.dp))
                Text(t, style = AuraType.Body, color = a.ink2)
            }
        }
    }
}

// ---------------------------------------------------------------- hypnogram

/** Classic hypnogram (awake on top, deep at the bottom). Drag across it to read the stage at any minute of the night. */
@Composable
fun Hypnogram(epochs: List<String>, start: Long, end: Long) {
    val rows = listOf("awake" to Palette.Awake, "rem" to Palette.Rem, "light" to Palette.StageLight, "deep" to Palette.Deep)
    val a = MaterialTheme.aura
    val label = a.ink3
    val tm = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    var sel by remember { mutableStateOf<Int?>(null) }
    var touching by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    fun idxAt(x: Float) = ((x / widthPx).coerceIn(0f, 0.9999f) * epochs.size).toInt()
    LaunchedEffect(sel) { if (sel != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    LaunchedEffect(sel, touching) { if (!touching && sel != null) { delay(2800); sel = null } }
    val pad = 26.dp

    Row(Modifier.fillMaxWidth().height(110.dp)) {
        Column(Modifier.fillMaxHeight().padding(top = pad, end = 6.dp), verticalArrangement = Arrangement.SpaceAround) {
            listOf("Awake", "REM", "Light", "Deep").forEach { Text(it, fontSize = 10.sp, color = label) }
        }
        Canvas(
            Modifier.weight(1f).fillMaxHeight().onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(epochs) { detectTapGestures(onPress = { o -> touching = true; sel = idxAt(o.x); tryAwaitRelease(); touching = false }) }
                .pointerInput(epochs) {
                    detectHorizontalDragGestures(onDragStart = { touching = true; sel = idxAt(it.x) }, onDragEnd = { touching = false }, onDragCancel = { touching = false }) { c, _ -> c.consume(); sel = idxAt(c.position.x) }
                },
        ) {
            val top = pad.toPx()
            val rowH = (size.height - top) / rows.size
            val w = size.width / epochs.size
            var i = 0
            while (i < epochs.size) {
                var j = i
                while (j < epochs.size && epochs[j] == epochs[i]) j++
                val r = rows.indexOfFirst { it.first == epochs[i] }.coerceAtLeast(0)
                val on = sel == null || sel in i until j
                drawRoundRect(rows[r].second.copy(alpha = if (on) 1f else 0.5f), Offset(i * w, top + r * rowH + rowH * 0.15f), Size((j - i) * w, rowH * 0.7f), CornerRadius(3.dp.toPx()))
                i = j
            }
            sel?.let { s ->
                val cx = (s + 0.5f) * w
                drawLine(a.ink2.copy(alpha = 0.55f), Offset(cx, top - 6.dp.toPx()), Offset(cx, size.height), 1.dp.toPx())
                val stage = epochs[s]
                val t = start + s * 30L
                val color = rows.first { it.first == stage }.second
                drawBubble(tm, stage.replaceFirstChar(Char::uppercase), fmtClock(t) + "  ·  ${((t - start) / 60)} min in", cx, color, a.glassTop, a.hairline, a.ink3)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 40.dp)) {
        Text(fmtClock(start), fontSize = 10.5.sp, color = label, modifier = Modifier.weight(1f))
        Text(fmtClock(end), fontSize = 10.5.sp, color = label)
    }
}
