# REST API 설계 가이드

## Base URL
```
개발: http://localhost:8080/api/v1
운영: https://api.shadowfit.com/api/v1
```

## 인증 API

### POST /auth/register - 회원가입
```json
// Request
{
  "email": "user@example.com",
  "password": "password123",
  "nickname": "홈트초보"
}

// Response 201
{
  "id": 1,
  "email": "user@example.com",
  "nickname": "홈트초보",
  "token": "eyJhbGci..."
}
```

### POST /auth/login - 로그인
```json
// Request
{
  "email": "user@example.com",
  "password": "password123"
}

// Response 200
{
  "token": "eyJhbGci...",
  "user": {
    "id": 1,
    "nickname": "홈트초보",
    "persona": "BEGINNER"
  }
}
```

## 사용자 API

### PUT /users/me - 프로필 수정 (온보딩 포함)
```json
// Request (Header: Authorization: Bearer {token})
{
  "nickname": "홈트초보",
  "persona": "BEGINNER",
  "height": 175.0,
  "weight": 70.5
}

// Response 200
{
  "id": 1,
  "nickname": "홈트초보",
  "persona": "BEGINNER",
  "height": 175.0,
  "weight": 70.5
}
```

### GET /users/me - 내 정보 조회

## 운동 API

### GET /exercises - 운동 종목 목록 (2026-09-22 구현 — 그 전엔 문서에만 있었다)
종목 선택 화면용. 인증 필요. `analysisSupported=false` 인 종목도 내려온다 — 그 종목으로 `POST /exercises/sessions` 를 부르면 W007. 화면이 «준비 중» 을 그릴지 숨길지 고른다 ([`decisions/lunge-and-set-backend.md`](./decisions/lunge-and-set-backend.md) §3-C).
`code` 는 AI 분석기 키(`exercises.code`, V25) — null 이면 분석기가 없는 종목이라 관리자가 분석을 켤 수 없다(W020).
```json
// Response 200
[
  {
    "id": 1,
    "code": "SQUAT",
    "name": "스쿼트",
    "categoryId": 1,
    "categoryName": "LOWER",
    "description": "하체 전체 운동",
    "preferredUrl": "https://www.youtube.com/watch?v=q6hBSSis_60",
    "expectedDurationMinutes": 15,
    "analysisSupported": true
  },
  {
    "id": 2,
    "code": "LUNGE",
    "name": "런지",
    "categoryId": 1,
    "categoryName": "LOWER",
    "description": null,
    "preferredUrl": "https://www.youtube.com/watch?v=U4s4mEQ5ovM",
    "expectedDurationMinutes": 15,
    "analysisSupported": false
  }
]
```

### POST /exercises/sessions - 운동 세션 시작
`targetRepsPerSet`·`targetSets` 는 선택(2026-09-22, V26). `targetRepsPerSet` 을 생략하면 `GET /recommendations/next-session` 과 같은 공식(페르소나·레벨)으로 채우고, `targetSets` 는 사용자 값만(생략 = 열린 세트). 응답의 두 값이 **세션에 확정된 값**이며 도중에 바꿀 수 없다 — 세트 경계가 «rep 이 이 값에 닿는 순간」 이라 바꾸면 이미 저장된 프레임의 세트가 흔들린다([`decisions/lunge-and-set-backend.md`](./decisions/lunge-and-set-backend.md) §7). 세트 설정 API 는 따로 없다.
```json
// Request
{
  "exerciseId": 1,
  "targetRepsPerSet": 12,   // 선택
  "targetSets": 3           // 선택
}

// Response 202 Accepted (비동기 - gRPC 호출이 백그라운드로 진행)
{
  "sessionId": 42,
  "exerciseId": 1,
  "startTime": "2026-03-30T14:00:00",
  "status": "IN_PROGRESS",
  "sessionNonce": "…",
  "aiWorkerIndex": 0,
  "targetRepsPerSet": 12,
  "targetSets": 3,
  "syncGoodThreshold": 80.0,   // 2026-10-21, V34 — 싱크로율 ≥ 이 값 = 정석
  "syncWarnThreshold": 60.0    //                  ≥ 이 값 = 교정 필요, 미만 = 위험
}
```
`GET /sessions/active` 도 같은 두 필드를 돌려준다(이어하기 화면이 «12회 x 3세트」 를 복원할 근거).

`syncGoodThreshold`·`syncWarnThreshold` 는 세션 종목의 판정 기준 한 쌍이다(`exercises.sync_good_threshold`/`sync_warn_threshold`, 관리자가 `PATCH /admin/exercises/{id}/thresholds` 로 바꾼다). **같은 값이 `AnalyzeRequest`(11·12)로 AI 에도 가서** 앱이 점수를 칠하는 기준과 AI 가 «자세 양호» 를 판정하는 기준이 한 값이다. 페르소나와 무관하다(페르소나는 트레이너 말투로만 쓴다). `GET /sessions/active`·`POST /sessions/{id}/reattach`(재부착 요청 `ReattachRequest` 12·13 에 실은 값)·`GET /reports/session/{id}` 도 같은 두 필드를 싣는다.
> 내부 흐름: Spring 이 DB에 세션 생성 → 즉시 202 응답 → `@Async` 로 gRPC `StartAnalysis` 송신 (AI 가 기준 좌표 받아 분석 시작). 결합 상세는 [`architecture/ai-backend-integration.md`](./architecture/ai-backend-integration.md).

### POST /admin/exercises/{exerciseId}/reference-video - 기준 영상(mp4) 업로드 → 기준 좌표 추출 (관리자, 2026-09-17)
```
POST /admin/exercises/1/reference-video
Content-Type: multipart/form-data; file=<mp4>

// Response 202 — «추출이 시작됐다» 이지 «끝났다» 가 아니다. 좌표는 AI 역호출로 exercise_references 를 교체할 때 바뀐다
{ "id": 1, "referenceVideoPath": "1/6f9a1c2e-….mp4", ... AdminExerciseDetailDto 전 필드 }

// 400 W016 — 비었거나 .mp4 가 아니거나 파일 머리(ftyp)가 아님 · 413 C007 — 50MB 초과 · 503 W017 — AI 서킷 OPEN(파일·DB 안 건드림)
```
> 영상은 공유 볼륨(`/data/reference-videos/{id}/{uuid}.mp4`)에 운동당 1개 보관·교체되고, 경로가 `exercises.reference_video_path` 에 남는다. 결합 상세·트랜잭션 경계는 [`architecture/ai-backend-integration.md` §3-3](./architecture/ai-backend-integration.md).
>
> **추출 결과 확인 (2026-10-21, V34)** — 업로드 트랜잭션이 등록 상태를 `EXTRACTING` 으로 바꾸고, AI 역호출(성공 → `READY`, `error_message`·좌표 0개 → `FAILED`, 기존 기준은 그대로)이나 접수 실패(파일 없음·통신 실패·서킷 OPEN → `FAILED`)가 닫는다. 관리자 화면은 `GET /admin/exercises/{id}/reference` 의 `extractionStatus`·`extractionError` 로 본다.

