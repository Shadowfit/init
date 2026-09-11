# 처음으로 돌아간다면 — 재설계 후보 (2026-09-11)

작성일: 2026-09-11
상태: **분석/회고 — 새 결정 없음.** 후보와 트레이드오프까지만 적는다. 「지금 고칠 것인가」는 §4 의 분류를 놓고 사용자가 정한다
대상: [`architecture-review-2026-08-11.md`](./architecture-review-2026-08-11.md) §2「다시 조립한다면」이 잡은 **두 축**(프레임 경로를 스트림으로 · 세션의 AI 인스턴스 소유권)은 **반복하지 않는다.** 그 문서가 안 본 축 — 데이터 모델·검증 구조·제품 표면·계약 배치 — 을 본다
연관: [`project-destination-and-exit-criteria.md`](./project-destination-and-exit-criteria.md) E4(«모른다» 로 답할 자리가 없다), [`db-portfolio-roadmap.md`](./db-portfolio-roadmap.md), [`../tasks/32-deferred-items.md`](../tasks/32-deferred-items.md)

---

## 0. 찾는 방법 — 결정 문서가 많이 매달린 자리가 빚이 있는 자리다

「아쉬운 점」을 감으로 고르면 최근에 아팠던 것만 나온다. 대신 **처음 설계 하나가 그 뒤로 몇 개의 결정 문서를 낳았는지** 를 센다. 문서가 많이 매달렸다는 건 그 설계가 계속 값을 치르게 했다는 뜻이다.

`docs/decisions/` 122건(README 포함)을 파일명 접두어로 묶으면:

| 뿌리 | 문서 수 | 무엇이 매달렸나 |
|---|:--:|---|
| `ai-*` | 18 | 인증 토큰 흐름·채널 풀·스티키 라우팅(2)·세션 소유 검증·프레임 경로 백프레셔·멀티프로세스(2)·수평 확장·… |
| `session-*` + reattach/outbox/circuit | 7 + 5 | 종료 트리거·재개와 AI 상태·liveness·검출기 소유권·outbox(2)·서킷브레이커 워커별 분리 |
| `pose-*` + partition/row-shape/covering | 6 + 4 | 파티션-FK 트레이드오프·멱등키 vs 파티션·다운샘플링·base64 비용·행 모양-파티션 상호작용·구멍 DROP 유의성 |
| `reference-*` | 4 | 스타일 정체성·캐싱·freeze·knee 분산 |

셋이 한 뿌리로 모인다 — **①「AI 가 두 번째 공개 표면이다」, ②「원시 좌표를 용도 없이 행으로 쌓았다」, ③「기준을 정적 산출물이 아니라 프레임 행으로 뒀다」.** 08-11 회고가 ①의 절반(상태 소유권)을 다뤘고, 이 문서는 나머지를 다룬다.

---

## 1. 후보 — 경계

### A1. AI 서버가 두 번째 공개 표면이다

`frontend --(HTTP, 카메라 프레임)--> ai-server` 직결(분기 H2). 폰이 Spring 과 AI **둘 다**에 직접 붙는다.

**여기서 파생된 것 (전부 이 한 결정의 대가다):**

| 파생 | 근거 |
|---|---|
| 앱 번들에서 추출 가능한 토큰으로 AI 를 지킨다 | [`ai-auth-token-flow.md`](./ai-auth-token-flow.md) §1, #134 |
| 세션 ID 가 순차 정수라 추측되므로 nonce 를 REST 응답과 gRPC 양쪽으로 흘린다 | `exercise.proto` `AnalyzeRequest.session_nonce`, #187 |
| 세션 시작 응답이 워커 인덱스를 알려주고 폰이 프레임마다 `X-AI-Worker` 헤더를 실어야 한다 | `exercise.tsx:79`, [`ai-sticky-routing.md`](./ai-sticky-routing.md) |
| 프레임 경로 앞에 `ai-nginx` 라는 라우터가 하나 더 선다 | `docker-compose.yml`, `nginx-ai` |
| 같은 CORS 실수·같은 문서 노출 실수가 **두 서비스에 복제**됐다 | 08-11 회고 ⑩·⑪ — "한쪽을 베낀 설정이거나 같은 프레임워크 기본값 습관" |
| `POST /pose` 입력 크기 무제한이 DoS 면이 됐다 | 08-11 회고 ⑫ |

