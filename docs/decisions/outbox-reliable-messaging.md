# 신뢰성 있는 비동기 통보 — 세션 종료 통보 유실(E1) 보강

작성일: 2026-06-12
상태: **분석/추천 (결정 전)** — 대안 비교·근거 제공. 착수·방식 선택은 사용자 confirm 후 박제 ([[feedback_user_decides_not_claude]], [[feedback_decision_doc]])
대상: 백엔드(Spring) 신입 포폴 — 헤드라인(세션 분산 정합성) **직접** 강화
관련: [`portfolio-narrative.md`](../portfolio/portfolio-narrative.md)(§1 헤드라인·§3 보강), [`failure-modes.md`](../portfolio/failure-modes.md)(E1·E2·C4·T3), [`db-portfolio-roadmap.md`](./db-portfolio-roadmap.md)

---

## 0. 한 줄 목적

> **"DB 상태는 바뀌었는데(세션 COMPLETED), 그 사실을 FastAPI에 알리는 통보가 유실되어 두 서비스가 영영 어긋나는 것"을 막는다.**

유실 0 + 멱등 수신(이미 있음) = **effectively exactly-once**.

---

## 1. 문제 — dual-write (실코드 기준)

`endSession` 한 번의 논리 작업이 **두 곳에 쓴다**:

| | 쓰기 대상 | 수단 | 원자성 |
|---|---|---|---|
| write 1 | MySQL (`exercise_sessions` status/endTime) | `@Transactional` commit | ✅ |
| write 2 | FastAPI (분석 중단 통보) | gRPC `StopAnalysis` | ❌ |

현재 흐름 (`SessionService.java:130-156`):

```
endSession  @Transactional
  session.setEndTime(...); saveAndFlush()      // write 1
  registerSynchronization { afterCommit() {    // 커밋 확정 후 — 순서는 잘 잡음
      analysisService.stopAnalysis(sessionId)  // write 2 (gRPC)
  }}
```

`stopAnalysis` (`ExerciseAnalysisService.java:174-193`):

```
getAuthenticatedStub().stopAnalysis(request, new StreamObserver<>() {
    onError(Throwable t) { log.error("AI 서버 중단 실패: {}", t.getMessage()); }  // :188 — 로그만!
});
```

**갭(E1)**: `afterCommit` 이후 gRPC가 실패하면 복구 수단이 없다(at-most-once). 결과:

```
MySQL: 세션 COMPLETED ✅   ←불일치→   FastAPI: orphan IN_PROGRESS ❌
```

> `afterCommit`을 쓴 건 **정답**이다(커밋 전 송신 시 "통보는 갔는데 DB 롤백"이 더 나쁨). 순서는 맞췄지만, **두 번째 write의 실패를 메꾸지 못하는 것**이 남은 문제다.

**이미 가진 절반**: 수신측 멱등성 — `applyCompleteFromApp:223` `if (status==COMPLETED) return` (first-write-wins). 중복 통보는 안전. **그래서 송신만 보강하면 완결된다.**

---

## 2. 대안 비교 ★ (이 문서의 핵심)

"통보가 유실되지 않게" 하는 방법은 outbox만이 아니다. 6가지를 같은 잣대로 본다.

| # | 방식 | 핵심 아이디어 | dual-write 해소 | 크래시 안전 | 비용 | 평가 |
|---|---|---|---|---|---|---|
| A | **트랜잭셔널 Outbox + 폴링 발행기** | 보낼 통보를 같은 트랜잭션에 행으로 INSERT, 별도 잡이 전달 보장 | ✅ | ✅ | 중 | **⭐ 추천** |
| B | 동기 재시도 (`@Retryable`/afterCommit 내 재시도) | gRPC 실패 시 그 자리서 N회 재시도 | ❌ | ❌ | 저 | 순진. 크래시 시 유실 |
| C | 상태기반 리컨실리에이션(sweep 잡) | "메시지" 안 적고, **불일치 상태를 주기적으로 찾아 고침** | ✅(우회) | ✅ | 중 | 유력 2순위 |
| D | 영속 메시지 브로커(Kafka/SQS) | gRPC 대신 큐에 발행 | ❌(여전히 dual-write) | — | 고 | 단독으론 문제 그대로 |
| E | 2PC / XA 분산 트랜잭션 | DB+gRPC를 한 원자 단위로 묶음 | ✅(이론) | — | 고 | 교과서적 기각 |
| F | 현행 유지 (타임아웃 스케줄러 세이프티넷) | 아무것도 안 함 | ✗ | — | 0 | 정직한 baseline |

