package com.aashish.myrings.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashish.myrings.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

fun scoreColor(v: Int?): Color = when {
    v == null -> Color.Gray
    v >= 85 -> Palette.Good
    v >= 70 -> Palette.Accent
    v >= 60 -> Palette.Warn
    else -> Palette.Bad
}

private val zoneColors = mapOf(
    Scores.Zone.STRESSED to Palette.Bad, Scores.Zone.ENGAGED to Palette.Warn,
    Scores.Zone.RELAXED to Palette.Light, Scores.Zone.RESTORED to Palette.Good,
)

fun dayLabel(date: String, latest: String): String {
    val d = LocalDate.parse(date)
    return when (date) {
        latest -> if (d == LocalDate.now()) "Today" else d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        LocalDate.now().minusDays(1).toString() -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
}

/** Previous / next day picker for the dashboard. */
@Composable
fun DayPicker(dates: List<String>, selected: String, onSelect: (String) -> Unit) {
    val i = dates.indexOf(selected)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onSelect(dates[i - 1]) }, enabled = i > 0) { Text("‹", fontSize = 22.sp) }
        Text(dayLabel(selected, dates.last()), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { onSelect(dates[i + 1]) }, enabled = i < dates.size - 1) { Text("›", fontSize = 22.sp) }
    }
}

