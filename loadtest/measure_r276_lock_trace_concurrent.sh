#!/usr/bin/env bash
# #276 — 결정적 trace(measure_r276_lock_trace.sh)의 판정을 동시 부하에서 확인한다
#
# trace 는 «두 세션 · 한 순서» 라 「데드락 0」을 일반화할 수 없다. 여기서는 기존 확률 rig
# (measure_r276_deadlock.sh 의 same_partition 팔)과 같은 모양 — 워커 W 개가 각자 다른 세션의
# 같은 R 행을 I 번 반복(첫 문장만 신규, 나머지 전부 중복) — 을 팔 넷에 건다:
#   base           운영 그대로 (RR · PK=(id, created_at) · uk_pose_event)
#   read_committed 격리수준만 RC
#   natural_pk     auto-increment id 를 없애고 멱등 키를 PK 로
#   natural_pk_keep_id  멱등 키를 PK 로 + id 는 AUTO_INCREMENT 보조 인덱스로 남김(리포트의 poseDataId 참조 보존)
# 각 문장은 autocommit(= 문장 하나가 트랜잭션 하나). 판 순서는 블록마다 회전(라틴 방격), 첫 블록 버림.
#
# 무대는 trace 와 같은 격리 컨테이너. 운영 compose DB 는 안 건드린다.
set -uo pipefail
export MSYS_NO_PATHCONV=1

CONTAINER=${CONTAINER:-r276-locktrace}
WORKERS=${WORKERS:-8}
ITER=${ITER:-40}
ROWS=${ROWS:-25}
BLOCKS=${BLOCKS:-4}          # 첫 블록은 버림
ARMS=(base read_committed natural_pk natural_pk_keep_id)

read -r -d '' RUN <<'EOS'
set -u
ARM=$1 W=$2 I=$3 R=$4
M="mysql -uroot -proot shadowfit"
$M -e "DROP TABLE IF EXISTS pose_lab; CREATE TABLE pose_lab LIKE pose_data;" 2>/dev/null
if [ "$ARM" = natural_pk ]; then
  $M -e "ALTER TABLE pose_lab MODIFY id BIGINT NOT NULL; ALTER TABLE pose_lab DROP INDEX uk_pose_event, DROP PRIMARY KEY, DROP COLUMN id, ADD PRIMARY KEY (session_id, rep_number, timestamp_sec, created_at);" 2>/dev/null
fi
if [ "$ARM" = natural_pk_keep_id ]; then
  $M -e "ALTER TABLE pose_lab DROP INDEX uk_pose_event, DROP PRIMARY KEY, ADD PRIMARY KEY (session_id, rep_number, timestamp_sec, created_at), ADD KEY idx_pose_id (id);" 2>/dev/null
fi
PRE=""; [ "$ARM" = read_committed ] && PRE="SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;"
cd /tmp && rm -f w*.sql w*.err
for w in $(seq 1 "$W"); do
  sid=$((900 + w)); vals=""
  for r in $(seq 1 "$R"); do
    vals="$vals${vals:+,}($sid, $r, $r.000, JSON_OBJECT('k',1), 50.00, 0.00, NULL, '2026-05-15 10:00:00')"
  done
  { echo "$PRE"; for i in $(seq 1 "$I"); do
      echo "INSERT INTO pose_lab (session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate, smoothed_knee_angle, feedback_message, created_at) VALUES $vals ON DUPLICATE KEY UPDATE session_id = session_id;"
    done; } > "w$w.sql"
done
for w in $(seq 1 "$W"); do mysql -ut1 -pp1 --force shadowfit < "w$w.sql" 2> "w$w.err" & done
wait
dead=$(cat w*.err | grep -c 'ERROR 1213' || true)
other=$(cat w*.err | grep 'ERROR' | grep -vc 'ERROR 1213' || true)
rows=$($M -N -e "SELECT COUNT(*) FROM pose_lab" 2>/dev/null)
echo "$ARM $dead $((W * I)) $other $rows"
EOS
docker exec -i "$CONTAINER" bash -c 'cat > /tmp/run.sh' <<<"$RUN"

echo "# MySQL $(docker exec "$CONTAINER" mysql -uroot -proot -N -e 'select version()' 2>/dev/null) · W=$WORKERS I=$ITER R=$ROWS · 블록 $BLOCKS(첫 블록 버림)"
echo "# block order arm deadlocks attempts other_errors final_rows"
n=${#ARMS[@]}
for b in $(seq 0 $((BLOCKS - 1))); do
  for k in $(seq 0 $((n - 1))); do
    arm=${ARMS[$(( (k + b) % n ))]}
    printf '%s %s ' "$b" "$k"
    docker exec "$CONTAINER" bash /tmp/run.sh "$arm" "$WORKERS" "$ITER" "$ROWS"
  done
done
