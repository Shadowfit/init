# Decision: 운동 «세트» 도입 (BE-09) — 경계·번호·저장 모양

상태: 🔶 **분기점. 결정 전.** 세 갈래(§2 경계 · §3 rep 번호 · §4 저장 모양)가 **한 덩어리라 따로 정하면 서로 어긋난다.**
작성: 2026-09-10
대상: `docs/tasks/22-backend-tasks-detail.md` §BE-09(🟦 보류, *"새 운동 추가 시점"* 의존)를 실제 설계안으로 바꾸는 것. 2회차 종목 확장(런지)과 함께 보류가 풀린다.
연관: [`report-aggregation.md`](./report-aggregation.md)(결정 5 — `setInfo` 1세트 고정의 출처) ·
[`report-read-path.md`](./report-read-path.md)(§215 — `PoseFrameProjection` 확장 예고) ·
[`pose-batch-idempotency-implementation.md`](./pose-batch-idempotency-implementation.md)(`uk_pose_event` 를 만든 문서) ·
[`online-ddl-vs-blocking-alter.md`](./online-ddl-vs-blocking-alter.md) · [`exercise-code-identity.md`](./exercise-code-identity.md)(같은 2회차지만 **독립** — §6) ·
[[project_squat_first]] · [[feedback_no_arbitrary_threshold_values]]

> 🟢=제안, 🔶=열림, ❌=스코프 밖. 결정 ✅ 는 사용자 confirm 후.

---

## 0. 🔴 먼저 — 세트가 주간 리포트를 «조용히» 바꾸는 지점

이 문서에서 가장 먼저 알아야 할 사실. 주간 요약의 B층 쿼리가 이렇게 생겼다
(`WeeklySummaryQueryRepositoryImpl.java:135-141`):

```sql
CROSS JOIN JSON_TABLE(r.detailed_analysis, '$.repTrend[*]'
       COLUMNS (rep_number INT PATH '$.repNumber',
                sync_rate  DOUBLE PATH '$.syncRate')) jt
WHERE r.member_id = :memberId AND s.start_time >= :from AND s.start_time < :to
GROUP BY jt.rep_number
ORDER BY jt.rep_number
```

