"""Local Oura Ring 4 control + dashboard server (stdlib only).

Wraps the open_oura `oura` CLI (the only thing that talks BLE) and reads the
shared SQLite store. Run:  python app/server.py   then open http://127.0.0.1:8765
"""
import bisect
import json
import os
import re
import sqlite3
import subprocess
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent.parent
OURA = Path(os.environ.get("OURA_BIN", r"D:\open_oura\target\release\oura.exe"))
DB = ROOT / "oura.db"
KEYS = ROOT / "keys"
REGISTRY = ROOT / "rings.json"
STATIC = Path(__file__).resolve().parent / "static"
PORT = 8765
APP_DB = ROOT / "app.db"  # app-owned data (battery history); oura.db stays the ring store
# Seconds between battery polls. Each poll is a full BLE connect + auth, which costs the
# ring real battery (at 60 s it was connecting ~50x/hour); the ring also records its own
# battery level, which every sync brings in, so a slow poll loses nothing.
BATTERY_INTERVAL = int(os.environ.get("BATTERY_INTERVAL", "1800"))

ble_lock = threading.Lock()  # BLE is exclusive: one ring operation at a time
jobs = {}
jobs_lock = threading.Lock()


# ---------- registry ----------
def load_rings():
    if REGISTRY.exists():
        return json.loads(REGISTRY.read_text())
    return {}


def save_rings(rings):
    REGISTRY.write_text(json.dumps(rings, indent=2))


def bootstrap_registry():
    """Adopt the key made by the first pairing, if present."""
    rings = load_rings()
    legacy = ROOT / "oura-ring4.key"
    if legacy.exists() and not rings:
        KEYS.mkdir(exist_ok=True)
        serial = None
        if DB.exists():
            c = sqlite3.connect(f"file:{DB.as_posix()}?mode=ro", uri=True)
            row = c.execute("select serial from device limit 1").fetchone()
            serial = row[0] if row else None
        if serial:
            dest = KEYS / f"oura-{serial}.key"
            if not dest.exists():
                dest.write_text(legacy.read_text())
            rings[serial] = {"name": "My Ring", "key_file": str(dest)}
            save_rings(rings)


# ---------- CLI jobs ----------
_last_auto = {}


def run_job(kind, serial, args, on_done=None, skip_if_busy=False):
    job_id = f"{int(time.time()*1000)}"
    job = {"id": job_id, "kind": kind, "serial": serial, "status": "queued", "log": ""}
    with jobs_lock:
        jobs[job_id] = job
    if skip_if_busy:
        # like the official app: never start a sync while another operation is running, and don't thrash
        # on rapid open/close (a 30 s gap per ring)
        if ble_lock.locked() or time.time() - _last_auto.get(serial, 0) < 30:
            job["status"], job["log"] = "skipped", "another operation is running or one just finished"
            return job
        _last_auto[serial] = time.time()

    # The ring talks to one device at a time and keeps a stale link to the previous one (e.g. the phone) for a
    # while; during that window connecting fails with "Not connected" / "no matching Oura ring". Retrying after a
    # short wait clears it without the user toggling Bluetooth. Pairing is never retried (it changes the ring).
    transient = ("Not connected", "no matching Oura ring", "connect timed out", "The device is not ready",
                 "characteristic not found")  # Windows sometimes reports a partial service list right after connecting
    attempts = 1 if kind == "pair" else 3

    def work():
        with ble_lock:
            job["status"] = "running"
            for attempt in range(1, attempts + 1):
                try:
                    p = subprocess.run(
                        [str(OURA), *args], capture_output=True, text=True,
                        timeout=600, cwd=str(ROOT), encoding="utf-8", errors="replace",
                    )
                    job["log"] = (p.stdout + p.stderr).strip()
                    job["status"] = "ok" if p.returncode == 0 else "error"
                except Exception as e:  # noqa: BLE001
                    job["log"] = f"{type(e).__name__}: {e}"
                    job["status"] = "error"
                if job["status"] == "ok" or attempt == attempts or not any(t in job["log"] for t in transient):
                    break
                job["status"] = f"running (retry {attempt})"
                time.sleep(6)
            if on_done:
                on_done(job)

    threading.Thread(target=work, daemon=True).start()
    return job


