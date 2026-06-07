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

## 3. 공통 선결 — 1억 row 합성 시딩 ✅ (2026-06-03 완료)

모든 대용량 실험의 전제. **"DAU 1,000 시뮬레이션 위해 합성"이라고 명시**. 결과: `pose_data_scale` **정확히 1억 행** (133,334 세션×750행, 2026년 12개월+ 분산), ~11GB.
- 방법: `_seq`(numbers 0~99만, digit cross join) × `_seq` cross join으로 session_id·날짜 분산. **더미 JSON `{}`** — 행수·payload 디커플링(§0.3): 실제 2.3KB JSON이면 1억=255GB라 로컬 불가, 더미로 ~11GB.
- 가속 교훈(실측): ① **버퍼풀 128MB→2GB** 필수(기본값은 롤백 64행/s, 2GB는 1만행/s). ② **인덱스는 시딩 후 일괄 빌드**(random insert 페이지 분할 회피, 375만 청크 3.4분→2분). ③ **청크 병렬**(세션 범위 3분할 동시 INSERT, 48분→16분). ④ `innodb_sort_buffer_size=1M→64M`로 인덱스 merge sort 가속.
- 스크립트: `loadtest/seed/seed_pose_scale.sh` (재현), `loadtest/measure_pagination.sh` (②c 측정).

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
- **설계**: 리포트 쿼리 `EXPLAIN ANALYZE` → `IGNORE INDEX`로 풀스캔 강제 비교.
- **결과 ✅ (2026-06-02, 로컬 412만 행)**:
  - 인덱스 사용: `type=ref`, `key=idx_session_timestamp`, **`Extra=NULL` (filesort 없음)** — WHERE+ORDER BY를 인덱스가 완결.
  - `IGNORE INDEX` 강제 풀스캔: **4.13M 행 스캔 + filesort = 85,000ms (85초)**.
  - → **"인덱스 추가"가 아니라 "이미 최적임을 측정으로 발견"**(§4.3 확인). 인덱스가 85초→lookup으로 바꾸는 핵심 역할도 대조로 입증.
- **면접**: "인덱스 넣기 전에 EXPLAIN으로 이미 최적 확인(filesort 0). 없으면 4M 풀스캔 85초. 진짜 병목은 payload(Ch.11 projection)."

