"""MIT-BIH Polysomnographic Database (PhysioNet slpdb) -> per-30 s epoch CSVs for StagerEvalTest.

Uses the database's own beat annotations (.ecg) and sleep-stage annotations (.st); no signal processing.
HR and ~5-minute RMSSD are built from normal-to-normal beats exactly like Analytics.stageNight builds them from
ring beats (successive differences > 20 % dropped). There is no movement channel, so motion is 0 ("still").

usage: python slpdb_to_epochs.py <download dir> <out dir>   (download dir is filled from the AWS mirror)
"""
import os, struct, sys, urllib.request

BASE = "https://physionet-open.s3.amazonaws.com/slpdb/1.0.0/"
FS = 250  # Hz, all slpdb records
STAGE = {"W": "awake", "1": "light", "2": "light", "3": "deep", "4": "deep", "R": "rem"}  # MT/other -> unlabeled


def fetch(name, d):
    p = os.path.join(d, name)
    if not os.path.exists(p):
        urllib.request.urlretrieve(BASE + name, p)
    return open(p, "rb").read()


def annotations(buf):
    """Parse a WFDB (MIT format) annotation file -> list of (sample, code, aux)."""
    out, t, i = [], 0, 0
    while i + 2 <= len(buf):
        w = struct.unpack_from("<H", buf, i)[0]; i += 2
        a, n = w >> 10, w & 0x3FF
        if a == 0 and n == 0:
            break
        if a == 59:  # SKIP: 32-bit interval follows (high 16 bits first)
            hi, lo = struct.unpack_from("<HH", buf, i); i += 4
            v = (hi << 16) | lo
            t += v - (1 << 32) if v & 0x80000000 else v
        elif a == 63:  # AUX string for the previous annotation
            s = buf[i:i + n].decode("latin-1").rstrip("\0"); i += n + (n & 1)
            if out:
                out[-1] = (out[-1][0], out[-1][1], s)
        elif a in (60, 61, 62):  # NUM / SUB / CHN modifiers
            pass
        else:
            t += n
            out.append((t, a, ""))
    return out


def main(dl, out):
    os.makedirs(dl, exist_ok=True); os.makedirs(out, exist_ok=True)
    records = urllib.request.urlopen(BASE + "RECORDS").read().decode().split()
    for rec in records:
        beats = [s for s, a, _ in annotations(fetch(rec + ".ecg", dl)) if a == 1]  # normal beats only
        stages = [(s, aux.split()[0] if aux.split() else "") for s, a, aux in annotations(fetch(rec + ".st", dl)) if aux]
        if not stages:
            print("no stages", rec); continue
        e0 = stages[0][0]
        n = (stages[-1][0] - e0) // (30 * FS) + 1
        label = [""] * n
        for s, st in stages:
            k = (s - e0) // (30 * FS)
            if 0 <= k < n:
                label[k] = STAGE.get(st, "")
        hr = [[] for _ in range(n)]; sq = [[0.0, 0] for _ in range(n)]
        prev_rr = None
        for b0, b1 in zip(beats, beats[1:]):
            rr = (b1 - b0) * 1000.0 / FS
            k = (b1 - e0) // (30 * FS)
            ok = 300 <= rr <= 2000 and (prev_rr is None or abs(rr - prev_rr) <= 0.25 * prev_rr)
            if ok and 0 <= k < n:
                hr[k].append(60000.0 / rr)
                if prev_rr is not None and abs(rr - prev_rr) <= 0.2 * prev_rr:
                    sq[k][0] += (rr - prev_rr) ** 2; sq[k][1] += 1
            prev_rr = rr if ok else None
        with open(os.path.join(out, rec + ".csv"), "w") as f:
            f.write("epoch,label,hr,m0.005,m0.01,m0.02,m0.04,m0.08,rmssd\n")
            for k in range(n):
                h = hr[k]
                s = sum(sq[j][0] for j in range(max(0, k - 5), min(n, k + 6)))
                c = sum(sq[j][1] for j in range(max(0, k - 5), min(n, k + 6)))
                rm = round((s / c) ** 0.5, 2) if c >= 8 else ""
                f.write(f"{k},{label[k]},{round(sum(h) / len(h), 2) if len(h) >= 2 else ''},0,0,0,0,0,{rm}\n")
        print(rec, "epochs", n, "labeled", sum(1 for x in label if x), "beats", len(beats))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