def ring_args(serial):
    ring = load_rings().get(serial)
    if not ring:
        raise KeyError(serial)
    return ["--name=", "--key-file", ring["key_file"], "--db", str(DB)]


# ---------- battery polling ----------
def app_db():
    c = sqlite3.connect(APP_DB)
    c.execute("create table if not exists battery (serial text not null, pct integer not null, ts integer not null)")
    c.execute("create index if not exists battery_idx on battery (serial, ts)")
    c.execute("create table if not exists tracking (serial text not null, hr text, spo2 text, resting text, exercise text, ts integer not null)")
    return c


_TRACK_RE = re.compile(r"^\s*(daytime HR|exercise HR|SpO2|resting HR):\s*mode=(\w+)", re.M)


def record_tracking(serial, log):
    """Remember the ring's measurement modes from `oura features` output (daytime HR, SpO2, ...)."""
    modes = {k: v for k, v in _TRACK_RE.findall(log or "")}
    if "daytime HR" not in modes and "SpO2" not in modes:
        return
    c = app_db()
    c.execute("insert into tracking values (?,?,?,?,?,?)",
              (serial, modes.get("daytime HR"), modes.get("SpO2"), modes.get("resting HR"), modes.get("exercise HR"), int(time.time())))
    c.commit()
    c.close()


def latest_tracking(serial):
    c = app_db()
    row = c.execute("select hr, spo2, resting, exercise, ts from tracking where serial=? order by ts desc limit 1", (serial,)).fetchone()
    c.close()
    if not row:
        return None
    on = row[0] == "automatic" and row[1] == "automatic"
    off = row[0] in (None, "off") and row[1] in (None, "off")
    return {"hr": row[0], "spo2": row[1], "resting": row[2], "exercise": row[3], "ts": row[4],
            "state": "on" if on else "off" if off else "partial"}


def record_battery(serial, pct):
    c = app_db()
    c.execute("insert into battery values (?,?,?)", (serial, pct, int(time.time())))
    c.commit()
    c.close()


def latest_battery(serial):
    c = app_db()
    row = c.execute("select pct, ts from battery where serial=? order by ts desc limit 1", (serial,)).fetchone()
    c.close()
    return {"pct": row[0], "ts": row[1]} if row else None


def battery_history(serial, hours=24):
    c = app_db()
    rows = c.execute("select ts, pct from battery where serial=? and ts>=? order by ts",
                     (serial, int(time.time()) - hours * 3600)).fetchall()
    c.close()
    return [{"ts": t, "pct": p} for t, p in rows]


def poll_battery_once():
    """Run `info` per ring. Skips if the ring is busy with sync/pair (never queues behind them)."""
    for serial in list(load_rings()):
        if not ble_lock.acquire(blocking=False):
            return
        try:
            p = subprocess.run(
                [str(OURA), *ring_args(serial)[:-2], "--scan-timeout", "6", "info"],
                capture_output=True, text=True, timeout=60, cwd=str(ROOT), encoding="utf-8", errors="replace")
            out = p.stdout
            s = re.search(r"Serial\s*:\s*(\w+)", out)
            b = re.search(r"Battery\s*:\s*(\d+)%", out)
            # only trust the reading if the ring that answered is the one we asked for
            if p.returncode == 0 and s and b and s[1] == serial:
                record_battery(serial, int(b[1]))
        except Exception:  # noqa: BLE001
            pass
        finally:
            ble_lock.release()


def battery_loop():
    while True:
        poll_battery_once()
        time.sleep(BATTERY_INTERVAL)


# ---------- analytics ----------
def db():
    c = sqlite3.connect(f"file:{DB.as_posix()}?mode=ro", uri=True)
    c.row_factory = sqlite3.Row
    return c


def corrected_sync_unix(body, unix, estimate=None):
    """Wall-clock time a `time_sync` event really marks.

    open_oura CLIs built before the time-sync fix sent `12 09 <token> <unix/256>
    00000000 f6`: a scrambled token, `(now % 256) * 37 + 0xa5`, where the low byte
    of the unix time belongs, and the ring echoes those bytes back as the event.
    Read raw, such an anchor is off by up to 255 s; undoing the token recovers the
    exact second. Fixed CLIs send the exact time with the same layout, so when
    [estimate] (the sync session's own capture time) is known, keep whichever
    reading is closer to it.
    """
    if len(body) != 9 or body[4:8] != b"\0\0\0\0":
        return unix
    low = ((body[0] - 0xA5) * 173) & 0xFF  # 173 = 37^-1 mod 256
    fixed = (unix & ~0xFF) | low
    if estimate is None:
        return fixed if body[8] == 0xF6 else unix  # every pre-fix sync ended in f6
    return min((unix, fixed), key=lambda u: abs(u - estimate))


