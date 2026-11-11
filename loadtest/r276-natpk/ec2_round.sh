#!/bin/bash
# EC2 무인 라운드 — user-data 로 넣는다. root 로 돈다.
#
#   박스 1 (c7i.xlarge):  PHASES="1"     — ① 삽입 처리량 (08-23 uk-bufferpool 과 같은 타입)
#   박스 2 (m6i.xlarge):  PHASES="2 3"   — ② PK 재구성 → ③ 읽기 (08-12 online-ddl 과 같은 타입)
#
# 지키는 것 (project_unattended_aws_round_recipe):
#   · 저장소는 **커밋 SHA** 로 받는다(브랜치 URL 은 CDN 캐시) · 받은 rig 이 기대한 것인지 grep 으로 단언
#   · 끝나면 산출물·로그를 S3 에 올리고 스스로 내려간다 — 판이 멈춰도 DEADLINE_MIN 이 지나면 내려간다
#   · 인스턴스는 --instance-initiated-shutdown-behavior terminate 로 띄울 것(볼륨까지 지워지게)
#
# 아래 세 줄은 launch 때 sed 로 채운다.
REF=__REF__
PHASES="__PHASES__"
RUN_ID=__RUN_ID__
DEADLINE_MIN=__DEADLINE_MIN__
# 박스별 rig 설정(SEQ · ONLINE_LOG_BYTES · WRITER_SECS 등) — launch 가 «export K=V ...» 한 줄로 채운다. 없으면 빈 줄
__ENV__
BUCKET=s3://shadowfit-measure-055447613012/shadowfit/$RUN_ID
DROP_CACHES=1

LOG=/root/natpk_round.log
exec > >(tee -a "$LOG") 2>&1
set -uo pipefail
echo "[round] start $(date -u +%FT%TZ) REF=$REF PHASES=$PHASES RUN_ID=$RUN_ID SEQ=${SEQ:-기본} ONLINE_LOG_BYTES=${ONLINE_LOG_BYTES:-기본} WRITER_SECS=${WRITER_SECS:-기본}"

up(){ aws s3 cp --quiet "$@" || echo "[round] ⚠️ 업로드 실패: $*"; }
finish(){
  echo "[round] finish $(date -u +%FT%TZ) — $1"
  [ -d /root/init/loadtest/r276-natpk/_out ] && aws s3 sync --quiet /root/init/loadtest/r276-natpk/_out "$BUCKET/$(hostname -s)/_out"
  up "$LOG" "$BUCKET/$(hostname -s)/natpk_round.log"
  sync; shutdown -h now
}
# 감시견 — 판이 멈춰도 요금을 안 흘린다
( sleep $((DEADLINE_MIN * 60)); echo "[round] 🔴 상한 ${DEADLINE_MIN}분 초과"; finish "deadline" ) &

dnf -y install docker git awscli-2 >/dev/null || finish "dnf 실패"
if ! docker compose version >/dev/null 2>&1; then
  mkdir -p /usr/libexec/docker/cli-plugins
  curl -fsSL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-$(uname -m)" \
    -o /usr/libexec/docker/cli-plugins/docker-compose && chmod +x /usr/libexec/docker/cli-plugins/docker-compose \
    || finish "compose 플러그인 실패"
fi
systemctl enable --now docker || finish "docker 기동 실패"

git clone -q https://github.com/Shadowfit/init.git /root/init || finish "clone 실패"
cd /root/init && git checkout -q "$REF" || finish "checkout $REF 실패"
cd loadtest/r276-natpk || finish "rig 폴더 없음"
grep -q "cmd_wsum" natpk.py && grep -q "load-at-startup=OFF" compose.yml && grep -q "N0" phase2_rebuild.sh && grep -q "ONLINE_LOG_BYTES" compose.yml || finish "rig 이 기대한 판이 아니다"
echo "[round] rig $(git rev-parse --short HEAD) · $(nproc) vCPU · $(free -g | awk '/Mem/{print $2}')GB"

for p in $PHASES; do
  case $p in
    1) bash phase1_insert.sh || echo "[round] phase1 rc=$?" ;;
    2) bash phase2_rebuild.sh || echo "[round] phase2 rc=$?" ;;
    3) DROP_CACHES=$DROP_CACHES bash phase3_reads.sh || echo "[round] phase3 rc=$?" ;;
  esac
  aws s3 sync --quiet _out "$BUCKET/$(hostname -s)/_out"
done
finish "done"
