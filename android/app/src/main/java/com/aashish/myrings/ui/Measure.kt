package com.aashish.myrings.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

/** Where a live measurement is: connecting, waking the sensor, looking for a pulse, or locked on. */
enum class MeasureStage(val text: String) {
    CONNECTING("Connecting to your ring…"),
    WAKING("Waking the heart-rate sensor…"),
    FINDING("Finding your pulse… hold still"),
    LOCKED("Reading your pulse"),
}

/** State of a live heart-rate measurement. [beats] are the bpm values seen so far, newest last. */
data class MeasureState(
    val open: Boolean = false,
    val running: Boolean = false,
    val stage: MeasureStage = MeasureStage.CONNECTING,
    val bpm: Int? = null,
    val hrv: Int? = null,
    val beats: List<Int> = emptyList(),
    /** Incremented on every real beat; the animation keys off it so the heart moves with your actual pulse. */
    val beatCount: Int = 0,
    val secondsLeft: Int = 0,
    val totalSeconds: Int = 45,
    val message: String? = null,
    /** From the ring's accelerometer once a pulse is locked; null until then (or if the ring doesn't stream it). */
    val motion: com.aashish.myrings.data.MotionLevel? = null,
    /** Beats left out of the reading because the hand was moving. */
    val discarded: Int = 0,
)

/** "Measure now": the heart beats (lub-dub) and sends out a ripple on every real beat the ring reports. */
@Composable
fun MeasureDialog(state: MeasureState, onStop: () -> Unit, onClose: () -> Unit, onAgain: () -> Unit) {
    if (!state.open) return
    val a = MaterialTheme.aura
    Dialog(onDismissRequest = { if (!state.running) onClose() }) {
        Column(
            Modifier.clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Heart rate", fontSize = 13.sp, color = a.ink2)
            Spacer(Modifier.height(10.dp))
            PulseStage(state)
            Spacer(Modifier.height(10.dp))

            // two readouts: bpm is in the stage above; HRV appears once there are enough beats
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Readout("Heart rate", state.bpm?.toString() ?: "—", "bpm")
                Readout("HRV", state.hrv?.toString() ?: "—", "ms")
            }
            if (state.running && state.motion != null) {
                Spacer(Modifier.height(10.dp))
                MotionChip(state.motion, state.discarded)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth()) { Spark(state.beats.map { it.toDouble() }, Palette.Bad, height = 46.dp, emptyText = "") }
            Spacer(Modifier.height(8.dp))

            AnimatedContent(
                targetState = state.message ?: if (state.running) state.stage.text else "",
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "stageText",
            ) { text ->
                Text(text, fontSize = 13.sp, color = a.ink2, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
            }
            if (state.running && state.stage == MeasureStage.LOCKED) {
                Text("${state.secondsLeft} s left · stay still for the best reading", fontSize = 11.5.sp, color = a.ink3, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.running) AuraButton("Stop", Glyph.Check, filled = false, modifier = Modifier.weight(1f), onClick = onStop)
                else {
                    AuraButton("Again", Glyph.Refresh, filled = false, modifier = Modifier.weight(1f), onClick = onAgain)
                    AuraButton("Done", Glyph.Check, modifier = Modifier.weight(1f), onClick = onClose)
                }
            }
        }
    }
}

/** "Still" in green, "Slight movement" in amber, "Hold still" in red: the live answer to "is my movement spoiling this?". */
@Composable
private fun MotionChip(level: com.aashish.myrings.data.MotionLevel, discarded: Int) {
    val (color, text) = when (level) {
        com.aashish.myrings.data.MotionLevel.STILL -> Palette.Good to "Still: good reading"
        com.aashish.myrings.data.MotionLevel.SLIGHT -> Palette.Warn to "Slight movement"
        com.aashish.myrings.data.MotionLevel.MOVING -> Palette.Bad to "Moving: hold still"
    }
    val tint by animateColorAsState(color, tween(250), label = "motionTint")
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(tint.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint))
        Spacer(Modifier.width(8.dp))
        Text(text + if (discarded > 0 && level == com.aashish.myrings.data.MotionLevel.MOVING) " ($discarded beats skipped)" else "", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}

