package com.aashish.myrings.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab(val label: String, val glyph: Glyph) {
    Today("Today", Glyph.Today), Sleep("Sleep", Glyph.Moon), Activity("Activity", Glyph.Pulse), Ring("Ring", Glyph.Ring),
}

/**
 * A floating pill with four destinations. The selected one wears an accent capsule and shows its label;
 * the others are quiet icons. The capsule grows in with the label (one smooth width animation).
 */
@Composable
fun FloatingNav(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val a = MaterialTheme.aura
    Surface(
        modifier = modifier.padding(horizontal = 24.dp),
        shape = RoundedCornerShape(32.dp),
        color = a.navSurface,
        border = BorderStroke(1.dp, a.hairline),
        shadowElevation = if (a.isDark) 0.dp else 10.dp,
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { tab ->
                val on = tab == selected
                val bg by androidx.compose.animation.animateColorAsState(
                    if (on) MaterialTheme.colorScheme.primary.copy(alpha = if (a.isDark) 0.20f else 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
                    tween(260), label = "navBg",
                )
                val tint by androidx.compose.animation.animateColorAsState(if (on) MaterialTheme.colorScheme.primary else a.ink3, tween(260), label = "navTint")
                Row(
                    Modifier.clip(RoundedCornerShape(26.dp)).background(bg).pressScale({ onSelect(tab) })
                        .padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize(tween(280)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AuraIcon(tab.glyph, tint, size = 22.dp)
                    AnimatedVisibility(on, enter = fadeIn(tween(240)) + expandHorizontally(tween(280)), exit = fadeOut(tween(120)) + shrinkHorizontally(tween(200))) {
                        Text(tab.label, Modifier.padding(start = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}
