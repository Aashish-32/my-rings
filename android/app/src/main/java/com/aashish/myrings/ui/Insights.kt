package com.aashish.myrings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashish.myrings.data.Analysis
import com.aashish.myrings.data.Export
import com.aashish.myrings.data.Workout
import kotlin.math.roundToInt

/** How far behind the sleep need the last two weeks have left you (ecore's sleep-debt measure), in plain words. */
@Composable
fun SleepDebtPanel(debtH: Double, needH: Double?) {
    val a = MaterialTheme.aura
    val (word, color) = when {
        debtH < 1.0 -> "You're well rested" to Palette.Good
        debtH < 3.0 -> "A little behind" to Palette.Warn
        else -> "Running a deficit" to Palette.Bad
    }
    Panel(Modifier.fillMaxWidth()) {
        Text("Sleep debt", fontSize = 12.sp, color = a.ink2)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (debtH < 0.1) "None" else "%d h %02d m".format(debtH.toInt(), ((debtH % 1) * 60).roundToInt()), style = AuraType.Display, color = color)
        }
        Text(word, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
        Spacer(Modifier.height(6.dp))
        Text(
            "Counts how far short of your sleep need${needH?.let { " (about %.1f h)".format(it) } ?: ""} you've been over the last two weeks, with recent nights weighing most.",
            fontSize = 12.sp, color = a.ink3,
        )
    }
}

private fun clock(t: Long) = fmtClock(t)

/** The day's activity sessions: when, how hard, heart rate and calories. */
@Composable
fun WorkoutsPanel(workouts: List<Workout>) {
    val a = MaterialTheme.aura
    Panel(Modifier.fillMaxWidth()) {
        Text("Workouts", style = AuraType.Title)
        if (workouts.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("No sessions detected. A session is 10 minutes or more of steady effort (3 MET or above).", fontSize = 12.5.sp, color = a.ink3)
            return@Panel
        }
        workouts.forEach { w ->
            val tint = when (w.intensity) { "Vigorous" -> Palette.Bad; "Moderate" -> Palette.Ember; else -> Palette.Accent }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 3.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(tint))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${clock(w.start)} – ${clock(w.end)}  ·  ${w.durationMin} min", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            "${w.intensity} (${"%.1f".format(w.avgMet)} MET avg)",
                            w.avgHr?.let { "${it.roundToInt()} bpm avg" },
                            w.maxHr?.let { "${it.roundToInt()} max" },
                            "${w.kcal} kcal",
                        ).joinToString("  ·  "),
                        fontSize = 12.5.sp, color = a.ink2,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("The ring can't tell which activity it was (Oura works that out in its cloud), so this shows effort, not type.", fontSize = 11.5.sp, color = a.ink3)
    }
}

/** Lets the person take their numbers out of the app as CSV files. */
@Composable
fun ExportDialog(analysis: Analysis?, serial: String, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(pending.toByteArray()) } != null }.getOrDefault(false)
        onMessage(if (ok) "Exported" else "Could not save the file")
        if (ok) onClose()
    }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        onDismissRequest = onClose,
        title = { Text("Export your data") },
        text = {
            Text(
                if (analysis == null) "Nothing to export yet." else "Choose what to save as a CSV file you can open in a spreadsheet. Everything stays on your device until you share it.",
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            if (analysis != null) Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { pending = Export.dailyCsv(analysis); saver.launch(Export.fileName("daily", serial)) }) { Text("Daily summary") }
                TextButton(onClick = { pending = Export.nightsCsv(analysis); saver.launch(Export.fileName("nights", serial)) }) { Text("Sleep nights") }
                TextButton(onClick = { pending = Export.workoutsCsv(analysis); saver.launch(Export.fileName("workouts", serial)) }) { Text("Workouts") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
