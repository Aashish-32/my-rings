package com.aashish.myrings.ui

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One orchestrated entrance, not motion everywhere: durations in one place, zero when the user removed animations. */
object Motion {
    const val Sweep = 900      // ring arcs and count-ups
    const val Enter = 520      // content fading up
    const val Stagger = 60     // delay between siblings
    val Smooth = CubicBezierEasing(0.22f, 1f, 0.36f, 1f) // ease-out, settles softly
    val PressSpring = spring<Float>(dampingRatio = 0.7f, stiffness = 500f)
}

/** 0 when Settings > Accessibility > Remove animations (animator scale 0) is on. */
@Composable
fun rememberMotionScale(): Float {
    val ctx = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f).coerceIn(0f, 1.5f) }
}

/** Animates from 0 to [target] the first time it is shown, and smoothly between targets afterwards. */
@Composable
fun animatedProgress(target: Float, durationMs: Int = Motion.Sweep, delayMs: Int = 0): Float {
    val scale = rememberMotionScale()
    val a = remember { Animatable(0f) }
    LaunchedEffect(target, scale) {
        if (scale == 0f) a.snapTo(target)
        else a.animateTo(target, tween((durationMs * scale).toInt(), (delayMs * scale).toInt(), Motion.Smooth))
    }
    return a.value
}

/** A figure that counts up to its value. [format] renders the in-between values. */
@Composable
fun CountUpText(
    value: Double?, style: TextStyle, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier,
    decimals: Int = 0, placeholder: String = "—", delayMs: Int = 0, textAlign: TextAlign? = null,
) {
    if (value == null) { Text(placeholder, modifier, style = style, color = color, textAlign = textAlign); return }
    val p = animatedProgress(1f, delayMs = delayMs)
    val shown = value * p
    Text(if (decimals == 0) "%d".format(Math.round(shown)) else "%.${decimals}f".format(shown), modifier, style = style, color = color, textAlign = textAlign)
}

/** Fade + rise on first composition, staggered by [index]. */
fun Modifier.fadeUp(index: Int = 0): Modifier = composed {
    val scale = rememberMotionScale()
    val a = remember { Animatable(if (scale == 0f) 1f else 0f) }
    LaunchedEffect(Unit) { if (scale != 0f) a.animateTo(1f, tween((Motion.Enter * scale).toInt(), (index * Motion.Stagger * scale).toInt(), Motion.Smooth)) }
    graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 14.dp.toPx() }
}

/** Press feedback: the element sinks slightly under the finger and springs back. */
fun Modifier.pressScale(onClick: () -> Unit, enabled: Boolean = true): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.965f else 1f, Motion.PressSpring, label = "press")
    this.scale(s).clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/** 0..1 repeating value for slow pulses (waiting states). */
@Composable
fun rememberPulse(periodMs: Int = 2400): Float {
    val t = rememberInfiniteTransition(label = "pulse")
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulseValue")
    return v
}

/** Continuous rotation in degrees for busy states. */
@Composable
fun rememberSpin(active: Boolean, periodMs: Int = 1100): Float {
    if (!active) return 0f
    val t = rememberInfiniteTransition(label = "spin")
    val v by t.animateFloat(0f, 360f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "spinValue")
    return v
}
