# 프론트 전달 — 미팅 항목별 API 주소 (2026-09-15)

기준: `origin/main` `5ed4dd13` (2026-09-15). 아래 API 는 전부 머지돼 있다.
계약·응답 예시 원본은 [`../07-api-design.md`](../07-api-design.md), 에러 코드는 [`../17-error-codes.md`](../17-error-codes.md).

## 0. 공통

- 백엔드 로컬 기동: 저장소 루트에서 `cp .env.example .env` 후 `docker compose up -d` (코드 바뀌면 `docker compose build shadowfit-backend` 먼저).
- Swagger: `http://localhost:8080/swagger-ui/index.html` — compose 가 `dev` 프로파일로 띄워서 그냥 뜬다(`application-dev.yml`). `./gradlew bootRun` 으로 띄우면 `--args='--spring.profiles.active=dev'` 필요.
- 인증: `POST /member/login` → `accessToken` 을 `Authorization: Bearer <token>` 으로. 아래 API 는 전부 JWT 필수.
- 소셜 권한 규칙은 하나 — **같은 모임의 ACTIVE 멤버**(아니면 403 `G002`).
- 에러 응답은 `{status, code, message}` 형식(`ErrorResponseDto`).

## 1회차 — 친구 운동 응원 UI

| 화면 | API | 비고 |
|---|---|---|
| 친구 목록(오늘 했는지·연속일수) | `GET /friends` | 내 모임들의 ACTIVE 멤버(나 제외·중복 제거). 응답 `[{memberId, username, profileImageUrl, attendedToday, streak}]`. 정렬: 오늘 완료 → 진행 중 → 기록 없음 |
| 응원(재촉) 버튼 | `POST /friends/{memberId}/nudge` | 201 + 만든 알림. **같은 사람 하루 1회**(2번째 409 `N002`), 자기 자신 400 `N003`. 서버는 «오늘 이미 완료한 상대» 재촉을 안 막는다 — 버튼 숨김은 프론트 |
| 알림함 | `GET /notifications?page&size` | 최신순 offset, `size` 기본 20·최대 100. 보낸 사람 탈퇴 시 `sender*` null |
| 읽음 처리 | `PATCH /notifications/{id}/read` | 남의 것·없음 404 `N001`. «모두 읽음» 없음 |
| 푸시 토큰 등록 | `POST /push-tokens` | `{token:"ExponentPushToken[...]", platform:"IOS"\|"ANDROID"}`. 멱등 200. 로그인 직후 1회. 삭제 API 없음 — 로그아웃이 지움 |
| 실시간 수신(선택) | `WebSocket /ws/groups/{groupId}?token={JWT}` | 재촉이 소켓으로도 온다. 1회차엔 알림함 폴링만으로 성립 |

재촉 전달 경로는 셋 — 알림 행(항상) · 접속 중이면 소켓 · 기기 등록돼 있으면 Expo Push.

## 1회차 — 커뮤니티(모임)

| 화면 | API | 비고 |
|---|---|---|
| 모임 만들기 | `POST /groups` `{name, description}` | 201, 응답 `inviteCode`(8자). 생성자 = OWNER |
| 코드로 참여 | `POST /groups/join` `{inviteCode}` | 대소문자·공백 무시. 승인 없이 바로 ACTIVE → 201. 코드 없음 404 `G008`, 이미 멤버 409 `G003` |
| 내 모임 / 상세 | `GET /groups/mine` · `GET /groups/{groupId}` | 상세에 `members[]`(memberId·username·role·status·joinedAt) |
| 초대 보내기 | `POST /groups/{groupId}/invitations` `{inviteeId}` | 이미 대기중 409 `G006` |
| 받은 초대 / 수락·거절 | `GET /invitations/mine` · `POST /invitations/{id}/accept` · `/decline` | 이미 응답 409 `G005` |
| 초대 코드 재발급 | `POST /groups/{groupId}/invite-code` | OWNER 만(403 `G007`). 이전 코드 즉시 무효 |
| 탈퇴 | `DELETE /groups/{groupId}/members/me` | 남긴 글·리액션은 남는다 |
| 구성원 현황 | `GET /groups/{groupId}/members/status` | `/friends` 와 같은 항목·정렬, 모임 하나로 한정 |
| 출석 캘린더 | `GET /groups/{groupId}/attendance?year&month` | 날짜별 `attendedCount` / `activeMemberCount`. 칸 농도는 프론트 |
| 피드 | `GET /groups/{groupId}/feed?beforeSeq&size` | 최신순 keyset. `beforeSeq` 생략 = 최신부터. 응답 `nextBeforeSeq` 를 다음 요청에(null 이면 끝). `size` 기본 20·최대 100 |
| 리액션 달기 / 취소 | `PUT` / `DELETE /groups/{groupId}/events/{seq}/reactions/{kind}` | `kind` = `HEART` \| `FIRE`. **둘 다 멱등 200**. 응답 `{reactions:{HEART,FIRE}, myReactions:[]}`. 글 없음 404 `G009` |
| 소켓 재연결 백필 | `GET /groups/{groupId}/events?afterSeq` | 오름차순·무페이징. 피드 화면용 아님 |

