# 주간 요약 B층 `JSON_TABLE` 쿼리 — 튜닝할 것인가, 무엇으로 할 것인가

작성: 2026-09-14
상태: **✅ ㄴ-3 채택 (2026-09-15 사용자 confirm)** — 결정 1 «측정 먼저»(09-14) → 측정(§7) → 결정 2′ ㄴ-3 채택·코드 반영. ㄹ 은 보류(3′), ㄷ·ㅁ 탈락(4′).
측정 장치: [`loadtest/measure_weekly_json_table.sh`](../../loadtest/measure_weekly_json_table.sh) · 결과 원본: [1차 `weekly-json-table-2026-09-14.txt`](../../loadtest/results/weekly-json-table-2026-09-14.txt)(현행+후보 ㄷ·ㄹ, ㄴ-3 는 말미 ad-hoc) · [2차 `weekly-json-table-2026-09-15-n3.txt`](../../loadtest/results/weekly-json-table-2026-09-15-n3.txt)(ㄴ-3 를 정식 쿼리로 8셀)
발단: "쿼리 튜닝 ①관측→②계획→③실측→④수정→⑤before/after 사이클을 끝까지 돌린 기록이 이 저장소에 없다" — 인덱스 구성 실측([`session-index-composition.md`](./session-index-composition.md))은 있지만 **쿼리 재작성 축**은 비어 있다. 첫 대상으로 인덱스만으로는 못 푸는 쿼리를 고른다.
대상 코드: [`WeeklySummaryQueryRepositoryImpl.java`](../../backend/src/main/java/com/shadowfit/repository/report/WeeklySummaryQueryRepositoryImpl.java) `repCurveBetween` · `worstRepDistributionBetween`
연관: [`report-generation-llm.md`](./report-generation-llm.md) §13-2(쿼리 원안 — "**계획을 재보기 전에는 카드라고 부르지 않는다**") · [`db-portfolio-roadmap.md`](./db-portfolio-roadmap.md) §증거(EXPLAIN·p99 before/after) · [`admin-page-scope.md`](./admin-page-scope.md) §4-5(EXPLAIN `rows` 38배 부풀림 사례) · [`worst-section-rep-resolution.md`](./worst-section-rep-resolution.md)(precompute-on-write 결정)

---

## 0. 한 줄 요약

주간 요약의 회차 곡선(Q2)·worst 회차 분포(Q3)는 `session_reports.detailed_analysis`(JSON)를 `JSON_TABLE` 로 펼쳐 집계한다. **인덱스를 얹어서 풀리는 모양이 아니다** — JSON 파싱과 집계가 비용이고, 옵티마이저가 `session_reports` 와 `exercise_sessions` 중 어느 쪽을 드라이빙하느냐에 따라 읽는 행 수가 «회원의 주간 세션 수» 와 «회원의 누적 리포트 수» 사이에서 갈린다. 후보는 5개(그대로 두기 / 조인 순서·필터 컬럼 변경 / generated column / rep 단위 정규화 표 / 주간 사전집계)이고, **어느 것도 EXPLAIN 없이 고를 수 없다.** 이 문서는 후보와 측정 설계까지만 적는다.

---

## 1. 대상 쿼리와 호출 경로

### 1-1. 쿼리 (코드 그대로)

```sql
-- Q2  회차 위치별 평균 싱크로율 곡선
SELECT jt.rep_number, AVG(jt.sync_rate), COUNT(*)
  FROM session_reports r
  JOIN exercise_sessions s ON s.id = r.session_id
 CROSS JOIN JSON_TABLE(r.detailed_analysis, '$.repTrend[*]'
        COLUMNS (rep_number INT PATH '$.repNumber',
                 sync_rate DOUBLE PATH '$.syncRate')) jt
 WHERE r.member_id = :memberId
   AND s.start_time >= :from AND s.start_time < :to
 GROUP BY jt.rep_number
 ORDER BY jt.rep_number
```

```sql
-- Q3  worst 회차 분포
SELECT jt.worst_rep, COUNT(*)
  FROM session_reports r
  JOIN exercise_sessions s ON s.id = r.session_id
 CROSS JOIN JSON_TABLE(r.detailed_analysis, '$'
        COLUMNS (worst_rep INT PATH '$.worstSection.repNumber')) jt
 WHERE r.member_id = :memberId
   AND s.start_time >= :from AND s.start_time < :to
   AND jt.worst_rep IS NOT NULL
 GROUP BY jt.worst_rep
 ORDER BY COUNT(*) DESC, jt.worst_rep ASC
```

### 1-2. 호출 경로

`GET /reports/weekly-summary` → `WeeklySummaryService.getWeeklySummary` → A층 `totalsBetween` ×2(이번 주·지난 주) → **A층이 비어 있지 않을 때만** Q2·Q3 각 1회. 즉 요청 1건당 Q2·Q3 는 **최대 1회씩**, 범위는 **한 회원의 한 주(7일)**.

### 1-3. 스키마 사실

