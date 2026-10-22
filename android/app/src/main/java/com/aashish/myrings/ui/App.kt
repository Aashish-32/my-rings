package com.aashish.myrings.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashish.myrings.data.DaySummary
import com.aashish.myrings.service.RingUi
import kotlinx.coroutines.delay
import kotlin.math.abs

private val ReadinessRing = RingSpec(null, Color(0xFF2DBE8A), Color(0xFF8CF3CF))
private val SleepRing = RingSpec(null, Color(0xFF6B5CE0), Color(0xFFB9ABFF))
private val CalmRing = RingSpec(null, Color(0xFFFF9A62), Color(0xFFFFD592))

@Composable
fun MyRingsApp(vm: AppViewModel) {
    val rings by vm.rings.collectAsState()
    val selected by vm.selected.collectAsState()
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var selDay by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.snack.collect { snack.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

    val ring = rings.firstOrNull { it.row.serial == selected }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(vm, rings, selected, onAdd = { showAdd = true })
            Box(Modifier.weight(1f)) {
                if (ring == null) {
                    EmptyState { showAdd = true }
                } else {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = { (fadeIn(tween(300, 90)) + slideInVertically(tween(340)) { it / 28 }) togetherWith fadeOut(tween(140)) },
                        label = "tab",
                    ) { t ->
                        when (t) {
                            Tab.Today -> TodayScreen(vm, ring, selDay, { selDay = it }, openSleep = { tab = Tab.Sleep })
                            Tab.Sleep -> SleepScreen(vm, selDay) { selDay = it }
                            Tab.Activity -> ActivityScreen(vm, selDay) { selDay = it }
                            Tab.Ring -> RingTab(vm, ring) { showAdd = true }
                        }
                    }
                }
            }
        }
        if (ring != null) {
            // content fades out under the floating nav instead of showing through it
            val bg = MaterialTheme.colorScheme.background
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = 0.92f), bg))))
        }
        if (ring != null) FloatingNav(tab, { tab = it }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp))
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp))
    }
    if (showAdd) AddRingDialog(vm) { showAdd = false }

    val measure by vm.measure.collectAsState()
    MeasureDialog(measure, onStop = vm::stopMeasure, onClose = vm::closeMeasure, onAgain = vm::startMeasure)
    val calm by vm.calm.collectAsState()
    CalmDialog(calm, onStop = vm::stopCalm, onClose = vm::closeCalm, onAgain = vm::startCalm)

    val backupSerial by vm.backupPrompt.collectAsState()
    rings.firstOrNull { it.row.serial == backupSerial }?.let { r ->
        KeyBackupDialog(r, fresh = true, onClose = vm::dismissBackupPrompt, onMessage = vm::toast)
    }
}

/** A soft pool of light behind everything: indigo at the top-left, a warm trace at the bottom-right. */
@Composable
private fun Backdrop() {
    val a = MaterialTheme.aura
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.radialGradient(listOf(a.glow.copy(alpha = if (a.isDark) 0.50f else 0.55f), Color.Transparent), Offset(0f, 0f), size.width * 1.15f))
        drawRect(Brush.radialGradient(listOf(Palette.Ember.copy(alpha = if (a.isDark) 0.07f else 0.10f), Color.Transparent), Offset(size.width, size.height), size.width * 0.9f))
    }
}

// ---------------------------------------------------------------- top bar