### A. 트랜잭셔널 Outbox (추천)
- write 2를 "지금 gRPC"에서 → **"같은 트랜잭션 안 `outbox` 테이블 INSERT"** 로 치환. 둘 다 MySQL이라 원자적 커밋.
- 별도 `@Scheduled` 발행기가 `PENDING` 행을 폴링 → gRPC 송신 → 성공 시 `SENT`, 실패 시 그대로 둬 다음 턴 재시도. **크래시해도 PENDING 행이 DB에 남아 재시작 후 이어 전달**(at-least-once).
- 장점: 헤드라인(분산 정합성)을 정면으로 푸는 **교과서 패턴** → 면접 즉답. 멱등 수신과 합쳐 exactly-once.
- 단점: 테이블 1개 + 발행기 1개 추가. 폴링 지연(턴 간격)만큼 통보가 늦을 수 있음.

### B. 동기 재시도 — 왜 부족한가
- `onError`에서 N회 재시도는 일시 장애엔 듣지만, **재시도 도중 인스턴스가 죽으면 "보낼 일"이 메모리에서 증발**(기록 없음). FastAPI가 수 초 이상 죽어있으면 afterCommit 스레드를 붙잡고 있을 수도 없다.
- 즉 "유실 0" 보장을 못 줌. outbox의 본질은 재시도가 아니라 **보낼 의무의 영속화**.

### C. 상태기반 리컨실리에이션 — 유력한 2순위
- 발상 전환: 보낼 메시지를 적지 말고, **"DB는 COMPLETED인데 FastAPI엔 안 알려진 세션"을 주기적으로 쿼리해서 다시 통보**.
- 이 앱엔 이미 **`SessionTimeoutScheduler`(세이프티넷, `markAsFailedIfStillInProgress:167`)** 가 있어 *같은 계열*이다 → 자산 재활용.
- 단점: "통보됨/안됨"을 알려면 결국 도메인 테이블에 `ai_notified BOOLEAN` 같은 **플래그가 필요** → 사실상 outbox-lite. 또 도메인 쿼리에 정합성 로직이 섞이고, 통보 순서/타입 확장이 어렵다.
- **Outbox와의 본질 차이**: outbox=**로그 기반**(보낼 것을 명시적으로 기록, 일반적·순서보장·여러 통보 타입 확장 쉬움) vs 리컨실리에이션=**상태 기반**(현재 상태 차이를 역산, 테이블 안 늘지만 도메인에 결합).

### D. 메시지 브로커 — 오해 주의
- "Kafka/SQS 쓰면 되잖아"는 **dual-write를 안 풉다**. "DB commit + 브로커 enqueue"가 여전히 두 시스템이라 같은 문제. **브로커는 보통 outbox가 *공급*하는 하류**(outbox→Debezium→Kafka). 캡스톤 도메인엔 과한 인프라.
- **outbox vs Kafka는 층위가 다름(경쟁 아님)**: outbox=*패턴*(DB커밋과 발행을 안 어긋나게 묶는 방법), Kafka=*인프라*(메시지를 대규모로 나르는 통). gRPC를 Kafka로 바꿔도 dual-write는 그대로 → 결국 `DB+INSERT outbox → 발행기 → Kafka`로 outbox가 Kafka **앞단**에 붙는다. 본 프로젝트는 통보 대상이 **FastAPI 단일·팬아웃 소비자 없음** → 브로커 자리를 **gRPC 직접 호출**이 대신(브로커 생략이 right-sizing). 면접 답: "dual-write는 브로커가 아니라 outbox가 푼다."

### E. 2PC/XA — 왜 안 하나
- DB와 gRPC를 한 원자 트랜잭션으로 묶는 정공법이지만, FastAPI/gRPC가 XA 참여자가 아니고, blocking·코디네이터 SPOF·운영 복잡도로 **현업에서 기피**. "왜 2PC 대신 outbox인가"는 좋은 면접 답변 소재(직접 채택은 안 함).