| 항목 | 값 | 출처 |
|---|---|---|
| `session_reports` 보조 인덱스 | `uk_report_session(session_id)` UNIQUE · FK 자동 인덱스 `(member_id)` | `V1__baseline.sql:259-265` |
| `session_reports` 에 시각 컬럼 | `created_at`(DEFAULT CURRENT_TIMESTAMP) · `updated_at` — **인덱스 없음** | 같은 곳 |
| `exercise_sessions` 회원 인덱스 | `(member_id, status, start_time)` 통합 ㄴ안 + `(member_id, exercise_id, status, start_time)` | [`session-index-composition.md`](./session-index-composition.md) §8 |
| `detailed_analysis` 형태 | `{worstSection: {...}, repTrend: [{repNumber, syncRate, timeStamp}, ...]}` — repTrend 원소 수 = 세션의 측정된 rep 수 | `SessionDetailedAnalysis.java` · `RepSyncRateDto.java` |
| 리포트 생성 시점 | 세션 완료 트랜잭션(`SessionCompletionTx`)에서 1건, 세션당 1건(UNIQUE) | `V1__baseline.sql:261-265` |
| 이 쿼리의 실측 기록 | **없음.** AWS 라이드얼롱 R1 은 리포트 0행 무대라 답이 안 났다 | [`AWS-RIDE-ALONG.md`](../../loadtest/AWS-RIDE-ALONG.md) R1 |

### 1-4. 규모 가정 — 이 문서가 서 있는 전제

[[feedback_state_assumption_design_to_it]] 에 따라 회피하지 않고 못박는다.

- **DAU 1,000** (이 저장소 공통 가정). 활성 회원의 1년차 세션 팬아웃 **156~365** ([`session-index-composition.md`](./session-index-composition.md) §0). 리포트는 완료 세션당 1건이므로 회원당 리포트 수 ≤ 세션 팬아웃.
- **주간 세션 수 W**: 위 팬아웃을 52로 나누면 **3~7**. 이게 Q2·Q3 가 «원리상» 읽어야 하는 행 수다.
- **세션당 rep 수 R**: 실사용 분포 **미측정**. `total_reps` 컬럼이 있으니 실사용 데이터가 생기면 `SELECT AVG(total_reps), MAX(total_reps)` 한 줄이다. 측정 설계(§4)에서는 변수로 둔다.

---

## 2. 왜 「인덱스 추가」로는 안 풀리나 — 접근 경로 분석 (⚠️ 전부 예상, EXPLAIN 미실행)

비용이 생길 수 있는 자리는 세 곳이고, 셋은 서로 다른 처방을 요구한다.

### 2-1. (a) 조인 순서 — 읽는 행이 W 인가 팬아웃 인가

WHERE 의 등호 조건(`r.member_id = ?`)은 `session_reports` 쪽에, 범위 조건(`s.start_time`)은 `exercise_sessions` 쪽에 있다. 옵티마이저는 둘 중 하나를 드라이빙 표로 고른다.

| 드라이빙 | 경로 | 읽는 리포트 행 | JSON 파싱 횟수 |
|---|---|---|---|
| **`r` 먼저** | FK 인덱스 `(member_id)` ref → 회원의 **전 기간** 리포트 → 각각 `s` PK eq_ref → `start_time` 필터 | **팬아웃 전체(1년차 156~365)** | 🔴 미정 — `JSON_TABLE` 이 `s` 필터 **전**에 펼쳐지면 전 리포트를 파싱 |
| **`s` 먼저** | `(member_id, status, start_time)` range → 주간 세션 W 건 → 각각 `r` UNIQUE eq_ref | **W(3~7)** | W 회 |

두 경로의 차이가 **50배**(365/7)다. 어느 쪽을 고르는지는 통계(카디널리티)에 달려 있고, 이 rig 의 EXPLAIN `rows` 는 38배 부풀린 전과가 있다([`admin-page-scope.md`](./admin-page-scope.md) §4-5). **그래서 EXPLAIN 이 답할 첫 질문은 "빠르냐" 가 아니라 "어느 표를 먼저 읽느냐" 다.**

`JSON_TABLE` 의 조인 위치도 미검증이다. MySQL 은 `JSON_TABLE` 을 lateral derived table 처럼 다루는데, `s` 와의 조인 조건이 없으므로 옵티마이저가 `r → jt → s` 순으로 놓을 수 있다. 그러면 `start_time` 으로 걸러질 행까지 파싱한다. `EXPLAIN FORMAT=TREE` 에서 `Materialize` / `Table scan on jt` 의 위치로 확인한다.

### 2-2. (b) JSON 파싱 — 행당 비용이 R 에 비례

`detailed_analysis` 는 InnoDB JSON 바이너리 포맷으로 저장된다. `JSON_TABLE` 은 행마다 문서 전체를 열어 경로를 따라간다. 비용은 **행 수 × 문서 크기**이고, 문서 크기는 R 에 선형이다(원소당 `repNumber`·`syncRate`·`timeStamp` ≈ 50~60B, 값 무관 — [`measure_json.sh`](../../loadtest/measure_json.sh) 와 같은 논리로 합성 데이터의 값 균일성이 이 비용에는 영향이 없다).

Q3 는 `$.worstSection.repNumber` **하나**만 뽑는데도 `JSON_TABLE` 을 쓴다. `JSON_EXTRACT`(`->>`) 와 비교하면 파싱은 같고 derived table 구성만 다르다 — 차이가 있는지 자체가 측정 항목.

인덱스는 여기서 **아무 역할이 없다.** JSON 컬럼은 보조 인덱스에 못 들어가고, multi-valued index(8.0.17+)는 `MEMBER OF`/`JSON_CONTAINS` 술어용이라 **집계에는 쓸 수 없다.**

### 2-3. (c) GROUP BY — temporary + filesort

`GROUP BY jt.rep_number` 는 derived table 위의 그룹화라 인덱스 순서를 못 쓴다. 입력 행 수 = W × R (Q2) 또는 W (Q3). W×R 이 수백이면 in-memory temporary 로 끝나지만 `Created_tmp_tables`·`Sort_rows` 카운터로 **확인**해야지 짐작하지 않는다.