@Composable
private fun TopBar(vm: AppViewModel, rings: List<RingUi>, selected: String?, onAdd: () -> Unit) {
    val busy by vm.busy.collectAsState()
    val a = MaterialTheme.aura
    var menu by remember { mutableStateOf(false) }
    val current = rings.firstOrNull { it.row.serial == selected }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .pressScale({ menu = true }).padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(current?.battery?.let { batteryColor(it.pct) } ?: a.ink3))
                Spacer(Modifier.width(8.dp))
                Text(current?.row?.name ?: "My Rings", Modifier.widthIn(max = 170.dp), style = AuraType.Title.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                current?.battery?.let { Text("  ${it.pct}%", fontSize = 13.sp, color = a.ink2) }
                Spacer(Modifier.width(4.dp))
                AuraIcon(Glyph.ChevronDown, a.ink2, size = 16.dp)
            }
            DropdownMenu(menu, { menu = false }) {
                rings.forEach { r ->
                    DropdownMenuItem(
                        text = { Text(r.row.name + (r.battery?.let { " · ${it.pct}%" } ?: "")) },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(r.battery?.let { batteryColor(it.pct) } ?: a.ink3)) },
                        onClick = { vm.select(r.row.serial); menu = false },
                    )
                }
                DropdownMenuItem(text = { Text("Add a ring") }, leadingIcon = { AuraIcon(Glyph.Plus, a.ink2, size = 18.dp) }, onClick = { menu = false; onAdd() })
            }
        }
        Spacer(Modifier.weight(1f))
        if (current != null) {
            val spin = rememberSpin(busy != null)
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .pressScale({ vm.sync() }, enabled = busy == null),
                contentAlignment = Alignment.Center,
            ) { AuraIcon(Glyph.Refresh, if (busy != null) MaterialTheme.colorScheme.primary else a.ink2, Modifier.rotate(spin), size = 21.dp) }
        }
    }
}

// ---------------------------------------------------------------- shared bits

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    val a = MaterialTheme.aura
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        ConcentricRings(listOf(ReadinessRing, SleepRing, CalmRing), size = 200.dp) { AuraIcon(Glyph.Ring, a.ink3, size = 34.dp, strokeWidth = 1.5f) }
        Spacer(Modifier.height(28.dp))
        Text("Meet your ring", style = AuraType.Display, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("Reset it, set it on the charger next to this phone, and add it here. Everything stays on your phone.", style = AuraType.Body, color = a.ink2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(26.dp))
        AuraButton("Add your ring", Glyph.Plus, onClick = onAdd)
    }
}

@Composable
fun AuraButton(text: String, glyph: Glyph? = null, filled: Boolean = true, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val a = MaterialTheme.aura
    val primary = MaterialTheme.colorScheme.primary
    val bg = if (filled) primary else Color.Transparent
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else primary
    Row(
        modifier.alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(26.dp)).background(bg)
            .then(if (filled) Modifier else Modifier.border(1.dp, primary.copy(alpha = 0.5f), RoundedCornerShape(26.dp)))
            .pressScale(onClick, enabled).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) { AuraIcon(glyph, fg, size = 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

@Composable
private fun SectionHeader(title: String, sub: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = AuraType.Title, modifier = Modifier.weight(1f))
        if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.aura.ink3)
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.13f)).padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** ‹ Today › : steps through the days the ring has recorded. */
@Composable
private fun DayHeader(dates: List<String>, selected: String, onSelect: (String) -> Unit, week: Boolean = false) {
    val a = MaterialTheme.aura
    val i = dates.indexOf(selected)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NavArrow(Glyph.ChevronLeft, enabled = i > 0) { onSelect(dates[i - 1]) }
        AnimatedContent(selected, Modifier.weight(1f), transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "day") { d ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (week) {
                    val end = java.time.LocalDate.parse(d); val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d")
                    Text(if (d == dates.last()) "This week" else "Week ending ${end.format(fmt)}", style = AuraType.Title)
                    Text("${end.minusDays(6).format(fmt)} – ${end.format(fmt)}", fontSize = 12.sp, color = a.ink3)
                } else {
                    Text(dayLabel(d, dates.last()), style = AuraType.Title)
                    Text(java.time.LocalDate.parse(d).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d")), fontSize = 12.sp, color = a.ink3)
                }
            }
        }
        NavArrow(Glyph.ChevronRight, enabled = i < dates.size - 1) { onSelect(dates[i + 1]) }
    }
}

