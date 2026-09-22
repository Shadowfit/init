# AI 측 작업 요청 — 런지 분석기 · 세트 인지 · proto 확장 (②)

작성: 2026-09-22 · 대상: **ai-server 담당자** · 배경: [`../decisions/lunge-and-set-backend.md`](../decisions/lunge-and-set-backend.md) §7(박제)·§3-E(합의 안건)
출처: 캡스톤 디자인 I 1회차 보고 — [AI] *런지 피드백 / 운동 세트 / TTS*, [백엔드] *운동 종목 선택 / 운동 세트(AI 연동) / 런지 횟수 측정*

> **상태**: 🟡 proto 초안. 아래 필드 이름·번호는 Spring 쪽 제안이고, AI 담당자 답을 받아 **한 PR** 로 양쪽을 바꾼다
> (`proto/exercise.proto` 한 벌 + `cd ai-server && ./scripts/gen_proto.sh` 재생성 커밋 — CI 가 원본과 다르면 막는다).

## 0. 한 줄 요약

Spring 이 세션 시작 때 **종목 코드와 세트 목표**를 실어 보내고, AI 는 **rep 이 목표에 닿으면 세트를 닫고** 종료 때 **세트별 요약**을 돌려준다. 세트 경계는 «목표 도달» 하나뿐이라 휴식 시간 임계값 같은 상수가 필요 없다.

## 1. proto 초안 (`proto/exercise.proto`)

```proto
message AnalyzeRequest {
  // … 기존 1~6 그대로 …
  // 종목 코드 (exercises.code, V25) — 분석기 레지스트리의 키. 빈 문자열이면 구버전 Spring: exercise_id 로 폴백.
  string exercise_code = 7;          // "SQUAT" | "LUNGE" | …  (AI 쪽 키는 소문자면 경계에서 lower())
  // 세트당 목표 횟수. rep 이 이 값에 닿으면 세트가 닫힌다. 0 이면 «세트 없음» = 지금 동작(BT-NONE, set_no=1 고정).
  int32 target_reps_per_set = 8;
  // 목표 세트 수. 0 = 열린 세트(사용자가 끝낼 때까지). 마지막 세트 완료 cue 에만 쓰고, 세션 종료는 여전히 사용자.
  int32 target_sets = 9;
}

message ReattachRequest {
  // … 기존 1~7 그대로 …
  string exercise_code = 8;
  int32 target_reps_per_set = 9;     // AI 는 initial_rep_count 로 current_set_no·reps_in_set 을 역산한다 (§2-3)
  int32 target_sets = 10;
}

message ExtractRequest {
  // … 기존 1~3 그대로 …
  string exercise_code = 4;          // 기준 영상 rep 분절에 어느 분석기를 쓰나. 지금은 analyze_video(path, "squat") 하드코딩
}

// 세트별 요약 — CompleteAnalysis 에 실린다. 마지막 세트는 목표 미달이어도 그대로.
message SetResult {
  int32 set_no = 1;                  // 1-based
  int32 reps = 2;
  double avg_sync_rate = 3;
  double started_sec = 4;            // 세트 첫 rep 의 timestamp_sec 기준 (pose_data 와 같은 원점)
  double ended_sec = 5;              // 세트 마지막 rep 의 timestamp_sec
}

message SessionCompleteRequest {
  // … 기존 1~7 그대로 …
  repeated SetResult sets = 8;       // 비어 있으면 구버전 AI: Spring 이 «1세트 x total_reps» 로 폴백
}
```

- `FeedbackBatchRequest.set_no`·`is_final` 은 **이미 있다** — 세트가 닫힐 때 `set_no=n, is_final=false`, 세션 종료 때 `is_final=true` 로 보내면 된다(BT-SET, [`tts-design.md`](../decisions/tts-design.md) §2.A.BT).
- `PoseDataRequest` 는 **안 바뀐다**. 프레임의 세트는 `ceil(rep_number / target_reps_per_set)` 로 Spring 이 역산한다 — `set_index` 컬럼을 파티션 표(`pose_data`)에 더하지 않기 위해서다.
- 전부 proto3 기본값(0·"") 이 «없음» 이라 **한쪽만 먼저 배포돼도 지금 동작이 유지**된다.

## 2. AI 쪽 작업 목록

### 2-1. 종목 코드로 분석기 찾기
- `analyzer_registry.py`: `_EXERCISE_ID_TO_TYPE` 대신 `exercise_code` 로 찾는다(`"LUNGE"` → `"lunge"`). 빈 문자열이면 지금처럼 id 표 폴백(한 릴리스만 유지 후 제거).
- `ExtractReferenceData`·`reference_builder.build_reference_sequence`: `"squat"` 하드코딩을 `exercise_code` 로.