### 2-4. 종합

| 자리 | 처방 | 인덱스로 되나 |
|---|---|:-:|
| (a) 조인 순서 | 쿼리 재작성 · 필터 컬럼 변경 · 힌트 | △ (필터 컬럼을 옮기면) |
| (b) JSON 파싱 | 스키마 변경(generated column · 정규화) 또는 사전집계 | ❌ |
| (c) GROUP BY | 입력 행 수를 줄이는 것 외엔 없음 | ❌ |

---

## 3. 후보안

### ㄱ. 그대로 두고 근거를 남긴다

측정해 보니 W=7·R=30 에서 Q2 가 수 ms 이고 스케일 곡선(§4)의 기울기가 완만하면, **"튜닝하지 않는다" 가 결론이고 그 근거가 산출물**이다. 관측 없이 고치는 것이 안티패턴이라는 서사를 실물로 갖게 된다.

- 읽기 비용: 현행. 쓰기 비용: 0. 스키마 변경: 없음.
- 단 (a) 에서 `r` 드라이빙으로 판명되면 이 안은 **탈락** — 팬아웃에 비례하는 읽기를 «괜찮다» 로 닫는 건 [[feedback_tps_over_dau_justification]] 위반이다(원인이 있는데 규모로 덮는 것).

### ㄴ. 조인 순서를 고정한다 — 필터를 `s` 쪽에 두거나 `session_reports` 에 시각 인덱스

두 갈래.

**ㄴ-1** `JOIN_ORDER` / `STRAIGHT_JOIN` 힌트로 `s` 드라이빙 강제. 스키마 무변경. 단 힌트는 옵티마이저 버전·통계 변화에 취약해 현업에서도 최후 수단이고, **"왜 옵티마이저가 틀렸나" 를 설명 못 하면 힌트는 증상 처치**다.

**ㄴ-2** `session_reports(member_id, created_at)` 인덱스를 얹고 WHERE 를 `r.created_at` 범위로 바꿔 **`exercise_sessions` 조인을 제거**. 읽는 행이 원리상 W 로 고정된다.
- 🔴 **의미가 바뀐다.** `created_at` 은 완료 시각, `start_time` 은 시작 시각. 일요일 23:50 에 시작해 월요일 00:05 에 끝난 세션은 A층(`start_time` 기준)에서는 지난 주, B층에서는 이번 주가 된다 — **A층과 B층의 주 경계가 어긋난다.** 세션 길이 분포(수 분)를 생각하면 경계에 걸리는 세션은 드물지만 "드물다" 로 닫을 수 없고, 어긋남을 허용할지는 사용자 결정.
- 대안: `session_reports` 에 `start_time` 을 **복제 저장**(비정규화)하고 `(member_id, start_time)` 인덱스. 의미는 보존되나 리포트 생성 시 컬럼 하나 더 쓰고, 세션 `start_time` 은 완료 후 불변이라 정합성 위험은 낮다.

### ㄷ. generated column — Q3 만 해결

```sql
ALTER TABLE session_reports
  ADD COLUMN worst_rep INT GENERATED ALWAYS AS
    (detailed_analysis->>'$.worstSection.repNumber') STORED;
```

Q3 가 `GROUP BY r.worst_rep` 로 바뀌고 JSON 파싱이 사라진다. `(member_id, worst_rep)` 인덱스까지 얹으면 커버링.
- **Q2 에는 안 통한다.** `repTrend` 는 배열이라 스칼라 generated column 으로 못 편다. Q2 가 더 무거운 쿼리(W×R 행)라 **반쪽 해법**.
- STORED 는 리포트 행 크기 +4B, VIRTUAL 은 읽을 때마다 파싱이라 여기선 STORED. STORED 추가는 테이블 재빌드다 — 무중단 DDL 축([[user_career_target]] 의 결손 항목)과 겹치는 실측 기회.
- 합성 데이터 한계: `worst_rep` 의 **선택도**는 값 분포에 달려 있는데 rig 는 값이 균일하다([[project_synthetic_data_distribution_limit]]). 인덱스 효과는 정직하게 «미측정» 으로 둬야 한다. 파싱 제거 효과만 잴 수 있다.

### ㄹ. rep 단위 정규화 표 — `session_rep_results(session_id, rep_number, sync_rate)`

세션 완료 트랜잭션에서 `detailed_analysis` 를 쓰는 김에 rep 행 R 개를 같이 INSERT. Q2 는 일반 조인+집계가 되고 `(session_id, rep_number)` PK 로 정렬까지 인덱스 순.
- 쓰기 증폭 **+R 행/세션**. DAU 1,000 × 일 1세션 × R=30 이면 일 3만 행 — `pose_data`(프레임 단위, 세션당 수백~수천 행) 옆에서는 작다. 하지만 «작다» 가 아니라 세션 완료 트랜잭션의 **커밋 지연 델타**를 재서 적어야 한다.
- 🔴 **`pose_data` 와 겹친다.** `pose_data` 를 `GROUP BY rep_number` 하면 같은 값이 나온다(`PoseDataRepository.java:111` 이 이미 그렇게 한다). precompute-on-write 를 택한 이유가 «조회 때 `pose_data` 를 안 읽으려고»([`SessionDetailedAnalysis.java`](../../backend/src/main/java/com/shadowfit/dto/report/detailreport/SessionDetailedAnalysis.java) javadoc)였는데, ㄹ 은 그 precompute 를 JSON 이 아니라 표로 하자는 것. 그러면 **JSON 의 `repTrend` 는 중복**이 된다 — JSON 을 남길지(세션 상세 응답이 통째로 쓰므로 남길 이유는 있다) 결정해야 한다.
- 이 안이 채택되면 «JSON 컬럼을 언제 정규화하나» 라는 일반론을 실측으로 답한 기록이 된다. 서사가 가장 크지만 스키마 변경도 가장 크다.