**처음이라면**: 공개 ingress 를 **하나**로 둔다. 폰은 Spring(또는 게이트웨이 한 층)에만 붙고, AI 는 사설망 안의 **순수 추론 서비스**가 된다. 토큰·nonce·워커 헤더·nginx 가 전부 필요 없어진다 — 세션↔워커 매핑은 Spring 이 이미 갖고 있으므로(`V14__add_ai_instance_endpoint.sql`) Spring 이 곧바로 그 워커에 보낸다.

**트레이드오프**:
- Spring 이 프레임 핫패스에 선다 — 3fps × 동시 세션의 바이너리를 자바 서버가 중계한다. **이 비용은 잰 적이 없다.** 직결(H2)을 고른 이유가 바로 이 홉을 빼는 것이었고, 그 판단 자체는 틀리지 않았다 — 다만 그 대가(위 표 6줄)를 그때는 몰랐다.
- 08-11 §2-① 의 WS 안과 겹친다. WS 로 가면 「폰→AI 연결 1개」가 되는데, 그 연결의 **종점이 AI 인지 Spring 인지** 가 이 항목의 질문이다. 둘은 독립 결정이 아니다.

### A2. gRPC 계약이 두 저장소에 바이트 단위로 복제돼 있다

`backend/src/main/proto/exercise.proto` 와 `ai-server/app/proto/exercise.proto` 가 같아야 하고, CI(`proto-sync-check.yml`)가 diff 로 잡는다. CLAUDE.md 가 첫 화면에서 경고하는 항목이다.

**처음이라면**: 루트에 `proto/` 하나. Gradle 의 `protobuf` 플러그인과 `grpcio-tools` 둘 다 **소스 디렉터리를 지정할 수 있으므로** 양쪽 빌드가 같은 파일에서 생성하면 된다. 복제도, CI 도, 경고문도 사라진다.

**트레이드오프**: 없다시피 하다. Docker 빌드 컨텍스트가 서비스 폴더 밖(`../proto`)을 봐야 하므로 `docker compose build` 의 `context` 를 루트로 올리거나 빌드 전 복사 단계가 필요하다. **지금 고쳐도 싸다(§4).**

### A3. AI→Spring 콜백이 양방향 gRPC 다

AI 가 Spring 의 gRPC **클라이언트**(`spring_client.py`)를 갖고 Spring 이 gRPC **서버**(6565)를 연다. 여기서 outbox·재부착·서킷브레이커 워커별 분리·`ReportFeedbackBatch` 통일 결정이 파생됐다.

**처음이라면**: 이건 08-11 §2-② 와 사실상 같은 항목이라 여기서는 짧게만 — 상태 소유권이 정해지면(세션 = 연결) 결과는 **그 연결의 응답으로 돌아오지 콜백일 이유가 없다.** 양방향이 필요했던 건 프레임 경로(폰→AI)와 결과 경로(AI→Spring)가 **다른 상대**를 향했기 때문이고, 그건 A1 의 결과다.

---

## 2. 후보 — 데이터 모델

### B1. 기준(reference)을 프레임당 행으로 두고, 세션마다 통째로 보낸다

`exercise_references(exercise_id, timestamp_sec, joint_coordinates JSON)` — **정적 산출물**(기준 영상 1개에서 한 번 뽑은 시퀀스)을 프레임당 1행으로 저장한다. 스타일 식별자가 없고(운동 1 : 시퀀스 1), 세션을 시작할 때마다 `AnalyzeRequest.reference_poses` 에 **전체 시퀀스를 실어** AI 로 보낸다(`exercise.proto:67`).

