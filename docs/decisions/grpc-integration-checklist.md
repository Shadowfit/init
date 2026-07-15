# gRPC 좌표 송수신(AI ↔ Backend) 설계 체크리스트

작성: 2026-07-15
대상: 백엔드(Spring) 포폴. 아키텍처 3축(① AI 좌표 송수신·gRPC, ② 보고서 조회·DB, ③ 세션 생명주기) 중 **①번 축**의 설계 점검.
연관: [`../architecture/ai-backend-integration.md`](../architecture/ai-backend-integration.md)(현황 스냅샷), [`./ai-backend-coupling.md`](./ai-backend-coupling.md)(결합 방식 트레이드오프), [`./report-read-path.md`](./report-read-path.md)(②번 축, 같은 형식의 체크리스트)

> ✅=코드로 확인, 🔶=부분 적용/의도적 결정, ⬜=미착수. `report-read-path.md`와 동일한 표기.

---

## 0. 사고 순서 — 왜 이 순서인가

`report-read-path.md`의 8단계(저장구조→over-fetch→인덱스→규모→동시성→precompute→보존정책→N+1)와 같은 원리: **뒤 단계일수록 앞 단계의 답을 전제**하고, **앞쪽일수록 코드만 읽으면 답이 나오는 저비용·확정적 질문, 뒤쪽일수록 가정·판단이 필요한 고비용 질문**이다.

| # | 단계 | 왜 이 자리인가 |
|---|---|---|
| ① | 계약 구조 파악 | proto·RPC 목록·호출 방향부터 알아야 나머지를 논할 수 있음(순수 사실) |
| ② | 호출 단위 설계(배치/윈도우) | "저장 vs 조회 컬럼 대조"와 같은 원리 — 불필요하게 잘게 쪼갠 호출을 피하는 것. 규모 몰라도 판단 가능한 구조적 결정 |
| ③ | 인증/권한 경계 | 누가 호출 가능한지 — 이것도 규모와 무관하게 코드로 확정되는 질문 |
| ④ | 규모 역산 | 여기서부터 **가정**이 시작(호출 빈도·페이로드 크기를 DAU 가정으로 역산) |
| ⑤ | 장애 격리(타임아웃·서킷브레이커) | ④(호출 빈도·규모)를 모르면 임계값 설정 자체가 의미 없음 |
| ⑥ | 전달 보장·멱등성 | ⑤(장애가 어떤 모양으로 나는지)를 알아야 "재시도 시 뭐가 위험한지" 판단 가능 |
| ⑦ | 방향 비대칭 재검토 | 양방향 gRPC 관계면, ③~⑥의 답이 방향마다 다를 수 있음 — 마지막에 재확인 |

**리포트(②) 축과 다른 점**: 리포트는 순수 읽기라 "장애 격리" 개념 자체가 없었음(외부 서비스 호출이 없으므로). gRPC는 외부 서비스 호출이 핵심이라 ⑤⑥(장애·재시도)이 새로 들어오고, 대신 "precompute·보존정책" 같은 데이터 수명주기 개념은 여기 해당 없음(gRPC 자체는 저장 계층이 아님).

---

## 1. 체크리스트 (코드 대조 완료, 2026-07-15)

| # | 요소 | 이 프로젝트 현황 | 상태 |
|---|---|---|---|
| ① | 프로토콜 계약 관리 | `exercise.proto`가 Spring·AI 양쪽에 각각 파일로 존재 — 필드 추가 시 두 서버 동시 배포 필요, 자동 동기화 없음 | 🔶 수동 동기화 |
| ② | 호출 단위(배치/윈도우) | 프레임마다 실시간 호출 금지 — **rep 완성 시점에 그 rep의 프레임 전체를 batch 전송**(`ai-server/app/api/endpoints/pose.py:107-116`) | ✅ 설계됨 |
| ③ | 인증 | Spring→AI: Bearer 토큰 헤더 첨부(`ExerciseAnalysisService.getAuthenticatedStub`). AI→Spring: `InternalAuthInterceptor` | ✅ 양방향 모두 있음 |
| ④ | 규모 역산 | 세션당 rep 수 × rep당 배치 호출 1회 — DAU 가정으로 호출 빈도 역산 가능(별도 실측은 안 함) | 🔶 개념만 |
| ⑤ | 타임아웃/데드라인 | `withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS)` — hang 상태도 `DEADLINE_EXCEEDED`로 귀결시켜 서킷브레이커가 실패로 잡을 수 있게 함 | ✅ 있음(Spring→AI만) |
| ⑤ | 서킷브레이커 | Resilience4j로 `extractReferenceData`·`startAnalysis`·`stopAnalysis` 3개 호출 보호. CLOSED→OPEN→HALF_OPEN 전체 생명주기 Docker(`docker stop`/`docker pause`)로 실측 확인(`production-signal-checklist.md` §2-3-3, §2-3-4) | ✅ 실측 완료 |
| ⑥ | 전달 보장·멱등성 | AI→Spring 콜백 재전송: `INSERT IGNORE` + `uk_session_event`로 멱등 방어(✅). Spring→AI 방향은 fire-and-forget이라 **at-least-once 미보장**(outbox 미착수) | 🔶 한쪽만 방어 |
| ⑦ | 방향 비대칭 | ③~⑥ 전부 **Spring→AI 방향만** 보호됨. AI→Spring(콜백 3개: `savePoseDataBatch`·`completeAnalysis`·`reportFeedbackBatch`)은 반대 방향이라 Spring이 느려지면 AI가 안 보호됨 — AI 코드 안 건드리는 정책([[feedback_minimize_python_changes]])으로 **의도적 스코프 제외**, 회피 아니라 명시적 결정(`production-signal-checklist.md` §2-3-4-2) | 🔶 결정된 갭 |
| — | 메시지 크기 제한 | 명시적 설정 없음(gRPC 기본 4MB) — rep당 5~30프레임 배치라 현재 규모에선 문제 없음 | ⬜ 미설정, 리스크 낮음 |
| — | 스트리밍 vs unary | 6개 RPC 전부 **unary**(`stream` 키워드 없음) — 프레임 실시간 스트림 대신 rep 단위 batch unary로 모은 설계(②)와 일치 | ✅ 의도적 선택 |

---

## 2. 문서 동기화 갭 발견 (2026-07-15)

`docs/tasks/22-backend-tasks-detail.md`·`21-task-assignment.md`의 **BE-10(gRPC 헬스체크+Resilience4j Circuit Breaker)이 아직 "🔴 미착수"로 표시**돼 있으나, 실제로는 `production-signal-checklist.md`에 **2026-07-11 완료**로 기록됨(§2-3-3, §2-3-4). `api-improvement-opportunities.md`와 같은 패턴(작업 문서가 실제 진행상황을 못 따라감) — 갱신 필요, 미반영 상태.

---

## 3. 관련 문서
- [`../architecture/ai-backend-integration.md`](../architecture/ai-backend-integration.md) — 결합 현황 스냅샷
- [`./ai-backend-coupling.md`](./ai-backend-coupling.md) — 결합 방식 트레이드오프(OPEN)
- [`./production-signal-checklist.md`](./production-signal-checklist.md) §2-3 — 서킷브레이커 구현·실측
- [`./report-read-path.md`](./report-read-path.md) — ②번 축(보고서 조회), 동일 형식의 체크리스트
- [`../tasks/22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) BE-10 — 상태 갱신 필요(§2)