/** A circular 0–100 score, like the three rings on Oura's home screen. */
@Composable
fun ScoreRing(title: String, value: Int?, caption: String, color: Color, size: Dp = 92.dp, onClick: () -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = 8.dp.toPx(); val arc = Size(this.size.width - s, this.size.height - s)
                drawArc(track, 0f, 360f, false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
                if (value != null) drawArc(color, -90f, 3.6f * value, false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
            }
            Text(value?.toString() ?: "—", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(caption, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The home row: Readiness, Sleep and Stress for the selected day. Tapping one opens its detail card. */
@Composable
fun ScoreRow(night: Night?, stress: StressDay?, onOpen: (String) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val rd = night?.readiness; val sl = night?.sleepScore
            ScoreRing("Readiness", rd?.value, rd?.label ?: "Needs a night", scoreColor(rd?.value)) { onOpen("readiness") }
            ScoreRing("Sleep", sl?.value, sl?.label ?: "Needs a night", scoreColor(sl?.value)) { onOpen("sleep") }
            val sc = when (stress?.summary) { "Restored" -> 90; "Normal" -> 70; "Stressful" -> 45; else -> null }
            ScoreRing("Stress", null, stress?.summary ?: "Needs daytime data", if (sc == null) Color.Gray else scoreColor(sc)) { onOpen("stress") }
        }
        if (night?.readiness?.provisional == true || night == null) {
            Text(
                night?.readiness?.note ?: "Readiness and Sleep appear after the ring records a night's sleep (wear it to bed, then open the app).",
                fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ContributorRow(c: Contributor) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, Modifier.weight(1f), fontSize = 13.5.sp)
            Text(c.value?.toString() ?: "—", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = scoreColor(c.value))
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (c.value != null) Box(Modifier.fillMaxWidth(c.value / 100f).fillMaxHeight().background(scoreColor(c.value)))
        }
        Text(c.detail, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ScoreCard(title: String, score: Score?, empty: String, extra: @Composable ColumnScope.() -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(score?.value?.toString() ?: "—", style = AuraType.Display, color = scoreColor(score?.value))
                    Text("  ${score?.label ?: ""}", modifier = Modifier.padding(bottom = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (score != null) TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Contributors") }
        }
        if (score == null) Text(empty, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extra()
        if (open && score != null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
            score.contributors.forEach { ContributorRow(it) }
            score.note?.let { Text(it, fontSize = 11.5.sp, color = Palette.Warn, modifier = Modifier.padding(top = 4.dp)) }
            Text("Weights are Oura's (recovered by open_oura); the curves are estimates, so numbers can differ from the Oura app by a few points.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun hm(min: Double) = "${(min / 60).toInt()}h ${(min % 60).roundToInt()}m"
private fun clock(t: Long) = Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("H:mm"))

@Composable
fun SleepDetails(n: Night) {
    Spacer(Modifier.height(6.dp))
    Text("${clock(n.start)} – ${clock(n.end)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    n.hypnogram?.let { Spacer(Modifier.height(8.dp)); Hypnogram(it, n.start, n.end) }
    when (n.stagesSource) {
        "estimated" -> Text("Stages estimated by My Rings from heart rate, HRV and movement", fontSize = 11.sp, color = Palette.Warn)
        null -> Text("No stages: not enough heart data this night (movement-only estimate)", fontSize = 11.sp, color = Palette.Warn)
    }
    n.stages?.let { Spacer(Modifier.height(8.dp)); StageBar(it); Spacer(Modifier.height(6.dp))
        Text(listOf("deep", "light", "rem", "awake").filter { k -> it.containsKey(k) }.joinToString("  ·  ") { k -> "${k.replaceFirstChar(Char::uppercase)} ${it.getValue(k).toInt()}m" },
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.height(6.dp))
    Stat("Total sleep", hm(n.totalSleepMin)); Stat("Time in bed", hm(n.inBedMin)); Stat("Efficiency", "${n.efficiency.roundToInt()}%")
    n.latencyMin?.let { Stat("Fell asleep in", "${it.roundToInt()} min") }
    n.lowestHr?.let { Stat("Lowest heart rate", "${it.roundToInt()} bpm") }
    n.hrv?.let { Stat("Average HRV", "${it.roundToInt()} ms") }
    n.tempDeviation?.let { Stat("Body temperature", "%+.1f °C".format(it)) }
    n.spo2?.let { Stat("Average blood oxygen", "%.0f%%".format(it)) }
    n.breathRate?.let { Stat("Breathing rate", "%.1f /min".format(it)) }
}

@Composable
fun StressCard(s: StressDay?) {
    Panel(Modifier.fillMaxWidth()) {
        Text("Daytime stress", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(s?.summary ?: "No daytime data", style = AuraType.Display.copy(fontSize = 28.sp))
        if (s == null || s.samples.isEmpty()) {
            Text("Measured while you're awake and still: wear the ring during the day with health tracking on.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Panel
        }
        Spacer(Modifier.height(8.dp))
        ZoneStrip(s.samples)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Stressed" to (s.stressedMin to Palette.Bad), "Engaged" to (s.engagedMin to Palette.Warn),
                "Relaxed" to (s.relaxedMin to Palette.Light), "Restored" to (s.restoredMin to Palette.Good)).forEach { (k, v) ->
                Column { Text(k, fontSize = 11.sp, color = v.second); Text(hm(v.first.toDouble()), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        Text("Each 5-minute stretch compares your heart rate variability with your daytime baseline (%.0f ms, neutral zone ±%.0f ms). Lower HRV than usual reads as stress; higher as recovery. Active periods are skipped."
            .format(s.dhrvBaseline ?: 0.0, s.neutralHalfMs ?: 0.0), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** One colored tick per 5-minute stress sample, placed by time of day. */
@Composable
private fun ZoneStrip(samples: List<Scores.StressSample>) {
    val t0 = samples.first().t; val t1 = samples.last().t + 300
    Canvas(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val span = (t1 - t0).coerceAtLeast(1).toFloat()
        samples.forEach { s ->
            val x = (s.t - t0) / span * size.width
            drawRect(zoneColors.getValue(s.zone), Offset(x, 0f), Size((300f / span * size.width).coerceAtLeast(2f), size.height))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(clock(t0), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(clock(t1), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ActivityCard(d: DaySummary) {
    Panel(Modifier.fillMaxWidth()) {
        Text("Activity", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(d.activeKcal?.toString() ?: "—", style = AuraType.Display)
            Text("  active kcal (est.)", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
        }
        val z = d.activeMin; val tot = (z.values.sum()).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            listOf("low" to Palette.Light, "medium" to Palette.Accent, "high" to Palette.Bad).forEach { (k, c) ->
                val v = z[k] ?: 0
                if (v > 0) Box(Modifier.weight(v.toFloat() / tot).fillMaxHeight().background(c))
            }
        }
        Spacer(Modifier.height(6.dp))
        Stat("Low / medium / high activity", "${z["low"] ?: 0} / ${z["medium"] ?: 0} / ${z["high"] ?: 0} min")
        d.totalKcal?.let { Stat("Total burn (resting + active)", "$it kcal") }
        Stat("Inactive time", hm(d.inactiveMin.toDouble()))
        Stat("Move reminders", "${d.moveAlerts}")
        Stat("Time wearing the ring", hm(d.wornMin.toDouble()))
        d.avgHr?.let { Stat("Average heart rate (awake)", "${it.roundToInt()} bpm") }
        Text("Calories use the ring's per-minute MET and your profile (Ring tab → Your body). Steps need the ring's real-steps feature, which isn't reporting yet.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}