[`reference-style-and-caching.md`](./reference-style-and-caching.md) §2 가 이미 이 셋을 다 짚었다 — 「스타일 식별자가 없다」·「리샘플 대표 시퀀스로 저장」·「카탈로그 캐시」. **즉 방향은 있고, 이 항목의 요지는 「처음부터 그랬어야 했다」다.**

**처음이라면**: 스타일 1개 = 블롭 1개(리샘플·트림된 시퀀스 + 버전). AI 는 `(exercise, style, version)` 카탈로그로 캐시하고, `StartAnalysis` 는 좌표가 아니라 **키**만 보낸다. 기준을 프레임 행으로 둔 것은 `pose_data` 와 같은 모양을 쓰고 싶었던 관성이지, 조회 패턴이 같아서가 아니다 — 기준은 언제나 **통째로** 읽힌다.

**트레이드오프**: 블롭이면 SQL 로 프레임을 못 들여다본다. 기준 데이터를 SQL 로 뜯어본 적이 있는지 — `reference-score-min-knee-variance.md` 가 그 사례인데, 그건 파이썬으로 했다.

### B2. 원시 좌표를 «용도를 정하기 전에» 프레임당 행으로 쌓았다

`pose_data` — 33 랜드마크 JSON(≈2.3KB, `PoseFrameProjection.java:4`)을 프레임마다 1행. 08-11 회고 ② 가 「쓰고 안 읽는다」로 잡았고, 그 뒤 worst rep 의 **프레임 1개**를 PK 로 재조회하는 읽기가 생겼다(`PoseFrameProjection.java:22`). 세션당 수백 행을 쓰고 1행을 읽는다.

**이 표에 매달린 결정 문서가 10개다**(§0). 파티션-FK, 멱등키 vs 파티션, 다운샘플링, base64 비용, 행 모양-파티션 상호작용, 구멍 DROP 유의성, `created_at` 의미 변경(V6)과 그 뒷수습(V9 — 72.6일 격차·1,190행 파티션 경계 이탈)…

**처음이라면**: 용도(Tier 0 「앱이 그릴 자세」, [`32-deferred-items.md P5`](../tasks/32-deferred-items.md))를 **먼저** 정하고 저장 단위를 거기 맞춘다. 행에는 **파생값**(각도·sync·rep 번호 — 숫자 몇 개)만 두고 원시 랜드마크는 **rep 단위 압축 블롭**으로. 그러면 행이 작아 파티션·다운샘플링·행 모양 논의의 절반이 애초에 안 생긴다.

**트레이드오프 — 이 항목이 제일 정직하게 적어야 하는 자리다**:
- **이 표가 DB 포폴의 실험 재료 대부분을 냈다.** projection −98.7%·keyset·파티션 pruning 0·구멍 DROP +13%·인덱스 쓰기 대가 — 전부 「큰 행이 많이 쌓이는 표」가 있어서 잴 수 있었다. 처음부터 잘 설계했으면 **잴 것이 없었다.**
- 그래서 「재설계했어야 했다」와 「이 설계 덕에 포폴이 됐다」가 **동시에 참**이다. 면접에서 이걸 「의도적으로 나쁘게 만들었다」고 하면 거짓이고, 「몰라서 그렇게 만들었고 그 뒤 재면서 배웠다」가 사실이다(§5).

### B3. 시간 타입이 표마다 다르고, 파티션 키의 «의미»가 중간에 바뀌었다

| 표 | 컬럼 | 타입 |
|---|---|---|
| `users` | `created_at` | TIMESTAMP |
| `exercise_sessions` | `start_time`·`end_time`·`last_active_at` | DATETIME |
| `pose_data` | `created_at` (파티션 키) | TIMESTAMP |
| `body_records` | `created_at` | DATETIME |

