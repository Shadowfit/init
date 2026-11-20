# 런지·세트·종목 선택 — 백엔드 3항목의 분기점

> **상태**: ✅ 2026-09-22 사용자 confirm — §7 에 박제. §3 의 후보·트레이드오프는 기록으로 남긴다.
> **출처**: 캡스톤 디자인 I 1회차 보고(2026-09-22, 팀 HOMINI) «향후 구현 항목»
> — [백엔드] *운동 종목 선택 / 운동 세트(AI 연동) / 런지 횟수 측정*, [AI] *런지 피드백 / 운동 세트 / TTS*.
> **관련**: [`../usecase/README.md`](../usecase/README.md) B-06(세트)·B-05(런지), [`../tasks/22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) BE-09, [`tts-design.md`](./tts-design.md) §2.A.BT, [`../12-persona-difficulty.md`](../12-persona-difficulty.md), 이슈 [#147](https://github.com/Shadowfit/init/issues/147)·[#92](https://github.com/Shadowfit/init/issues/92)

## 0. 한 줄 요약

세 항목은 «백엔드 API 를 새로 짜는 일» 이 아니라 **스쿼트 하나를 전제로 박혀 있는 자리 6곳을 종목·세트가 들어갈 수 있게 여는 일**이다. 그중 절반은 AI 와 proto 를 같이 바꿔야 하므로, **무엇을 백엔드 혼자 먼저 할 수 있고 무엇을 AI 담당자와 한 PR 로 묶어야 하는지**를 먼저 가르는 게 이 문서의 목적이다.

## 1. 전제가 바뀌었다 — 09-11 결정과의 관계

[`../usecase/README.md`](../usecase/README.md) B-06(세트)·B-05(런지) 은 **2026-09-11 에 «이번 학기 제외»** 로 닫혔다. 이유는 하나였다 — *AI 쪽 세트 인지·런지 분석기가 이번 학기에 안 오니 백엔드만 하면 죽은 코드*.

1회차 보고 PDF 는 AI 파트 항목에 **런지 피드백 · 운동 세트** 를 명시했다. 그 전제가 뒤집힌 것이다. 다만 이 문서는 PDF 를 근거로 삼을 뿐 **AI 담당자가 실제로 무엇을·언제 내놓는지는 확인하지 않았다**. §5 의 첫 질문이 그것이다.

## 2. 지금 코드에 있는 것 — 스쿼트가 박힌 자리 (실측, 2026-09-22)

### 2-1. 종목 선택

| 자리 | 현재 | 근거 |
|---|---|---|
| 회원용 종목 목록 API | **없음.** `GET /admin/exercises` 만 있고 회원 경로는 `POST /exercises/sessions` 하나 | `ExercisesController.java:33,55` · `AdminExerciseController.java:39` |
| 프론트 종목 선택 | 라우트 param 없으면 `exerciseId = 1` 고정. «추후 종목 선택 UI 추가 시 정리» 주석 | `frontend/app/(tabs)/exercise.tsx:56` |
| 종목 식별자 | `exercises` 에 코드 컬럼 없음, `name` 이 한국어. 그래서 **DB id 를 3곳이 하드코딩** — AI `_EXERCISE_ID_TO_TYPE = {1: "squat"}`, 프론트 `exerciseTypeOf()` switch, 시드 `REPLACE INTO … (id, …)` | `analyzer_registry.py:38-49` · `exercise.tsx:44-52` · `V2__seed_master_data.sql:26-33` |
| 세션 시작 가드 | `analysis_supported=FALSE` 면 W007. 관리자 `PATCH /analysis-support` 는 기준 좌표 1건 이상일 때만 켜짐(W012) | `SessionService.java:104-108` · `ErrorCode.java:62,99` |

`analyzer_registry.py` 주석이 이미 이 결합의 약점을 적어뒀다 — *«시드가 바뀌면 이 표는 조용히 틀린다. 제대로 하려면 Spring 이 종목 코드를 실어 보내야 하는데 컬럼 추가 마이그레이션이 따라온다. 그건 별도 결정으로 둔다»*. 그 «별도 결정» 이 지금이다.

### 2-2. 세트

| 자리 | 현재 | 근거 |
|---|---|---|
| 스키마 | `Session.totalReps` 만. `setCount`·세트 표 없음. `pose_data` 에 `set_index` 없음 | `Session.java:56` · `V6__add_pose_data_idempotency_key.sql` |
| 화면 표기 | `SetSummaryFormatter.FIXED_SET_COUNT = 1` → 항상 «1세트 x N회». 주석이 *«BE-09 때 Session.setCount 로 교체할 자리»* 라고 못박음 | `SetSummaryFormatter.java:14` |
| proto — 이미 있는 것 | `FeedbackBatchRequest.set_no`·`is_final` (BT-SET 선반영). AI 는 `set_no=1, is_final=true` 한 번만 보냄(BT-NONE) | `proto/exercise.proto` §4 · `FeedbackLogService.java:92` 는 로깅만 |
| proto — 없는 것 | `AnalyzeRequest` 에 세트 목표 없음. `PoseDataRequest` 에 세트 없음. `SessionCompleteRequest` 에 세트별 요약 없음 | `proto/exercise.proto` |
| 세트 목표값 공식 | `targetReps = baseReps + (level-1)*2` (REHAB 5, 그 외 10), `restTimeSec = max(90-(level-1)*5, 30)` — `RecommendationService.buildRecommendation` 이 자바로 옮겨 `GET /recommendations/next-session` 에서 내고 있음. **세션 시작 흐름은 이 값을 안 씀**. `Session.difficultyLevel` 은 «세션 생성 흐름 어디서도 채워지지 않아 항상 1」(같은 파일 클래스 주석). **세트 수 공식은 어디에도 없음** | `RecommendationService.java:127-142` · `Session.java:71` |
| AI 세션 상태 | `SessionState` 에 세트 필드 없음. #784 로 `SquatCounter` FSM 이 들어왔지만 세트 경계는 모름 | `session_state.py:100-139` (origin/main) |

### 2-3. 런지

| 자리 | 현재 | 근거 |
|---|---|---|
| 카탈로그 행 | `id=2 '런지' LOWER`, `analysis_supported=FALSE`. 기준 좌표(`exercise_references`) 없음 → W012 로 켤 수 없음 | `V2__seed_master_data.sql:29-30` |
| 피드백 템플릿 | 3행, persona NULL. **스쿼트용 타입을 런지 의미로 재해석** — `HIP_HIGH` → «뒷무릎을 더 굽혀주세요», `KNEE_OUT` → «앞 무릎이 발끝을 넘지 않게». 타입 이름과 뜻이 어긋남 | `V2__seed_master_data.sql:61-65` · `FeedbackType.java` 8종 |
| 기준 좌표 추출 | `ExtractReferenceData` 가 **`analyze_video(path, "squat")` 하드코딩** → 런지 mp4 를 올려도 스쿼트 rep 분절로 기준이 만들어진다. `ExtractRequest` 에 종목 타입이 없어 AI 가 알 길도 없음 | `exercise_servicer.py:89` · `reference_builder.py:131` |
| 프레임 지표 | `PoseDataRequest.smoothed_knee_angle` = «좌우 무릎각 평균». 리포트 대표 프레임을 이 값 최소로 고름(«가장 깊게 앉은 순간»). 런지는 앞·뒷무릎이 다른 각도라 **평균의 의미가 없다** | `SessionAnalysisCalculator.java:154-182` · proto `PoseDataRequest.6` |
| 칼로리 | AI 가 `calories_burned=0.0` 고정 («추후») — 종목 무관하게 미구현 | `exercise_servicer.py:549` |
| 분석기 | `squat_analyzer.py`·`squat_counter.py` 뿐. 각도 정의표 `EXERCISE_ANGLES` 는 rep 카운팅과 무관(registry 주석) | `analyzer_registry.py` 모듈 주석 |

## 3. 분기점

### 3-A. «런지 횟수 측정» 을 백엔드가 맡는다는 게 무슨 뜻인가 — 가장 먼저 정해야 할 것

PDF 가 횟수 측정을 백엔드 열에 둔 게 의도인지 배치 편의인지에 따라 작업이 통째로 달라진다.

| 해석 | 내용 | 백엔드 작업 | 문제 |
|---|---|---|---|
| **A-1** AI 가 세고 백엔드는 저장·집계 | 스쿼트와 같은 파이프라인. AI 런지 분석기가 `rep_number` 를 찍어 보내면 `pose_data`·`total_reps` 에 그대로 들어감 | 종목 가정이 박힌 자리(§2-3) 를 여는 것뿐 | PDF 의 «백엔드: 런지 횟수 측정» 이 실제로는 «AI: 런지 피드백» 의 부속이 됨 — 발표 자료와 어긋나는지 확인 필요 |
| **A-2** 백엔드가 좌표로 재계산 | `pose_data.joint_coordinates` 로 서버 쪽 카운터를 돌림 | 자바 카운터 신설 | AI 와 **이중 판정**. 이미 [pose JSON 재검증 카드](./pose-json-backfill-query.md)(PR #783) 가 «저장된 좌표로 판정 재현» 을 다뤘고 z=0 재생에서 rep 3/6 이 갈렸다 — 서버 판정을 정답으로 삼을 근거가 없음 |
| **A-3** 좌/우 다리 분리 카운트 | 런지 고유 — 왼발 앞 N회·오른발 앞 N회. proto·DB·리포트에 «다리» 축 추가 | 계약 확장 + 리포트 | AI 가 좌/우를 판정해 주는 게 선행. 스쿼트엔 없는 축이라 `rep_number` 하나로는 못 실음 |

**추천**: A-1 을 기본으로, A-3 은 AI 담당자가 «좌/우를 판정한다» 고 확답할 때만. A-2 는 하지 않는다.

### 3-B. 종목 식별 — DB id 하드코딩 3곳을 어떻게 풀 것인가

| 안 | 내용 | 바뀌는 곳 | 트레이드오프 |
|---|---|---|---|
| **B-1** `exercises.code` 컬럼 + proto 에 `exercise_code` | `SQUAT / LUNGE / PLANK` unique 컬럼(V25). `AnalyzeRequest`·`ReattachRequest`·`ExtractRequest` 에 `string exercise_code` 추가. AI registry 가 코드로 분석기를 찾음. 프론트는 응답의 code 사용 | Flyway 1개 · 엔티티 · proto 3메시지 · AI registry · 프론트 switch 제거 | proto 변경이라 **AI 와 같은 PR**. 대신 3곳의 하드코딩이 한 번에 사라지고, 관리자가 종목을 추가해도 코드만 맞추면 됨 |
| **B-2** 현행 유지, AI 표에 `2: "lunge"` 추가 | 한 줄 | AI 만 | 가장 싸다. 그러나 registry 주석이 경고한 «시드가 바뀌면 조용히 틀림» 을 그대로 안고 감. 관리자 CRUD 로 만든 종목은 영원히 못 붙음 |
| **B-3** 지원 종목을 묻는 RPC (#147 ㄴ) | `ListSupportedExercises` 신설 → Spring 이 `PATCH /analysis-support` 때 AI 에 물어 검증 | proto RPC 1개 · Admin 서비스 | B-1/B-2 와 직교. W012(기준 좌표 유무) 가 «필요조건」 이었던 걸 «충분조건» 으로 만든다. 다만 RPC 하나 늘고 AI 다운 시 활성화가 막힘 |

**추천**: B-1. B-3 은 B-1 뒤 선택 사항.

### 3-C. 회원용 종목 목록 API

| 안 | 내용 | 비고 |
|---|---|---|
| **C-1** `GET /exercises` 신설 | `id, code, name, categoryId, categoryName, description, preferredUrl, expectedDurationMinutes, analysisSupported`(구현 기준으로 정정 — `hasReferenceVideo` 는 회원 화면에 쓸 값이 아니라 뺐다). `analysisSupported=false` 도 내려서 화면이 «준비 중» 을 그림 | 캐시하지 않는다 — 관리자 쓰기 5곳에 evict 를 빠짐없이 걸어야 하는 비용 대비 행 수가 한 자리(`ExerciseCatalogService` 주석) |
| C-2 프론트가 `/admin/exercises` 재사용 | — | ADMIN 권한 필요. 불가 |

지원 안 되는 종목을 목록에서 **빼는 것과 «준비 중» 으로 보이는 것** 중 어느 쪽인지는 프론트 결정 — API 는 플래그를 내리고 프론트가 고른다.

### 3-D. 세트 — 경계를 누가 알고, 어디에 저장하나

세트는 «rep 이 목표에 닿으면 끝나고 휴식 뒤 다음 세트」 라는 도메인 단위다. 경계를 아는 주체가 셋 중 하나여야 한다.

| 안 | 경계 주체 | 계약 | 트레이드오프 |
|---|---|---|---|
| **D-1** AI 가 인지 | Spring 이 세션 시작 시 `target_reps_per_set`·`target_sets` 를 `AnalyzeRequest` 에 실어 보냄 → AI 가 rep 이 목표에 닿으면 세트 종료, `PoseDataRequest.set_index` 를 찍고 세트 경계마다 `ReportFeedbackBatch(set_no)`. `SessionCompleteRequest` 에 `repeated SetResult{set_no, reps, avg_sync_rate, started_sec, ended_sec}` | proto 4메시지 확장(**AI 동시**). [`tts-design.md`](./tts-design.md) §2.A.BT 가 2026-05-25 에 이미 이 모양(BT-SET)으로 추천해 둔 것 | 휴식을 AI 가 알게 되므로 [#92](https://github.com/Shadowfit/init/issues/92)(휴식 중 프레임 낭비) 의 선행이 같이 풀림. 대신 AI 작업이 가장 큼 |
| D-2 백엔드가 사후 분절 | `pose_data` 의 `rep_number` + `timestamp_sec` 간격으로 «N초 이상 비면 세트 경계» | proto 무변경 | **휴식 간격 임계값에 근거가 없다** — 근거 없는 상수 금지 원칙에 걸림. 사용자가 세트 중간에 멈춰도 세트가 갈림. 실시간 TTS(«3세트 시작」) 가 불가능 |
| D-3 프론트가 명시 | 사용자가 «세트 종료」 를 누름 → `POST /sessions/{id}/sets/{n}/end` → Spring 이 AI 에 `NextSet` RPC | proto RPC 1개 + REST 1개 | 경계가 가장 정직(사용자 의도). 그러나 «자동 구분」 이라는 PDF 문구와 어긋나고 화면·AI 둘 다 바뀜 |

**추천**: D-1. 단 D-1 의 세부 두 가지가 다시 갈린다:

- **D-1-ⅰ 세트 목표값 출처** — ⓐ 세션 시작 요청 body 에 `targetSets`·`targetRepsPerSet` 를 받는다(사용자가 정함) / ⓑ `12-persona-difficulty.md` 공식으로 Spring 이 계산한다(`Session.difficultyLevel` 이 이미 있음) / ⓒ ⓑ 를 기본값으로 두고 ⓐ 로 덮어쓰기. 공식은 `RecommendationService` 에 이미 있으므로 ⓑ·ⓒ 는 그 서비스를 세션 시작에서 재사용하는 일이다. 세트 «수」 는 어느 문서·코드에도 공식이 없다 — 근거 없는 수를 넣지 않는다.
- **D-1-ⅱ 저장 형태** — ⓐ `exercise_session_sets` 표 신설(`session_id, set_no, target_reps, reps, avg_sync_rate, started_at, ended_at`, PK `(session_id, set_no)`) + `pose_data.set_index` / ⓑ `pose_data.set_index` 만 두고 세트 요약은 조회 시 집계 / ⓒ 세트 표만, `pose_data` 무변경. **`pose_data` 는 파티션 표(`V1__baseline.sql:209`)** 라 컬럼 추가 ALTER 의 비용·잠금은 실측 없이 말할 수 없다 — ⓐ·ⓑ 를 고르면 그 실측이 선행. ⓒ 는 프레임→세트 역참조를 `rep_number` 범위로 대신하므로 ALTER 가 없다.

  추천은 ⓒ(세트 표 + rep 범위). 리포트가 «세트별 최악 구간」 을 그리려면 rep 범위로 충분하고, 재부착도 `MAX(rep_number)` 만 넘기던 규칙을 «세트 표의 마지막 행」 으로 확장하면 된다.

### 3-E. 런지가 들어오면 깨지는 스쿼트 가정 — AI 담당자와 합의할 것

이건 백엔드 단독 결정이 아니라 **AI 가 무엇을 내놓느냐에 종속**된다. 합의 안건으로만 적는다.

| 자리 | 백엔드가 할 것 | AI 에 물을 것 |
|---|---|---|
| `ExtractReferenceData` 스쿼트 하드코딩 | B-1 의 `exercise_code` 를 `ExtractRequest` 에도 실음 | 런지 기준 영상의 rep 분절을 어떻게 하나 (`reference_builder.py` 는 스쿼트 cycle_stage 기반) |
| `PoseDataRequest.smoothed_knee_angle` | 런지에서 이 값이 «앞무릎」 인지 «평균」 인지에 따라 대표 프레임 선정(`SessionAnalysisCalculator`) 을 종목별로 갈라야 함. 필드 이름을 `depth_metric` 처럼 종목 중립으로 바꿀지 | 런지의 «깊이」 를 어떤 값으로 보낼 것인가 |
| `FeedbackType` 8종 | 런지 고유 타입(예: 앞무릎 전방 이탈·뒷무릎 미굴곡·상체 기울기) 이 필요하면 enum + 템플릿 시드 V25 + 페르소나 4행씩 | 런지 판정기가 내는 타입 집합. `spring_client.py:260` 주석이 «값 집합은 미정」 |
| 페르소나 임계값 4컬럼 | 이미 종목별 — 런지 값은 시드 기본값(60/85/70/50) 그대로인데 **근거 없음**. 실측 전엔 비워두거나 스쿼트와 같다고 명시 | 런지 sync_rate 분포 |
| 리포트 «최악 구간」·주간 패턴 | 종목이 섞이면 주간 집계가 `exercise_id` 로 갈라져야 함. `SessionAnalysisCalculator`·주간 리포트 LLM 프롬프트(VERSION 상향) 점검 | — |

### 3-F. 플랭크

PDF 에 없다. 플랭크는 rep 이 아니라 **시간 유지** 종목이라 `total_reps`·`rep_number` 축 자체가 안 맞는다 — 넣으려면 별도 분기. **이번 범위 밖**으로 두는 것을 추천.

## 4. 순서 — 백엔드 혼자 해도 죽은 코드가 안 되는 것부터

| 단계 | 내용 | AI 의존 | 비고 |
|---|---|---|---|
| ① | C-1 `GET /exercises` + B-1 `exercises.code`(V25) + 프론트 하드코딩 제거 | 없음(proto 제외) | 스쿼트만 있어도 «종목 선택」 화면이 성립. proto 의 `exercise_code` 는 ② 로. **백엔드 몫은 이 문서와 같은 PR 에서 구현**(V25·`GET /exercises`·관리자 `code` CRUD·W018~W020). 프론트 하드코딩 제거는 프론트 트랙(35-frontend-api-handoff.md) |
| ② | proto 확장 — `exercise_code`(3메시지), `target_reps_per_set`·`target_sets`(`AnalyzeRequest`·`ReattachRequest`). ~~`SetResult`~~ 는 ③ 에서 취소. **Spring 쪽은 2026-09-22 구현**(proto·pb2 재생성·송신부), AI 가 읽는 부분은 [`../handoff/ai-lunge-and-sets-proto.md`](../handoff/ai-lunge-and-sets-proto.md) §2-1·2-2 | AI 가 읽는 부분만 | `gen_proto.sh` 재생성 커밋 + CI proto 검사. AI 는 필드를 «받아서 무시」 부터 시작해도 됨(proto3 기본값) |
| ③ | 세트 표(V26) + `SessionSetAssembler` + `SetSummaryFormatter` 교체 + 리포트 `sets` + 세션 시작 body `targetRepsPerSet`·`targetSets` + 추천 폴백 + `difficultyLevel` 채움 | **없음** — 세트 요약을 Spring 이 `pose_data` 로 만들기로 해서(§7 3-D-ⅱ) ②·AI 를 안 기다린다. 2026-09-22 구현(PR 별도) | 세트 도입 전 세션은 세트 행 없음 → «1세트 x N회」 폴백 |
| ④ | 런지 활성화 절차 — 관리자 mp4 업로드 → 추출(종목 코드 반영) → `PATCH /analysis-support`. 템플릿 시드·`FeedbackType` 확장 | AI 런지 분석기 머지 뒤 | 그 전엔 W007 그대로. 3-E 안건 합의가 선행 |

①·② 는 이번 주에 백엔드가 시작할 수 있고, ③ 은 ② 의 계약이 잡히면 AI 구현을 안 기다려도 된다. ④ 만 AI 를 기다린다.

## 5. 결정 대기 — 사용자에게

1. **AI 담당자 확인** — PDF 의 «AI: 런지 피드백 / 운동 세트」 가 이번 학기 안에 오는 게 맞는가. 아니면 09-11 결정이 그대로고 이 문서는 보류.
2. **3-A** «런지 횟수 측정」 해석 — A-1(AI 가 세고 저장·집계) 인지, A-3(좌/우 분리) 까지인지.
3. **3-B** 종목 식별 — B-1(code 컬럼 + proto) 로 가는지.
4. **3-D** 세트 경계 주체 — D-1(AI 인지) 인지. 그렇다면 D-1-ⅰ 목표값 출처(ⓐ 사용자 입력 / ⓑ 페르소나 공식 / ⓒ 둘 다), D-1-ⅱ 저장 형태(ⓒ 세트 표 + rep 범위 추천).
5. **3-F** 플랭크 제외 확인.
6. **채널** — 3-E 안건과 ② proto 초안을 AI 담당자에게 넘길 형태. → 미응답, 초안은 [`../handoff/ai-lunge-and-sets-proto.md`](../handoff/ai-lunge-and-sets-proto.md) 로 만들어 뒀다.

답이 오면 §4 ① 부터 브랜치를 따로 파서 진행한다(주제별 브랜치, 스쿼시 머지 = main 커밋 하나).

## 7. 박제 — 2026-09-22 사용자 confirm

| 분기 | 채택 | 근거·조건 |
|---|---|---|
| §1 전제 | **AI 파트가 런지 피드백·세트를 이번 학기에 낸다** — 사용자 확인 | 09-11 의 세트·런지 «제외」 를 연다. 유스케이스 갱신은 ① 머지 때 (09-23 카탈로그에 반영 — 번호는 B-06 세트·B-05 런지) |
| 3-A | **A-1** — AI 가 세고 백엔드는 저장·집계 | 사용자 무응답 → 추천대로. A-3(좌/우 분리) 은 AI 담당자가 판정을 준다고 할 때 재개 |
| 3-B | **B-1** — `exercises.code` + proto `exercise_code` | — |
| 3-C | **C-1** — `GET /exercises` | 미지원 종목도 `analysisSupported=false` 로 내림 |
| 3-D | **D-1** — AI 가 세트 경계 인지 | «코드 봐서 정하라」 → 아래 두 줄 |
| 3-D-ⅰ | **ⓒ** — `POST /exercises/sessions` body 에 `targetRepsPerSet`(선택)·`targetSets`(선택, null = 열린 세트). `targetRepsPerSet` 이 없으면 `RecommendationService.buildRecommendation` 값 | 공식이 이미 코드에 있어 재사용. 세트 수는 공식이 없어 사용자 입력만. 채택 부속: `Session.difficultyLevel` 에 추천 level 을 채운다(죽어 있던 컬럼 — `RecommendationService` 주석의 «직전 난이도」 결손이 이걸로 풀림) |
| 3-D-ⅱ | **세트 표 + rep 범위, `pose_data` 무변경** — `exercise_session_sets(session_id, set_no, reps, avg_sync_rate, started_sec, ended_sec)` (V26). **채우는 주체는 Spring** — 완료 시점에 `pose_data` 의 rep 별 집계(`findRepSummaries`)를 `ceil(rep_number / T)` 로 묶는다(`SessionSetAssembler`). ~~`SessionCompleteRequest.sets` 로 AI 가 채움~~ → 2026-09-22 ③ 착수 때 변경(사용자 confirm) | 세트 경계가 «목표 도달」 하나뿐이라 저장본만으로 결정적으로 재현된다 — 싱크 통계(#75)가 AI 값 대신 `pose_data` 를 쓰는 것과 같은 원칙이고, proto 에 `SetResult` 가 필요 없어져 ③ 이 ②·AI 구현에 안 묶인다. `pose_data` 파티션은 `retention-buffer-months: 1` 로 드롭되니 세트 요약은 별도 표에 남긴다. 세트 도입 전 세션(`target_reps_per_set` NULL) 폴백 = 지금처럼 1세트 |
| 3-F | **플랭크 제외** — 나중에 | — |

## 8. 변경 이력

- 2026-09-22: 신설. §2 는 이 시점 `origin/main`(#784 포함) 실측.
- 2026-09-22: 사용자 confirm → §7 박제. §2-2 의 «공식이 코드에 없다」 는 오기를 정정(`RecommendationService` 에 있음).
- 2026-09-22: ① 백엔드 구현 — `code` 는 NULL 허용(사용자 confirm, 관리자 종목엔 분석기가 없으므로), 관리자 생성·수정에서 받되 분석이 켜진 종목은 잠금(추천값, 미응답).
- 2026-09-22: ② Spring 쪽 구현(사용자 지시 «AI 답 전에 Spring 먼저») — proto 필드 7·8·9 / 8·9·10 / 4, 값은 저장값 그대로·NULL 은 빈 문자열/0. AI 담당자가 이름·번호를 바꾸자면 AI 가 읽기 전에 맞춘다.
- 2026-09-22: ③ 착수 — 세트 요약 출처를 «AI `SetResult`」 에서 «Spring 이 `pose_data` 로 집계」 로 변경(사용자 confirm). ② proto 에서 `SetResult` 가 빠지고 `target_reps_per_set`·`target_sets` 는 TTS cue 용으로만 남는다. 세트 설정 API 는 따로 두지 않는다 — 목표는 세션 시작 body 에 실리고 도중 변경은 없다(`ceil(rep/T)` 역산이 흔들리므로).
