"""팔 Y — 커넥션을 열어 둔 클라이언트로 하트비트 지연을 잰다.

소스 커넥션 하나가 0.1초마다 클라이언트 시각(µs)을 쓰고, 리플리카 커넥션 하나가 0.1초마다
그 값을 읽어 «지금 − 읽은 시각» 을 지연으로 기록한다. 시각은 쓰는 쪽·읽는 쪽 모두 이
컨테이너의 시계라 시계 차이가 끼지 않는다.

출력(TSV, stdout): now_us  beat_us  lag_s  read_rtt_ms
"""
import sys
import threading
import time

import pymysql

DUR = float(sys.argv[1]) if len(sys.argv) > 1 else 30.0
WARM = float(sys.argv[2]) if len(sys.argv) > 2 else 7.0
STEP = 0.1


def conn(host):
    return pymysql.connect(host=host, user="root", password="repl", autocommit=True)


src = conn("mpath_source")
rep = conn("mpath_replica")
stop = threading.Event()


def writer():
    c = src.cursor()
    while not stop.is_set():
        c.execute("UPDATE mpathdb.hb_y SET beat_us=%s WHERE id=1", (time.time_ns() // 1000,))
        time.sleep(STEP)


t = threading.Thread(target=writer, daemon=True)
t.start()
time.sleep(WARM)  # SOURCE_DELAY=5 뒤 첫 하트비트가 도착할 때까지

c = rep.cursor()
end = time.time() + DUR
print("now_us\tbeat_us\tlag_s\tread_rtt_ms")
while time.time() < end:
    t0 = time.perf_counter()
    c.execute("SELECT beat_us FROM mpathdb.hb_y WHERE id=1")
    beat = c.fetchone()[0]
    rtt = (time.perf_counter() - t0) * 1000
    now = time.time_ns() // 1000
    if beat:
        print(f"{now}\t{beat}\t{(now - beat) / 1e6:.4f}\t{rtt:.2f}")
    time.sleep(STEP)

stop.set()
t.join(timeout=2)