### ② Ch.11 + Ch.13 — 쿼리 최적화 & 파티션 🟢⭐
- **개념**: bulk INSERT, 페이지네이션(offset→cursor), projection / Range 파티션·프루닝.
- **이 프로젝트 (a) 쓰기**: ✅ batch insert 완료 — JdbcTemplate `batchUpdate`, throughput **+99%**, p99 −37% (§7.6). Ch.11 bulk insert 그 자체.
- **이 프로젝트 (b) projection**: `ReportService` JSON blob 헛로드(~3MB) → 3컬럼 DTO. 🔶
- **이 프로젝트 (c) 페이지네이션**: ✅ 전체 테이블 시간순 페이지네이션 offset → keyset(cursor). 1억 행 합성 rig로 실측 — offset O(N) 선형 저하 vs keyset 평탄 입증.
- **이 프로젝트 (d) 파티션**: `pose_data` 날짜 Range 파티션 → **버퍼 TTL의 DROP PARTITION**(주용도, [`db-deep-dive §2-0`](./db-deep-dive.md) raw=버퍼) + cross-session 집계 pruning(부차). ⚠️ PK를 `(id, created_at)`로 변경 선결. 샤딩은 미적용(과설계).
  - **언제 파티셔닝하나? — "행 수"가 아니라 "용도"** ⭐: 업계 감각치(단일 테이블이 버퍼풀 초과 / 수천만~1억 행 / 수십~100GB↑)는 **필요조건일 뿐 충분조건 아님**. RealMySQL도 "몇 행"으로 안 박고 "감당·관리(특히 오래된 데이터 삭제)가 부담일 때"로 설명. 정당화 트리거 3: ①TTL/보존(대량 DELETE 부담→DROP PARTITION) ②쿼리가 항상 파티션 키로 범위 좁힘(pruning) ③파티션 단위 백업·아카이빙.
  - **이 프로젝트의 정직한 포지션**: DAU 작아 파티션 **실수요 0**, 1억 행은 "DAU 1,000 합성", 세션 리포트는 인덱스로 이미 빠름(pruning 이득 없음, §4.3). → 유일한 정당화 = **raw를 단기 버퍼로 보고 `DROP PARTITION`으로 싸게 버리는 TTL**. "1억이니까 파티션"이 아니라 "크기는 충분조건 아님 → 용도(buffer TTL)가 있어서 → 그래서 pruning 아닌 DROP이 헤드라인"이라는 **판단 과정 자체가 차별점** (AI의 "1억이면 파티션 추천"과 정반대 결).
  - **왜 ALTER인가 (CREATE vs ALTER)**: 시딩은 **일부러 비파티션 일반 테이블**로 채움(파티션 걸면 INSERT마다 라우팅 계산으로 시딩 느려짐). 이미 1억 행 든 테이블에 파티션을 거니 `CREATE` 불가 → `ALTER TABLE ... PARTITION BY`만 가능. 그런데 이건 메타데이터만 바꾸는 게 아니라 **임시 테이블(`#sql-…`) 생성 → 1억 행 전부 읽어 파티션별 재배치 → 인덱스 재빌드 → 원본 교체**(=`copy to tmp table`).
  - **같은 `ALTER`라도 비용 정반대** ⭐: `PARTITION BY`(전환)=1억 행 풀 리빌드 71분 vs `DROP PARTITION`(만료)=O(1) 메타데이터. 이 대비가 카드 핵심 — "파티션 전환은 비싸니 운영이면 처음부터 파티션 테이블로 만들거나 pt-osc 무중단, 일단 걸어두면 만료는 DROP으로 거의 공짜".
  - **운영 캐비엇**: 이 in-place `ALTER ... PARTITION BY`는 1억 행 **copy-to-tmp 풀 리빌드**(로컬 실측 ~85분, ~24,700행/초, 그동안 테이블 락) → 현업 운영 DB라면 `pt-online-schema-change`/`ALGORITHM=INPLACE`로 무중단 전환할 이유. 회고 포인트.
- **설계**: (b) projection before/after payload·응답 / (c) offset N=10만 vs cursor 응답 곡선 / (d) 파티션 후 `EXPLAIN`의 `partitions` 컬럼 pruning 확인 + **DROP PARTITION vs DELETE WHERE 실측 시간**(O(1) vs 락·undo).
- **결과 (b) projection ✅ (2026-06-02, warm, 세션 750행)**:
  - payload **1,716.8 KB → 22.4 KB (−98.7%)**, warm 쿼리 **12.1ms → 1.5ms (8x, −87%)**. **인덱스는 동일** — 차이는 `joint_coordinates`(2.3KB JSON)가 InnoDB **off-page(overflow) 저장**이라 SELECT 시 추가 random I/O, projection이 회피(Ch.15).
  - ⚠️ cold 721ms → warm 12ms(같은 쿼리) — 워밍업 통제 필수 재확인(§7.6). 절대 ms는 로컬 기준, 상대 delta는 신뢰 가능.
- **결과 (c) 페이지네이션 ✅ (2026-06-03, 1억 행 합성[측정 시점 9,750만, 결론 동일], warm 3회째, EXPLAIN ANALYZE)**:

  | 깊이(OFFSET) | offset ms | keyset ms | speedup |
  |---|---|---|---|
  | 0 | 0.035 | 0.056 | 1x |
  | 1만 | 3.04 | 0.079 | 39x |
  | 10만 | 27.7 | 0.035 | 798x |
  | 100만 | 290 | 0.034 | 8,504x |
  | 1,000만 | 2,933 | 0.046 | 64,039x |
  | **5,000만** | **25,963 (26초)** | **0.053** | **489,868x** |

  - **offset = O(N) 선형** — 깊이 10배마다 시간 ~10배(`EXPLAIN`: rows 스캔량 = OFFSET+20, cost 0.09→149,953). keyset = **평탄(≈O(log n))**, PK 범위 점프라 깊이 무관.
  - ⚠️ cold/warm: OFFSET 1,000만 cold **8.9초** → warm **2.9초**(버퍼풀 캐시). **캐시돼도 선형 유지** → 병목은 디스크 I/O가 아니라 **행 스캔/폐기 자체(CPU)**. keyset은 그 스캔을 안 함이 핵심.
  - rig: `loadtest/measure_pagination.sh`(재현), 133,334 세션×750행=정확히 1억 행, 더미 JSON `{}`(행수·payload 디커플링 §0.3 — 실제 2.3KB JSON이면 255GB라 불가, 더미로 ~11GB). 버퍼풀 2GB·sort_buffer 64M로 시딩·인덱스 빌드 가속(`docker-compose.yml`).
