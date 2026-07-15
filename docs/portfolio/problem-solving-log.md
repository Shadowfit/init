# 포폴: 문제해결 경험 로그 (3~10월)

작성: 2026-06-02
대상: 백엔드(Spring) 신입 포폴. 프로젝트 기간(2026-03~10) 동안 **본인이 실제로 해결한 문제**를 problem→root cause→solution→result→면접답변 형식으로 박제.
연관: [`./db-deep-dive.md`](./db-deep-dive.md), [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md), [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md)

> ✅=완료·검증, 🔶=개발하면 스토리화, ⬜=계획. 강도: 🔴 헤드라인 / 🟠 트러블슈팅 / 🟡 소형.

---

## 0. 정직성 전제 (먼저 박을 것)

- **backend/ 커밋 55/62가 본인(Khyojae)** → Spring 백엔드 문제해결은 떳떳하게 본인 것.
- ⚠️ **ai-server(FastAPI) 측 커밋은 본인/팀원 경계 모호** → 포폴에 넣기 전 `git blame`으로 본인 작업분만 분리. (팀원: jiho/demetergod, hojin — AI·프론트 위주)
- 면접은 "본인이 실제 한 작업"만. AI-server 트러블을 본인 것처럼 말하면 라이브 질문에서 무너짐.

---

## 1. 타임라인

