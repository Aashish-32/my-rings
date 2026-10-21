package com.aashish.myrings.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashish.myrings.data.Profile
import com.aashish.myrings.data.Sex
import com.aashish.myrings.data.UsedProfile
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * "Your body": the wearer profile for the selected ring. Stays on the phone (never written to the ring).
 * Shows what the numbers currently use and where each value came from.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileCard(vm: AppViewModel, used: UsedProfile?, startEditing: Boolean = false, onClose: (saved: Boolean) -> Unit = {}) {
    val a = MaterialTheme.aura
    val selected by vm.selected.collectAsState()
    var loaded by remember(selected) { mutableStateOf<Profile?>(null) }
    LaunchedEffect(selected) { loaded = vm.loadProfile() ?: Profile() }
    var editing by remember { mutableStateOf(startEditing) }

    Panel(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your body", style = AuraType.Title)
                Text("Used for calories and age-aware sleep scores. Stays on this phone.", fontSize = 12.sp, color = a.ink3)
            }
            if (!editing) TextButton(onClick = { editing = true }) { Text(if (loaded?.isEmpty != false) "Set up" else "Edit") }
        }
        Spacer(Modifier.height(10.dp))
        if (!editing || loaded == null) {
            if (used != null) {
                fun from(src: String) = when (src) { "you" -> ""; "ring" -> "  · from ring"; else -> "  · default" }
                Line("Weight", "%.1f kg".format(used.weightKg) + from(used.weightFrom))
                Line("Age", (used.age?.let { "$it" } ?: "not set") + from(used.ageFrom))
                Line("Sex", used.sex.label + from(used.sexFrom))
                loaded?.heightCm?.let { Line("Height", "${it.roundToInt()} cm") }
                Line("Resting burn (BMR)", "${used.bmrKcal} kcal/day")
                used.vo2max?.let { Line("VO₂ max estimate", "%.0f ml/kg/min".format(it)) }
                if (used.weightFrom == "default" || used.ageFrom == "default")
                    Text("Defaults are in use: set your weight and birth year for accurate calories.", fontSize = 12.sp, color = Palette.Warn, modifier = Modifier.padding(top = 6.dp))
                used.vo2max?.let {
                    Text("VO₂ max here comes only from age, sex and weight (the same formula Oura's engine uses); it doesn't measure fitness.",
                        fontSize = 11.sp, color = a.ink3, modifier = Modifier.padding(top = 4.dp))
                }
            } else Text("Sync the ring once to see what your numbers are based on.", fontSize = 13.sp, color = a.ink2)
        } else {
            val p = loaded!!
            var weight by remember { mutableStateOf(p.weightKg?.let { "%.1f".format(it).removeSuffix(".0") } ?: "") }
            var height by remember { mutableStateOf(p.heightCm?.roundToInt()?.toString() ?: "") }
            var birth by remember { mutableStateOf(p.birthYear?.toString() ?: "") }
            var sex by remember { mutableStateOf(p.sex) }
            val year = LocalDate.now().year
            val w = weight.replace(',', '.').toDoubleOrNull(); val h = height.toDoubleOrNull(); val by = birth.toIntOrNull()
            val wOk = weight.isBlank() || (w != null && w in 25.0..300.0)
            val hOk = height.isBlank() || (h != null && h in 100.0..250.0)
            val bOk = birth.isBlank() || (by != null && by in (year - 110)..(year - 10))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Weight (kg)", weight, wOk, Modifier.weight(1f)) { weight = it }
                NumberField("Height (cm)", height, hOk, Modifier.weight(1f)) { height = it }
            }
            Spacer(Modifier.height(8.dp))
            NumberField("Birth year", birth, bOk, Modifier.fillMaxWidth()) { birth = it.take(4) }
            Spacer(Modifier.height(10.dp))
            Text("Sex (for the metabolism and VO₂ max formulas)", fontSize = 12.sp, color = a.ink2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sex.entries.forEach { s -> FilterChip(selected = sex == s, onClick = { sex = s }, label = { Text(s.label) }) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AuraButton("Cancel", filled = false, modifier = Modifier.weight(1f)) { editing = false; onClose(false) }
                AuraButton("Save", enabled = wOk && hOk && bOk, modifier = Modifier.weight(1f)) {
                    val np = Profile(if (weight.isBlank()) null else w, if (height.isBlank()) null else h, if (birth.isBlank()) null else by, sex)
                    vm.saveProfile(np); loaded = np; editing = false; onClose(true)
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.aura.ink2)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NumberField(label: String, value: String, ok: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
        label = { Text(label) }, singleLine = true, isError = !ok, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/**
 * Home-screen nudge for a ring whose wearer profile is still empty (a fresh install or a newly added ring):
 * "Set up" opens the profile form in place; "Not now" hides it for that ring. Gone once a profile is saved.
 */
@Composable
fun ProfilePrompt(vm: AppViewModel, used: UsedProfile?) {
    val a = MaterialTheme.aura
    val selected by vm.selected.collectAsState()
    var show by remember(selected) { mutableStateOf(false) }
    LaunchedEffect(selected) { show = !vm.profilePromptDismissed() && vm.loadProfile()?.isEmpty == true }
    var form by remember(selected) { mutableStateOf(false) }
    if (!show) return
    if (form) {
        ProfileCard(vm, used, startEditing = true) { saved -> form = false; if (saved) show = false }
        return
    }
    Panel(Modifier.fillMaxWidth()) {
        Text("Personalize your numbers", style = AuraType.Title)
        Text("Add your weight, birth year and sex for accurate calories, resting burn and sleep targets for your age. It stays on this phone.",
            fontSize = 13.sp, color = a.ink2, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AuraButton("Not now", filled = false, modifier = Modifier.weight(1f)) { vm.dismissProfilePrompt(); show = false }
            AuraButton("Set up", modifier = Modifier.weight(1f)) { form = true }
        }
    }
}