def clock(c, serial):
    """Return f(ring_ts) -> unix seconds, anchored on the nearest earlier time_sync."""
    # A sync session stamps every event it drains with one captured_unix, taken just
    # after the drain; its newest event is a few seconds older than that.
    newest = dict(c.execute("select captured_unix, max(ring_timestamp) from events where serial=? "
                            "group by captured_unix", (serial,)).fetchall())
    anchors = []
    for r in c.execute("select ring_timestamp, body, decoded_json, captured_unix from events "
                       "where serial=? and name='time_sync' order by ring_timestamp", (serial,)):
        try:
            unix = json.loads(r["decoded_json"])["unix_time"]
        except (TypeError, ValueError, KeyError):
            continue
        cap, ts = r["captured_unix"], r["ring_timestamp"]
        est = cap - (newest[cap] - ts) / 10 if cap in newest else None
        anchors.append((ts, corrected_sync_unix(bytes(r["body"]), unix, est)))
    if not anchors:
        return None
    keys = [a[0] for a in anchors]

    def to_unix(ts):
        i = max(0, bisect.bisect_right(keys, ts) - 1)
        ts0, unix = anchors[i]
        return unix + (ts - ts0) / 10.0
    return to_unix


def decode_debug_data(body):
    """Re-decode `debug_data` (0x61) bodies the CLI gets wrong.

    The CLI treats any printable-looking body as ASCII, so a battery record whose
    voltage bytes are zero (`24 5e 00 00 00`) comes out as the string "$^", and
    firmware log lines carrying a leading level byte (`04 "chg_rp;1;6552"`) are
    left as opaque hex.
    """
    if not body:
        return None
    sub = body[0]
    if sub == 0x24 and len(body) >= 4:
        mv = body[2] | body[3] << 8
        return {"kind": "battery", "pct": body[1], "voltage_mv": mv or None}
    text = (body[1:] if sub < 0x20 else body).rstrip(b"\0")
    # Real log lines are printable all the way through (zeros only as trailing padding).
    if all(0x20 <= b < 0x7F for b in text) and sum(chr(b).isalnum() for b in text) >= 3:
        return {"kind": "log", "text": text.decode("ascii").strip()}
    return None


# Heuristics. A temp_event probe-0 reading at or above this means the ring is on a
# finger (on the charger it sits ~28 °C, off-finger it falls to room temperature).
WORN_TEMP_C = 30.0
WEIGHT_KG = float(os.environ.get("WEIGHT_KG", "70"))  # used only when the ring has no user profile
BUCKET = 300  # seconds: HR / temperature are summarised in 5-minute buckets
RESET_REASONS = {0: "power-on / factory reset", 4: "watchdog reset"}

_cache = {}


