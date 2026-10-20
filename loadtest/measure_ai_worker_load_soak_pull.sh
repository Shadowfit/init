#!/usr/bin/env bash
# AI 워커 부하-중 장애 빈도 — 증거 회수기. **부하기 박스에서** 돈다.
#
# 이 스크립트가 있는 이유: 2026-08-28 라운드는 두 번 다 **대상 박스가 얼어서 그 안의 로그를
# 못 건졌다.** 1차는 systemd 저널이 부하 시작 *전*에 죽었고, SSH 가 끝내 안 붙어 리부트 전
# dmesg 를 통째로 잃었다. 계측을 아무리 잘 걸어도 **그 계측이 대상 박스와 같이 죽으면 0** 이다.
#
# 그래서 대상의 로그를 부하기(살아남는 쪽)로 계속 흘려 받는다. 대상이 얼어도
# **그 순간까지의 줄은 여기 남는다.**
#
# 설계: docs/decisions/ai-worker-load-soak-experiment.md
# 매니페스트: loadtest/aws/ROUND-2026-09-08-ebs-causality.md §4-ㄴ
set -uo pipefail

TARGET=${TARGET:?TARGET(대상 박스 사설 IP) 필요}
SSH_USER=${SSH_USER:-ec2-user}
SSH_KEY=${SSH_KEY:-$HOME/.ssh/shadowfit-measure.pem}
DURATION_SEC=${DURATION_SEC:-10800}
TAIL_SEC=${TAIL_SEC:-600}
HB_INTERVAL=${HB_INTERVAL:-7}          # 하트비트 간격 — 대상 폴러(7초)와 같은 리듬
OUT_DIR=${OUT_DIR:-/root/pulled}
# 대상에서 빨아올 파일들. 대상 폴러 셋의 기본 출력 경로다.
FILES=${FILES:-"/root/ai_worker_load_soak_monitor.log /root/innodb_status_poll.log /root/ai_worker_load_soak_disk.csv /root/backend_threaddumps.log"}

die() { echo "🔴 $*" >&2; exit 1; }
SSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=no -o ConnectTimeout=5 -o BatchMode=yes ${SSH_USER}@${TARGET}"

mkdir -p "$OUT_DIR" || die "$OUT_DIR 를 못 만든다"

# ── 게이트 ────────────────────────────────────────────────────────────────
# 🔴 여기서 죽는 것이 이 스크립트의 일이다. 붙지도 않는데 조용히 돌면 빈 파일만 남고,
#    그건 "증거를 회수했다"는 착각을 만든다 — 08-28 이 잃은 것을 또 잃는 모양이다.
$SSH 'echo ok' >/dev/null 2>&1 || die "대상($TARGET)에 SSH 가 안 붙는다 — 키·보안그룹·IP 확인"

present=""
for f in $FILES; do
  if $SSH "test -e '$f'" 2>/dev/null; then present="$present $f"; else echo "⚠️  대상에 없다(건너뜀): $f" >&2; fi
done
[ -n "$present" ] || die "빨아올 파일이 하나도 없다 — 대상 폴러를 **먼저** 띄웠는지 확인할 것"

DEADLINE=$(( $(date +%s) + DURATION_SEC + TAIL_SEC ))

# ── 하트비트 ──────────────────────────────────────────────────────────────
# 08-28 의 결정적 관측은 「세 독립 채널이 같은 30초 창에서 동시에 멎었다」였다. 그 창을
# **부하기 시계로** 못 박으려면 이쪽에서 찍는 시각이 필요하다 — 대상이 얼면 대상 시계도 멎는다.
HB="$OUT_DIR/heartbeat.csv"
echo "local_epoch,rc,remote_epoch,note" > "$HB"
(
  while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    now=$(date +%s)
    remote=$(timeout 5 $SSH 'date +%s' 2>/dev/null); rc=$?
    if [ "$rc" -eq 0 ] && [ -n "$remote" ]; then
      echo "$now,0,$remote," >> "$HB"
    else
      echo "$now,$rc,,대상 무응답" >> "$HB"
    fi
    sleep "$HB_INTERVAL"
  done
) &
HB_PID=$!

# ── 스트리밍 ──────────────────────────────────────────────────────────────
# `tail -n +1 -F` 는 파일 처음부터 주고, 이후 추가분을 계속 따라간다. 연결이 끊기면 tail 이
# 끝나므로 **그 시점이 곧 「여기까지 받았다」** 이다. 자동 재접속은 일부러 안 한다 —
# 끊긴 자리를 로그에 남기는 편이 조용히 다시 붙는 것보다 진단에 쓸모 있다.
PIDS=""
for f in $present; do
  base=$(basename "$f")
  {
    echo "=== PULL START $(date -u +%FT%TZ) src=$f ==="
    $SSH "tail -n +1 -F '$f'" 2>&1
    echo "=== PULL END $(date -u +%FT%TZ) (스트림 종료 — 대상이 멎었거나 판이 끝났다) ==="
  } >> "$OUT_DIR/$base" &
  PIDS="$PIDS $!"
  echo "  스트리밍 시작: $f -> $OUT_DIR/$base"
done

echo "## 회수기 가동 — 종료 예정 epoch=$DEADLINE (하트비트 pid $HB_PID)"

while [ "$(date +%s)" -lt "$DEADLINE" ]; do sleep 30; done

kill $PIDS "$HB_PID" 2>/dev/null
echo "## 완료 — $(date -u +%FT%TZ)"
echo "  🔑 판정 먼저 볼 것: $HB 에서 rc≠0 이 처음 나온 시각 = 대상이 멎은 순간(부하기 시계)"
