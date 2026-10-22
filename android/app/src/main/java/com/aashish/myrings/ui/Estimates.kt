package com.aashish.myrings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashish.myrings.data.DaySummary
import com.aashish.myrings.data.Resilience

/** Our resilience estimate: a level word, the 0-100 number and the three parts it was built from. Clearly marked as an estimate. */
@Composable
fun ResilienceCard(r: Resilience) {
    val a = MaterialTheme.aura
    val color = when (r.level) { "Limited" -> Palette.Bad; "Adequate" -> Palette.Warn; else -> Palette.Good }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Resilience", fontSize = 12.sp, color = a.ink2, modifier = Modifier.weight(1f))
            Text("Estimate", fontSize = 11.sp, color = a.ink3)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(r.level, style = AuraType.Display, color = color)
            Text("  ${r.score}", color = a.ink3, modifier = Modifier.padding(bottom = 5.dp))
        }
        Spacer(Modifier.height(8.dp))
        r.parts.forEach { (name, v) -> Stat(name, v?.toString() ?: "—") }
        Spacer(Modifier.height(6.dp))
        Text(
            (if (r.provisional) "Based on ${r.nights} night${if (r.nights == 1) "" else "s"}; it settles after 14. " else "Last 14 nights. ") +
                "Oura's resilience uses a private model. This is our own blend of your sleep, calm time in the day and HRV, so read it as a trend, not Oura's number.",
            fontSize = 12.sp, color = a.ink3,
        )
    }
}

/**
 * Estimated steps for the day, with a calibration walk: tap Start, walk about 10 minutes counting your steps, tap Finish and enter the count.
 * The number is a guide built from the ring's activity level, not Oura's step count.
 */
@Composable
fun StepsCard(d: DaySummary, note: String?, scale: Double, walkStartedAt: Long?, onStartWalk: () -> Unit, onFinishWalk: (Int) -> Unit, onCancelWalk: () -> Unit) {
    val a = MaterialTheme.aura
    var asking by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Steps", fontSize = 12.sp, color = a.ink2, modifier = Modifier.weight(1f))
            Text("Estimate", fontSize = 11.sp, color = a.ink3)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(d.steps?.let { "%,d".format(it) } ?: "—", style = AuraType.Display)
            Text("  steps (est.)", color = a.ink3, modifier = Modifier.padding(bottom = 5.dp))
        }
        d.steps?.let { Stat("Distance", "%.1f km".format(it * 0.762 / 1000)) }
        Text(
            if (scale == 1.0) "Counted from the ring's activity level, so it can be off by a fifth or more. Calibrate with a walk to fit it to you."
            else "Fitted to you with a counted walk (scaled by ${"%.2f".format(scale)}). Still an estimate, not Oura's count.",
            fontSize = 12.sp, color = a.ink3, modifier = Modifier.padding(top = 6.dp),
        )
        note?.let { Text(it, fontSize = 12.sp, color = a.ink2, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(12.dp))
        if (walkStartedAt == null) {
            AuraButton("Calibrate with a walk", Glyph.Pulse, filled = false, modifier = Modifier.fillMaxWidth(), onClick = onStartWalk)
        } else {
            Text("Walk started. Walk steadily for about 10 minutes and count your steps, then tap Finish.", fontSize = 12.5.sp, color = a.ink2)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AuraButton("Cancel", Glyph.Refresh, filled = false, modifier = Modifier.weight(1f), onClick = onCancelWalk)
                AuraButton("Finish", Glyph.Check, modifier = Modifier.weight(1f), onClick = { asking = true })
            }
        }
    }
    if (asking) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("How many steps did you count?") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), placeholder = { Text("e.g. 1100") },
                )
            },
            confirmButton = { TextButton(onClick = { text.toIntOrNull()?.let { onFinishWalk(it); asking = false } }) { Text("Save", fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Back") } },
        )
    }
}