@Composable
private fun Readout(label: String, value: String, unit: String) {
    val a = MaterialTheme.aura
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.5.sp, color = a.ink3)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = AuraType.Display.copy(fontSize = 34.sp, lineHeight = 38.sp))
            Text(" $unit", fontSize = 12.sp, color = a.ink3, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

/**
 * The animated heart. While searching it shows a scanning arc and slow breathing glow, so the wait feels like progress; once beats
 * arrive, every real beat triggers a lub-dub pulse of the heart and a ripple that spreads out and fades. A thin ring shows time left.
 */
@Composable
private fun PulseStage(state: MeasureState) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val locked = state.stage == MeasureStage.LOCKED
    val scale = rememberMotionScale()

    // lub-dub: a quick strong swell, a settle, a smaller second swell, a settle
    val heart = remember { Animatable(1f) }
    LaunchedEffect(state.beatCount) {
        if (state.beatCount > 0 && scale > 0f) {
            heart.animateTo(1.22f, tween(70, easing = FastOutSlowInEasing))
            heart.animateTo(1.0f, tween(110))
            heart.animateTo(1.12f, tween(70))
            heart.animateTo(1.0f, tween(160))
        }
    }

    // ripples: one per real beat, drawn from the time it started
    val ripples = remember { mutableStateListOf<Long>() }
    var nowNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.beatCount) { if (state.beatCount > 0) ripples.add(System.nanoTime()) }
    LaunchedEffect(state.running) {
        while (state.running) { withFrameNanos { nowNs = System.nanoTime() } }
        ripples.clear()
    }

    val spin by rememberInfiniteTransition(label = "scan").animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "scanSpin")
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breatheV")
    val progress = if (state.totalSeconds > 0) 1f - state.secondsLeft.toFloat() / state.totalSeconds else 0f
    val red = Palette.Bad

    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val coreR = 58.dp.toPx()
            // ripples travel from the heart out to the edge
            ripples.removeAll { (nowNs - it) / 1e6 > 1300 }
            ripples.forEach { start ->
                val t = ((nowNs - start) / 1e6 / 1300.0).toFloat().coerceIn(0f, 1f)
                val r = coreR + (size.minDimension / 2f - coreR - 8.dp.toPx()) * FastOutSlowInEasing.transform(t)
                drawCircle(red.copy(alpha = 0.40f * (1f - t)), r, c, style = Stroke(2.5.dp.toPx() * (1f - t * 0.6f)))
            }
            // time-left ring
            val ringR = size.minDimension / 2f - 4.dp.toPx()
            drawCircle(red.copy(alpha = 0.10f), ringR, c, style = Stroke(3.dp.toPx()))
            if (locked) drawArc(red.copy(alpha = 0.7f), -90f, 360f * progress, false, Offset(c.x - ringR, c.y - ringR), Size(ringR * 2, ringR * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            // searching: a comet arc sweeps around the heart
            if (!locked) rotate(spin, c) {
                drawArc(
                    Brush.sweepGradient(0f to red.copy(alpha = 0f), 0.75f to red.copy(alpha = 0.85f), 1f to red.copy(alpha = 0f), center = c),
                    0f, 270f, false, Offset(c.x - ringR + 10.dp.toPx(), c.y - ringR + 10.dp.toPx()),
                    Size((ringR - 10.dp.toPx()) * 2, (ringR - 10.dp.toPx()) * 2), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            // soft glow behind the heart
            val glow = if (locked) 0.22f else 0.10f + 0.10f * breathe
            drawCircle(Brush.radialGradient(listOf(red.copy(alpha = glow), red.copy(alpha = 0f)), c, coreR * 1.7f), coreR * 1.7f, c)
            drawCircle(red.copy(alpha = if (locked) 0.18f else 0.12f), coreR, c)
        }
        Box(Modifier.scale(if (locked) heart.value else 1f + 0.05f * breathe), contentAlignment = Alignment.Center) {
            AuraIcon(Glyph.Heart, red, size = 46.dp, strokeWidth = 2.2f)
        }
    }
}
