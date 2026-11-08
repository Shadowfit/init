#!/bin/bash
# 프로세스 재기동 RTO — mysqld 가 죽은 순간부터 다시 쿼리를 받기까지 (RPO·RTO 분기점 §4 첫 줄 · §6-2 마지막 빈칸)
#
# 질문: OOM kill·크래시로 mysqld 만 죽고 디스크는 남았을 때, 다시 쓸 수 있기까지 얼마나 걸리나?
#       그 시간은 «죽는 순간 디스크 반영이 덜 된 변경(redo, 체크포인트 이후 몫)» 에 따라 늘어날 것으로 예상한다.
#
# 팔 (죽이는 순간의 쓰기 상태, 2026-11-08 사용자 confirm):
#   idle    쓰기 없음 — 기준선
#   paced   문서 §8-1 가정 피크 45행/초(측정 아님)로 PACED_SECS 동안 쓰다가 죽임
#   stress  4개 연결이 쉬지 않고 STRESS_SECS 동안 쓰다가 죽임 — 상한 확인용, 운영 값 아님
# 순서: 앞 세 판은 팔마다 하나씩 버림, 그 뒤 세 바퀴는 팔 순서를 돌린다.
#
# 재는 것 (한 판 한 줄):
#   ckpt_age_b     죽이기 직전 «LSN − 마지막 체크포인트» (바이트) = 재기동 때 다시 적용할 redo 의 상한
#   dirty_pages    죽이기 직전 버퍼 풀의 더러운 페이지 수
#   ready_ms       docker start 부터 «SELECT 1» 이 처음 성공하기까지 (docker exec 고정 비용 포함 — exec_ms 로 따로 남김)
#   down_ms        kill 부터 같은 시각까지 (= 사람·감시 시간을 뺀 재기동 RTO 하한)
#   recov_*        서버 로그의 시각으로 쪼갠 구간 (크래시 복구 시작 → redo 적용 끝 → 연결 받음)
#   lost_tx        클라이언트가 «커밋 성공» 을 받았는데 재기동 뒤 없는 트랜잭션 — flush=1·sync_binlog=1 이라 0 이어야 한다
#
# 🔴 죽이기 = docker kill (SIGKILL). 컨테이너만 죽고 호스트·볼륨은 남는다 — 문서 §4 의 «프로세스 죽음» 그대로.
# 🔴 재시작은 바로 한다(감시·사람 시간 0). 실제 RTO 는 «죽은 걸 알아채고 다시 띄우는» 시간이 더해진다.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/_out}"
WORK="${WORK:-$HERE/_work}"
mkdir -p "$OUT" "$WORK"

IMG=${MYSQL_IMAGE:-mysql:8.0}
PW=repl
C=rs_db; VOL=rs_data; NET=rs_net
TRIALS=${TRIALS:-"idle paced stress  stress idle paced  paced stress idle  idle stress paced"}
BURN=${BURN:-3}
PACED_ROWS_PER_S=${PACED_ROWS_PER_S:-45}
ROWS_PER_TX=${ROWS_PER_TX:-25}
STRESS_CONNS=${STRESS_CONNS:-4}
IDLE_SECS=${IDLE_SECS:-30}
PACED_SECS=${PACED_SECS:-120}
STRESS_SECS=${STRESS_SECS:-240}
SERVER_OPTS=(--server-id=1 --log-bin=binlog --binlog-format=ROW
             --innodb-flush-log-at-trx-commit=1 --sync-binlog=1
             --innodb-buffer-pool-size=${BUFFER_POOL:-2G} --innodb-redo-log-capacity=${REDO_CAPACITY:-2G})

log() { printf '[restart] %s\n' "$*" >&2; }
die() { log "🔴 $*"; exit 1; }
q()  { docker exec -i "$1" mysql -uroot -p"$PW" -N -s -e "$2" 2>/dev/null; }
now_ms() { date +%s%3N; }
source "$HERE/../binlog-replay-time-2026-11-07/_writer_common.sh"

wait_ready() {
  local i
  for i in $(seq 1 300); do [ "$(q "$1" "SELECT @@skip_networking;")" = 0 ] && return 0; sleep 1; done
  die "$1 가 5분 안에 쿼리를 안 받는다"
}

