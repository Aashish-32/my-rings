package com.aashish.myrings.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aashish.myrings.data.*
import com.aashish.myrings.service.RingManager
import com.aashish.myrings.service.RingUi
import kotlinx.coroutines.Dispatchers
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
                // first sync right away: it sets the ring's clock and timezone, which dates everything it records
                mgr.scope.launch { mgr.lifecycleSync("after pairing") }
            } catch (e: Exception) {
                _pair.value = PairState(error = if (e is IllegalArgumentException) e.message else mgr.friendly(e))
            }
        }
    }
    fun resetPair() { _pair.value = PairState() }
}
