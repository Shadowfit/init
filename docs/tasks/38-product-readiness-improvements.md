# 제품 수준(Product-level) 개선사항 — 코드 점검 결과

작성: 2026-11-18 · **갱신: 2026-11-26** (`origin/main` `fa83eff3` 기준) · 상태: **P0 약 절반 해결 — 아래 «현재 상태» 표가 최신, 그 아래 본문은 11-18 점검 원문**
방법: 영역별(백엔드 / AI 서버 / 프론트·관리자 / 인프라·문서) 읽기 전용 코드 점검. **앱·서버·테스트를 실행하지 않았다.** 아래 근거는 파일 읽기와 grep이다. 확인 못 한 것은 "미확인"으로 구분했다.
검증: 이 문서를 쓰기 직전 세 항목을 직접 재확인했다 — `JwtUtil.java:80` 이메일 로그, 토큰 종류 claim 부재(`JwtUtil.java:75-97`), `deleteByStatusAndCreatedAtBefore` 호출처 0건. 나머지는 점검 에이전트 보고 그대로이며 개별 재검증 전이다.
연관: [`31-production-readiness-plan.md`](./31-production-readiness-plan.md)(운영 완성도 점수 계획) · [`../portfolio/failure-modes.md`](../portfolio/failure-modes.md)(2026-06-08 초안, 아래 §6에서 갭 ID별 현재 상태 갱신)

표기: 🔴 차단/높음 · 🔶 부분 · ✅ 해결됨 · 근거 경로는 `E:\init` 기준.

---

## 0. 한 줄 요약

서버 쪽 **정합성 설계는 이미 잘 되어 있다**(outbox, 멱등, 낙관락, 세션 소유권). 제품이 되려면 막힌 곳은 코드의 정교함이 아니라 **(1) 사용자에게 닿는 문 — 개인정보·탈퇴·빌드 식별자·도메인, (2) 인증의 구멍, (3) 운영 호스트와 알람, (4) 정확도 근거**다.

---

## 현재 상태 (2026-11-26 갱신)

**방법.** 11-18 점검 항목을 `origin/main`(`fa83eff3`) 코드·이력으로 다시 대조했다(`git grep`·파일 읽기). **앱·서버를 실행해 확인한 것은 아니다.** 표기: ✅ 해결 · 🔶 부분 · ❌ 미착수 · ❓ 이번에 재확인하지 못함(코드 흔적 없음 또는 사람이 확인할 항목). 아래 본문(P0~§8)은 11-18 시점의 원문이라, 해결된 항목도 «할 일» 로 남아 있다.

**P0 22개: ✅ 9 · 🔶 7 · ❌ 3 · ❓ 3** (✅=1·🔶=0.5 로 세면 약 57%). 이 문서의 이전 판단 중 **A3 를 «부분» 으로 본 것은 틀렸다** — #860(11-19)으로 이미 해결돼 있었는데 `MemberService` 안에서만 찾았다.

> ⚠️ **«코드가 있다» ≠ «출시 가능».** C1 은 Terraform·배포 코드가 머지됐지만 **운영 호스트는 0대**다(2026-11-26: `Project=shadowfit` 태그 EC2 조회 0건, tfstate 없음 → 최초 프로비저닝 전). C4 알람도 코드만 머지됐고 AWS 에는 없다.

### P0