### ㅁ. 주간 사전집계 표 — `weekly_rep_curves(member_id, week_start, rep_number, sum_sync, cnt)`

세션 완료 아웃박스 이벤트(`SESSION_COMPLETED`, 이미 있음)를 구독해 증분 갱신(`sum += syncRate, cnt += 1`). 읽기는 PK 조회 R 행.
- 🔴 [`WeeklySummaryService`](../../backend/src/main/java/com/shadowfit/service/report/WeeklySummaryService.java) javadoc 이 **「저장하지 않는다 — 비싸지도 비결정적이지도 않다」** 를 명시적 결정으로 적어 뒀다. ㅁ 은 그 결정을 뒤집는 것이고, 뒤집을 근거는 **측정으로 «비싸다» 가 나왔을 때**만 성립한다. 지금 고르면 결정 번복이 아니라 결정 무시다.
- 주 경계·시간대·재계산(리포트 정정 시) 문제가 따라온다. 읽기 최적이지만 운영 복잡도 최대.

### 3-1. 비교표

| | ㄱ 그대로 | ㄴ-2 시각 인덱스 | ㄷ generated | ㄹ 정규화 표 | ㅁ 사전집계 |
|---|:-:|:-:|:-:|:-:|:-:|
| (a) 조인 순서 해결 | ❌ | ✅ | ❌ | ✅ | ✅ |
| (b) JSON 파싱 제거 | ❌ | ❌ | Q3 만 | ✅ | ✅ |
| (c) GROUP BY 입력 감소 | ❌ | ❌ | ❌ | ❌(인덱스 순 정렬은 됨) | ✅ |
| 쓰기 비용 | 0 | +컬럼 1 | +4B/행 | +R 행/세션 | +R 행/세션 + 아웃박스 소비 |
| 스키마 변경 | 없음 | 인덱스(+컬럼) | 컬럼+인덱스 | 표 신설 | 표 신설 |
| 의미 보존 | ✅ | `created_at` 이면 ❌ / 복제면 ✅ | ✅ | ✅ | 재계산 경로 필요 |
| 기존 결정과 충돌 | — | — | — | precompute 형식 | «저장 안 함» 결정 |
| 합성 데이터로 잴 수 있나 | ✅ | ✅ | 파싱 제거만 | ✅ | ✅ |
| 포폴 서사 | «관측 후 안 고쳤다» | «필터 위치가 조인 순서를 정한다» | 반쪽 | «JSON 을 언제 정규화하나» | 오버엔지니어링 위험 |

**추천(결정 아님)**: **측정을 먼저** 하고, (a) 가 `r` 드라이빙이면 ㄴ-2(복제 컬럼), (b) 가 지배적이면 ㄹ, 둘 다 미미하면 ㄱ. ㄷ 은 단독으로는 반쪽이라 ㄴ 의 보조로만, ㅁ 은 측정으로 «비싸다» 가 나오기 전엔 후보에서 뺀다.

---

## 4. 측정 설계 — 무엇을 어떤 순서로 재나

### 4-1. ① 관측 — 이 쿼리가 «문제» 인지부터

현업 순서는 slow log / `performance_schema.events_statements_summary_by_digest` 로 **총 소요 = 평균 × 호출 수** 순위를 매기는 것이다. 이 저장소는 실사용 트래픽이 없어 **이 단계를 건너뛰는 것을 정직하게 적는다.** 대신 `db-portfolio-roadmap.md` 의 합성볼륨 전제 위에서 «규모 가정(§1-4)에서 얼마나 되는가» 로 대체한다. AWS 라이드얼롱 R2(`R2_top_digest.txt`)가 digest 순위를 뽑는 장치이니, 앱 트래픽이 도는 무대가 생기면 그때 ① 을 채운다.

### 4-2. 데이터 — 변수 3개, 나머지 고정

| 변수 | 값 | 이유 |
|---|---|---|
| 팬아웃 F(회원당 리포트) | {7, 50, 365} | (a) 의 두 경로 차이가 F/W 배. 7 이면 두 경로가 같아 차이가 안 보이고 365 가 1년차 상한 |
| 세션당 rep R | {10, 30, 100} | (b) 의 선형 계수. 실사용 분포 미측정이라 자릿수로 훑는다 |
| 주간 세션 W | {3, 7} | §1-4 가정 범위. 주 안의 행 수 |

총 행수는 F 에 따라 회원 수를 줄여 고정한다([`session-index-composition.md`](./session-index-composition.md) §2 와 같은 수법 — «표가 커져서» 를 변수에서 뺀다). `detailed_analysis` 는 R 원소짜리 템플릿을 복제 — 값 균일이 (b) 비용에 무관한 이유는 §2-2.

### 4-3. 지표 — 견적과 사실을 분리

| 층 | 지표 | 답하는 질문 |
|---|---|---|
| 계획 | `EXPLAIN FORMAT=TREE` · `FORMAT=JSON` 의 조인 순서·`Materialize` 위치 | (a) 어느 표를 먼저 읽나, `JSON_TABLE` 이 필터 전인가 후인가 |
| 사실 | `EXPLAIN ANALYZE` 의 actual rows / actual time per node | 견적과 실제의 괴리(38배 전과) |
| 사실 | `Handler_read_key/next/rnd_next` 델타 · `Created_tmp_tables` · `Sort_rows` | (a)·(c) 를 카운터로 |
| 시간 | 쿼리 실행시간, F·R·W 격자 각 셀 **N판**(버림판 1 + 라틴 방격 순서) | 기울기 — 어느 변수에 비례하나 |