- **결과 (d) 파티션 ✅ (2026-06-03, 1억 행 → `created_at` 월별 14파티션, in-place ALTER)**:
  - **파티션 전환 비용**: `ALTER TABLE ... PARTITION BY RANGE(UNIX_TIMESTAMP(created_at))` = **5,767초(96분)** copy-to-tmp 풀 리빌드(~24,700행/초). → 운영이면 처음부터 파티션 테이블 or `pt-osc`/`INPLACE` 무중단(위 운영 캐비엇 실측 확인).
  - **pruning (EXPLAIN `partitions` 칼럼)**: 날짜범위 `WHERE created_at∈[6월]` → **`p2026_06` 1개만**(13개 안 읽음) / 범위 없으면 14개 전부 / ⚠️ **`session_id=…` → 14개 전부**(rows:750이나 파티션-로컬 인덱스라 14개 트리 모두 탐색) — **세션 쿼리는 pruning 이득 0, 오히려 미세 손해**. §4.3·정직 포지션 실측 확인.
  - **⭐ DROP PARTITION vs DELETE WHERE (같은 ~8M 행 만료)**:

    | 작업 | 행수 | 소요 | 파티션 | `.ibd` 디스크 |
    |---|---|---|---|---|
    | `DELETE WHERE created_at<…` | 8,301,450 | **1,118,936 ms (18.6분)** | 잔존(0행) | **952MB 그대로**(공간 미회수) |
    | `ALTER … DROP PARTITION` | 7,560,000 | **1,790 ms (1.8초)** | 제거 | **파일 삭제 → ~910MB 즉시 회수** |

  - **~625배** (행당 정규화 ~570배). DELETE는 8.3M행 행단위 삭제(undo·인덱스 유지·락) + **빈 952MB 파일 잔존**(OPTIMIZE 없인 공간 안 돌아옴), DROP은 **파티션 `.ibd` 파일째 unlink** = 행단위 작업 0. ⚠️ DROP 1.8초도 진짜 O(1)이 아니라 ~910MB 파일 삭제 I/O(로컬 디스크 느림) — 빠른 스토리지면 sub-100ms. "O(1)"은 *행단위 비용 없음*을 뜻함.
  - rig: `loadtest/measure_partition.sh`(재현). 측정 후 p2026_01(DELETE로 빈 채 잔존)·p2026_02(DROP) 제거됨 → rig 재현은 `seed_pose_scale.sh`.
- **면접**: "세션 리포트는 안 느려지지만(§4.3), payload는 JSON off-page 페치라 projection이 −98.7%. **파티션은 pruning(세션 쿼리엔 이득 0)이 아니라 TTL이 핵심 — 8M행 만료가 DELETE 18.6분 vs DROP PARTITION 1.8초(625x), DELETE는 빈 파일까지 남는다.**"