### POST /exercises/{exerciseId}/reference - 기준 좌표 추출 요청 (관리자) — ⚠️ 유튜브 URL 은 동작하지 않는다
```
POST /exercises/1/reference?youtubeUrl=<AI 컨테이너 안 파일 경로>

// Response 202
"운동 ID [1]에 대한 기준 좌표 추출이 시작되었습니다."
```
> 파라미터 이름만 `youtubeUrl` 이다 — AI 가 http(s) 를 거부한다(유튜브 다운로드 ToS 미결정, `decisions/youtube-coordinate-harvest.md` §4-2). 위 mp4 업로드 API 가 실사용 경로다.
```
유튜브 URL → AI 가 MediaPipe로 프레임마다 관절 좌표 추출 → Spring 콜백으로 `exercise_references` 테이블 영속화.

### POST /sessions/{sessionId}/reattach - 진행 중 세션 이어하기 (AI 상태 재부착)
AI 프로세스 메모리에서 사라진 분석 상태를 DB 값(pose_data·미인정 시도)으로 되살린다(이슈 #59 2단계, [`decisions/session-resume-and-ai-state.md`](./decisions/session-resume-and-ai-state.md)).
```json
// Response 200
{
  "sessionId": 42,
  "restoredRepCount": 7,          // 인정 반복 수(MAX(pose_data.rep_number)). alreadyActive 면 살아있던 AI 의 현재 값
  "restoredRejectedCount": 2,     // 2026-10-21, V35 — 저장된 미인정 시도 수. 이어하기 화면의 «미인정 N회» 카운터 시작값
  "alreadyActive": false,
  "analyzerStateReset": true,
  "message": "7회까지 이어서 진행합니다. 자세 판정이 잠시 흔들릴 수 있습니다.",
  "sessionNonce": "…",
  "aiWorkerIndex": 0,
  "syncGoodThreshold": 80.0,
  "syncWarnThreshold": 60.0
}
// 503 SESSION_REATTACH_UNAVAILABLE — AI 연결 실패·서킷 OPEN·AI 거절(세션은 그대로 둔다)
```
`restoredRejectedCount` 는 재부착 요청 `ReattachRequest.initial_rejected_count`(14)로 AI 에 실어 보낸 값과 같다 — AI 는 미인정 번호(멱등키)와 화면 카운트를 여기서부터 잇는다. `restoredRepCount` 와 달리 `alreadyActive` 여도 **DB 값**이다(AI 재부착 응답에 그 필드가 없다).

### PATCH /sessions/{sessionId}/end - 운동 세션 종료
권장 종료 경로. 프론트가 종료 버튼을 누르면 호출. **멱등** — 이미 종료된 세션에 다시 호출해도 `200`.
```
PATCH /sessions/42/end