`V1__baseline.sql`. 그리고 `pose_data.created_at` 은 V6 에서 「적재 시각」→「세션 시작 시각」으로 **의미가 바뀌었고**, V9 가 그 전에 쌓인 행을 지워야 했다(복원 경로는 백업뿐 — V9 헤더).

**처음이라면**: 규약 하나(예: 전부 `DATETIME(6)` UTC, 또는 전부 `TIMESTAMP`)를 V1 에 못 박고, **파티션 키는 처음부터 「나중에 의미가 안 바뀔 값」** — 세션 시작 시각 — 으로 둔다. 파티션 키의 의미 변경은 되돌릴 수 없는 마이그레이션을 부른다는 걸 V9 가 증명했다.

**트레이드오프**: 없다. 순수하게 처음에 정했으면 공짜였던 항목.

### B4. 페르소나가 스키마 네 곳에 박혀 있다

| 위치 | 형태 |
|---|---|
| `users.selected_persona` | `ENUM('BEGINNER','ADVANCED','DIET','REHAB')` |
| `exercises.sync_threshold_beginner / _advanced / _diet / _rehab` | **컬럼 4개** |
| `exercise_feedback_templates.persona` | VARCHAR |
| `AnalyzeRequest.persona` | string |

페르소나를 하나 추가하면 DDL 이 세 곳, proto 주석이 한 곳. 「enum 값을 컬럼 이름으로 편다」의 교과서 사례다.

**처음이라면**: `personas` 표 + `exercise_persona_thresholds(exercise_id, persona_id, threshold)`. 4컬럼이 4행이 된다.

**트레이드오프**: 조회가 조인 하나 늘고, 페르소나가 정말 4개에서 안 늘어나면 지금 구조가 더 단순하다. [[project_squat_first]] 가 exercise_type 일반화를 막았듯 이것도 「지금 안 늘어난다」로 닫을 수 있다 — 다만 그때는 **결정으로** 닫아야지 관성으로 두는 것과 다르다.

### B5. `reports` 표가 세 성격을 한 표에 두고, 둘은 존재할 수 없다

`report_type ENUM('SESSION','WEEKLY','MONTHLY')` 인데 `session_id NOT NULL` + `uk_report_session(session_id)`. 주간·월간 행은 **만들 수 없다.** [`weekly-monthly-stat-preaggregation.md`](./weekly-monthly-stat-preaggregation.md) §0 이 「그 벽은 LLM 붙일 때 만나면 된다」로 미뤄뒀다.

**처음이라면**: `session_reports` 로 이름을 좁힌다. 주간·월간은 저장하지 않기로 했으니(같은 문서) enum 값 자체가 **약속되지 않은 미래**다. 08-11 ⑧ 「테이블 성격 경계가 흐림」의 구체 사례.

**트레이드오프**: 없다시피. 리네임 마이그레이션 하나.

### B6. `daily_logs` — 사용자 입력과 파생 집계가 한 행

08-11 회고 ⑧ 에 이미 있다. `memo`·`mood`(사용자가 쓴 것)와 `total_exercise_time`·`total_calories`(세션에서 유도되는 것)가 한 행이라 후자는 **언제 갱신되는지** 가 표에 안 적혀 있다. 처음이라면 파생값은 조회 시 계산하거나 별도 표. 여기 다시 적는 이유는 B5 와 같은 종류(성격 경계)라서다.

---

## 3. 후보 — 검증·제품 표면·클라이언트·운영

### C1. 테스트가 MySQL 을 한 번도 안 본다

`src/test/resources/application.yml` — H2 `MODE=MySQL`, **Flyway 비활성화**, `ddl-auto: create-drop`. 파일 주석이 스스로 한계를 적는다: *"테스트는 언제나 «Java 기준» 스키마를 보므로 엔티티와 마이그레이션이 어긋나도 초록불이 뜬다."*