### F. 현행 유지 — 정직 baseline
- 지금도 타임아웃 스케줄러가 orphan을 **FAILED로** 수렴은 시킨다. 하지만 이는 **"올바른 COMPLETED 통보 전달"이 아니라 "포기 처리"** — 분석은 끝났는데 결과가 FAILED로 묻힐 수 있음(C4와 동일). E1을 *진짜로* 푸는 게 아니다.

---

## 3. 추천 — 순수 A(Outbox) + 내장 FAILED 처리. C는 확장 옵션

| | |
|---|---|
| **1차** | **A 트랜잭셔널 Outbox + 폴링 발행기만.** 헤드라인 직결·교과서·exactly-once 완성. **메인 경로 단일화.** |
| **내장** | 발행기가 `retry_count > N`이면 **`status=FAILED` + 로그/알람** 한 줄. 별도 잡 없이 독(poison) 메시지·영구 실패를 처리(사람이 보고 개입). |
| 확장 옵션 | C(별도 리컨실리에이션 잡)는 **"여기서 더 가면"으로 문서에만**(§3-1). 실제 구현 보류. |
| 안 함 | D(브로커)·E(2PC) — 도메인 규모 대비 과설계, 개념 언급만. |

### 3-1. 왜 A+C 결합을 *1차에서* 안 하나 (정직)

직전 검토에서 "A+C 결합"을 추천했으나, 결합엔 분명한 비용이 있어 **1차에선 순수 A로 내린다**:

- **책임 경계 흐려짐**: 발행기(A)와 리컨실리에이션(C)이 같은 세션을 동시에 통보 → 중복 폭증 위험. 막으려면 "C는 FAILED 잔류분만" 같은 경계 규칙을 엄격히 지켜야 함.
- **진실의 출처 이중화**: A는 `outbox_events.status`, C는 도메인 상태(+플래그)를 봄. 둘이 어긋나면 정합성 풀려다 새 정합성 문제 생김.
- **표면적·테스트 2배**: 부품 증가 → 신입 한 카드치고 과설계로 보일 위험(right-sizing 위반).

**C가 실제 값을 하는 구간은 "독 메시지/영구 실패" 하나뿐**인데, 그건 위 *내장 FAILED 처리* 한 줄로 더 싸게 해결된다. 따라서 C는 보험이지 필수가 아니며, 확장 옵션으로 둔다.

| 장애 상황 | A만으로 충분? |
|---|---|
| FastAPI 일시 장애(초~분) | ✅ 발행기 재시도로 해결 |
| FastAPI 장기 장애 | ✅ PENDING 누적 → 복구 시 전송 |
| 독 메시지(영영 실패) | ⚠️ *내장 FAILED+알람*으로 처리. 별도 C 불필요 |

---

## 4. 설계 (A 채택 가정 — 미확정)

### 4-1. 테이블 (스키마 컨벤션 준수: snake_case, TIMESTAMP, BIGINT PK)

```sql
-- N. 아웃박스 (트랜잭셔널 메시징 — 통보 유실 방지)
CREATE TABLE IF NOT EXISTS outbox_events (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,          -- 예: 'SESSION'
    aggregate_id  BIGINT        NOT NULL,          -- session_id
    event_type    VARCHAR(50)   NOT NULL,          -- 예: 'STOP_ANALYSIS'
    payload       JSON          NOT NULL,          -- { "sessionId": 42 }
    status        ENUM('PENDING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
    retry_count   INT           NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP     NULL,              -- 백오프용(선택)
    created_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    sent_at       TIMESTAMP     NULL,
    INDEX idx_outbox_dispatch (status, next_retry_at)  -- 발행기 폴링 쿼리용
);
```

### 4-2. 코드 변경점 (최소 침습)

