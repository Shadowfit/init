# #276 결정적 재현 — 중복 하나가 파티션 끝을 잠근다 (2026-09-24)

이슈: [#276](https://github.com/Shadowfit/init/issues/276) · rig: [`measure_r276_lock_trace.sh`](../../measure_r276_lock_trace.sh)(단계별 잠금 표) · [`measure_r276_lock_trace_concurrent.sh`](../../measure_r276_lock_trace_concurrent.sh)(동시 부하 확인)
원문: [`trace.txt`](./trace.txt) · [`concurrent.txt`](./concurrent.txt)

## 0. 왜 이 판인가

08-17~08-30 의 판은 전부 **확률**(워커 N 개를 풀어 데드락 비율을 셈)이었고, 처방도 **재시도 상한**(현재 5)이었다.
잠금 자리(`PRIMARY` supremum)는 데드락 덤프 한 쌍으로만 봤고 **어느 문장이 그 X 를 만드는지**는 미검증이었다.
이 판은 부하 없이 두 세션을 한 문장씩 진행시키며 단계마다 `performance_schema.data_locks` 를 찍는다 —
같은 입력이면 같은 출력이라 판 수가 필요 없다. 그리고 **재시도가 아니라 그 X 를 안 생기게 하는 구조**를 팔로 넣었다.

## 1. 무대

- 격리 컨테이너 `mysql:8.0` → **8.0.46**, 기본 격리 REPEATABLE-READ, `innodb_autoinc_lock_mode=2`
- 스키마: Flyway `V1`~`V26` 을 버전 순으로 그대로 적용. 대상 표 `pose_lab` = `CREATE TABLE ... LIKE pose_data`
  (PK `(id, created_at)` · `uk_pose_event` · 월 파티션 전부 복제). 운영 compose DB 는 안 건드렸다
- 삽입문은 `PoseDataService.INSERT_POSE_SQL` 과 같은 꼴(`ON DUPLICATE KEY UPDATE session_id = session_id`)
- 원본 행: 세션 901·902 의 `(rep 1, ts 1.000)` 을 먼저 커밋 — 재전송이 겹칠 대상
- 로컬 박스(i3-6100, 2물리코어). **비율의 절대값은 이 박스의 것**이다

## 2. 결정적 trace — 판정

| 팔 | 중복 1건 직후 T1 이 쥐는 것 | 남(T2)의 삽입 | 데드락 |
|---|---|---|---|
| **base** (운영 그대로) | `PRIMARY` **supremum `X`** + 원본 PK 행 `X,REC_NOT_GAP` + uk 원본 `X` | **막힘** — `X,INSERT_INTENTION WAITING` on supremum, T1 COMMIT 까지(3.09초) | 0 (T2 가 먼저 막혀 순환이 안 닫힘) |
| **dup_blocks_new** | 같음 | **무관한 세션(903)의 신규 키도 막힘** | 0 |
| plain_insert (ODKU 아님, 1062 실패) | **supremum `X` 가 똑같이 남는다** | — | — |
| insert_ignore | 같음 (supremum `X`) | 막힘 | 0 |
| dup_only / new_only | dup_only 는 base 와 같음 · new_only 는 gap 락 없음 | new_only 는 안 막힘 | 0 |
| no_uk | 잠금 없음 | 안 막힘 | 0 — **대신 중복이 그대로 들어간다(6행)** |
| **read_committed** | supremum 없음. 원본 PK 행 `X,REC_NOT_GAP` + uk 원본 **`X`(next-key)** | **한 방향 대기** — T1 의 신규(901,2,2)가 T2 가 잡은 uk (902,1,1) 앞 gap 에서 WAITING(3.26초) | 0 |
| **natural_pk** (id 제거, 멱등 키 = PK) | **원본 PK 행 `X,REC_NOT_GAP` 하나뿐** — gap·supremum 없음 | 안 막힘 | 0 |
| **natural_pk_keep_id** (멱등 키 = PK, id 는 AUTO_INCREMENT 보조 인덱스 `KEY(id)`) | natural_pk 와 같음 — `idx_pose_id` 는 잠금 목록에 안 나온다 | 안 막힘 | 0 — 기존 id(1·2) 그대로 |

### 읽으면

1. **supremum `X` 의 출처는 «중복 한 건» 이다.** 두 세션이 엇갈릴 필요도 없다 — T1 혼자 중복 하나를 넣은 직후에 이미 있다.
   ODKU 고유도 아니다: `INSERT IGNORE` 와 평범한 `INSERT`(1062 실패)도 같은 락을 남긴다. 즉 **RR 에서의 중복 키 처리 경로**다.
2. **그 X 는 데드락 이전에 «파티션 끝 직렬화» 다.** 중복을 넣은 트랜잭션이 커밋할 때까지, 같은 파티션(= 같은 달)에
   **새 행을 넣으려는 모든 트랜잭션**이 supremum 의 insert intention 에서 기다린다 — 원본과 무관한 세션도(dup_blocks_new).
   auto-increment PK 라 모든 신규 삽입이 파티션 끝으로 가기 때문이다.
3. **데드락은 그 X 를 두 트랜잭션이 «동시에» 쥘 때 닫힌다.** 이 trace 는 문장 단위로 진행하므로 T2 가 X 를 얻기 전에 막혀
   순환이 안 생긴다 — 둘이 같은 순간에 중복을 처리하는 창은 SQL 단계로는 못 만든다. 그래서 동시 부하 판(§3)으로 확인했다.
   동시성이 오를수록 그 창이 겹칠 확률이 오른다 — 08-20 워커 스윕(w=2 1.2% → w=16 59.5%)과 방향이 맞는다.
4. **RC 는 supremum 을 없애지만 대기를 다 없애지는 않는다.** 중복 검사는 RC 에서도 uk 원본 레코드에 next-key `X` 를 잡는다.
   관측된 대기는 **한 방향**(낮은 세션의 신규 → 다음 세션이 잡은 레코드 앞 gap)이라 이 모양에서는 순환이 안 생겼다.
5. **자연키 PK 는 둘 다 없앤다.** 중복 검사가 곧 PK 조회라 원본 레코드 하나만 `REC_NOT_GAP` 으로 잠근다.
   id 를 AUTO_INCREMENT 보조 인덱스로 남겨도 같다 — 리포트가 영구 저장한 `poseDataId`(`WorstSectionDto`)를 살릴 수 있다.

## 3. 동시 부하 확인 — trace 의 «0» 이 부하에서도 0 인가

기존 확률 rig 의 same_partition 팔과 같은 모양: 워커 8 · 워커마다 다른 세션의 같은 25행을 40문(첫 문장만 신규) · 문장 = autocommit.
팔 4 × 4블록, **블록마다 순서 회전(라틴 방격), 블록 0 버림.**

| 팔 | 블록 1 | 블록 2 | 블록 3 | 중앙값 | 최종 행 |
|---|---:|---:|---:|---:|---:|
| base | 155/320 | 147/320 | 129/320 | **45.9%** | 200 |
| read_committed | 0/320 | 0/320 | 0/320 | **0 / 960** | 200 |
| natural_pk | 0/320 | 0/320 | 0/320 | **0 / 960** | 200 |
| natural_pk_keep_id | 0/320 | 0/320 | 0/320 | **0 / 960** | 200 |

- 같은 날 팔 3 개로 먼저 돌린 판(이 파일에 없음)은 base 141·103·148(중앙값 44.1%), 나머지 둘 0/960 이었다 — 판정이 같다
- base 의 45.9% 는 08-20 로컬(37.5%·42.2%)·08-23 AWS(42.8%)와 같은 자리다 — 무대가 기존 판을 재현한다
- 두 후보 모두 **멱등은 유지**(최종 200행 = 8세션 × 25행)
- 그 외 에러 0

## 4. 정정·철회

- 🔴 **trace 도중 세운 가설 하나를 철회한다** — base 팔 최종 id 가 1·2·4·5 라 «T1 의 중복이 클러스터에 먼저 들어갔다 되감기며
  락이 supremum 으로 상속됐다» 고 봤는데, **단일 세션에서 중복 하나를 넣으면 RR·RC 모두 id 가 안 빈다**(1·2 연속).
  빈 id 3 은 T1 이 아니라 **대기하던 T2** 쪽에서 났다. supremum X 가 «왜» 생기는지의 소스 수준 설명은 **미검증**이다
- 08-17 코멘트의 «PK 가 AUTO_INCREMENT 라 삽입이 끝에 몰린다» 는 **자리의 설명으로는 맞다**(natural_pk 에서 사라지므로).
  08-20 코멘트가 no_uk 대조군을 근거로 이 서술을 «반증됐다» 고 적었고, 같은 날 덤프 코멘트는 **자리**(PRIMARY supremum)만
  되돌렸다 — auto-increment 쪽 서술은 반증 표시가 붙은 채 남아 있었다. 이 판이 그것을 되살린다

## 5. 말하면 안 되는 것

- **RC 가 «모든 패턴에서» 데드락 0 이라는 것.** 관측한 대기가 한 방향이었던 것은 세션의 키가 `session_id` 로 묶여 있어서다.
  한 트랜잭션이 여러 세션의 키를 섞어 넣는 경로가 생기면 이 논증은 안 선다. 부하 확인도 이 모양 하나다
- **자연키 PK 의 비용.** 이 판은 잠금만 봤다 — 쓰기 처리량·페이지 분할·보조 인덱스 크기·1억 행 PK 재구성 시간은 **안 쟀다**
- **supremum 직렬화의 운영 영향 크기.** 재전송이 드문 동안은 트랜잭션이 짧아 체감이 작을 것이다 — 이것도 **안 쟀다**
- 45.9% 등 절대 비율 — 박스·동시성·페이로드(작은 JSON) 조건의 값이다
