"""#276 ㄷ(자연키 PK) 비용 측정 — 클라이언트 쪽 일꾼.

설계: docs/decisions/r276-lock-root-cause-fix.md §5
셸(phase*.sh)이 무대·판 순서를 정하고, 이 스크립트는 한 가지 일만 한다.

  create <table> <arm>                       팔 스키마로 표를 새로 만든다 (A · B · Bp)
  seed   <table> <sessions> <rows/session>   과거 세션으로 무대를 채운다 (세션 번호 순 — 실제 적재 순서와 같다)
  insert <table> <active> <rows> <workers>   운동 세션 교체 모형으로 삽입하고 한 줄 JSON 을 낸다
  reads  <table> <n> <mode>                  세션 단위 읽기 / id 단건 읽기 지연을 한 줄 JSON 으로
  writer <table> <seconds> <gap_ms> <out>    DDL 중 쓰기 정지를 재는 writer (②)
  stats  <table>                             data_length · index_length · 행 수

삽입 모형 (insert) — 실사용에서 «삽입이 흩어지는 정도» 는 **동시에 운동 중인 세션 수** 다.
  · 활성 세션 <active> 개가 있고, 각자 20행 묶음을 보낸다(rpo-rto-targets.md §8-1 가정)
  · 한 세션은 600행(15분 × 0.67행/초)을 채우면 끝나고, 다음 번호의 새 세션이 그 자리를 잇는다
    — exercise_sessions.id 가 AUTO_INCREMENT 라 새 세션 번호는 늘 커진다
  · created_at 은 세션 시작 시각 하나다(V9 — 한 세션의 행은 created_at 이 같다)
  · 문장은 앱과 같다: INSERT … ON DUPLICATE KEY UPDATE session_id = session_id (PoseDataService:59)
  · 일꾼 <workers> 개가 동시에 커넥션을 하나씩 쥐고 묶음을 하나씩 커밋한다 (READ COMMITTED — 지금 채택안)
"""
import json
import os
import random
import sys
import threading
import time
from datetime import datetime, timedelta

import pymysql

HOST = os.environ.get("NATPK_HOST", "natpk_mysql")
PW = os.environ.get("NATPK_PW", "natpk")
DB = os.environ.get("NATPK_DB", "natpk")
BATCH = int(os.environ.get("NATPK_BATCH", "20"))
ROWS_PER_SESSION = int(os.environ.get("NATPK_ROWS_PER_SESSION", "600"))
BASE = datetime(2026, 11, 1, 0, 0, 0)   # 세션 시작 시각의 원점 — 모든 행이 한 파티션(p2026_11)에 든다
_r = random.Random(1)
JOINT = json.dumps({"j": [[round(_r.random(), 3) for _ in range(4)] for _ in range(33)]}, separators=(",", ":"))
# 관절 33개 × 4값 = 847B — 실제 평균 916B(FINAL-REPORT.md:251, 경량화 뒤)와 비슷해 행 폭이 실제에 가깝다

COLS = ("session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate, smoothed_knee_angle, "
        "feedback_message, created_at, lead_leg, torso_tilt_deg, stagger_ratio")
INSERT = (f"INSERT INTO {{t}} ({COLS}) VALUES " + "{v} ON DUPLICATE KEY UPDATE session_id = session_id")

PARTITIONS = """PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (
  PARTITION p2026_09 VALUES LESS THAN (UNIX_TIMESTAMP('2026-10-01 00:00:00')),
  PARTITION p2026_10 VALUES LESS THAN (UNIX_TIMESTAMP('2026-11-01 00:00:00')),
  PARTITION p2026_11 VALUES LESS THAN (UNIX_TIMESTAMP('2026-12-01 00:00:00')),
  PARTITION p2026_12 VALUES LESS THAN (UNIX_TIMESTAMP('2027-01-01 00:00:00')),
  PARTITION pfuture  VALUES LESS THAN MAXVALUE)"""

BODY = """
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  rep_number INT NOT NULL DEFAULT 0,
  timestamp_sec DECIMAL(10,3) NOT NULL,
  joint_coordinates JSON NOT NULL,
  sync_rate DECIMAL(5,2) NULL,
  smoothed_knee_angle DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  feedback_message VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lead_leg VARCHAR(5) NULL DEFAULT NULL,
  torso_tilt_deg DOUBLE NULL,
  stagger_ratio DOUBLE NULL,"""