**`GROUP BY rep_number`** — "이번 주 N번째 rep 의 평균 싱크로율" 곡선이고, 이게 성립하는 전제는
`rep_number` 가 **세션 전체 연번**이라는 것이다(`RepSyncRateDto.java:23` 주석: *"1부터. 세션 전체
기준 연번(세트 도입 시 재검토)"*).

세트를 넣으면서 rep 번호를 세트마다 리셋하면 이 쿼리는 **1세트 3번째와 2세트 3번째를 한 통에
넣는다. 에러가 안 난다.** 숫자가 나오는데 다른 것을 센 숫자다.

> ⚠️ **다만 이것을 «리셋하면 안 된다» 의 근거로 쓰면 안 된다.** 초안이 그렇게 적었다가 §3-1 에서
> 바로잡았다 — 리셋은 이 쿼리를 **깨뜨리는 게 아니라 재해석시키고**, 재해석된 쪽이 더 쓸모 있는
> 지표일 수 있다. 여기서 확실한 것은 하나뿐이다: **리셋을 고르면 이 쿼리를 의식적으로 다시 써야
> 한다.** 안 쓰면 조용히 틀린다.

`report-read-path.md` §215 가 이 계열을 이미 예고했지만 `PoseFrameProjection` 만 지목했다 —
**주간 쿼리는 그 문서보다 나중에 생겨서 목록에 없다.** 영향 목록은 §5 로 새로 만든다.

---

## 1. 현재 상태 — 세트 개념이 코드에 «없다» 는 것의 실제 모양

| 위치 | 현재 |
|---|---|
| `exercise_sessions` | `total_reps` 만. `set_count` 없음 |
| `pose_data` | `rep_number` 만. `set_index` 없음 |
| `PoseDataRequest`(proto 양쪽) | 세트 필드 없음 |
| `global/util/SetSummaryFormatter` | **`FIXED_SET_COUNT = 1` 하드코딩** |

`SetSummaryFormatter` 는 이미 이 작업을 기다리도록 쓰여 있다:

> 세트 개념이 아직 스키마에 없어 세트 수는 1로 고정한다. … **BE-09(세트 도입) 때
> `Session.setCount` 로 교체할 자리도 여기 한 곳이 되도록 유지할 것.**

과거에 `ReportService` 는 `"1세트"`, `SessionService` 는 `"0세트"` 로 각자 리터럴을 들고 있어
**같은 세션이 화면마다 다르게 보이는 결함**이 있었다([#69](https://github.com/Shadowfit/init/issues/69)).
지금은 한 곳으로 모여 있으므로 **표기 쪽은 이 작업에서 문제가 아니다.**

---

## 2. 🔶 분기 A — 세트 경계를 누가 정하나

| 안 | 방식 | 파급 |
|---|---|---|
| **ㄱ. 사용자가 누른다** | 앱에 "세트 종료" 버튼 | FastAPI 변경 **0**. Spring 은 엔드포인트 하나. 가장 싸고 **판정이 틀릴 일이 없다** |
| **ㄴ. AI 가 휴식으로 자동 판정** | rep 이 N초 이상 안 나오면 세트 종료 | AI-03. 🔴 **N 의 근거가 없다** — 아래 참조 |
| **ㄷ. 사전 계획** | 세션 시작 시 "3세트 × 10회" 선언 | 세트가 **사후 관측이 아니라 사전 계획**이 된다. 추천 API(`/recommendations/next-session`)가 이미 강도·볼륨을 내놓으므로 붙일 자리가 있다 |

### 2-1. 🔴 ㄴ이 필요로 하는 임계값에 근거가 없다

이 저장소에 "세트 간 휴식 최대 90초"라는 수가 이미 있다(`MemberService.java:387` 근방). 그런데
그 값은 **세션 타임아웃을 정하려고 잡은 하한**이지 세트 경계를 가르려고 잰 값이 아니다:

> 임계값의 기준은 배치 간격(~3~4초)이 아니라 **세트 간 휴식(최대 90초)** 이다. 짧게 잡으면 …

같은 90초를 세트 경계 판정에도 쓰면 **두 판정이 같은 신호를 다투게 된다** — "휴식 중이라 세션을
안 끊는다"와 "휴식이라 세트를 끊는다"가 동시에 성립해야 한다. 값을 새로 정하려면 재야 하고,
재지 않고 정하면 이 저장소가 금지한 임의 기준값이 된다.

> 🟢 제안: **ㄱ 또는 ㄷ.** ㄴ은 근거 없는 수가 하나 필요하고, 틀렸을 때 **에러 없이 틀린 세트
> 구분**이 나온다 — #147(런지를 스쿼트로 채점)과 같은 실패 모양이다.

---

## 3. 🔶 분기 B — rep 번호를 세트마다 리셋하나

`rep_number` 는 표시용 번호가 아니라 **자연키의 일부**다:

```sql
-- V6__add_pose_data_idempotency_key.sql:77
ALTER TABLE pose_data
    ADD UNIQUE KEY uk_pose_event (session_id, rep_number, timestamp_sec, created_at);
```

(`created_at` 이 낀 것은 파티션 테이블의 유니크 키가 파티션 표현식 컬럼을 포함해야 하기 때문 —
*"빠뜨린 방어가 아니라 파티셔닝을 얻은 대가"*.)

그래서 이 질문은 "화면에 몇 번이라고 쓸까"가 아니라 **"프레임 하나를 무엇으로 식별할까"** 다.

### 3-1. 리셋 쪽 근거 — 초안이 약하게 다뤘던 것

**도메인은 리셋이 자연스럽다.** 아무도 "27번째 rep"이라고 말하지 않고 "2세트 5회차"라고 한다.
초안은 구현 비용으로 이 사실을 눌렀는데, 그건 한쪽으로 기운 서술이었다. 리셋 쪽 근거는 셋이다:

1. **자연키는 도메인을 따르는 게 원칙이다.** 저장 형태가 도메인과 어긋나면 읽는 코드마다 변환이
   붙고, 그 변환을 빠뜨린 곳이 버그가 된다.
2. **AI 쪽 소스가 이미 세트 스코프일 수 있다.** 분석기가 세트 단위로 rep 을 센다면 Spring 이
   통번호로 바꿔 저장했다가 화면에서 되돌리는 **왕복**이 생긴다. → §7 확인 항목.
3. **§0 의 주간 곡선이 오히려 더 의미 있어진다.** 세션 통번호 기준의 "27번째 rep"은 세션 길이가
   제각각이라 **뒤로 갈수록 표본이 급감하는** 축이다. 세트 내 번호 기준이면 "세트 안에서 몇
   번째부터 무너지나" — **피로 곡선**이 되고, 그게 원래 보고 싶던 것에 더 가깝다.

### 3-2. 세 안

| 안 | 저장 | `uk_pose_event` | 화면의 "2세트 5회차" |
|---|---|---|---|
| **ㄱ. 리셋** | `rep_number` 를 세트 내 번호로 + `set_index` | **재구성 필요** (파티션 테이블 유니크 인덱스) | 그대로 씀 |
| **ㄴ. 유지 + 라벨** | `rep_number` 통번호 + `set_index` 를 부가 라벨로 | 안 건드림 | **계산으로 유도** |
| **ㄷ. 둘 다 저장** | `rep_number` 통번호 유지 + `set_index`·`rep_in_set` 추가 | **안 건드림** | 그대로 씀 |

**두 표현은 정보량이 같고 서로 유도된다** — 어느 쪽을 저장하든 화면에는 "2세트 5회차"를 띄울 수
있다. 차이는 **변환을 어디서 하느냐**와 **멱등 키를 건드리느냐**뿐이다.

**ㄷ 이 절충안이다.** 통번호를 멱등 키 안에 그대로 두고 도메인 번호를 별도 컬럼으로 같이 넣는다.
비정규화지만 — 멱등 키를 안 건드리고, 읽는 쪽 변환이 없고, **옛 행 백필이 자명하다**
(세트 개념이 없던 시절의 모든 세션은 «1세트» 로 해석하면 정합적이므로
`set_index=1, rep_in_set=rep_number`). 대가는 두 컬럼이 어긋날 수 있다는 것(쓰는 곳이
`PoseDataService` 한 군데라 실질 위험은 낮다)과 `pose_data` DDL 이다 — 그건 §4-2 로.

> 🔶 열림. **§7의 «AI 가 rep 을 어느 단위로 세는가» 를 확인하면 상당 부분 좁혀진다** —
> 소스가 이미 세트 스코프면 ㄱ·ㄷ 이 자연스럽고, 세션 스코프면 ㄴ 이 자연스럽다.

---

## 4. 🔶 분기 C — 어디에 저장하나

### 4-1. 두 안

| 안 | 모양 | 언제 맞나 |
|---|---|---|
| **컬럼 하나** | `exercise_sessions.set_count` | 리포트에 "3세트 × 10회" 표기만 하면 충분할 때. `SetSummaryFormatter` 한 줄이 바뀐다 |
| **세트 표 신설** | `session_sets(session_id, set_index, reps, avg_sync_rate, rest_seconds, ...)` | **세트별 싱크로율**을 보여줄 때. 세트 간 휴식 시간도 여기 |

> 🟢 제안: **`session_sets` 표.** 컬럼 하나로는 *"3세트째부터 자세가 무너진다"* 를 못 보여주는데,
> 그게 세트를 도입하는 거의 유일한 도메인 이유다. 세트 «수» 만 세려면 도입할 필요가 없다.

### 4-2. 🔴 `session_sets` 가 여는 두 번째 길 — `pose_data` 를 안 건드릴 수 있다

`session_sets` 가 각 세트의 **rep 범위**를 들고 있으면, 프레임은 기존 `rep_number` 만으로 세트에
귀속된다. 즉 **`pose_data` 에 컬럼을 안 대도 된다.**

이게 중요한 이유는 그 표가 **파티션 테이블**이기 때문이다:

```sql
PRIMARY KEY (id, created_at)
PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (...)
```

이 저장소는 이 표의 DDL 에서 이미 한 번 데였다 — 무중단 DDL 실측 §1:

> `ERROR 1845 (0A000): ALGORITHM=INPLACE is not supported for this operation. Try ALGORITHM=COPY.`

그리고 그때의 팔 A(차단 ALTER)는 1,000만 행에서 **DDL 이 도는 내내(≈69초) 쓰기를 전 구간
차단**했다.

> ⚠️ **그 숫자를 이번 DDL 에 인용하면 안 된다.** 그건 `PARTITION BY RANGE` 였고 이번은
> `ADD COLUMN` 이라 **DDL 종류가 다르다.** MySQL 8.0 은 특정 버전부터 파티션 테이블의
> `ADD COLUMN` 에도 `ALGORITHM=INSTANT` 를 지원하는 것으로 알려져 있으나 — **이 저장소는
> «될 줄 알았는데 서버가 거절한» 경험이 있으므로 실제 서버에 던져 확인하기 전에는 미지로 둔다.**
> `docker-compose.yml` 은 `mysql:8.0` 태그라 정확한 패치 버전도 실행 시점에 확인해야 한다.

**정리하면 DDL 비용은 §3·§4 결정에 종속이다:**

| 조합 | `pose_data` DDL |
|---|---|
| §4 `session_sets` + §3 ㄴ(유지) | **없음** |
| §4 `session_sets` + §3 ㄷ(둘 다) | 컬럼 2개 추가 (INSTANT 여부 확인 대상) |
| §3 ㄱ(리셋) | 컬럼 추가 + **유니크 인덱스 재구성** — 가장 비쌈 |

> 포폴 관점에서 상충이 있다: **비용을 없애는 쪽(`session_sets`)과 무중단 DDL 실측의 두 번째
> 사례를 얻는 쪽이 반대 방향**이다. 「같은 표에 다른 종류의 DDL 을 던지면 판정이 달라지는가」는
> 좋은 실험 주제지만, 그걸 위해 필요 없는 컬럼을 만드는 것은 본말전도다.

---

## 5. 세트가 건드리는 곳 — 영향 목록

`report-read-path.md` §215 의 목록이 낡아서 여기서 새로 만든다.

| 대상 | 영향 | 조건 |
|---|---|---|
| `WeeklySummaryQueryRepositoryImpl:140` | `GROUP BY rep_number` 의 의미 | **§3 ㄱ(리셋) 일 때만.** ㄴ·ㄷ 이면 그대로 맞다 |
| `PoseFrameProjection` (현 4컬럼) | `setIndex` 추가 여부 | §3·§4 종속 |
| `detailed_analysis` JSON (`repTrend`·`worstSection`) | 세트 정보를 넣으면 **기존 행과 새 행의 JSON 모양이 달라진다** — 스키마 없는 컬럼의 대가 | 넣기로 할 때만 |
| `SetSummaryFormatter` | `FIXED_SET_COUNT` 제거 | **항상** (이 작업의 유일한 확정 변경) |
| worst 구간 계산 | 세션 전체 vs 세트 단위 | 🔶 별도 판단 — 도메인 질문이지 기술 질문이 아니다 |
| proto `PoseDataRequest` | 세트 필드 추가 | §4-2 에서 `pose_data` 를 안 건드리면 **proto 도 안 건드릴 수 있다** |

---

## 6. 순서 — 세트가 리포트 LLM 보다 먼저다

```
① 경계 방식(§2) ─┐
② rep 번호(§3)  ─┤ 한 덩어리. 따로 정하면 어긋난다
③ 저장 모양(§4) ─┘
        ↓
④ 세트 구현
        ↓
⑤ 리포트 LLM 착수 → 저장 필요 → V11 스키마 적용(초안 이미 있음)
```

**세트를 먼저 하는 이유**: 리포트 LLM 을 먼저 붙이면 주간 요약 «문장» 이 생성·저장되는데, 그 뒤에
세트가 들어와 `repTrend` 의 의미가 바뀌면 **이미 저장된 문장이 소급해서 틀려진다.** 반대 순서면
그 문제가 없다.

[`exercise-code-identity.md`](./exercise-code-identity.md) 와는 **순서 제약이 없다** — 그쪽은
`exercises`, 이쪽은 `pose_data`/`exercise_sessions` 라 겹치지 않는다.

---

## 7. 확인해야 열리는 것

- [ ] **AI 분석기가 rep 을 어느 단위로 세는가** — `squat_analyzer.StreamingSquatAnalyzer` 의 rep
      카운터가 세션 전체 연번인지, 세트 개념 없이 그냥 누적인지. §3 의 절반이 여기서 좁혀진다
- [ ] **`mysql:8.0` 의 실제 패치 버전과 파티션 테이블 `ADD COLUMN` 의 ALGORITHM 판정** (§4-2)
- [ ] **AI-03 협의** — `23-ai-tasks-detail.md` 가 *"BE-09 와 협의(proto / DB 양쪽)"* 로 걸어둔 항목.
      §2 에서 ㄱ·ㄷ 을 고르면 AI 쪽 작업이 사실상 사라지므로, 협의 내용 자체가 달라진다

---

## 8. 미결정 목록

- [ ] §2 세트 경계 (ㄱ 사용자 버튼 / ㄴ AI 자동 / ㄷ 사전 계획)
- [ ] §3 rep 번호 (ㄱ 리셋 / ㄴ 유지+라벨 / ㄷ 둘 다 저장)
- [ ] §4 저장 모양 (`set_count` 컬럼 / `session_sets` 표)
- [ ] §5 worst 구간을 세트 단위로 볼 것인가
- [ ] BE-09 보류 해제 시점 — 런지와 동시인가, 별도인가

> §2·§3·§4 는 **한 덩어리라 같이 정해야 한다.** 셋이 정해지면 이 문서에 DDL 초안을 붙인다.
