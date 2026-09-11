# Decision: 친구 현황·재촉하기·모임 피드·출석 캘린더 — 레퍼런스 화면을 어디까지, 어떤 구조로

상태: ✅ **전부 결정됨(2026-09-11, 사용자 confirm)** — §3 분기 A~G, 층 L1, 학기 계획 조정까지. 각 결정은 해당 절 안 ✅ 블록에 박제. 다음은 구현(§4-1 #1~#12)
작성: 2026-09-11
배경: 레퍼런스 앱 화면 6장(홈·커뮤니티·모임 상세·출석 캘린더·루틴 실행·루틴 시작 모달)을 보고 "이런 느낌"으로 응원하기 기능을 만들자는 논의. [`professor-vision-backend-impact.md`](./professor-vision-backend-impact.md) §4 갈래 ②(파트너십)의 구체화이자, 그 문서 §4-1 의 전제("폐기했던 그 테이블들이 그대로 필요") 가 **그룹 테이블 채택(2026-08-30)으로 이미 절반 바뀐** 상태의 재산정.
연관: [`multiuser-realtime-sync.md`](./multiuser-realtime-sync.md)(그룹 4테이블·WS 릴레이 — 이미 구현), [`weekly-monthly-stat-preaggregation.md`](./weekly-monthly-stat-preaggregation.md)(파생값을 미리 계산할지 — 같은 축), [`goal-domain-design.md`](./goal-domain-design.md)(rolling window 를 조회 시점 계산으로 확정한 선례), [`trainer-live-monitoring.md`](./trainer-live-monitoring.md)(1:1 SSE 푸시 선례), [`admin-page-scope.md`](./admin-page-scope.md)(읽기 주체가 늘 때 인덱스가 갈린 선례), [`withdrawal-with-active-session.md`](./withdrawal-with-active-session.md)(탈퇴 시 남의 화면에 남는 데이터)

---

## 0. 한 줄 요약

레퍼런스 화면은 **세 덩어리**다 — ① 루틴 실행(5·6번) ② 모임 컨테이너(1·2·3번) ③ 응원·재촉·출석(1·2·3·4번). ①은 BE-08·BE-09 로 이미 계획표에 있고, ②의 그릇은 이미 구현돼 있다. **이 문서가 새로 결정할 것은 ③** 이고, ③의 핵심은 "누가 오늘 했나·N일째인가·이 달에 며칠 나왔나" 라는 **세션 테이블 파생값**을 **남의 것까지** 읽는 구조다. 팬아웃이 아니라 **파생값의 원천 통일 + 남의 데이터를 읽는 권한**이 설계의 무게중심이다.

---

## 1. 지금 코드에 있는 것 — 출발점

| 부품 | 상태 | 위치·비고 |
|---|:--:|---|
| 모임(그룹) 생성·초대·수락·탈퇴·멤버 목록 | ✅ | `workout_groups`·`group_members`(ACTIVE/LEFT)·`group_invitations` — V12, `GroupController`·`GroupInvitationController` |
| 그룹 이벤트 순서 보장·재연결 백필 | ✅ | `group_events(group_id, seq)` UNIQUE, `GroupEventService.publish` |
| 접속 중인 멤버에게 실시간 전달 | ✅ 단일 인스턴스 | `GroupSocketHandler` — 클라이언트가 보낸 `type` 문자열을 검증 없이 그대로 발행·릴레이. **`CHEER` 타입을 얹으면 코드 변경 없이 지금도 간다**(단, 상대가 소켓에 붙어 있을 때만) |
| 회원×날짜 1행 | ✅ | `daily_logs(member_id, log_date)` UNIQUE — `SessionCompletionTx.accumulateStats` 가 **세션 완료 시** upsert. memo/mood 만 쓴 날도 행이 생김(`upsertMemoAndMood`) |
| 내 연속일수 | ✅ | `SessionActivityQueryService.calculateConsecutiveDays` — `exercise_sessions` 100일 창, **status 전부**(CANCELLED·FAILED 포함, #541 의 인덱스 사정 때문) |
| 내 월 캘린더 | ✅ | `getCalendarMain` — 세션을 월 범위로 읽어 날짜 distinct |
| 주간 목표 진척 | ✅ | `GoalService` — 저장 없이 최근 7일을 조회 시점 계산(선례) |
| 친구 관계 | ❌ | `friendships` 없음 |
| 알림 적재·읽음 | ❌ | `notifications` 없음 |
| 앱 꺼진 상대에게 도달 | ❌ | FCM/Web Push 없음 — 코드·의존성·문서 어디에도 없음 |
| 코드로 참여 | ❌ | 초대가 `invitee_id` 기반 — 상대 계정을 알아야 초대 가능 |
| 피드(글·사진·리액션·댓글) | ❌ | 테이블 없음, 오브젝트 스토리지 없음 |
| Redis | ❌ | 없음 — 그룹 WS 는 단일 인스턴스 전제 |

> `professor-vision-backend-impact.md` §4-1 은 "폐기했던 `friendships`/`activity_feed`/`notifications` 가 그대로 필요" 라고 썼는데, 그 뒤 그룹 4테이블이 채택·구현됐다. **`activity_feed` 자리는 `group_events` 가 이미 차지하고 있고**, 남은 공백은 `friendships`·`notifications` 둘이다.

---

## 2. 화면 → 부품 분해

사용자가 정리한 인벤토리를 **부품 단위**로 다시 쪼갠 것. 같은 부품이 여러 화면에 나온다.

| # | 부품 | 나오는 화면 | 있음/없음 |
|:--:|---|---|:--:|
| P1 | **"오늘 했나" 판정** (재촉하기 버튼 노출 조건) | 1·3·4 | 파생 — 원천 미결(§3-B) |
| P2 | **"N일째 운동 완료/중" streak** | 1·3·4 | 내 것만 있음, 남의 것 없음 |
| P3 | **친구 목록** ("친구의 운동 현황"의 대상 집합) | 1·4 | ❌ (§3-A) |
| P4 | **재촉하기 / 응원보내기** — 저장 + 전달 | 1·2·3·4 | 접속 중 상대에게만 가능 (§3-C) |
| P5 | **모임 컨테이너** — 생성·멤버·초대 | 1·2·3 | ✅ |
| P6 | **코드로 참여** | 1 | ❌ (§3-F) |
| P7 | **모임 피드** — 글·사진·리액션·댓글 | 2 | ❌ (§3-D) |
| P8 | **출석 캘린더** — 멤버 × 월, 칸 농도 | 3 | ❌ (§3-E) |
| P9 | 내 주간 체크·연속 출석 | 4 | ✅ (`getWeeklyActivity`·`calculateConsecutiveDays`) |
| — | 루틴 실행·세트·컨디션 자가보고 (5·6) | 5·6 | BE-08·BE-09 — **이 문서 범위 밖**, §7 에 메모만 |

**P1·P2·P8 은 같은 원천에서 나오는 같은 계산**이다(회원×날짜 출석 집합). 이 셋을 따로 만들면 "오늘 했나"의 정의가 세 군데로 갈린다 — §3-B 가 첫 번째 결정인 이유.

---

## 3. 분기점

### 3-A. 친구 도메인을 따로 만드나, 모임 멤버로 대신하나

레퍼런스는 **친구**(홈의 "친구의 운동 현황")와 **모임**을 둘 다 가진다.

| 후보 | 구조 | 얻는 것 | 대가 |
|---|---|---|---|
| **a. `friendships` 신설** | self M:N + 상태머신(REQUESTED→ACCEPTED/DECLINED→REMOVED, BLOCKED) | 레퍼런스와 1:1. 모임 없이도 1:1 친구 가능 | §4-1 이 적은 그대로 — 대칭 저장(A→B 한 행 vs 두 행)·자기 신청·중복 신청·차단을 제약으로 막을지 코드로 막을지. 탈퇴 시 정리. **신규 상태머신 하나** |
| **b. 친구 = 내가 속한 모임 멤버의 합집합** | 테이블 없음. `group_members` 를 `member_id` 로 역조회 → 같은 그룹의 ACTIVE 멤버 distinct | 신규 테이블 0. 권한 판정이 "같은 모임에 있나" 하나로 통일(§3-G) | 1:1 친구는 **2인 모임**으로 표현해야 함. 모임을 떠나면 친구도 사라짐 — 레퍼런스 UX 와 다름 |
| **c. b 로 시작, a 는 보류** | 홈 "친구의 운동 현황"을 "내 모임 사람들"로 라벨링 | b 의 이득 + 나중에 a 를 얹어도 P1·P2·P4 는 대상 집합만 바뀜 | 레퍼런스 UI 를 문구 수준에서 바꿔야 함 |

> ✅ **결정(2026-09-11, 사용자 confirm): b.** 레퍼런스 6장 어디에도 친구 신청·수락 화면이 없다 — 가입은 "모임 만들기 / 코드로 참여" 둘뿐이다. 그러므로 a(`friendships` 상태머신)는 레퍼런스에 없는 것을 후보로 올린 것이었고, "친구" = 같은 모임의 ACTIVE 멤버로 닫는다. 홈 "친구의 운동 현황"의 대상 집합 = 내가 속한 그룹들의 멤버 distinct(나 제외). 3-F 와 한 묶음으로 결정됨.

b 의 역조회: `findAllByMemberIdAndStatus`(`/groups/mine` 이 이미 씀)는 FK `fk_group_members_member` 가 InnoDB 에 암묵 생성한 `(member_id)` 인덱스를 탄다 — 명시 인덱스는 UNIQUE `(group_id, member_id)` 뿐이지만 FK 인덱스가 있어 회원 선두 조회가 풀스캔은 아니다. status 는 그 뒤 필터. 회원당 그룹 수가 작아 문제 없음.

### 3-B. "출석"의 원천과 정의 — 첫 번째로 정해야 할 것

지금 같은 질문에 답이 둘이다:

| 원천 | 지금 쓰는 곳 | "했다"의 정의 | 남의 것 N명 읽을 때 |
|---|---|---|---|
| **`exercise_sessions`** | `calculateConsecutiveDays`, `getCalendarMain` | 세션 행이 있으면(status 무관) | `member_id IN (...)` + `start_time` 범위 → 인덱스 `(member_id, status, start_time)` 은 status 등치가 없으면 seek 못 함(#541). 회원당 이력 전체를 읽을 위험 |
| **`daily_logs`** | 일지 화면 | 행이 있으면 — 단 memo/mood 만 쓴 날도 행이 생기므로 `total_exercise_time > 0` 같은 추가 조건이 필요 | `member_id IN (...)` + `log_date` 범위 → UNIQUE `(member_id, log_date)` 가 그대로 커버. **회원당 하루 1행이라 읽는 행수 = 인원 × 일수로 상한이 닫힘** |

| 후보 | 내용 | 트레이드오프 |
|---|---|---|
| **a. `daily_logs` 로 통일** | P1·P2·P8 전부 `daily_logs` 에서. 내 streak(`calculateConsecutiveDays`)도 여기로 옮겨 정의를 하나로 | 정의 변경 — 지금은 취소·실패 세션도 streak 에 들어가는데 `daily_logs` 는 **완료 세션만** 채운다. 화면 숫자가 바뀔 수 있음(개선인지 회귀인지는 사용자 판단). "완료로 친다"의 기준이 `total_exercise_time > 0` 인지 세션 카운트 컬럼을 새로 두는지 |
| **b. `exercise_sessions` 유지, IN 조회 추가** | 기존 함수 시그니처를 다중 회원으로 확장 | 정의 불변. 대신 status 등치를 억지로 넣는 #541 식 우회를 다중 회원에도 반복, 읽는 행수가 인원 × 회원당 세션수 |
| **c. 사전집계 테이블** (`member_daily_attendance` 등) | 세션 완료 시 upsert | `daily_logs` 가 **이미 그 테이블**이다 — 하나 더 만들 이유가 없음. [`weekly-monthly-stat-preaggregation.md`](./weekly-monthly-stat-preaggregation.md) §1 의 경고("증거 없이 사전집계 = 포장한 CRUD") 그대로 적용 |
| **d. `exercise_sessions` 원본 + 정의를 COMPLETED 로** | P1·P2·P8 전부 원본에서. "했다" = 그날 `status = COMPLETED` 세션이 1건 이상. `calculateConsecutiveDays` 도 이 정의로 | 정의 변경은 a 와 같음(취소·실패 제외). 대신 **파생값이 없어** 드리프트·백필·삭제 차감이 안 생기고, `status` 등치가 들어가면서 기존 `(member_id, status, start_time)` 이 다중 회원 IN + 범위를 그대로 seek(#541 우회 불필요, start_time 만 읽으니 커버링). 읽는 행 = 인원 × 창 안의 완료 세션수 — a 보다 상수배 크지만 자릿수는 같음 |

**a 를 처음 추천했다가 철회한 이유** — `daily_logs` 는 완료 시 `+분·+칼로리` 를 더하기만 하는 집계라, 세션이 나중에 바뀌면 안 따라간다. 실제로 `SessionService.deleteSession`(`SessionService.java:325`)은 COMPLETED 세션을 지우면서 `daily_logs` 를 안 건드린다 — 지금은 일지 화면의 분·칼로리만 틀리지만, 출석 원천으로 승격하면 "지운 세션 때문에 오늘 한 걸로 남아 재촉 버튼이 안 뜨는" 문제가 남의 화면까지 번진다. 막으려면 차감·재계산·정책 중 하나가 따라와야 하고 그게 곧 정합성 유지 부담이다. 또 `total_exercise_time` 은 `Duration.toMinutes()` 절삭이라 1분 미만 완료 세션이 0 으로 들어가 "했다" 판정에 카운터 컬럼 신설·백필까지 필요했다. 원본을 읽으면 이 전부가 없다.

**d 의 정의 변경은 사실상 정렬이다** — 주간 요약(`WeeklySummaryQueryRepositoryImpl:89`)·목표 진척(`GoalService`)·패턴 분석·리포트가 이미 전부 COMPLETED 만 센다. status 를 안 보던 건 streak 하나였고 그 이유도 의미가 아니라 인덱스 사정(#541)이었다.

> ✅ **결정(2026-09-11, 사용자 confirm): d.** 출석 원천은 `exercise_sessions`, 정의는 "그날 COMPLETED 세션 1건 이상", 날짜 귀속은 `start_time` 의 서버 LocalDate(기존과 동일). `calculateConsecutiveDays` 를 이 정의로 맞춘다 — 취소 세션만 있던 날이 빠져 **내 streak 숫자가 바뀔 수 있음을 알고 결정**. `daily_logs` 삭제 드리프트는 이 결정과 별개의 일지 버그로 [#718](https://github.com/Shadowfit/init/issues/718) 등록.

streak 계산 창(지금 100일)은 근거가 문서화돼 있지 않다. 다중 회원으로 갈 때 창 × 인원이 읽는 행수가 되므로, 창을 정하면 그 근거를 같이 적어야 한다(임의 숫자 금지). 후보: (i) 화면이 보여줄 최대 일수에 맞춤 (ii) 창 없이 정렬 후 첫 끊김까지 커서.

**구현 착수 시 확인한 사실(2026-09-11)** — streak 구현이 이미 둘이다. `SessionActivityQueryService.calculateConsecutiveDays`(내 캘린더, status 전부, 100일 창·근거 없음)와 `PatternAnalysisService.calculateStreak`(`/patterns/consistency`, COMPLETED, 28일 창 = «최근 4주» 정의, 08-30 confirm). 같은 `findDistinctActiveDates` 를 status 인자만 다르게 부른다.

| 창 후보 | 읽는 행 | 정확도 | 근거 |
|---|---|---|---|
| A. 가입일 이후 전체(`users.created_at`) | 회원의 전체 COMPLETED 세션 — 3년차 ~1,000 × 친구 12 = 홈 한 번에 ~12k | 정확 | 자연 상한이라 임의 숫자 0. 대신 «친구가 오래 쓸수록 내 홈이 느려진다» |
| B. 28일(패턴 분석과 통일) | ≤28일치 × 12 | **28에서 캡** | 그 창은 패턴 분석용 정의지 표시 상한이 아님 |
| C. 창 없이 최신순 커서, 첫 끊김에서 중단 | **streak 길이만큼**(+배치 K). 대부분 첫 페이지에서 끝 | 정확 | K 는 결과를 안 바꾸는 배치 크기(fetch size 성격) — 임의 임계값 아님. 대가: 친구 N명 = N쿼리 |

> ✅ **결정(2026-09-11, 사용자 confirm): C.** 읽는 양이 데이터 나이가 아니라 **답의 크기**에 비례한다 — «필요한 만큼만 읽는다». 캘린더·친구 현황·출석은 새 계산기 하나로 모으고, `/patterns/consistency` 의 28일 streak 는 «최근 4주 안에서의 연속»이라는 별개 정의(08-30 confirm)라 **그대로 둔다** — 구현은 셋이 아니라 둘이 되고 둘의 정의 차이(창 유무)는 여기 명시. `calculateConsecutiveDays` 는 새 계산기로 대체되며 status 전부 → COMPLETED 로 바뀌어 내 캘린더 `consecutiveDays` 숫자가 바뀔 수 있다(3-B 에서 수용).

### 3-C. 재촉하기·응원보내기 — 저장과 전달

세 경로는 배타적이지 않다.

| 경로 | 도달 조건 | 필요한 것 | 비고 |
|---|---|---|---|
| **① `group_events` CHEER** | 상대가 그 그룹 소켓에 붙어 있을 때 | 이벤트 타입 하나 + 프론트 렌더 | 지금도 릴레이됨. 붙어 있지 않으면 재연결 백필로 나중에 받긴 하지만 "그룹 채널"이라 **1:1 재촉이 그룹 전원에게 보임** |
| **② `notifications` 테이블 + 앱 내 조회** | 앱을 열면 | `notifications(recipient_id, sender_id, type, ref, read_at, created_at)` + 목록/읽음 API | 앱 꺼진 동안의 재촉을 **잃지 않는** 최소 구조. 전달은 폴링 또는 기존 소켓에 실어 보냄 |
| **③ FCM/Web Push** | 앱이 꺼져 있어도 | 외부 인프라·토큰 저장·Expo 푸시 연동 | 레퍼런스의 "재촉"이 의미 있으려면 결국 이게 필요 — 안 하고 있는 사람은 앱을 안 켠다. professor-vision §3-2 가 이미 "파트너 알림엔 FCM" 이라고 짚음 |

| 후보 | 구성 | 트레이드오프 |
|---|---|---|
| a. ① 만 | 이벤트 타입 추가 | 가장 싸다. 재촉의 대상(오늘 안 한 사람)이 접속해 있을 가능성이 낮아 **기능이 거의 안 보임** |
| b. ② 저장 + ① 실시간 | 알림 행을 쓰고, 접속 중이면 소켓으로도 밀어줌 | 유실 없음, 외부 인프라 없음. "앱 열었더니 재촉이 와 있다"까지 |
| c. b + ③ | 알림 행 → 아웃박스 → FCM | 완성형. **`OutboxEvent` 가 두 번째 용처를 얻는다**(전문가 연계 문서가 기대한 그 그림). 대신 새 인프라 하나 |

> ✅ **결정(2026-09-11, 사용자 confirm): c — ② `notifications` 저장 + ① 접속 중이면 소켓 즉시 전달 + ③ 푸시.** 저장이 원천이고 소켓·푸시는 전달 수단이다. 푸시는 **알림 행 → 아웃박스 → 외부 푸시 서비스** 경로로 간다 — `OutboxEventType` 이 enum + `OutboxPublisher:135` 의 switch 분기라 `PUSH_NOTIFICATION` 타입 하나 추가로 두 번째 용처가 열린다(첫 용처는 AI `STOP_ANALYSIS`/`REATTACH_ANALYSIS`). 새로 생기는 것: `notifications` 테이블, **디바이스 토큰 테이블**(`push_tokens(member_id, token, platform, updated_at)` — 회원당 기기 여러 개), 프론트 `expo-notifications`(현재 `frontend/package.json` 에 없음), 외부 푸시 자격 증명.
>
> ✅ 하위 결정 ①(2026-09-11): **Expo Push Service.** 토큰 = ExpoPushToken, 서버는 `exp.host` HTTPS 한 곳으로 POST, Expo 가 FCM/APNs 로 중계. 프론트가 Expo 54 관리형이라 자격 증명 면적이 가장 작다. 도달 여부는 Expo receipt API 로 따로 조회 — 아웃박스의 SENT 는 "Expo 가 받았다"이지 "폰이 울렸다"가 아니다(이 구분은 `outbox-reliable-messaging.md` 의 at-least-once 의미와 같은 층).
> ✅ 하위 결정 ②(2026-09-11): **남발 방지 — 같은 사람에게 같은 날 같은 종류 1회**, `notifications` UNIQUE `(sender_id, recipient_id, type, target_date)` 로 DB 가 막는다. "하루"는 임의값이 아니라 P1("오늘 했나")의 판정 단위에서 따라오는 주기 — 재촉의 근거가 하루 단위로 갱신되므로 재촉도 하루 단위. 2회째는 409 로 답한다(`goals` UNIQUE 위반 처리와 같은 모양).

**남발 방지**는 저장 구조에서 갈린다 — "같은 사람에게 같은 날 재촉 1회" 같은 규칙을 둔다면 `notifications` 의 UNIQUE `(sender_id, recipient_id, type, date)` 로 DB 가 막을 수 있고(`daily_logs`·`goals` 의 선례), 규칙 자체(하루 1회인지, 몇 회인지)는 근거가 있어야 정한다.

### 3-D. 모임 피드 — 어디까지

레퍼런스는 글+사진+리액션 2종+댓글+응원보내기.

| 조각 | 신규 | 무게 |
|---|---|---|
| 글(텍스트) | `group_posts(group_id, author_id, body, created_at)` | 가볍다 — 또는 **`group_events` 의 이벤트 타입**(POST)으로 흡수 가능. 피드 = 이벤트 로그의 일부 타입만 필터 |
| 사진 | 오브젝트 스토리지(S3 호환) + presigned URL 업로드 + URL 저장 | **새 인프라**. 로컬/EC2 실측 환경에 스토리지가 없음. 프로필 이미지(`users.profile_image_url`)도 URL 만 있고 업로드 경로가 없음 |
| 리액션 💗🔥 | `post_reactions(post_id, member_id, kind)` UNIQUE + 카운트 | 카운트를 `COUNT(*)` 로 셀지 컬럼으로 denormalize 할지 — 모임 12명이면 어느 쪽도 문제 없음. **핫 카운터 서사는 이 규모에서 안 선다**(팬아웃과 같은 이유) |
| 댓글 | `post_comments` | 가볍다 |
| "오늘 스쿼트 20개 3세트 완료!" 자동 글 | 세션 완료 → 그룹 이벤트 발행 | `SessionCompletionTx` 에서 `group_events` 에 SESSION_COMPLETED 발행. 아웃박스 경로가 이미 그 트랜잭션에 있음 |

| 후보 | 트레이드오프 |
|---|---|
| a. 피드 전체 | 레퍼런스 그대로. 테이블 3 + 스토리지. professor-vision §4-3 이 말한 "중간 값" 그대로 — 규모가 작아 DB 서사 없음 |
| b. **자동 글 + 리액션만, 사진·댓글·수기 글 없음** | 세션 완료 이벤트가 곧 피드. 신규 테이블 1(`post_reactions` 상당 — 대상이 `group_events.id`). 스토리지 없음 |
| c. 피드 안 함 — `group_events` 를 프론트가 피드처럼 그림 | 신규 0. 리액션·댓글 없음 |
| a′. 수기 글 + 댓글까지, 사진 제외 | 테이블 2, 인프라 0. SNS 느낌은 나되 스토리지는 안 엶 — 나중에 사진을 얹을 때 payload 의 URL 필드만 채우면 되는 구조 |

> ✅ **결정(2026-09-11, 사용자 confirm): b 로 시작, a 는 후속.** 1차는 **세션 완료 자동 글 + 리액션(💗🔥)**. 자동 글은 `SessionCompletionTx` 안에서 회원이 속한 ACTIVE 그룹마다 `group_events` 에 `SESSION_COMPLETED` 를 INSERT — 같은 DB·같은 트랜잭션이라 아웃박스 불필요. 이건 fan-out-on-write 이고 그룹 수가 한 자릿수라 문제가 아니다 — **"팬아웃이 왜 여기선 문제가 아닌가"를 숫자(회원당 그룹 수 분포)로 적을 자리.** 리액션은 `event_reactions(event_id, member_id, kind)` UNIQUE, 카운트는 `COUNT(*)`(12명 규모에 denormalize 근거 없음). **리액션은 알림을 안 보낸다**(피드에서만 보임) — 3-C 재촉·응원 알림과 역할을 분리. a(수기 글·댓글·사진)는 **별도 결정으로 후속** — 사진은 오브젝트 스토리지(로컬 MinIO + S3) 결정이 선행돼야 하고, 수기 글·댓글은 append-only 로그(`group_events`)에서 삭제를 어떻게 표현할지가 새 질문이다.

### 3-E. 출석 캘린더 — 계산 위치

3-B(d) 기준으로 이건 쿼리 하나다:

```
SELECT DATE(start_time) AS d, COUNT(DISTINCT member_id) FROM exercise_sessions
 WHERE member_id IN (<ACTIVE 멤버>) AND status = 'COMPLETED' AND start_time BETWEEN <월초 00:00> AND <월말 23:59:59>
 GROUP BY d
```

칸 농도 = 그날 출석 인원 / 멤버 수. seek 는 `(member_id, status, start_time)` 이 회원별로 받고, `DATE()` 그룹핑은 seek 로 걸러진 수백 행 위에서만 돈다(함수 그룹핑이 인덱스를 못 타는 건 여기선 상관없음 — 필터가 인덱스, 그룹핑은 결과 위). 사전집계·캐시 근거 없음 — [`weekly-monthly-stat-preaggregation.md`](./weekly-monthly-stat-preaggregation.md) §1 과 같은 판단. 결정할 것은 **분모**(현재 ACTIVE 멤버 수인지, 그 날짜 시점 멤버 수인지 — LEFT 멤버의 과거 출석을 셀 것인가)뿐이다. `group_members` 에 `left_at` 이 없어서 시점 분모는 지금 못 구한다.

> ✅ **결정(2026-09-11, 사용자 confirm): a — 분모·분자 모두 현재 ACTIVE 멤버.** 농도 = 그날 COMPLETED 세션이 있는 현재 ACTIVE 멤버 수 / 현재 ACTIVE 멤버 수. LEFT 멤버의 과거 출석은 안 센다(IN 리스트에 ACTIVE 만). 정의가 "지금 모임 사람들이 그날 몇 명 했나" 하나로 닫힌다. 시점 분모(b)는 `left_at` 이력 설계가 따라오는데 그 정확도를 요구하는 화면이 없어 택하지 않음.
>
> ⚠️ **레퍼런스 칸 농도(진함/연함/흰색)의 의미는 추정이다** — 3번 화면에 설명이 없다. 인원 비율 / 전원 여부 / 나 기준 세 해석이 가능하고, 셋 다 서버 응답(날짜별 출석 인원수 + 멤버 수)은 같고 프론트 매핑만 다르다. 서버는 날짜별 `count` 와 `activeMemberCount` 를 주고 농도 매핑은 프론트 몫으로 둔다. 이 캘린더는 **모임 상세 화면**의 것이다(내 개인 달력은 `getCalendarMain` 으로 별도 존재).

### 3-F. 코드로 참여

| 후보 | 구조 | 트레이드오프 |
|---|---|---|
| a. `workout_groups.invite_code` UNIQUE | 그룹당 코드 1개, 생성 시 발급 | 가장 단순. 만료·회전 없음 → 유출되면 그룹장이 재발급하는 API 필요 |
| b. `group_invite_codes(group_id, code, expires_at, max_uses)` | 코드 여러 개, 만료 | 레퍼런스에 만료 UI 는 없음 — 필요 근거 없이 넣으면 임의 기능 |

어느 쪽이든 기존 `group_invitations`(초대→수락) 와 **가입 경로가 둘**이 된다. 코드 참여는 승인 없이 바로 ACTIVE 인지, 그룹장 승인 대기(`PENDING` 상태 추가)인지 — 레퍼런스에서 확인 안 됨.

> ✅ **결정(2026-09-11, 사용자 confirm): a — 코드 참여 추가, 기존 지목 초대(`group_invitations`) 는 유지(공존).** 코드는 **그룹당 1개 고정**(`workout_groups.invite_code` UNIQUE, 생성 시 발급) — 레퍼런스에 만료 UI 가 없어 만료형은 근거 없는 기능. 코드 참여는 **승인 없이 바로 ACTIVE** (레퍼런스 흐름: 카톡으로 코드 공유 → 입력 → 멤버). 구현 시 주의: `GroupInvitationService.accept()` 의 "LEFT 행 되살리기"(`GroupInvitationService.java:79-83`)를 코드 참여 경로에도 똑같이 태워야 한다 — 안 그러면 나갔다 재참여 시 UNIQUE`(group_id, member_id)` 위반 500 이 재발한다. 유출 시 재발급 API(그룹장만)는 코드 1개 고정의 필연적 짝이라 같이 간다. 교체안(b, 지목 초대 제거)은 8/30 채택·구현분을 걷어내는 것이라 택하지 않음.
>
> ✅ **하위 결정(2026-09-11): 코드 형식 = 8자리, 대문자+숫자에서 헷갈리는 글자(0/O, 1/I) 제외한 32자.** 32⁸ ≈ 1.1×10¹² — 그룹 수가 수만 개여도 충돌은 무시 가능하고, 레퍼런스 1번의 "코드로 참여" 입력란이 손 입력을 전제하므로 사람이 읽고 칠 수 있는 길이여야 한다(근거 둘). UNIQUE 충돌 시 재생성 재시도. `POST /groups` 가 생성 시 발급해 응답에 `inviteCode` 를 싣는다.
> ✅ **하위 결정(2026-09-11): `workout_groups.description` 추가** — 레퍼런스 1·2번의 그룹명 아래 한 줄("우리 진짜 거북목 되지 말자"). `invite_code` 와 같은 마이그레이션(V15)에 묶는다. `CreateGroupRequestDto`·`GroupResponseDto` 에 필드 추가.

### 3-G. 남의 출석·streak 를 보는 권한

professor-vision §2 의 "행 단위 접근 제어" 가 여기서 처음 실제로 필요해진다.

| 후보 | 규칙 | 트레이드오프 |
|---|---|---|
| a. **같은 모임 = 공개** | 같은 ACTIVE 그룹에 있으면 상대의 출석/streak/오늘 여부를 본다. 그 외엔 안 보임 | 판정이 `group_members` 조인 하나. 3-A(b) 와 맞물리면 권한 모델이 테이블 하나로 끝남. **가입 = 동의** 라는 전제를 UI 문구로 알려야 함 |
| b. 지표별 공개 설정 | `users` 또는 별도 테이블에 "출석 공개/streak 공개" 플래그 | §4-3 이 말한 동의 모델. 규모 무관하게 설계 난이도 있음. 레퍼런스에 이 UI 없음 |

노출되는 것이 **출석 여부·연속일수** 뿐이라면 자세 안정성 점수 같은 건강 지표는 안 나간다. 무엇을 노출하는지 목록으로 못박아야 a 가 성립한다.

> ✅ **결정(2026-09-11, 사용자 confirm): a — 같은 ACTIVE 그룹 = 공개.** 판정은 "요청자와 대상이 같은 그룹에 둘 다 ACTIVE 인가" 하나(`group_members` 조인). 그 외 관계에서는 아무것도 안 보인다. **남에게 노출되는 항목은 다음 셋으로 고정**:
> 1. 오늘 완료 여부 (P1 — 재촉 버튼 노출 조건)
> 2. 연속일수 (P2)
> 3. 그 달의 출석 날짜 — 단 캘린더는 **인원수로 합산**해 보여주므로 개인별 날짜가 식별되진 않음 (P8)
>
> 자세 안정성 점수·rep 수·칼로리·세션 상세 등 **건강 지표는 노출 항목이 아니다.** 이 목록이 a 의 성립 조건이며, 목록을 넓히는 요구(예: 친구에게 싱크로율 공개)가 오면 그때 b(지표별 동의)로의 승격을 별도 결정한다. 코드 참여 화면에 "참여하면 모임 사람들에게 출석 여부와 연속일수가 보입니다" 문구를 둔다 — 가입 = 동의의 최소 고지.

---

## 4. 후보 묶음 — 세 층위

| 층 | 구성 | 신규 테이블 | 신규 인프라 | 서사 |
|---|---|:--:|:--:|---|
| **L0 최소** | 3-A(b) 모임 멤버 = 친구 ✅, 3-B(d) 원본+COMPLETED ✅, 3-F(a) 코드 참여 ✅, 3-C(c) 알림 저장+소켓+푸시 ✅, 3-E 캘린더, 3-G(a) | `notifications`·`push_tokens` 2 | **푸시 서비스(Expo Push 또는 FCM)** | "출석 파생값의 원천을 하나로 모으고, 읽기 주체가 늘어난 자리에서 인덱스가 어떻게 갈렸나" — `admin-page-scope.md` 의 연장 |
| **L1 레퍼런스 근접** | L0 + 3-D(b) 자동 글·리액션 ✅ | +1 | 없음 | L0 + 세션 완료 → 그룹 이벤트 발행(아웃박스 두 번째 용처) |
| **L2 레퍼런스 완성** | L1 + 3-D(a) 피드 전체 (~~3-A(a) friendships~~·~~3-C(c)~~ — 각각 제외·L0 로 승격) | +2~3 | 오브젝트 스토리지 | §4-2 결론 그대로 — 팬아웃 서사 없음, "중간 값" |

§3 결정 조합이 정확히 **L1** 이다(L0 + 자동 글·리액션). 아래는 [`multiuser-realtime-sync.md`](./multiuser-realtime-sync.md) §7 과 같은 방식의 견적 — 작업 단위, 단독 vs Claude 병행(단독의 60~65%, `28-remaining-work-plan.md` 관행). **백엔드만**이고 프론트는 별도 행.

### 4-1. L1 견적 (2026-09-11)

| # | 작업 | 단독 | Claude 병행 | 비고 |
|:--:|---|:--:|:--:|---|
| 1 | V15(`description`·`invite_code`) + 엔티티/DTO + 코드 발급(충돌 재시도) + `POST /groups` 응답 + 재발급 API(그룹장) | 2~3h | 1.5~2h | 기존 그룹 행 백필 여부는 운영 DB 확인 후 |
| 2 | `POST /groups/join` — 코드 → ACTIVE, `accept()` 의 LEFT 되살리기 재사용, `MEMBER_JOINED` 발행 | 2~3h | 1.5~2h | 되살리기를 공통 메서드로 뽑아 두 경로가 같이 쓰게 |
| 3 | 출석 정의 통일 — `calculateConsecutiveDays` COMPLETED 기준, 다중 회원 IN 쿼리(`findDistinctActiveDates` 다중판), streak 창 근거 확정 | 2~3h | 1.5~2h | 인덱스 `(member_id, status, start_time)` 플랜 확인 포함 |
| 4 | `GET /groups/{id}/members/status`(오늘 여부·연속일수) + `GET /feed/friends`(내 모임 사람 distinct) + 권한 가드(같은 ACTIVE 그룹) | 3~4h | 2~2.5h | 3-G 노출 항목 3개만 응답에 싣는지 테스트로 고정 |
| 5 | `GET /groups/{id}/attendance?year&month` — 날짜별 `COUNT(DISTINCT member_id)` + `activeMemberCount` | 2h | 1~1.5h | 농도 매핑은 프론트 |
| 6 | `notifications` 테이블·엔티티 + `POST /members/{id}/nudge`(UNIQUE 위반 → 409, 권한 가드) + `GET /notifications` + 읽음 처리 | 3~4h | 2~2.5h | UNIQUE 위반 처리는 `goals` 선례 |
| 7 | **1:1 소켓 전달** — 현재 `GroupSocketRegistry` 는 그룹→세션 집합뿐이라 개인에게 밀 수 없음. 회원→세션 레지스트리 추가 + nudge 시 접속 중이면 즉시 전달 | 2~3h | 1.5~2h | 그룹 채널에 실으면 3-C ①의 "전원에게 보임" 문제 재발 — 그래서 별도 레지스트리 |
| 8 | `push_tokens` 테이블 + `POST /push-tokens`(갱신·삭제 포함) | 1.5~2h | 1~1.5h | 회원당 기기 여러 개 |
| 9 | 아웃박스 `PUSH_NOTIFICATION` 타입 + Expo Push HTTP 클라이언트 + `OutboxPublisher` 분기 + 응답 분류(RETRY / TERMINAL, `DeviceNotRegistered` 면 토큰 삭제) | 4~5h | 2.5~3h | receipt API 조회는 이 견적 밖(SENT = Expo 수신까지) |
| 10 | 자동 글 — `SessionCompletionTx` 에서 ACTIVE 그룹마다 `group_events` `SESSION_COMPLETED` INSERT + 회원당 그룹 수 분포 기록 | 1.5~2h | 1~1.5h | ⚠️ `GroupEventService.publish` 가 `workout_groups` 행을 `PESSIMISTIC_WRITE` 로 잠그고 seq 를 채번한다 — 완료 트랜잭션 안에서 그룹 N개를 순서대로 잠그면 소켓 발행 경로와 **잠금 순서가 엇갈릴 수 있음**. group_id 오름차순으로 고정하고 데드락 테스트 1개 |
| 11 | 리액션 — `event_reactions` + POST/DELETE + 피드 조회 응답에 카운트·내 리액션 | 2.5~3h | 1.5~2h | 카운트는 `COUNT(*)` |
| 12 | 통합 테스트(코드 참여→완료→자동 글→리액션→재촉→알림 흐름) + API 문서 갱신 | 3~4h | 2~2.5h | |
| **합계** | | **≈28~38h (중앙값 ≈33h)** | **≈19~25h (중앙값 ≈22h)** | |
| 프론트(참고) | `expo-notifications` 토큰 등록, 코드 참여 화면 + 동의 문구, 친구 현황·재촉 버튼, 모임 캘린더, 피드 리액션 | — | — | 백엔드 견적에 미포함. 별도 산정 |
| 비코딩(참고) | Expo 계정·EAS 프로젝트 ID·푸시 자격 증명, 실기기 테스트 | — | — | 코딩 속도와 무관 |

**주당 8h 가정 시 Claude 병행 ≈3주.** [`multiuser-realtime-sync.md`](./multiuser-realtime-sync.md) §7 의 그룹 WS(≈27~35h 병행)보다 작다 — 그릇(그룹 4테이블·소켓)이 이미 있어서다. 가장 무거운 건 #9(푸시 발행)이고, 3-C 를 b(푸시 미룸)로 했다면 #8·#9 가 빠져 ≈14~19h 였다 — c 의 대가가 이 5~6h 다.

**견적이 틀리기 쉬운 곳**: #7(회원별 소켓 레지스트리 — 지금 구조에 없는 개념), #10(잠금 순서). 나머지는 기존 패턴의 반복이다.

---

## 5. 추천 (결정 아님)

- ~~**3-B 를 먼저 정한다.** `daily_logs` 통일 추천~~ → **3-B 는 d(원본+COMPLETED)로 결정됨.** 처음엔 `daily_logs` 통일을 추천했다가 삭제 드리프트(§3-B)를 확인하고 철회 — 읽기 상수배를 사기 위해 정합성 유지 코드를 들이는 교환은 손해라는 판단.
- **층은 L0 또는 L1.** L2 의 추가분(friendships·사진·FCM)은 §4-2·§4-3 판정("팬아웃 없음, 중간 값")을 바꾸지 못하면서 인프라 둘을 더한다. 다만 FCM 없는 "재촉하기"는 앱을 연 사람에게만 닿는 기능이라는 점은 정직하게 적어야 한다 — 그게 싫으면 3-C(c) 만 L1 에 얹는 변형도 가능.
- **3-D 는 b(자동 글 + 리액션)** — 수기 글·사진은 이 프로젝트가 잘 하는 축(DB) 밖이고, 자동 글은 이미 있는 `group_events` 에 타입 하나 더하는 일이다.
- 결정 뒤 `professor-vision-backend-impact.md` §4-1 의 전제(테이블 3개 신규)를 정정한다 — 그룹은 있고, `activity_feed` 는 `group_events` 가 대신하며, 남은 공백은 둘이다.

---

## 6. 미결정 (사용자 confirm 필요)

- [x] **3-B** 출석 원천 — ✅ **d. `exercise_sessions` 원본 + COMPLETED 정의** (2026-09-11). streak 정의 변경 수용. 하위(streak 창) — ✅ **C. 창 없이 최신순 커서, 첫 끊김에서 중단** (2026-09-11, 구현 #3)
- [x] **3-A** 친구 도메인 — ✅ **b. 친구 = 같은 모임 ACTIVE 멤버** (2026-09-11). friendships 안 만듦
- [x] **3-C** 재촉 전달 — ✅ **c. 저장+소켓+푸시, Expo Push, 하루 1회 UNIQUE** (2026-09-11)
- [x] **3-D** 피드 범위 — ✅ **b. 자동 글 + 리액션, a(수기 글·댓글·사진)는 후속 결정** (2026-09-11)
- [x] **3-E** 캘린더 분모 — ✅ **a. 현재 ACTIVE 멤버(분자·분모 동일)** (2026-09-11). 농도 매핑은 프론트, 레퍼런스 의미는 추정으로 표기
- [x] **3-F** 코드 참여 — ✅ **a. 추가·공존, 그룹당 코드 1개 고정, 승인 없이 ACTIVE, 재발급 API 동반** (2026-09-11)
- [x] **3-G** 공개 범위 — ✅ **a. 같은 모임 = 공개, 노출 항목 3개 고정(오늘 여부·연속일수·합산 출석)** (2026-09-11)
- [x] 층 — ✅ **L1** (결정 조합상 확정, 2026-09-11). 견적 §4-1: 단독 ≈28~38h / 병행 ≈19~25h
- [x] `24-semester2-plan.md` 에서 **무엇을 뺄지** — ✅ (2026-09-11) BE-09 세트+런지·플랭크 결합(12h) · 2차 사용자 테스트(6h+) · cleanup 축소(4h) = 22h. 7주 재편성(L1 → 테스트 준비 → 1차 테스트 → 핫픽스 → 발표)은 [`../tasks/24-semester2-plan.md`](../tasks/24-semester2-plan.md) 머리 블록

---

## 7. 범위 밖 메모 — 5·6번 화면(루틴 실행)

이 문서가 다루지 않지만 같은 레퍼런스에서 나온 것:

| 화면 요소 | 대응 |
|---|---|
| 오늘의 루틴 3개 운동 + "AI PT" 라벨 | BE-08 `GET /recommendations/today` — `decisions/recommendation-algorithm.md` 신설이 선행 |
| 10회 × 3세트 | BE-09 세트 개념(🟦 보류) — proto 양쪽 수정 |
| 컨디션 상태 / 불편한 부위 드롭다운 | 세션 시작 전 자가보고 — `exercise_sessions` 에 컬럼 없음. "불편한 부위"는 갈래 ③(전문가 연계) 위험 판정의 입력 후보 |
| 1/3 → 3/3 진행, 운동별 타이머 | "루틴 1회 실행" 단위가 없음 — 세션 1행 = 운동 1개. 상위 키(routine_run) 를 둘지는 BE-08 설계에서 |
| 어깨 돌리기·런지 | 스쿼트 외 운동 — 기준 좌표·AI 판정이 스쿼트만. 후속 |

---

## 결정 로그

- 2026-09-11 (11): **3-B 하위 streak 창 — C(커서, 첫 끊김 중단).** 구현 #3 착수 시 streak 구현이 둘(캘린더 100일·status 전부 / 패턴 분석 28일·COMPLETED)임을 확인. 패턴 분석 쪽은 별개 정의라 유지.
- 2026-09-11 (10): **학기 계획 조정 확정** — 24 문서 실측 점검(기능 축 BE-05~08 전부 완료 확인) 후 BE-09+종목 결합·2차 테스트·cleanup 축소로 22h 확보. 미결 0.
- 2026-09-11 (9): **층 L1 확정 + §4-1 견적.** 12개 작업, 단독 ≈33h / 병행 ≈22h 중앙값. 푸시(#8·#9)가 c 선택의 대가 5~6h.
- 2026-09-11 (8): **3-F 하위 — 코드 8자리(혼동 글자 제외 32자), `description` 컬럼 추가.** `POST /groups` 응답에 `inviteCode` 포함.
- 2026-09-11 (7): **3-E 결정 — a(현재 ACTIVE 분모·분자).** 이로써 §3 분기 7개(A~G) 전부 확정. 남은 미결은 층(L0/L1 — 결정 조합상 사실상 L1)과 학기 계획에서 뺄 항목.
- 2026-09-11 (6): **3-D 결정 — b(자동 글+리액션) 먼저, a 는 후속.** a 의 실제 무게가 사진(오브젝트 스토리지 신설)임을 확인하고 단계화. 리액션은 알림 안 보냄으로 3-C 와 분리.
- 2026-09-11 (5): **3-G 결정 — a(같은 모임 = 공개), 노출 항목 3개 고정.** 건강 지표 비노출을 성립 조건으로 명시.
- 2026-09-11 (4): **3-C 결정 — c(저장+소켓+푸시).** 추천은 b(푸시 미룸)였으나 사용자가 c 선택. 푸시는 아웃박스 두 번째 용처로 — `OutboxEventType` enum 에 타입 추가. 하위: **Expo Push + 하루 1회 UNIQUE** 로 같은 날 confirm.
- 2026-09-11 (3): **3-A·3-F 결정 — 친구 = 모임 멤버(b), 코드 참여 추가·공존(a), 코드 그룹당 1개 고정.** 사용자 지적("초대코드 보내서 모임 만드는 거 아님?")으로 레퍼런스에 친구 신청·수락이 없음을 확인 → friendships 후보 자체가 레퍼런스 밖이었음. 3-A 와 3-F 가 한 결정으로 묶임.
- 2026-09-11 (2): **3-B 결정 — d(원본 `exercise_sessions` + COMPLETED)**. 사용자가 `daily_logs` 통일로 먼저 기울었다가 트레이드오프를 물었고, 삭제 경로 드리프트(`deleteSession` 이 `daily_logs` 를 안 건드림)·분 절삭(1분 미만 세션 = 0)이 확인되면서 추천을 철회, 셋째 길로 confirm. 정의 변경은 나머지 집계(주간 요약·목표·패턴·리포트)가 이미 COMPLETED 만 세는 것에 맞추는 정렬.
- 2026-09-11: 최초 작성. 레퍼런스 화면 6장을 부품 9개로 분해, 그룹 테이블 채택(08-30) 이후의 공백을 `friendships`·`notifications` 둘로 좁힘. **핵심 발견 — `daily_logs` 가 이미 회원×날짜 출석 행이고, "오늘 했나·N일째·월 출석" 셋이 같은 계산인데 지금 코드는 원천이 둘(`exercise_sessions` vs `daily_logs`)로 갈려 있다.** 3-B(원천 통일)를 첫 결정으로 추천. 채택 여부 전부 미결.