# 팔 — 지금 스키마(V1 + V6 + V28 + V30~32)와 ㄷ 두 변형
KEYS = {
    "A": """
  PRIMARY KEY (id, created_at),
  UNIQUE KEY uk_pose_event (session_id, rep_number, timestamp_sec, created_at),
  KEY idx_session_timestamp (session_id, timestamp_sec)""",
    "B": """
  PRIMARY KEY (session_id, rep_number, timestamp_sec, created_at),
  KEY idx_id (id),
  KEY idx_session_timestamp (session_id, timestamp_sec)""",
    "Bp": """
  PRIMARY KEY (session_id, rep_number, timestamp_sec, created_at),
  KEY idx_id (id)""",
}


def conn(autocommit=True):
    c = pymysql.connect(host=HOST, user="root", password=PW, database=DB, autocommit=autocommit)
    with c.cursor() as cur:
        cur.execute("SET SESSION transaction_isolation = 'READ-COMMITTED'")
    return c


def one(sql):
    c = conn()
    with c.cursor() as cur:
        cur.execute(sql)
        r = cur.fetchone()
    c.close()
    return r


def ddl(table, arm):
    return f"CREATE TABLE {table} ({BODY}{KEYS[arm]}\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 {PARTITIONS}"


def row(sid, k):
    """세션 sid 의 k 번째 프레임(0-base). rep 은 20프레임마다 하나, 시각은 1.5초 간격(0.67행/초)."""
    start = BASE + timedelta(minutes=sid)
    return (f"({sid},{k // 20 + 1},{k * 1.5:.3f},'{JOINT}',45.00,90.00,'',"
            f"'{start:%Y-%m-%d %H:%M:%S}','L',3.5,0.4)")


# ── create ────────────────────────────────────────────────────────────────
def cmd_create(table, arm):
    c = conn()
    with c.cursor() as cur:
        cur.execute(f"DROP TABLE IF EXISTS {table}")
        cur.execute(ddl(table, arm))
    c.close()
    print(json.dumps({"table": table, "arm": arm}))


# ── seed ──────────────────────────────────────────────────────────────────
def cmd_seed(table, sessions, per):
    """세션 1..sessions 를 번호 순으로 채운다. 한 문장 = 한 세션(최대 600행)이라 빠르다."""
    c = conn()
    t0 = time.time()
    with c.cursor() as cur:
        cur.execute("SET SESSION unique_checks = 0")
        for sid in range(1, sessions + 1):
            vals = ",".join(row(sid, k) for k in range(per))
            cur.execute(f"INSERT INTO {table} ({COLS}) VALUES {vals}")
    c.close()
    print(json.dumps({"table": table, "sessions": sessions, "rows": sessions * per, "sec": round(time.time() - t0, 1)}))