@Composable
private fun NavArrow(glyph: Glyph, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).pressScale(onClick, enabled), contentAlignment = Alignment.Center) {
        AuraIcon(glyph, MaterialTheme.aura.ink2.copy(alpha = if (enabled) 1f else 0.25f), size = 22.dp)
    }
}

private fun headline(v: Int?, anyNight: Boolean = false): String = when {
    v == null -> if (anyNight) "No sleep recorded" else "Waiting for your first night"
    v >= 85 -> "Primed to go"
    v >= 70 -> "Good to go"
    v >= 60 -> "Take it steady"
    else -> "Time to recharge"
}

// ---------------------------------------------------------------- Today

@Composable
private fun TodayScreen(vm: AppViewModel, ring: RingUi, selDay: String?, onDay: (String) -> Unit, openSleep: () -> Unit) {
    val detail by vm.detail.collectAsState()
    val d = detail
    if (d == null) { LoadingSkeleton(); return }
    val busy by vm.busy.collectAsState()
    val series = d.analysis.days
    val a = MaterialTheme.aura
    var week by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        if (series.isEmpty()) {
            item { ProfilePrompt(vm, null) }
            item { Onboarding(ring) }
            return@LazyColumn
        }
        val dates = series.map { it.date }
        // open on the latest day that has a night: today is empty until you wear the ring to bed
        val day = selDay?.takeIf { it in dates } ?: dates.lastOrNull { dt -> d.analysis.nights.any { it.date == dt } } ?: dates.last()
        val night = d.analysis.nights.lastOrNull { it.date == day }
        val stress = d.analysis.stress[day]
        val calm = when (stress?.summary) { "Restored" -> 90; "Normal" -> 70; "Stressful" -> 45; else -> null }
        val rd = night?.readiness?.value
        val sl = night?.sleepScore?.value

        item { ProfilePrompt(vm, d.analysis.profile) }
        item { DayHeader(dates, day, onDay, week) }
        item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SegmentedToggle(listOf("Day", "Week"), if (week) 1 else 0) { week = it == 1 } } }
        if (week) {
            item { WeekView(d.analysis, day) }
            return@LazyColumn
        }
        item {
            Column(Modifier.fillMaxWidth().fadeUp(1), horizontalAlignment = Alignment.CenterHorizontally) {
                ConcentricRings(
                    listOf(RingSpec(rd, ReadinessRing.from, ReadinessRing.to), RingSpec(sl, SleepRing.from, SleepRing.to), RingSpec(calm, CalmRing.from, CalmRing.to)),
                    size = 276.dp,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Readiness", fontSize = 12.sp, color = a.ink2, fontWeight = FontWeight.Medium)
                        CountUpText(rd?.toDouble(), AuraType.Hero, MaterialTheme.colorScheme.onSurface, delayMs = 200)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(headline(rd, d.analysis.nights.isNotEmpty()), style = AuraType.Display.copy(fontSize = 28.sp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(
                    night?.readiness?.note ?: night?.readiness?.label ?: "Wear your ring to bed and open the app in the morning.",
                    style = AuraType.Body, color = a.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth().fadeUp(2), horizontalArrangement = Arrangement.SpaceEvenly) {
                ScoreKey("Readiness", rd, ReadinessRing.from, night?.readiness?.label ?: "—") { openSleep() }
                ScoreKey("Sleep", sl, SleepRing.to, night?.sleepScore?.label ?: "—") { openSleep() }
                ScoreKey("Calm", calm, CalmRing.from, stress?.summary ?: "—") { openSleep() }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().fadeUp(3), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AuraButton("Measure heart rate now", Glyph.Heart, filled = false, enabled = busy == null) { vm.startMeasure() }
                AuraButton("Calm session · 1 minute", Glyph.Wave, filled = false, enabled = busy == null) { vm.startCalm() }
            }
        }
        item { VitalsPanel(series, Modifier.fadeUp(3)) }

        val last = series.reversed()
        val insights = METRICS.mapNotNull { m ->
            val c = last.firstNotNullOfOrNull(m.pick)
            val b = series.dropLast(1).takeLast(7).mapNotNull(m.pick).let { if (it.isEmpty()) null else it.average() }
            if (c != null && b != null && abs(c - b) >= (if (m.dec > 0) 0.2 else 2.0))
                "${m.title} is ${"%.${m.dec}f".format(abs(c - b))} ${m.unit} ${if (c > b) "higher" else "lower"} than your 7-day average."
            else null
        }
        // a metric that has drifted from the person's own normal for several days gets a calm heads-up
        val drift = com.aashish.myrings.data.Drift.findings(d.analysis, System.currentTimeMillis() / 1000).map { it.text }
        val allNotes = drift + insights
        if (allNotes.isNotEmpty()) {
            item {
                Column(Modifier.fadeUp(4), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Worth noticing")
                    allNotes.forEach { t ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                            Spacer(Modifier.width(12.dp))
                            Text(t, style = AuraType.Body, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f))
                        }
                    }
                }
            }
        }
    }
}