절대 시간에는 [[project_nonrepro_axis_b_closed]] 규칙대로 calib cpu 를 병기한다. 이 박스의 절대값은 못 믿고 **셀 간 델타와 기울기**만 읽는다([[project_loadtest_env_constraint]]).

### 4-4. 판정 — 임계값 없이

「몇 ms 넘으면 튜닝」 같은 기준은 없다([[feedback_no_arbitrary_threshold_values]]). 판정은 다음 **사실 질문**의 답으로 한다.

1. (a) 드라이빙이 `r` 인가 `s` 인가. `r` 이면 F 축 기울기가 양수로 나올 것이고, 그 자체가 결함이다(주간 조회가 누적에 비례).
2. (b) R 축 기울기 — Q2 시간이 R 에 선형이면 파싱이 지배. W×R 이 같은 셀끼리 비교해 «행 수» 와 «문서 크기» 를 분리.
3. 후보 적용 후 같은 격자에서 **before/after 델타**. 어느 후보가 어느 기울기를 죽이는지.

### 4-5. 후보 적용 순서

ㄱ(현행 측정) → ㄴ-1(힌트로 `s` 드라이빙 강제 — 스키마 무변경이라 (a) 의 **상한**을 빨리 본다) → ㄷ(Q3 파싱 제거 델타) → ㄹ(Q2 파싱 제거 델타 + 세션 완료 커밋 지연 델타). ㅁ 은 ㄹ 결과를 본 뒤.

측정 스크립트는 [`loadtest/measure_weekly_json_table.sh`](../../loadtest/measure_weekly_json_table.sh) — `measure_index_overlap.sh` 의 골격(스크래치 DB·자기검증·EXPLAIN ANALYZE 최소값·Handler 델타)을 재사용한다. 장치 쪽에서 정한 것:

| 항목 | 값 | 이유 |
|---|---|---|
| 격자 | 축 A = F×W(R=30 고정) 6셀 + 축 B = R(F=50·W=7 고정) 3셀, 겹침 제외 8셀 | 3변수 풀격자 18셀은 JSON 표 용량(R=100 이면 셀당 ~550MB)이 감당 안 됨. (a)·(b) 는 축이 달라 분리 측정이 원리상 가능 |
| 셀당 행수 | 10만(env `ROWS`) | 쿼리가 만지는 행은 F 또는 W 뿐이라 총 행수는 인덱스 깊이·버퍼풀 점유만 바꾼다 |
| 주간 배치 | 대상 주(2025-10-01~08)에 W 건 등분, 나머지 F−W 건은 그 주 **이전** 52주에 해시로 분산 | W 를 F 와 독립으로 통제하려면 «주 안» 과 «주 밖» 을 명시적으로 나눠야 한다 |
| 인덱스 | `exercise_sessions` 4종 그대로, `session_reports` PK+uk+(member_id) | 현행을 재는 것이므로 |
| 반복 | 버림판 1 + 7회, 최소값 신호, 셀마다 쿼리 순서 1칸씩 회전(라틴 방격) | [[feedback_measure_design_needs_repeats]] |
| 후보 측정 | 축 B 3셀에서만 ㄷ(STORED generated + 인덱스, ALTER 시간 기록)·ㄹ(정규화 표, 적재 시간·크기 기록) before/after. ㄴ-1 은 STRAIGHT_JOIN 변형으로 전 셀 | ㄹ 은 행수 = ROWS×R 이라 전 셀에 깔면 용량 초과 |

---

## 5. 사용자가 결정할 것

| # | 질문 | 추천 | 비고 |
|---|---|---|---|
| 1 | 측정을 먼저 하나, 후보를 먼저 고르나 | **측정 먼저** | §13-2 원문 「EXPLAIN 선행」 과 일치. ㄱ 이 결론일 수 있다 |
| 2 | ㄴ 을 갈 때 `created_at`(의미 어긋남 허용) vs `start_time` 복제 | 복제 | A층·B층 주 경계가 다르면 리포트 문장이 자기모순 |
| 3 | ㄹ 을 갈 때 JSON `repTrend` 를 남기나 지우나 | 남긴다(세션 상세 응답이 통째로 씀) | 지우면 세션 상세 읽기 경로도 바뀐다 — 범위 확장 |
| 4 | 이걸 포폴 카드로 올리나 | 측정 결과 본 뒤 | §13-2: 「계획을 재보기 전에는 카드라고 부르지 않는다」 |

결정 1 은 확정됐다(2026-09-14). 앱 코드는 결정 2~4 전까지 건드리지 않는다.

---

## 6. 사실 / 미검증 목록

**사실**
- Q2·Q3 는 `GET /reports/weekly-summary` 요청당 최대 1회씩, A층이 비어 있으면 호출 안 됨 (`WeeklySummaryService.java`)
- `session_reports` 에 `(member_id)` FK 인덱스와 `session_id` UNIQUE 만 있음. `created_at` 인덱스 없음 (`V1__baseline.sql`)
- `detailed_analysis` 의 `repTrend` 원소 수 = 측정된 rep 수, 원소당 3필드 (`RepSyncRateDto.java`)
- 이 쿼리에 대한 EXPLAIN·시간 실측 기록 없음 (AWS R1 은 0행 무대)
- `WeeklySummaryService` 가 「저장하지 않는다」 를 명시적 결정으로 적어 둠