// Response 200 (본문 없음)
// 403 — 본인 세션 아님
```
`SessionController.java:57` → `SessionService.endSession`. `endTime` 기록 + **아웃박스에 AI 통보 적재**(직접 gRPC 호출 아님 — [`architecture/ai-backend-integration.md`](./architecture/ai-backend-integration.md) §4 중단).

> 🔴 **2026-08-08 정정 — 이 절은 존재하지 않는 엔드포인트를 적고 있었다.** 원래 `PUT /exercises/sessions/{sessionId}/stop`(2026-05-17 `143a2e4` 신설, `202`)로 되어 있었는데 **지금 컨트롤러에 `stop` 매핑이 0건**이다. 프론트도 `PATCH /sessions/{id}/end` 를 부른다(`frontend/services/exercisesService.ts:23`) — **즉 클라·서버는 일치하고 문서만 낡아 있었다.**
>
> ✅ **언제·왜 바뀌었는지는 결정 문서에 있다** — [`decisions/session-end-trigger.md`](./decisions/session-end-trigger.md)(2026-05-26): **ET-H(단일 endpoint 분배자 패턴) 확정**으로 `PUT …/stop` 을 **의도적으로 삭제**하고 `PATCH /sessions/{id}/end` 하나로 합쳤다. 클라가 종료를 한 번만 알리면 Spring 이 endTime 기록과 AI 통보를 **분배**한다(그 통보는 2026-07-29 아웃박스로 다시 바뀐다).
>
> 🔴 **즉 이 문서가 2.5개월 넘게 «폐기된 endpoint 를 권장 경로» 로 적고 있었다.** 결정 문서는 맞았고 API 문서만 안 따라왔다 — 프론트가 이 문서를 계약으로 읽었다면 없는 경로를 불렀을 것이다.
>
> ⚠️ 응답 코드도 `202 Accepted`(비동기 접수) → **`200 OK`**(멱등 종료)로 바뀌었다.
>
> 📌 `143a2e4` 를 기록한 문서들([`architecture/ai-backend-changelog.md`](./architecture/ai-backend-changelog.md)·`commit-details`·`monthly-log`)은 **그 시점 사실**이라 그대로 둔다.
내부 흐름:
1. Spring 이 `endTime` 을 기록하고 **아웃박스에 AI 통보를 적재**한 뒤 `200` 반환 (gRPC 직접 호출 아님)
2. 아웃박스 publisher 가 커밋 확정 후 gRPC `StopAnalysis(session_id=42)` 를 AI 에 송신
3. AI 가 누적 통계 정리 후 gRPC `CompleteAnalysis` 로 콜백 (3회 재시도)
4. Spring 콜백 수신 시점에 `status=COMPLETED`, `total_reps`, `avg_sync_rate` 등 DB에 영속화
5. 프론트는 별도 조회 API 로 결과 폴링

AI = 운동 통계의 단일 진실 원천 원칙. (커밋 143a2e4)

### ~~PUT /exercises/sessions/{sessionId}/complete~~ — 제거됨
프론트가 자체 카운트한 통계로 DB를 직접 갱신하던 옛 경로. AI 분석 결과와 권위가 충돌해 디프리케이트됐고, 엔드포인트는 `23c8953`(2026-07-11, 인증 없이 임의 세션을 강제 완료할 수 있던 결함)에서, 뒤에 남아 있던 서비스 계층·DTO 는 이슈 #179 에서 제거했다. 종료는 `PATCH /sessions/{sessionId}/end` 하나이고 완료 값의 출처는 AI gRPC 콜백이다.

### GET /exercises/{exerciseId}/feedback-templates - 운동별 TTS 멘트 목록
```json
// Response 200
[
  {
    "feedbackType": "KNEE_OVER",
    "message": "무릎이 발끝을 넘었습니다",
    "priority": 10
  },
  {
    "feedbackType": "GOOD_FORM",
    "message": "좋은 자세입니다",
    "priority": 100
  }
]
```
세션 시작 시 클라이언트가 호출해 device TTS 재생용 멘트 매핑.

> **참고**: 과거에 검토되었던 `POST /exercises/sessions/{id}/pose-data` (REST 배치 저장) 엔드포인트는 gRPC `SavePoseDataBatch` 콜백으로 대체되어 제거됨 (커밋 8ac8248).

## 기록 API

### GET /records/calendar?year=2026&month=3 - 월별 운동 기록
```json
// Response 200
{
  "year": 2026,
  "month": 3,
  "records": [
    {
      "date": "2026-03-15",
      "totalExerciseTime": 45,
      "totalCalories": 320.5,
      "sessionCount": 2,
      "mood": "GOOD"
    }
  ]
}
```

### GET /records/daily/{date} - 특정일 상세 기록
### POST /records/daily-logs - 일지 작성/수정
```json
// Request
{
  "logDate": "2026-03-30",
  "memo": "오늘 스쿼트 자세가 많이 좋아졌다!",
  "mood": "GREAT"
}
```

## 보고서 API

### GET /reports/session/{sessionId} - 세션 보고서
```json
// Response 200
{
  "reportId": 10,
  "sessionId": 42,
  "exerciseName": "스쿼트",
  "duration": "30분",
  "avgSyncRate": 78.5,
  "summary": "전체적으로 좋은 자세를 유지했습니다. 다만 후반부에 무릎이 발끝을...",
  "improvementTips": "1. 무릎 위치를 더 신경써주세요\n2. 허리를 곧게 유지해주세요",
  "workoutMinutes": 3,                   // 시작~종료, 분 절삭
  "caloriesBurned": 10,                  // 2026-10-21 — kcal 정수 반올림, Spring 이 체중으로 추정(아래). 못 쟀으면 null
  "comparisonWithPrevious": {            // 같은 종목의 직전 COMPLETED 세션이 없으면 null
    "syncRateDiff": 5,
    "workoutMinutesDiff": -2,
    "caloriesDiff": 3                    // 2026-10-21 — 두 세션 중 하나라도 칼로리가 null 이면 null
  },
  "squatDepthAnalysis": {                // 스쿼트 세션만(런지·깊이 데이터 없음 → null)
    "depthMode": "FULL",
    "depthScore": 32,                    // 모든 행의 평균 (64 + 0) / 2 — 미인정 시도도 들어간다
    "depthGrade": "POOR",
    "repDepths": [                       // 2026-10-21 — 미인정 시도가 counted=false 행으로 시간순으로 섞인다
      { "repNumber": 1, "minKneeAngle": 85.3, "depthScore": 64, "timeStamp": "00:05", "counted": true, "attemptNumber": null },
      { "repNumber": null, "minKneeAngle": 101.2, "depthScore": 0, "timeStamp": "00:09", "counted": false, "attemptNumber": 1 }
    ],
    "narrative": ["1회 중 1회는 시범 깊이에 조금 못 미쳤어요.",
                  "1회는 바닥 자세가 확인되지 않아 인정되지 않았어요 — 무릎 76° 이하까지 앉아 잠깐 멈췄다 일어나세요."],
    "targetKneeDeg": 76.2                // 2026-10-21 — FULL 목표 무릎각(이하면 만점). HALF·이전 저장 리포트는 null
  },
  "totalReps": 1,                        // 인정 반복만 — 미인정 시도는 안 센다
  "rejectedReps": 1,                     // 2026-10-21, V35 — 미인정 시도 수. 기록 이전 리포트는 0
  "rejectedRepsByReason": { "DEPTH_NOT_CONFIRMED": 1 },   // 0 이 아닌 사유만. 없으면 {}
  "syncGoodThreshold": 80.0,             // 2026-10-21, V34 — 세션 종목의 «현재» 판정 기준(정석 ≥ good, 교정 필요 ≥ warn, 위험 < warn)
  "syncWarnThreshold": 60.0,
  "repTrend": [                          // 회차 추이 — 예전 문서의 syncRateTimeline 은 오기. 2026-10-21 부터 시간순, 미인정 시도 포함
    { "repNumber": 1, "syncRate": 82.5, "timeStamp": "00:05", "timestampSec": 5.2,
      "counted": true, "attemptNumber": null, "rejectReason": null },
    { "repNumber": null, "syncRate": 41.5, "timeStamp": "00:09", "timestampSec": 9.4,
      "counted": false, "attemptNumber": 1, "rejectReason": "DEPTH_NOT_CONFIRMED" }
  ],
  "syncRateDetails": [{ "exerciseId": 1, "name": "스쿼트", "setInfo": "3세트 x 12회 (마지막 7회)", "syncRate": 78.5 }],
  "sets": [                              // 2026-09-22, V26. 세트 도입 전 세션은 []
    { "setNo": 1, "reps": 12, "avgSyncRate": 80.1, "startedSec": 3.2, "endedSec": 41.0 },
    { "setNo": 2, "reps": 12, "avgSyncRate": 78.9, "startedSec": 95.4, "endedSec": 133.7 },
    { "setNo": 3, "reps": 7,  "avgSyncRate": 74.2, "startedSec": 190.0, "endedSec": 214.5 }
  ]
}
```
`sets`·`setInfo` 는 AI 가 보낸 값이 아니라 완료 시점에 `pose_data` 의 rep 별 집계를 `ceil(rep_number / targetRepsPerSet)` 로 묶은 것이다(싱크 통계 #75 와 같은 원칙). 마지막 세트만 목표 미달일 수 있어 `setInfo` 는 «N세트 x T회」 또는 «N세트 x T회 (마지막 r회)」 두 꼴뿐이다.

**칼로리 (2026-10-21)** — AI 는 체중을 몰라 완료 콜백의 `calories_burned` 를 늘 0.0 으로 보낸다. 그래서 그 값은 버리고(proto 는 그대로) Spring 이 완료 시점에 `kcal = MET 3.0 × 3.5 × 체중(kg) ÷ 200 × 분` 으로 추정해 `exercise_sessions.calories_burned` 에 소수 둘째 자리로 저장한다(`CalorieEstimator`). MET 3.0 은 2024 Adult Compendium 코드 02056 «Body weight resistance exercises (squat, lunge, …), general» — 스쿼트·런지 공통. 분은 시작~완료 구간(`workoutMinutes` 와 같은 구간)을 **절삭하지 않은 소수**로 쓴다.
- 체중(`members.weight`)이 없거나 0 이하면 세션 칼로리는 **null**(«못 잼»)이다 — 0 이 아니다. 이 응답의 `caloriesBurned` 도 `Integer` 로 바꿔 **null** 을 그대로 싣는다(예전엔 `int` 라 0 으로 접혔다 — 이 필드만 `avgSyncRate`·`totalReps` 의 «0 으로 접기» 규약에서 빠진다). 값이 있으면 소수 둘째 자리 저장값을 정수로 **반올림**한다(예전엔 절삭 — `caloriesDiff` 의 반올림과 맞춤). 일지(`daily_logs.total_calories`)에는 0 을 더하고, 주간 합계(`totalCalories`)도 0 으로 친다.
- `comparisonWithPrevious.caloriesDiff` 는 `Integer` 다 — 두 세션 중 하나라도 null 이면 **null**(없는 값을 0 으로 쳐서 «지난번 대비 −56» 같은 가짜 차이를 만들지 않는다). 이 변경 전에 완료된 세션은 백필하지 않았다: AI 경로로 완료된 옛 세션은 `0.00` 이 그대로 남아 있어 그 세션과 비교하면 차이가 «+N» 으로 나온다.

**`squatDepthAnalysis.targetKneeDeg` (2026-10-21)** — FULL 모드 깊이 채점의 목표 무릎각 = 이 종목 기준(`exercise_references`, 시범)의 최저 무릎각 + 5.0°(AI 싱크로율의 무릎 허용 오차와 같은 값). 회차 점수 = `max(0, round(100 − 4 × max(0, 최저 무릎각 − 목표)))`. 앱은 «목표: 무릎 76° 이하» 처럼 보여 준다. 리포트는 완료 시점에 계산돼 `reports.detailed_analysis` 에 저장되므로(precompute-on-write) 목표·점수는 **그 시점 값으로 박제**된다 — 이 필드가 생기기 전에 저장된 리포트는 옛 규칙(100° 이하 만점) 점수 그대로이고 `targetKneeDeg` 가 null 이다. 근거: `docs/decisions/squat-repetition-counting.md` 2026-10-21 절.

**«미인정 시도» (2026-10-21, V35 `exercise_rejected_attempts`)** — 내려갔다 올라왔지만 AI 가 1회로 인정하지 않은 시도(바닥 자세 미확인 `DEPTH_NOT_CONFIRMED`·런지 자세 `SPLIT_STANCE`). 횟수 판정은 엄격하게 두고(사람 판정과 맞춤), 대신 미달 시도가 리포트에 보이게 한다 — 예전엔 그런 시도가 어디에도 안 남아 «좋은 자세만 세니 리포트는 무조건 좋다» 였다. 근거: [`decisions/counted-reps-vs-scored-attempts.md`](./decisions/counted-reps-vs-scored-attempts.md).
- **횟수·세트·목표 달성은 인정 반복만**이다: `totalReps`·`sets`·`syncRateDetails[0].setInfo` 는 그대로.
- **점수는 모든 시도**다: `avgSyncRate`(= 세션 `avg_sync_rate`, `max/min_sync_rate` 도 같다)는 인정 반복과 미인정 시도를 **한 표씩** 센 평균이다. 인정 0회·미인정만 있는 세션도 점수가 있다(`scoreAvailable: true`, `totalReps: 0`). 그래서 이 날짜 이후 세션의 평균은 «센 반복의 평균» 이 아니라 «시도의 평균» 이고, 미인정 시도가 있던 세션은 그 전 세션과 비교하면(`comparisonWithPrevious.syncRateDiff`·주간 평균) 그만큼 낮게 나온다. 미인정 시도가 없는 세션은 값이 예전과 같다.
- `repTrend` 의 점에 `timestampSec`(숫자 시각)·`counted`·`attemptNumber`·`rejectReason` 이 붙었다. 미인정 점은 `repNumber: null`·`counted: false`·`attemptNumber: k`(세션 안 미인정 번호, 1부터). 미인정 시도가 있으면 목록 전체가 `timestampSec` 오름차순이다(없으면 예전과 같은 rep 오름차순). **`repNumber` 는 null 일 수 있다** — 회차 번호로 무언가를 잇는 쪽은 null 을 건너뛸 것(`worstSection.repNumber` 매칭 등). `worstSection` 은 인정 반복에서만 고른다(대표 프레임의 3D 자세가 `pose_data` 에만 있다).
- `squatDepthAnalysis.repDepths`·`squatTrunkLeanAnalysis.repTrunkLeans` 에도 미인정 시도가 `counted: false`·`repNumber: null`·`attemptNumber` 행으로 시간순으로 섞이고, **인정 반복과 같은 공식**(같은 목표·같은 감점)으로 채점된다. 세션 `depthScore`·`tiltScore` 는 모든 행의 평균이다. 미인정 체간 행의 `avgTiltDeg` 는 평균이 아니라 «가장 깊이 앉은 순간» 한 장의 값이다. 무릎각·기울기가 미상인 시도는 행을 안 만든다.
- 깊이 `narrative` 는 미인정 시도가 있으면 사유별 한 문장을 끝에 붙인다(합계 4문장 이하 — 회차 코멘트 몫이 줄어든다): `DEPTH_NOT_CONFIRMED` «N회는 바닥 자세가 확인되지 않아 인정되지 않았어요 — 무릎 {목표}° 이하까지 앉아 잠깐 멈췄다 일어나세요.»(HALF 모드는 «목표 깊이까지»), `SPLIT_STANCE` «N회는 두 발이 앞뒤로 벌어진 자세로 보여 인정되지 않았어요 — 두 발을 어깨너비로 나란히 두고 앉으세요.» 회차 지목(«N회차»)은 인정 반복의 번호만 쓴다.
- 런지는 AI 가 미인정 시도를 보내지 않아 바뀐 것이 없다.
- **옛 리포트**: 이 필드들이 생기기 전에 저장된 `detailed_analysis` 는 그대로 읽힌다 — `rejectedReps: 0`·`rejectedRepsByReason: {}`, 옛 점·행은 `counted: true`·`attemptNumber: null`·`timestampSec: null`(백필 없음).

### ~~GET /reports/weekly~~ → **GET /reports/weekly-summary 로 합쳤다** (2026-08-23, #352)

주간이 두 경로로 갈려 있었고 새 쪽은 **부르는 곳이 없었다**. 이제 하나다.

**요청**: 파라미터 없음. 인증 필요. 기준은 **오늘이 속한 주(월 시작)** 고정
— 기준일 파라미터를 안 받는 이유는 응답의 두 절반(활동 집계 · A층 요약)이 같은 주를 보게 하기 위해서다.

**응답** `WeeklyActivityResponseDto`

| 필드 | 뜻 |
|---|---|
| `dateRange` · `totalWorkouts` · `totalMinutes` · `totalCalories` | 활동 집계 |
| `dailyLogs[]` · `todayDetails[]` | 요일별 막대 · 오늘 상세 |
| **`summary`** | A층 요약 — `periodStart` · `periodEnd`(미포함) · `thisWeek` · `lastWeek` 집계와 `sentences[]`(규칙 문장) |

기록이 없어도 **200** 이다 — 「이번 주에 운동을 안 했다」는 정상 상태라 빈 집계와 그 사실을 말하는 문장을 돌려준다.

### GET /reports/weekly-report?week=YYYY-MM-DD — 끝난 주의 리포트 + AI 총평 (2026-09-15, report-generation-llm.md §14)

`weekly-summary` 가 **오늘이 속한 주**에 묶여 있어(위 «기준일 파라미터를 안 받는 이유») 별도 경로다. `week` 는 그 주의 아무 날, 없으면 **지난주**.
이번 주·미래 주는 **400 R002** — LLM 문장은 «끝난 주» 에만, 한 번만 만든다(같은 주에 문장이 바뀌지 않는다).

**응답** `WeeklyReportResponseDto`

| 필드 | 뜻 |
|---|---|
| `periodStart` · `periodEnd`(미포함) | 월요일 ~ 다음 월요일 |
| `summary` | `weekly-summary.summary` 와 같은 A층 요약 — **항상 있다**(조회 시 계산) |
| `aiSummary` | Gemini 가 쓴 2~3문장. `aiSummarySource == LLM` 일 때만, 아니면 null |
| `aiSummarySource` | `PENDING`(처음 조회 — 생성이 걸렸다) · `LLM` · `TEMPLATE_FALLBACK`(검증 실패·한도·거절·기록 없음 — 화면은 `summary.sentences` 를 그대로 쓴다) |
| `aiGeneratedAt` | 종료 상태가 된 시각 |

**전달**: 첫 조회가 `weekly_reports` 행(PENDING)과 아웃박스 `GENERATE_WEEKLY_REPORT` 를 한 트랜잭션에 만들고 **즉시 템플릿으로 응답**한다 — LLM 을 기다리지 않는다.
별도 발행기(`WeeklyReportOutboxPublisher`, 5초 tick)가 Gemini 를 부르고, 출력의 숫자를 입력 집계와 대조해 없는 수가 있으면 **버리고 템플릿으로**(재호출 없음). 429·503·타임아웃만 재시도(백오프, 최대 ≈68분) 후 `exhausted` 로 닫는다.
`GEMINI_API_KEY` 가 없으면 전부 `TEMPLATE_FALLBACK(disabled)` — 서비스는 그대로 선다.

### GET /reports/monthly - 월간 보고서

## 사용자 환경설정 API (2026-05 추가)

### GET /preferences/tts - TTS 설정 조회
```json
// Response 200
{
  "ttsEnabled": true,
  "ttsSpeed": 1.0
}
```

### PATCH /preferences/tts - TTS 설정 변경
```json
// Request
{
  "ttsEnabled": true,
  "ttsSpeed": 1.5
}
// Response 200 — 갱신된 설정 반환
```
`ttsSpeed` 는 0.5~2.0 범위. device TTS 재생 시 클라이언트가 이 값을 그대로 `expo-speech` 의 `rate` 로 전달. ([`11-tts-youtube-guide.md`](./11-tts-youtube-guide.md))

## 관리자 API (2026-05 추가)

### 종목 코드 `code` (2026-09-22, V25)
`POST /admin/exercises`·`PATCH /admin/exercises/{id}` 가 `code`(선택, `^[A-Z][A-Z0-9_]{1,31}$`) 를 받고, 목록·상세 응답에 실린다.
- 중복이면 **409 W018** (UNIQUE 가 어차피 막지만 500 대신 이유를 준다)
- `analysisSupported=true` 인 종목의 코드 변경은 **409 W019** — 끄고 → 바꾸고 → 기준 영상 다시 올려 → 켠다
- `PATCH …/analysis-support` 로 켤 때 코드가 없으면 **400 W020** (기준 좌표 없음 W012 와 나란한 필요조건, 코드 검사가 먼저)

### PATCH /admin/exercises/{exerciseId}/thresholds - 싱크로율 판정 기준 변경
관리자 권한(`ROLE_ADMIN`) 필수. 신규 세션부터 적용 — 세션 시작 때 `AnalyzeRequest` 로 AI 에, 시작 응답으로 앱에 같이 나간다.
2026-10-21(V34) **«하나로 통일»**: 예전 페르소나별 4개(`beginner/advanced/diet/rehab`)는 읽는 곳이 없어 없앴고, 종목당 한 쌍이다. 페르소나는 트레이너 말투로만 남는다.
```json
// Request
{
  "good": 80,   // 싱크로율 ≥ good = 정석
  "warn": 60    // ≥ warn = 교정 필요, < warn = 위험
}
// 제약: 0 < warn < good ≤ 100 — 위반 400 W022 · 값이 빠지면 400 C001

