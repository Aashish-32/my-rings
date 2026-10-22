package com.aashish.myrings.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aashish.myrings.data.*
import com.aashish.myrings.service.RingManager
import com.aashish.myrings.service.RingUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Detail(
    val analysis: Analysis,
    val batteryHistory: List<BatterySample>,
    val counts: List<Pair<String, Int>>,
    val events: List<StoredEvent>,
    val device: Map<String, String?>,
)

data class PairState(val running: Boolean = false, val status: String = "", val error: String? = null, val done: Boolean = false)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val mgr = RingManager.get(app)
    private val prefs = app.getSharedPreferences("ui", 0)

    val rings = mgr.rings
    val busy = mgr.busy
    val log = mgr.log
    val messages = mgr.messages

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected

    private val _dev = MutableStateFlow(prefs.getBoolean("dev", false))
    val dev: StateFlow<Boolean> = _dev

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter

    private val _detail = MutableStateFlow<Detail?>(null)
    val detail: StateFlow<Detail?> = _detail

    private val _pair = MutableStateFlow(PairState())
    val pair: StateFlow<PairState> = _pair

    /** Serial of a ring that was just paired and should be offered a key backup. */
    private val _backupPrompt = MutableStateFlow<String?>(null)
    val backupPrompt: StateFlow<String?> = _backupPrompt
    fun dismissBackupPrompt() { _backupPrompt.value = null }

    private val _calm = MutableStateFlow(CalmState())
    val calm: StateFlow<CalmState> = _calm
    private var calmJob: kotlinx.coroutines.Job? = null

    private val _measure = MutableStateFlow(MeasureState())
    val measure: StateFlow<MeasureState> = _measure
    private var measureJob: kotlinx.coroutines.Job? = null

    private val _snack = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snack: SharedFlow<String> = _snack

    init {
        viewModelScope.launch {
            // keep a valid selection and refresh detail whenever rings / selection / dev / filter change
            combine(rings, _selected, _dev, _filter) { r, s, d, f -> arrayOf(r, s, d, f) }.collect { arr ->
                @Suppress("UNCHECKED_CAST") val list = arr[0] as List<RingUi>
                var sel = arr[1] as String?
                if (sel == null || list.none { it.row.serial == sel }) { sel = list.firstOrNull()?.row?.serial; _selected.value = sel }
                loadDetail(sel)
            }
        }
    }

    private suspend fun loadDetail(serial: String?) {
        if (serial == null) { _detail.value = null; return }
        val dev = _dev.value
        val f = _filter.value
        _detail.value = withContext(Dispatchers.IO) {
            Detail(
                analysis = Analytics.analyze(mgr.store, serial),
                batteryHistory = mgr.store.batteryHistory(serial, 24),
                counts = if (dev) mgr.store.eventCounts(serial) else emptyList(),
                events = if (dev) mgr.store.recentEvents(serial, f.ifEmpty { null }, 60) else emptyList(),
                device = mgr.store.deviceInfo(serial),
            )
        }
    }

    fun toast(message: String) { _snack.tryEmit(message) }

    /** Live heart rate (and HRV) for up to 45 s on the selected ring. */
    fun startMeasure() {
        val serial = _selected.value ?: return
        measureJob?.cancel()
        val total = 45
        _measure.value = MeasureState(open = true, running = true, stage = MeasureStage.CONNECTING, secondsLeft = total, totalSeconds = total)
        measureJob = viewModelScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            var readyAt = 0L
            val ibis = ArrayList<Int>()
            val meter = com.aashish.myrings.data.MotionMeter()
            val ticker = launch {
                while (true) {
                    kotlinx.coroutines.delay(250)
                    val s = _measure.value
                    val left = (total - (System.currentTimeMillis() - started) / 1000).toInt().coerceAtLeast(0)
                    // the wait gets honest progress: connected -> sensor waking -> looking for the pulse
                    val stage = when {
                        s.stage == MeasureStage.LOCKED -> MeasureStage.LOCKED
                        readyAt == 0L -> MeasureStage.CONNECTING
                        System.currentTimeMillis() - readyAt < 2500 -> MeasureStage.WAKING
                        else -> MeasureStage.FINDING
                    }
                    _measure.value = s.copy(secondsLeft = left, stage = stage)
                }
            }
            try {
                mgr.measureNow(serial, total, onReady = { readyAt = System.currentTimeMillis() }, onMotion = { x, y, z ->
                    meter.add(x, y, z)
                    val lv = meter.level()
                    if (lv != _measure.value.motion) _measure.value = _measure.value.copy(motion = lv)
                }) { beat ->
                    // beats taken while the hand is moving are unreliable (the light sensor shakes): show them as discarded, don't average them in
                    if (meter.level() == com.aashish.myrings.data.MotionLevel.MOVING) {
                        _measure.value = _measure.value.copy(discarded = _measure.value.discarded + 1)
                        return@measureNow
                    }
                    val beats = (_measure.value.beats + beat.bpm).takeLast(60)
                    val last3 = beats.takeLast(3).sorted()
                    val smooth = last3[last3.size / 2] // median of the last few beats steadies the number
                    ibis += beat.ibiMs
                    _measure.value = _measure.value.copy(
                        stage = MeasureStage.LOCKED, bpm = smooth, beats = beats,
                        beatCount = _measure.value.beatCount + 1, hrv = liveRmssd(ibis),
                    )
                }
                val b = _measure.value.beats
                _measure.value = _measure.value.copy(
                    running = false,
                    message = if (b.size >= 5) "Average ${b.average().toInt()} bpm over ${b.size} beats" else "Not enough beats. Wear the ring snugly on your finger and hold still, then try again.",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                _measure.value = _measure.value.copy(running = false, message = "Stopped")
                throw e
            } catch (e: Exception) {
                _measure.value = _measure.value.copy(running = false, message = mgr.friendly(e))
            } finally { ticker.cancel() }
        }
    }

    /** RMSSD of the recent beat-to-beat intervals (ms), skipping jumps over 25 % that are usually missed or doubled beats. Needs 8 beats. */
    private fun liveRmssd(ibis: List<Int>): Int? {
        val r = ibis.takeLast(30)
        val diffs = r.zipWithNext().filter { (a, b) -> kotlin.math.abs(b - a) <= 0.25 * a }.map { (a, b) -> (b - a).toDouble() }
        if (r.size < 8 || diffs.size < 6) return null
        return kotlin.math.sqrt(diffs.sumOf { it * it } / diffs.size).roundToInt()
    }

    /** A guided one-minute slow-breathing session read through the live heart-rate stream (see [com.aashish.myrings.data.CalmSession]). */
    fun startCalm() {
        val serial = _selected.value ?: return
        calmJob?.cancel()
        _calm.value = CalmState(open = true, running = true)
        calmJob = viewModelScope.launch(Dispatchers.IO) {
            val beats = ArrayList<com.aashish.myrings.data.CalmBeat>()
            val recentIbis = ArrayList<Int>()
            val meter = com.aashish.myrings.data.MotionMeter()
            val started = System.currentTimeMillis()
            var readyAt = 0L
            var lockAt = 0L
            var finished = false
            val live = launch {
                try {
                    mgr.measureNow(serial, 90, onReady = { readyAt = System.currentTimeMillis() }, onMotion = { x, y, z ->
                        meter.add(x, y, z)
                        val lv = meter.level()
                        if (lv != _calm.value.motion) _calm.value = _calm.value.copy(motion = lv)
                    }) { beat ->
                        if (meter.level() == com.aashish.myrings.data.MotionLevel.MOVING) return@measureNow // a moving hand spoils the beat
                        val now = System.currentTimeMillis()
                        if (lockAt == 0L) { lockAt = now; _calm.value = _calm.value.copy(lockedAtMs = now, stage = MeasureStage.LOCKED) }
                        beats += com.aashish.myrings.data.CalmBeat(now - lockAt, beat.ibiMs)
                        recentIbis += beat.ibiMs
                        val window = recentIbis.takeLast(20)
                        val hrv = com.aashish.myrings.data.CalmSession.rmssd(window)?.roundToInt()
                        val last3 = window.takeLast(3).map { 60000 / it }.sorted()
                        _calm.value = _calm.value.copy(
                            bpm = last3[last3.size / 2], hrv = hrv ?: _calm.value.hrv,
                            hrvTrend = if (hrv != null) (_calm.value.hrvTrend + hrv).takeLast(40) else _calm.value.hrvTrend,
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!finished) { finished = true; _calm.value = _calm.value.copy(running = false, message = mgr.friendly(e)) }
                }
            }
            while (live.isActive) {
                kotlinx.coroutines.delay(250)
                val now = System.currentTimeMillis()
                if (lockAt == 0L) {
                    val stage = when {
                        readyAt == 0L -> MeasureStage.CONNECTING
                        now - readyAt < 2500 -> MeasureStage.WAKING
                        else -> MeasureStage.FINDING
                    }
                    _calm.value = _calm.value.copy(stage = stage)
                    if (now - started > 45_000) { // no pulse found
                        finished = true
                        _calm.value = _calm.value.copy(running = false, message = "Couldn't find your pulse. Wear the ring snugly on your finger and try again.")
                        live.cancelAndJoin(); break
                    }
                } else {
                    val left = (com.aashish.myrings.data.CalmSession.SESSION_S - (now - lockAt) / 1000).toInt().coerceAtLeast(0)
                    _calm.value = _calm.value.copy(secondsLeft = left)
                    if (left <= 0) {
                        finished = true
                        _calm.value = _calm.value.copy(running = false, result = com.aashish.myrings.data.CalmSession.summarize(beats))
                        live.cancelAndJoin(); break
                    }
                }
            }
        }
    }

    fun stopCalm() { calmJob?.cancel(); _calm.value = _calm.value.copy(running = false, message = "Stopped") }
    fun closeCalm() { calmJob?.cancel(); _calm.value = CalmState() }

    fun stopMeasure() { measureJob?.cancel() }
    fun closeMeasure() { measureJob?.cancel(); _measure.value = MeasureState() }
    fun select(serial: String) { _selected.value = serial }
    fun setFilter(f: String) { _filter.value = f }
    fun setDev(v: Boolean) { prefs.edit().putBoolean("dev", v).apply(); _dev.value = v }

    private fun run(label: String, ok: String, block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block(); _snack.emit(ok) } catch (e: Exception) { _snack.emit(mgr.friendly(e)) }
        mgr.reload()
    }

    fun sync() {
        val s = _selected.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // "synced" must mean data arrived, not just that the command finished
                val n = mgr.sync(s)
                _snack.emit(if (n > 0) "Synced — %,d new events".format(n) else "Connected, but the ring had no new events")
            } catch (e: Exception) { _snack.emit(mgr.friendly(e)) }
            mgr.reload()
        }
    }
    /** One button that does the right thing: turn tracking on if it isn't, otherwise just re-check. Reports the real result. */
    fun enable() {
        val s = _selected.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val on = mgr.rings.value.firstOrNull { it.row.serial == s }?.tracking?.isOn == true
                val t = if (on) mgr.checkTracking(s) else mgr.enableTracking(s)
                _snack.emit(
                    when {
                        t == null -> "Could not read the ring's tracking status"
                        t.isOn -> "Health tracking is ON: heart rate and SpO2 are measuring automatically"
                        else -> "Health tracking is NOT fully on (heart rate: ${t.modeName(t.hrMode)}, SpO2: ${t.modeName(t.spo2Mode)})"
                    }
                )
            } catch (e: Exception) { _snack.emit(mgr.friendly(e)) }
            mgr.reload()
        }
    }
    fun rename(name: String) { val s = _selected.value ?: return; mgr.rename(s, name.trim().take(40)) }
    fun remove() { val s = _selected.value ?: return; mgr.remove(s) }
    // Lifecycle syncs run in the manager's own scope, not viewModelScope: swiping the app away destroys the
    // ViewModel, and the background sync it starts must still finish (the foreground service keeps the process up).

    /** App came to the foreground: sync every ring (like the official app). Skipped if a sync is already running. */
    fun onForeground() { mgr.scope.launch { mgr.lifecycleSync("foreground") } }

    /** App went to the background: sync again so the data is current while the phone is in your pocket. */
    fun onBackground() { mgr.scope.launch { mgr.lifecycleSync("background") } }

    fun refreshBattery() { val s = _selected.value ?: return; viewModelScope.launch(Dispatchers.IO) { mgr.refreshBattery(s) } }

    fun startPair(name: String, keyHex: String?, adoptExisting: Boolean) {
        _pair.value = PairState(running = true, status = "Starting…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val onStatus = { s: String -> _pair.value = _pair.value.copy(status = s) }
                val serial = if (adoptExisting) mgr.adopt(name, keyHex ?: "", onStatus) else mgr.pair(name, keyHex, onStatus)
                _selected.value = serial
                _pair.value = PairState(done = true, status = "Ring added")
                if (!adoptExisting) _backupPrompt.value = serial // a fresh key exists only on this phone: offer a copy
                // first sync right away: it sets the ring's clock and timezone, which dates everything it records
                mgr.scope.launch { mgr.lifecycleSync("after pairing") }
            } catch (e: Exception) {
                _pair.value = PairState(error = if (e is IllegalArgumentException) e.message else mgr.friendly(e))
            }
        }
    }
    fun resetPair() { _pair.value = PairState() }

    /** The selected ring's wearer profile (what the user entered; empty if never set). */
    suspend fun loadProfile(): Profile? = _selected.value?.let { s -> withContext(Dispatchers.IO) { mgr.store.profile(s) } }

    /** True once the user chose "Not now" on the home-screen profile prompt for the selected ring. */
    fun profilePromptDismissed(): Boolean = _selected.value?.let { prefs.getBoolean("profilePromptDismissed:$it", false) } ?: true
    fun dismissProfilePrompt() { _selected.value?.let { prefs.edit().putBoolean("profilePromptDismissed:$it", true).apply() } }

    /** Save the wearer profile and recompute calories and scores with it. */
    fun saveProfile(p: Profile) {
        val s = _selected.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            mgr.store.saveProfile(s, p)
            loadDetail(s)
            _snack.emit("Profile saved — calories and scores updated")
        }
    }
}
