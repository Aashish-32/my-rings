package com.aashish.myrings.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * "Dusk & Porcelain": the look comes from the subject, a ring worn through day and night.
 * Dark mode is a deep indigo night sky with silver-lilac ink; light mode is warm porcelain with ink-blue text.
 * Semantic colors are shared across modes; the sleep-stage steps were validated with the dataviz validator
 * (adjacent CVD separation and normal-vision separation on both surfaces).
 */
object Palette {
    val Accent = Color(0xFF7C8CFF)   // moonlight periwinkle: brand, selection
    val Good = Color(0xFF2DBE8A)     // aurora: recovered, healthy
    val Warn = Color(0xFFE8A23B)     // ember: caution
    val Bad = Color(0xFFEA5F6E)      // coral: alert
    val Ember = Color(0xFFFF9A62)    // effort / activity warmth
    val Light = Color(0xFF8FA8FF)    // soft blue, used by stress "relaxed"

    // Sleep stages (validated order: deep, light, rem, awake)
    val Deep = Color(0xFF3987E5)
    val StageLight = Color(0xFF199E70)
    val Rem = Color(0xFF9085E9)
    val Awake = Color(0xFFD95926)
}

/** Extra tokens that Material's ColorScheme has no slot for. */
@Immutable
class AuraColors(
    val isDark: Boolean,
    val ink2: Color,        // secondary text
    val ink3: Color,        // muted text and axis labels
    val hairline: Color,    // 1px borders
    val glassTop: Color,    // card gradient, top
    val glassBottom: Color, // card gradient, bottom
    val glow: Color,        // soft backdrop glow
    val navSurface: Color,
)

private val DarkAura = AuraColors(
    isDark = true, ink2 = Color(0xFFA9A8C4), ink3 = Color(0xFF71708F), hairline = Color(0x14FFFFFF),
    glassTop = Color(0xFF171B36), glassBottom = Color(0xFF11142A), glow = Color(0xFF3B3F9E), navSurface = Color(0xF21A1E3C),
)
private val LightAura = AuraColors(
    isDark = false, ink2 = Color(0xFF55557A), ink3 = Color(0xFF8E8EA8), hairline = Color(0x1A1B1B3A),
    glassTop = Color(0xFFFFFFFF), glassBottom = Color(0xFFFBF8F2), glow = Color(0xFFC9CCFF), navSurface = Color(0xF2FFFFFF),
)

val LocalAura = staticCompositionLocalOf { DarkAura }

private val Dark = darkColorScheme(
    primary = Palette.Accent, onPrimary = Color(0xFF0B0D22),
    primaryContainer = Color(0xFF262B5E), onPrimaryContainer = Color(0xFFDDE0FF),
    background = Color(0xFF0A0C1A), onBackground = Color(0xFFECEAF6),
    surface = Color(0xFF12152B), onSurface = Color(0xFFECEAF6),
    surfaceVariant = Color(0xFF1B1F3F), onSurfaceVariant = Color(0xFFA9A8C4),
    outline = Color(0x1FFFFFFF), outlineVariant = Color(0x14FFFFFF), error = Palette.Bad,
)
private val Light = lightColorScheme(
    primary = Color(0xFF4B5BE0), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E8FF), onPrimaryContainer = Color(0xFF1A1F6B),
    background = Color(0xFFF5F2EC), onBackground = Color(0xFF16162D),
    surface = Color.White, onSurface = Color(0xFF16162D),
    surfaceVariant = Color(0xFFEFEBE3), onSurfaceVariant = Color(0xFF55557A),
    outline = Color(0x261B1B3A), outlineVariant = Color(0x1A1B1B3A), error = Color(0xFFD13B4C),
)

/** Big figures use the serif for personality; everything else is the clean system sans. */
val NumeralFont = FontFamily.Serif

object AuraType {
    val Hero = TextStyle(fontFamily = NumeralFont, fontWeight = FontWeight.Normal, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1.5).sp)
    val Display = TextStyle(fontFamily = NumeralFont, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp)
    val Figure = TextStyle(fontFamily = NumeralFont, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp)
    val Title = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp)
    val Body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val Label = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp)
}

private val AuraTypography = Typography(
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 1.4.em),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 1.4.em),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 1.35.em),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 1.3.em, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 1.3.em, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 1.3.em, fontWeight = FontWeight.SemiBold),
)

private val AuraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun MyRingsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalAura provides (if (dark) DarkAura else LightAura)) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AuraShapes, typography = AuraTypography, content = content)
    }
}

val MaterialTheme.aura: AuraColors @Composable get() = LocalAura.current
