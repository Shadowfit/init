#!/usr/bin/env python3
"""판별 요약 — events.tsv + t*/probe.tsv → 한 판 한 줄 (모든 시각은 장애를 넣은 시각 t_kill 기준, ms)

  first_fail      장애 뒤 처음 실패가 «돌아온» 시각 (실패한 요청의 응답 시각 중 가장 이른 것)
  recovered       이 시각 뒤에 «보낸» 요청은 끝까지 전부 성공했다 — 그 첫 요청이 성공으로 돌아온 시각.
                  사용자가 «다시 저장된다» 를 처음 보는 시각이다
  n_fail          실패 수 (HTTP 204 가 아닌 것 전부)
  fail_kinds      실패 종류별 수
  max_hang        장애 전후로 나간 요청 중 가장 오래 걸린 응답 시간
  n_over_5s       5초 넘게 걸린 요청 수
  unreturned      클라이언트 상한(CLIENT_TIMEOUT_S)까지 안 돌아온 요청 수 (probe.tsv 에 줄이 없다)
  lost_acked      성공(204)을 받았는데 새 원본에 없는 쓰기
"""
import collections
import csv
import os
import sys

out = sys.argv[1]
ev = list(csv.DictReader(open(os.path.join(out, "events.tsv")), delimiter="\t"))
cols = ["trial", "kind", "arm", "kill", "promoted", "switch_at", "first_fail", "recovered", "n_fail",
        "fail_kinds", "max_hang", "n_over_5s", "unreturned", "lost_acked", "rows_on_old", "status"]
print("\t".join(cols))
for e in ev:
    tk = int(e["t_kill_ms"])
    rows = list(csv.DictReader(open(os.path.join(out, "t" + e["trial"], "probe.tsv")), delimiter="\t"))
    sent = set()
    for r in rows:
        sent.add(int(r["seq"]))
    max_seq = max(sent) if sent else -1
    unreturned = (max_seq + 1) - len(sent)
    rows.sort(key=lambda r: int(r["t_send_ms"]))
    fails = [r for r in rows if r["result"] != "204" and int(r["t_done_ms"]) >= tk]
    first_fail = min((int(r["t_done_ms"]) for r in fails), default=None)
    # recovered: 뒤에서부터 훑어 «마지막 실패보다 뒤에 보낸» 첫 성공
    last_bad_send = max((int(r["t_send_ms"]) for r in rows if r["result"] != "204"), default=None)
    rec = None
    if last_bad_send is None:
        rec = 0
    else:
        after = [r for r in rows if int(r["t_send_ms"]) > last_bad_send]
        if after:
            rec = int(after[0]["t_done_ms"]) - tk
    kinds = collections.Counter(r["result"] for r in fails)
    lat = [int(r["t_done_ms"]) - int(r["t_send_ms"]) for r in rows if int(r["t_done_ms"]) >= tk]
    print("\t".join(str(x) for x in [
        e["trial"], e["kind"], e["arm"], e["kill"], e["promoted_after_kill_ms"],
        int(e["t_switch_ms"]) - tk,
        "" if first_fail is None else first_fail - tk,
        "" if rec is None else rec,
        len(fails), ",".join(f"{k}:{v}" for k, v in kinds.most_common()),
        max(lat, default=""), sum(1 for x in lat if x > 5000), unreturned,
        e["lost_acked"], e["rows_on_old"], e["status"]]))