**미검증**
- 옵티마이저의 드라이빙 표 선택 (§2-1)
- `JSON_TABLE` 이 `s` 필터 전에 펼쳐지는지 (§2-1)
- Q3 의 `JSON_TABLE` vs `->>` 비용 차이 (§2-2)
- 세션당 rep 수 R 의 실사용 분포 (§1-4)
- 주 경계에 걸치는 세션의 실제 빈도 (ㄴ-2)
- ㄹ 의 세션 완료 커밋 지연 델타 (§3 ㄹ)

---

## 7. 측정 결과 (2026-09-14 ~ 15, 로컬 rig)

### 7-0. 한 줄 요약

**비용은 (b) JSON 파싱이 아니라 (a) 조인 순서였다.** 옵티마이저는 8셀 전부 `session_reports` 를 먼저 읽어 회원의 **전 기간** 리포트 F 행을 페치했고(주간 조회가 누적 이력에 비례), Q3 처럼 스칼라 하나만 뽑는 쿼리도 Q2 와 같은 비용이 들었다(파싱이 아니라 행 페치가 비용이라는 증거). `s.status = 'COMPLETED'` 술어 하나를 보태면(ㄴ-3, 스키마·힌트 없음) 옵티마이저가 스스로 `(member_id, status, start_time)` 을 **완전 범위**로 타서 W 행만 읽고, 읽는 양이 F 와 무관해진다 — F=365 에서 시간 **12분의 1**, Handler 945→221. 파싱(b)은 R=100 에서만 보이고, GROUP BY(c)는 무시할 수준.

### 7-1. 환경과 읽기 주의

- MySQL 8.0.46(docker `shadowfit-mysql`), 버퍼풀 2GB. i3-6100 2코어 박스에 backend·ai·nginx·dockin·postgres 컨테이너가 **같이 떠 있는 채로** 쟀다 — 중앙값이 최소값의 2~4배인 셀이 있다(F=365/W=3 Q3 cur: min 2.9 / med 13.3). **최소값만 신호**, 비율만 읽는다. calib cpu 는 이 라운드에 안 쟀으므로 [[project_nonrepro_axis_b_closed]] 규칙상 **절대 ms 는 인용 불가**.
- 셀당 10만 행, 회원 수 = 10만/F. 시딩 자기검증 8셀 전부 통과(F·W·R·JSON 크기 721/1902/6033B). 「주간전체행」(전 회원의 그 주 세션) = 819~99,995.
- 스모크(5천 행)에서 F=365 셀이 s 드라이빙으로 나온 것은 **회원 13명의 산물**이었다(주간전체행 39~91 < F). 본 측정(회원 273명, 주간전체행 819~1,911)에서는 r 드라이빙으로 돌아왔다 — 옵티마이저 선택이 F 뿐 아니라 «전 회원 주간 행수» 에도 달려 있다는 뜻이고, 실서비스(DAU×W ≫ F)에서는 r 드라이빙이다.

### 7-2. (a) 조인 순서 — 축 A (R=30)

Q2 현행 vs `STRAIGHT_JOIN` 으로 s 를 먼저 읽게 한 것(ㄴ-1). min(ms) / Handler_read 합.

| F | W | Q2 cur (r 먼저) | Q2 s-first (ㄴ-1) | Handler 차 | = F−W |
|--:|--:|--:|--:|--:|--:|
| 7 | 3 | 0.334 / 229 | 0.267 / 225 | 4 | 4 ✅ |
| 7 | 7 | 0.397 / 473 | 0.487 / 473 | 0 | 0 ✅ |
| 50 | 3 | 0.704 / 315 | 0.187 / 268 | 47 | 47 ✅ |
| 50 | 7 | 1.09 / 559 | 0.462 / 516 | 43 | 43 ✅ |
| 365 | 3 | **3.06** / 945 | 0.482 / 583 | 362 | 362 ✅ |
| 365 | 7 | **2.53** / 1189 | 0.802 / 831 | 358 | 358 ✅ |

- 8셀 전부 EXPLAIN 첫 표가 `r(member_id)`, TREE 는 `Index lookup on r using member_id → PK lookup on s → Filter(start_time)`. **Handler 차이가 정확히 F−W** — r 드라이빙은 F 행을 페치한 뒤 F 번 s 를 찾고, 그중 F−W 를 버린다.
- F 축 기울기(W=3): r 먼저 ≈ **7.6µs/행**(1.9KB JSON 행 페치 + PK 조회), s 먼저 ≈ 0.6µs/행(커버링 인덱스 엔트리). **둘 다 F 에 선형**이다 — ㄴ-1 도 W 만 읽는 게 아니라 F 엔트리를 훑고 있었다. 이유는 §7-5 ㄴ-3.
- `JSON_TABLE` 은 8셀 모두 `s.start_time` 필터 **바깥** 루프에 `Materialize table function` 으로 붙었다 → 파싱 횟수는 W. §2-1 의 «필터 전 파싱» 우려는 **기각**.

### 7-3. (b) JSON 파싱 — 축 B (F=50, W=7)

| R | JSON(B) | Q2 cur min(ms) [3/5] · [4/5] | Q2 s-first | Handler(cur) |
|--:|--:|--|--:|--:|
| 10 | 721 | 0.574 · 0.643 | 0.276 | 259 |
| 30 | 1902 | 1.09 · 0.583 | 0.462 | 559 |
| 100 | 6033 | 1.77 · 1.13 | 1.16 | 1609 |