| # | 상태 | 현재 근거 |
|---|---|---|
| A1 | 🔶 | 동의 화면(`consent.tsx`)·`V36`·`ConsentService`·`HEALTH_INFO` 동의·처리방침/약관 **초안**(`docs/legal/`)이 있다. 처리방침은 «초안 — 법률 검토 전, 게시·배포 금지», `[확인 필요]` 남음. 스토어 제출용 공개 URL 없음 |
| A2 | ✅ | 마이페이지 탈퇴 연결(`mypage.tsx`) |
| A3 | ✅ | **#860(11-19)**: 그룹 생성자 CASCADE 차단·그룹장 양도 가드·작성 글·사진 삭제, `MemberPurgeService` 로 pose_data·사진 삭제를 outbox 에 같은 트랜잭션으로 적재(재시도). 11-24 `GroupWithdrawalIntegrationTest` 5건·`MemberPurgeServiceTest` 9건 통과 확인. 남은 것: outbox 페이로드 PII 여부 미확인, 삭제 FAILED 알람은 코드만(C4) |
| A4 | 🔶 | `eas.json`(development·preview·production, `autoIncrement`)·`name`/`slug`/`scheme` ✅. **`bundleIdentifier`·`package` 미정**(문서 39 D-2) |
| A5 | ✅ | 카메라 권한 문구 + 영구 거부 시 `Linking.openSettings`(`exercise.tsx:545`) |
| A6 | 🔶 | 빌드 때 `EXPO_PUBLIC_API_BASE_URL` 주입·릴리스 http 차단(#877), 가짜 `api.shadowfit.com` 제거. **운영 도메인 미정** |
| A7 | ✅ | 가짜 재설정 화면·진입 링크 제거(`reset-password.tsx` 없음). 대체 안내(문의 메일)는 아직 없음 |
| A8 | ❌ | Sentry·`expo-updates` 없음(`package.json`) |
| B1 | ✅ | `JwtUtil` `typ` claim, `JwtAuthFilter` 가 refresh 를 거부 |
| B2 | ✅ | 계정 없음·비밀번호 불일치를 같은 코드로, 더미 해시로 응답 시간도 맞춤(`MemberService`) |
| B3 | ✅ | `JwtUtil` 에 이메일 로그 없음 |
| B4 | 🔶 | **세션 소유권(nonce) 대조·`compare_digest`·«세션 없음» 과 같은 거절 응답** 구현(#187 안 d, 2단계 강제). **정적 공개 토큰(`AI_PUBLIC_TOKEN`)은 아직 있다** — 39번의 «공개 토큰 제거(3단계)» 는 미착수. `limit_req` 는 미확인 |
| B5 | ❓ | 세션 없는 `/pose` 는 stateless 각도 반환 경로로 아직 열려 있다(`pose.py:268`). 검출기 풀 상한 우회 여부는 재확인하지 못함 |
| B6 | ✅ | `/video/analyze` 제거(`router.py:11`) |
| B7·B8 | ❓ | **사람이 확인할 항목**(토큰 폐기·교체) — 코드로 닫을 수 없다 |
| B9 | ✅ | 원래도 코드는 닫혀 있었다(서버가 `USER` 고정). `REQUIREMENTS.md` §9 문서 정정 여부는 미확인 |
| C1 | 🔶 | Terraform·SSM 자동 배포·`deploy.yml`(#909) 있음. **호스트 0대** |
| C2 | ❌ | Caddy·TLS 없음, `forward-headers-strategy` 기본 `none`, prod compose 가 8080·8000 을 평문 게시 |
| C3 | 🔶 | `scripts/backup`(mysqldump+복구 검증, #895)·일일 systemd timer(#909). **실호스트 가동·S3 전송 미검증**, binlog 반출·PITR 없음(RPO=1일) |
| C4 | 🔶 | **outbox FAILED 알람**(호스트 지표 → CloudWatch → SNS → Lambda → 웹훅, #915) **코드 머지·AWS 미적용**. 그 외 규칙(백업 실패·다운·pending)은 0건. 39번이 설계한 «Prometheus+Alertmanager» 대신 **CloudWatch 방식으로 결정**(2026-11-24) |
| C5 | ❌ | staging 없음 |

### P1

| # | 상태 | 현재 근거 |
|---|---|---|
| D1 | ✅ | `OutboxRetentionCleaner`(SENT 7일·FAILED 90일, #868). 38번 원문의 «호출처 0건» 은 옛 쿼리 메서드 얘기였다 |
| D2 | 🔶 | FAILED 알람 코드(#915). 사람이 볼 확인 화면·절차는 없음 |
| D3 | ✅ | `session_reaper`(60초 주기, `reap_idle`) |
| D4 | ✅ | `StartAnalysis` 멱등(`create_if_absent`, 중복이면 기존 상태 유지) |
| D5 | ❌ | `entrypoint.sh` 의 `wait -n` 구조 그대로(재검증은 안 함) |
| D6 | ❓ | `AsyncConfig` 에 큐 상한·rate limit 흔적 없음 |
| D7 | ❌ | ShedLock 없음 |
| D8 | 🔶 | 데드락 재시도 소진은 gRPC `ABORTED` 로 바뀜(#276 ②). 낙관락 소진 경로는 재확인 못 함 |
| D9·D10·E2·E5·E6·E7 | ❓ | 이번에 재확인하지 못함 |
| D11 | ✅ | 에러 응답에 `code` 필드, 405·415·필수 파라미터 누락 핸들러(11-19) |
| E1 | 🔶 | 정확도 비교 스크립트(`ai-server/scripts/accuracy_report.py`, #903)가 생김. **실영상 라벨 확대·실측은 아직** |
| E3 | ✅ | «측정 안 됨» 계약(`MeasurementStatus`, #899·#900·#902) — 0회 세션을 출석·연속·일지에서 제외 |
| E4 | 🔶 | «왜 0 인가» 는 구분됨. 응답 단위 신뢰도 필드는 없음(`visibility` 는 관절 단위) |
| F1 | 🔶 | CI node 테스트 12종(`frontend-test.yml`, #936 의 `devOnlyRoute` 포함). jest-expo+RNTL 화면 테스트 시범은 #927 open |
| F2 | ✅ | 오프라인 배너(netinfo)·복귀 시 재시도·`LoadError` 확산(#926). **마이페이지·출석 달력 로딩 실패, 운동 중 연결 끊김은 미처리** |
| F3 | 🔶 | `devLog` 도입, 확인한 `console.log` 2곳은 `__DEV__` 가드. 프레임 로그(`exercise.tsx:453`)는 가드 미확인 |
| F4 | 🔶 | 운동 중 뒤로가기(소프트·하드)가 종료 확인을 거친다(`handleBack`). 격자·전체화면 버튼은 재확인 못 함 |
| F5 | ❌ | `expo-camera` 그대로, `vision-camera` 미도입, 실기기 성능 검증 없음 |
| F6 | 🔶 | 접근성 라벨 28곳(11-18: 약 9곳) |
| F7 | ✅ | TODO 2건(공지·QnA)만 남음. 진입점은 `__DEV__`, **딥링크 차단은 #936 으로 머지됨**(릴리스에서 `DevOnlyRoute` 가 홈으로 리다이렉트, 실제 릴리스 빌드에서 딥링크를 열어 본 적은 없음). 서버 API 는 없다(실서비스 전 필요) |
| G1·G3 | ❌ | admin-web 배포 구성·회원 정지·감사 로그 흔적 없음 |
| G2 | ❓ | 재확인 못 함 |

---

## P0 — 출시 전에 반드시 (차단)

### A. 법적·스토어 심사 차단

| # | 항목 | 근거 | 할 일 |
|---|---|---|---|
| A1 | 개인정보 동의·약관·처리방침 화면 없음. 앱이 약 3fps로 **얼굴이 담길 수 있는 프레임을 서버에 전송**하는데 고지가 없다 | `frontend/app/(tabs)/exercise.tsx:341-408`, `app/(auth)/login.tsx:185-300`, 문서 0건 | 처리방침·약관 작성, 가입 동의 체크, 영상 전송·보관 고지, 키·몸무게 별도 동의 |
| A2 | 회원 탈퇴 UI 없음 (API만 있고 호출처 0) — 앱스토어 5.1.1(v) 반려 사유 | `frontend/services/authService.ts:23-24`, `mypage.tsx` | 탈퇴 버튼·확인 화면 연결 |
| A3 | 탈퇴가 개인정보를 완전히 못 지움 | 아래 A3 상세 | 아래 참조 |
| A4 | `eas.json` 없음, bundle id/package/buildNumber 없음, name·slug가 `frontend` 템플릿 그대로 | `frontend/app.json:1-37` | EAS 프로파일, 식별자, 앱 이름·아이콘 |
| A5 | 카메라 권한 문구(`expo-camera` 플러그인) 없음, 영구 거부 시 설정 이동(`Linking.openSettings`) 없음 | `app.json`, `exercise.tsx:500-514` | 한국어 목적 문구, 거부 분기 |
| A6 | 프로덕션 API 주소가 가짜(`api.shadowfit.com`), 주입 경로 없음. AI 쪽은 https 필수인데 `aiService.ts`와 입장 불일치 | `frontend/services/api.ts:35`, `aiService.ts:287-311` | 실제 도메인 확정 후 `EXPO_PUBLIC_*`로 주입 |
| A7 | 비밀번호 재설정이 **가짜 성공**(`setTimeout` 후 "변경 완료" 표시) | `frontend/app/(auth)/reset-password.tsx:42,65,88` | 실구현하거나 화면 제거 |
| A8 | 크래시 리포팅·OTA 없음 | `frontend/package.json` | Sentry 등 + `expo-updates` |

**A3 상세 (탈퇴 구멍 3개)**
- 피드 사진 파일이 안 지워짐: `FeedPhotoStorage.deleteQuietly`는 있으나 탈퇴 경로에서 호출 없음 (`MemberService.java:344-379`)
- **그룹장이 탈퇴하면 그룹 전체가 CASCADE 삭제**되어 다른 멤버의 피드·멤버십도 사라짐 (`V12__add_group_tables.sql:13`). `leaveGroup`에는 양도 가드가 있으나 `deleteAccount`에는 없음 (`GroupService.java:203-206`)
- pose_data 비동기 삭제는 실패해도 재시도 없음. 최종 안전망은 월 파티션 TTL이라 최대 약 2개월 잔존 가능 (`PoseDataCleanupService.java:29-36`)

### B. 인증·보안

| # | 항목 | 근거 | 할 일 |
|---|---|---|---|
| B1 | **refresh 토큰을 access 토큰으로 사용 가능.** 토큰 종류 claim이 없어 7일짜리 refresh가 API 접근에 그대로 통한다 | `JwtAuthFilter.java:220-231`, `JwtUtil.java:75-97` | `typ` claim 추가 + 필터에서 access만 허용 + 테스트 |
| B2 | 로그인이 계정 존재 여부를 노출 (없으면 404 `U001`, 틀리면 401 `A005`) | `MemberService.java:112,115` | 동일 응답으로 통일 |
| B3 | 토큰 생성마다 **이메일을 INFO 로그**에 남김 | `JwtUtil.java:80` | 로그 제거 (`JwtAuthFilter`는 #411로 이미 제거됨) |
| B4 | AI 서버 `/pose` 인증이 **앱 번들에 들어가는 공개 토큰 하나** — 누구나 호출 가능. 토큰 비교가 상수시간이 아님. rate limit 없음 | `ai-server/app/middleware/auth.py:49-59`, `config.py:79-87`, 이슈 #187 | 사용자 JWT 기반 인증 또는 세션 발급 토큰, `hmac.compare_digest`, nginx `limit_req` |
| B5 | 세션 없는 `/pose` 요청이 검출기 풀 상한을 우회(스레드 로컬 검출기) | `pose.py:221`, `mediapipe_detector.py:112-117` | 세션 필수화 또는 풀 편입 |
| B6 | deprecated `/api/v1/video/analyze`가 살아 있음: 무제한 업로드 + 이벤트 루프 블로킹 | `ai-server/app/**/video.py:23-38`, `router.py:11` | 라우트 제거 |
| B7 | 앱 `.env`에 옛 내부 토큰 변수가 남아 있음. 번들에 들어간 적 있으면 교체 필요 | `frontend/.env` (값은 문서에 옮기지 않음) | **본인 확인 후 교체** |
| B8 | 커밋된 EC2 부트스트랩 로그 3곳에 토큰 형태 문자열 (폐기 여부 미확인, git 이력에도 남음) | `loadtest/results/ai-worker-load-soak-2026-08-28/bootstrap_target.log:23-24`, `.../coresidency-aws-b-2026-08-16/bootstrap_target.log:14-15`, `.../e2e-interaction-aws-2026-08-30/userdata-target.sh:9-10` | 현재 운영 토큰과 다른지 확인, 같다면 교체 |
| B9 | ~~관리자 role을 가입 시 클라이언트가 지정 가능~~ → **정정: 코드는 닫혀 있다.** 이슈 #138 이후 서버가 `USER`로 고정하고 회귀 테스트가 있다(`MemberRequestDto` 주석, `MemberService.java:294-296`). `REQUIREMENTS.md` §9 서술만 낡았다 | `docs/REQUIREMENTS.md` | 문서 정정 |

### C. 운영 기반

| # | 항목 | 근거 |
|---|---|---|
| C1 | **운영 호스트가 0대.** CD는 GHCR 이미지 푸시에서 끝나고, prod compose는 실호스트 검증 0회 | `.github/workflows/cd-backend.yml` 하단, `docs/19-deployment.md` §3 |
| C2 | TLS·리버스 프록시 없음. 8080/8000이 평문 직접 노출. nginx `location /`가 전 경로를 통과시켜 `/metrics`도 외부에 닿을 수 있어 보임(실행 확인 못 함) | `nginx-ai/default.conf`, `docker-compose.prod.yml:218-219` |
| C3 | 백업 **자동화** 없음(복구 시간 실측만 있음). 주기·보존 기간 숫자 미정 | `docs/decisions/rpo-rto-targets.md`, `19-deployment.md` §4.2 |
| C4 | 알람 규칙 0건, 스케줄러·outbox 상태를 보는 HealthIndicator 0건 | `monitoring/prometheus.yml`, `SessionTimeoutScheduler.java:133-135` |
| C5 | staging 환경 없음 | 관련 파일 0건 |

---

## P1 — 첫 사용자(베타) 받기 전에

### D. 데이터·정합성 잔여 갭

| # | 항목 | 근거 |
|---|---|---|
| D1 | **outbox 테이블 무한 증가** — purge 쿼리가 정의만 있고 호출처 0건 (직접 확인) | `OutboxEventRepository.java:179-180` |
| D2 | FAILED가 된 STOP_ANALYSIS 행은 사람이 볼 수단·알림이 없음 | `AbstractOutboxPublisher.java:172,183` |
| D3 | **AI 서버 방치 세션 정리 장치 없음.** 상태가 프로세스 메모리에만 있고 `StopAnalysis`가 안 오면 검출기(약 100MB)가 재기동까지 남아 신규 세션이 `RESOURCE_EXHAUSTED`로 막힘 | `ai-server/app/grpc/session_state.py:346-514`, `exercise_servicer.py:443` |
| D4 | AI `StartAnalysis`가 비멱등 — 중복 호출이 진행 중 세션을 덮어써 초기화 | `session_state.py:372-387` |
| D5 | 워커 1개 장애 시 컨테이너 전체 재기동(실측 24초, 활성 세션 전부 소실). 서버 2대 이상이면 세션 라우팅 구조가 성립 안 함 | `ai-server/entrypoint.sh:30-33`, `docs/decisions/ai-horizontal-scaling.md` |
| D6 | pose_data 적재에 백프레셔·rate limit 없음, `@Async` 큐 상한 추정 불가(Boot 기본값 추론) | `AsyncConfig.java:30-32` |
| D7 | 타임아웃 스케줄러 ShedLock 없음(데이터는 상태 가드·`@Version`으로 안전, 낭비만 발생). IN_PROGRESS 전체를 매 tick 읽음 | `SessionTimeoutScheduler.java:55,73-74` |
| D8 | 낙관락 3회 소진 시 INTERNAL로 응답 — 상대가 영구 실패로 오인할 수 있음. AI 쪽 `_RETRYABLE_CODES` 대응은 미확인 | `ExerciseGrpcService.java:352-355` |
| D9 | AI→Spring 프레임 배치 전송이 요청 스레드에서 최악 약 19초 블로킹. 전송이 끝까지 실패해도 AI 쪽 rep 카운트는 진행 → DB와 어긋남 | `ai-server/app/.../pose.py:502,534-547` |
| D10 | 디코드 실패·미지원 `exercise_type`이 500으로 나감(클라이언트 오류가 서버 오류로 보임) | `image_utils.py:22`, `angle_calculator.py:38-40` |
| D11 | 405·415·필수 파라미터 누락이 `Exception.class`로 떨어져 500 가능성(실제 동작 미확인). PR #809가 이미 이를 다루는 중 | `GlobalExceptionHandler.java:183-187` |

### E. 정확도·품질 (제품 신뢰의 핵심)

| # | 항목 | 근거 |
|---|---|---|
| E1 | 실영상 라벨 데이터가 소수(6개 영상). `squat_front_01`은 5회 중 1회만 검출, 원인 미해결(3fps 표본화 한계) | `docs/verification/2026-09-21-verification-report.md` |
| E2 | 정확히 3fps로 전송 시 서버 유입 상한(300ms)에 걸려 rep이 4/5로 과소 집계(재현 2회), 2.5fps는 5/5. 클라이언트 간격과 서버 상한 여유가 30ms뿐 | 검증 보고서 §7-3, `session_state.py:71` |
| E3 | 점수 계산 예외·기준 각도 부재 시 **조용히 0점** → "즉시 자세 수정 필요"로 매핑 | `squat_analyzer.py:127-133,266-268` |
| E4 | 응답에 신뢰도 필드 없음, 판정 불가를 구분 못 함 | `ai-server/app/models/pose.py:112-145` |
| E5 | 임계값(rep 판정 4/15/60, 하프 스쿼트, DTW τ·허용오차)이 소수 영상으로 손으로 맞춘 값, 코드가 미검증이라고 인정 | `session_state.py:28-71`, `squat_counter.py:65-81`, `config.py:59-64` |
| E6 | 기준 좌표 추출이 비결정적(같은 영상·코드인데 결과가 갈림, 이슈 #224) | `exercise_servicer.py:137-146` |
| E7 | 런지는 횟수만 셈, 기준 대비 점수 없음. `exerciseTypeOf` 기본값이 `'squat'`이라 미지원 종목이 스쿼트로 처리됨 | `exercise_servicer.py:41-42`, `exercise.tsx:36-47` |

### F. 앱 품질

| # | 항목 | 근거 |
|---|---|---|
| F1 | 앱 테스트 사실상 0건, 인증 경합 로직(`api.ts`, `sessionVersion.ts`)에 테스트 없음. `lib/motion/run-tests.cjs`(약 105개)는 CI에 연결 안 됨 | `frontend/components/__tests__`, `lib/motion/` |
| F2 | 오프라인 감지 없음, 화면 다수가 `console.warn`만 남기고 사용자에게 아무 표시 없음, 자동 재시도 없음 | `groups.tsx:48`, `index.tsx:97` 등 |
| F3 | 로그가 `__DEV__` 가드 없이 서버 응답 본문을 출력 | `login.tsx:228`, `exercise.tsx:263` 등 |
| F4 | 운동 중 뒤로가기가 세션을 종료하지 않음, 격자·전체화면 버튼은 동작 없는 껍데기 | `exercise.tsx:530-540` |
| F5 | 정지 사진 촬영 방식이라 프레임 속도·배터리에 구조적 한계. 실기기 성능 검증 결과 **미확인**(`react-native-vision-camera` 도입 미결정) | `exercise.tsx:341-408`, `docs/20-eas-build-guide.md` |
| F6 | 접근성 라벨 거의 없음 | 전체 약 9곳 |
| F7 | TODO 5건: 비밀번호 재설정 3건, 공지·QnA 더미 데이터 2건 | `reset-password.tsx`, `app/board/notice.tsx:24`, `qna.tsx:27` |

### G. 관리자(admin-web)

| # | 항목 | 근거 |
|---|---|---|
| G1 | 프로덕션 배포 구성 없음(정적 호스팅·`/api` 라우팅·compose 서비스 0). 빌드가 `frontend/` 소스에 의존 | `admin-web/src/api.ts:57`, `components/MannequinReferenceModal.tsx:2` |
| G2 | 토큰이 localStorage(XSS 노출), CSP 미확인 | `admin-web/src/api.ts:59-85` |
| G3 | 회원 정지·권한 변경, 공지·QnA 관리, 신고 처리, 감사 로그 없음 | `admin-web/src/App.tsx:352-359` |

---

## P2 — 서비스 규모를 키울 때

- **다중 인스턴스**: AI 서버 어피니티 라우팅 방식 미결정, 인메모리 rate limiter·WebSocket 레지스트리가 단일 인스턴스 전제(`FixedWindowCounter.java:22`, `GroupSocketRegistry.java:21-22`), nginx map이 워커 0~9만 지원
- **전송 구간 암호화**: gRPC 평문(`ExerciseAnalysisService.java:116`, `ai-server/app/grpc/server.py:93`), DB `useSSL=false`(`application.yml:43`). 같은 도커 네트워크 안이면 허용 가능한 선택일 수 있으나 결정을 문서로 남길 것
- gRPC reflection 기본 ON(`application.yml:159`), CORS `*`(`WebConfig.java:84`, `ai-server/config.py:108`), AI `DEBUG` 기본 True(`config.py:7`)
- 가입 입력 검증(이메일 형식·비밀번호 정책·username 길이)이 `@NotBlank`뿐(`MemberRequestDto.java`), 앱도 6자 이상만 확인(`login.tsx:210`)
- 컨테이너 root 실행·`HEALTHCHECK` 없음(backend·ai-server Dockerfile), ai-server 이미지에 `analysis_data/`(51MB) 등 데이터 포함, `requirements.txt` lock·해시 없음(`grpcio>=1.83.1`은 범위 지정), mediapipe 0.10.21에 묶여 업그레이드 막힘
- 로깅이 평문 콘솔(JSON 구조화·수집 없음), `/health`가 gRPC 서빙만 보고 검출기 풀은 안 봄, AI 지표 일부가 기본 설정에서 비노출(`main.py:131-141`)
- 에러코드 문서 불일치: enum에만 있는 코드 21개(C006, C007, G012~G014, GL001, GL002, R002, T001, W011~W022), 응답에 `code` 필드 없음(PR #809가 다루는 중), 만료 토큰과 잘못된 토큰 구분 불가(`TOKEN_EXPIRED` 미사용)
- WebSocket 토큰이 쿼리스트링(`JwtHandshakeInterceptor.java:96-97`), `/feed-photos/**` 무인증(UUID 파일명에만 의존)
- 테스트: 기본 H2 / MySQL 고유 동작은 `race` 프로파일에서만 검증, jacoco 없음, AI 서버 gRPC 통합 테스트 없음
- DB: 롤백 스크립트 없음(Flyway Community), `baseline-version: 2`, 무중단 마이그레이션 도구 미확인

---

## 5. 정책·사업 결정이 필요한 것 (코드로 못 닫음)

**갱신 (2026-11-26).** 문서 39 가 도메인·스토어 계정·호스트 등 일부를 확정했다([39 «확정 내역»](./39-p0-launch-blockers-design.md)). 아래 번호별 현황: ① YouTube 기준 영상 — 미결정 그대로(#800 이 URL 입력을 400 으로 거절하도록 막았을 뿐). ② 타깃·범위 — README 를 제품 소개 중심으로 재작성(#876)했으나 PRD·REQUIREMENTS 갱신 여부는 미확인. ③ 수익·비용 모델 — 호스트는 **AWS EC2 단일 호스트**로 방침 확정(39 D-3), 수익 모델 문서는 없음. ④ Gemini 프롬프트 — **확인됨: 집계 숫자만**(`WeeklyReportPrompt` 머리말·키/몸무게/이름/이메일 grep 0건). ⑤ `analysis_data/`·`reference_data/` 촬영 동의 — 미확인 그대로.

1. **YouTube 기준 영상 수집**: 코드에는 다운로드 로직이 없고 ToS 때문에 의도적으로 거부한다(`exercise_servicer.py:564-571`). 설계 문서는 "결정 전"(`docs/decisions/youtube-coordinate-harvest.md`). 대안(CC 라이선스·자체 촬영)과 함께 결정 필요. 법률 검토는 이 점검 범위 밖
2. **타깃 사용자와 범위**: PRD가 낡았다. 비목표로 둔 소셜·런지·목표·트레이너 기능이 이미 마이그레이션(V11·V12·V13·V17·V18·V20·V27·V28)에 들어가 있다. PRD·REQUIREMENTS를 코드 기준으로 갱신하거나 기능을 접을지 결정
3. **수익·비용 모델**: 문서 없음. PRD §6은 "졸업작품 겸 포트폴리오"라고 명시. 상용 서비스로 갈지에 따라 P0 범위가 달라짐
4. **Gemini로 보내는 프롬프트의 신체 수치 포함 여부**와 처리방침상 제3자 제공 고지(**미확인**)
5. 저장소에 포함된 실사용자 촬영 영상 기반 `analysis_data/`·`reference_data/`(`kkw`, `60kg_squat` 등)의 촬영 동의·공개 범위(**미확인**)

---

## 6. failure-modes.md(2026-06-08 초안) 갭 ID별 현재 상태

해결된 것: **S3**(deadline 5초) · **I1**(단일 트랜잭션 batchUpdate) · **I2**(UNIQUE `uk_pose_event`(session_id, rep_number, timestamp_sec, created_at) + ODKU. 초안이 제안한 2컬럼 키는 아님. 파티션 때문에 `created_at` 포함) · **E1**(outbox로 at-least-once) · **D3**(파티션 2개월분 자동 생성) · **A1**(refresh 회전·재사용 감지) · C1·C2·T1(원래 ✅ 유지)

부분 해결: **S1**(실패 통지·FAILED 전환됨, 재시도는 의도적으로 없음) · **S2**(활성화 시점 검증은 있고 세션 시작 경로엔 없음) · **E2**·**C3**·**C4**(타임아웃 후 StopAnalysis로 결과 재수신하는 경로는 있으나 DLQ 없음, AI 세션 상태가 남아 있어야 성립) · **T3**(ShedLock 없음, 데이터는 안전) · D1

여전히 갭: **I3**(백프레셔·rate limit 0) · **T2**(스케줄러 다운 감지 수단 0)

이 문서의 갱신본이 `E:\init-wt-failure-modes`(`docs/failure-modes-refresh`)에 미커밋으로 있으므로, 위 표와 대조해 반영할 것.

---

## 7. 미확인 (이번 점검의 한계)

**갱신 (2026-11-26) 추가분.** 이번 갱신도 코드·이력 대조뿐이며 **실행하지 않았다.** 새로 생긴 미확인: ⓐ #915 알람이 **실제 AWS 에서 울리는지** ⓑ #926 `LoadError`·복귀 재시도가 **실기기·로그인 상태에서 렌더링되는지**(웹에서 오프라인 배너 렌더링만 확인) ⓒ #906 리포트 응답에 `jointCoordinates` 가 실제로 실려 오는지 ⓓ 카메라 권한 문구가 «보관하지 않습니다» 라고 단정하는 근거 — AI 서버 코드에 디스크 쓰기는 grep 0건이지만 **전수 조사는 아니고**, 중계 프록시의 임시 파일(처리방침 초안 `[확인 필요]`)은 확인하지 못했다.

- 앱·서버·테스트를 **실행하지 않았다.** 테스트 개수(AI 약 279개, 백엔드 `@Test` 1,136개)는 grep 집계이고 통과 여부는 미확인
- AI 서버가 업로드 프레임을 디스크에 저장하는지(grep에선 없었으나 전수 조사 아님), EAS 빌드 산출물의 실제 권한 문구, 릴리스 빌드의 라우팅 가드·실기기 성능
- GitHub 브랜치 보호·Secrets 설정, prod compose의 healthcheck·리소스 제한, `cd-backend.yml` 전체
- 그룹·피드·알림 서비스의 소유권(IDOR) 검사 전수, 관리자 컨트롤러 6개 외 `@PreAuthorize` 적용 여부
- 서버 쪽 로그인 rate limit 외의 보호, Spring 기본 보안 헤더 적용 여부
- 워크트리 비교는 로컬 main이 origin/main보다 7커밋 뒤처진 상태 기준이라 ahead/behind 수치가 부정확할 수 있다. 미머지 브랜치 87개, 열린 PR 14건(의존성 봇 PR 3건이 약 2주 방치)

---

## 8. 권장 착수 순서

### 현재 기준 남은 순서 (2026-11-26 갱신)

원문의 1·2·3번 묶음은 대부분 끝났다(B1~B3·B6·D1·E3 ✅, A1~A3·A5·A7 ✅/🔶). 남은 것:

1. **사람이 정할 것(막힌 곳을 푼다)**: 운영 **도메인 이름**·**bundle id**(A4·A6·C2 의 선행), IAM 권한이 있는 AWS 자격 준비(현재 PC 의 자격은 부하 테스트용 임시 사용자라 IAM 조회조차 막혀 `terraform apply` 불가).
2. **사람이 확인할 것**: B7·B8 토큰 폐기·교체, 처리방침 `[확인 필요]` 항목과 법률 검토.
3. **운영 호스트 최초 프로비저닝(C1)**: `terraform plan` 으로 EC2 신규 생성 확인 → apply → 시크릿 → 첫 배포. 이때 **#915 알람(C4)이 같이 적용**된다.
4. **TLS·프록시(C2)**: Caddy + `forward-headers-strategy` + rate limit 통합 테스트를 한 묶음으로.
5. **빌드·관측(A4·A8)**: bundle id 확정 후 EAS 빌드, Sentry(개인정보 마스킹 포함).
6. **AI 인증 마무리(B4·B5)**: 정적 공개 토큰 제거 3단계, 세션 없는 `/pose` 경로.
7. **앱 마무리**: 푸시 알림(`expo-notifications` 미설치 — 친구 재촉·응원이 폰에 안 온다), 마이페이지·출석 달력 로딩 실패, 운동 중 연결 끊김, 공지·QnA 실제 API 또는 연락 채널(문의 메일).
8. **병행**: 알람 범위 확대(백업 실패·백엔드 다운·pending 적체), 베타 10명 모집 → E 계열 정확도 데이터.

### 원문 (2026-11-18)

1. **반나절 (코드 몇 줄)**: B1(토큰 종류 claim), B3(이메일 로그 제거), B2(응답 통일), D1(outbox purge 호출), B6(`/video/analyze` 제거), E3(0점 폴백 → "판정 불가")
2. **하루 (확인·결정)**: B7·B8(토큰 폐기 여부 확인), B9(관리자 role 지정 경로), 사업 결정 §5-1·§5-3, 실제 도메인 확정(A6)
3. **며칠**: A1·A2·A3(개인정보·탈퇴 일체), A4·A5·A8(빌드 식별자·권한 문구·Sentry), A7(재설정 실구현 또는 제거), D3·D4(AI 세션 TTL·멱등)
4. **병행**: 베타 호스트 1대에 배포(C1·C2) → 알람(C4)과 백업 자동화(C3) → 10명 규모 베타로 E 계열 정확도 데이터 수집

사용자 검증이 가장 큰 공백이다. 위 1~3을 하는 동안 베타 모집을 시작하면 E1·E2·F5의 "미확인"이 실제 수치로 바뀐다.
