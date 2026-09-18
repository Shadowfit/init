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

### GET /exercises - 운동 종목 목록
```json
// Response 200
[
  {
    "id": 1,
    "name": "스쿼트",
    "category": "LOWER",
    "description": "하체 전체 운동",
    "syncThresholdBeginner": 60.0,
    "syncThresholdAdvanced": 85.0
  }
]
```

### POST /exercises/sessions - 운동 세션 시작
```json
// Request
{
  "exerciseId": 1,
  "referenceSource": "youtube:https://youtu.be/xxx"
}

// Response 202 Accepted (비동기 - gRPC 호출이 백그라운드로 진행)
{
  "sessionId": 42,
  "exerciseId": 1,
  "startTime": "2026-03-30T14:00:00",
  "status": "IN_PROGRESS"
}
```
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

### POST /exercises/{exerciseId}/reference - 기준 좌표 추출 요청 (관리자) — ⚠️ 유튜브 URL 은 동작하지 않는다
```
POST /exercises/1/reference?youtubeUrl=<AI 컨테이너 안 파일 경로>

// Response 202
"운동 ID [1]에 대한 기준 좌표 추출이 시작되었습니다."
```
> 파라미터 이름만 `youtubeUrl` 이다 — AI 가 http(s) 를 거부한다(유튜브 다운로드 ToS 미결정, `decisions/youtube-coordinate-harvest.md` §4-2). 위 mp4 업로드 API 가 실사용 경로다.
```
유튜브 URL → AI 가 MediaPipe로 프레임마다 관절 좌표 추출 → Spring 콜백으로 `exercise_references` 테이블 영속화.

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
  "comparisonWithPrevious": {
    "syncRateChange": +5.2,
    "repChange": +3
  },
  "syncRateTimeline": [82.5, 80.1, 75.0, ...]
}
```

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

### PATCH /admin/exercises/{exerciseId}/thresholds - 싱크로율 임계값 변경
관리자 권한(`ROLE_ADMIN`) 필수. 신규 세션부터 적용.
```json
// Request
{
  "syncThresholdBeginner": 65.0,
  "syncThresholdAdvanced": 88.0
}
// 제약: beginner < advanced

// Response 200
{
  "exerciseId": 1,
  "syncThresholdBeginner": 65.0,
  "syncThresholdAdvanced": 88.0
}
```

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
| `GET /notifications?page&size` | 내 알림함 | 최신순 offset(`PageResponse`), `size` 기본 20·최대 100. 보낸 사람이 탈퇴했으면 `sender*` null |
| `PATCH /notifications/{id}/read` | 읽음 처리 | 남의 것·없음 404 `N001`. 이미 읽었으면 처음 시각 그대로 200. «모두 읽음» 없음 |
| `POST /push-tokens` | 푸시 토큰 등록 | `{token, platform}` — `ExponentPushToken[...]` 형식 아니면 400, `platform` = `IOS`\|`ANDROID`. **멱등 200**(신규든 갱신이든). 다른 계정이 같은 토큰을 등록하면 소유자가 옮겨감. 삭제 API 없음 — 로그아웃이 계정 단위로 지움 |

재촉의 전달은 셋 — 알림 행(원천, 항상) · 접속 중이면 소켓(#7, 진행 중) · 등록된 기기가 있으면 Expo Push(아웃박스 `PUSH_NOTIFICATION` 경유, at-least-once). 서버는 «오늘 이미 완료한 상대» 재촉을 막지 않는다 — 버튼 노출은 프론트.

```json
// POST /friends/2/nudge  Response 201 — NotificationDto
{ "id": 55, "type": "NUDGE", "senderId": 1, "senderUsername": "민수", "senderProfileImageUrl": null,
  "targetDate": "2026-09-14", "read": false, "readAt": null, "createdAt": "2026-09-14T08:00:00" }
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
