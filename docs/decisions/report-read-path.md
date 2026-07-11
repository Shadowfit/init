# 리포트 기능 읽기 경로 감사 — 병목·갭·해결 로드맵

상태: **분석 완료(코드 기준), 해결 착수 미결 — 결정은 사용자 confirm 후**
작성: 2026-06-14
대상: 백엔드(Spring) 포폴. 리포트 기능(`GET /reports/sessions/{id}`)의 **읽기 경로**를 코드로 감사 — 실제 병목/갭이 뭔지, 인덱스로 풀리는 것/안 풀리는 것, 일반(CRUD) 프로젝트와의 차이, 정직한 현재 상태 분류.
연관: [`../portfolio/db-deep-dive.md`](../portfolio/db-deep-dive.md) §B, [`../portfolio/realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §②b, [`./load-test-strategy.md`](./load-test-strategy.md) §4.6, [`./report-aggregation.md`](./report-aggregation.md), [`../portfolio/portfolio-narrative.md`](../portfolio/portfolio-narrative.md)

> ✅=코드/측정으로 확인, 🔶=실재하나 미발동/미구현, ⬜=계획. **결정 마크는 사용자 confirm 후에만.**

---

## 0. 한 줄 요약

리포트는 **읽기 축(read-heavy, GET)** 이라 쓰기 최적화(락·트랜잭션)가 아니라 **쿼리·payload·캐싱**에 의존한다. 현재 읽기 경로엔 코드로 실재하는 갭이 4개(over-fetch·재계산·REPORT_NOT_FOUND·인덱스 갭), 규모 미발동 잠재 2개(버퍼풀 오염·무제한 프레임)가 있다. **인덱스는 "찾기"만 풀고**, 리포트의 진짜 병목(fat 컬럼·재계산·오염)은 인덱스 바깥(projection·precompute)에 있다.

---

## 1. 현재 읽기 경로 (코드 기준)

`ReportService.getSessionReport`([`ReportService.java:33`](../../backend/src/main/java/com/shadowfit/service/Report/ReportService.java))가 던지는 쿼리 4개:

```
GET /reports/sessions/{id}
 1. findSessionWithExerciseById         ← JOIN FETCH s.exercise (SessionRepository.java:16)
 2. reportRepository.findBySessionId     ← Report 1행, 없으면 REPORT_NOT_FOUND (ReportService.java:41)
 3. findFirstByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc  ← 이전 세션(비교용)
 4. findBySessionIdOrderByTimestampSecAsc ← pose_data 전체 로드 (★ 무거움, ReportService.java:51)
```

이후 `buildReportResponse` → `selectWorstSection`([`:74`](../../backend/src/main/java/com/shadowfit/service/Report/ReportService.java))에서 연속 3프레임(`WORST_WINDOW_SIZE=3`) 슬라이딩으로 최저 syncRate 구간을 **자바 메모리에서 O(N) 재계산**.

**데이터 모델**: pose_data 1행 = 분석 프레임 1개. `joint_coordinates`(@Lob TEXT, [`PoseData.java:26`](../../backend/src/main/java/com/shadowfit/model/exercise/PoseData.java))는 프레임당 33 랜드마크 JSON ≈ 2.3KB. 세션당 ~750행 → ~1.7MB.

---

## 2. 발견된 문제 (코드 기준 분류)

| # | 문제 | 상태 | 코드 근거 |
|---|------|------|-----------|
| ① | **fat 컬럼 over-fetch** — 풀엔티티 로드, worst 계산은 `syncRate`·`timestampSec`·`feedbackMessage` 3개만 씀. `joint_coordinates`는 **한 번도 안 씀** | ✅ 실재 + 측정 | `findBySessionIdOrderByTimestampSecAsc`(전체) vs `selectWorstSection` 사용 필드 |
| ② | **재계산-on-read** — precompute 없이 매 GET마다 worst 구간 재계산 | ✅ 실재 | `selectWorstSection` 매 호출 |
| ③ | **REPORT_NOT_FOUND 갭** — `completeAnalysis`가 `session`만 UPDATE, `reports`엔 안 씀 → 실제 세션은 Report 행 없음 → 404 | ✅ 실재(기능 끊김) | `applyCompleteFromApp`([`ExerciseAnalysisService.java:217`](../../backend/src/main/java/com/shadowfit/service/Exercise/ExerciseAnalysisService.java)) session만 UPDATE / `reports`는 [`data.sql:168`](../../mysql/data.sql) 시드뿐 |
| ④ | ~~**exercise_sessions 인덱스 갭** — `(member_id, start_time)` 등 복합 인덱스 없음(FK 단일뿐) → 캘린더·이전세션 쿼리 filesort~~ | **✅ 해결(2026-07-11)** | `idx_session_member_starttime` 추가([`schema.sql:71`](../../mysql/schema.sql)), 실측: 월간 조회 1675행 Filter → 143행 Index range scan(cost 91.4→64.6), 연간 조회는 Covering index scan으로 전환. 커밋 `dbb0fec` |
| ⑤ | **버퍼풀 오염** — cold 거대 raw를 서빙 경로가 건드려 핫 데이터 evict | 🔶 메커니즘 실재, 규모 미발동 | DAU 작아 리포트 조회 드묾 |
| ⑥ | **무제한 프레임 극단** — R(프레임/세션)이 위로 열림(fps 미강제) → 극단 세션이 수십~수백 MB 로드·O(N) 루프·OOM 위험 | 🔶 가능성 실재, 미발동 | 정상 750행, [`load-test-strategy.md`](./load-test-strategy.md) §4.5 "R 위로 열림" |

---

## 3. 인덱스가 푸는 것 / 못 푸는 것 (핵심 reframe)

읽기를 단계로 쪼개면 인덱스는 **①찾기만** 담당:

| 시계열 읽기 하위문제 | 인덱스로 풀리나 |
|---|---|
| ① 어느 행/범위 찾고 정렬 | ✅ **풀린다** (단건 리포트는 이미 최적; 캘린더도 복합 인덱스 추가로 해결=문제④, 2026-07-11) |
| ② 찾은 fat 행 JSON 펼치기 | ❌ projection |
| ③ derived(worst 구간) 재계산 | ❌ precompute |
| ④ cold raw 버퍼풀 오염 | ❌ 분리/티어링 |

- 단건 리포트는 `idx_session_timestamp(session_id, timestamp_sec)`([`schema.sql:86`](../../mysql/schema.sql))로 **①이 이미 최적**(세션 바운드, filesort 0, [`db-deep-dive.md`](../portfolio/db-deep-dive.md) §4.3) → 남는 병목 ②③⑤가 전부 **인덱스 바깥**.
- **cross-session(캘린더·이전세션)도 ①이 인덱스 갭이었으나 해결됨**(문제④, `idx_session_member_starttime` 추가) — member 바운드+누적이라 데이터 쌓이면 인덱스 없이는 진짜 느려지는 구조였음(pose_data와 정반대).

**쓰기(적재)와도 대비**: 적재 경로엔 인덱스가 *비용*(쓰기 증폭·페이지 분할). 시딩 때 인덱스 빼고 일괄 빌드한 이유([`realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §3). → 인덱스가 닿는 건 "읽기의 찾기" 한 칸뿐.