private val VITALS = listOf(
    Triple(Glyph.Heart, Palette.Bad, "Resting heart rate"),
    Triple(Glyph.Wave, Palette.Accent, "Heart rate variability"),
    Triple(Glyph.Thermo, Palette.Ember, "Skin temperature"),
    Triple(Glyph.Drop, Palette.Good, "Blood oxygen"),
)

/** The four vitals in ONE panel (a 2x2 grid with hairlines), instead of four identical cards. */
@Composable
private fun VitalsPanel(series: List<DaySummary>, modifier: Modifier = Modifier) {
    val a = MaterialTheme.aura
    val last = series.reversed()
    Panel(modifier.fillMaxWidth()) {
        SectionHeader("Vitals", "vs your 7-day average")
        Spacer(Modifier.height(14.dp))
        METRICS.chunked(2).forEachIndexed { row, pair ->
            if (row > 0) HorizontalDivider(Modifier.padding(vertical = 16.dp), color = a.hairline)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                pair.forEachIndexed { col, m ->
                    if (col > 0) VerticalDivider(Modifier.padding(horizontal = 14.dp), color = a.hairline)
                    val (glyph, tint, _) = VITALS.first { it.third == m.title }
                    val cur = last.firstNotNullOfOrNull(m.pick)
                    val base = series.dropLast(1).takeLast(7).mapNotNull(m.pick).let { if (it.isEmpty()) null else it.average() }
                    val day = last.firstOrNull { m.pick(it) != null }
                    val daytime = when (m.title) { "Resting heart rate" -> day?.restingSrc; "Heart rate variability" -> day?.hrvSrc; else -> null } == "daytime"
                    var scrub by remember { mutableStateOf<Int?>(null) }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AuraIcon(glyph, tint, size = 16.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(m.title, fontSize = 12.sp, color = a.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                            CountUpText(cur, AuraType.Figure, MaterialTheme.colorScheme.onSurface, decimals = m.dec, delayMs = 250 + (row * 2 + col) * 80)
                            Text(" ${m.unit}", fontSize = 12.sp, color = a.ink3, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        val trendDays = series.takeLast(14).filter { m.pick(it) != null }
                        val scrubbed = scrub?.let { trendDays.getOrNull(it) }
                        val note = when {
                            scrubbed != null -> "${java.time.LocalDate.parse(scrubbed.date).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))} · ${"%.${m.dec}f".format(m.pick(scrubbed))} ${m.unit}"
                            cur == null -> if (m.title == "Blood oxygen" || m.title == "Resting heart rate") "Appears after a night's sleep" else "No reading yet"
                            base == null -> if (daytime) "Daytime estimate" else "Building your baseline"
                            else -> {
                                val diff = cur - base
                                val tol = if (m.dec > 0) 0.05 else 0.5
                                if (abs(diff) < tol) "In line with your average" else "${if (diff > 0) "▲" else "▼"} ${"%.${m.dec}f".format(abs(diff))} ${m.unit}"
                            }
                        }
                        Text(note, fontSize = 11.5.sp, color = if (scrubbed != null) tint else if (daytime && base == null) Palette.Warn else a.ink3, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // a trend needs at least two days; until then show nothing rather than an empty gap
                        val trend = series.takeLast(14).mapNotNull(m.pick)
                        if (trend.size >= 2) { Spacer(Modifier.height(6.dp)); Spark(trend, tint, height = 34.dp, onScrub = { scrub = it }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Onboarding(ring: RingUi) {
    val a = MaterialTheme.aura
    Column(Modifier.fillMaxWidth().fadeUp(0), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(10.dp))
        ConcentricRings(listOf(RingSpec(null, ReadinessRing.from, ReadinessRing.to), RingSpec(null, SleepRing.from, SleepRing.to), RingSpec(null, CalmRing.from, CalmRing.to)), size = 240.dp) {
            AuraIcon(Glyph.Moon, a.ink2, size = 38.dp, strokeWidth = 1.5f)
        }
        Spacer(Modifier.height(24.dp))
        Text("Your first night", style = AuraType.Display, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("${ring.row.name} starts recording sleep, heart rate and temperature once it's worn.", style = AuraType.Body, color = a.ink2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        listOf("Ring paired and connected" to true, "Health tracking switched on" to true, "Wear it to bed tonight" to false, "Open the app in the morning. It syncs by itself." to false)
            .forEachIndexed { i, (t, done) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).fadeUp(i + 1), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(if (done) Palette.Good else Color.Transparent)
                            .then(if (done) Modifier else Modifier.border(1.5.dp, a.ink3.copy(alpha = 0.6f), CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) { if (done) AuraIcon(Glyph.Check, Color.White, size = 15.dp, strokeWidth = 2.4f) else Text("${i + 1}", fontSize = 11.sp, color = a.ink3) }
                    Spacer(Modifier.width(14.dp))
                    Text(t, style = AuraType.Body, color = if (done) a.ink3 else MaterialTheme.colorScheme.onSurface)
                }
            }
    }
}

@Composable
private fun LoadingSkeleton() {
    val p = rememberPulse(1400)
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.padding(top = 30.dp).size(260.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f + 0.04f * p)))
        Box(Modifier.fillMaxWidth(0.6f).height(22.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f + 0.04f * p)))
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f + 0.04f * p)))
    }
}

// ---------------------------------------------------------------- Sleep / Activity

@Composable
private fun SleepScreen(vm: AppViewModel, selDay: String?, onDay: (String) -> Unit) {
    val detail by vm.detail.collectAsState()
    val d = detail
    if (d == null) { LoadingSkeleton(); return }
    val series = d.analysis.days
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (series.isEmpty()) { item { EmptyTab(Glyph.Moon, "No nights yet", "Wear the ring to bed. Sleep stages, readiness and calm appear here after your first sync in the morning.") }; return@LazyColumn }
        val dates = series.map { it.date }
        // open on the latest day that has a night: today is empty until you wear the ring to bed
        val day = selDay?.takeIf { it in dates } ?: dates.lastOrNull { dt -> d.analysis.nights.any { it.date == dt } } ?: dates.last()
        val night = d.analysis.nights.lastOrNull { it.date == day }
        item { DayHeader(dates, day, onDay) }
        item { Box(Modifier.fadeUp(1)) { ScoreCard("Sleep", night?.sleepScore, "No sleep recorded for this day. Wear the ring to bed; it reports the night once you're up.") { night?.let { SleepDetails(it) } } } }
        night?.sleepDebtH?.let { debt ->
            item { Box(Modifier.fadeUp(2)) { SleepDebtPanel(debt, d.analysis.profile?.age?.let { com.aashish.myrings.data.Metabolic.sleepNeedHours(it) } ?: com.aashish.myrings.data.Metabolic.sleepNeedHours(null)) } }
        }
        item { Box(Modifier.fadeUp(2)) { ScoreCard("Readiness", night?.readiness, "Appears after the ring records a night's sleep.") } }
        item { Box(Modifier.fadeUp(3)) { StressCard(d.analysis.stress[day]) } }
    }
}

@Composable
private fun ActivityScreen(vm: AppViewModel, selDay: String?, onDay: (String) -> Unit) {
    val detail by vm.detail.collectAsState()
    val d = detail
    if (d == null) { LoadingSkeleton(); return }
    val series = d.analysis.days
    var cursor by remember { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (series.isEmpty()) { item { EmptyTab(Glyph.Pulse, "No activity yet", "Heart rate, movement and temperature charts appear after the ring has been worn and synced.") }; return@LazyColumn }
        val dates = series.map { it.date }
        val day = selDay?.takeIf { it in dates } ?: dates.last()
        val intra = d.analysis.intraday[day]
        val night = d.analysis.nights.lastOrNull { it.date == day }
        val sleepBand = night?.let { listOf(it.start to it.end) } ?: emptyList()
        val prev = d.analysis.intraday[java.time.LocalDate.parse(day).minusDays(1).toString()]
        // yesterday's readings moved onto today's clock so the two lines line up by time of day
        fun shifted(l: List<Pair<Long, Double>>?) = l.orEmpty().map { (t, v) -> (t + 86_400L) to v }
        item { DayHeader(dates, day, onDay) }
        item { Box(Modifier.fadeUp(1)) { ScoreCard("Activity", d.analysis.activity[day], "Appears once the ring has a few hours of data for the day.") } }
        item { Box(Modifier.fadeUp(1)) { ActivityCard(series.first { it.date == day }) } }
        val dayWorkouts = d.analysis.workouts[day].orEmpty()
        item { Box(Modifier.fadeUp(2)) { WorkoutsPanel(dayWorkouts) } }
        if (intra != null) {
            item { Box(Modifier.fadeUp(2)) { DayHighlights(intra) } }
            item {
                Text("Press and drag on any chart: the time cursor moves on all three, so you can see what your heart rate did while you were moving.",
                    fontSize = 12.sp, color = MaterialTheme.aura.ink3, modifier = Modifier.padding(horizontal = 4.dp))
            }
            item { Box(Modifier.fadeUp(3)) { TimeChart("Heart rate · 5-min median", intra.hr, Palette.Bad, "bpm", caption = "Good-quality beats while worn.", cursor = cursor, onCursor = { cursor = it }, bands = sleepBand, compare = shifted(prev?.hr), highlights = dayWorkouts.map { it.start to it.end }) } }
            item { Box(Modifier.fadeUp(4)) { TimeChart("Activity level · MET per minute", intra.met, Palette.Ember, "MET", 1, bars = true, marks = intra.alerts, caption = "Dashed lines: move reminders from the ring.", cursor = cursor, onCursor = { cursor = it }, describe = { metWord(it) }) } }
            item { Box(Modifier.fadeUp(5)) { TimeChart("Skin temperature", intra.temp, Palette.Warn, "°C", 1, caption = "Only while worn.", cursor = cursor, onCursor = { cursor = it }, bands = sleepBand, compare = shifted(prev?.temp)) } }
        }
    }
}

@Composable
private fun EmptyTab(glyph: Glyph, title: String, text: String) {
    val a = MaterialTheme.aura
    Column(Modifier.fillMaxWidth().padding(top = 80.dp).fadeUp(0), horizontalAlignment = Alignment.CenterHorizontally) {
        AuraIcon(glyph, a.ink3, size = 44.dp, strokeWidth = 1.4f)
        Spacer(Modifier.height(18.dp))
        Text(title, style = AuraType.Display.copy(fontSize = 26.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = AuraType.Body, color = a.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

// ---------------------------------------------------------------- Ring

@Composable
private fun RingTab(vm: AppViewModel, ring: RingUi, onAdd: () -> Unit) {
    val detail by vm.detail.collectAsState()
    val busy by vm.busy.collectAsState()
    val dev by vm.dev.collectAsState()
    val a = MaterialTheme.aura
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    var renaming by remember { mutableStateOf(false) }
    val b = ring.battery
    val stale = b != null && now / 1000 - b.ts > 2 * 3600

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Box(Modifier.fadeUp(0)) { ProfileCard(vm, detail?.analysis?.profile) } }
        item {
            Column(Modifier.fillMaxWidth().fadeUp(0), horizontalAlignment = Alignment.CenterHorizontally) {
                Gauge(b?.pct, size = 176.dp)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.pressScale({ renaming = true }), verticalAlignment = Alignment.CenterVertically) {
                    Text(ring.row.name, style = AuraType.Display.copy(fontSize = 30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
                    Spacer(Modifier.width(8.dp))
                    AuraIcon(Glyph.Edit, a.ink3, size = 17.dp)
                }
                Spacer(Modifier.height(4.dp))
                Text("Ring 4 · ${ring.row.firmware?.let { "firmware $it" } ?: ring.row.serial}", fontSize = 12.sp, color = a.ink3)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        when { busy != null -> busy!!; b == null -> "Checking…"; stale -> "Not seen for a while"; else -> "Battery read ${ago(b.ts, now)}" },
                        when { busy != null -> MaterialTheme.colorScheme.primary; b == null -> a.ink3; stale -> Palette.Warn; else -> Palette.Good },
                    )
                    Pill("Synced ${ago(ring.row.lastSync, now)}", a.ink3)
                }
                Spacer(Modifier.height(8.dp))
                val tr = ring.tracking
                when {
                    tr == null -> Pill("Health tracking: not checked yet", a.ink3)
                    tr.isOn -> Pill("Health tracking on · checked ${ago(tr.ts, now)}", Palette.Good)
                    tr.isOff -> Pill("Health tracking off", Palette.Bad)
                    else -> Pill("Health tracking partly on (HR ${tr.modeName(tr.hrMode)}, SpO2 ${tr.modeName(tr.spo2Mode)})", Palette.Warn)
                }
                if (b != null && b.pct <= 20) { Spacer(Modifier.height(8.dp)); Pill("Charge soon", Palette.Bad) }
            }
        }
        item {
            Row(Modifier.fadeUp(1), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AuraButton("Sync now", Glyph.Refresh, enabled = busy == null, modifier = Modifier.weight(1f)) { vm.sync() }
                val trackingOn = ring.tracking?.isOn == true
                AuraButton(if (trackingOn) "Re-check tracking" else "Turn on tracking", if (trackingOn) Glyph.Check else Glyph.Heart, filled = false, enabled = busy == null, modifier = Modifier.weight(1f)) { vm.enable() }
            }
        }

        item { Box(Modifier.fadeUp(3)) { TimeChart("Battery · last 24 hours", batteryPoints(detail), Palette.Good, "%", caption = "Includes the ring's own records, which arrive with each sync.") } }

        item {
            var showBackup by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().fadeUp(4).pressScale({ showBackup = true }), verticalAlignment = Alignment.CenterVertically) {
                AuraIcon(Glyph.Check, a.ink2, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Back up this ring's key", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Without it, a wiped phone means another factory reset", fontSize = 12.sp, color = a.ink3)
                }
                AuraIcon(Glyph.ChevronRight, a.ink3, size = 18.dp)
            }
            if (showBackup) KeyBackupDialog(ring, fresh = false, onClose = { showBackup = false }, onMessage = vm::toast)
        }
        item {
            var showExport by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().fadeUp(4).pressScale({ showExport = true }), verticalAlignment = Alignment.CenterVertically) {
                AuraIcon(Glyph.Plus, a.ink2, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Export your data", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Daily summary, sleep nights and workouts as CSV", fontSize = 12.sp, color = a.ink3)
                }
                AuraIcon(Glyph.ChevronRight, a.ink3, size = 18.dp)
            }
            if (showExport) ExportDialog(detail?.analysis, ring.row.serial, onClose = { showExport = false }, onMessage = vm::toast)
        }
        item {
            Row(Modifier.fillMaxWidth().fadeUp(4), verticalAlignment = Alignment.CenterVertically) {
                AuraIcon(Glyph.Code, a.ink2, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Developer mode", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Raw events, keys and the activity log", fontSize = 12.sp, color = a.ink3)
                }
                Switch(dev, vm::setDev)
            }
        }
        item {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var headsUp by remember { mutableStateOf(com.aashish.myrings.service.HealthNotifier.enabled(ctx)) }
            Row(Modifier.fillMaxWidth().fadeUp(4), verticalAlignment = Alignment.CenterVertically) {
                AuraIcon(Glyph.Alert, a.ink2, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Health heads-ups", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("A notification if your heart rate doesn't settle after activity, or last night's heart rate or temperature was off your normal", fontSize = 12.sp, color = a.ink3)
                }
                Switch(headsUp, { headsUp = it; com.aashish.myrings.service.HealthNotifier.setEnabled(ctx, it) })
            }
            if (dev) {
                Spacer(Modifier.height(10.dp))
                AuraButton("Send a test notification", Glyph.Alert, filled = false, modifier = Modifier.fillMaxWidth()) {
                    if (!com.aashish.myrings.service.HealthNotifier.sendTest(ctx)) vm.toast("Notifications are blocked for My Rings. Allow them in Android settings.")
                }
            }
        }
        if (dev) item { DevPanel(vm, ring) }
        item { Box(Modifier.fadeUp(5)) { AuraButton("Add another ring", Glyph.Plus, filled = false, modifier = Modifier.fillMaxWidth()) { onAdd() } } }
        // Ring health sits last. Everyone sees battery drain; firmware resets, timing glitches and radio stats are developer-only.
        val warnings = detail?.analysis?.warnings.orEmpty().filter { dev || !it.devOnly }
        if (warnings.isNotEmpty()) {
            item {
                Column(Modifier.fadeUp(6), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader("Ring health")
                    warnings.forEach { w ->
                        val c = when (w.level) { "bad" -> Palette.Bad; "warn" -> Palette.Warn; else -> Palette.Good }
                        Row(verticalAlignment = Alignment.Top) {
                            AuraIcon(if (w.level == "good") Glyph.Check else Glyph.Alert, c, Modifier.padding(top = 2.dp), size = 18.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(w.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(w.detail, fontSize = 12.5.sp, color = a.ink2, lineHeight = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    if (renaming) RenameDialog(ring.row.name, onDismiss = { renaming = false }) { vm.rename(it); renaming = false }
}


/** A two-option pill switch whose highlight glides between the options. */
@Composable
private fun SegmentedToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val a = MaterialTheme.aura
    val primary = MaterialTheme.colorScheme.primary
    val segW = 84.dp
    val offset by androidx.compose.animation.core.animateDpAsState(segW * selected, androidx.compose.animation.core.spring(0.8f, 420f), label = "seg")
    Box(Modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(3.dp)) {
        Box(Modifier.offset(x = offset).width(segW).height(34.dp).clip(RoundedCornerShape(19.dp)).background(primary.copy(alpha = if (a.isDark) 0.22f else 0.16f)))
        Row {
            options.forEachIndexed { i, label ->
                Box(Modifier.width(segW).height(34.dp).clip(RoundedCornerShape(19.dp)).pressScale({ onSelect(i) }), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (i == selected) primary else a.ink2)
                }
            }
        }
    }
}
