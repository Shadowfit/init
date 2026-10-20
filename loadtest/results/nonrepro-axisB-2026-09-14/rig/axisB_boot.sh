#!/bin/bash
# 축 B 부팅 1회분 — 보정(전) → run_all(calibration + framepath + collect) → 보정(후) → S3.
# 사용: bash /root/axisB_boot.sh <k>     (k = 부팅 번호, 1부터)
set -u
K=${1:?k}
ROOT=/root/init
BASE=/root/axisB
OUT=$BASE/boot$K
S3=s3://shadowfit-measure-055447613012/shadowfit/nonrepro-axisB-2026-09-14
PY=$ROOT/ai-server/.venv/bin/python
mkdir -p "$OUT"
cd "$ROOT"

imds() { local t; t=$(curl -sf --max-time 3 -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60'); curl -sf --max-time 3 -H "X-aws-ec2-metadata-token: $t" "http://169.254.169.254/latest/meta-data/$1"; echo; }

# 호스트 지문 — 물리 호스트 ID 는 게스트에서 못 읽는다(설계 §6). 대신 바뀔 «수 있는» 것들을 남긴다.
{
  echo "boot_k        : $K"
  echo "생성          : $(date -Is)"
  echo "boot_id       : $(cat /proc/sys/kernel/random/boot_id)"
  echo "uptime -s     : $(uptime -s)"
  echo "instance-id   : $(imds instance-id)"
  echo "instance-type : $(imds instance-type)"
  echo "AZ            : $(imds placement/availability-zone)"
  echo "host-id(IMDS) : $(imds placement/host-id)   # 전용 호스트가 아니면 비어 있다(404)"
  echo "system-uuid   : $(cat /sys/devices/virtual/dmi/id/product_uuid 2>/dev/null)"
  echo "bios          : $(cat /sys/devices/virtual/dmi/id/bios_version 2>/dev/null) / $(cat /sys/devices/virtual/dmi/id/bios_date 2>/dev/null)"
  echo "cpu model     : $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2-)"
  echo "cpu family/model/stepping : $(grep -m1 '^cpu family' /proc/cpuinfo | awk '{print $NF}') / $(grep -m1 '^model[[:space:]]' /proc/cpuinfo | awk '{print $NF}') / $(grep -m1 '^stepping' /proc/cpuinfo | awk '{print $NF}')"
  echo "microcode     : $(grep -m1 microcode /proc/cpuinfo | awk '{print $NF}')"
  echo "cpu MHz(참고) : $(grep -m1 'cpu MHz' /proc/cpuinfo | awk '{print $NF}')   # 가상화라 신뢰 안 함(설계 §6)"
  echo "nproc         : $(nproc)"
  echo "kernel        : $(uname -r)"
  echo "커밋          : $(git -C $ROOT rev-parse HEAD)"
} > "$OUT/boot.txt"
cat "$OUT/boot.txt"

echo "== 보정(전) =="
"$PY" "$ROOT/loadtest/calibrate_box.py" --tsv "$OUT/calibration_before.tsv" 2>&1 | sed 's/^/  /'

echo "== run_all: calibration + framepath(B×5, 버림1) + collect =="
S3_BASE="$S3" RUN_ID="boot$K" OUTDIR="$OUT/run" AUTO_SHUTDOWN=0 \
  PHASES="calibration framepath collect" \
  FP_PLAN="B,B,B,B,B" FP_DISCARD=1 FP_TAG="boot$K" \
  FP_SESSIONS=160 FP_FPS=3 FP_DUR=90 FP_POOL=201 \
  bash "$ROOT/loadtest/aws/run_all.sh" > "$OUT/run_all.log" 2>&1
echo "run_all rc=$?" | tee "$OUT/run_all.rc"
tail -5 "$OUT/run_all.log"

echo "== 보정(후) =="
"$PY" "$ROOT/loadtest/calibrate_box.py" --tsv "$OUT/calibration_after.tsv" 2>&1 | sed 's/^/  /'

cp /root/ai_venv_conditions.txt "$OUT/" 2>/dev/null
cp /root/calibration.tsv "$BASE/calibration_bootstrap.tsv" 2>/dev/null
cp /var/log/axisB-userdata.log "$BASE/" 2>/dev/null
aws s3 sync "$BASE" "$S3/" --only-show-errors && echo "S3 OK" || echo "S3 FAIL"
date -Is > "$OUT/DONE"