**이 프로젝트의 정체성이 MySQL 내부**(파티션·인덱스 구성·EXPLAIN·JSON_TABLE·온라인 DDL)인데, 테스트는 그 어느 것도 못 본다. 이미 치른 비용:

| 비용 | 근거 |
|---|---|
| 마이그레이션 번호 중복이 머지 뒤에 발견 | #653 (V11 중복 → V13 리네임) |
| ENUM 정합성만 따로 지키는 우회 테스트 | `SchemaEnumConsistencyTest` |
| 네이티브 쿼리 4개(`JSON_TABLE` 포함)가 H2 에서 안 돌거나 다르게 돈다 | `DailyLogRepository`·`OutboxEventRepository` |
| 파티션 프루닝·멱등키 충돌·`ON DUPLICATE KEY` 경로가 자동 테스트 밖 | 전부 `measure_*.sh` 로만 검증 |

**처음이라면**: Testcontainers MySQL 로 Flyway 를 **테스트에서 그대로** 돌린다. 마이그레이션이 곧 테스트 픽스처가 되므로 「엔티티 ≠ 마이그레이션」 이라는 틈이 구조적으로 사라진다. H2 는 속도용으로 남기더라도 마이그레이션·네이티브 쿼리·파티션 테스트는 MySQL 프로파일로 가른다.

**트레이드오프**: CI 에 Docker 가 필요하고 테스트 시작이 느려진다(컨테이너 기동 — 미측정, 통상 수 초~수십 초). CLAUDE.md 의 「H2 인메모리, Docker 불필요」 장점을 잃는다. **그래도 §4 에서 「지금 고칠 가치가 가장 큰 항목」으로 둔다** — 현업이면 첫 주에 했을 일이고, 「DB 포폴」 이라면서 DB 를 테스트 안 하는 건 면접에서 바로 찔린다.

### D1. 백엔드 엔드포인트의 58% 는 호출하는 화면이 없다

컨트롤러 19개 · 엔드포인트 55개 중 **프론트가 부르는 것은 23개**(`frontend/services/*`·`app/**` 전수 grep, 2026-09-11). 호출 0건인 모듈:

| 모듈 | 엔드포인트 | 부수 |
|---|:--:|---|
| Admin(category·exercise·member·session·stats) | 14 | |
| Group + Invitation | 9 | + WebSocket(`GroupSocketRegistry`) |
| Goal | 4 | |
| PatternAnalysis | 3 | |
| Recommendation | 1 | |
| CoachingStream | 1 | SSE |
| **합계** | **32 / 55** | |

**이건 실수가 아니라 결정의 결과다** — DB 포폴 재포지셔닝([`db-portfolio-roadmap.md`](./db-portfolio-roadmap.md))이 「화면보다 쿼리」를 골랐고, BE-06/07/08·트레이너 모니터링·그룹 동기화는 백엔드 서사용으로 만들었다.

**처음이라면**: 핵심 루프 하나(세션 시작 → 실시간 분석 → 종료 → 리포트)를 **화면까지** 끝내고, 깊이(측정·정직)를 그 루프 위에 쌓는다. 넓이(모듈 7개)는 면접에서 「그거 화면 있어요?」 한 질문에 무너진다. [`project-destination-and-exit-criteria.md`](./project-destination-and-exit-criteria.md) §2 가 「화면으로는 아무것도 안 보인다」를 이미 최대 감점 사유로 짚었다.

**트레이드오프**: 반대편 질문은 「왜 기능이 이것뿐인가」다. 신입 포폴 벤치마크([`portfolio-benchmark.md`](./portfolio-benchmark.md))가 「채울 키워드」를 요구했고 그룹 WS·SSE·추천이 거기서 나왔다. **어느 질문에 맞을지는 지원 포지션(백엔드 vs DBA)에 따라 다르다** — 사용자 판단([[user_career_target]]).

