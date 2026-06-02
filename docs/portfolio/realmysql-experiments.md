# 포폴: RealMySQL 8.0 (1·2권) → 이 프로젝트 적용 실험 패키지

작성: 2026-06-02
대상: DB를 **현업/RealMySQL 1·2권 수준**으로 파기. 책 챕터를 이 프로젝트 데이터(시계열 `pose_data` · JSON · gRPC 적재 · 집계)에 **구체 실험**으로 매핑.
연관: [`./db-deep-dive.md`](./db-deep-dive.md), [`./problem-solving-log.md`](./problem-solving-log.md), [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md), [`../tasks/26-vacation-semester2-portfolio-plan.md`](../tasks/26-vacation-semester2-portfolio-plan.md)

> ⬜=실험 미실시(설계만). 각 카드는 **4단**(책 개념/가설/설계/결과+회고)으로 채워나감. substrate 강도 🟢진짜 / 🟡부분·합성 / 🔴없음.
> 장 번호는 RealMySQL 8.0 개정판 기준 (판본 따라 ±1).

---

## 0. 포지셔닝 — 왜 이게 "현업 수준"으로 보이나

**클로드 시대엔 실험 코드가 아니라 "측정·발견·판단"이 차별점**([`25-doc §36`](../tasks/25-portfolio-strategy.md)). RealMySQL 실험의 가치는 "돌렸다"가 아니라:
- **가설 → 측정 → 발견(가설과 다름) → 회고**의 지적 작업 (AI가 못 하는 20%)
- `EXPLAIN ANALYZE`·`performance_schema`로 **증거 기반** (정량)
- "이건 이 프로젝트에 안 맞아서 안 했다"는 **substrate 정직성** (복제 등)

→ 각 실험 = STAR 면접 답변 그대로 (상황/과제/행동/결과).

---

## 1. 이 프로젝트 데이터 substrate (왜 RealMySQL에 적합)

| 특성 | 해당 | RealMySQL 연결 |
|---|---|---|
| 시계열 대용량 | `pose_data` (연 ~8억 행 가능, §4.3) | Ch.8·10·13 |
| JSON 컬럼 | `joint_coordinates` 1~3KB | Ch.15·11(projection) |
| 이벤트 적재 정합성 | gRPC 콜백, `@Version`, INSERT IGNORE | Ch.5 |
| cross-session 집계 | admin 통계·패턴 분석 | Ch.9·10·13(pruning) |
| 동기 쓰기 부하 | `SavePoseDataBatch` + ghz | Ch.4(버퍼풀)·18 |

---

## 2. 장별 적합도 맵

| 권 | 장 | 주제 | substrate | 우선 |
|---|---|---|---|---|
| 1 | Ch.4 | 아키텍처(InnoDB·버퍼풀·MVCC) | 🟢🟡 | ④ |
| 1 | Ch.5 | 트랜잭션·잠금·격리수준 | 🟢 | ③ |
| 1 | Ch.6·7 | 압축·암호화 | 🟡 | 선택 |
| 1 | Ch.8 | 인덱스 | 🟢 | ① |
| 1 | Ch.9 | 옵티마이저·힌트 | 🟢 | ⑤ |
| 1 | Ch.10 | 실행 계획(EXPLAIN) | 🟢 | ① |
| 2 | Ch.11 | 쿼리 작성·최적화 | 🟢 | ② |
| 2 | Ch.12 | 전문·공간 검색 | 🔴 | N/A |
| 2 | Ch.13 | 파티션 | 🟢 | ② |
| 2 | Ch.14 | 스토어드 프로그램 | 🟡 | 시딩 도구 |
| 2 | Ch.15 | 데이터 타입(JSON) | 🟢 | ④ |
| 2 | Ch.16·17 | 복제·InnoDB 클러스터 | 🔴 | 개념만 |
| 2 | Ch.18 | Performance Schema·sys | 🟢 | ⓪ 백본 |

---

## 3. 공통 선결 — 1억 row 합성 시딩

모든 대용량 실험의 전제. **"DAU 1,000 시뮬레이션 위해 합성"이라고 명시**.
- 방법: `INSERT ... SELECT` 자가증식(최속) — 단 세션·멤버를 numbers 테이블 cross join으로 먼저 N개 깔아 **session_id·날짜 분산**(cross-session 쿼리 현실성).
- 목표: pose_data ~1억 행, 월 분산(파티션·pruning 실험용).
- 스크립트 위치(예정): `loadtest/seed/` (gitignore 결과).

---

## 4. 실험 카드 (우선순위 순)

