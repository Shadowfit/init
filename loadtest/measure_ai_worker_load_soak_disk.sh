#!/usr/bin/env bash
# AI 워커 부하-중 장애 빈도 — 디스크 «수요» 폴러.
#
# 이 스크립트가 있는 이유: 2026-08-28 라운드가 두 번 다 박스 정지로 끝났고, 09-02 CloudWatch
# 사후 조회가 「gp3 기본 처리량 상한(125MiB/s)에 눌러붙었다」를 찾았지만 **인과를 못 세웠다** —
# 결과 문서 §6-7이 직접 적었다:
#
#   "요청이 상한보다 먼저 늘었는지, 상한에 닿은 뒤에 밀린 것뿐인지를 봐야 하는데,
#    그 지표를 이 라운드가 안 걷었다 — 이건 두 인스턴스가 사라진 지금은 영영 못 메운다."
#
# 그 채널이 이것이다. CloudWatch EBS 지표는 gp3에서 **5분 해상도가 한계**인데 사고는 90초 만에
# 났다 — 그래서 이 박스 안 폴러가 대체재가 아니라 **유일한 채널**이다.
#
# 설계: docs/decisions/ai-worker-load-soak-experiment.md
# 매니페스트: loadtest/aws/ROUND-2026-09-08-ebs-causality.md §4-ㄱ
#
# 대상 박스(shadowfit-* 컨테이너가 떠 있는 곳)에서 root로 nohup 실행.
# _monitor.sh(60초)·_diagnostics.sh(7초)와 **같이** 돈다 — 겹치는 채널은 없다.
set -uo pipefail

INTERVAL_SEC=${INTERVAL_SEC:-7}            # _diagnostics.sh 와 같은 리듬
DURATION_SEC=${DURATION_SEC:-10800}        # 부하기 쪽과 맞춘다
TAIL_SEC=${TAIL_SEC:-600}                  # 부하 종료 후에도 관찰(상한에서 언제 내려오나)
PW=${PW:-1234}
OUT=${OUT:-/root/ai_worker_load_soak_disk.csv}
DEV=${DEV:-}                               # 비우면 루트 파일시스템에서 유도한다
CONTAINERS=${CONTAINERS:-"shadowfit-ai shadowfit-backend shadowfit-mysql"}

# ── 게이트 ────────────────────────────────────────────────────────────────
# 🔴 여기서 죽는 것이 이 스크립트의 일이다. 장치를 못 찾은 채로 돌면 표는 멀쩡한데 값이
#    전부 -1 이고, 그건 #271(「없는 카운터를 8판 내내 읽고 0을 찍었다」)의 재발이다.
die() { echo "🔴 $*" >&2; exit 1; }

[ -r /proc/diskstats ] || die "/proc/diskstats 를 못 읽는다 — 이 채널 없이는 이 라운드를 돌 이유가 없다"

if [ -z "$DEV" ]; then
  src=$(findmnt -no SOURCE / 2>/dev/null) || src=""
  [ -n "$src" ] || die "루트 파일시스템의 장치를 못 찾았다(findmnt 실패) — DEV=<장치명> 으로 직접 줄 것"
  base=$(basename "$src")
  # nvme0n1p1 -> nvme0n1 · xvda1 -> xvda · sda1 -> sda
  case "$base" in
    nvme*) DEV=$(echo "$base" | sed -E 's/p[0-9]+$//') ;;
    *)     DEV=$(echo "$base" | sed -E 's/[0-9]+$//') ;;
  esac
fi

grep -qE "[[:space:]]${DEV}[[:space:]]" /proc/diskstats \
  || die "장치 '$DEV' 가 /proc/diskstats 에 없다 — DEV 를 직접 줄 것. 후보: $(awk '{print $3}' /proc/diskstats | tr '\n' ' ')"

# MySQL 상태 채널이 살아 있는지도 지금 확인한다 — 부하 중에 처음 알면 늦다.
if ! docker exec -e MYSQL_PWD="$PW" shadowfit-mysql mysql -uroot -N \
     -e "SHOW GLOBAL STATUS LIKE 'Innodb_data_written';" >/dev/null 2>&1; then
  echo "⚠️  MySQL 상태 조회가 지금 실패한다 — 컨테이너 이름·PW 를 확인할 것." >&2
  echo "    (막지는 않는다. 부하 중 무응답은 그 자체가 관측이라 -1 로 계속 찍는다)" >&2
fi