| 위치 | 현재 | 변경 |
|---|---|---|
| `SessionService.endSession:151` afterCommit | `analysisService.stopAnalysis(id)` 직접 호출 | **트랜잭션 본문에서** `outboxRepository.save(STOP_ANALYSIS, id)` INSERT. afterCommit gRPC 직접호출 제거(또는 best-effort 즉시발송 후 실패해도 outbox가 보장) |
| 신규 `OutboxPublisher` | — | `@Scheduled(fixedDelay=…)` → PENDING 조회 → `stopAnalysis` 송신 → SENT/retry++ |
| `stopAnalysis` | 그대로 재사용 | 발행기가 호출. onError 시 throw하게 바꿔 발행기가 PENDING 유지 판단 |

> 멱등 수신(`applyCompleteFromApp:223`)은 **그대로** — at-least-once 중복을 흡수. 손 안 댐.

### 4-3. 확장성 경로 (반드시 같이 박제 — [[feedback_industry_level_standard]])

| 단계 | 방식 | 깨지는 지점 → 대응 |
|---|---|---|
| 1차 | 단일 인스턴스 폴링 | OK |
| 수평 확장 | 발행기 다중화 | **여러 발행기가 같은 PENDING 동시 집음 → 중복 송신.** `SELECT … FOR UPDATE SKIP LOCKED` 또는 **ShedLock**으로 단일 처리 보장 (= [`failure-modes.md`](../portfolio/failure-modes.md) **T3**와 동일 줄기) |
| 고트래픽 | 폴링 빈조회·지연 | **CDC(Debezium)로 outbox binlog 스트리밍** → 폴링 제거. 단 운영 복잡도↑, 도메인 규모선 보류 |

> 폴링은 *1차 구현*일 뿐. "다중 인스턴스면 SKIP LOCKED/ShedLock 필요, 더 크면 CDC"를 설계에 명시하는 것이 핵심(확장성 의식).

---

## 5. 포폴 서사 (왜 이 카드인가)

- 면접 질문 "두 서비스 걸친 상태인데 통보 유실되면요?" → **"트랜잭셔널 outbox로 at-least-once 송신, 기존 멱등 수신과 합쳐 exactly-once. 2PC는 운영비용·blocking으로 기각, 브로커는 dual-write를 못 풀어 outbox가 그 앞단."** = 분산 시스템 깊이 즉증.
- `failure-modes.md`의 **E1·E2·C4의 공통 뿌리(at-most-once 송신)** 를 한 번에 제거 → 카탈로그와 정합.
- 확장 경로(SKIP LOCKED→CDC)까지 말하면 right-sizing + 확장성 의식 동시 시연.

---

## 6. 측정 (구현 시 — 말 아닌 증거)

- **유실 재현**: FastAPI를 죽인 채 N세션 종료 → 현행은 orphan N건 / outbox는 PENDING N건 적재 확인 → FastAPI 복구 후 **전건 SENT 수렴** 관찰.
- **지연**: 폴링 간격별 통보 p99 latency(턴 간격이 하한).
- **중복 흡수**: 의도적 2회 송신 → 수신측 멱등으로 COMPLETED 1회만 반영 확인.

---

## 7. 미결정 (사용자 confirm 필요)

- [ ] **방식 선택**: 순수 A(추천) vs C(리컨실리에이션) vs A+C 결합(§3-1 단점 인지하고도)
- [ ] afterCommit에서 **즉시 best-effort 송신도 유지**할지(지연↓) vs 순수 outbox만(단순)
- [ ] 발행기 재시도 정책: 고정 간격 vs 지수 백오프(`next_retry_at`), `retry_count > N` 시 FAILED+알람의 N값·알람 채널
- [ ] 다중 인스턴스 가정 범위: 1차부터 SKIP LOCKED 넣을지 vs 단일 전제로 두고 "확장 시"로 문서화만
- [ ] 보강 착수 순서에서 이걸 1번으로 둘지 (vs 관측성 — [`portfolio-narrative.md §7`](../portfolio/portfolio-narrative.md))

---

## 결정 로그
- 2026-06-12: 문서 작성. 대안 6종(A~F) 비교, **A(Outbox) 추천·C 결합 옵션**. 방식·착수 **미결정**(§7).
- 2026-06-12: 추천 수정 — A+C 결합의 단점(책임 경계 흐림·진실 이중화·표면적·테스트) 검토 후 **순수 A + 내장 FAILED 처리**로 하향, C는 확장 옵션으로 강등(§3-1).
