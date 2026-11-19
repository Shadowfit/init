#!/bin/bash
# EC2 무인 라운드 — 프로세스 재기동 RTO (RPO·RTO 분기점 §4 첫 줄 · §6-2)
# 규칙은 server-prep-and-ship-cost-2026-11-08/ec2_userdata.sh 와 같다 (SHA 고정·grep 단언·감시견·terminate).
# 대문자 자리표시자(골뱅이 두 쌍으로 감싼 것)는 launch.sh 가 띄우기 직전에 채운다.
set -uo pipefail
SHA=@@SHA@@
RUN_ID=@@RUN_ID@@
S3=s3://shadowfit-measure-055447613012/shadowfit/$RUN_ID
CAP_MIN=@@CAP_MIN@@
RIG=/root/init/loadtest/results/process-restart-rto-2026-11-08
OUT=/root/rig_out
LOG=/root/round.log
exec > >(tee -a "$LOG") 2>&1

up() { aws s3 cp "$LOG" "$S3/round.log" --only-show-errors || true; [ -d "$OUT" ] && aws s3 sync "$OUT" "$S3/out" --only-show-errors || true; }
finish() { echo "== finish: $1 ($(date -Is))"; up; aws s3 cp - "$S3/FINISHED" <<< "$1 $(date -Is)" || true; shutdown -h now; }

echo "== start $(date -Is) SHA=$SHA RUN_ID=$RUN_ID CAP_MIN=$CAP_MIN"
( sleep $((CAP_MIN * 60)); echo "🔴 상한 ${CAP_MIN}분 도달 — 끈다"; finish CAP ) &
( while sleep 300; do up; done ) &

cd /root
curl -fsSL "https://raw.githubusercontent.com/Shadowfit/init/$SHA/loadtest/aws/bootstrap.sh" -o bootstrap.sh || finish BOOTSTRAP_DOWNLOAD
grep -q 'ROLE=${ROLE:-db}' bootstrap.sh || finish BOOTSTRAP_ASSERT
ROLE=db REF=$SHA SKIP_FLYWAY=1 bash bootstrap.sh || finish BOOTSTRAP_FAIL

[ -f "$RIG/restart.sh" ] || finish RIG_MISSING
grep -q 'ckpt_age_b' "$RIG/restart.sh" || finish RIG_ASSERT
grep -q '_tpl' /root/init/loadtest/results/binlog-replay-time-2026-11-07/_writer_common.sh || finish WRITER_ASSERT
docker ps --format '{{.Names}}' | grep -E '^shadowfit-' | xargs -r docker stop

mkdir -p "$OUT"
{ echo "instance_type=$(curl -s -H "X-aws-ec2-metadata-token: $(curl -s -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')" http://169.254.169.254/latest/meta-data/instance-type)"; nproc; free -g; df -h /; } > "$OUT/box.txt"
env OUT=$OUT WORK=/root/rig_work bash "$RIG/restart.sh" > "$OUT/run.log" 2>&1
rc=$?
echo "== rig exit $rc"
finish "DONE rc=$rc"