# ── 읽는 것 ───────────────────────────────────────────────────────────────
# /proc/diskstats 필드: 3=장치 4=읽기완료 6=읽은섹터 8=쓰기완료 10=쓴섹터 12=진행중IO
# 섹터는 리눅스 관례대로 **512바이트 고정**이다(장치의 물리 섹터 크기와 무관).
read_dev() { awk -v d="$DEV" '$3==d {print $6, $10, $12; found=1} END{if(!found) print -1, -1, -1}' /proc/diskstats; }

# 🔴 위치가 아니라 **이름**으로 받는다 — SHOW GLOBAL STATUS 는 이름 오름차순이라
#    Com_commit 이 Innodb_* 보다 먼저 나온다. 위치로 읽으면 열이 통째로 밀린 채
#    표가 멀쩡해 보인다(#271 계열의 조용한 결함).
read_mysql() {
  docker exec -e MYSQL_PWD="$PW" shadowfit-mysql mysql -uroot -N -e \
    "SHOW GLOBAL STATUS WHERE Variable_name IN
       ('Innodb_data_writes','Innodb_data_written','Innodb_os_log_written','Com_commit');" 2>/dev/null \
    | awk '{v[$1]=$2}
           END{ n=split("Innodb_data_writes Innodb_data_written Innodb_os_log_written Com_commit", k, " ");
                for(i=1;i<=n;i++) printf "%s ", (k[i] in v ? v[k[i]] : -1) }'
}

# 컨테이너별 누적 블록 I/O 와 json-file 로그 크기.
# 🔑 로그 크기가 1순위 후보다 — 1차 사고에서 07:54에 부하를 껐는데도 08:05까지 처리량이
#    상한에 붙어 있었다. 부하가 없는데 쓰기가 계속됐다는 뜻이라 「누가 쓰는가」를 갈라야 한다.
read_containers() {
  local c out=""
  for c in $CONTAINERS; do
    local bio logsz
    bio=$(docker stats --no-stream --format '{{.BlockIO}}' "$c" 2>/dev/null | tr -d ' ' || echo "n/a")
    [ -n "$bio" ] || bio="n/a"
    local lp
    lp=$(docker inspect --format '{{.LogPath}}' "$c" 2>/dev/null || echo "")
    if [ -n "$lp" ] && [ -f "$lp" ]; then
      logsz=$(stat -c %s "$lp" 2>/dev/null || echo -1)
    else
      logsz=-1
    fi
    out="${out}${bio},${logsz},"
  done
  echo "${out%,}"
}

# ── 폴링 ──────────────────────────────────────────────────────────────────
DEADLINE=$(( $(date +%s) + DURATION_SEC + TAIL_SEC ))

{
  echo "# 장치=$DEV interval=${INTERVAL_SEC}s duration=${DURATION_SEC}s tail=${TAIL_SEC}s"
  echo "# 섹터=512B 고정 · rate 는 직전 표본과의 차분 / 실경과초"
  echo "# gp3 기본 처리량 상한 = 128,000 KiB/s (=125MiB/s) — write_KiBps 가 여기 눌러붙는지가 관측 대상"
  printf 'epoch,read_KiBps,write_KiBps,io_inflight,rd_sectors_cum,wr_sectors_cum,'
  printf 'innodb_data_writes,innodb_data_written,innodb_os_log_written,com_commit'
  for c in $CONTAINERS; do printf ',%s_blkio,%s_logbytes' "$c" "$c"; done
  printf '\n'
} > "$OUT"

prev_rd=-1; prev_wr=-1; prev_ts=0

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  ts=$(date +%s)
  read -r rd wr inflight <<< "$(read_dev)"

  if [ "$prev_rd" -ge 0 ] && [ "$rd" -ge 0 ] && [ "$ts" -gt "$prev_ts" ]; then
    # 512B 섹터 → KiB/s : (Δ섹터 × 512) / Δ초 / 1024 = Δ섹터 / Δ초 / 2
    rd_rate=$(awk -v a="$rd" -v b="$prev_rd" -v d="$((ts-prev_ts))" 'BEGIN{printf "%.1f",(a-b)/d/2}')
    wr_rate=$(awk -v a="$wr" -v b="$prev_wr" -v d="$((ts-prev_ts))" 'BEGIN{printf "%.1f",(a-b)/d/2}')
  else
    rd_rate=""; wr_rate=""   # 첫 표본은 차분이 없다 — 0 으로 채우지 않는다
  fi

  read -r idw idwn iolw commit <<< "$(read_mysql)"
  cstats=$(read_containers)

  echo "$ts,$rd_rate,$wr_rate,$inflight,$rd,$wr,$idw,$idwn,$iolw,$commit,$cstats" >> "$OUT"

  prev_rd=$rd; prev_wr=$wr; prev_ts=$ts
  sleep "$INTERVAL_SEC"
done

echo "# END $(date -u +%FT%TZ)" >> "$OUT"