### ⓪ Ch.18 — Performance Schema (측정 백본) 🟢
- **개념**: `performance_schema`·`sys` 스키마로 쿼리/락/I-O 관측.
- **가설**: 모든 후속 실험의 "증거"는 추측이 아니라 PS 지표로 댄다.
- **설계**: `events_statements_summary_by_digest`(쿼리 다이제스트), `data_locks`/`data_lock_waits`(락 대기), `table_io_waits_summary`(테이블 I-O), `file_summary`(디스크). sys: `sys.statement_analysis`, `sys.schema_index_statistics`.
- **지표**: 느린 쿼리 digest, 락 대기 건수, 버퍼풀 hit율.
- **결과**: ⬜
- **면접**: "감으로 '느리다'가 아니라 PS digest로 범인 쿼리 특정."

### ① Ch.8 + Ch.10 — 인덱스 & 실행계획 🟢
- **개념**: 클러스터링 인덱스, covering index, 카디널리티 / `EXPLAIN` type·key·rows·Extra.
- **이 프로젝트**: `pose_data.idx_session_timestamp(session_id, timestamp_sec)`.
- **가설(반증된)**: "리포트 쿼리가 인덱스 없어 풀스캔" → **측정으로 폐기**: 이미 covering(lookup+정렬 한 인덱스, filesort 없음), 세션 단위라 테이블 성장 무관(§4.3).
- **설계**: 리포트 쿼리 `EXPLAIN ANALYZE`(covering 확인, `Using index`) → `IGNORE INDEX`로 풀스캔 강제 비교 → 복합 인덱스 컬럼 순서 (session_id, timestamp_sec) vs 역순 EXPLAIN 차이 → 1억 행에서 B-Tree 깊이·인덱스 크기(`information_schema`).
- **결과**: ⬜ (헤드라인은 "인덱스 추가"가 아니라 **"이미 최적임을 측정으로 발견"** — 카고컬트 반대 시그널)
- **면접**: "인덱스 넣기 전에 EXPLAIN으로 이미 최적임을 확인 → 진짜 병목은 payload(Ch.11 projection)."

### ② Ch.11 + Ch.13 — 쿼리 최적화 & 파티션 🟢⭐
- **개념**: bulk INSERT, 페이지네이션(offset→cursor), projection / Range 파티션·프루닝.
- **이 프로젝트 (a) 쓰기**: ✅ batch insert 완료 — JdbcTemplate `batchUpdate`, throughput **+99%**, p99 −37% (§7.6). Ch.11 bulk insert 그 자체.
- **이 프로젝트 (b) projection**: `ReportService` JSON blob 헛로드(~3MB) → 3컬럼 DTO. 🔶
- **이 프로젝트 (c) 페이지네이션**: 세션 목록·pose 조회 offset → keyset(cursor). 대용량에서 offset 깊을수록 저하.
- **이 프로젝트 (d) 파티션**: `pose_data` 월 Range 파티션 → cross-session 시간범위 쿼리 **pruning** + **DROP PARTITION TTL**.
- **설계**: (b) projection before/after payload·응답 / (c) offset N=10만 vs cursor 응답 곡선 / (d) 파티션 후 `EXPLAIN`의 `partitions` 컬럼 pruning 확인 + **DROP PARTITION vs DELETE WHERE 실측 시간**(O(1) vs 락·undo).
- **지표**: payload(MB), 응답(ms), pruning 파티션 수, DROP vs DELETE(ms).
- **결과**: ⬜
- **면접**: "세션 리포트는 안 느려지지만(§4.3), cross-session 집계는 파티션 프루닝으로 개선. TTL은 DELETE 아니라 DROP PARTITION."

### ③ Ch.5 — 트랜잭션·잠금·격리수준 🟢
- **개념**: 격리수준(RC/RR), 레코드/갭/넥스트키 락, 낙관적 vs 비관적.
- **이 프로젝트 (a) 낙관락**: ✅ 구현 — 타임아웃 스케줄러 vs FastAPI 콜백(`Session.java:65 @Version`, `SessionTimeoutScheduler.java:84` 양보).
- **이 프로젝트 (b) lost-update**: `DailyLog.updateStats()` read-modify-write 배선 시 동시 종료 경합 🔶.
- **이 프로젝트 (c) 멱등성**: ✅ INSERT IGNORE (`FeedbackLogService.java:33`).
- **설계**: (b) 두 트랜잭션으로 lost-update **재현**(RC) → 원자 UPDATE / `SELECT FOR UPDATE` / `@Version` 비교, `performance_schema.data_locks`로 락 관찰, `SHOW ENGINE INNODB STATUS` → (a) 낙관락 충돌 시 양보 정책 근거.
- **지표**: 손실 갱신 발생/방지, 락 종류·대기.
- **결과**: ⬜
- **면접**: "RC로는 lost-update 못 막음 → 원자 UPDATE 선택. 타임아웃 vs 콜백은 저경합·읽기위주라 낙관락(비관락 블로킹 비용 회피)." 🟡 갭/넥스트키 락은 저경합이라 일부 합성 — 명시.

