"""CAP Sleep Database (PhysioNet capslpdb), healthy controls n1-n15 -> per-30 s epoch CSVs for StagerEvalTest.

Streams each EDF from PhysioNet's AWS mirror, keeps only the ECG channel (the rest is discarded as it arrives),
detects R peaks (Pan-Tompkins style: band-pass, derivative, squaring, moving-window integration, adaptive
threshold, refractory period), then builds HR and ~5-minute RMSSD per epoch exactly like Analytics.stageNight
does from ring beats. Stages come from the RemLogic export (<rec>.txt). No movement channel: motion is 0.

usage: python -I capslpdb_to_epochs.py <out dir> [records...]     (needs numpy)
"""
import os, sys, urllib.request
import numpy as np

BASE = "https://physionet-open.s3.amazonaws.com/capslpdb/1.0.0/"
STAGE = {"W": "awake", "S1": "light", "S2": "light", "S3": "deep", "S4": "deep", "R": "rem", "REM": "rem"}


def read_exact(f, n):
    b = bytearray()
    while len(b) < n:
        c = f.read(n - len(b))
        if not c:
            break
        b += c
    return bytes(b)


def stream_ecg(rec):
    """Return (ecg samples in physical units, fs, start seconds-of-day) reading only what's needed."""
    f = urllib.request.urlopen(BASE + rec + ".edf")
    h = read_exact(f, 256)
    ns = int(h[252:256]); dur = float(h[244:252])
    start = h[176:184].decode()
    start_s = int(start[0:2]) * 3600 + int(start[3:5]) * 60 + int(start[6:8])
    sh = read_exact(f, ns * 256)
    def field(off, width, i):
        return sh[ns * off + width * i: ns * off + width * (i + 1)].decode("latin-1").strip()
    labels = [field(0, 16, i) for i in range(ns)]
    pmin = [float(field(104, 8, i)) for i in range(ns)]; pmax = [float(field(112, 8, i)) for i in range(ns)]
    dmin = [float(field(120, 8, i)) for i in range(ns)]; dmax = [float(field(128, 8, i)) for i in range(ns)]
    spr = [int(field(216, 8, i)) for i in range(ns)]
    k = next(i for i, l in enumerate(labels) if "ECG" in l.upper() or "EKG" in l.upper())
    off = sum(spr[:k]) * 2; rec_bytes = sum(spr) * 2; n = spr[k]
    parts = []
    while True:
        b = read_exact(f, rec_bytes)
        if len(b) < rec_bytes:
            break
        parts.append(np.frombuffer(b, dtype="<i2", count=n, offset=off))
    d = np.concatenate(parts).astype(np.float64)
    gain = (pmax[k] - pmin[k]) / (dmax[k] - dmin[k])
    return (d - dmin[k]) * gain + pmin[k], n / dur, start_s


def movavg(x, w):
    w = max(1, int(w))
    c = np.cumsum(np.insert(x, 0, 0.0))
    y = (c[w:] - c[:-w]) / w
    return np.concatenate([np.full(w // 2, y[0]), y, np.full(len(x) - len(y) - w // 2, y[-1])])


def r_peaks(ecg, fs):
    """Pan-Tompkins style QRS detection; returns sample indices of R peaks."""
    hp = ecg - movavg(ecg, 0.2 * fs)          # remove baseline wander
    bp = movavg(hp, max(1, round(0.012 * fs)))  # light low-pass
    d = np.diff(bp, prepend=bp[0])
    mwi = movavg(d * d, 0.15 * fs)
    # adaptive threshold: 40 % of the 98th percentile over 10-s blocks
    blk = int(10 * fs); thr = np.empty_like(mwi)
    for i in range(0, len(mwi), blk):
        seg = mwi[i:i + blk]
        thr[i:i + blk] = 0.4 * np.percentile(seg, 98)
    above = mwi > thr
    edges = np.flatnonzero(np.diff(above.astype(np.int8)) == 1)
    refr = int(0.27 * fs); half = int(0.12 * fs)
    peaks, last = [], -refr
    absbp = np.abs(bp)
    for e in edges:
        lo = max(0, e - half); hi = min(len(bp), e + half + int(0.15 * fs))
        p = lo + int(np.argmax(absbp[lo:hi]))
        if p - last >= refr:
            peaks.append(p); last = p
    return np.array(peaks)


def stages(rec):
    """Scored 30-s epochs as (seconds of day, stage). Columns vary between files, so read them from the header row."""
    out, col = [], None
    txt = urllib.request.urlopen(BASE + rec + ".txt").read().decode("latin-1").splitlines()
    for line in txt:
        p = [x.strip() for x in line.split("\t")]
        if col is None:
            if p and p[0] == "Sleep Stage":
                col = {name: i for i, name in enumerate(p)}
                ti = next(i for n_, i in col.items() if n_.startswith("Time"))
                ei = col["Event"]; di = next(i for n_, i in col.items() if n_.startswith("Duration"))
            continue
        if len(p) <= max(ti, ei, di) or not p[ei].startswith("SLEEP-") or p[di] != "30":
            continue
        hh, mm, ss = (int(x) for x in p[ti].replace(".", ":").split(":"))
        out.append((hh * 3600 + mm * 60 + ss, STAGE.get(p[0], "")))
    return out


def main(out, recs):
    os.makedirs(out, exist_ok=True)
    for rec in recs:
        st = stages(rec)
        ecg, fs, start_s = stream_ecg(rec)
        pk = r_peaks(ecg, fs)
        bt = pk / fs  # beat times, seconds from EDF start
        t0 = (st[0][0] - start_s) % 86400  # first scored epoch, seconds from EDF start
        n = len(st)
        label = [""] * n
        for t, s in st:
            k = int(round((((t - start_s) % 86400) - t0) / 30))
            if 0 <= k < n:
                label[k] = s
        hr = [[] for _ in range(n)]; sq = [[0.0, 0] for _ in range(n)]
        prev, rejected = None, 0
        for a, b in zip(bt, bt[1:]):
            rr = (b - a) * 1000
            k = int((b - t0) // 30)
            ok = 300 <= rr <= 2000 and (prev is None or abs(rr - prev) <= 0.25 * prev)
            rejected += not ok
            if ok and 0 <= k < n:
                hr[k].append(60000 / rr)
                if prev is not None and abs(rr - prev) <= 0.2 * prev:
                    sq[k][0] += (rr - prev) ** 2; sq[k][1] += 1
            prev = rr if ok else None
        with open(os.path.join(out, rec + ".csv"), "w") as f:
            f.write("epoch,label,hr,m0.005,m0.01,m0.02,m0.04,m0.08,rmssd\n")
            for k in range(n):
                s = sum(sq[j][0] for j in range(max(0, k - 5), min(n, k + 6)))
                c = sum(sq[j][1] for j in range(max(0, k - 5), min(n, k + 6)))
                h = hr[k]
                f.write(f"{k},{label[k]},{round(sum(h) / len(h), 2) if len(h) >= 2 else ''},0,0,0,0,0,"
                        f"{round((s / c) ** 0.5, 2) if c >= 8 else ''}\n")
        print(rec, f"fs {fs:g}", "epochs", n, "beats", len(bt), f"rejected RR {rejected / max(1, len(bt) - 1):.1%}",
              f"median HR {np.median(60 / np.diff(bt)):.0f}", flush=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:] or [f"n{i}" for i in range(1, 16)])