| 기간 | 단계 | 성격 |
|---|---|---|
| **3~5월** | 1학기 MVP (✅) | 기능 구축 + 문제해결 일부 (#1~#9) |
| **6~8월** | 방학 (지금~) | **수치 문제해결 본편** (읽기 최적화·동시성 개발) |
| **9~10월** | 2학기 시작 | 운영(SLO·Resilience4j)·발표 |

> 솔직한 현실: "기능 구축"은 거의 끝났고, **"수치로 증명하는 문제해결"은 대부분 6~10월에 있음.** 지금이 그걸 만드는 적기.

---

## 2. 문제해결 카드 (완료, 본인 backend)

### #1 🔴 batch insert N방 → JdbcTemplate (throughput +99%)
- **문제**: pose_data 적재가 동시성 부하에서 느림(p99 수 초).
- **Root cause**: `PoseDataService.savePoseDataBatch`의 JPA `saveAll`이 `@GeneratedValue(IDENTITY)` 때문에 Hibernate batch 원천 차단 → 개별 INSERT N방.
- **Solution**: `JdbcTemplate.batchUpdate` multi-row INSERT (IDENTITY 우회, 25방→1방).
- **Result**: throughput **23.5→46.7 RPS(+99%)**, p50 −64%, p99 **−37%** (공정 측정, [`load-test §7.6`](../decisions/load-test-strategy.md)).
- **면접**: "config `batch_size`로 왜 안 풀었나? → IDENTITY라 Hibernate batch 미발동, 드라이버 레벨 batch가 정석."

### #2 🔴 타임아웃 스케줄러 vs FastAPI 콜백 경합 → 낙관적 락
- **문제**: 백그라운드 타임아웃 스케줄러(IN_PROGRESS→FAILED, 1분마다)와 FastAPI 완료 콜백이 **같은 세션 row 동시 갱신**. 타임아웃 직전 결과 도착 시 경합 → 완료된 세션이 잘못 FAILED 될 수 있음.
- **Solution**: `Session.java:66 @Version` 낙관적 락. 충돌 시 `ObjectOptimisticLockingFailureException`을 잡아 **스케줄러가 양보**(결과 데이터 우선, `SessionTimeoutScheduler.java:84`). yield 건수 로깅.
- **Result**: 정합성 깨짐 방지 + 관측 가능.
- **면접**: "왜 비관적 락(FOR UPDATE)·SERIALIZABLE이 아니라 낙관적 락? → 경합 빈도 낮고 읽기 위주라 블로킹 비용이 아까움. 누가 이기는 게 옳은지(충돌 해소 정책)까지 설계." ⭐ gRPC×DB 교집합.

### #3 🔴 at-least-once gRPC 콜백 → 멱등성 (INSERT IGNORE)
- **문제**: AI가 BT-SET 피드백을 batch로 송신, 네트워크 재시도 시 **중복 전송** 가능.
- **Solution**: `(session_id, occurred_at, feedback_type)` 유니크키 + `FeedbackLogService.java:33` **`INSERT IGNORE`**로 중복 흡수. inserted/skipped 카운트 반환.
- **Result**: 재전송돼도 중복 row 0.
- **면접**: "exactly-once가 아니라 at-least-once 전제에서 멱등성으로 푼다 — 분산 시스템 정석. DB 유니크 제약에 위임."

### #4 🟠 gRPC long 정밀도 손실 버그
- **문제**: `StopAnalysis` 세션 ID(long)가 gRPC/JSON 경계에서 정밀도 손실 + 응답 DTO 정수 타입 불일치.
- **Solution**: 타입 일관성 정리 + DTO 정수 타입 통일 (commit 2026-05-17).
- **Result**: 세션 ID 정합성 버그 해결.
- **면접**: "JSON number의 정밀도 한계(53bit) ↔ Java long(64bit) 경계 문제. 직렬화 계약을 의식." (※ 정확한 수정 라인은 코드 재확인 권장.)

### #5 🔴 Redis 도입 보류 — "안 하기로 한 결정"
- **상황**: 캐싱으로 Redis 도입 압박.
- **판단**: "MySQL이 부족하다는 게 **엄격하게 미증명**"이라 측정 없이 도입 거부 ([`redis-introduction.md`](../decisions/redis-introduction.md)).
- **면접**: "도입하면 인프라 복잡도·일관성 비용. 병목을 측정으로 입증한 뒤 도입하는 게 맞다." ⭐ 카고컬트 Redis 스토리의 정반대 = 시니어 시그널.

### #6 🟠 부하 측정 방법론 함정 (cold JVM)
- **문제**: 1차 측정에서 "batch 개선안이 오히려 느림"으로 잘못 나옴.
- **Root cause**: before만 warm(58분), 개선안은 cold(기동 직후). cold JVM 인터프리터 모드가 ramp 저동시성 구간 오염 → 측정한 게 "batch 효과"가 아니라 "워밍업 차이".
- **Solution**: 공정 절차 확립(재빌드→cold 기동→**warmup 60s 폐기**→리셋→ramp). 같은 절차끼리만 비교.
- **면접**: "JVM 서비스 부하 측정은 워밍업 통제가 필수." ([`load-test §7.6`](../decisions/load-test-strategy.md))

### #7 🟠 측정 종료 에러 100건 원인 규명
- **문제**: 모든 ramp에 `Unavailable` 100건 고정 재현.
- **Root cause**: `details` timestamp 분석 → 에러가 **측정 종료 직전 ~1~2ms에 전부** 몰림(그 전 210초간 0건). 정확히 `--concurrency-end=100`과 일치 = ghz `-z` 종료 시 잘린 in-flight 요청. **서버 결함 아님**(max-connections/GC 등 추정 기각).
- **면접**: "에러 숫자만 보고 서버 한계로 단정 안 함. 데이터로 측정 아티팩트임을 증명." ([`load-test §7.7`](../decisions/load-test-strategy.md))

### #8 🟡 data.sql 연동 실패
- data.sql 정보가 연동 안 되던 문제 디버깅→해결 (commit 2026-04-25~26). 소형 트러블슈팅.

### #9 🟡 LocalDateTime 직렬화
- `write-dates-as-timestamps=false`로 LocalDateTime ISO string 직렬화 정정 (commit 2026-05-31). 직렬화 계약 일관성. 소형.

---

## 3. 개발 예정 카드 (6~8월, 만들면 스토리)

| 카드 | 성격 | 비고 |
|---|---|---|
| 🔶 **읽기 최적화 (projection)** | 🔴 헤드라인 | `ReportService` JSON blob 헛로드 → 3컬럼 DTO. payload 3MB→0.05MB ([`db-deep-dive §2-B`](./db-deep-dive.md)) |
| 🔶 **일일 집계 lost-update** | 🟠 동시성 | `DailyLog.updateStats()` 배선 → 동시 종료 경합 → 원자 UPDATE/락 |
| 🔶 **report 생성 멱등성** | 🟠 정합성 | session_id 유니크/upsert로 중복 리포트 방어 |
| ⬜ **파티셔닝 + TTL** | 시계열 운영 | 월 Range + DROP PARTITION ([`db-deep-dive §2-D`](./db-deep-dive.md)) |
| ⬜ **Resilience4j** | 운영 신뢰성 | Spring→FastAPI Circuit Breaker |

---

## 4. 면접 답변 정련 가이드

각 🔴/🟠 카드를 **3단 길이**로 준비:
- **30초**: 문제 → 핵심 해결 → 수치 결과 (예: "JPA saveAll이 IDENTITY로 batch 차단돼 개별 INSERT N방 → JdbcTemplate batch로 throughput +99%")
- **2분**: + root cause 진단 과정 + 트레이드오프 + 왜 다른 대안 아님
- **10분**: + 측정 방법 + 코드 + 회귀 위험 + 다음에 할 것

> 포화 시장의 승부처(§25-doc §36): 산출물 자체보다 **"왜 이렇게? 단점은? 대안은? 측정은?"에 라이브로 답**하는 능력. 위 카드 전부 그 질문을 견디게 준비.

---

## 5. 관련 문서
- [`./db-deep-dive.md`](./db-deep-dive.md) — DB 깊이 (시계열·정합성·격리수준)
- [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md) — 부하 테스트 측정값·방법론
- [`../decisions/redis-introduction.md`](../decisions/redis-introduction.md) — Redis 보류 결정
- [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md) — 진로 전략 회고