// Response 200
{
  "exerciseId": 1,
  "syncGoodThreshold": 80.0,
  "syncWarnThreshold": 60.0
}
```
현재 값은 `GET /admin/exercises/{id}` 의 `syncGoodThreshold`·`syncWarnThreshold` 로 본다(신규 종목은 80/60 에서 시작).

### GET /admin/members - 회원 목록 조회 (2026-08 추가)
관리자 권한(`ROLE_ADMIN`) 필수. 필터 5종의 **임의 조합**(32가지) + 정렬 3종 + offset 페이징.

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `keyword` | — | `username`/`email` 부분일치. ⚠️ 선행 와일드카드라 인덱스 탐색 불가 |
| `persona` · `workoutLevel` · `onboardingCompleted` | — | 등치 필터 |
| `joinedFrom` · `joinedTo` | — | 가입일 범위(`joinedTo` 는 그날 포함) |
| `sort` | `CREATED_AT` | 화이트리스트 enum |
| `asc` | `false` | 최신순 기본 |
| `page` · `size` | `0` · `20` | `size` 최대 100 |

```json
// Response 200 — PageResponse<AdminMemberListItemDto>
{ "content": [ { "id": 1, "username": "...", "email": "...", "selectedPersona": "BEGINNER",
                 "workoutLevel": "STARTER", "onboardingCompleted": true, "createdAt": "..." } ],
  "page": 0, "size": 20, "totalElements": 1234, "totalPages": 62 }
