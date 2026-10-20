# `pose_data.joint_coordinates` 로 판정을 재검증하는 쿼리 — 포폴 카드로 만들 것인가, 무엇을 잴 것인가

작성: 2026-09-19
상태: **✅ 4-1 a · 4-2 a · 4-3 a 채택 (2026-09-19 사용자 confirm)** — 선행 #714 는 PR #777. 카드 위치(§8)는 측정 결과 뒤. §7 은 측정 뒤 채운다.
발단: "운동 좌표 JSON 관련해서 만들 수 있는 쿼리가 있나 — 포폴용으로". 쿼리 자체는 여럿 가능하지만(§3), **포폴 카드**가 되려면 «순진한 방법이 숫자로 깨진 곳 + 고친 before/after» 가 있어야 하고([`db-portfolio-roadmap.md`](./db-portfolio-roadmap.md) 기준선), 이미 있는 JSON 카드([`weekly-json-table-query-tuning.md`](./weekly-json-table-query-tuning.md), «파싱이 아니라 조인 순서»)와 **교훈이 달라야** 한다. 그 조건을 통과하는 후보는 하나(§3 ①)뿐이고, 그 하나가 분기점 3개를 안고 있다.
대상 코드: [`PoseDataRepository.java`](../../backend/src/main/java/com/shadowfit/repository/exercise/PoseDataRepository.java) · [`angle_calculator.py`](../../ai-server/app/core/angle_calculator.py) · [`squat_analyzer.py`](../../ai-server/app/core/squat_analyzer.py)
연관: 이슈 [#217](https://github.com/Shadowfit/init/issues/217)(무릎각 3D/2D 70° 갈림 — 실영상 미검증) · [`../tasks/32-deferred-items.md`](../tasks/32-deferred-items.md) P5(«좌표가 write-only 다») · [`pose-ingest-downsampling.md`](./pose-ingest-downsampling.md)(R=5 대표추출, TTL 보류) · [`../portfolio/realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §4 ④(off-page JSON I/O 실측 · generated column 미수행 결정) · [`worst-section-rep-resolution.md`](./worst-section-rep-resolution.md)(`smoothed_knee_angle` 컬럼의 출처)

---

## 0. 한 줄 요약

`pose_data.joint_coordinates` 는 한 달에 수천만 행 규모로 쌓였다가(합성 기준) `DROP PARTITION` 으로 지워지는데, **읽는 쿼리가 대표 프레임 1행 조회 하나뿐**이다. 그 JSON 안에 33개 랜드마크의 x·y·z 가 다 있으므로 **AI 가 내린 각도 판정을 DB 안에서 다시 계산해 대조**할 수 있고, 그것이 #217(3D 각도가 z 추정에 의존한다 — 실영상에서 얼마나 갈리는지 모른다)에 **AI 서버를 다시 돌리지 않고** 답하는 길이다. 카드가 되려면 «왜 raw 를 저장하는가» 에 답이 생기는 것이 핵심이고, 정직하게 적어야 할 한계(측정 데이터가 실사용자가 아님, 각도 수식이 두 곳에 생김)가 둘 있다.

---

## 1. 사실 — 지금 상태

### 1-1. 저장 모양

| 항목 | 값 | 근거 |
|---|---|---|
| JSON 모양 | `[{index, x, y, z, visibility} × 33]`, 행당 ≈2.3KB(off-page) | [`pose.py:53`](../../ai-server/app/api/endpoints/pose.py) `_landmarks_to_json` · realmysql §4 ④ |
| 무릎 3점 | LEFT 23·25·27 / RIGHT 24·26·28 (hip·knee·ankle) | [`constants.py:12-17`](../../ai-server/app/utils/constants.py) |
| 이미 컬럼으로 빼둔 스칼라 | `sync_rate`(rep 단위 상수) · `smoothed_knee_angle`(좌우 평균 무릎각을 3프레임 평활, **3D**) · `rep_number` | V1 DDL 주석 · [`squat_analyzer.py:148-158`](../../ai-server/app/core/squat_analyzer.py) |
| 저장 밀도 | R=5 윈도우당 `sync_rate` 최저 프레임 1개만 저장 — **원본 밀도 복구 불가** | pose-ingest-downsampling.md §7 |
| 보존 | 이번 달 + 1개월, 그 뒤 `DROP PARTITION` | `application.yml` `retention-buffer-months: 1` |
| PK | `(id, created_at)` — `created_at` = 세션 시작 시각 앵커(V9) → 앵커를 조건에 넣어야 프루닝 | `PoseDataRepository` 클래스 주석 |

### 1-2. 읽는 곳

| 쿼리 | 용도 | JSON 을 읽나 |
|---|---|:--:|
| `findJointCoordinatesById(id, anchor)` | worst rep 대표 프레임 1행 → 앱이 그릴 자세 | ○ (1행) |
| `MAX(rep_number)` / `MAX(timestamp_sec)` | 재부착 복원 | × |
| `AVG(sync_rate)` | 세션 집계 | × |
| `PoseFrameProjection` | 리포트 계산 — *"`jointCoordinates` 를 싣지 않는 방침"* | × |

📌 32 문서 P5 의 "Tier 0 이 없다" 는 **낡았다** — `findJointCoordinatesById` 가 있다. 이 문서가 그 정정이다. 그래도 P5 의 본질은 남는다: **JSON 안의 값으로 무엇을 계산하는 쿼리는 0개**다. 한 달 치를 저장하는 이유는 «시연 중 재부착·대표 프레임 1장» 이고, 나머지 99.9% 행은 안 읽힌 채 드롭된다.

### 1-3. #217 의 상태 — 잴 수 있게 됐는데 안 쟀다

- 3D(x,y,z)로 각도를 내는데 z 는 MediaPipe 단안 깊이 추정치다. 합성 스틱 피겨에서 서있는 자세가 3D 108° / 2D 178° 로 갈렸다. 그 각도가 rep 카운트 문턱(100/150)·`deepest_knee`·피드백의 입력 전부다.
- 이슈가 «잴 수 있는 형태» 로 적어둔 것: 같은 프레임 시퀀스에서 3D·2D 를 나란히 → ① 분포 차 ② rep 카운트 차 ③ 문턱 근처 판정 뒤집힘.
- 선행 조건이던 #196(쓸 수 있는 스쿼트 입력 없음)은 08-17 에 닫혔고, 09-03 에 스톡 영상 9편으로 정확도 실측도 했다([`measure_pose_real_footage_accuracy.py`](../../loadtest/measure_pose_real_footage_accuracy.py)). **그런데 #217 은 그대로다** — 그 실측은 해상도·디코드 축이었지 3D/2D 축이 아니었다.

### 1-4. 데이터 자산과 한계

| 소스 | 있나 | 분포 | 한계 |
|---|:--:|---|---|
| 합성 rig(`pose_data_scale`, 1억 행) | ○ | **템플릿 1개 복제** — 3D/2D 차이가 값 하나로 나옴 | 판정축 측정 불가. DB 비용축(I/O·프루닝)만 가능 |
| Pexels류 스톡 영상 9편(`~/Downloads`, 저장소 밖 — #266) | ○ | 실제 사람·실제 카메라. 15프레임/편 = 125 앵커 프레임 실측 이력 | 이 앱의 캡처 파이프라인(전면 셀피)이 아님. **실사용자 아님** |
| E1 통주행(`e1_walkthrough.py`)으로 위 영상을 풀스택에 흘려 `pose_data` 에 적재 | 가능 | 위와 같음 + R=5 다운샘플 적용된 실제 저장 모양 | #714(페이싱 없음 → RATE_LIMITED 로 프레임 잘림) 미해결 |
| 1차 사용자 테스트(10/12~16, [`34-user-test-scenario.md`](../tasks/34-user-test-scenario.md)) | 예정 | 진짜 실사용자·진짜 파이프라인 | N=5~10명. **이 문서의 측정보다 뒤**이고 소셜 화면 결정(34 §7 ①)에 종속 |

---

## 2. 왜 이것이 카드 후보인가 — 그리고 무엇과 다른가

| 기준 | 이 후보 | 비고 |
|---|---|---|
| 순진한 상태가 «깨진» 곳 | 1억 행 저장 · 읽기 0 · 보존 기간 근거 없음(32 문서 P5 "TTL 을 며칠로 잡을지에 근거가 없다") · 판정 축(3D)이 미검증인데 그 위에 rep 카운트가 서 있음 | 성능이 아니라 **데이터 생애주기·정합성**의 결손 — DBA 축([[user_career_target]] 의 «백업·복제·무중단 DDL» 옆자리) |
| before/after 로 닫히나 | «raw 를 왜 저장하나» → «판정 기준이 바뀌면 AI 재실행 없이 과거 세션을 DB 안에서 재채점한다» 로 답이 생김. #217 이 수치로 닫힘 | 숫자는 §5 측정에서 |
| 기존 JSON 카드(#760)와 겹치나 | 아니다. #760 은 «`JSON_TABLE` 비용은 파싱이 아니라 조인 순서» — 읽기 축 튜닝. 이건 **write-only 데이터에 용도를 만든 것** | 같은 함수를 쓰지만 교훈이 다름 |
| off-page JSON 카드와 겹치나 | 아니다. 그건 «8.6GB 작업셋은 warm 해도 675초» — 교차 세션 불가의 근거. 이 후보는 그 결론을 **전제**로 세션 1개 범위에만 선다 | |

정직하게: 이 카드는 «쿼리가 몇 배 빨라졌다» 류가 아니다. 헤드라인은 **«저장만 하던 데이터로 AI 판정을 검증했다»** 이고, DB 기술은 그 도구다(JSON 경로 접근·파티션 프루닝·off-page 비용 경계). 면접에서 «DB 성능 카드» 로 팔면 어긋난다.

---

## 3. 만들 수 있는 쿼리 — 세션 1개 범위

교차 세션(Tier 2, generated column + 인덱스)은 열지 않는다 — realmysql §303 «실수요 0 + 합성 분포 균일 → 선택도 실험 불가» 판단 유효, 그리고 8.6GB 작업셋 675초 실측이 즉석 조회를 부정한다.

| # | 쿼리 | 카드 후보 | 이유 |
|:--:|---|:--:|---|
| **①** | **3D·2D 무릎각 재계산 + 저장된 `smoothed_knee_angle` 대조** | **○** | #217 에 직접 답. §2 |
| ② | 좌우 무릎각 차(비대칭) 프레임별 분포 | ①의 부산물 | 현 컬럼은 좌우 **평균**이라 비대칭이 사라져 있음. **판정이 아니라 분포 측정으로만** — 임계값 없음([[feedback_no_arbitrary_threshold_values]]) |
| ③ | 프레임별 평균 `visibility`, 관절별 최저 | ①의 부산물 | 실기기 카메라 품질을 숫자로. 사용자 테스트 결함 분석 재료 |
| ④ | 하체 6점만 추린 JSON 재구성(rep 재생 전송량) | × | 33→13 트림 −60.9% 는 이미 실측(realmysql §4). 새 교훈 없음 |
| ⑤ | rep 재생(`session_id, rep_number ORDER BY timestamp_sec`) | × | JSON 검색 아님. 인덱스 `(session_id, timestamp_sec)` 로 이미 됨 |

### ① 의 골격 (MySQL 8.0)

```sql
-- 세션 1개: 앵커(created_at)를 반드시 넣는다 — PK 가 (id, created_at) 이라 앵커 없이는 14 파티션 전부 탐색
WITH pt AS (
  SELECT id, timestamp_sec, rep_number, smoothed_knee_angle,
         joint_coordinates->'$[23].x' AS hx, joint_coordinates->'$[23].y' AS hy, joint_coordinates->'$[23].z' AS hz,
         joint_coordinates->'$[25].x' AS kx, joint_coordinates->'$[25].y' AS ky, joint_coordinates->'$[25].z' AS kz,
         joint_coordinates->'$[27].x' AS ax, joint_coordinates->'$[27].y' AS ay, joint_coordinates->'$[27].z' AS az
  FROM pose_data
  WHERE session_id = :sessionId AND created_at = :sessionAnchor
)
SELECT id, timestamp_sec, rep_number, smoothed_knee_angle,
       DEGREES(ACOS(GREATEST(-1, LEAST(1,
         ((hx-kx)*(ax-kx) + (hy-ky)*(ay-ky))
         / (SQRT(POW(hx-kx,2)+POW(hy-ky,2)) * SQRT(POW(ax-kx,2)+POW(ay-ky,2)) + 1e-8)
       )))) AS left_knee_2d,
       DEGREES(ACOS(GREATEST(-1, LEAST(1,
         ((hx-kx)*(ax-kx) + (hy-ky)*(ay-ky) + (hz-kz)*(az-kz))
         / (SQRT(POW(hx-kx,2)+POW(hy-ky,2)+POW(hz-kz,2)) * SQRT(POW(ax-kx,2)+POW(ay-ky,2)+POW(az-kz,2)) + 1e-8)
       )))) AS left_knee_3d
FROM pt
ORDER BY timestamp_sec;
```

- `GREATEST/LEAST` 는 `angle_calculator.py` 의 `np.clip(cosine, -1, 1)` 과 같은 자리 — 부동소수 오차로 `ACOS` 가 NULL 을 내는 것을 막는다. `1e-8` 도 원본과 같다.
- `$[23]` 은 **배열 위치 = 랜드마크 index** 라는 가정이다. `_landmarks_to_json` 이 MediaPipe 순서 그대로 직렬화하므로 성립하지만, 측정 전 `JSON_EXTRACT(jc,'$[23].index') = 23` 을 전 행에서 한 번 확인한다. 깨지면 `JSON_TABLE(... FILTER index)` 로 바꾼다.
- 오른쪽(24·26·28)은 같은 식. `smoothed_knee_angle` 과의 대조(«좌우 평균 → 3프레임 평활»)는 운영 저장 모양(R=5 다운샘플)에서는 **정확히 재현 못 한다** — 원본 5프레임 중 1개만 남아 평활 창이 다르다. 측정 적재를 R=1 로 하면 재현된다(§4-2). 어느 쪽이든 #217 의 본 측정은 **같은 JSON 에서 나온 3D vs 2D** 끼리 비교하므로 저장값·다운샘플에 안 기댄다.

---

## 4. 분기점 — 결정이 필요한 것

### 4-1. 각도 계산을 어디에 두나

| | 후보 | 장점 | 단점 |
|:--:|---|---|---|
| a | **SQL 네이티브 쿼리** (측정·검증 전용, `loadtest/` 스크립트 + 필요 시 `@Query(nativeQuery)`) | 포폴 주제가 «DB 안에서» 라 서사와 일치. Spring 을 안 거치고 mysql 클라이언트로 바로 돎. JSON 경로 접근·프루닝이 그대로 카드 재료 | 각도 수식이 `angle_calculator.py` 와 SQL 두 곳. 평활·rep 상태기계는 SQL 로 재현 안 함(안 하는 게 맞음 — 검증이지 판정이 아니다) |
| b | Spring 배치 — JSON 을 Java 로 읽어 계산 | 수식 1:1 이식이 쉬움. 정식 backfill 경로로 승격하기 쉬움 | 세션 전체 JSON 을 통째 읽음(2.3KB × 행수). «DB 안에서» 서사가 약해짐 — 그냥 Java 코드 |
| c | AI 서버에 backfill 엔드포인트 | 수식 중복 0 | **아키텍처 위반** — AI 는 DB 를 안 본다(결합면은 gRPC 뿐, [`../architecture/`](../architecture/)). 좌표를 다시 AI 로 보내는 것은 실시간 경로의 재사용이지 backfill 이 아님 |

**추천 a.** 단서: «검증·측정 전용, 실시간 판정은 AI» 를 쿼리 주석과 카드에 못박는다. 수식 중복은 단점이 맞고, 그 대신 §3 의 «`smoothed_knee_angle` 대조로 수식 자기검증» 이 중복의 안전장치다.

### 4-2. 측정 데이터를 어디서 얻나

| | 후보 | 장점 | 단점 |
|:--:|---|---|---|
| a | **스톡 영상 9편 → E1 통주행으로 풀스택 적재** | 실제 저장 모양(앵커·파티션) 그대로. 09-03 실측과 같은 소재라 비교 가능. `pose-data.downsample-window` 는 yml 주석이 «측정 손잡이» 로 못박은 값이라 **R=1 로 적재하면 원본 밀도**가 남아 §3 의 평활 대조가 정확해진다 | #714 페이싱 미해결 — 프레임이 RATE_LIMITED 로 잘림(11→7). ㄱ안(`--interval`)이면 작지만 #714 자체가 안 3개 미결. 영상이 짧다(3.4~39초, 350ms 페이싱이면 편당 ~10~117 프레임) — 9편 합쳐 수백 프레임이라 **5-1 의 표본이 작다**는 걸 적어야 함 |
| b | 오프라인 로더 — 영상 → MediaPipe → `squat_analyzer` 로 컬럼값까지 계산 → 직접 INSERT | #714 무관. 다운샘플을 끄고 원본 밀도로 넣을 수도 있음(평활 대조가 정확해짐) | «실제 파이프라인을 거친 데이터» 가 아니게 됨. 로더 코드가 하나 더 생김 |
| c | 10월 사용자 테스트 데이터 | 진짜 실사용자·전면 셀피·이 앱의 캡처 | 이 측정보다 **뒤**(10/16 이후). 34 §7 ① 결정에 종속. N 이 작음 |

**추천 a 지금, c 로 보강.** a 의 #714 는 ㄱ안(`--interval`) 이면 루프에 sleep 하나라 먼저 고친다(별도 PR — 단 #714 의 안 선택도 그때 confirm). 적재는 R=1. 카드에는 «스톡 영상 = 실제 사람이지만 실사용자·이 앱의 카메라가 아니다» 를 적고, c 가 생기면 같은 쿼리로 재실행해 표를 한 줄 늘린다. b 는 «파이프라인을 안 거쳤다» 가 면접에서 찔리는 자리라 뺀다.

### 4-3. 측정 1회로 끝내나, backfill 경로로 승격하나

| | 후보 | 언제 |
|:--:|---|---|
| a | **측정 1회 + 문서** — `loadtest/measure_pose_json_backfill.sh` + 결과 폴더 + 이 문서 §7 | 지금 |
| b | 정식 경로 — «판정 기준 변경 → 과거 세션 재채점» API/배치 | #217 의 답이 «3D 가 틀렸다(문턱과 자가 다르다)» 로 나와서 **실제로 기준을 바꿀 때** |

**추천 a.** b 는 답이 나오기 전엔 죽은 코드다. 다만 카드에는 «b 의 원형이 이 쿼리다 — 기준이 바뀌면 이 경로로 재채점한다» 를 적어 raw 보존의 근거로 삼는다. 그러면 TTL(현재 1개월 버퍼)의 근거도 «재채점 가능 창» 으로 서술할 수 있다 — **단 그 기간 값 자체는 이 측정이 안 정한다**(임의 기준값 금지).

---

## 5. 측정 설계 (4-1 a · 4-2 a 가정)

두 축을 **따로** 잰다 — 판정축은 결정론적(저장된 JSON 이 고정)이라 반복이 필요 없고, DB 비용축은 버퍼풀·캐시 때문에 반복이 필요하다([[feedback_measure_design_needs_repeats]]).

### 5-1. 판정축 (#217) — 세션 9개(영상당 1)

| 지표 | 정의 | 결과 형태 |
|---|---|---|
| 3D−2D 차 분포 | 프레임별 `left_knee_3d − left_knee_2d`, 좌·우 각각 | 세션별 p50/p90/max, 전체 히스토그램 |
| 문턱 뒤집힘 | 3D 로는 `<100`(BOTTOM) 인데 2D 로는 아닌 프레임 수, `>150`(STANDING) 도 같이 | 세션별 건수 / 전체 프레임 수 |
| rep 카운트 차 | 3D·2D 각각으로 상태기계를 **오프라인(Python)** 에 돌린 카운트 — SQL 로는 안 함 | 세션별 (3D, 2D, 저장된 MAX(rep_number)) |
| 좌우 비대칭(②) | `|left − right|` 프레임별 | 분포만. 판정 없음 |
| 자기검증 | 재계산 3D 좌우 평균 → 3프레임 평활 vs 저장 `smoothed_knee_angle` 오차 | p50/max — 수식이 맞다는 증거. R=1 적재면 반올림(소수 2자리) 안에서 0 이어야 하고, 아니면 수식이나 `$[i]` 가정이 틀린 것 |

📌 «어느 쪽이 맞다» 는 이 측정이 못 정한다 — 정답지가 없다(#256 의 «지터 3.5°» 문제와 같은 층). 결과는 **차이의 크기와 문턱 근처 체류 비율**까지이고, 그 다음(문턱을 2D 로 다시 정하나)은 AI 축의 별도 분기점이다.

### 5-2. DB 비용축 — 합성 rig 로 가능

| 셀 | 비교 | 왜 |
|---|---|---|
| 앵커 유무 | `WHERE session_id=?` 만 vs `+ created_at=?` | 프루닝: `EXPLAIN` partitions 컬럼 14 vs 1. 클래스 주석의 주장을 이 쿼리로 재확인 |
| 접근 방식 | `->'$[23].x'` 6점 vs `JSON_TABLE(... '$[*]')` 33행 펼침 후 3행 필터 | 같은 답을 내는 두 문법의 비용 차. 파싱은 한 번이라 차이가 작을 것으로 **예상**하나 잰 적 없음 |
| 세션 크기 | 저장 행수 100 / 1,000 / 10,000 (다운샘플 후) | off-page 페치가 선형인지. 675초 카드의 «세션 1개면 0.46초» 를 이 쿼리로 |
| cold/warm | 각 셀 버림 1판 + 본 3판, `Innodb_data_read` 바이트 병기 | hit율 공식은 read-ahead 로 거짓(realmysql §5 캐비엇) |

절대 ms 를 인용할 땐 `calib cpu` 를 병기한다([[project_nonrepro_axis_b_closed]] 규칙 ㉠). 로컬 박스는 상대·델타만.

### 5-3. 산출물

- `loadtest/measure_pose_json_backfill.sh`(5-2) · `loadtest/measure_pose_json_angle_axis.py`(5-1 의 오프라인 rep 카운트) — 이름은 결정 뒤 확정
- `loadtest/results/pose-json-backfill-YYYY-MM-DD/` — `result.json` + README
- 이 문서 §7 에 결과, #217 에 코멘트, 32 문서 P5 에 «Tier 0 있음» 정정 링크

---

## 6. 정직하게 적을 것 (카드에 그대로 들어간다)

1. **실사용자가 아니다** — 스톡 영상. 사용자 테스트 데이터로 재실행하기 전까지 «실사용에서 X° 갈린다» 는 말 금지.
2. **수식이 두 곳** — SQL 은 검증용 사본. 실시간 판정은 여전히 `angle_calculator.py`. 둘이 어긋나면 SQL 이 틀린 것으로 본다.
3. **평활·rep 상태기계는 SQL 로 안 한다** — 그건 판정이고, 이 쿼리는 재계산이다. rep 카운트 차는 Python 으로 따로.
4. **교차 세션은 안 한다** — 675초 실측이 이유. «세션 1개 범위에서만 성립하는 backfill» 이 이 카드의 경계다.
5. **TTL 값은 이 측정이 안 정한다** — «재채점 가능 창» 이라는 *의미*가 생길 뿐, 몇 개월인지의 근거는 따로 필요하다.
6. 합성 rig 는 5-2 에만 쓴다 — 5-1 에 쓰면 값 하나짜리 히스토그램이 나온다.

---

## 7. 결과

(측정 뒤 기록)

---

## 8. 미결 체크리스트

- [x] 4-1 계산 위치 — **a(SQL, 검증 전용)** 채택 2026-09-19
- [x] 4-2 데이터 — **a(스톡 9편 → E1 통주행, R=1 적재)** 채택 2026-09-19. #714 는 PR #777(ㄱ안). 10월 사용자 테스트 c 로 보강
- [x] 4-3 승격 — **a(측정 1회 + 문서)** 채택 2026-09-19. b 는 #217 답 뒤
- [ ] 카드 위치 — one-pager «그 외 실측» vs 별도 카드 (측정 결과 보고 정한다)
- [ ] 32 문서 P5 «Tier 0 없음» 정정 — 이 문서 §1-2 로 링크
- [ ] PR #777 머지 → 풀스택 통주행으로 «전송 = 판정» 확인 → 9편 적재(R=1) → §5 측정 → §7