- 같은 셀을 두 구간에서 잰 값이 0.58~1.09 로 흔들린다 — 동거 노이즈. 두 값의 최소로 보면 R=10→30 은 차이 없고 R=30→100 에서 +0.5ms 가 난다. **R=100 은 돼야 파싱이 보인다.** 이 크기에서 원소당 ≈ 0.9µs.
- Handler 는 R 에 비례(W×R 의 약 2배 — 물질화 표 스캔 + 임시 표). GROUP BY 는 Sort_rows = R, tmp 2 — 전 셀 동일하고 시간에 안 보인다. **(c) 는 없다.**

### 7-4. Q3 — `JSON_TABLE` vs `->>`

| 셀 | Q3 JSON_TABLE | Q3 `->>` |
|---|--:|--:|
| F=7/W=3 | 0.228 | 0.123 |
| F=50/W=7 | 0.586 | 0.984 |
| F=365/W=3 | 2.90 | 3.21 |
| F=50/W=7/R=100 | 0.701 | 0.749 |

방향이 셀마다 뒤집힌다 — **차이 없음**(tmp 2→1 만 일관). 그리고 **Q3 가 F=365 에서 2.9ms 로 Q2(3.06)와 같다.** Q3 는 스칼라 하나만 뽑는데도 Q2 만큼 든다 → 비용은 파싱이 아니라 F 행 페치. (a) 의 독립 증거.

### 7-5. 후보안 before/after

**ㄷ generated column** (F=50/W=7, `worst_rep` STORED + `(member_id, worst_rep)`)

| R | Q3 before | Q3 after | Handler | tmp | ALTER 시간 (표 크기) |
|--:|--:|--:|--|--|--|
| 10 | 0.448 | 0.412 | 127→101 | 2→0 | 43.8s (103MB) |
| 30 | 0.485 | 0.477 | 130→101 | 2→0 | 118s (271MB) |
| 100 | 0.491 | 0.452 | 130→101 | 2→0 | 353s (793MB) |

- 시간 델타 ≤8% — 노이즈 안. 파싱은 없앴지만 여전히 `r(idx_member_worst)` 로 **F 엔트리를 읽고 F 번 s 를 조회**한다. (a) 가 남아 있으니 (b) 를 없애도 안 보인다. **탈락.**
- 부산물: STORED 컬럼 추가 = 전체 재빌드, **≈0.44 s/MB** 로 표 크기에 선형. 무중단 DDL 축의 첫 실측 수치(절대값은 이 박스의 것). VIRTUAL 로 하면 INPLACE 가 되는지는 미측정.

**ㄹ rep 단위 정규화 표** (F=50/W=7, `(session_id, rep_number)` PK)

| R | Q2 before | Q2 after | 델타 | Handler | 정규화 표 / JSON 표 크기 | 백필 적재 |
|--:|--:|--:|--:|--|--|--|
| 10 | 0.643 | 0.258 | −60% | 259→209 | 34.6 / 103MB | 1M 행 58s |
| 30 | 0.583 | 0.222 | −62% | 559→509 | 104 / 271MB | 3M 행 134s |
| 100 | 1.13 | 0.626 | −45% | 1609→1559 | 345 / 794MB | 10M 행 424s |

- 효과는 있는데 **Handler 가 정확히 F(=50)만 준다** — 없어진 건 r 의 F 행 페치이고, after 는 `s(idx_session_member_status_start)` 프리픽스로 F 엔트리를 훑은 뒤 PK 로 W×R 행을 읽는다. 즉 ㄹ 의 이득 대부분도 **(a) 가 사라져서** 생긴 것이다. R=100 에서만 (b) 몫(파싱 vs 정규 행 읽기)이 더해진다.
- 정규화 표가 JSON 의 **1/3 크기** — JSON 은 원소마다 키 이름과 `timeStamp` 문자열을 반복 저장한다.

**ㄴ-3 술어 추가** (힌트·스키마 변경 없음) — `WHERE s.member_id = ? AND s.status = 'COMPLETED' AND s.start_time …`

1차 말미에 3셀 ad-hoc 으로 본 뒤, 스크립트 정식 쿼리 목록에 넣고 **새 스크래치 DB 에서 8셀 재실행**(2차, 2026-09-15). min(ms) / Handler.

| 셀 | Q2 cur | **Q2 +status** | 배 | Q3 cur | **Q3 +status** | 배 |
|---|--:|--:|--:|--:|--:|--:|
| F=7/W=3 | 0.193 / 229 | 0.149 / **221** | 1.3 | 0.116 / 28 | 0.083 / **20** | 1.4 |
| F=7/W=7 | 0.258 / 473 | 0.423 / **473** | 0.6 | 0.133 / 43 | 0.130 / **43** | 1.0 |
| F=50/W=3 | 0.406 / 315 | 0.177 / **221** | 2.3 | 0.399 / 114 | 0.088 / **20** | 4.5 |
| F=50/W=7 | 0.501 / 559 | 0.270 / **473** | 1.9 | 0.391 / 130 | 0.134 / **44** | 2.9 |
| F=365/W=3 | 1.55 / 945 | 0.137 / **221** | **11.3** | 1.39 / 744 | 0.082 / **20** | **17** |
| F=365/W=7 | 2.11 / 1189 | 0.249 / **473** | **8.5** | 1.39 / 760 | 0.135 / **44** | **10** |
| F=50/W=7/R=10 | 0.514 / 259 | 0.156 / **173** | 3.3 | 0.293 / 127 | 0.114 / **41** | 2.6 |
| F=50/W=7/R=100 | 1.03 / 1609 | 0.557 / **1523** | 1.8 | 0.494 / 130 | 0.143 / **44** | 3.5 |

