#!/bin/bash
# EC2 무인 라운드 — binlog 재생 시간 · 호스트 상실 RPO 공용 (user-data 로 넣는다)
#
# 무인 라운드 규칙(loadtest/aws/README.md · CLAUDE.md):
#   - bootstrap 은 **커밋 SHA 로 고정**해 받는다(브랜치 URL 은 CDN 캐시로 옛 파일을 준다)
#   - 받은 파일에 기대한 것이 있는지 grep 으로 단언한다 — 없으면 조용히 다른 것을 잰다
#   - 러너(run_all.sh)를 안 거치는 단독 판이라 **감시견**을 같이 심는다: DONE 이면 업로드 후 끄고,
#     상한 시각을 넘기면 판이 멈춰 있어도 업로드 후 끈다
#   - 인스턴스는 --instance-initiated-shutdown-behavior terminate 로 띄운다(꺼지면 EBS 까지 지워진다)
#
# 아래 @@...@@ 는 띄우기 직전에 채운다.
set -uo pipefail
SHA=@@SHA@@
RUN_ID=@@RUN_ID@@
S3=s3://shadowfit-measure-055447613012/shadowfit/$RUN_ID
CAP_MIN=@@CAP_MIN@@
RIG=/root/init/loadtest/results/@@RIG_DIR@@
OUT=/root/rig_out
LOG=/root/round.log
exec > >(tee -a "$LOG") 2>&1

up() { aws s3 cp "$LOG" "$S3/round.log" --only-show-errors || true; [ -d "$OUT" ] && aws s3 sync "$OUT" "$S3/out" --only-show-errors || true; }
finish() { echo "== finish: $1 ($(date -Is))"; up; aws s3 cp - "$S3/FINISHED" <<< "$1 $(date -Is)" || true; shutdown -h now; }

echo "== start $(date -Is) SHA=$SHA RUN_ID=$RUN_ID CAP_MIN=$CAP_MIN"
# 감시견 — 상한 시각이 지나면 무조건 업로드 후 끈다
( sleep $((CAP_MIN * 60)); echo "🔴 상한 ${CAP_MIN}분 도달 — 판이 안 끝났어도 끈다"; finish CAP ) &
# 중간 업로드 — 10분마다
( while sleep 600; do up; done ) &

cd /root
curl -fsSL "https://raw.githubusercontent.com/Shadowfit/init/$SHA/loadtest/aws/bootstrap.sh" -o bootstrap.sh || finish BOOTSTRAP_DOWNLOAD
grep -q 'ROLE=${ROLE:-db}' bootstrap.sh || finish BOOTSTRAP_ASSERT
ROLE=db REF=$SHA SKIP_FLYWAY=1 bash bootstrap.sh || finish BOOTSTRAP_FAIL

[ -f "$RIG/@@RIG_SCRIPT@@" ] || finish RIG_MISSING
grep -q '@@ASSERT@@' "$RIG/@@RIG_SCRIPT@@" || finish RIG_ASSERT     # 기대한 수정이 들어 있나
grep -q '_tpl' /root/init/loadtest/results/binlog-replay-time-2026-11-07/_writer_common.sh || finish WRITER_ASSERT  # 실제 JSON writer

# 부트스트랩이 띄운 프로젝트 MySQL 은 이 판과 무관하다 — 메모리·디스크를 안 나눠 쓰게 내린다
docker ps --format '{{.Names}}' | grep -E '^shadowfit-' | xargs -r docker stop

{ echo "instance_type=$(curl -s -H "X-aws-ec2-metadata-token: $(curl -s -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')" http://169.254.169.254/latest/meta-data/instance-type)";
  nproc; free -g; df -h /; } > /root/box.txt
mkdir -p "$OUT"; cp /root/box.txt "$OUT/"

env OUT=$OUT WORK=/root/rig_work @@RIG_ENV@@ bash "$RIG/@@RIG_SCRIPT@@" > "$OUT/run.log" 2>&1
rc=$?
echo "== rig exit $rc"
finish "DONE rc=$rc"