### 2-2. 세트 카운터 (`session_state.py`)
```
current_set_no = 1, reps_in_set = 0, set_results: list[SetResult]
rep 완성 시:
  reps_in_set += 1
  if target_reps_per_set > 0 and reps_in_set == target_reps_per_set:
      set_results.append(요약)                       # reps·avg_sync·started/ended_sec
      report_feedback_batch(set_no=current_set_no, is_final=False)
      /pose 응답 cue 에 «n세트 완료» (target_sets 에 닿으면 «마지막 세트 완료»)
      current_set_no += 1; reps_in_set = 0
세션 종료(Stop/Complete) 시:
  reps_in_set > 0 이면 미완 세트도 set_results 에 넣고, CompleteAnalysis.sets 로 전송
```
- 세트 경계 = «목표 도달» 하나. 휴식 시간으로 자르지 않는다(근거 있는 초 값이 없다).
- 휴식 구간(세트 닫힘 ~ 다음 rep 시작)은 이 카운터로 알 수 있다 → [#92](https://github.com/Shadowfit/init/issues/92)(휴식 중 프레임 낭비) 의 선행이 같이 풀린다. 이번 범위엔 안 넣어도 된다.

### 2-3. 재부착
- `ReattachRequest.initial_rep_count` 와 `target_reps_per_set` 으로 `current_set_no = initial // T + 1`, `reps_in_set = initial % T`. 이미 닫힌 세트의 요약은 Spring 이 세트 표에 갖고 있으므로 AI 는 **재부착 이후 세트만** `sets` 에 실어도 된다(Spring 이 set_no 로 병합).

### 2-4. 런지 분석기 (§3-E 합의 안건 — 답을 주면 Spring 이 맞춘다)

| 질문 | 왜 Spring 이 알아야 하나 |
|---|---|
| 런지의 «깊이» 를 `PoseDataRequest.smoothed_knee_angle` 에 무엇으로 실을 것인가 (앞무릎? 좌우 평균?) | 리포트 대표 프레임을 이 값 최소로 고른다(`SessionAnalysisCalculator`). 평균이면 런지에선 의미가 없다. 필드 이름을 `depth_metric` 처럼 종목 중립으로 바꿀지도 같이 |
| 런지 판정기가 내는 `feedback_type` 집합 | `FeedbackType` 8종 enum + 템플릿 시드(페르소나 4행씩)를 맞춰야 한다. 지금 런지 템플릿 3행은 스쿼트 타입을 재해석한 것(`HIP_HIGH` → «뒷무릎을 더 굽혀주세요») |
| 좌/우 다리를 구분해 세는가 | 구분하면 `rep_number` 하나로는 못 실어 proto·리포트에 «다리」 축이 생긴다(A-3). 안 하면 지금 계약 그대로 |
| 런지 기준 영상의 rep 분절 방식 | `reference_builder` 가 스쿼트 cycle_stage 기반. 런지용이 따로 필요한지 |
| 런지 `sync_rate` 분포 | `exercises` 의 페르소나 임계값 4컬럼이 런지는 시드 기본값(60/85/70/50) 그대로인데 근거가 없다. 실측 전엔 «스쿼트와 같다고 가정» 으로 명시 |

## 3. Spring 쪽이 할 일 (③, ② 계약이 잡히면 AI 구현을 안 기다린다)

- `POST /exercises/sessions` body: `targetRepsPerSet?`·`targetSets?` — 없으면 `RecommendationService` 공식으로 `targetRepsPerSet` 채움, `targetSets` 는 null(열린 세트)
- `exercise_sessions.target_reps_per_set`·`target_sets`(NULL 허용) + `exercise_session_sets(session_id, set_no, reps, avg_sync_rate, started_sec, ended_sec)` — V26
- `ExerciseAnalysisService`·`ReattachRequestBuilder`: `exercise_code`·세트 목표 실어 보내기
- `SessionCompletionTx`: `sets` 저장(비면 폴백), `SetSummaryFormatter.FIXED_SET_COUNT` 교체, 리포트·주간 집계 세트 반영
- `Session.difficultyLevel` 에 추천 level 채우기(죽어 있던 컬럼)

## 4. 순서

1. 이 문서로 §1 필드 이름·번호 + §2-4 질문에 답 → proto PR (양쪽 pb2/Java 스텁 동시)
2. AI: §2-1·2-2 → 스쿼트로 먼저 세트가 도는지 확인(런지 분석기 전에 끝낼 수 있다)
3. Spring: §3
4. AI: 런지 분석기 → 관리자가 런지 mp4 업로드 → `PATCH /admin/exercises/2/analysis-support`