### ③ Ch.5 — 트랜잭션·잠금·격리수준 🟢
- **개념**: 격리수준(RC/RR), 레코드/갭/넥스트키 락, 낙관적 vs 비관적.
- **이 프로젝트 (a) 낙관락**: ✅ 구현 — 타임아웃 스케줄러 vs FastAPI 콜백(`Session.java:65 @Version`, `SessionTimeoutScheduler.java:84` 양보).
- **이 프로젝트 (b) lost-update**: `DailyLog.updateStats()` read-modify-write 배선 시 동시 종료 경합 🔶.
- **이 프로젝트 (c) 멱등성**: ✅ INSERT IGNORE (`FeedbackLogService.java:33`).
- **설계**: (b) 두 트랜잭션으로 lost-update **재현**(RC) → 원자 UPDATE / `SELECT FOR UPDATE` / `@Version` 비교, `performance_schema.data_locks`로 락 관찰, `SHOW ENGINE INNODB STATUS` → (a) 낙관락 충돌 시 양보 정책 근거.
- **지표**: 손실 갱신 발생/방지, 락 종류·대기.
- **결과 (b) lost-update 재현·방지 ✅ (2026-06-05, scratch `lock_lab`, daily_logs.updateStats 동형, 매 run 초기화)**:
  - 시나리오: 같은 사용자·같은 날 두 세션 동시 종료 = `total_exercise_time += A(10)`, `+= B(20)`, 기댓값 **30**.

    | 전략 | 방식 | 최종값 | 손실 | 락 비용 |
    |---|---|---|---|---|
    | ❌ naive RMW (RC) | `SELECT @x → SLEEP → UPDATE=@x+v` | **10 or 20** | **유실(commit 순서 따라 10/20)** | 없음(둘 다 0 읽음) |
    | ✅ 원자 UPDATE | `SET x = x + v` | **30** | 없음 | UPDATE 1문장 X락 직렬화 |
    | ✅ 비관락 | `SELECT … FOR UPDATE` | **30** | 없음 | **트랜잭션 내내 X락 점유(블로킹)** |
    | ✅ 낙관락 CAS | `… WHERE id=? AND version=@v` | **30** | 없음 | 블로킹 0, **충돌 시 재시도 1회** |

  - **재현 안정성**: 3회 반복 모두 naive 는 유실(10 또는 20), 세 방지책 모두 30 복구. CAS 는 매 run **정확히 1 worker 충돌→재시도 2회차 성공**(version 0→1→2).
  - **락 증거(`performance_schema.data_locks`, B 가 FOR UPDATE 대기 중 스냅샷)**: 양 trx 가 테이블 `IX`(의도) GRANTED + 동일 레코드(PK=1)에 trx A `X,REC_NOT_GAP **GRANTED**` / trx B `X,REC_NOT_GAP **WAITING**` — 비관락이 단일 핫로우를 **직렬화**함을 그대로 관찰. naive·원자·CAS 경로엔 이 WAITING 이 없음(블로킹 무).
  - **해석**: RC 격리만으론 read-modify-write 의 lost-update 를 **못 막는다**(MVCC 스냅샷 읽기라 둘 다 옛값). 막으려면 ① 읽기 자체를 없애거나(원자 UPDATE) ② 읽기에 락을 걸거나(FOR UPDATE) ③ 쓰기에 버전 가드(CAS). **단일 카운터 누적이면 원자 UPDATE 가 최적**(왕복·블로킹 0). `@Version` 은 충돌이 드물고 블로킹을 피하고 싶을 때(이 프로젝트의 타임아웃 vs 콜백 경합).
  - rig: `loadtest/measure_lock.sh`(재현, 4단계 + 락 스냅샷). 🟡 갭/넥스트키 락은 단일 PK 핫로우라 미관찰 — append-only 저경합 substrate 한계, 명시.
- **(a) 낙관락 양보(실코드) 근거**: 타임아웃 스케줄러 vs FastAPI 콜백은 **저경합·서로 다른 세션 위주**라 비관락의 상시 블로킹 비용이 아깝다 → `@Version` 으로 충돌만 감지, 충돌 시 콜백이 재시도(`SessionService.completeSession` 최대 3회, FastAPI 결과 우선). 위 (b) 의 CAS 가 바로 이 정책의 축소 재현.
- **면접**: "RC로는 lost-update 못 막음(MVCC 스냅샷이라 둘 다 옛값 읽음) → 단일 카운터 누적은 **원자 UPDATE**가 최적(왕복·블로킹 0). `data_locks`로 FOR UPDATE의 X,REC_NOT_GAP WAITING을 직접 관찰해 비관락이 핫로우를 직렬화함을 확인. 타임아웃 vs 콜백은 저경합·세션 분리라 낙관락(@Version)으로 블로킹 비용 회피, 충돌 시 3회 재시도." 🟡 갭/넥스트키 락은 단일 PK 핫로우라 미관찰 — 명시.

