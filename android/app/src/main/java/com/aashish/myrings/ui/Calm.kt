package com.aashish.myrings.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aashish.myrings.data.CalmResult
import com.aashish.myrings.data.CalmSession
import com.aashish.myrings.data.MotionLevel

/** State of a guided slow-breathing session. [lockedAtMs] is when the first beat locked; the breathing starts from there. */
data class CalmState(
    val open: Boolean = false,
    val running: Boolean = false,
    val stage: MeasureStage = MeasureStage.CONNECTING,
    val lockedAtMs: Long = 0L,
    val bpm: Int? = null,
    val hrv: Int? = null,
    val hrvTrend: List<Int> = emptyList(),
    val secondsLeft: Int = CalmSession.SESSION_S,
    val motion: MotionLevel? = null,
    val result: CalmResult? = null,
    val message: String? = null,
)

/** A breathing orb that swells as you breathe in and shrinks as you breathe out, with your live heart rate and HRV beside it. */
@Composable
fun CalmDialog(state: CalmState, onStop: () -> Unit, onClose: () -> Unit, onAgain: () -> Unit) {
    if (!state.open) return
    val a = MaterialTheme.aura
    val breath = remember { Animatable(0f) }
    LaunchedEffect(state.lockedAtMs, state.running) {
        // one smooth in/out cycle per pace, started at the first locked beat; sine easing so it feels like a real breath
        if (state.running && state.lockedAtMs > 0) {
            val sine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
            breath.snapTo(0f)
            while (true) {
                breath.animateTo(1f, tween((CalmSession.BREATH_IN_S * 1000).toInt(), easing = sine))
                breath.animateTo(0f, tween((CalmSession.BREATH_OUT_S * 1000).toInt(), easing = sine))
            }
        } else breath.snapTo(0f)
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running) { while (state.running) { kotlinx.coroutines.delay(200); nowMs = System.currentTimeMillis() } }
    val sinceLock = if (state.lockedAtMs > 0) (nowMs - state.lockedAtMs) / 1000.0 else 0.0
    val accent = Palette.Accent

    Dialog(onDismissRequest = { if (!state.running) onClose() }) {
        Column(
            Modifier.clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Calm breathing", fontSize = 13.sp, color = a.ink2)
            Spacer(Modifier.height(12.dp))

            Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center
                    val maxR = size.minDimension / 2f - 6.dp.toPx()
                    val r = maxR * (0.52f + 0.48f * breath.value)
                    drawCircle(accent.copy(alpha = 0.08f), maxR, c, style = Stroke(2.dp.toPx()))
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.04f)), c, r * 1.25f), r * 1.25f, c)
                    drawCircle(accent.copy(alpha = 0.22f), r, c)
                    drawCircle(accent.copy(alpha = 0.6f), r, c, style = Stroke(2.5.dp.toPx()))
                }
                AnimatedContent(
                    targetState = when {
                        state.result != null -> "Done"
                        !state.running -> ""
                        state.lockedAtMs == 0L -> state.stage.text
                        else -> CalmSession.phase(sinceLock)
                    },
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "phase",
                ) { t -> Text(t, style = AuraType.Figure.copy(fontSize = 22.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 36.dp)) }
            }
            Spacer(Modifier.height(10.dp))

            if (state.result == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    CalmReadout("Heart rate", state.bpm?.toString() ?: "—", "bpm")
                    CalmReadout("HRV", state.hrv?.toString() ?: "—", "ms")
                }
                if (state.running && state.motion != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (state.motion) { MotionLevel.STILL -> "Still: good"; MotionLevel.SLIGHT -> "Slight movement"; else -> "Moving: try to rest your hand" },
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = when (state.motion) { MotionLevel.STILL -> Palette.Good; MotionLevel.SLIGHT -> Palette.Warn; else -> Palette.Bad },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth()) { Spark(state.hrvTrend.map { it.toDouble() }, accent, height = 40.dp, emptyText = "") }
                Spacer(Modifier.height(6.dp))
                Text(
                    state.message ?: if (state.running && state.lockedAtMs > 0) "${state.secondsLeft} s left · breathe with the circle" else if (state.running) "Sit comfortably and rest your hand" else "",
                    fontSize = 12.5.sp, color = a.ink2, textAlign = TextAlign.Center,
                )
            } else {
                val r = state.result
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    CalmChange("Heart rate", r.hrStart, r.hrEnd, "bpm")
                    CalmChange("HRV", r.hrvStart, r.hrvEnd, "ms")
                }
                Spacer(Modifier.height(10.dp))
                Text(CalmSession.message(r), fontSize = 13.sp, color = a.ink2, textAlign = TextAlign.Center)
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

@Composable
private fun CalmReadout(label: String, value: String, unit: String) {
    val a = MaterialTheme.aura
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.5.sp, color = a.ink3)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = AuraType.Display.copy(fontSize = 32.sp, lineHeight = 36.sp))
            Text(" $unit", fontSize = 12.sp, color = a.ink3, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

/** "74 → 66 bpm": the value at the start of the session and at the end. */
@Composable
private fun CalmChange(label: String, start: Int?, end: Int?, unit: String) {
    val a = MaterialTheme.aura
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.5.sp, color = a.ink3)
        Text(if (start != null && end != null) "$start → $end" else "—", style = AuraType.Display.copy(fontSize = 30.sp, lineHeight = 34.sp))
        Text(unit, fontSize = 12.sp, color = a.ink3)
    }
}
