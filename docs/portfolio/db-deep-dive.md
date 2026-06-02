# 포폴: DB 깊이 — 시계열·정합성·격리수준

작성: 2026-06-02
대상: 백엔드(Spring) 신입 포폴. 이 프로젝트(ShadowFit)의 DB 영역에서 **교체 불가능한 깊이**를 어디서 어떻게 뽑는지 정리.
연관: [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md), [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md), [`./problem-solving-log.md`](./problem-solving-log.md)

> 살아있는 문서. ✅=코드/측정으로 확인된 사실, 🔶=후보(개발하면 스토리화), ⬜=계획. **결정 마크는 사용자 confirm 후에만.**

---

## 0. 포지셔닝 (클로드 시대 기준)

### 0.1 두 번 평준화된다 — 포화 + AI

1. **표면적 DB 최적화는 이미 포화** — N+1·인덱스·단순 Redis 캐싱을 부트캠프·독학이 쇼핑몰/게시판 클론 위에 똑같이 찍어낸다. 교체 가능한 부품.
2. **클로드가 코드·아키텍처를 평준화** — "복잡한 gRPC 시스템·파티셔닝·락 만들었다"는 자랑은 클로드가 누구한테나 만들어주는 순간 **차별점이 아니다**([`25-doc §36`](../tasks/25-portfolio-strategy.md)). 면접관은 모두가 AI 쓴다고 전제한다.

→ **"무엇을 만들었나"로는 못 이긴다.**

### 0.2 그래서 차별점 = AI가 못 만드는 것

| 진짜 차별점 (희소) | 보조 (천장 있음) |
|---|---|
| **① 측정·판단** — 가설→측정→발견→"안 하기로 한 결정". AI 못 하는 인간 20% | 운영 인식(HikariCP·파티셔닝·마이그레이션) — **시뮬레이션 천장**(실트래픽 없음). 헤드라인❌, "운영을 의식한다" 증거로만 |
| **② 실신호** — 베타 사용자 실버그·실피드백. AI 절대 못 만듦 ([`24-plan`](../tasks/24-semester2-plan.md) 베타 테스트) | 아키텍처 복잡도 — 평준화됨. 조연 |
| **③ 내 AI 코드 100% 이해·라이브 방어** — 옆 클로드 유저와 나를 가르는 유일한 것 | |

### 0.3 이 프로젝트의 교체 불가능 지점 (reframed)

1. **측정·판단 깊이** — "인덱스 넣었어요"❌ → *"측정해보니 인덱스 이미 최적, 진짜 병목은 payload"*(§4.3 발견). 카고컬트 반대 시그널. ⭐ AI 시대 핵심.
2. **이벤트 적재 정합성** (gRPC×DB) — 사용자 "저장" 클릭(CRUD)이 아니라 별도 서비스가 at-least-once로 미는 배치를 정합성 있게 받음(낙관락·멱등성). **단순 CRUD가 절대 안 만나는 문제**, 실제 구현됨.
3. **실신호** — 베타로 확보할 authentic 트러블 1~2개.

### 0.4 경쟁 포지션 (정직하게 — 어디서 이기고 지나)

| 상대 | 결과 |
|---|---|
| 단순 CRUD 포폴 | **이김** — 이벤트 적재 정합성 + 시계열 (CRUD엔 측정·결정할 게 적음) |
| 실운영 MAU1000 (단순) | **실신호는 짐**(AI 못 만듦), **측정·판단 깊이는 경쟁**. 아키텍처는 무승부(둘 다 AI) |
| 잘 만든 MSA | **breadth·키워드는 짐** — SQS 도입으로 키워드 갭 일부 보완(동기 콜백 디커플링, 진짜 개선) |
| 카고컬트 MSA | **이김** — "왜 MSA 안 했나" 판단 + 실제 분산 문제 해결 |

### 0.5 정직한 천장

- ❌ "1~2년차/운영 경력 위장" (실트래픽·실장애 못 만듦)
- ✅ **"CRUD만 있는 median 신입보다 한 단계 위"** — 분산·이벤트·정합성을 *의식하고 구현·측정해본* 신입
- → **헤드라인 = "측정·판단 + 이벤트 적재 정합성 + 베타 실신호".** 운영·아키텍처 복잡도는 조연.

