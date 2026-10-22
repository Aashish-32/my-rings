package com.aashish.myrings.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aashish.myrings.protocol.RingEvent
import com.aashish.myrings.protocol.toJson

data class RingRow(val serial: String, val name: String, val address: String?, val keyHex: String, val firmware: String?, val lastSync: Long?)
data class BatterySample(val ts: Long, val pct: Int)
/** What the ring last reported for its measurement modes (0 = off, 1 = automatic). */
data class TrackingState(val hrMode: Int?, val spo2Mode: Int?, val ts: Long) {
    val isOn get() = hrMode == 1 && spo2Mode == 1
    val isOff get() = (hrMode ?: 0) == 0 && (spo2Mode ?: 0) == 0
    fun modeName(m: Int?) = when (m) { null -> "unknown"; 0 -> "off"; 1 -> "automatic"; else -> "mode $m" }
}
data class StoredEvent(val id: Long, val name: String, val tag: Int, val ringTs: Long, val decodedJson: String?, val bodyLen: Int, val hexHead: String)

/**
 * Local SQLite store. The events / device / sync_state tables intentionally mirror the PC client's
 * oura.db, so a database from either side can be read by the other.
 */
class Store(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "myrings.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table rings (serial text primary key, name text not null, address text, key_hex text not null, added_unix integer not null)")
        db.execSQL("create table device (serial text primary key, hardware_id text, firmware text, api_version text, mac text, updated_unix integer not null)")
        db.execSQL("create table sync_state (serial text primary key, next_cursor integer not null, last_sync_unix integer not null)")
        db.execSQL(
            "create table events (id integer primary key autoincrement, serial text not null, tag integer not null, name text not null, " +
                "ring_timestamp integer not null, body blob not null, decoded_json text, captured_unix integer not null, " +
                "unique(serial, tag, ring_timestamp, body))"
        )
        db.execSQL("create index events_name on events (serial, name, ring_timestamp)")
        db.execSQL("create table battery (serial text not null, pct integer not null, ts integer not null)")
        db.execSQL("create index battery_idx on battery (serial, ts)")
        onUpgrade(db, 1, 5)
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        // v2: per-sync-session lookups (time-sync anchoring) and windowed analytics scans
        if (o < 2) {
            db.execSQL("create index if not exists events_capture on events (serial, captured_unix, ring_timestamp)")
            db.execSQL("create index if not exists events_ts on events (serial, ring_timestamp)")
        }
        // v3: remember what the ring last said about heart-rate / SpO2 tracking, so the UI can show a real status
        if (o < 5) db.execSQL("create table if not exists meta (key text primary key, value text not null)")
        if (o < 3) db.execSQL("create table if not exists tracking (serial text primary key, hr_mode integer, spo2_mode integer, ts integer not null)")
        // v4: the wearer's body profile, per ring (used for calories, BMR and age-aware sleep scoring)
        if (o < 4) db.execSQL("create table if not exists profile (serial text primary key, weight_kg real, height_cm real, birth_year integer, sex integer)")
    }

    private val now get() = System.currentTimeMillis() / 1000

    // ---- rings ----
    @Synchronized fun rings(): List<RingRow> = readableDatabase.rawQuery(
        "select r.serial, r.name, r.address, r.key_hex, d.firmware, nullif(s.last_sync_unix, 0) from rings r " +
            "left join device d on d.serial = r.serial left join sync_state s on s.serial = r.serial order by r.added_unix", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                RingRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), if (c.isNull(5)) null else c.getLong(5))
            )
        }
    }

    @Synchronized fun upsertRing(serial: String, name: String, address: String?, keyHex: String) {
        val v = ContentValues().apply { put("serial", serial); put("name", name); put("address", address); put("key_hex", keyHex); put("added_unix", now) }
        writableDatabase.insertWithOnConflict("rings", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun renameRing(serial: String, name: String) {
        writableDatabase.update("rings", ContentValues().apply { put("name", name) }, "serial=?", arrayOf(serial))
    }

    @Synchronized fun setAddress(serial: String, address: String) {
        writableDatabase.update("rings", ContentValues().apply { put("address", address) }, "serial=?", arrayOf(serial))
    }

    @Synchronized fun deleteRing(serial: String) {
        val db = writableDatabase
        for (t in listOf("rings", "device", "sync_state", "events", "battery", "tracking", "profile")) db.delete(t, "serial=?", arrayOf(serial))
    }

    // ---- body profile ----
    @Synchronized fun profile(serial: String): Profile =
        readableDatabase.rawQuery("select weight_kg, height_cm, birth_year, sex from profile where serial=?", arrayOf(serial)).use { c ->
            if (!c.moveToFirst()) Profile() else Profile(
                if (c.isNull(0)) null else c.getDouble(0), if (c.isNull(1)) null else c.getDouble(1),
                if (c.isNull(2)) null else c.getInt(2), Sex.from(if (c.isNull(3)) null else c.getInt(3)),
            )
        }

    @Synchronized fun saveProfile(serial: String, p: Profile) {
        val v = ContentValues().apply {
            put("serial", serial); put("weight_kg", p.weightKg); put("height_cm", p.heightCm); put("birth_year", p.birthYear); put("sex", p.sex.code)
        }
        writableDatabase.insertWithOnConflict("profile", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- small key/value settings kept with the data (decoder version, re-decode progress, ...) ----
    @Synchronized fun meta(key: String): String? =
        readableDatabase.rawQuery("select value from meta where key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Re-decodes one batch of stored events (ids above [afterId]) with [decode] and rewrites the stored JSON where it changed.
     * Returns the last id handled and how many rows changed, or null when there is nothing left.
     */
    @Synchronized fun redecodeBatch(afterId: Long, limit: Int, decode: (tag: Int, body: ByteArray) -> String?): Pair<Long, Int>? {
        val db = writableDatabase
        val rows = ArrayList<Triple<Long, Int, ByteArray>>()
        val old = HashMap<Long, String?>()
        db.rawQuery("select id, tag, body, decoded_json from events where id > ? order by id limit ?", arrayOf(afterId.toString(), limit.toString())).use { c ->
            while (c.moveToNext()) { rows += Triple(c.getLong(0), c.getInt(1), c.getBlob(2)); old[c.getLong(0)] = c.getString(3) }
        }
        if (rows.isEmpty()) return null
        var changed = 0
        db.beginTransaction()
        try {
            for ((id, tag, body) in rows) {
                val json = decode(tag, body)
                if (json != old[id]) {
                    db.execSQL("update events set decoded_json = ? where id = ?", arrayOf<Any?>(json, id))
                    changed++
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return rows.last().first to changed
    }

    // ---- health tracking status ----
    @Synchronized fun setTracking(serial: String, hrMode: Int?, spo2Mode: Int?) {
        val v = ContentValues().apply { put("serial", serial); put("hr_mode", hrMode); put("spo2_mode", spo2Mode); put("ts", now) }
        writableDatabase.insertWithOnConflict("tracking", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun tracking(serial: String): TrackingState? =
        readableDatabase.rawQuery("select hr_mode, spo2_mode, ts from tracking where serial=?", arrayOf(serial)).use {
            if (it.moveToFirst()) TrackingState(if (it.isNull(0)) null else it.getInt(0), if (it.isNull(1)) null else it.getInt(1), it.getLong(2)) else null
        }

    // ---- battery ----
    @Synchronized fun addBattery(serial: String, pct: Int) {
        writableDatabase.insert("battery", null, ContentValues().apply { put("serial", serial); put("pct", pct); put("ts", now) })
        writableDatabase.execSQL("delete from battery where serial=? and ts < ?", arrayOf(serial, now - 14 * 86400))
    }

    @Synchronized fun latestBattery(serial: String): BatterySample? =
        readableDatabase.rawQuery("select ts, pct from battery where serial=? order by ts desc limit 1", arrayOf(serial)).use {
            if (it.moveToFirst()) BatterySample(it.getLong(0), it.getInt(1)) else null
        }

    @Synchronized fun batteryHistory(serial: String, hours: Int = 24): List<BatterySample> =
        readableDatabase.rawQuery("select ts, pct from battery where serial=? and ts>=? order by ts", arrayOf(serial, (now - hours * 3600L).toString())).use {
            buildList { while (it.moveToNext()) add(BatterySample(it.getLong(0), it.getInt(1))) }
        }

    // ---- device + sync ----
    @Synchronized fun upsertDevice(serial: String, hardware: String?, firmware: String?, api: String?, mac: String?) {
        val v = ContentValues().apply {
            put("serial", serial); put("hardware_id", hardware); put("firmware", firmware); put("api_version", api); put("mac", mac); put("updated_unix", now)
        }
        writableDatabase.insertWithOnConflict("device", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun cursor(serial: String): Long =
        readableDatabase.rawQuery("select next_cursor from sync_state where serial=?", arrayOf(serial)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized fun deviceInfo(serial: String): Map<String, String?> =
        readableDatabase.rawQuery("select hardware_id, firmware, api_version, mac from device where serial=?", arrayOf(serial)).use {
            if (it.moveToFirst()) mapOf("hardware" to it.getString(0), "firmware" to it.getString(1), "api" to it.getString(2), "mac" to it.getString(3)) else emptyMap()
        }

    /** Insert a batch and advance the cursor in ONE transaction, so a crash never skips stored events. */
    @Synchronized fun commitBatch(serial: String, events: List<RingEvent>, nextCursor: Long): Int {
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            for (e in events) {
                val v = ContentValues().apply {
                    put("serial", serial); put("tag", e.tag); put("name", e.name); put("ring_timestamp", e.timestamp)
                    put("body", e.body); put("decoded_json", e.decoded?.let { toJson(it) }); put("captured_unix", now)
                }
                if (db.insertWithOnConflict("events", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L) inserted++
            }
            // moves the cursor only: a saved batch is not a finished sync, so a sync that fails later must not look like it succeeded.
            // touchSync() sets the sync time once the whole drain is done. 0 means "never" (shown as such by rings()).
            db.execSQL(
                "insert into sync_state(serial, next_cursor, last_sync_unix) values(?,?,0) on conflict(serial) do update set next_cursor=excluded.next_cursor",
                arrayOf(serial, nextCursor)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    @Synchronized fun touchSync(serial: String) {
        writableDatabase.execSQL(
            "insert into sync_state(serial, next_cursor, last_sync_unix) values(?,?,?) on conflict(serial) do update set last_sync_unix=excluded.last_sync_unix",
            arrayOf(serial, cursor(serial), now)
        )
    }

    // ---- reading events ----
    @Synchronized fun eventCounts(serial: String): List<Pair<String, Int>> =
        readableDatabase.rawQuery("select name, count(*) from events where serial=? group by name order by 2 desc", arrayOf(serial)).use {
            buildList { while (it.moveToNext()) add(it.getString(0) to it.getInt(1)) }
        }

    @Synchronized fun recentEvents(serial: String, name: String?, limit: Int): List<StoredEvent> {
        val args = if (name.isNullOrEmpty()) arrayOf(serial) else arrayOf(serial, name)
        val where = if (name.isNullOrEmpty()) "" else " and name=?"
        return readableDatabase.rawQuery(
            "select id, name, tag, ring_timestamp, decoded_json, length(body), hex(substr(body,1,24)) from events where serial=?$where " +
                "order by ring_timestamp desc, id desc limit $limit", args
        ).use { c -> buildList { while (c.moveToNext()) add(StoredEvent(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3), c.getString(4), c.getInt(5), c.getString(6))) } }
    }

    /** Cheap change detector for caching derived data: (max id, row count). */
    @Synchronized fun eventStamp(serial: String): Pair<Long, Long> =
        readableDatabase.rawQuery("select coalesce(max(id),0), count(*) from events where serial=?", arrayOf(serial)).use { it.moveToFirst(); it.getLong(0) to it.getLong(1) }

    /** Streams stored events at or after ring time [fromTs] in ring-time order: (tag, name, ring_timestamp, body, decoded_json). */
    @Synchronized fun forEachEvent(serial: String, fromTs: Long = 0, block: (tag: Int, name: String, ts: Long, body: ByteArray, json: String?) -> Unit) {
        readableDatabase.rawQuery(
            "select tag, name, ring_timestamp, body, decoded_json from events where serial=? and ring_timestamp>=? order by ring_timestamp, id",
            arrayOf(serial, fromTs.toString())
        ).use { c -> while (c.moveToNext()) block(c.getInt(0), c.getString(1), c.getLong(2), c.getBlob(3), c.getString(4)) }
    }

    @Synchronized fun newestRingTs(serial: String): Long =
        readableDatabase.rawQuery("select coalesce(max(ring_timestamp),0) from events where serial=?", arrayOf(serial)).use { it.moveToFirst(); it.getLong(0) }

    class TimeSyncRow(val ringTs: Long, val body: ByteArray, val json: String?, val captured: Long, val newestInCapture: Long)

    /**
     * Every time_sync event, with the capture time of the sync session that stored it and that session's newest
     * event. A session stamps all its events with one captured_unix taken just after the drain, so
     * `captured - (newest - ringTs)/10` estimates when the sync really happened (to within seconds).
     */
    @Synchronized fun timeSyncs(serial: String): List<TimeSyncRow> =
        readableDatabase.rawQuery(
            "select e.ring_timestamp, e.body, e.decoded_json, e.captured_unix, " +
                "(select max(x.ring_timestamp) from events x where x.serial=e.serial and x.captured_unix=e.captured_unix) " +
                "from events e where e.serial=? and e.name='time_sync' order by e.ring_timestamp", arrayOf(serial)
        ).use { c -> buildList { while (c.moveToNext()) add(TimeSyncRow(c.getLong(0), c.getBlob(1), c.getString(2), c.getLong(3), c.getLong(4))) } }

    /** (ring_timestamp, name, decoded_json) for the events the dashboard summarises. */
    @Synchronized fun analyticsRows(serial: String): List<Triple<Long, String, String>> =
        readableDatabase.rawQuery(
            "select ring_timestamp, name, decoded_json from events where serial=? and decoded_json is not null and name in " +
                "('hrv_event','temp_event','sleep_temp_event','spo2_event','spo2_smoothed_event','ibi_and_amplitude_event'," +
                "'green_ibi_quality_event','sleep_phase_data','sleep_phase_information')", arrayOf(serial)
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getString(2))) } }

    @Synchronized fun latestTimeSync(serial: String): Pair<Long, Long>? =
        readableDatabase.rawQuery("select ring_timestamp, decoded_json from events where serial=? and name='time_sync' order by ring_timestamp desc limit 1", arrayOf(serial)).use {
            if (!it.moveToFirst()) null else {
                val unix = Regex("\"unix_time\":(\\d+)").find(it.getString(1))?.groupValues?.get(1)?.toLongOrNull()
                if (unix == null) null else it.getLong(0) to unix
            }
        }
}