### E1. 3fps 는 분석 요구가 아니라 클라이언트 캡처 방식에서 나온 상수다

`exercise.tsx:238-257` — *"takePictureAsync 는 셔터·인코딩 비용이 크므로 10fps 는 비현실적. ~3fps (330ms) 로 시작"*. 프레임마다 **정지 사진을 찍는다.**

그런데 AI 용량 모델 전체(코어당 세션 수·검출기 풀·`rep-timing-fps-contract.md`·`resolution-tiers.md`)가 **이 3 위에** 서 있다. 3 은 스쿼트 rep 타이밍 해상도가 요구한 값이 아니라 `takePictureAsync` 가 허락한 값이다.

**처음이라면**: 프레임 프로세서(카메라 스트림에서 직접 프레임을 받는 경로)로 캡처하고, fps 는 **분석이 요구하는 값**으로 정한다. ⚠️ **미측정** — 프레임 프로세서로 실제 몇 fps 가 나오는지, 그때 서버 용량 모델이 어떻게 바뀌는지 잰 적 없다. 08-11 ⑦(base64) 과 같은 「전송 경로 미측정」 축이다.

**트레이드오프**: fps 가 오르면 A1·B2 의 비용이 그대로 배수로 오른다. 3fps 가 우연히 서버를 살렸을 수도 있다.

### F1. dev / prod 를 프로파일로 못 가른다

둘 다 `SPRING_PROFILES_ACTIVE: prod` 라서 문서 노출 게이트를 별도 플래그(`SPRINGDOC_ENABLED`·FastAPI `DEBUG`)로 달아야 했다(08-11 ⑪). 처음이라면 `dev`·`test`·`prod` 세 프로파일과 「dev 기본값이 prod 로 새는 것」을 막는 구조를 첫 커밋에. 작고, 지금 고쳐도 싸다.

---

## 4. 분류 — «지금 고칠 가치» 와 «처음이라면만»

⚠️ 분류는 Claude 판단이다. 어느 칸을 실제로 여는지는 사용자 결정([[feedback_user_decides_not_claude]]).