---

## 1. 강점 축 vs 안 파는 축 (정직하게)

| 🟢 깊게 파도 됨 (희소) | 🔴 억지로 팔면 털림 |
|---|---|
| 대용량 시계열 (연 ~8억 행/~800GB) | 고 throughput/QPS (DAU 1,000은 작음, §4.2) |
| JSON 컬럼 운영 (projection·하이드레이션) | 샤딩 (규모상 불필요) |
| 파티셔닝 + TTL/DROP PARTITION | read replica (실수요 없음 → "확장 대비 설계"로만) |
| gRPC 결합 정합성 (락·멱등성) | 무거운 락 경합·데드락 (append-only라 자연 hotspot 없음 → 합성) |
| 측정 기반 읽기 최적화 (projection·캐싱) | 인덱스 튜닝 단독 (리포트 쿼리 이미 최적, §4.3) |

> "안 한 것/규모상 불필요한 것을 정직하게 구분"하는 게 없는 QPS 자랑보다 강함 — median 신입보다 위인 판단 시그널(§0.5).

---

## 2. 항목별 깊이 트랙

### A. 쓰기 최적화 — batch insert ✅ 완료

- **문제**: `PoseDataService.savePoseDataBatch`의 JPA `saveAll`이 `@GeneratedValue(IDENTITY)` 때문에 Hibernate batch가 원천 차단 → 개별 INSERT N방.
- **해결**: `JdbcTemplate.batchUpdate` multi-row INSERT로 전환 (`FeedbackLogService` 패턴). IDENTITY 우회, INSERT 25방→1방.
- **결과** (공정 측정, 워밍업 통제, [`load-test-strategy §7.6`](../decisions/load-test-strategy.md)):
  - throughput **23.5 → 46.7 RPS (+99%)**, p50 −64%, p99 7,549→4,784ms (**−37%**)
- 면접: "왜 config(`hibernate.jdbc.batch_size`)로 안 풀고 JdbcTemplate? → IDENTITY라 Hibernate batch 미발동, 드라이버 레벨 batch가 정석."

### B. 읽기 최적화 — projection / 캐싱 / precompute ⬜ 헤드라인 본편

목표 = `GET /reports/sessions/{id}` 응답시간 before/after.

1. **projection** 🔶 — `ReportService`가 엔티티 전체 로드 → worst 구간 계산은 `syncRate`·`feedbackMessage`·`timestampSec` 3개만 쓰는데 `joint_coordinates`(JSON 1~3KB) 까지 끌어옴. 세션당 ~1,500행 × ~2KB = **~3MB 헛로드**. → 3컬럼 projection DTO. **헤드라인: payload 3MB→0.05MB, 응답 X→Y ms** (측정값이 헤드라인 결정, 사전 고정 금지).
2. **Redis 캐싱** ⬜ — 세션 종료 후 리포트 불변 → cache-aside, 높은 적중률. stampede 방지.
3. **precompute-on-write** ⬜ — worst 구간을 세션 종료 시 1회 계산해 `reports`에 저장 → GET 때 pose_data 스캔 제거. denormalization.

> ⚠️ 구 "인덱스 추가 → 850ms→12ms" 가설은 **폐기**. `schema.sql:86 idx_session_timestamp(session_id, timestamp_sec)`이 이미 쿼리에 최적이고 세션 단위라 테이블 성장에 무관(§4.3). 헤드라인은 인덱스가 아니라 **payload 축소**.

### C. 동시성 / 정합성 — gRPC × DB 교집합 ⭐

| 항목 | 상태 | 근거 |
|---|---|---|
| **낙관적 락**: 타임아웃 스케줄러 vs FastAPI 완료 콜백 경합 | ✅ | `Session.java:65 @Version`, `SessionTimeoutScheduler.java:84` 충돌 시 양보 |
| **멱등성**: at-least-once gRPC 콜백 재전송 | ✅ | `FeedbackLogService.java:33` `INSERT IGNORE` + `uk_session_event` |
| **일일 집계 lost-update** | 🔶 개발 | `DailyLog.updateStats()`(L45) 존재하나 **호출처 0** — 배선 시 동시 종료 경합 |
| **report 생성 멱등성** | 🔶 후보 | `reports`에 session_id 유니크 없음 → 종료 재시도 시 중복 가능성 |