---

## 4. 일반(CRUD) vs 시계열 읽기 문제 클래스

| | 일반 (게시판·쇼핑몰·CRUD) | 너희류 (시계열 적재 + derived 리포트) |
|---|---|---|
| 읽기 핫패스 | 목록·검색(다행) | 단건 리포트(부모의 children) |
| 행 특성 | 작고 균일 | **행마다 fat JSON(off-page)** |
| 주 문제 | N+1, 인덱스 부재→풀스캔, 깊은 offset | **fat 헛로드·재계산·버퍼풀 오염·raw-in-OLTP** |
| 인덱스 추가 효과 | 극적(헤드라인) | 이미 최적(조연) |
| projection 효과 | 작음(행 작음) | **큼(−98.7%)** |
| 해결 성격 | textbook=평준화 | **판단 필요**(denormalization·티어링) |

→ 같은 "읽기"여도 **병목이 어느 단계냐**가 달라, 인덱스가 헤드라인(CRUD)이냐 조연(시계열)이냐가 갈린다. 유사 프로젝트도 *제대로 측정하면* 같은 문제를 만나지만 대부분 측정 안 해 모름 → **차별점은 "문제를 가진 것"이 아니라 "측정해 발견한 것"**([`db-deep-dive.md`](../portfolio/db-deep-dive.md) §0.3).

---

## 5. 정직한 현재 상태 분류 (오버셀 금지)

| 등급 | 항목 | 의미 |
|---|---|---|
| 🟢 **실재 + 측정** | ① over-fetch (projection −98.7% 측정) | 코드에 있고 합성 볼륨으로 측정 |
| 🟢 **실재 (코드)** | ② 재계산, ③ REPORT_NOT_FOUND | 코드/스키마에 실재 |
| ✅ **해결됨(2026-07-11)** | ④ 인덱스 갭 | `idx_session_member_starttime` 추가, 실측 확인 — 커밋 `dbb0fec` |
| 🟡 **잠재 (규모 미발동)** | ⑤ 버퍼풀 오염, ⑥ 무제한 프레임 | 메커니즘 실재하나 DAU 작아 안 터짐 |

**메타 포인트**: 이건 "프로덕션 화재"가 아니라 "**코드를 읽고 측정해 발견·판단한 것**". 실트래픽 작아 실제로 아픈 건 아니지만 코드엔 실재. → §0.5 정직한 천장이자 차별점("운영 위장"❌ / "median 신입보다 위"✅). 면접에선 **"실재+측정 / 잠재+미발동"을 갈라** 말해야 과장도 약함도 아님.

---

## 6. 해결 로드맵 — 무엇이 무엇을 푸나

| 문제 | projection | precompute | 복합 인덱스 | 다운샘플 |
|---|---|---|---|---|
| ① fat 헛로드 | ✅ | ✅ | | |
| ② 재계산 | | ✅ | | |
| ③ REPORT_NOT_FOUND | | ✅ | | |
| ④ 인덱스 갭(캘린더) | | | ✅ **완료(2026-07-11)** | |
| ⑤ 버퍼풀 오염 | 🔶 부분 | ✅ | | |
| ⑥ 무제한 프레임 | ❌ | ✅ | | ✅ |