```

### GET /admin/sessions - 세션 목록 조회 (2026-08 추가)
관리자 권한 필수. `exercise_sessions ⋈ users ⋈ exercises` 조인. 필터 4종 + 정렬 3종.

| 파라미터 | 설명 |
|---|---|
| `status` | 세션 상태 등치 |
| `exerciseId` | 운동 종목 |
| `startedFrom` · `startedTo` | 시작 시각 범위 |
| `keyword` | **회원** `username`/`email` 부분일치 (조인 너머) |
| `sort` | `START_TIME`(기본) / `AVG_SYNC_RATE` / `TOTAL_REPS` — 2차 정렬로 `id` 고정 |

### GET /admin/stats/overview - 대시보드 통계 (2026-08 추가)
관리자 권한 필수. 위젯 5종을 **실시간 집계**로 반환(사전집계·캐시 없음).

```json
// Response 200
{ "todaySessionCount": 2653, "sessionStatusDistribution": { "COMPLETED": 500000, "FAILED": 249554, "IN_PROGRESS": 250446 },
  "averageSyncRate": 75.0, "newMemberCount": 548, "activeMemberCount": 19019 }
```

> 📌 **세 API 의 설계 근거·실측은 [`decisions/admin-page-scope.md`](./decisions/admin-page-scope.md).**
> 인덱스 커버리지(§4-3), 드라이빙 테이블(§4-4-1), 집계 비용(§4-5) 이 거기 있다.
>
> 🔶 **총건수(`totalElements`)는 `LIMIT` 이 없어 조건에 맞는 행을 전부 센다.** 대부분의 필터
> 조합에서 전수 스캔이고, 이건 **감수하기로 한 것**(㉮)이다 — 페이지 번호 UI 를 그리려면
> 필요하고, 무한 스크롤로 정해지면 keyset 을 얹으면서 안 부르면 된다(§4-3 "2026-08-06").
>
> 🔶 **대시보드는 b(상태별 분포)·e(활성 회원) 둘이 비용의 대부분**이다(실측 각각 ~357ms /
> ~307ms, 나머지 셋 합쳐 5ms 미만). 캐시·인덱스 도입은 미결(§4-5-1 ④).

### 관리자 웹 콘솔용 추가 API (2026-10-09)
`admin-web/`(별도 웹 앱 — `admin-page-scope.md` §5-1 의 «표 + 페이지 번호» 형태)이 쓰는 API. 전부 관리자 권한 필수, 목록은 위와 같은 `{content, page, size, totalElements, totalPages}` 오프셋 페이징.

| 메서드 · 경로 | 내용 |
|---|---|
| `GET /admin/members/{memberId}` | 회원 상세 — 프로필, 세션 수·완료 수·평균 싱크로율·마지막 운동, 소속 모임, 최근 세션 10건 |
| `GET /admin/stats/daily?days=14` | 일별 추이 — 날짜별 세션 수·완료 수·완료 평균 싱크로율·신규 가입 (빈 날도 0 으로 채움, `days` 1~90) |
| `GET /admin/stats/community` | 모임 수·활성 참여 수·공유 글·응원 한마디·오늘 재촉 |
| `GET /admin/groups?keyword=` | 모임 목록 — 그룹장, 활성 멤버 수, 공유 글 수, 마지막 활동 |
| `GET /admin/groups/{groupId}` | 모임 상세 — 위 요약 + 멤버(역할·상태·가입일) |
| `DELETE /admin/groups/{groupId}` | 모임 삭제 — 멤버·초대·이벤트·응원 FK CASCADE, 공유 사진 파일도 지움 |
| `GET /admin/feed-posts?groupId=` | 공유 글(`SESSION_SHARED`) 최신순 — 사진·캡션 검수용 |
| `DELETE /admin/feed-posts/{postId}` | 공유 글 내리기 — 응원과 사진 파일까지 삭제 |
| `GET /admin/exercises/{id}/reference` | 싱크로율 기준 좌표 요약 — `{frameCount, source: MANNEQUIN_3D·VIDEO·null, durationSec, minKneeDeg, minHipDeg, depthVerdict: LENIENT·OK·STRICT·null, extractionStatus: NONE·EXTRACTING·READY·FAILED, extractionError, extractionAt: "yyyy-MM-ddTHH:mm:ss"·null}` (2026-10-20, 깊이·등록 상태는 2026-10-21 V34). `minKneeDeg`/`minHipDeg` 는 AI 와 같은 2D 각도(x 에 `index=-1` 표식의 `image_aspect_ratio` 를 곱함, 좌우 평균)의 전 프레임 최솟값(소수 1자리). `depthVerdict` 는 무릎 최솟값 60° 미만 `STRICT`(기준이 너무 깊어 대부분 감점) · 95° 초과 `LENIENT`(얕은 스쿼트도 만점) · 그 사이 `OK`, 프레임이 없으면 null. `extractionStatus` 는 가장 최근 등록 시도 — `NONE` 은 시도 없음, `EXTRACTING` 이 10분 넘게 그대로면 `FAILED`(«AI 응답이 10분 넘게 없어요. 영상을 다시 올려주세요.») 로 보인다 |
| `PUT /admin/exercises/{id}/reference-poses` | 기준 좌표 직접 등록(교체) — 관리자 웹의 «3D 시범을 기준으로 등록». body `{source, frames:[{timestampSec, jointCoordinates}]}` 4~2000프레임, 형식 오류 400 `W021`. 영상 업로드(`POST …/reference-video`)와 달리 AI 추출을 거치지 않는다 — 등록 상태는 바로 `READY`, 응답은 위 요약과 같은 모양 |

> 🔶 날짜 직렬화가 두 가지다 — 기존 관리자 DTO(회원·세션·종목·overview)는 `[2026,10,9,20,21,0]` 배열, 이번에 추가한 DTO 는 `"2026-10-09T20:21:00"` 문자열. 앱 쪽 영향 때문에 전역 설정은 건드리지 않았고, 관리자 웹이 둘 다 받는다(`admin-web/src/format.ts`).
>
> 🔶 관리자 계정을 만드는 API 는 없다 — DB 에서 직접 `users.role = 'ADMIN'` 으로 지정한다(권한 신청 기능은 만들었다가 필요 없다고 해서 뺐다, 2026-10-09).

## 모임·소셜 API (2026-08~09 추가)

설계 근거: [`decisions/multiuser-realtime-sync.md`](./decisions/multiuser-realtime-sync.md)(그룹 4테이블·WS 릴레이), [`decisions/social-cheer-and-group-feed.md`](./decisions/social-cheer-and-group-feed.md)(친구=같은 모임 ACTIVE 멤버, 출석=COMPLETED 세션, 재촉·푸시·자동 글·리액션). 전부 JWT 필수. 권한 규칙은 하나 — **같은 모임의 ACTIVE 멤버**(아니면 403 `G002`). 에러 코드 `G001`~`G011`·`N001`~`N003` 은 `ErrorCode` 참고.

### 모임

| 메서드·경로 | 요약 | 비고 |
|---|---|---|
| `POST /groups` | 모임 생성 | 생성자가 OWNER 로 자동 가입. 응답에 `inviteCode`(8자, 혼동 글자 제외) → 201 |
| `POST /groups/join` | 코드로 참여 | `{inviteCode}` — 대소문자·공백 무시. 승인 없이 바로 ACTIVE → 201. 코드 없음 404 `G008`, 이미 멤버 409 `G003` |
| `GET /groups/mine` | 내 모임 목록 | ACTIVE 인 것만 |
| `GET /groups/{groupId}` | 모임 상세 | `members[]`(memberId·username·role·status·joinedAt) 포함 |
| `POST /groups/{groupId}/invite-code` | 초대 코드 재발급 | OWNER 만(403 `G007`). 이전 코드 즉시 무효 |
| `PUT /groups/{groupId}/owner` | 그룹장 양도 | `{memberId}`. OWNER 만(403 `G007`). 대상이 ACTIVE 멤버 아니면 404 `G011`, 자기 자신이면 400. 넘긴 쪽은 MEMBER |
| `DELETE /groups/{groupId}/members/me` | 탈퇴 | MEMBER: 행은 LEFT 로 남고(재가입 시 되살림), 남긴 글·리액션은 그대로. **OWNER**: 다른 ACTIVE 멤버가 있으면 409 `G010`(양도 먼저), 혼자면 **모임 삭제**(멤버·초대·피드 CASCADE) — #721 |
| `POST /groups/{groupId}/invitations` | 초대 발송 | `{inviteeId}`. ACTIVE 멤버 누구나. 이미 대기중 409 `G006` |
| `GET /invitations/mine` | 내게 온 대기중 초대 | |
| `POST /invitations/{id}/accept` · `/decline` | 수락·거절 | 수락 = 코드 참여와 같은 가입 경로(`MEMBER_JOINED` 발행). 이미 응답 409 `G005` |

```json
// POST /groups  Request
{ "name": "아침 스쿼트", "description": "매일 7시" }
// Response 201 — GroupResponseDto
{ "id": 7, "name": "아침 스쿼트", "description": "매일 7시", "inviteCode": "K7M2P9XW", "createdById": 1, "createdAt": "2026-09-14T09:00:00" }
```

### 출석·현황 (친구 = 내 모임 사람들)

| 메서드·경로 | 요약 | 비고 |
|---|---|---|
| `GET /groups/{groupId}/members/status` | 구성원 운동 현황 | ACTIVE 전원의 `attendedToday`·`streak`. 정렬: 오늘 완료 → 진행 중 → 기록 없음 |
| `GET /friends` | 친구의 운동 현황 | 내가 속한 모든 모임의 ACTIVE 멤버(나 제외, 중복 제거), 같은 항목·같은 정렬 |
| `GET /groups/{groupId}/attendance?year&month` | 모임 출석 캘린더 | 그 달의 모든 날 × `attendedCount`(COMPLETED 세션이 있는 현재 ACTIVE 멤버 수) + `activeMemberCount`(분모). 칸 농도 매핑은 프론트 |
| `GET /attendance/mine` | 내 스트릭 카드 (2026-09-18, streak-card-api.md) | `today`·`attendedToday`·`currentStreak(+Start)`·`longestStreak(+Start/End)`·`thisWeek`(월~일 7개 고정). 최장 기록 동률이면 최근 구간. 문구(«오늘 하면 N일째», «갱신 중» = `currentStreak == longestStreak && longestStreakEnd ≥ 어제`)는 프론트 파생 |

«출석» 의 정의는 한 곳 — `AttendanceService`: **COMPLETED 세션이 있는 날**(status 무관이던 예전 캘린더 정의와 다름), streak 은 창 없이 최신순 커서로 첫 끊김에서 중단. 노출 항목은 §3-G 로 고정된 셋(오늘 여부·연속일수·합산 출석)뿐 — rep 수·칼로리·세션 상세는 남에게 안 보인다.

```json
// GET /friends  Response 200 — List<MemberAttendanceStatusDto>
[ { "memberId": 2, "username": "철수", "profileImageUrl": null, "attendedToday": true, "streak": 5 },
  { "memberId": 3, "username": "영희", "profileImageUrl": null, "attendedToday": false, "streak": 0 } ]