### ④ Ch.4 + Ch.15 — 아키텍처(MVCC·버퍼풀) & JSON 🟢🟡
- **개념**: InnoDB 버퍼풀, undo, MVCC 스냅샷 / JSON 저장·함수·generated column 인덱스.
- **설계 (MVCC)**: 긴 batch insert 트랜잭션 open 중 동시 리포트 조회가 **이전 스냅샷** 읽음(RR) 관찰 — gRPC 적재↔조회 사이 일관성.
- **설계 (버퍼풀)**: 1억 행 > `innodb_buffer_pool_size`면 디스크 읽기 급증 — hit율(`sys`) 관찰, 버퍼풀 사이징 영향.
- **설계 (JSON)**: `joint_coordinates` 저장 비용 측정, JSON path에 **generated column + 인덱스** vs 정규화 테이블 트레이드오프. 🟡 (현재 불필요, 데모용).
- **설계 (트림 33→13)** 🔶: `_landmarks_to_json`이 MediaPipe **33개 전부 저장**(필터 없음, `constants.py LANDMARK`는 13개만 참조) → JSON 함수로 사용 13개만 추출해 `AVG(LENGTH)` before/after 측정(33→13 ≈ ~60% 절감). **단 이건 국소 미봉책** — 진짜 해법은 [`db-deep-dive §2-0`](./db-deep-dive.md)의 raw→buffer/serving 분리(raw 단기화하면 트림 영구비용 자체가 사라짐).
- **결과 (트림 33→13) ✅ (2026-06-07, `_pose_template` 현실 JSON)**:
  - `joint_coordinates` 평균 **2,344 B → 916 B (−60.9%)** — 33개 landmark 중 사용 13개(`0,11~16,23~28`)만 `JSON_ARRAY(JSON_EXTRACT(...))`로 남긴 결과. 구조적 절감이라 값 균일과 무관하게 유효.
  - **키는 못 줄임(천장)**: `z`(angle_calculator 3D 각도)·`visibility`(squat_analyzer 추적신뢰도) 둘 다 실사용 → `x,y`만 남기는 추가 트림은 분석이 깨짐. 13 landmark × 4 key 가 정직한 상한.
  - ⚠️ rig JSON 은 `{"landmarks":[{x,y,z,visibility}×33]}`(index 필드 없음)인데 실코드 `_landmarks_to_json`(`pose.py:93`)은 `[{index,x,y,z,visibility}]`라 index 키만큼 더 큼 → **실제 절감폭 ≥ 60.9%**. 진짜 수정위치는 `pose.py:93`(이번 미변경, ai-server 별도 결정).
  - 스케일 투영: `pose_data` 412만 행 ~10.7GB → 트림 시 ~40%대. DAU 1,000 1억 행이면 ~234GB → ~92GB. **단 국소 미봉책** — 진짜 해법은 raw→buffer/serving 분리([`db-deep-dive §2-0`](./db-deep-dive.md)).
- **결과 (MVCC) ✅ (2026-06-07, scratch `mvcc_lab` 100행, `measure_mvcc.sh`)**:
  - **RR(기본)**: Reader 트랜잭션 첫 SELECT=100 → 그 사이 Writer 가 50행 INSERT+COMMIT → 같은 트랜잭션 재SELECT **여전히 100**(스냅샷 고정) → COMMIT 후 **150**. 일관된 읽기(consistent read) 입증.
  - **RC 대비**: 같은 시나리오에서 재SELECT가 **150**(매 문장 새 스냅샷). RR↔RC 차이를 직접 관찰.
  - **읽기↔쓰기 비블로킹(RR)**: Reader 가 3초 트랜잭션 점유 중에도 Writer INSERT+COMMIT 이 **461ms** 에 완료(안 막힘). `performance_schema.data_locks` **0행** — 일반 SELECT 는 락 없이 undo 로 과거 버전 재구성.
  - **SERIALIZABLE 대비**: 같은 시나리오를 SERIALIZABLE 로 돌리면 일반 SELECT 가 암묵적 잠금읽기(S 넥스트키락)가 됨 → `data_locks` 에 `RECORD S GRANTED 101`(100행+supremum), Writer INSERT 가 **1,998ms** 블로킹(RR 461ms 의 4배). **읽기가 락을 잡아 직렬화 = MVCC 비블로킹의 반대편.** 단일 카운터엔 과함(원자 UPDATE/낙관락이 답) — ③ 락 실험과 연결.
  - 실코드 매핑: gRPC `SavePoseDataBatch` 적재가 길게 열려도 리포트 조회(`ReportService`)는 RR 스냅샷으로 대기 없이 일관 뷰 → 적재↔조회 상호 비블로킹(OLTP 동시성).
