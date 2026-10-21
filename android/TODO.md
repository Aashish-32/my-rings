# TODO

## Measure how much HRV improves REM / deep staging

**Status:** Route A done (HRV helps once its sign is corrected, see below); Route B open, needs ≥ 14 own nights.

**Why:** [`SleepStager`](app/src/main/java/com/aashish/myrings/data/SleepStager.kt) was tuned on the PhysioNet
Walch 2019 dataset (wrist heart rate + motion + lab polysomnography). On nights held out from tuning it scored:
4-stage accuracy 63%, κ 0.37, deep found 44%, REM 51%. That dataset has **no beat-to-beat intervals**, so the
HRV (RMSSD) part of the stager — `deepRm` and `remRm` in `SleepStager.Params` — was set from physiology, not
fitted or measured. The ring *does* record beat-to-beat intervals at night (`0x60`, `0x80`), so the app uses HRV,
but nobody has measured whether it helps.

### Route A: measure it now on open ECG + polysomnography data — DONE 2026-10-20

Result (33 nights: 15 healthy CAP controls + 18 MIT-BIH Polysomnographic, 5-fold cross-validated, staging within
sleep light/deep/REM; no movement channel in these datasets):

| | κ | accuracy | deep found | REM found |
|---|---|---|---|---|
| HRV off | 0.27 | 58% | 49% | 50% |
| HRV, original textbook signs (deep = high HRV) | 0.20 | 53% | 46% | 49% |
| HRV, fitted signs (cross-validated) | 0.32 | 61% | 41% | 58% |
| HRV, shipped defaults `deepRm=-0.5, remRm=1.0` | 0.33 | 62% | 49% | 56% |

Finding: short-term HRV (5-min RMSSD) is *lower* in deep sleep than in light sleep in both healthy and apnea
nights, so the original weight made staging worse. Fixed in `SleepStager.Params`; the wrist-data (Walch) result
is unchanged because that data has no HRV. Scripts: `tools/sleep-eval/slpdb_to_epochs.py`,
`capslpdb_to_epochs.py`; harness: `StagerEvalTest.evaluateHrv` (`SLEEP_EVAL_HRV_DIR="<cap>;<slpdb>"`).

Original plan, for reference:

ECG gives exact beat-to-beat timing, the same signal the ring's IBIs approximate. Both datasets are open access
on PhysioNet's AWS mirror (checked downloadable 2026-10-20):

- MIT-BIH Polysomnographic Database `slpdb` (18 records, ECG + 30 s sleep stages):
  `https://physionet-open.s3.amazonaws.com/slpdb/1.0.0/`
- CAP Sleep Database `capslpdb` (108 records, ECG + stages; some patients have sleep disorders):
  `https://physionet-open.s3.amazonaws.com/capslpdb/1.0.0/`

Steps:
1. Detect R-peaks in the ECG → beat-to-beat intervals → per-30 s HR and ~5-min RMSSD (the inputs
   `Analytics.stageNight` builds from ring beats).
2. Movement: neither dataset has a wrist/finger accelerometer, so use an EMG/limb channel if present, or evaluate
   HR-only vs HR+HRV with motion set to "still" for both (the difference is what we want).
3. Export per-night CSVs in the `StagerEvalTest` format (add an `rmssd` column) and extend the harness to pass it.
4. Compare on held-out nights: (a) current defaults with HRV, (b) HRV weights zeroed, (c) HRV weights re-tuned.
5. Keep whichever wins on held-out nights; update the numbers in `SleepStager.Params` docs.

**Done when:** we have a held-out κ / deep / REM comparison with vs without HRV, and the defaults reflect it.

### Route B: check on my own ring once there are enough nights

My own nights have no lab labels, so they can't measure *accuracy*; they can catch problems the datasets can't
(finger vs wrist motion, ring IBI quality).

**Enough data:** ≥ 14 nights worn to bed and synced (also when Readiness baselines stop being provisional).

Checks:
1. The stager staged every night (didn't fall back to "movement-only"), i.e. enough overnight beats arrived.
2. Stage shares look human: deep ~10–25%, REM ~15–30%, deep front-loaded, REM back-loaded.
3. Motion: how often `motion_event` seconds are non-zero during sleep. If most sleep epochs read as moving, the
   per-night motion baseline (`motionBaselineQ`) or `wakeMotion` needs adjusting for the finger.
4. Run the stager with HRV weights on vs zeroed on the same nights and see how much the stages move.
5. Optional, best ground truth available at home: a few nights alongside another validated tracker, or a
   one-off home sleep test, to spot-check stage timing.

**Done when:** the checks above pass on ≥ 14 nights, or the parameters are adjusted until they do.