→ **precompute가 6개 중 4개를 한 번에 푼다**(세션 종료 시 worst 구간·요약을 `reports`에 1회 저장 → 조회가 raw를 0개 읽음). projection은 그 전까지 완화책(per-row만), 복합 인덱스는 별개 축(세션 히스토리, 완료)이고, 다운샘플은 프레임 수 자체([`pose-ingest-downsampling.md`](./pose-ingest-downsampling.md)).

**추천 착수 순서(미confirm)**: ① projection(가볍다, 쿼리/DTO만) → ② precompute(`completeAnalysis`에서 Report 생성 + worst 저장, ③·⑥도 해결) → ~~③ 복합 인덱스(캘린더)~~ **완료** → ④ raw TTL.

**부수 발견(2026-07-11)**: 복합 인덱스 실측 중 `calculateConsecutiveDays`(연속일수 계산)가 실제 세션 데이터가 있으면 항상 500(`java.sql.Date` → `LocalDate` 컨버터 없음, `ConverterNotFoundException`)을 내는 걸 발견·수정. 이전까지 이 경로를 테스트할 때 결과가 항상 빈 배열이라 드러나지 않았던 버그 — `GET /reports/calendar`가 실사용자 데이터로는 사실상 항상 터지고 있었을 것. 인덱스와는 무관한 별개 버그, 커밋 `eed807e`.

---

## 7. projection 구현 (③단계, 코드 — 미적용)

3파일 변경. **적용은 사용자 결정 대기**(3파일↑ 사전 범위 공유 규칙).

**① 신규 DTO** — `dto/report/PoseFrameProjection.java`
```java
package com.shadowfit.dto.report;

// worst 구간 계산에 필요한 3컬럼만. joint_coordinates(2.3KB) 제외 → off-page I/O 회피.
public record PoseFrameProjection(Double timestampSec, Double syncRate, String feedbackMessage) {}
```

**② Repository** — 명시적 JPQL로 컬럼 고정
```java
@Query("SELECT new com.shadowfit.dto.report.PoseFrameProjection(" +
       "p.timestampSec, p.syncRate, p.feedbackMessage) " +
       "FROM PoseData p WHERE p.session.id = :sessionId ORDER BY p.timestampSec ASC")
List<PoseFrameProjection> findFramesBySessionId(@Param("sessionId") Long sessionId);
```
생성 SQL: `SELECT timestamp_sec, sync_rate, feedback_message FROM pose_data WHERE session_id=? ORDER BY timestamp_sec` (joint_coordinates 없음). `p.session.id`는 FK 컬럼 직접 사용(session 조인 안 함).

**③ ReportService** — `selectWorstSection`/`buildWorstReason`/`pickDominantFeedback` 시그니처를 `List<PoseFrameProjection>`로, 접근자 `getSyncRate()→syncRate()` 등으로 교체. 호출부(`:51`)를 `findFramesBySessionId`로.

> 기존 `findBySessionIdOrderByTimestampSecAsc`는 교체 후 미사용(테스트는 `findAll`/`count`만 씀) → 제거 선택.

**측정 기대치**(이미 [`realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §②b): payload 1,716.8KB→22.4KB(−98.7%), warm 쿼리 12.1ms→1.5ms(8x). 바이트는 −98.7%인데 시간은 −87%인 이유 = 750 row lookup 등 고정비용은 남고 off-page JSON I/O만 사라지기 때문(바이트≠시간 선형).

---

## 8. 미결정 (사용자 confirm 필요)

- [ ] projection 코드 적용 여부 (위 §7)
- [ ] precompute 착수 — `completeAnalysis`에서 Report 생성(③·⑥ 동시 해결). 단 `reports` write 경로 신설 = AI 측 요약 송신 계약과 맞물림([`report-aggregation.md`](./report-aggregation.md) 참조)
- [ ] exercise_sessions 복합 인덱스 추가 `(member_id, start_time)` / `(member_id, exercise_id, status, start_time)`
- [ ] 착수 순서: projection 먼저(가벼움) vs precompute 먼저(헤드라인·4개 해결)

---

## 9. 관련 문서
- [`../portfolio/db-deep-dive.md`](../portfolio/db-deep-dive.md) §B(읽기 최적화)·§2-0(raw→buffer/serving 분리)
- [`../portfolio/realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §②b(projection 측정)
- [`./load-test-strategy.md`](./load-test-strategy.md) §4.6(살아남는 최적화 스토리)
- [`./report-aggregation.md`](./report-aggregation.md) — 리포트 집계 로직 설계
- [`./pose-ingest-downsampling.md`](./pose-ingest-downsampling.md) — 적재 다운샘플(⑥ 완화)
- 코드: `ReportService.java`(읽기 경로), `PoseDataRepository.java`, `ExerciseAnalysisService.java:217`(완료 콜백), `SessionRepository.java`(세션 쿼리), `mysql/schema.sql:55,86`(인덱스)
