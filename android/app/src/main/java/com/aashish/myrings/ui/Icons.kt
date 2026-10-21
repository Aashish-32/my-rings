package com.aashish.myrings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One icon family: 24-unit grid, 1.75 stroke, round caps and joins (Feather-style outlines, MIT licensed).
 * Drawn straight from SVG path data, so there is no icon-font or vector-asset dependency.
 */
enum class Glyph(val paths: List<String>) {
    Today(listOf("M12 7a5 5 0 1 0 0 10a5 5 0 0 0 0-10z", "M12 1v2", "M12 21v2", "M4.22 4.22l1.42 1.42", "M18.36 18.36l1.42 1.42", "M1 12h2", "M21 12h2", "M4.22 19.78l1.42-1.42", "M18.36 5.64l1.42-1.42")),
    Moon(listOf("M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z")),
    Pulse(listOf("M22 12h-4l-3 9L9 3l-3 9H2")),
    Ring(listOf("M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z", "M12 7.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 0 0 0-9z")),
    Heart(listOf("M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z")),
    Drop(listOf("M12 2.69l5.66 5.66a8 8 0 1 1-11.31 0z")),
    Thermo(listOf("M14 14.76V3.5a2.5 2.5 0 0 0-5 0v11.26a4.5 4.5 0 1 0 5 0z")),
    Wave(listOf("M2 12c2-6 4-6 6 0s4 6 6 0 4-6 6 0")),
    Battery(listOf("M3 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M23 13v-2")),
    Refresh(listOf("M23 4v6h-6", "M1 20v-6h6", "M3.51 9a9 9 0 0 1 14.85-3.36L23 10", "M1 14l4.64 4.36A9 9 0 0 0 20.49 15")),
    ChevronLeft(listOf("M15 18l-6-6 6-6")),
    ChevronRight(listOf("M9 18l6-6-6-6")),
    ChevronDown(listOf("M6 9l6 6 6-6")),
    Plus(listOf("M12 5v14", "M5 12h14")),
    Check(listOf("M20 6L9 17l-5-5")),
    Sliders(listOf("M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M1 14h6", "M9 8h6", "M17 16h6")),
    Code(listOf("M16 18l6-6-6-6", "M8 6l-6 6 6 6")),
    Edit(listOf("M12 20h9", "M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z")),
    Alert(listOf("M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z", "M12 9v4", "M12 17h.01")),
    Flame(listOf("M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.07-2.14-.22-4.05 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.15.43-2.29 1-3a2.5 2.5 0 0 0 2.5 2.5z")),
}

private val parser = PathParser()

@Composable
fun AuraIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, strokeWidth: Float = 1.75f) {
    val paths = remember(glyph) { glyph.paths.map { parser.parsePathString(it).toPath() } }
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        withTransform({ scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) }) {
            paths.forEach { p -> drawPath(p, tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}