상세 스토리는 [`problem-solving-log.md #3·#4`](./problem-solving-log.md).

### D. 시계열 대용량 — 파티셔닝 + 보존정책 ⬜ 설계+트리거

- **무한 성장**: 연 ~8억 행 시계열을 무한 적재 불가. TTL이 정답.
- **pose_data는 중간 산출물** — 리포트 worst 구간 계산용. precompute(B-3) 후 cold data → TTL 안전(UX 손실 0). precompute가 TTL을 *안전하게* 만든다(상호 강화).
- **구현은 DELETE 아니라 DROP PARTITION**: 월 단위 Range 파티셔닝 → 가장 오래된 파티션을 **O(1) 메타데이터 연산**으로 제거(락 거의 없음). 대량 DELETE는 락·undo 폭발.
- **파티셔닝의 진짜 가치 = 쿼리 pruning 아니라 "값싼 TTL"** (쿼리는 이미 인덱스로 빠름). 이 reframe이 핵심.
- (선택) S3 아카이빙 → DROP. FK `session→pose_data` ON DELETE CASCADE.
- **트리거 명시**: DAU 50엔 불필요. pose_data 수천만 행 / 단일 테이블 백업·ALTER가 운영 부담일 때 발동.
- 포폴 가치: 보존정책 + partition-drop은 신입이 거의 안 함 (§4.7).

### E. CS 토픽 → 내 코드 매핑 (MVCC·격리수준)

> 교과서 재현 단독❌ (누구나 함, "공부 노트" 인상). **실제 코드 결정에 앵커할 때만** 강함.

3단 답변(정의 + 본인 코드 위치 + 트레이드오프):

| 토픽 | 정의 | 내 코드 위치 | 트레이드오프 (30초) |
|---|---|---|---|
| **낙관적 vs 비관적 락** | @Version 충돌 감지 vs FOR UPDATE 블로킹 | 타임아웃 vs 콜백 (`SessionTimeoutScheduler`) | 저경합·읽기위주 → 낙관. 비관은 블로킹 비용 |
| **격리수준** | RC / RR / Serializable | 일일 집계 lost-update | RC는 lost-update 못 막음 → 원자 UPDATE·락 필요 |
| **MVCC** | 스냅샷 일관성 읽기 | batch insert ↔ 리포트 조회 | 읽기-쓰기 비블로킹, undo 비용 |

- **정합성/이상현상 추론** = 🟢 진짜 (위 앵커 실재). **throughput 벤치마킹**(MVCC가 락보다 빠르다 류) = 🟡 합성(저경합).
- RealMySQL 적합도: Ch.13 파티셔닝🟢 · Ch.8 인덱스🟢(이미 최적 발견 포함) · Ch.9·11 옵티마이저🟢 · Ch.18 운영🟢 · Ch.4·5 잠금/MVCC🟡 · Ch.16 복제🔴.

---

## 3. 측정 방법론 원칙 (함정 회피)

수치 개선 사례의 신뢰성을 가르는 것:
- **워밍업 통제 필수** — cold JVM은 초반 인터프리터 모드로 2~5배 느림. before만 warm/after는 cold면 "개선안이 더 느림"으로 오측정 (§7.6 1차 무효 교훈).
- **동일 환경 비교** — 같은 절차(재빌드→cold 기동→warmup 60s→리셋→ramp)끼리만 비교.
- **금지**: 인위적 개악 후 개선, 숫자만 외움, 측정 방법 모름, 다른 시점 머신부하 섞기.

---

## 4. 관련 문서

- [`./problem-solving-log.md`](./problem-solving-log.md) — 문제해결 경험 카드 (3~10월)
- [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md) — 부하 테스트 전략·측정값(§7), 용량 산정(§4)
- [`../decisions/redis-introduction.md`](../decisions/redis-introduction.md) — Redis 도입 보류 결정 (안 하기로 한 결정)
- [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md) — 진로 전략 회고, 깊이 트랙 후보
- 코드: `backend/.../service/Exercise/PoseDataService.java`(batch insert), `SessionTimeoutScheduler.java`(낙관적 락), `FeedbackLogService.java`(멱등성), `model/exercise/Session.java:65`(@Version)
