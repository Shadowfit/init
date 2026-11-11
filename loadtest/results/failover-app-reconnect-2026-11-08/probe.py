#!/usr/bin/env python3
"""페일오버 탐침 — 쓰기 API 를 일정 간격으로 계속 부르고 요청마다 한 줄을 남긴다.

설계: docs/decisions/failover-app-reconnect.md §4 · §6-1 R-c

쓰는 API 는 «운동 메모 저장»(POST /reports/daily-logs — ExerciseRecordController:95, 경로는 PROBE_PATH 로 바꿀 수 있다)이다.
요청마다 날짜를 다르게 넣어서(계정별로 하루씩 증가) 한 요청 = DB 한 줄이 되게 한다 — 그래야
«성공 응답을 받았는데 새 DB 에 없는 쓰기» 를 한 건씩 셀 수 있다(memo 에 요청 번호를 넣는다).

🔴 요청 간격(초당 10번)은 부하가 아니라 **시간 해상도 0.1초**에서 나온 값이다(§6-1 R-c).
🔴 열린 고리(open-loop)다 — 앞 요청이 매달려도 다음 요청은 제시각에 나간다. 그래야 «매달림» 이
   «안 보낸 요청» 으로 숨지 않는다. 요청마다 스레드 하나.

사용:
  probe.py setup BASE N PREFIX TOKENS_OUT          계정 N 개 가입·로그인 → 토큰 파일
  probe.py run   BASE TOKENS RATE OUT_TSV CTRL_DIR TAG CLIENT_TIMEOUT_S
      CTRL_DIR/STOP 이 생기면 새 요청을 멈추고, 이미 나간 요청은 CLIENT_TIMEOUT_S 까지 기다린 뒤 끝낸다.
"""
import datetime
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request

PASSWORD = os.environ.get("PROBE_PASSWORD", "K6load!2026")
LOG_PATH = os.environ.get("PROBE_PATH", "/reports/daily-logs")
BASE_DATE = datetime.date(2000, 1, 1)


def ms():
    return int(time.time() * 1000)


def post(url, body, token=None, timeout=10):
    data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read()


def setup(base, n, prefix, out):
    tokens = []
    for i in range(n):
        email = f"{prefix}{i}@loadtest.local"
        try:
            post(base + "/member/signup", {"username": f"{prefix}{i}", "email": email,
                                           "password": PASSWORD, "sex": "MALE", "role": "USER"})
        except urllib.error.HTTPError as e:
            if e.code != 409:
                raise SystemExit(f"signup 실패 {email}: {e.code} {e.read()[:200]}")
        _, body = post(base + "/member/login", {"email": email, "password": PASSWORD})
        tok = json.loads(body).get("accessToken")
        if not tok:
            raise SystemExit(f"login 응답에 accessToken 없음: {body[:200]}")
        tokens.append(tok)
    with open(out, "w") as f:
        f.write("\n".join(tokens) + "\n")
    print(f"계정 {n}개 준비 → {out}")


def run(base, tokens_file, rate, out_tsv, ctrl, tag, client_timeout):
    tokens = [t.strip() for t in open(tokens_file) if t.strip()]
    lock = threading.Lock()
    out = open(out_tsv, "w", buffering=1)
    out.write("seq\tacct\tlog_date\tt_send_ms\tt_done_ms\tresult\n")
    threads = []

    def one(seq, acct, day):
        log_date = (BASE_DATE + datetime.timedelta(days=day)).isoformat()
        t0 = ms()
        try:
            st, _ = post(base + LOG_PATH, {"logDate": log_date, "memo": f"{tag}-{seq}", "mood": "GOOD"},
                         tokens[acct], timeout=client_timeout)
            res = str(st)
        except urllib.error.HTTPError as e:
            res = str(e.code)
        except Exception as e:  # 연결 거부·타임아웃·리셋 — 종류를 남긴다
            reason = getattr(e, "reason", None)
            res = "EXC:" + type(reason if reason is not None else e).__name__
        t1 = ms()
        with lock:
            out.write(f"{seq}\t{acct}\t{log_date}\t{t0}\t{t1}\t{res}\n")

    gap = 1.0 / rate
    start = time.time()
    seq = 0
    stop_path = os.path.join(ctrl, "STOP")
    while not os.path.exists(stop_path):
        target = start + seq * gap
        d = target - time.time()
        if d > 0:
            time.sleep(d)
        acct = seq % len(tokens)
        th = threading.Thread(target=one, args=(seq, acct, seq // len(tokens)), daemon=True)
        th.start()
        threads.append(th)
        seq += 1
    deadline = time.time() + client_timeout + 5
    for th in threads:
        th.join(max(0.0, deadline - time.time()))
    alive = sum(th.is_alive() for th in threads)
    out.close()
    print(f"보낸 요청 {seq} · 끝까지 안 돌아온 요청 {alive}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "setup":
        setup(sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5])
    elif cmd == "run":
        run(sys.argv[2], sys.argv[3], float(sys.argv[4]), sys.argv[5], sys.argv[6], sys.argv[7], float(sys.argv[8]))
    else:
        raise SystemExit("setup | run")