make_paced_proc() {
  cat > "$WORK/_paced.sql" <<'SQL'
DELIMITER //
DROP PROCEDURE IF EXISTS lab.paced_ack //
CREATE PROCEDURE lab.paced_ack(IN p_session BIGINT, IN p_rows INT, IN p_secs INT, IN p_gap DOUBLE)
BEGIN
  DECLARE v_end DATETIME(6);
  DECLARE v_off INT DEFAULT 0;
  DECLARE v_ver BIGINT;
  SET v_end = SYSDATE(6) + INTERVAL p_secs SECOND;
  WHILE SYSDATE(6) < v_end DO
    START TRANSACTION;
      INSERT INTO lab.pose_data_scale (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message)
        SELECT p_session, ts, jc, 75.0, 1, fb FROM lab._tpl WHERE n >= v_off AND n < v_off + p_rows;
      UPDATE lab.session_probe SET version = version + 1, last_active_at = SYSDATE(6) WHERE id = p_session;
      SELECT version INTO v_ver FROM lab.session_probe WHERE id = p_session;
    COMMIT;
    SELECT p_session, v_ver, ROUND(UNIX_TIMESTAMP(SYSDATE(6)) * 1000000);
    SET v_off = (v_off + p_rows) % (750 - p_rows);
    DO SLEEP(p_gap);
  END WHILE;
END //
DELIMITER ;
SQL
  docker exec -i "$C" mysql -uroot -p"$PW" < "$WORK/_paced.sql" 2>/dev/null || die "paced 프로시저 생성 실패"
}

fresh_db() {
  docker rm -f "$C" >/dev/null 2>&1 || true
  docker volume rm -f "$VOL" >/dev/null 2>&1 || true
  docker volume create "$VOL" >/dev/null
  docker network create "$NET" >/dev/null 2>&1 || true
  docker run -d --name "$C" --network "$NET" -e MYSQL_ROOT_PASSWORD="$PW" -v "$VOL:/var/lib/mysql" \
    "$IMG" "${SERVER_OPTS[@]}" >/dev/null
  wait_ready "$C"
  q "$C" "
    CREATE DATABASE lab;
    CREATE TABLE lab.pose_data_scale (
      id bigint NOT NULL AUTO_INCREMENT, session_id bigint NOT NULL, timestamp_sec double NOT NULL,
      joint_coordinates text COLLATE utf8mb4_unicode_ci NOT NULL, sync_rate double DEFAULT NULL,
      is_correct tinyint(1) DEFAULT 1, feedback_message varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
      created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    CREATE TABLE lab.session_probe (id BIGINT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0,
                                    last_active_at DATETIME(6) NULL) ENGINE=InnoDB;" || die "스키마 실패"
  local i vals=""
  for i in $(seq 1 "$STRESS_CONNS"); do vals="$vals${vals:+,}($((100000 + i)),0,NULL)"; done
  q "$C" "INSERT INTO lab.session_probe VALUES $vals;"
  setup_writer "$C"
  make_paced_proc
}

# 서버 로그에서 «t0 이후 처음 나온» 패턴의 시각(ms). docker logs --timestamps 의 RFC3339 나노초를 쓴다
log_ms() {  # $1 since(RFC3339) $2 패턴
  docker logs --timestamps --since "$1" "$C" 2>&1 | grep -m1 -E "$2" | awk '{print $1}' \
    | xargs -r -I{} date -d {} +%s%3N
}

trial() {  # $1 번호 $2 팔 $3 kind
  local idx=$1 arm=$2 kind=$3 tdir="$WORK/t$1" i pids=()
  mkdir -p "$tdir"
  log "── 판 $idx ($kind) 팔=$arm"
  fresh_db
  case "$arm" in
    idle)   sleep "$IDLE_SECS" ;;
    paced)
      local gap; gap=$(awk -v r="$ROWS_PER_TX" -v p="$PACED_ROWS_PER_S" 'BEGIN{printf "%.3f", r/p}')
      docker run --rm --network "$NET" "$IMG" mysql -h "$C" -uroot -p"$PW" --unbuffered -N -B \
        -e "CALL lab.paced_ack(100001, $ROWS_PER_TX, 36000, $gap);" > "$tdir/w_1.tsv" 2>/dev/null &
      pids+=($!); sleep "$PACED_SECS" ;;
    stress)
      for i in $(seq 1 "$STRESS_CONNS"); do
        docker run --rm --network "$NET" "$IMG" mysql -h "$C" -uroot -p"$PW" --unbuffered -N -B \
          -e "CALL lab.load_run($((100000 + i)), $ROWS_PER_TX, 36000);" > "$tdir/w_$i.tsv" 2>/dev/null &
        pids+=($!)
      done
      sleep "$STRESS_SECS" ;;
  esac

  # 죽이기 직전 상태 — redo 체크포인트 나이 · 더러운 페이지
  local st lsn ckpt dirty
  st=$(docker exec "$C" mysql -uroot -p"$PW" -e "SHOW ENGINE INNODB STATUS\G" 2>/dev/null)
  lsn=$(awk '/^Log sequence number/{print $4; exit}' <<<"$st")
  ckpt=$(awk '/^Last checkpoint at/{print $4; exit}' <<<"$st")
  dirty=$(q "$C" "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_pages_dirty';")
  local o0 o1 exec_ms; o0=$(now_ms); q "$C" "SELECT 1;" >/dev/null; o1=$(now_ms); exec_ms=$((o1 - o0))

  local since t_kill t_start t_ready
  since=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)
  t_kill=$(now_ms)
  docker kill "$C" >/dev/null                      # SIGKILL — OOM kill 과 같은 신호
  for i in "${pids[@]}"; do wait "$i" 2>/dev/null || true; done
  t_start=$(now_ms)
  docker start "$C" >/dev/null
  until q "$C" "SELECT 1;" >/dev/null; do sleep 0.05; done
  t_ready=$(now_ms)

  local r_start r_redo_done r_conn
  r_start=$(log_ms "$since" "Starting crash recovery|Crash recovery" || true)
  r_redo_done=$(log_ms "$since" "Apply batch completed|redo log processing" || true)
  r_conn=$(log_ms "$since" "ready for connections" || true)
  docker logs --timestamps --since "$since" "$C" > "$tdir/server.log" 2>&1 || true

  # 잃은 쓰기 — 클라이언트가 받은 마지막 version 합 vs 재기동 뒤 합
  local acked rest lost=NA
  if [ "$arm" != idle ]; then
    acked=$(for f in "$tdir"/w_*.tsv; do awk 'NF>=3{v=$2} END{print v+0}' "$f"; done | awk '{s+=$1} END{print s+0}')
    rest=$(q "$C" "SELECT COALESCE(SUM(version),0) FROM lab.session_probe;")
    lost=$((acked - rest))
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$idx" "$kind" "$arm" "$((lsn - ckpt))" "${dirty:-NA}" "$((t_ready - t_start))" "$((t_ready - t_kill))" \
    "${r_start:+$((r_start - t_start))}" "${r_redo_done:+$((r_redo_done - t_start))}" "${r_conn:+$((r_conn - t_start))}" \
    "$exec_ms" "$lost" "$((t_start - t_kill))" >> "$OUT/trials.tsv"
  log "판 $idx: redo $(( (lsn - ckpt) / 1048576 ))MB · 더러운 페이지 ${dirty} → 재기동 $((t_ready - t_start))ms · 잃은 트랜잭션 $lost"
}

