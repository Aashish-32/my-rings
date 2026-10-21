"""Merge labels + heart rate + reduced motion into one CSV per subject, one row per labeled 30-s epoch.
Labels (Walch 2019): 0 wake, 1 N1, 2 N2, 3 N3, 4 N4, 5 REM, -1 unscored -> awake/light/deep/rem/''."""
import sys, glob, os
src, dst = sys.argv[1], sys.argv[2]
LAB = {0: "awake", 1: "light", 2: "light", 3: "deep", 4: "deep", 5: "rem"}
for lf in sorted(glob.glob(os.path.join(src, "*_labels.txt"))):
    sid = os.path.basename(lf).split("_")[0]
    mf = os.path.join(src, f"{sid}_motion_epochs.csv"); hf = os.path.join(src, f"{sid}_hr.txt")
    if not (os.path.exists(mf) and os.path.exists(hf)) or os.path.getsize(mf) < 100:
        print("skip", sid); continue
    labels = []
    for line in open(lf):
        p = line.split()
        if len(p) >= 2:
            labels.append((int(float(p[0])), int(float(p[1]))))
    hr = {}
    for line in open(hf):
        p = line.replace(",", " ").split()
        if len(p) >= 2:
            t, v = float(p[0]), float(p[1])
            if 30 <= v <= 200:
                hr.setdefault(int(t // 30), []).append(v)
    mo = {}
    for line in list(open(mf))[1:]:
        p = line.strip().split(",")
        mo[int(p[0])] = p[2:]
    with open(os.path.join(dst, f"{sid}.csv"), "w") as out:
        out.write("epoch,label,hr,m0.005,m0.01,m0.02,m0.04,m0.08\n")
        for t, s in labels:
            e = t // 30
            h = hr.get(e)
            m = mo.get(e, ["-1"] * 5)
            out.write(f"{e},{LAB.get(s, '')},{'' if not h else round(sum(h) / len(h), 2)},{','.join(m)}\n")
    print("ok", sid, len(labels))