```

```json
// GET /attendance/mine  Response 200 — MyAttendanceResponseDto (기록 없으면 0·null, thisWeek 는 전부 false)
{ "today": "2026-09-18", "attendedToday": false,
  "currentStreak": 5, "currentStreakStart": "2026-09-13",
  "longestStreak": 12, "longestStreakStart": "2026-07-01", "longestStreakEnd": "2026-07-12",
  "thisWeek": [ { "date": "2026-09-14", "attended": true }, { "date": "2026-09-15", "attended": true },
                { "date": "2026-09-16", "attended": true }, { "date": "2026-09-17", "attended": true },
                { "date": "2026-09-18", "attended": false }, { "date": "2026-09-19", "attended": false },
                { "date": "2026-09-20", "attended": false } ] }
```

### 피드·리액션

| 메서드·경로 | 요약 | 비고 |
|---|---|---|
| `GET /groups/{groupId}/feed?beforeSeq&size` | 모임 피드 | 최신순 keyset. `beforeSeq` 생략 = 최신부터, `size` 기본 20·최대 100. 응답 `nextBeforeSeq` 를 다음 요청에(null 이면 끝) |
| `PUT /groups/{groupId}/events/{seq}/reactions/{kind}` | 리액션 달기 | **멱등** — 이미 있어도 200. `kind` = `HEART` \| `FIRE`(밖이면 400). 같은 글에 둘 다 가능. 글 없음 404 `G009` |
| `DELETE /groups/{groupId}/events/{seq}/reactions/{kind}` | 리액션 취소 | **멱등** — 없어도 200 |
| `POST /groups/{groupId}/shares` | 운동 공유 (2026-10-06) | multipart — `sessionId`(필수) · `caption`(선택, 200자) · `photo`(선택, jpg·png·webp 10MB, 앞 바이트로 판정). `SESSION_SHARED` 글 → 201 + 피드 항목. **같은 운동을 같은 모임에 두 번이면 409 `G012`**(V19 UNIQUE 가 최종 판정), 다른 모임엔 가능. 내 것이 아니거나 안 끝난 세션 404 `G013`, 사진 형식 400 `G014`. payload 공개 항목은 종목·유효 횟수·운동 시간·한마디·`photoUrl` — 싱크로율 비공개 |
| `GET /groups/shares?sessionId` | 이 운동을 이미 공유한 내 모임 | `[groupId]` — 공유 화면의 «공유됨» 잠금용 |
| `PUT /groups/{groupId}/events/{seq}/cheers` | 피드 글 응원 한마디 | `{message}` 1~100자. **한 글에 회원당 한 줄**(V27 UNIQUE) — 다시 보내면 문구만 바뀐다. 응답 = 그 글의 응원 전부(오래된 순) |
| `DELETE /groups/{groupId}/events/{seq}/cheers` | 내 응원 지우기 | 멱등 200 |
| `GET /feed-photos/{name}` | 피드 사진 | **인증 없음**(웹 `<img>` 가 헤더를 못 실음). 이름이 UUID 라 추측 불가, 피드 응답에만 실린다. 30일 캐시 |

피드 항목에는 `cheers: [{memberId, username, message}]` 가 함께 실린다(2026-10-06). 공유 글(`SESSION_SHARED`)과 자동 완료 글(`SESSION_COMPLETED`)은 타입이 달라 서로를 막지 않는다 — 앱은 자동 글을 한 줄 소식으로, 공유 글을 사진 카드로 그린다.
| `GET /groups/{groupId}/events?afterSeq` | WS 재연결 백필 | `afterSeq` 이후 **전부·오름차순·무페이징**. 피드 화면용이 아니라 소켓이 끊긴 동안 놓친 것을 채우는 용도 |

피드 항목은 `group_events` 행이다. 서버가 만드는 타입은 `SESSION_COMPLETED`(세션 완료 시 아웃박스를 거쳐 회원의 ACTIVE 모임마다 1건, `senderId` = 완료한 회원, `payload` = `{sessionId, memberId, username, exerciseName}`)와 `MEMBER_JOINED`(`payload` = `{memberId, username}`). 소켓 클라이언트가 보낸 임의 `type` 도 같은 표에 쌓이므로 프론트는 모르는 타입을 무시해야 한다. 리액션은 알림·소켓 발행이 없다 — 재조회로 반영된다.

```json
// GET /groups/7/feed?size=2  Response 200 — GroupFeedResponseDto
{ "items": [
    { "seq": 41, "groupId": 7, "type": "SESSION_COMPLETED", "senderId": 2,
      "payload": "{\"sessionId\":123,\"memberId\":2,\"username\":\"철수\",\"exerciseName\":\"스쿼트\"}",
      "occurredAt": "2026-09-14T07:12:30",
      "reactionSummary": { "reactions": { "HEART": 2, "FIRE": 0 }, "myReactions": ["HEART"] } },
    { "seq": 40, "groupId": 7, "type": "MEMBER_JOINED", "senderId": null, "payload": "{\"memberId\":3,\"username\":\"영희\"}",
      "occurredAt": "2026-09-13T21:00:00",
      "reactionSummary": { "reactions": { "HEART": 0, "FIRE": 0 }, "myReactions": [] } } ],
  "nextBeforeSeq": 40 }