printf 'trial\tkind\tarm\tckpt_age_b\tdirty_pages\tready_ms\tdown_ms\trecov_start_ms\tredo_done_ms\tconn_ms\texec_ms\tlost_tx\tkill_to_start_ms\n' > "$OUT/trials.tsv"
{
  echo "# 프로세스 재기동 실험 조건 ($(date -Is))"
  echo "mysql image : $IMG ($(docker image inspect -f '{{index .RepoDigests 0}}' "$IMG" 2>/dev/null))"
  echo "server opts : ${SERVER_OPTS[*]}"
  echo "trials      : $TRIALS (앞 $BURN 판 버림)"
  echo "idle ${IDLE_SECS}s · paced ${PACED_ROWS_PER_S}행/초(문서 §8-1 가정) ${PACED_SECS}s · stress ${STRESS_CONNS}연결 ${STRESS_SECS}s"
  echo "recov_*/conn_ms 는 docker start 기준, 서버 로그 시각(초 단위 정밀도는 로그 형식에 따름)"
} > "$OUT/CONDITIONS.txt"
n=0
for arm in $TRIALS; do
  n=$((n + 1))
  trial "$n" "$arm" "$([ $n -le "$BURN" ] && echo 버림 || echo 본판)"
done
cp -r "$WORK"/t* "$OUT/" 2>/dev/null || true
find "$OUT" -name 'w_*.tsv' -size +5M -delete    # writer 기록은 크면 버린다(합만 쓰면 된다)
docker rm -f "$C" >/dev/null 2>&1 || true
log "완료 → $OUT/trials.tsv"
