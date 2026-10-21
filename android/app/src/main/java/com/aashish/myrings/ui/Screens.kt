package com.aashish.myrings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashish.myrings.data.DaySummary
import com.aashish.myrings.service.RingUi
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal data class Metric(val title: String, val unit: String, val dec: Int, val color: Color, val goodWhenUp: Boolean?, val caption: String, val pick: (DaySummary) -> Double?)

internal val METRICS = listOf(
    Metric("Resting heart rate", "bpm", 0, Palette.Bad, false, "Lower usually means better recovery") { it.restingHr },
    Metric("Heart rate variability", "ms", 0, Palette.Accent, true, "Higher usually means better recovery") { it.hrv },
    Metric("Skin temperature", "°C", 1, Palette.Warn, null, "Compared with your own normal") { it.temp },
    Metric("Blood oxygen", "%", 1, Palette.Good, true, "Typically 95–100% while asleep") { it.spo2 },
)

/** Last 24 h of battery: phone-read samples merged with the ring's own records (which arrive with each sync). */
internal fun batteryPoints(d: Detail?): List<Double> {
    if (d == null) return emptyList()
    val cutoff = System.currentTimeMillis() / 1000 - 24 * 3600
    return (d.batteryHistory + d.analysis.ringBattery.filter { it.ts >= cutoff }).sortedBy { it.ts }.map { it.pct.toDouble() }
}

// ---------------- developer mode ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DevPanel(vm: AppViewModel, ring: RingUi) {
    val detail by vm.detail.collectAsState()
    val filter by vm.filter.collectAsState()
    val log by vm.log.collectAsState()
    val clip = LocalClipboardManager.current
    var expanded by remember { mutableStateOf<Long?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var logQuery by remember { mutableStateOf("") }

    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("⚙ Developer mode", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            val d = detail?.device.orEmpty()
            Kv("Serial", ring.row.serial); Kv("Address", ring.row.address ?: "—"); Kv("MAC (ring)", d["mac"] ?: "—")
            Kv("Firmware / API", "${d["firmware"] ?: "—"} / ${d["api"] ?: "—"}"); Kv("Hardware", d["hardware"] ?: "—")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kv("Auth key", if (showKey) ring.row.keyHex else "•".repeat(32), Modifier.weight(1f))
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                TextButton(onClick = { clip.setText(AnnotatedString(ring.row.keyHex)) }) { Text("Copy") }
            }
            Text("Keep the auth key safe — without it the ring needs another factory reset. It never leaves this phone unless you copy it.", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::refreshBattery) { Text("Read battery now") }
            }

            Text("Event types — tap to filter", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilterChip(selected = filter.isEmpty(), onClick = { vm.setFilter("") }, label = { Text("all") })
                detail?.counts.orEmpty().forEach { (n, c) -> FilterChip(selected = filter == n, onClick = { vm.setFilter(n) }, label = { Text("$n · $c", fontSize = 11.sp) }) }
            }
            Text("Recent events", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            detail?.events.orEmpty().forEach { e ->
                Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == e.id) null else e.id }.padding(vertical = 3.dp)) {
                    Text("${e.name}  ·  0x%02x  ·  ${e.bodyLen} B  ·  ts ${e.ringTs}".format(e.tag), fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                    val body = e.decodedJson ?: e.hexHead
                    Text(if (expanded == e.id) body else body.take(90), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded == e.id) 40 else 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            val reboots = detail?.analysis?.reboots.orEmpty()
            if (reboots.isNotEmpty()) {
                Text("Ring restarts (${reboots.size})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                reboots.takeLast(10).asReversed().forEach { r ->
                    Text("${java.time.Instant.ofEpochSecond(r.ts).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ').take(16)}  ${r.label} (${r.reason})",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            Text("Firmware log (newest first)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(logQuery, { logQuery = it }, singleLine = true, placeholder = { Text("Filter, e.g. wdt, batt, DHR, chg") }, modifier = Modifier.fillMaxWidth())
            val logs = detail?.analysis?.logs.orEmpty().asReversed().asSequence()
                .filter { logQuery.isBlank() || it.second.contains(logQuery.trim(), ignoreCase = true) }.take(200).toList()
            SelectionContainer {
                Text(
                    logs.joinToString("\n") { (t, s) -> java.time.Instant.ofEpochSecond(t).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(8) + "  " + s }.ifEmpty { "No log lines." },
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                )
            }
            Text("Activity log", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(log.ifEmpty { "No operations yet." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
            }
            TextButton(onClick = vm::remove) { Text("Remove this ring from the app", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun Kv(k: String, v: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(k, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- dialogs ----------------

@Composable
internal fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Rename ring") },
        text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun AddRingDialog(vm: AppViewModel, onClose: () -> Unit) {
    val state by vm.pair.collectAsState()
    var name by remember { mutableStateOf("My Ring") }
    var adopt by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }

    LaunchedEffect(state.done) { if (state.done) { delay(900); vm.resetPair(); onClose() } }

    AlertDialog(
        onDismissRequest = { if (!state.running) { vm.resetPair(); onClose() } },
        title = { Text("Add a ring") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.running || state.done) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(state.status)
                    }
                } else {
                    Text("1. Factory-reset the ring (light blinks blue).\n2. Put it on the charger next to this phone and keep your other rings away.\n3. Turn off Bluetooth on other phones.", fontSize = 13.sp)
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(adopt, { adopt = it }); Spacer(Modifier.width(8.dp))
                        Text(if (adopt) "Ring is already set up — I have its key" else "Ring was factory-reset (new key)", fontSize = 13.sp)
                    }
                    OutlinedTextField(
                        key, { key = it.filter { c -> c.isLetterOrDigit() }.take(32) },
                        label = { Text(if (adopt) "Auth key (32 hex characters)" else "Reuse an existing key (optional)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.error != null) Text(state.error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            if (!state.running && !state.done) TextButton(onClick = { vm.startPair(name.trim().ifEmpty { "My Ring" }, key.ifBlank { null }, adopt) }) { Text("Pair") }
        },
        dismissButton = { if (!state.running && !state.done) TextButton(onClick = { vm.resetPair(); onClose() }) { Text("Cancel") } },
    )
}
