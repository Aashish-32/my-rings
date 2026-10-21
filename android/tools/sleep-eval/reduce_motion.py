"""Stream an Apple Watch acceleration file (t,x,y,z in g; ~50 Hz) from stdin and print per-30-s epoch
motion features: for each threshold, the number of 1-s windows whose acceleration-magnitude range exceeds it.
That mirrors the ring's motion_event "seconds of motion per window"."""
import sys, math
TH = [0.005, 0.01, 0.02, 0.04, 0.08]
sec = {}  # second -> [min, max] of magnitude
for line in sys.stdin:
    p = line.replace(',', ' ').split()
    if len(p) < 4:
        continue
    try:
        t, x, y, z = float(p[0]), float(p[1]), float(p[2]), float(p[3])
    except ValueError:
        continue
    m = math.sqrt(x * x + y * y + z * z)
    s = math.floor(t)
    r = sec.get(s)
    if r is None:
        sec[s] = [m, m]
    else:
        if m < r[0]: r[0] = m
        if m > r[1]: r[1] = m
ep = {}
for s, (lo, hi) in sec.items():
    e = s // 30
    row = ep.setdefault(e, [0] * (len(TH) + 1))
    row[0] += 1
    for i, th in enumerate(TH):
        if hi - lo > th:
            row[i + 1] += 1
print("epoch,seconds_with_data," + ",".join(f"moving_{t}" for t in TH))
for e in sorted(ep):
    print(f"{e}," + ",".join(str(v) for v in ep[e]))
