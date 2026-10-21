#!/bin/bash
# Fetch labels + heart rate (small) and reduce motion on the fly for every subject.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
S="$1"; B=https://physionet-open.s3.amazonaws.com/sleep-accel/1.0.0; IDX=https://physionet.org/files/sleep-accel/1.0.0
mkdir -p "$S/sleep-accel/raw"; cd "$S/sleep-accel"
ids=$(curl -sL "$IDX/labels/" | grep -o '[0-9]*_labeled_sleep.txt' | sort -u | sed 's/_labeled_sleep.txt//')
for id in $ids; do
  curl -sfL "$B/labels/${id}_labeled_sleep.txt" -o "raw/${id}_labels.txt" || echo "labels fail $id"
  curl -sfL "$B/heart_rate/${id}_heartrate.txt" -o "raw/${id}_hr.txt" || echo "hr fail $id"
  curl -sfL "$B/motion/${id}_acceleration.txt" | python -I "$HERE/reduce_motion.py" > "raw/${id}_motion_epochs.csv" || echo "motion fail $id"
  echo "done $id $(wc -l < raw/${id}_motion_epochs.csv)"
done
echo ALL_DONE