# ── insert ────────────────────────────────────────────────────────────────
def pct(xs, p):
    xs = sorted(xs)
    if not xs:
        return None
    k = (len(xs) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def cmd_insert(table, active, rows, workers):
    first = (one(f"SELECT IFNULL(MAX(session_id), 0) FROM {table}")[0] or 0) + 1
    lock = threading.Lock()
    state = {"next_sid": first + active, "done": 0}
    slots = [[first + i, 0] for i in range(active)]     # [session_id, 보낸 프레임 수]
    free = list(range(active))                          # 지금 아무 일꾼도 안 쥔 슬롯
    lat = []
    errors = []
    rnd = random.Random(276)

    def take():
        with lock:
            if state["done"] >= rows or not free:
                return None
            i = free.pop(rnd.randrange(len(free)))
            sid, k = slots[i]
            n = min(BATCH, ROWS_PER_SESSION - k, rows - state["done"])
            state["done"] += n
            return i, sid, k, n

    def give(i, sid, k, n):
        with lock:
            if k + n >= ROWS_PER_SESSION:               # 세션 끝 → 다음 번호의 새 세션이 이 자리를 잇는다
                slots[i] = [state["next_sid"], 0]
                state["next_sid"] += 1
            else:
                slots[i] = [sid, k + n]
            free.append(i)

    def work():
        c = conn(autocommit=False)
        cur = c.cursor()
        my = []
        while True:
            job = take()
            if job is None:
                break
            i, sid, k, n = job
            sql = INSERT.format(t=table, v=",".join(row(sid, k + j) for j in range(n)))
            t = time.perf_counter()
            try:
                cur.execute(sql)
                c.commit()
            except Exception as e:  # 데드락이 나면 세고 넘어간다 — RC 라 0 이어야 한다(§1)
                c.rollback()
                errors.append(type(e).__name__ + ":" + str(e.args[:1]))
            my.append(time.perf_counter() - t)
            give(i, sid, k, n)
        with lock:
            lat.extend(my)
        c.close()

    ths = [threading.Thread(target=work) for _ in range(workers)]
    t0 = time.perf_counter()
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    sec = time.perf_counter() - t0
    print(json.dumps({
        "table": table, "active": active, "rows": state["done"], "workers": workers,
        "sec": round(sec, 3), "rows_per_sec": round(state["done"] / sec, 1),
        "batch_p50_ms": round(pct(lat, .5) * 1000, 2), "batch_p99_ms": round(pct(lat, .99) * 1000, 2),
        "batches": len(lat), "errors": len(errors), "error_sample": errors[:3],
        "first_sid": first, "last_sid": state["next_sid"] - 1,
    }))


# ── reads (③) ─────────────────────────────────────────────────────────────
# 앱의 두 조회와 같은 문장이다 (PoseDataRepository.java:48-53, :65-68)
SESSION_SQL = ("SELECT id, timestamp_sec, sync_rate, rep_number, smoothed_knee_angle, lead_leg, torso_tilt_deg, "
               "stagger_ratio FROM {t} WHERE session_id = {sid} AND created_at = '{ca}' "
               "ORDER BY rep_number, timestamp_sec")
ID_SQL = "SELECT joint_coordinates FROM {t} WHERE id = {id} AND created_at = '{ca}'"


def cmd_sample(table, n, out):
    """읽을 대상을 미리 뽑아 파일로 — 뽑는 질의가 버퍼풀을 데우므로 «차가운» 판 전에 따로 한다.
       id 는 표를 복사해도(INSERT … SELECT *) 그대로라 팔 셋에 같은 표본을 쓴다."""
    lo, hi = one(f"SELECT MIN(session_id), MAX(session_id) FROM {table}")
    rnd = random.Random(2760)
    c = conn()
    cur = c.cursor()
    with open(out, "w") as f:
        for _ in range(n):
            sid = rnd.randint(lo, hi)
            cur.execute(f"SELECT id, created_at FROM {table} WHERE session_id = {sid} LIMIT 1 OFFSET {rnd.randrange(ROWS_PER_SESSION)}")
            r = cur.fetchone()
            if r:
                f.write(f"{sid}\t{r[0]}\t{r[1]:%Y-%m-%d %H:%M:%S}\n")
    c.close()


def cmd_reads(table, sample, mode):
    """mode=session: 세션 하나의 프레임 전부 (findFramesBySessionId)
       mode=id:      id + created_at 으로 좌표 한 건 (findJointCoordinatesById)"""
    picks = [l.rstrip("\n").split("\t") for l in open(sample)]
    c = conn()
    cur = c.cursor()
    lat, got = [], 0
    sql = ""
    for sid, pid, ca in picks:
        sql = (SESSION_SQL.format(t=table, sid=sid, ca=ca) if mode == "session"
               else ID_SQL.format(t=table, id=pid, ca=ca))
        t = time.perf_counter()
        cur.execute(sql)
        got += len(cur.fetchall())
        lat.append(time.perf_counter() - t)
    n = len(picks)
    cur.execute(f"EXPLAIN FORMAT=TRADITIONAL {sql}")
    plan = [dict(zip([d[0] for d in cur.description], r)) for r in cur.fetchall()]
    c.close()
    print(json.dumps({
        "table": table, "mode": mode, "n": n, "rows_returned": got,
        "p50_ms": round(pct(lat, .5) * 1000, 3), "p95_ms": round(pct(lat, .95) * 1000, 3),
        "max_ms": round(max(lat) * 1000, 3), "total_sec": round(sum(lat), 3),
        "plan_key": [p.get("key") for p in plan], "plan_extra": [p.get("Extra") for p in plan],
    }, default=str))


# ── writer (②) ────────────────────────────────────────────────────────────
def cmd_writer(table, seconds, gap_ms, out):
    """DDL 이 도는 동안 gap_ms 마다 새 세션의 20행 묶음 하나를 넣는다. 시도마다 시작·끝·성공을 기록한다.
       최대 쓰기 정지 = 한 시도가 걸린 최대 시간. stop 파일이 생기면 끝낸다."""
    sid = (one(f"SELECT IFNULL(MAX(session_id), 0) FROM {table}")[0] or 0) + 1_000_000
    c = conn(autocommit=False)
    cur = c.cursor()
    end = time.time() + seconds
    k = 0
    with open(out, "w") as f:
        f.write("t0_ms\tt1_ms\tok\terr\n")
        while time.time() < end and not os.path.exists(out + ".stop"):
            sql = INSERT.format(t=table, v=",".join(row(sid, k + j) for j in range(BATCH)))
            t0 = time.time()
            ok, err = 1, ""
            try:
                cur.execute(sql)
                c.commit()
            except Exception as e:
                ok, err = 0, type(e).__name__
                try:
                    c.rollback()
                except Exception:
                    c = conn(autocommit=False)
                    cur = c.cursor()
            t1 = time.time()
            f.write(f"{int(t0 * 1000)}\t{int(t1 * 1000)}\t{ok}\t{err}\n")
            f.flush()
            k += BATCH
            if k >= ROWS_PER_SESSION:
                sid, k = sid + 1, 0
            time.sleep(gap_ms / 1000)


# ── stats ─────────────────────────────────────────────────────────────────
def cmd_stats(table):
    c = conn()
    with c.cursor() as cur:
        cur.execute(f"ANALYZE TABLE {table}")
        cur.fetchall()
        cur.execute("SELECT data_length, index_length FROM information_schema.tables "
                    "WHERE table_schema = DATABASE() AND table_name = %s", (table,))
        dl, il = cur.fetchone()
        cur.execute(f"SELECT COUNT(*) FROM {table}")
        n = cur.fetchone()[0]
    c.close()
    print(json.dumps({"table": table, "rows": n, "data_length": dl, "index_length": il,
                      "bytes_per_row": round((dl + il) / n, 1) if n else None}))


def cmd_wsum(path):
    """writer 로그 요약 — 시도 수 · 실패 수 · 한 시도의 최대 시간 · 성공 커밋 사이 최대 간격(= 쓰기 정지)."""
    rows = [l.split("\t") for l in open(path).read().splitlines()[1:] if l.strip()]
    rows = [(int(a), int(b), int(c), d) for a, b, c, d in rows]
    oks = [r[1] for r in rows if r[2] == 1]
    gaps = [b - a for a, b in zip(oks, oks[1:])]
    el = [r[1] - r[0] for r in rows]
    print(json.dumps({"attempts": len(rows), "ok": len(oks), "errors": len(rows) - len(oks),
                      "err_kinds": sorted({r[3] for r in rows if r[3]}),
                      "max_attempt_ms": max(el) if el else None, "p50_attempt_ms": pct(el, .5) if el else None,
                      "max_gap_ms": max(gaps) if gaps else None}))


def cmd_fmt(j, s):
    """phase1 원시 표 한 줄 — insert JSON 과 stats JSON 에서 필요한 칸만 TSV 로."""
    j, s = json.loads(j), json.loads(s)
    print("\t".join(str(x) for x in [j["rows"], j["sec"], j["rows_per_sec"], j["batch_p50_ms"], j["batch_p99_ms"],
                                     j["errors"], s["data_length"], s["index_length"], s["bytes_per_row"]]))


if __name__ == "__main__":
    a = sys.argv[1:]
    {"fmt": lambda: cmd_fmt(a[1], a[2]),
     "wsum": lambda: cmd_wsum(a[1]),
     "create": lambda: cmd_create(a[1], a[2]),
     "seed": lambda: cmd_seed(a[1], int(a[2]), int(a[3])),
     "insert": lambda: cmd_insert(a[1], int(a[2]), int(a[3]), int(a[4])),
     "sample": lambda: cmd_sample(a[1], int(a[2]), a[3]),
     "reads": lambda: cmd_reads(a[1], a[2], a[3]),
     "writer": lambda: cmd_writer(a[1], float(a[2]), int(a[3]), a[4]),
     "stats": lambda: cmd_stats(a[1])}[a[0]]()
