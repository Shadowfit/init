#!/bin/bash
# EC2 무인 라운드 — ① 새 서버 준비 시간 ② 반출 업로드 비용 (RPO·RTO 분기점 §6-2 후속)
# 규칙은 binlog-replay-time-2026-11-07/ec2_userdata.sh 와 같다(커밋 SHA 고정·grep 단언·감시견·terminate).
#
# ① 준비 시간 = 이 스크립트가 «빈 AL2023 박스를 복구 대상으로 쓸 수 있는 상태» 까지 가는 시간.
#    구간마다 시각(ms)을 prep.tsv 에 남긴다. 호출 쪽(launch.sh)이 run-instances 호출·running 상태 시각을 따로 남긴다.
#      boot            커널이 뜬 시각 (현재 − /proc/uptime)
#      userdata_start  이 스크립트 시작
#      bootstrap_dl    bootstrap.sh 내려받기 끝
#      bootstrap_done  bootstrap.sh 끝 (docker·이미지·repo 준비)
#      target_ready    복구 대상으로 쓸 mysql:8.0 컨테이너가 실제 쿼리를 받음 (skip_networking=0)
#    🔴 이것은 «빈 박스에서 직접 부트스트랩» 경로다. 미리 구워 둔 이미지(AMI)로 띄우면 더 짧다 — 그건 안 쟀다.
#
# ② RUN_SHIP=1 인 박스만 ship_cost.sh 를 돌린다.
#
# 아래 SHA·RUN_ID 등 대문자 자리표시자(골뱅이 두 쌍으로 감싼 것)는 띄우기 직전에 launch.sh 가 채운다.
set -uo pipefail
SHA=@@SHA@@
RUN_ID=@@RUN_ID@@
S3=s3://shadowfit-measure-055447613012/shadowfit/$RUN_ID
CAP_MIN=@@CAP_MIN@@
RUN_SHIP=@@RUN_SHIP@@
NODE=@@NODE@@
RIG=/root/init/loadtest/results/server-prep-and-ship-cost-2026-11-08
OUT=/root/rig_out
LOG=/root/round.log
exec > >(tee -a "$LOG") 2>&1
ms() { date +%s%3N; }
T_UD=$(ms)
T_BOOT=$(( T_UD - $(awk '{printf "%d", $1*1000}' /proc/uptime) ))
mkdir -p "$OUT"
mark() { printf '%s\t%s\t%s\n' "$NODE" "$1" "$2" >> "$OUT/prep.tsv"; }
mark boot "$T_BOOT"; mark userdata_start "$T_UD"

up() { aws s3 cp "$LOG" "$S3/$NODE.round.log" --only-show-errors || true; aws s3 sync "$OUT" "$S3/out/$NODE" --only-show-errors || true; }
finish() { echo "== finish: $1 ($(date -Is))"; up; aws s3 cp - "$S3/FINISHED.$NODE" <<< "$1 $(date -Is)" || true; shutdown -h now; }

echo "== start $(date -Is) node=$NODE SHA=$SHA RUN_ID=$RUN_ID CAP_MIN=$CAP_MIN RUN_SHIP=$RUN_SHIP"
( sleep $((CAP_MIN * 60)); echo "🔴 상한 ${CAP_MIN}분 도달 — 끈다"; finish CAP ) &
( while sleep 600; do up; done ) &

cd /root
curl -fsSL "https://raw.githubusercontent.com/Shadowfit/init/$SHA/loadtest/aws/bootstrap.sh" -o bootstrap.sh || finish BOOTSTRAP_DOWNLOAD
mark bootstrap_dl "$(ms)"
grep -q 'ROLE=${ROLE:-db}' bootstrap.sh || finish BOOTSTRAP_ASSERT
ROLE=db REF=$SHA SKIP_FLYWAY=1 bash bootstrap.sh || finish BOOTSTRAP_FAIL
mark bootstrap_done "$(ms)"

# 복구 대상 준비 — 실제 쿼리를 받을 때까지(임시 초기화 서버의 소켓에 속지 않으려고 skip_networking 을 본다)
docker ps --format '{{.Names}}' | grep -E '^shadowfit-' | xargs -r docker stop
docker run -d --name prep_target -e MYSQL_ROOT_PASSWORD=repl mysql:8.0 >/dev/null
for i in $(seq 1 300); do
  [ "$(docker exec prep_target mysql -uroot -prepl -N -s -e 'SELECT @@skip_networking;' 2>/dev/null)" = 0 ] && break
  sleep 1
done
mark target_ready "$(ms)"
docker rm -f prep_target >/dev/null 2>&1
{ echo "instance_type=$(curl -s -H "X-aws-ec2-metadata-token: $(curl -s -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')" http://169.254.169.254/latest/meta-data/instance-type)"; nproc; free -g; df -h /; } > "$OUT/box.txt"
up

if [ "$RUN_SHIP" = "1" ]; then
  [ -f "$RIG/ship_cost.sh" ] || finish RIG_MISSING
  grep -q 'ship_one' "$RIG/ship_cost.sh" || finish RIG_ASSERT
  grep -q '_tpl' /root/init/loadtest/results/binlog-replay-time-2026-11-07/_writer_common.sh || finish WRITER_ASSERT
  env OUT=$OUT/ship WORK=/root/rig_work S3_BASE=$S3/shipstore \
      SEG_SECS_60=480 SEG_SECS_300=900 STRESS_SECS_60=240 STRESS_SECS_300=600 \
      bash "$RIG/ship_cost.sh" > "$OUT/ship_run.log" 2>&1
  echo "== rig exit $?"
fi
finish "DONE"