def _median(a):
    a = sorted(a)
    n = len(a)
    return None if not n else a[n // 2] if n % 2 else (a[n // 2 - 1] + a[n // 2]) / 2


def _mean(a, nd=2):
    return round(sum(a) / len(a), nd) if a else None


def analyze(serial):
    """Decode every stored event once into per-day metrics, intraday series and ring-health facts.

    Cached until new events land, because the store grows by tens of thousands of rows a day.
    """
    c = db()
    stamp = tuple(c.execute("select max(id), count(*) from events where serial=?", (serial,)).fetchone())
    hit = _cache.get(serial)
    if hit and hit[0] == stamp:
        return hit[1]
    to_unix = clock(c, serial)
    res = {"days": {}, "battery": [], "reboots": [], "logs": [], "warnings": [], "corr": None}
    if not to_unix:
        _cache[serial] = (stamp, res)
        return res

    days = {}

    def D(t):
        d = datetime.fromtimestamp(t).strftime("%Y-%m-%d")
        if d not in days:
            days[d] = {"beats": {}, "rr": {}, "temp": {}, "worn_s": 0, "hrv_hr": [], "hrv": [],
                       "spo2": [], "phases": {}, "alerts": [], "ble": 0, "skin": [], "met": {}}
        return days[d]

    weight = WEIGHT_KG
    worn_prev = None  # (unix, worn) of the previous temp_event, for wear time
    counts = {"wdt": 0, "neg_t": 0}
    last_ble = []
    for r in c.execute("select tag, name, ring_timestamp, body, decoded_json from events where serial=? "
                       "order by ring_timestamp, id", (serial,)):
        tag, name, ts = r["tag"], r["name"], r["ring_timestamp"]
        body = bytes(r["body"])
        t = to_unix(ts)
        try:
            j = json.loads(r["decoded_json"]) if r["decoded_json"] else {}
        except ValueError:
            j = {}
        if tag == 0x61:
            j = decode_debug_data(body) or {}
            if j.get("kind") == "battery":
                res["battery"].append({"ts": int(t), "pct": j["pct"], "mv": j["voltage_mv"]})
            elif j.get("kind") == "log":
                res["logs"].append((t, j["text"]))
            continue
        if tag == 0x43:
            txt = j.get("ascii")
            if txt:
                res["logs"].append((t, txt))
                if txt.startswith("wdt "):
                    counts["wdt"] += 1
                elif txt.startswith("DHR_info:neg t"):
                    counts["neg_t"] += 1
            continue
        if tag in (0x45, 0x53) and j.get("text"):
            res["logs"].append((t, f"{name}: [{j.get('state')}] {j['text']}"))
            continue
        if tag == 0x41:
            reason = j.get("reason")
            res["reboots"].append({"ts": int(t), "reason": reason,
                                   "label": RESET_REASONS.get(reason, f"reason {reason}"),
                                   "firmware": j.get("firmware_version")})
            continue
        if tag == 0x5c and j.get("weight_kg"):
            weight = j["weight_kg"]
            continue
        day = D(t)
        if tag == 0x80:  # green IBI + quality: the cleanest beat stream
            ibis, qs = j.get("ibi_ms", []), j.get("quality", [])
            b = int(t // BUCKET * BUCKET)
            prev = None
            for ibi, q in zip(ibis, qs):
                good = q == 1 and 300 <= ibi <= 2000
                if good:
                    day["beats"].setdefault(b, []).append(60000 / ibi)
                    if prev is not None and abs(ibi - prev) <= 0.2 * prev:
                        day["rr"].setdefault(b, []).append((ibi - prev) ** 2)
                prev = ibi if good else None
        elif tag == 0x5d:  # hrv_event: 5-minute (HR, RMSSD) pairs, emitted during sleep
            day["hrv_hr"] += [v for v in j.get("hr_bpm", []) if v]
            day["hrv"] += [v for v in j.get("rmssd_ms", []) if v]
        elif tag == 0x46:
            temps = j.get("temps_c") or []
            if temps:
                worn = temps[0] >= WORN_TEMP_C
                if worn:
                    day["temp"].setdefault(int(t // BUCKET * BUCKET), []).append(temps[0])
                if worn_prev and worn_prev[1]:
                    D(worn_prev[0])["worn_s"] += min(t - worn_prev[0], 120)
                worn_prev = (t, worn)
        elif tag in (0x69, 0x75):  # temp_period / sleep_temp_event
            day["skin"] += [v for v in j.get("temps_c", []) if v >= WORN_TEMP_C]
        elif tag in (0x6f, 0x70):
            day["spo2"] += [v for v in j.get("spo2_percent", []) if 50 < v <= 100]
        elif tag in (0x4b, 0x4e, 0x5a) and j.get("phases"):
            day["phases"].setdefault(name, []).extend(j["phases"])
        elif tag == 0x50:  # activity_information: one MET value per minute, ending at the event time
            mets = j.get("met") or []
            for i, m in enumerate(mets):
                mt = int((t - (len(mets) - 1 - i) * 60) // 60 * 60)
                D(mt)["met"][mt] = m  # keyed by minute: overlapping records must not double-count
        elif tag == 0x56:
            day["alerts"].append(int(t))
        elif tag == 0x5b and body[:1] == b"\x02":  # connection-established record
            day["ble"] += 1
            last_ble.append(t)

    out = {}
    for d, v in sorted(days.items()):
        buckets = sorted((b, _median(x)) for b, x in v["beats"].items() if len(x) >= 3)
        bvals = [x for _, x in buckets]
        if v["hrv_hr"]:
            resting, rsrc = min(v["hrv_hr"]), "sleep"
        elif len(bvals) >= 6:
            resting, rsrc = round(_mean(sorted(bvals)[:3])), "daytime"
        else:
            resting, rsrc = None, None
        rr = [(sum(x) / len(x)) ** 0.5 for x in v["rr"].values() if len(x) >= 8]
        if v["hrv"]:
            hrv, hsrc = _mean(v["hrv"], 1), "sleep"
        elif len(rr) >= 3:
            hrv, hsrc = round(_median(rr), 1), "daytime"
        else:
            hrv, hsrc = None, None
        temps = sorted((b, _median(x)) for b, x in v["temp"].items())
        skin = [x for _, x in temps] + v["skin"]
        # Several hypnogram tags can carry the same night; count the fullest one, never their sum.
        ph = max(v["phases"].values(), key=len, default=[])
        sleep = {}
        for p in ph:
            sleep[p] = sleep.get(p, 0) + 0.5
        met = sorted(v["met"].items())
        mets = [m for _, m in met]
        zones = {"low": sum(1 for m in mets if 1.5 <= m < 3), "medium": sum(1 for m in mets if 3 <= m < 6),
                 "high": sum(1 for m in mets if m >= 6)}
        out[d] = {
            "date": d,
            "resting_hr": resting, "resting_src": rsrc,
            "avg_hr": _mean(bvals, 1),
            "hrv": hrv, "hrv_src": hsrc,
            "temp": _mean(skin),
            "spo2": _mean(v["spo2"], 1),
            "sleep_min": sleep or None,
            "active_kcal": round(sum(max(0.0, m - 1) for m in mets) * weight / 60) if mets else None,
            "active_min": zones,
            "met_minutes": len(mets),
            "worn_min": round(v["worn_s"] / 60),
            "move_alerts": len(v["alerts"]),
            "ble_connections": v["ble"],
            "_hr": [{"t": b + BUCKET / 2, "v": round(x, 1)} for b, x in buckets],
            "_temp": [{"t": b + BUCKET / 2, "v": round(x, 2)} for b, x in temps],
            "_met": [{"t": mt, "v": m} for mt, m in met],
            "_alerts": v["alerts"],
        }
    res["days"] = out
    res["weight_kg"] = weight
    res["warnings"] = health_warnings(res, counts, last_ble)
    _cache[serial] = (stamp, res)
    return res


def battery_drain(samples):
    """%/hour over the most recent discharge run (ring-recorded samples), or None."""
    run = []
    for s in reversed(samples):
        if run and s["pct"] < run[-1]["pct"]:
            break  # going back in time the level dropped, i.e. it was charging here
        run.append(s)
    if len(run) < 2:
        return None
    hours = (run[0]["ts"] - run[-1]["ts"]) / 3600
    drop = run[-1]["pct"] - run[0]["pct"]
    return round(drop / hours, 2) if hours >= 1 and drop > 0 else None


def health_warnings(res, counts, ble_times):
    w = []
    wd = [r for r in res["reboots"] if r["reason"] == 4]
    if wd:
        w.append({"level": "bad", "title": f"{len(wd)} watchdog reset(s)",
                  "detail": "The firmware hung and restarted itself (ring_start reason 4, followed by a 'wdt' log line). "
                            f"Last: {datetime.fromtimestamp(wd[-1]['ts']):%b %d %H:%M}. Isolated resets during pairing are "
                            "harmless; repeated ones while worn are worth reporting."})
    if counts["neg_t"]:
        w.append({"level": "warn", "title": f"Daytime HR scheduler saw a negative time step ×{counts['neg_t']}",
                  "detail": "Firmware log 'DHR_info:neg t'. Appears after the ring clock is changed by a time sync; "
                            "the HR samples around it may be skipped."})
    rate = battery_drain(res["battery"])
    if rate:
        lvl = "bad" if rate > 2 else "warn" if rate > 1 else "good"
        w.append({"level": lvl, "title": f"Battery draining {rate}%/h (~{round(100 / rate)} h per charge)",
                  "detail": "From the ring's own battery records. A Ring 4 normally lasts 5–8 days (~0.5–0.8%/h). "
                            "Every Bluetooth connection costs power — see the connection rate below."})
    ble_times = [t for t in ble_times if t >= ble_times[-1] - 6 * 3600] if ble_times else []  # recent rate only
    if len(ble_times) >= 2:
        span = (ble_times[-1] - ble_times[0]) / 3600
        per_h = len(ble_times) / span if span > 0.5 else None
        if per_h:
            w.append({"level": "bad" if per_h > 6 else "good",
                      "title": f"{round(per_h, 1)} Bluetooth connections per hour",
                      "detail": f"{len(ble_times)} connections recorded by the ring over the last "
                                f"{round(span, 1)} h of data. The battery poll is set to every {BATTERY_INTERVAL}s; "
                                "the Oura app connects only a few times a day."})
    return w


def summary(serial):
    if not DB.exists():
        return {"events": 0}
    c = db()
    out = {}
    out["counts"] = {r["name"]: r["n"] for r in c.execute(
        "select name, count(*) n from events where serial=? group by name order by n desc", (serial,))}
    st = c.execute("select next_cursor,last_sync_unix from sync_state where serial=?", (serial,)).fetchone()
    out["last_sync"] = st["last_sync_unix"] if st else None
    dev = c.execute("select * from device where serial=?", (serial,)).fetchone()
    out["device"] = dict(dev) if dev else None
    a = analyze(serial)
    out["series"] = [{k: v for k, v in d.items() if not k.startswith("_")} for d in a["days"].values()]
    out["warnings"] = a["warnings"]
    out["reboots"] = a["reboots"][-20:]
    out["drain_pct_h"] = battery_drain(a["battery"])
    out["weight_kg"] = a["weight_kg"]
    return out


def day_detail(serial, date):
    a = analyze(serial)
    d = a["days"].get(date)
    if not d:
        return {"date": date}
    t0 = datetime.strptime(date, "%Y-%m-%d").timestamp()
    bat = [b for b in a["battery"] if t0 <= b["ts"] < t0 + 86400]
    return {"date": date, "hr": d["_hr"], "temp": d["_temp"], "met": d["_met"], "alerts": d["_alerts"],
            "battery": bat}


def ring_logs(serial, q="", limit=300):
    logs = analyze(serial)["logs"]
    if q:
        ql = q.lower()
        logs = [x for x in logs if ql in x[1].lower()]
    return [{"ts": t, "text": s} for t, s in logs[-limit:]][::-1]


# ---------- HTTP ----------
def ring_list():
    rings = load_rings()
    out = []
    for serial, r in rings.items():
        info = {"serial": serial, "name": r["name"], "key_file": r["key_file"],
                "battery": latest_battery(serial), "battery_interval": BATTERY_INTERVAL,
                "tracking": latest_tracking(serial)}
        if DB.exists():
            c = db()
            st = c.execute("select last_sync_unix from sync_state where serial=?", (serial,)).fetchone()
            info["last_sync"] = st["last_sync_unix"] if st else None
            dev = c.execute("select firmware from device where serial=?", (serial,)).fetchone()
            info["firmware"] = dev["firmware"] if dev else None
        out.append(info)
    return out


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def do_GET(self):
        path = urlparse(self.path).path
        try:
            if path in ("/", "/index.html"):
                data = (STATIC / "index.html").read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            elif path == "/api/rings":
                self.send_json(ring_list())
            elif m := re.fullmatch(r"/api/rings/(\w+)/summary", path):
                self.send_json(summary(m[1]))
            elif m := re.fullmatch(r"/api/rings/(\w+)/events", path):
                q = parse_qs(urlparse(self.path).query)
                limit = min(int(q.get("limit", ["60"])[0]), 500)
                name = q.get("name", [""])[0]
                c = db()
                to_unix = clock(c, m[1])
                sql = ("select id,name,tag,ring_timestamp,decoded_json,body,length(body) blen,"
                       "hex(substr(body,1,24)) hexhead from events where serial=?")
                args = [m[1]]
                if name:
                    sql += " and name=?"
                    args.append(name)
                rows = c.execute(sql + " order by ring_timestamp desc, id desc limit ?", (*args, limit)).fetchall()
                out = []
                for r in rows:
                    e = dict(r)
                    body = bytes(e.pop("body"))
                    if e["tag"] == 0x61 and (fixed := decode_debug_data(body)):
                        e["decoded_json"] = json.dumps(fixed)
                    e["unix"] = to_unix(r["ring_timestamp"]) if to_unix else None
                    out.append(e)
                self.send_json(out)
            elif m := re.fullmatch(r"/api/rings/(\w+)/battery", path):
                cutoff = time.time() - 24 * 3600
                ring = [{"ts": b["ts"], "pct": b["pct"], "src": "ring"}
                        for b in analyze(m[1])["battery"] if b["ts"] >= cutoff] if DB.exists() else []
                hist = sorted(battery_history(m[1]) + ring, key=lambda b: b["ts"])
                self.send_json({"latest": latest_battery(m[1]), "history": hist})
            elif m := re.fullmatch(r"/api/rings/(\w+)/day/(\d{4}-\d\d-\d\d)", path):
                self.send_json(day_detail(m[1], m[2]))
            elif m := re.fullmatch(r"/api/rings/(\w+)/logs", path):
                q = parse_qs(urlparse(self.path).query)
                self.send_json(ring_logs(m[1], q.get("q", [""])[0], min(int(q.get("limit", ["300"])[0]), 2000)))
            elif m := re.fullmatch(r"/api/jobs/(\d+)", path):
                self.send_json(jobs.get(m[1], {"status": "unknown"}))
            else:
                self.send_json({"error": "not found"}, 404)
        except Exception as e:  # noqa: BLE001
            self.send_json({"error": f"{type(e).__name__}: {e}"}, 500)

    def do_POST(self):
        path = urlparse(self.path).path
        try:
            if path == "/api/scan":
                job = run_job("scan", None, ["--name=", "--scan-timeout", "10", "scan"])
                return self.send_json(job)
            if path == "/api/pair":
                b = self.body()
                name = (b.get("name") or "Ring").strip()
                KEYS.mkdir(exist_ok=True)

                def done(job):
                    m = re.search(r"auth key on (\w+); saved to (.+)", job["log"])
                    if job["status"] == "ok" and m:
                        src = Path(m[2].strip())
                        if not src.is_absolute():
                            src = ROOT / src
                        dest = KEYS / f"oura-{m[1]}.key"
                        if src.exists() and src.resolve() != dest.resolve():
                            dest.write_text(src.read_text())  # copy, never move: the key is the only way back in
                        rings = load_rings()
                        rings[m[1]] = {"name": name, "key_file": str(dest if dest.exists() else src)}
                        save_rings(rings)

                # cwd is ROOT, so the CLI writes oura-<serial>.key there; done() copies it into keys/
                job = run_job("pair", None, ["--name=", "--scan-timeout", "15", "pair"], done)
                return self.send_json(job)
            if m := re.fullmatch(r"/api/rings/(\w+)/(sync|info|features|tracking)", path):
                serial, action = m[1], m[2]
                args = ring_args(serial)
                if action == "sync":
                    args += ["sync"]
                elif action == "info":
                    args += ["info"]
                elif action == "tracking":  # read-only: report the ring's current measurement modes
                    args += ["features"]
                else:
                    args += ["features", "--enable-hr", "--enable-spo2"]

                def done(job, serial=serial):
                    s = re.search(r"Serial\s*:\s*(\w+)", job["log"])
                    b = re.search(r"Battery\s*:\s*(\d+)%", job["log"])
                    if job["status"] == "ok" and s and b and s[1] == serial:
                        record_battery(serial, int(b[1]))
                    if job["status"] == "ok" and action in ("features", "tracking"):
                        record_tracking(serial, job["log"])

                auto = bool(self.body().get("auto")) if action == "sync" else False
                return self.send_json(run_job(action, serial, args, done if action in ("info", "features", "tracking") else None,
                                              skip_if_busy=auto))
            if m := re.fullmatch(r"/api/rings/(\w+)/rename", path):
                rings = load_rings()
                rings[m[1]]["name"] = self.body().get("name", rings[m[1]]["name"])
                save_rings(rings)
                return self.send_json({"ok": True})
            self.send_json({"error": "not found"}, 404)
        except KeyError:
            self.send_json({"error": "unknown ring"}, 404)
        except Exception as e:  # noqa: BLE001
            self.send_json({"error": f"{type(e).__name__}: {e}"}, 500)


if __name__ == "__main__":
    bootstrap_registry()
    threading.Thread(target=battery_loop, daemon=True).start()
    print(f"Oura dashboard on http://127.0.0.1:{PORT}")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