// PUT /groups/7/events/41/reactions/FIRE  Response 200 — ReactionSummaryDto
{ "reactions": { "HEART": 2, "FIRE": 1 }, "myReactions": ["HEART", "FIRE"] }
```

### 재촉·알림·푸시

| 메서드·경로 | 요약 | 비고 |
|---|---|---|
| `POST /friends/{memberId}/nudge` | 재촉하기 | 같은 모임 ACTIVE 멤버에게만. **같은 사람에게 하루 1회** — 두 번째는 409 `N002`. 자기 자신 400 `N003`. 응답 = 만든 알림 → 201 |
| `POST /friends/{memberId}/cheer` | 응원 보내기 | `{message}` 1~100자(앞뒤 공백 제거, 빈 문자열 400). 같은 모임 ACTIVE 멤버에게만. **같은 사람에게 하루 1회 — 재촉과 별개로 센다**(두 번째 409 `N004`). 자기 자신 400 `N005`. 응답 = 만든 알림(`type=CHEER`, `message` 채움) → 201. 완료한 상대에게 보내는 것이 정상 경로라 서버는 아무 조건도 안 본다 |
| `GET /notifications?page&size` | 내 알림함 | 최신순 offset(`PageResponse`), `size` 기본 20·최대 100. 보낸 사람이 탈퇴했으면 `sender*` null |
| `PATCH /notifications/{id}/read` | 읽음 처리 | 남의 것·없음 404 `N001`. 이미 읽었으면 처음 시각 그대로 200. «모두 읽음» 없음 |
| `POST /push-tokens` | 푸시 토큰 등록 | `{token, platform}` — `ExponentPushToken[...]` 형식 아니면 400, `platform` = `IOS`\|`ANDROID`. **멱등 200**(신규든 갱신이든). 다른 계정이 같은 토큰을 등록하면 소유자가 옮겨감. 삭제 API 없음 — 로그아웃이 계정 단위로 지움 |

재촉·응원의 전달은 셋 — 알림 행(원천, 항상) · 접속 중이면 소켓(#7, 진행 중) · 등록된 기기가 있으면 Expo Push(아웃박스 `PUSH_NOTIFICATION` 경유, at-least-once). 서버는 «오늘 이미 완료한 상대» 재촉을 막지 않는다 — 버튼 노출은 프론트.

```json
// POST /friends/2/nudge  Response 201 — NotificationDto
{ "id": 55, "type": "NUDGE", "senderId": 1, "senderUsername": "민수", "senderProfileImageUrl": null,
  "targetDate": "2026-09-14", "read": false, "readAt": null, "createdAt": "2026-09-14T08:00:00" }

