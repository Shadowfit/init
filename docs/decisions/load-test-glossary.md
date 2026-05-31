# Reference: 부하 테스트 용어집

상태: REFERENCE — 부하 테스트(loadtest/)에서 쓰는 용어 정의. 결정 아님, 개념 정리.
작성: 2026-05-31
배경: ② 백엔드 격리(ghz) 측정 진행 중 등장한 용어들을 한곳에 정리. 전략·결정은 [`./load-test-strategy.md`](./load-test-strategy.md), 실행은 [`../../loadtest/README.md`](../../loadtest/README.md).

---

## 1. 테스트 모드 (ghz `-Mode`)

부하를 점점 키우는 3단계. `loadtest/ghz/run-save-pose-batch.ps1` 에 구현.

| 모드 | 동시성 | 양 | 목적 | 한 줄 |
|------|--------|----|------|-------|
| **smoke** | 1 | 5 call | 경로·인증 살아있나 | "연결 되나?" 연막탄 한 발 |
| **baseline** | 1 | 200 call | 방해 없는 순수 지연 | "혼자면 얼마나 빠른가?" 기준선(0점) |
| **ramp** | 5→100 step | 210초 | 한계·천장 탐색 | "어디서 무너지나?" 계단 상승 |

### baseline (베이스라인)
동시성 1로 요청을 **하나씩 순차** 호출 → 서버가 안 붐비는 이상적 상태의 처리 시간. 나중에 ramp 숫자가 좋은지 나쁜지 판단할 **비교의 0점**. 이게 없으면 "p99 500ms" 가 부하 탓인지 원래 느린 건지 알 수 없음.

### ramp (램프)
동시 요청 수를 계단처럼 **점점 올리며**(이 프로젝트: 5→10→…→100, 5씩, 각 10초) 부하를 키운다. 두 가지를 본다:
1. **throughput 천장** — 동시성을 올려도 처리량이 더 안 늘고 평탄해지는 지점 = 백엔드 한계
2. 그 지점의 **p99 지연 / 에러율** — 한계 근처에서 얼마나 느려지고 깨지나

> 비유: baseline = "차 한 대 0→100 몇 초?", ramp = "도로에 차를 5대→100대 밀어넣으며 언제 막히나".

⚠️ ramp 은 session 801 에 **실제 row 를 누적 INSERT**. 측정 후 `DELETE FROM pose_data WHERE session_id = 801;` 로 정리(더미 세션 row 자체는 보존).

---

## 2. 측정 지표

### concurrency (동시성, `-c`)
동시에 날아가는 요청 수. 1이면 줄 서서 하나씩, 100이면 100개가 한꺼번에. ramp 은 이 값을 키우는 것.

### throughput / RPS (처리량, requests per second)
**초당 처리한 요청 수.** 부하를 키워도 더 안 오르고 평탄해지는 값 = 백엔드가 버틸 수 있는 천장. baseline RPS(이번 측정 12.9)는 동시성 1이라 낮음 — 천장이 아니라 단발 속도일 뿐.

### latency percentile — p50 / p90 / p95 / p99 (백분위 지연)
요청들의 응답 시간을 **느린 순으로 줄 세웠을 때 그 위치의 값**. 평균(average)은 소수의 느린 요청에 가려지므로 분포로 본다.

| 지표 | 뜻 | 읽는 법 |
|------|----|---------|
| **p50** (중앙값) | 절반이 이 시간 안에 끝남 | "보통의 사용자 경험" |
| **p95** | 95%가 이 안에 끝남 | "거의 다 이 정도" |
| **p99** | 99%가 이 안에 끝남 | "최악의 1% — 꼬리 지연" |

> 예: p50 60ms / p99 446ms = 보통은 빠른데 100명 중 1명은 446ms 까지 기다림(콜드스타트·GC·락 경합 등). **p99 가 시스템 안정성의 진짜 척도** — 평균이 멀쩡해도 p99 가 튀면 누군가는 매번 느림.

### SLO (Service Level Objective, 서비스 수준 목표)
"이 정도는 지키겠다"는 **성능 합격 기준**. 이 프로젝트 후보: "콜백 p99 < 20ms, 리포트 조회 p95 < X ms, 적재 누락 0"([strategy §10·§11](./load-test-strategy.md)). 측정값을 SLO 와 비교해 합격/불합격 판정.

---

## 3. 도구·인프라 용어

### ghz
gRPC 전용 부하 테스트 CLI. `SavePoseDataBatch` 같은 gRPC 메서드를 풀스로틀로 호출. **reflection** 켜져 있으면 proto 파일 없이 스키마 자동 인식.

### reflection (gRPC 리플렉션)
서버가 자기 스키마(메서드·메시지 구조)를 런타임에 알려주는 기능(`grpc.server.reflection-enabled: true`). 켜져 있으면 클라이언트(ghz)가 `.proto` 파일·import 경로 지정 없이 호출 가능.

### Locust
HTTP/E2E 부하 테스트 도구(Python). ③ E2E 트랙(실제 프레임 리플레이)용 — ② ghz 와 별개.

### R 값 (이 프로젝트 고유)
rep(반복 동작) 1회당 전송되는 **포즈 프레임 수**. PoseData 1행 = 분석 프레임 1개. 모든 시딩량 산정의 기준인데 아직 추정치(R≈20~30) — 실제 스쿼트 세션 1회 로그로 확정 필요([strategy §7](./load-test-strategy.md)).

---

## 변경 이력
- **2026-05-31 (신설)**: ② 백엔드 격리 측정 중 등장한 용어 정리 — 모드(smoke/baseline/ramp), 지표(concurrency/throughput/RPS/percentile/SLO), 도구(ghz/reflection/Locust/R). [`./load-test-strategy.md`](./load-test-strategy.md) 및 [`../../loadtest/README.md`](../../loadtest/README.md) 보조.
