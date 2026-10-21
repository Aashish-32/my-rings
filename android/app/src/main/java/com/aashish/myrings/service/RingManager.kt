package com.aashish.myrings.service

import android.annotation.SuppressLint
import android.content.Context
import com.aashish.myrings.ble.*
import com.aashish.myrings.data.*
import com.aashish.myrings.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.security.SecureRandom
import java.util.UUID

data class RingUi(val row: RingRow, val battery: BatterySample?, val tracking: TrackingState? = null)

/** Owns all ring I/O. Bluetooth is exclusive, so every operation runs under one mutex. */
@SuppressLint("MissingPermission")
class RingManager private constructor(private val app: Context) {
    val store = Store(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bleLock = Mutex()

    private val _rings = MutableStateFlow<List<RingUi>>(emptyList())
    val rings: StateFlow<List<RingUi>> = _rings
    private val _busy = MutableStateFlow<String?>(null)
    /** Name of the operation currently holding Bluetooth (for the UI), or null. */
    val busy: StateFlow<String?> = _busy
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    init { reload() }

    fun reload() {
        _rings.value = store.rings().map { RingUi(it, store.latestBattery(it.serial), store.tracking(it.serial)) }
    }

    private fun logLine(s: String) { _log.value = (s + "\n" + _log.value).take(6000) }

    fun friendly(e: Throwable): String = when {
        e is AuthException -> e.message ?: "Authentication failed"
        e is BleException && e.message?.contains("timed out") == true -> "Ring not in range — bring it close to the phone."
        e is BleException -> e.message ?: "Bluetooth error"
        e is ProtocolException -> e.message ?: "Protocol error"
        e is TimeoutCancellationException -> "Timed out talking to the ring."
        else -> e.message ?: e.javaClass.simpleName
    }

    private suspend fun <T> withRing(row: RingRow, op: String, block: suspend (OuraClient) -> T): T {
        val address = row.address ?: throw BleException("this ring has no Bluetooth address yet — re-add it")
        val key = row.keyHex.hexToBytes()
        _busy.value = op
        try {
            // The ring keeps a stale link to its previous host (e.g. the PC) for a while; connecting during that
            // window fails. Retrying after a short wait clears it without the user toggling Bluetooth.
            var t: GattTransport? = null
            var last: BleException? = null
            for (attempt in 1..3) {
                try { t = GattTransport.connect(app, address); break } catch (e: BleException) {
                    last = e
                    if (attempt < 3) { _busy.value = "$op (retrying)"; delay(5_000) }
                }
            }
            if (t == null) throw last ?: BleException("could not connect")
            try {
                val client = OuraClient(t)
                client.authenticate(key)
                return block(client)
            } finally { t.close() }
        } finally { _busy.value = null }
    }

    /** Run [block] if Bluetooth is free; returns null (without waiting) when another operation holds it. */
    private suspend fun <T> exclusive(wait: Boolean, block: suspend () -> T): T? {
        if (!wait && !bleLock.tryLock()) return null
        if (wait) bleLock.lock()
        try { return block() } finally { bleLock.unlock() }
    }

    // ---- battery ----
    /** Quick battery read. Returns true on success, false on failure, null if Bluetooth was busy (nothing attempted). */
    suspend fun refreshBattery(serial: String): Boolean? {
        val row = store.rings().firstOrNull { it.serial == serial } ?: return false
        return exclusive(wait = false) {
            try {
                withRing(row, "Reading battery") { c ->
                    val b = c.battery()
                    store.addBattery(serial, b.percent)
                }
                reload(); true
            } catch (e: Exception) { logLine("battery ${row.name}: ${friendly(e)}"); false }
        }
    }

    // ---- sync ----
    /** Full history sync. Returns number of new events, or throws a readable error. */
    suspend fun sync(serial: String): Int {
        val row = store.rings().first { it.serial == serial }
        return exclusive(wait = true) { syncLocked(row) }!!
    }

    /** Caller must hold [bleLock]. */
    private suspend fun syncLocked(row: RingRow): Int {
        val serial = row.serial
        return run {
            withRing(row, "Syncing") { c ->
                try { c.setupAppStream() } catch (e: Exception) { logLine("stream setup: ${e.message}") }
                try { c.syncTimeApp() } catch (e: Exception) { runCatching { c.syncTimeLegacy() } }
                runCatching { c.checkSleepAnalysis(false) }
                runCatching { readTracking(c, serial) }
                val info = runCatching { c.firmware() }.getOrNull()
                val hw = runCatching { c.hardwareId() }.getOrNull()
                store.upsertDevice(serial, hw, info?.firmware, info?.api, info?.mac)
                var inserted = 0
                val (total, cursor) = c.drainEvents(store.cursor(serial)) { events, p ->
                    inserted += store.commitBatch(serial, events, p.nextCursor)
                    _busy.value = "Syncing · ${p.eventsSynced} events"
                    true
                }
                store.touchSync(serial)
                runCatching { store.addBattery(serial, c.battery().percent) }
                logLine("sync ${row.name}: $total events ($inserted new), cursor $cursor")
                reload()
                inserted
            }
        }
    }

    /** Ask the ring which measurements are on, and remember the answer. Caller must be connected and authenticated. */
    private suspend fun readTracking(c: OuraClient, serial: String): TrackingState? {
        val hr = runCatching { c.featureStatus(Feature.DAYTIME_HR).mode }.getOrNull()
        val sp = runCatching { c.featureStatus(Feature.SPO2).mode }.getOrNull()
        if (hr == null && sp == null) return null
        store.setTracking(serial, hr, sp)
        return store.tracking(serial)
    }

    /** Switch heart-rate and SpO2 measurement on, then read back what the ring actually reports. */
    suspend fun enableTracking(serial: String): TrackingState? {
        val row = store.rings().first { it.serial == serial }
        val result = exclusive(wait = true) {
            withRing(row, "Enabling health tracking") { c ->
                c.setFeatureMode(Feature.DAYTIME_HR, FeatureMode.AUTOMATIC)
                c.setFeatureMode(Feature.SPO2, FeatureMode.AUTOMATIC)
                val t = readTracking(c, serial)
                logLine("tracking on ${row.name}: hr=${t?.modeName(t.hrMode)} spo2=${t?.modeName(t.spo2Mode)}")
                t
            }
        }
        reload()
        return result
    }

    /** Read-only: ask the ring which measurements are switched on. */
    suspend fun checkTracking(serial: String): TrackingState? {
        val row = store.rings().first { it.serial == serial }
        val result = exclusive(wait = true) { withRing(row, "Checking tracking") { c -> readTracking(c, serial) } }
        reload()
        return result
    }

    // ---- pairing ----
    /** Pairs the nearest Oura ring. [keyHex] reuses an existing 16-byte key (e.g. from the PC); null mints a new one. */
    suspend fun pair(name: String, keyHex: String?, onStatus: (String) -> Unit): String {
        val key: ByteArray = if (keyHex.isNullOrBlank()) newKey() else keyHex.trim().hexToBytes().also { require(it.size == 16) { "key must be 32 hex characters" } }
        return exclusive(wait = true) {
            _busy.value = "Pairing"
            try {
                onStatus("Looking for a ring…")
                val hits = GattTransport.scan(app, 15_000) { it.rssi > -75 }
                val hit = hits.firstOrNull() ?: throw BleException("no Oura ring found nearby (is it blinking blue on the charger?)")
                onStatus("Found a ring (${hit.rssi} dBm). Bonding…")
                val t = GattTransport.connect(app, hit.address)
                try {
                    val client = OuraClient(t)
                    val serial = client.serial()
                    // Persist the key BEFORE installing it, so a crash never loses the only copy of a live key.
                    store.upsertRing(serial, name, hit.address, key.toHex())
                    onStatus("Installing key on $serial…")
                    client.setAuthKey(key)
                    client.authenticate(key)
                    val info = runCatching { client.firmware() }.getOrNull()
                    runCatching { store.upsertDevice(serial, client.hardwareId(), info?.firmware, info?.api, info?.mac) }
                    runCatching { store.addBattery(serial, client.battery().percent) }
                    runCatching { client.setFeatureMode(Feature.DAYTIME_HR, FeatureMode.AUTOMATIC) }
                    runCatching { client.setFeatureMode(Feature.SPO2, FeatureMode.AUTOMATIC) }
                    runCatching { readTracking(client, serial) }
                    logLine("paired $serial")
                    reload()
                    serial
                } finally { t.close() }
            } finally { _busy.value = null }
        }!!
    }

    /** Add a ring that is ALREADY provisioned (e.g. paired from the PC) using its existing key. */
    suspend fun adopt(name: String, keyHex: String, onStatus: (String) -> Unit): String {
        val key = keyHex.trim().hexToBytes().also { require(it.size == 16) { "key must be 32 hex characters" } }
        return exclusive(wait = true) {
            _busy.value = "Connecting"
            try {
                onStatus("Looking for a ring…")
                val hit = GattTransport.scan(app, 15_000) { it.rssi > -75 }.firstOrNull() ?: throw BleException("no Oura ring found nearby")
                onStatus("Bonding…")
                val t = GattTransport.connect(app, hit.address)
                try {
                    val client = OuraClient(t)
                    val serial = client.serial()
                    client.authenticate(key)
                    store.upsertRing(serial, name, hit.address, key.toHex())
                    runCatching { store.addBattery(serial, client.battery().percent) }
                    reload()
                    serial
                } finally { t.close() }
            } finally { _busy.value = null }
        }!!
    }

    fun rename(serial: String, name: String) { store.renameRing(serial, name); reload() }
    fun remove(serial: String) { store.deleteRing(serial); reload() }

    private fun newKey(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    // ---- background loop ----
    private val failures = HashMap<String, Int>()
    private val nextTry = HashMap<String, Long>()

    /**
     * Periodic background work: a battery refresh, plus a background sync when the last one is older than
     * [SAFETY_SYNC_EVERY_MS]. The official app's data syncs are lifecycle-triggered (see [lifecycleSync]) but it also
     * keeps a live connection and syncs whenever the ring reconnects; we don't hold a connection (it costs ring
     * battery), so this periodic sync stands in for that, keeping scores fresh even if the app is never opened.
     */
    suspend fun backgroundPass(batteryEveryMs: Long = BATTERY_EVERY_MS, safetySyncEveryMs: Long = SAFETY_SYNC_EVERY_MS) {
        val now = System.currentTimeMillis()
        for (ui in store.rings()) {
            val serial = ui.serial
            if ((nextTry[serial] ?: 0L) > now) continue
            val needSafetySync = ui.lastSync == null || now - ui.lastSync * 1000 > safetySyncEveryMs
            val lastBattery = store.latestBattery(serial)?.ts
            val needBattery = lastBattery == null || now - lastBattery * 1000 > batteryEveryMs
            if (!needSafetySync && !needBattery) continue
            val ok: Boolean? = try {
                if (needSafetySync) exclusive(wait = false) { syncLocked(ui); true } else refreshBattery(serial)
            } catch (e: Exception) { logLine("background ${ui.name}: ${friendly(e)}"); false }
            if (ok == null) continue // Bluetooth busy with another operation; try again next pass
            if (ok) { failures[serial] = 0 } else {
                val n = (failures[serial] ?: 0) + 1
                failures[serial] = n
                // back off while a ring is out of range, so a missing ring doesn't drain the phone
                nextTry[serial] = now + minOf(60_000L shl minOf(n, 4), 600_000L)
            }
        }
        reload()
    }

    private val lastLifecycleSync = HashMap<String, Long>()

    /**
     * Sync triggered by the app lifecycle, like the official app: when the app comes to the foreground and when it
     * goes to the background. Skips a ring if any Bluetooth operation (including another sync) is already running,
     * and stays quiet when the ring is simply out of range.
     */
    suspend fun lifecycleSync(reason: String) {
        for (ring in store.rings()) {
            val now = System.currentTimeMillis()
            if (now - (lastLifecycleSync[ring.serial] ?: 0L) < MIN_GAP_BETWEEN_SYNCS_MS) continue // avoids open/close thrash
            val result: Int? = try {
                exclusive(wait = false) { syncLocked(ring) }
            } catch (e: Exception) { logLine("$reason sync ${ring.name}: ${friendly(e)}"); -1 }
            if (result == null) { logLine("$reason sync ${ring.name}: skipped, another operation is running"); continue }
            if (result >= 0) lastLifecycleSync[ring.serial] = System.currentTimeMillis()
        }
        reload()
    }

    /** Battery read when the app is opened, so the number is fresh without a full sync. No-op if recent or busy. */
    suspend fun refreshBatteryIfStale(maxAgeMs: Long = 2 * 60_000L) {
        val now = System.currentTimeMillis()
        for (r in store.rings()) {
            val last = store.latestBattery(r.serial)?.ts
            if (last == null || now - last * 1000 > maxAgeMs) refreshBattery(r.serial)
        }
    }

    companion object {
        const val BATTERY_EVERY_MS = 30 * 60_000L
        const val SAFETY_SYNC_EVERY_MS = 3 * 60 * 60_000L
        const val MIN_GAP_BETWEEN_SYNCS_MS = 30_000L
        @Volatile private var inst: RingManager? = null
        fun get(ctx: Context): RingManager = inst ?: synchronized(this) { inst ?: RingManager(ctx.applicationContext).also { inst = it } }
    }
}
