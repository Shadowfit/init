# 부하 테스트 (loadtest/)

전략·근거는 [`docs/decisions/load-test-strategy.md`](../docs/decisions/load-test-strategy.md). 이 디렉토리는 **실행 스크립트**.
용어(baseline/ramp/smoke, percentile, throughput, SLO 등)는 [`docs/decisions/load-test-glossary.md`](../docs/decisions/load-test-glossary.md).

확정 결정(2026-05-31): 목표 DAU 1,000 / 트랙 **② 백엔드 격리(ghz) → ⑤ 시딩→projection** 순차 / 도구 ghz + Locust.

> 핵심: 시스템 병목은 AI 추론(MediaPipe)이라 **MediaPipe 를 빼고** 본인 소유 경로(Spring+MySQL)만
> `SavePoseDataBatch` gRPC 로 격리 측정한다. (strategy §3.2·§5)

---

## ghz/ — ② 백엔드 격리

| 파일 | 용도 |
|------|------|
| `gen_batch.py` | `PoseDataBatchRequest` 1건(= rep 1회 프레임들) JSON 생성기. `--reps` = R 값 |
| `batch.json` | 생성된 데이터 템플릿 (session 801, R=25, ~52KB / 프레임 ~2.1KB). **실측 R 로 재생성 권장** |
| `run-save-pose-batch.ps1` | Windows 실행 (smoke / baseline / ramp) |
| `run-save-pose-batch.sh` | bash 실행 (Git Bash / Linux) |
| `results/` | ghz HTML 리포트 출력 (gitignore) |

### 사전조건

1. **백엔드 gRPC 가 :6565 에 떠 있음** — reflection 켜진 상태 (`application.yml` `grpc.server.reflection-enabled: true`).
   ghz 가 reflection 으로 스키마 자동 인식 → proto 파일·import 경로 지정 불필요.
2. **세션 row 존재** — `batch.json` 의 `sessionId`(기본 801)가 DB 에 있어야 함
   ([`PoseDataService.savePoseDataBatch`](../backend/src/main/java/com/shadowfit/service/Exercise/PoseDataService.java) 가 `findById` 로 세션 먼저 조회 → 없으면 `SESSION_NOT_FOUND`).
   더미 801 은 [`mysql/data.sql`](../mysql/data.sql) 에 seed 됨.
3. **`INTERNAL_API_TOKEN`** — 서버와 동일 값. 인증은 메타데이터 `authorization: Bearer <token>`
   ([`InternalAuthInterceptor`](../backend/src/main/java/com/shadowfit/global/config/InternalAuthInterceptor.java)).
4. **ghz 설치** — 아래.

### ghz 설치

```powershell
scoop install ghz            # Windows (scoop)
# 또는
go install github.com/bojand/ghz/cmd/ghz@latest   # go 있으면
```
릴리스 바이너리: https://github.com/bojand/ghz/releases

### 실행

```powershell
$env:INTERNAL_API_TOKEN = "<server-token>"
cd loadtest\ghz
.\run-save-pose-batch.ps1 -Mode smoke      # 1) 경로·인증 OK 확인 (5 call)
.\run-save-pose-batch.ps1 -Mode baseline   # 2) 단일 세션 순차 — batch 1건 p50/95/99
.\run-save-pose-batch.ps1 -Mode ramp       # 3) 동시성 5->100 step — throughput 천장 + p99
```

---

## 실행 순서 (strategy §10)

### 0단계 — R 값 실측 (가장 먼저) ⭐

모든 시딩량이 R(= rep 당 프레임 수)에서 나오는데 현재는 추정값(R≈20~30, strategy §4.5).
**실제 스쿼트 세션 1회** 돌린 뒤 Spring 로그에서 확정:

```
세션 {} : 포즈 데이터 {}개 일괄 저장 성공   ← 이 "{}개" 가 batch 당 행 수 ≈ R
```

확정한 R 로 `batch.json` 재생성:
```bash
python gen_batch.py --session 801 --reps <측정 R> --out batch.json
```
(host 에 Python 없으면 README 하단 PowerShell 생성 블록 사용. ai-server 는 손대지 않음.)

→ 측정한 R 을 strategy §7 / §11 에 박제.

### 1~3단계 — ② 백엔드 격리

`-Mode smoke` → `baseline` → `ramp` 순. ramp 에서 **throughput 가 평탄해지는 동시성 = 백엔드 천장**,
그 지점의 **콜백 p99** 를 SLO(strategy §10-1, "콜백 p99 < 20ms")와 비교.

캡처할 숫자 → strategy §11 SLO 행 / §7 에 박제:
- baseline: batch 1건 저장 지연 p50/95/99
- ramp: 천장 throughput(req/s), 그 지점 p99, 에러율 0 여부

### 다음 — ⑤ 시딩 → projection (별도 작업)

ramp 가 이미 session 801 에 pose_data 를 대량 적재함(side effect). 이걸 GET /reports projection
전/후 비교(payload ~3MB→0.05MB, strategy §4.6-1)의 시드로 재활용 가능.

---

## 주의

- **ramp 는 session 801 에 실제 row 를 누적 INSERT** 한다. 측정 후 정리:
  ```sql
  DELETE FROM pose_data WHERE session_id = 801;   -- 더미 세션 자체는 보존
  ```
- E2E(③, Locust)는 별도. MediaPipe 가 진짜 이미지여야 부하가 흐름(strategy §6) — 실제 프레임 리플레이 필요.

---

## batch.json 재생성 (Python 없을 때 — PowerShell)

```powershell
# loadtest/ghz 에서 실행. $reps 를 측정 R 로.
$reps = 25; $session = 801
$fb = @("","","KNEE_OUT","BACK_BENT","HIP_HIGH","KNEE_IN","","KNEE_OUT")
$frames = New-Object System.Collections.Generic.List[string]
for ($f=0; $f -lt $reps; $f++) {
  $lm = New-Object System.Collections.Generic.List[string]
  for ($i=0; $i -lt 33; $i++) {
    $b = (($f*31 + $i*7) % 1000)/1000.0
    $x=[math]::Round(0.30+$b*0.40,6); $y=[math]::Round(0.20+(($b*17)%1.0)*0.60,6)
    $z=[math]::Round(-0.25+(($b*13)%1.0)*0.50,6); $v=[math]::Round(0.85+(($b*11)%1.0)*0.15,6)
    $lm.Add('{"x":'+$x+',"y":'+$y+',"z":'+$z+',"visibility":'+$v+'}')
  }
  $c = ('['+($lm -join ',')+']') -replace '"','\"'
  $ts=[math]::Round($f*0.1,1); $sr=[math]::Round(45.0+($f*7%50),2); $m=$fb[$f%8]
  $frames.Add('{"timestampSec":'+$ts+',"jointCoordinates":"'+$c+'","syncRate":'+$sr+',"feedbackMessage":"'+$m+'"}')
}
$batch = '{"sessionId":'+$session+',"poseData":['+($frames -join ',')+']}'
[System.IO.File]::WriteAllText("$PWD\batch.json", $batch, (New-Object System.Text.UTF8Encoding($false)))
```