- **결과 (버퍼풀) ✅ (2026-06-07, `pose_data_scale` 8,256만 행/10.4GB vs 풀 128MB, `measure_bufferpool.sh`)**:
  - **작업셋 ≫ 풀**: 월 파티션 풀스캔(540MB ≫ 128MB) → cold/warm 모두 **매번 ~485MB 디스크 읽기**, hit ~84% 고정, ~10초. 작업셋이 풀을 초과해 **warm 재실행도 캐시 무력**(eviction 으로 재miss).
  - **작업셋 < 풀**: 좁은 PK 범위(수MB) → warm **디스크 0MB, hit 100%**, ~0.45초. 핫 데이터 메모리 상주. (⚠️ 이 구간 cold 도 직전 실행 잔여로 이미 resident — 진짜 cold 아님, 명시)
  - **⭐ read-ahead 함정**: 순차 스캔은 InnoDB 선읽기(`Innodb_buffer_pool_read_ahead`)가 페이지를 비동기로 당겨와 **표준 hit율 공식(1−`reads`/`read_requests`)이 거짓 99.9%** 가 됨(`reads`는 동기 미스만 카운트). 진짜 물리 I/O = `reads`+`read_ahead`, 또는 `Innodb_data_read`(바이트)로 봐야 84%/485MB 가 보임. **"hit율 공식은 read-ahead 가 가린다"** 가 핵심 교훈.
  - 같은 뿌리: 페이지네이션 cold/warm(②c §3)·시딩 가속(버퍼풀 128MB→2GB)·라우팅 모두 "작업셋 vs 메모리". 사이징 판단 = ⓐraw 단기화·파티션 만료로 작업셋 축소 ⓑ풀 증설.
- **결과 (generated column) ⬜ 미수행 결정**: ⓐ프로젝트 실수요 0(JSON 내부값으로 검색 안 함, `sync_rate`는 별도 컬럼) ⓑ합성 rig 가 단일 템플릿 복제라 **값 분포(카디널리티) 균일**(`_pose_template` 750행 `d_whole_json=1`) → 선택도 실험 불가. **세 번째 축(값 분포) 한계**로 정직 박제(아래 §5 캐비엇). ⑤ 옵티마이저도 동일 제약.
- **면접**: "MVCC 덕에 적재 트랜잭션이 조회를 블로킹 안 함(RR 스냅샷, data_locks 0). 트림은 33→13 으로 −60.9%지만 z·visibility 실사용이라 키는 못 줄임, 진짜 해법은 raw 를 OLTP 에서 빼는 것. 버퍼풀은 **작업셋 vs 메모리** — 540MB 스캔이 128MB 풀에선 warm 도 매번 485MB 재읽기, 그리고 **read-ahead 때문에 표준 hit율 공식이 거짓 99% 라 `data_read` 바이트로 봐야 한다**."

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
| 합성데이터 **값 분포** | 🟡 rig 가 단일 템플릿 복제라 **카디널리티 균일**(`d_whole_json=1`). 행수·payload(크기)는 진짜지만 값 분포는 가짜 → 분포 의존 실험(generated column 선택도·옵티마이저 카디널리티)은 **미수행**. "구조 실험만 골라 돌렸다"가 정직 |
| 버퍼풀 hit율 | 🟡 표준 공식(1−`reads`/`req`)은 **read-ahead 가 가려 거짓 99%** — 순차 스캔은 `read_ahead`+`Innodb_data_read`(바이트)로 봐야 함(§4 ④) |
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