- **Handler 가 F 와 무관해졌다** — W=3 이면 221/20, W=7 이면 473/44 로 F=7·50·365 에서 **같은 값**. TREE(F=365/W=3): `Covering index range scan on s using idx_session_member_status_start over (member_id = 1 AND status = 'COMPLETED' AND start_time 범위) rows=3` → `uk_report_session` 단건 조회 W 번 → 물질화.
- Q2 +status 는 R 축에서 s-first 와 같다(R=100 에서 0.557 vs 0.586) — (a) 를 없애고 남은 것이 (b) 이고, 그건 R=100 에서만 보인다(§7-3).
- F=7/W=7 셀은 옵티마이저가 `idx_session_member_exercise_status_start` 프리픽스를 골랐다(두 인덱스의 행 견적이 7 로 같아 임의 선택). F=W 라 읽는 양은 같고 결과에 영향 없지만, **선택이 통계에 달려 있다**는 사실은 남긴다.
- 왜 이게 되나: 현행 인덱스 `(member_id, status, start_time)` 은 [`session-index-composition.md`](./session-index-composition.md) ㄴ안으로 `(member_id, start_time)` 을 **흡수**한 것이다. status 등치가 빠지면 `start_time` 범위를 못 타고 member_id 프리픽스만 써서 F 엔트리를 다 훑는다 — ㄴ-1 이 F 에 선형이었던 이유. status 를 넣으면 세 컬럼이 전부 걸려 W 엔트리로 끝난다.
- 의미 보존: 리포트는 완료 세션에만 생기므로(`SessionCompletionTx`) `status='COMPLETED'` 는 결과를 바꾸지 않는 **중복 술어**다. 코드 주석이 "세션 상태를 따로 거르지 않는다" 고 적어 둔 바로 그 중복이 인덱스를 여는 열쇠였다. A층 `totalsBetween` 도 같은 술어를 쓰므로 두 층의 행 집합 정의가 오히려 일치한다 — `WeeklySummaryBLayerRaceTest.미완료_세션은_리포트가_있어도_제외` 가 이를 값으로 못박는다.
- 1차와 2차의 절대값이 2배쯤 다르다(F=365/W=3 Q2 cur 3.06 → 1.55) — 1차는 Gradle·다른 컨테이너가 동거했다. **같은 실행 안의 비율만 읽는다**는 규칙이 여기서도 필요했다.

### 7-6. §4-4 세 질문의 답

| # | 질문 | 답 |
|---|---|---|
| 1 | 드라이빙이 r 인가 s 인가 | **r**, 8셀 전부. F 축 기울기 양수(7.6µs/행). 주간 조회가 누적 이력에 비례 — §3 ㄱ «그대로 두기» 는 **탈락** |
| 2 | R 축 기울기 | R=100 에서만 +0.5ms. R=10~30 구간은 노이즈 안. 실사용 R 분포 미측정이라 (b) 가 «실제로» 문제인지는 열려 있음 |
| 3 | 후보별 델타 | ㄷ ≈0(탈락) · ㄹ −45~62%(대부분 (a) 몫) · **ㄴ-3 8.5~11×(F=365), Handler F 무관** |

### 7-7. 판정과 결정 (2026-09-15 사용자 confirm)

| # | 질문 | 추천 | 근거 |
|---|---|---|---|
| 2′ | ㄴ-3(`s.status='COMPLETED'` + `s.member_id`) 를 채택하나 | **✅ 채택** | 코드 두 줄, 스키마·힌트 없음, 의미 보존, F 무관. ㄴ-2(created_at·복제 컬럼)는 필요 없어짐 |
| 3′ | ㄹ 정규화 표는 | **보류**(확정) — 실사용 R 분포를 본 뒤 | ㄹ 의 이득 대부분이 (a) 몫이라 ㄴ-3 뒤엔 R=100 급에서만 남는다. `total_reps` 실측 한 줄이 선행 |
| 4′ | ㄷ·ㅁ 은 | **탈락**(확정) | ㄷ 은 (a) 가 남아 효과 없음, ㅁ 은 «비싸다» 가 안 나왔다 |
| 5′ | 포폴 카드로 올리나 | 미결 — 사용자 판단 | «JSON_TABLE 이 느린 줄 알았는데 조인 순서였고, 중복 술어 하나가 인덱스를 열었다» — EXPLAIN 선행 조건(§13-2) 충족 |

반영: `WeeklySummaryQueryRepositoryImpl.repCurveBetween` · `worstRepDistributionBetween` 의 WHERE 를 `s.member_id = :memberId AND s.status = 'COMPLETED' AND s.start_time …` 로 바꿨다(javadoc 에 이유 명기). `WeeklySummaryBLayerRaceTest` 에 미완료 세션 제외 케이스 추가, race(실 MySQL)·H2 테스트 전부 통과. 실행 계획의 근거는 테스트가 아니라 이 스크립트다.

### 7-8. 이 라운드가 못 답한 것

- calib cpu 미측정 → 절대 ms 인용 불가. 인용하려면 §8 규칙대로 calib 병기해 재측정(AWS 무대).
- 실사용 R 분포(`SELECT AVG(total_reps), MAX(total_reps)`) — 결정 3′ 의 선행.
- VIRTUAL generated column + 보조 인덱스가 INPLACE 로 되는지(무중단 DDL 축) — ㄷ 탈락으로 이 쿼리엔 불필요하지만 DDL 축 자체는 열려 있음.
- ① 관측(digest 순위) — 실트래픽 무대 필요, 변함없음.