피드 항목 `type` 은 서버가 만드는 `SESSION_COMPLETED`(payload `{sessionId, memberId, username, exerciseName}`)·`MEMBER_JOINED`(`{memberId, username}`) 둘. `payload` 는 **JSON 문자열**이라 파싱 필요. 소켓으로 보낸 임의 type 도 같은 표에 쌓이므로 **모르는 type 은 무시**할 것. 리액션은 소켓 발행 없음 — 재조회로 반영.

## 2회차 — AI 리포트

| 화면 | API | 비고 |
|---|---|---|
| 끝난 주 리포트 + AI 총평 | `GET /reports/weekly-report?week=YYYY-MM-DD` | `week` = 그 주의 아무 날, 기본 지난주. **첫 조회는 `aiSummarySource=PENDING`**(생성 시작) → 다시 조회하면 `LLM` 또는 `TEMPLATE_FALLBACK`. 이번 주·미래 400 `R002` |
| 이번 주 요약(기존) | `GET /reports/weekly-summary` | 이미 붙어 있음 |
| 세션 리포트(기존) | `GET /reports/session/{sessionId}` | 이미 붙어 있음 |
| 패턴 | `GET /patterns/periodicity` · `/patterns/intensity-trend` · `/patterns/consistency` | 4주치 이상 데이터일 때 의미 있음 |
| 다음 세션 추천 | `GET /recommendations/next-session` | |
| 목표 | `GET`/`POST /goals`, `PATCH`/`DELETE /goals/{goalId}` | 진척은 조회 시 계산 — 프론트가 갱신 호출 안 함 |

`weekly-report` 는 2026-09-14 머지(#758) — 백엔드를 최신 main 으로 띄워야 보인다. PENDING 상태 UI(«분석 중…» + 재조회)가 필요하다.

## 2회차 — 런지 · 운동 set ⚠️ 백엔드·AI 없음

| 항목 | 현재 | 뭐가 필요한가 |
|---|---|---|
| 런지 | DB 에 종목 행은 있으나 `analysis_supported=FALSE`. AI 분석기가 `squat_analyzer.py` 하나뿐 | AI 쪽 런지 분석기 → 그 뒤 `PATCH /admin/exercises/{id}/analysis-support` 로 켬. 백엔드 API 는 새로 만들 게 없다 |
| 운동 set | 미구현(BE-09). `SetSummaryFormatter` 가 «1세트 × N회» 고정 출력이라 화면상만 세트처럼 보임 | proto·DB·API 전부 신규. AI 의 세트 인지(AI-03)와 동시 필요 |

둘 다 2026-09-11 에 **이번 학기 범위에서 뺀 항목**이다(발표는 스쿼트 하나, [`24-semester2-plan.md`](./24-semester2-plan.md) «뺀 것»). 2회차에 정말 넣으려면 그 결정을 뒤집고 AI 트랙까지 같이 잡아야 한다 — 프론트 UI 만 먼저 만들면 붙일 곳이 없다.

## 나중에 — 관리자 페이지

`GET /admin/stats/overview` · `GET /admin/members` · `GET /admin/sessions` · `GET/POST/PATCH/DELETE /admin/exercises` · `/admin/categories` · `PATCH /admin/exercises/{id}/thresholds` · `/analysis-support`. ADMIN 토큰 필요 — 승격 API 없음, DB 에서 `users.role='ADMIN'` 수동 변경 후 재로그인.

## 일정 제약

1차 사용자 테스트(10/12~16)에 **모임 기능이 들어가야** 의미가 있다. 1회차 두 항목이 그 전에 나와야 하고, 밀리면 리액션·1:1 실시간부터 뺀다(재편성 표).
