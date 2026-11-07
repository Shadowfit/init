# #276 근본 처방 — 재시도가 아니라 «중복 하나가 파티션 끝을 잠그는» 자리를 없앤다

작성: 2026-09-24
상태: **ㄴ(RC) 채택 · 적용 (2026-09-24 사용자 confirm)** — ㄷ(자연키 PK) 비용 측정 **착수·설계 확정(2026-11-08)**, rig 미작성 — §5
근거: [`loadtest/results/r276-lock-trace-2026-09-24/`](../../loadtest/results/r276-lock-trace-2026-09-24/README.md)
관련: [#276](https://github.com/Shadowfit/init/issues/276) · [`r276-retry-followup.md`](./r276-retry-followup.md) · [`pose-batch-idempotency-vs-partition.md`](./pose-batch-idempotency-vs-partition.md) · [`online-ddl-vs-blocking-alter.md`](./online-ddl-vs-blocking-alter.md)

---

## 0. 한 줄

지금 처방(데드락 재시도 상한 5)은 **그물**이다. 09-24 결정적 재현이 **그물 아래의 자리**를 찾았다 —
RR 에서 **중복 키 한 건**이 `PRIMARY` 의 파티션 끝(supremum)에 `X` 를 잡고, 커밋까지 **그 파티션의 모든 신규 삽입을 세운다.**
데드락은 그 `X` 를 두 트랜잭션이 동시에 쥘 때 닫히는 결과일 뿐이다. 그 `X` 를 안 생기게 하는 후보가 둘 있고, 둘 다 동시 부하에서 0/960 이었다.

## 1. 확인된 것 (측정)

| 사실 | 근거 |
|---|---|
| supremum `X` 는 중복 **한 건**에서 생긴다(엇갈림 불필요) | trace base 단계 1 |
| ODKU 고유가 아니다 — `INSERT IGNORE`·평범한 `INSERT`(1062) 도 같다 | trace insert_ignore · plain_insert |
| 그 X 는 **무관한 세션의 신규 키**도 커밋까지 세운다 | trace dup_blocks_new |
| RC 에서는 supremum 이 없다. 단 uk 원본 레코드의 next-key `X` 가 남아 **한 방향 대기**가 생긴다 | trace read_committed (3.26초 대기, 데드락 0) |
| 멱등 키를 PK 로 하면 원본 레코드 `REC_NOT_GAP` 하나만 잡는다. id 를 보조 인덱스로 남겨도 같다 | trace natural_pk · natural_pk_keep_id |
| 동시 부하(워커 8·중복): base **45.9%**, RC·자연키 PK 두 변형 **0/960**, 네 팔 모두 멱등 유지(200행) | concurrent.txt, 라틴 방격 3블록 |

**미검증**: supremum `X` 가 «왜» 생기는지의 소스 수준 설명(가설 하나를 세웠다가 철회했다 — 결과 README §4).

## 2. 후보

### ㄱ. 현상 유지 — 재시도 상한 5

- 비용 0. 08-26 에 확정된 값
- 남는 것: 데드락은 계속 나고(앱 경로 상한 5 에서도 잔여 4.2%, 08-23), 재시도 루프 p99 약 4초.
  그리고 이번에 보인 **«중복 하나가 파티션 끝을 세운다»** 는 재시도와 무관하게 그대로다

### ㄴ. 이 쓰기 경로만 READ COMMITTED

`PoseDataService.savePoseDataBatch` 의 `@Transactional` 에 `isolation = READ_COMMITTED` 한 줄.

| 얻는 것 | 치르는 것 / 모르는 것 |
|---|---|
| supremum `X` 가 사라진다 · 동시 부하 0/960 | **한 방향 대기는 남는다** — 재전송 트랜잭션이 커밋할 때까지 «다음 세션 원본 앞 gap» 에 들어갈 신규 삽입이 기다린다 |
| 코드 한 줄 · 되돌리기 쉽다 · 스키마 불변 | 「순환이 안 생긴다」는 **세션 키가 `session_id` 로 묶여 있다는 논증**이다. 한 트랜잭션이 여러 세션 키를 섞는 경로가 생기면 안 선다 |
| | 같은 트랜잭션 안의 세션 조회(`findById`)도 RC 가 된다 — 이 경로에선 무해해 보이나 **확인 안 함** |
| | binlog 는 8.0 기본값 ROW 에 기대고 있다(설정 파일에 명시 없음). STATEMENT 로 바뀌면 RC 와 못 산다 |

### ㄷ. 멱등 키를 PK 로 — `(session_id, rep_number, timestamp_sec, created_at)`, id 는 AUTO_INCREMENT 보조 인덱스로 유지

| 얻는 것 | 치르는 것 / 모르는 것 |
|---|---|
| supremum 도, 한 방향 대기도 없다 — 원본 레코드 하나만 잠근다 · 동시 부하 0/960 | **1억 행급 파티션 표의 PK 재구성** — 테이블 재작성이다. 온라인으로 가능한지·걸리는 시간은 **안 쟀다**([`online-ddl-vs-blocking-alter.md`](./online-ddl-vs-blocking-alter.md) 의 축) |
| `uk_pose_event` 가 PK 로 흡수돼 **인덱스 하나가 줄어든다** | PK 폭 12B → 21B — 보조 인덱스(`idx_session_timestamp`, `idx_pose_id`)가 그만큼 커진다. `idx_session_timestamp` 는 PK 앞부분과 겹쳐 **중복 인덱스가 될 수 있다**(검토 안 함) |
| 행이 **세션 순으로 물리 정렬**된다 — 세션 단위 읽기(`findFramesBySessionId`)가 PK 범위 스캔이 될 수 있다 | 그 이득도 **안 쟀다.** 삽입이 파티션 끝 한 점이 아니라 **동시 세션 수만큼의 지점**으로 흩어진다 — 페이지 분할·버퍼풀 영향 미측정(uk-bufferpool 08-23 판이 «흩어진 삽입 −46%» 를 본 적 있다) |
| id 를 남기므로 `reports.detailed_analysis` 의 `poseDataId` 참조가 산다 | id 로 찾는 조회(`PoseDataRepository.findJointCoordinatesById`)가 PK 다이브에서 **보조 인덱스 + PK 룩업**으로 바뀐다(행 1개라 작을 것 — 미측정). JPA 엔티티 `@Id` 변경 필요 |

(id 를 아예 지우는 변형도 잠금은 같았지만, 저장된 `poseDataId` 가 깨지므로 후보에서 뺀다.)

### ㄹ. 기존 키를 먼저 조회해 신규 행만 INSERT

- 조회와 삽입 사이에 재전송이 끼면 같은 경로로 떨어진다 — 창을 좁힐 뿐 자리를 없애지 않는다. **측정 안 함.** 비교용으로만 적는다

## 3. 추천 (결정 아님)

**ㄴ 을 먼저, ㄷ 은 비용을 잰 뒤에.**

- ㄴ 은 한 줄이고 되돌리기 쉽다. 측정으로 데드락 0/960 이 나왔고, 남는 한 방향 대기는 재전송 트랜잭션 길이(배치 하나, ms 단위)만큼이다
- ㄷ 은 자리를 완전히 없애는 **구조적 답**이고 DB 이야기로는 가장 굵다(인덱스 설계·클러스터링·온라인 DDL). 하지만 치르는 것 네 줄이 전부 미측정이라,
  지금 채택하면 «근거 없는 결정» 이 된다. 착수한다면 순서는 ① 삽입 처리량(append vs 세션별 지점) ② PK 재구성 시간·온라인 여부 ③ 세션 단위 읽기 이득
- 재시도(ㄱ)는 어느 쪽을 골라도 **남겨 둔다** — 다른 원인의 데드락에 대한 그물로서. 상한을 줄일지는 ㄴ/ㄷ 적용 뒤 `pose_batch_deadlock_retries` 로 본다

## 4. 미결정 (사용자 confirm 필요)

1. ~~ㄴ 을 적용할지~~ → ✅ 적용(아래 결정 로그)
2. ~~ㄷ 의 비용 측정(§3 순서)을 착수할지~~ → ✅ 착수(2026-11-08 사용자 confirm). 설계는 §5, 그 안의 미결정은 §5-5
3. #276 에 이 판 결과를 코멘트로 남기고, 이슈 상태 블록을 갱신할지

## 5. ㄷ 비용 측정 설계 (2026-11-08 — 착수·세부 확정, rig 미작성)

### 5-1. 왜 재나

ㄴ(RC)을 고른 이유는 «ㄷ 이 더 근본적이지만 치르는 것 네 줄이 전부 미측정» 이었다(§2 ㄷ 표). 그 판단이
맞았는지는 **ㄷ 의 값을 재야** 말할 수 있다. 이 측정은 ㄴ 을 뒤집으려는 것이 아니라 «보류» 를 «근거 있는
선택» 으로 바꾸려는 것이다 — 결과가 «ㄷ 이 싸다» 로 나오면 ㄴ 을 다시 연다.

### 5-2. 이미 아는 것 — 출발점

- 🟢 **흩어진 삽입의 값은 이미 한 번 쟀다.** [uk-bufferpool AWS 08-23](../../loadtest/results/uk-bufferpool-aws-2026-08-23/README.md)
  (`c7i.xlarge`) — **보조 인덱스**만 흩어져도 버퍼풀을 넘으면 유니크 −46%, 비유니크조차 append 대비 29배 느려졌다.
  ㄷ 은 **행 전체(클러스터드 인덱스)** 가 흩어지는 경우라 그보다 클 수 있다 — 이것이 ① 의 가설이다
- 🟢 **PK 를 바꾸는 DDL 의 비교 기준이 있다.** [online-ddl AWS 08-12](../../loadtest/results/online-ddl-aws-2026-08-12/README.md)
  (`m6i.xlarge`) — 1,000만 행 · 차단 ALTER 68~70초(전 구간 쓰기 정지) ↔ pt-osc 112~114초(최대 정지 30~364ms,
  binlog 441MB). 단 그때 DDL 은 `PARTITION BY` 라 INPLACE 가 거절됐다. **PK 교체는 INPLACE 가 되는지부터 모른다**
- 흩어지는 정도 = 동시에 쓰는 운동 세션 수. 이 값은 이미 가정이 있다 —
  피크 동시 = DAU × 0.0675([`ai-horizontal-scaling.md:35`](./ai-horizontal-scaling.md)) → DAU 1,000 이면 약 68, DAU 10,000 이면 약 675

### 5-3. 팔

| 팔 | 스키마 |
|---|---|
| **A** (지금) | PK `(id, created_at)` + `uk_pose_event` + `idx_session_timestamp` · RC |
| **B** (ㄷ) | PK `(session_id, rep_number, timestamp_sec, created_at)` + `KEY(id)`(AUTO_INCREMENT 유지) + `idx_session_timestamp` |
| **B′** | B 에서 `idx_session_timestamp` 를 뺀 것 — PK 앞부분(`session_id`)과 겹쳐 중복인지 본다 |

### 5-4. 단계 — §3 의 순서 그대로

**① 삽입 처리량** — 재고표에 없던 «클러스터드 인덱스가 흩어질 때» 를 잰다
- 체제 둘: 메모리 안(풀 2GB) · 버퍼풀 초과(풀을 줄인 전용 컨테이너 — uk-bufferpool rig 과 같은 방식)
- 동시 세션: 68 · 675(위 가정에서 나온 값) — 실제 페이로드처럼 세션마다 배치로
- 지표: 행/초 · 배치 p99 · `Innodb_buffer_pool_reads` · 페이지 분할(`information_schema.innodb_metrics` 의 `index_page_splits`)
- 판: 팔 A·B·B′ × 4블록(첫 블록 버림) 라틴 방격

**② PK 재구성** — 1,000만 행(online-ddl 08-12 와 같은 시더·규모라 직접 비교된다)
- 먼저 소형 복제본에 `ALTER TABLE … DROP PRIMARY KEY, ADD PRIMARY KEY(…), ALGORITHM=INPLACE, LOCK=NONE` 을
  던져 **서버가 받는지** 확인한다(08-12 의 1845 거절과 같은 사전 확인)
- 팔: 받으면 INPLACE · 거절되면 COPY, 그리고 pt-osc — `uk_pose_event` 가 있어 pt-osc 가 청크를 나눌 키가 있다
- 지표: DDL 소요 · 최대 쓰기 정지 · binlog 증가 · 디스크 최대(08-12 와 같은 writer)

**③ 세션 단위 읽기** — `findFramesBySessionId` 형태
- A 는 `idx_session_timestamp` → PK 룩업, B 는 PK 범위 스캔. 버퍼풀을 비운 직후(차가움)와 데운 뒤 둘 다
- 덤: id 로 찾는 조회(`findJointCoordinatesById`)가 B 에서 보조 인덱스 + PK 룩업으로 바뀌는 값

### 5-5. 세부 — ✅ 네 건 모두 추천대로 확정 (2026-11-08 사용자 confirm)

1. **박스** — 추천: **① 은 `c7i.xlarge`, ② 는 `m6i.xlarge`** — 각각 비교 기준(08-23 · 08-12)과 같은 타입이라 값을
   나란히 놓을 수 있다. ③ 은 ② 의 무대에 얹는다. 둘 다 4 vCPU 라 동시에 띄워도 8 — 계정 한도 32 를 다른 세션
   라운드(binlog 재생 RTO)와 나눠 쓴다
2. **② 규모** — 추천: 1,000만 행(08-12 직접 비교). 1억 행은 시딩만 수 시간이라 ①③ 결과를 보고 판단
3. **③ 을 이번에 같이 할지** — 추천: 같이(② 의 무대를 그대로 쓰면 추가 시딩이 없다)
4. **B′ 를 넣을지** — 추천: 넣는다(중복 인덱스 질문이 §2 ㄷ 표에 «검토 안 함» 으로 남아 있다)

## 결정 로그

- 2026-09-24 — 문서 작성. 결정 없음
- 2026-09-24 — **ㄴ 채택 (사용자 confirm).** `PoseDataService.savePoseDataBatch` 에 `@Transactional(isolation = READ_COMMITTED)`.
  회귀 가드 `PoseDataResendDeadlockRaceTest`(실 MySQL, 세션 8 × 재전송 20): **RR 로 되돌리면 데드락 63/160 으로 실패, RC 에서 0 으로 통과**를 둘 다 확인했다.
  데드락 재시도(상한 5)는 그대로 둔다(§3)
- 2026-11-08 — **ㄷ 비용 측정 착수 (사용자 confirm).** 계기: 자소서에 «PK 재구성 비용을 재지 못해 보류» 라고 적었고,
  «그럼 지금은?» 에 답할 값이 없다. 설계 §5 — 팔 A(지금)·B(자연키 PK)·B′(B − `idx_session_timestamp`),
  ① 삽입 처리량(메모리 안 · 버퍼풀 초과 × 동시 세션 68·675) ② PK 재구성(1,000만 행, INPLACE 가능 여부부터) ③ 세션 단위 읽기.
  박스·규모·범위(§5-5 네 건)는 confirm 대기
- 2026-11-08 — **§5-5 네 건 추천대로 확정 (사용자 confirm).** ① `c7i.xlarge` · ②③ `m6i.xlarge` · ② 1,000만 행 ·
  ③ 같이 · B′ 넣음. 다음은 rig 작성 → 로컬 축소 스모크 → EC2 무인 라운드