| 칸 | 항목 | 이유 |
|---|---|---|
| **지금 고쳐도 싸다** (하루 안) | A2 proto 단일화 · B5 `reports` 이름 좁히기 · F1 프로파일 · B4 페르소나 표 | 마이그레이션 1~2개 + 빌드 설정. 서사에 손 안 댐 |
| **지금 고칠 가치가 크다** (며칠) | **C1 Testcontainers** — ✅ 착수 [#722](https://github.com/Shadowfit/init/pull/722) (2026-09-11 사용자 결정) | 「DB 포폴인데 DB 를 테스트 안 한다」는 E4 의 열린 구멍. 비용은 CI 시간뿐. **validate 를 켠 첫날 드리프트 3종이 잡혔다** — 죽은 `Authority` 엔티티·`Double`↔DECIMAL 4컬럼·`joint_coordinates` TEXT↔JSON, 그리고 race 테스트 3개가 V10 이후 한 번도 안 돈 상태였다는 것 |
| **방향은 이미 문서에 있다** — 구현만 남음 | B1 기준 블롭화([`reference-style-and-caching.md`](./reference-style-and-caching.md)) | 스타일 기능과 묶여 있어 그 착수 여부에 종속 |
| **처음이라면만** — 지금 바꾸면 자산이 사라진다 | B2 원시 좌표 저장 단위 · D1 제품 표면 | 실험 재료(B2)·키워드(D1)가 걸려 있다. 바꾸는 게 아니라 **§5 의 답으로** 쓴다 |
| **08-11 과 묶여서 따로 못 정한다** | A1 ingress 단일화 · A3 콜백 · E1 캡처 | 상태 소유권·스트림 전환과 한 덩어리. 그쪽이 열릴 때 같이 |
| **처음에 정했으면 공짜였다** — 지금은 못 바꾼다 | B3 시간 타입·파티션 키 의미 | V9 가 이미 값을 치렀다. 교훈으로만 |

---

## 5. 면접 답변 (E4)

### Q. 처음부터 다시 만든다면 뭘 바꾸시겠어요?

> "세 가지가 한 뿌리에서 나왔다고 봅니다.
> 첫째, **원시 관절 좌표를 용도를 정하기 전에 프레임당 행으로 쌓았습니다.** 그래서 파티션·다운샘플링·멱등키·행 크기 문제가 전부 그 표에서 나왔고, 결정 문서만 열 개가 매달렸습니다. 다시 한다면 용도를 먼저 정하고 파생값만 행에, 원시는 rep 단위 블롭으로 둘 겁니다.
> 둘째, **AI 서버를 앱이 직접 부르게 했습니다.** 홉 하나를 아끼려던 건데, 그 대가로 앱 번들 토큰·세션 nonce·워커 헤더·nginx 라우터가 생겼고 CORS 같은 실수가 두 서비스에 복제됐습니다. 공개 표면은 하나였어야 합니다.
> 셋째, **테스트가 MySQL 을 한 번도 안 봅니다.** H2 로 돌리니 마이그레이션이 어긋나도 초록불이 떴고 실제로 번호 중복이 머지 뒤에 잡혔습니다. Testcontainers 로 Flyway 를 테스트에서 그대로 돌렸어야 했습니다.
>
> 다만 첫째는 솔직히 — 그 표가 있어서 잴 것이 생겼습니다. 잘못 만든 걸 재면서 배운 거지, 배우려고 잘못 만든 게 아닙니다."

### Q. 그럼 왜 지금 안 고치세요?

> "셋 중 테스트는 고칠 수 있고 고쳐야 합니다. 나머지 둘은 지금 바꾸면 측정 근거가 같이 사라지거나(좌표 표), 상태 소유권 문제와 묶여 있어서(직결) 따로 못 정합니다. 안 고친 게 아니라 **어디까지가 한 덩어리인지** 아는 상태입니다."

---

## 6. 이 문서에 안 넣은 것 — 08-11 이 이미 다룬 항목

| 항목 | 어디 |
|---|---|
| AI stateful 소유권 · 프레임 경로 스트림(WS) | [`architecture-review-2026-08-11.md`](./architecture-review-2026-08-11.md) §1 최대 결함 · §2-①② |
| 배포 대상 0 · 분산추적 없음 · 마스터 시드 누락 | 같은 문서 ③④⑨ |
| base64 프레임 비용(미측정) | 같은 문서 ⑦, [`pose-frame-base64-cost.md`](./pose-frame-base64-cost.md) |
| 검출기가 스레드에 붙음(#164) | [`session-detector-ownership.md`](./session-detector-ownership.md) — 확정, 구현 미착수 |
| `/member/{email}` 경로에 신원이 중복(`requireSelf` 로 IDOR 만 막음) | 여기서만 한 줄 — `/me` 였으면 검사 자체가 필요 없다. 작아서 표에 안 올림 |

---

## 결정 로그

- 2026-09-11: **C1 착수** — 사용자 결정(범위: race 프로파일 대체 + 마이그레이션 검증 · 게이트: Docker 있으면 자동 ·
  `ddl-auto: validate` 켬). PR [#722](https://github.com/Shadowfit/init/pull/722). 나머지 11건은 그대로 미결.
- 2026-09-11: 작성. **새 결정 없음.** 후보 12건(A1~A3·B1~B6·C1·D1·E1·F1), §4 분류는 Claude 판단·미확정.
  방법(§0)은 「결정 문서 수로 빚의 뿌리를 센다」 — 감이 아니라 `docs/decisions/` 파일명 접두어 집계.
  D1 의 32/55 는 `frontend/` 전수 grep 실측(2026-09-11). E1·C1 의 「미측정」 표시는 그대로 미측정이다.