### ④ Ch.4 + Ch.15 — 아키텍처(MVCC·버퍼풀) & JSON 🟢🟡
- **개념**: InnoDB 버퍼풀, undo, MVCC 스냅샷 / JSON 저장·함수·generated column 인덱스.
- **설계 (MVCC)**: 긴 batch insert 트랜잭션 open 중 동시 리포트 조회가 **이전 스냅샷** 읽음(RR) 관찰 — gRPC 적재↔조회 사이 일관성.
- **설계 (버퍼풀)**: 1억 행 > `innodb_buffer_pool_size`면 디스크 읽기 급증 — hit율(`sys`) 관찰, 버퍼풀 사이징 영향.
- **설계 (JSON)**: `joint_coordinates` 저장 비용 측정, JSON path에 **generated column + 인덱스** vs 정규화 테이블 트레이드오프. 🟡 (현재 불필요, 데모용).
- **결과**: ⬜
- **면접**: "MVCC 덕에 적재 트랜잭션이 조회를 블로킹 안 함. 버퍼풀 넘는 데이터에서 hit율 저하 관찰."

### ⑤ Ch.9 — 옵티마이저·힌트 🟢
- **개념**: 통계정보, 비용기반 최적화, 조인 알고리즘(8.0 hash join), 힌트.
- **이 프로젝트**: cross-session 집계(admin 통계·패턴 분석) — `reports ⋈ sessions ⋈ users`.
- **설계**: `ANALYZE TABLE`로 통계 갱신 전후 plan 변화, 옵티마이저의 인덱스 선택/오선택, `STRAIGHT_JOIN`·index 힌트로 교정, nested loop vs hash join 관찰.
- **결과**: ⬜
- **면접**: "옵티마이저가 통계 부정확으로 잘못된 인덱스 선택 → ANALYZE/힌트로 교정."

### 선택 — Ch.6 압축 🟡
- `pose_data` JSON 시계열은 압축률 높음 → InnoDB page compression 적용 시 스토리지·I-O 트레이드오프 측정. niche, 여유 시.

---

## 5. 정직 캐비엇 (오버셀 금지)

| 항목 | 캐비엇 |
|---|---|
| Ch.16·17 복제·클러스터 | 🔴 **substrate 없음** — 규모상 실수요 0. "개념 학습 + 왜 미적용인지"로만. 가짜 replication 데모 금지 |
| Ch.5 갭/넥스트키 락 | 🟡 append-only 저경합이라 일부 **합성 구간** — 명시 |
| Ch.8 인덱스 | "추가로 빨라짐" ❌ → "**이미 최적임을 측정으로 발견**"이 정직한 헤드라인(§4.3) |
| 대용량 쿼리 저하 | 세션 리포트는 안 느려짐. **cross-session 집계**에서만 성립 |
| throughput | DAU 작음(§4.2) — 처리량 자랑 ❌, 데이터량·정합성 축 |

---

## 6. 우선순위 · 일정 (방학 배치, [`26-plan`](../tasks/26-vacation-semester2-portfolio-plan.md))

| 순서 | 실험 | 시점 |
|---|---|---|
| ⓪ | Ch.18 PS 셋업 (측정 백본) | 6월 초 |
| ① | Ch.8+10 인덱스·EXPLAIN | 6월 |
| ② | Ch.11+13 projection·파티션·DROP PARTITION | 6~7월 |
| ③ | Ch.5 lost-update·격리·락 | 7월 |
| ④⑤ | Ch.4·15 MVCC·JSON / Ch.9 옵티마이저 | 7~8월 |

> 선결: 1억 row 합성 시딩(§3) 먼저.

---

## 7. 관련 문서
- [`./db-deep-dive.md`](./db-deep-dive.md) — DB 깊이 (시계열·정합성·격리수준)
- [`./problem-solving-log.md`](./problem-solving-log.md) — 문제해결 카드
- [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md) — 부하 측정·R·천장
- [`../tasks/26-vacation-semester2-portfolio-plan.md`](../tasks/26-vacation-semester2-portfolio-plan.md) — 방학~2학기 배분