// POST /friends/2/cheer  {"message":"오늘도 힘내!"}  Response 201 — NotificationDto (재촉은 message=null)
{ "id": 56, "type": "CHEER", "senderId": 1, "senderUsername": "민수", "senderProfileImageUrl": null,
  "targetDate": "2026-09-17", "message": "오늘도 힘내!", "read": false, "readAt": null, "createdAt": "2026-09-17T08:05:00" }
```

### WebSocket `/ws/groups/{groupId}?token={JWT}`

브라우저 핸드셰이크가 `Authorization` 헤더를 못 실어 JWT 는 **쿼리 파라미터**로. ACTIVE 멤버만 업그레이드. 서버→클라이언트 프레임은 `GroupEventResponseDto`(`seq·groupId·type·senderId·payload·occurredAt`) 그대로. 클라이언트→서버는 `{ "type": "...", "payload": {...} }` — 서버가 seq 를 채번해 저장·전원 릴레이. 끊겼다 붙으면 마지막 `seq` 로 `GET /groups/{groupId}/events?afterSeq` 백필. 단일 인스턴스 전제(Redis 없음).

## 내부 API (AI ↔ Spring, gRPC 단일 채널)

> **2026-05-26 갱신**: AI → Spring 내부 호출은 *전부 gRPC* 로 통일. `Authorization: Bearer {INTERNAL_API_TOKEN}` (metadata) 로 인증. REST `/internal/*` endpoint 는 폐기됨 (기존 `POST /internal/feedback/batch` → `ExerciseService.ReportFeedbackBatch`). proto 정의는 `backend/src/main/proto/exercise.proto`. 박제: [`./decisions/tts-design.md`](./decisions/tts-design.md) 상단 박스.

### gRPC ExerciseService.ReportFeedbackBatch — 세션별 TTS 발화 이벤트 batch 저장
**호출자**: AI 서버 (FastAPI → Spring). BT-SET 모델 (분기 2.A.BT) — *세트 경계마다 mini-batch + 세션 종료 시 final batch*. 매 rep 실시간 호출 금지.
**인증**: gRPC metadata `Authorization: Bearer {INTERNAL_API_TOKEN}` (`InternalAuthInterceptor`).
**멱등성**: `(session_id, occurred_at, feedback_type)` uniqueKey + `INSERT IGNORE`. 같은 events 재송신 안전.

```proto
// Request
message FeedbackBatchRequest {
  int64 session_id = 1;
  int32 set_no = 2;                            // 1-based. BT-NONE 호환 시 1 고정
  bool is_final = 3;                           // 마지막 batch 여부
  repeated FeedbackEvent events = 4;
}

message FeedbackEvent {
  string feedback_type = 1;                    // 8종 enum 중 하나 (KNEE_OUT 등)
  double sync_rate_at_trigger = 2;
  google.protobuf.Timestamp occurred_at = 3;
}

// Response
message FeedbackBatchResponse {
  int64 session_id = 1;
  int32 saved_count = 2;                       // INSERT 된 row 수 (중복 흡수 제외)
}
```

### gRPC ExerciseService.SaveRejectedAttempt — «미인정 시도» 1건 저장 (2026-10-21, V35)
**호출자**: AI 서버 (FastAPI → Spring). 내려갔다 올라왔지만 1회로 인정하지 않은 시도마다 1건 — «가장 깊이 앉은 순간» 한 장으로 요약.
**인증**: 다른 AI 콜백과 같다(`InternalAuthInterceptor`).
**멱등성**: `(session_id, attempt_number)` UNIQUE + `ON DUPLICATE KEY UPDATE` — 재전송(동시 재전송 포함)에 행이 안 는다. 첫 저장값을 보존한다.
**세션 규칙**: `SavePoseDataBatch` 와 같다 — 세션 없음 `NOT_FOUND`, `IN_PROGRESS` 가 아니면(완료 뒤 도착) 저장 없이 `success=true`. 횟수만 세는 종목도 저장하지 않는다(점수가 없다). 저장되면 `last_active_at` 도 갱신한다(얕은 시도만 반복하는 동안 유휴 타임아웃이 세션을 걷어가지 않게).
**거절**: `attempt_number ≤ 0`·모르는 `reason` → `INVALID_ARGUMENT`(INVALID_INPUT_VALUE), `sync_rate ∉ [0,100]`·`timestamp_sec ∉ [0,3600]`·`min_knee_angle`/`torso_tilt_deg ∉ [0,180]`·NaN·JSON 이 아닌 `joint_coordinates` → `INVALID_ARGUMENT`(DATA_INTEGRITY_VIOLATION). 잠금 경합 → `ABORTED`. `min_knee_angle` 0 은 «미상» 이라 NULL 로, 빈 `joint_coordinates` 는 NULL 로 저장한다.

```proto
message RejectedAttemptRequest {
  int64 session_id = 1;
  int32 attempt_number = 2;      // 세션 안 미인정 번호(1부터) — 멱등키
  string reason = 3;             // "DEPTH_NOT_CONFIRMED" | "SPLIT_STANCE"
  double sync_rate = 4;          // 인정 반복과 같은 식, 0~100
  double min_knee_angle = 5;     // 가장 깊이 앉은 순간의 무릎각
  double torso_tilt_deg = 6;
  double timestamp_sec = 7;      // pose_data.timestamp_sec 과 같은 원점
  string joint_coordinates = 8;
}
message RejectedAttemptResponse { bool success = 1; }
```
저장된 시도는 세션 완료 때 싱크 통계(avg/max/min)와 리포트(`rejectedReps`·회차 추이·깊이·체간)에 들어가고 횟수·세트에는 안 들어간다 — 위 「세션 보고서」 절.

### 기존 gRPC RPC (참고)

- `StartAnalysis (AnalyzeRequest) returns (AnalyzeResponse)` — Spring → FastAPI 세션 시작
- `StopAnalysis (StopRequest) returns (StopResponse)` — Spring → FastAPI 강제 중단 (ET-H, `SessionService.endSession` afterCommit 에서 호출)
- `SavePoseDataBatch (PoseDataBatchRequest) returns (PoseDataResponse)` — FastAPI → Spring 포즈 batch
- `CompleteAnalysis (SessionCompleteRequest) returns (SessionCompleteResponse)` — FastAPI → Spring 세션 최종 통계
- `ExtractReferenceData (ExtractRequest) returns (ExtractResponse)` — Spring → FastAPI YouTube 좌표 추출

## 공통 응답 형식

### 성공
```json
{
  "status": 200,
  "data": { ... }
}
```

### 에러
```json
{
  "status": 400,
  "error": "BAD_REQUEST",
  "message": "유효하지 않은 이메일 형식입니다."
}
```

## 인증 방식
- JWT Bearer Token
- Header: `Authorization: Bearer {token}`
- 토큰 만료: 24시간
- `/auth/*` 엔드포인트는 인증 불필요
