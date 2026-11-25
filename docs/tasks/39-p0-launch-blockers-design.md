# P0 출시 차단 항목 설계 (A 개인정보·탈퇴·스토어 / B 인증 / C 운영 기반)

작성: 2026-11-18 · **갱신: 2026-11-26** (`origin/main` `fa83eff3` 기준) · 상태: **A·B 대부분 구현, C 는 코드만 있고 호스트 0대 — 아래 «진행 현황» 이 최신, 본문은 11-18 설계 원문**
입력: [`38-product-readiness-improvements.md`](./38-product-readiness-improvements.md)의 P0. 코드 근거는 이 문서를 쓰며 다시 읽은 부분(`MemberService`, `GroupService`, `FeedPhotoStorage`, `JwtUtil`, `JwtAuthFilter`, `V12__add_group_tables.sql`, 온보딩 화면)과 38번 점검 보고다.
**법률 문서(처리방침·약관)의 문구는 법률 자문이 아니다.** 아래는 구조와 필요 항목까지이고, 최종 문구는 KISA 작성 지침 등 공식 양식과 필요 시 전문가 검토를 거칠 것.

---

## 진행 현황 (2026-11-26 갱신)

방법·표기는 [38번](./38-product-readiness-improvements.md#현재-상태-2026-11-26-갱신)과 같다(코드·이력 대조, **실행해 확인한 것은 아님**). ✅ 구현 · 🔶 부분 · ❌ 미착수 · ❓ 재확인 못 함.

| 절 | 상태 | 현재 |
|---|---|---|
| D-1~D-3 | 🔶 | 방침만 확정. **도메인 이름·bundle id 미정**. 호스트는 EC2 단일이나 **0대**(11-26 EC2 조회 0건·tfstate 없음) |
| D-4 | ✅ | 탈퇴 시 작성 글 본문 삭제(`GroupWithdrawalService`) |
| D-5 | ✅ | 그룹장 양도 후 탈퇴(`OWNER_MUST_TRANSFER_FIRST`) |
| D-6 | ❓ | 법정 보관 범위 — 법률 확인 항목 그대로 |
| D-7 | ✅ | Gemini 입력 확인됨: **집계 숫자만**(신체·식별값 없음). Sentry 는 아직 도입 전 |
| A-0 ①프레임 보존 | 🔶 | AI 서버 코드에 디스크 쓰기 grep 0건(**전수 아님**). 프록시 임시 파일은 처리방침 초안이 `[확인 필요]`. `E:\init-wt-rep-frame` 결정 문서는 확인 못 함 |
| A-0 ②Gemini 입력 | ✅ | 위 D-7 |
| A-1 동의 | 🔶 | `V36`·`ConsentService`·`consent.tsx`·`HEALTH_INFO` 동의·`docs/legal/` 초안. 처리방침은 «법률 검토 전, 게시 금지». **공개 URL 없음.** 만 14세 문구·재동의 흐름 세부는 재확인 못 함 |
| A-2 탈퇴 UI | ✅ | 마이페이지 탈퇴 + 그룹장 안내 문구. 구글 플레이용 **웹 삭제 요청 URL 은 ❌** |
| A-3 삭제 완전성 | ✅ | ①그룹(생성자 재지정·양도 가드) ②사진 ③outbox `PURGE_MEMBER_DATA`(청크·재시도) ④글 본문 — #860. ⑤outbox PII: 삭제 이벤트는 `sessionIds`·`photos`(UUID 파일명)뿐(코드 확인), **다른 이벤트 페이로드는 미확인** |
| A-4 빌드 | 🔶 | `app.json`·`eas.json`·권한 문구·재설정 화면 제거 ✅. **bundle id ❌, Sentry ❌** |
| B-1 | ✅ | `typ` claim + 필터가 refresh 거부 + 전환 규칙(`typ`·`ver` 없는 구 토큰은 access) |
| B-2·B-3 | ✅ | 응답 통일·더미 해시, 이메일 로그 제거 |
| B-4 | 🔶 | 세션 nonce 대조·`compare_digest`·거절 응답 통일 ✅. **정적 공개 토큰 제거(3단계) ❌** |
| B-5 | ❓ | 토큰 해시 비교·교체는 **사람 절차** |
| B-6 | 🔶 | `/video/analyze` 제거 ✅. gRPC reflection prod off·CORS 화이트리스트·비밀번호 정책/DTO 검증은 재확인 못 함 |
| C-1 TLS·프록시 | ❌ | Caddy 없음, `forward-headers-strategy` 기본 none, 8080·8000 평문 게시 |
| C-2 배포 | 🔶 | `deploy.yml`(SSM Run Command, **SSH 아님**)·`sha-` 태그 고정·`deploy.sh`(#909). 헬스체크 게이트·자동 복귀는 재확인 못 함. **첫 실호스트 검증 0회** |
| C-3 시크릿 | 🔶 | SSM Parameter Store 방침 채택(웹훅 URL 은 `/shadowfit/alerts/`, 앱 시크릿은 `/shadowfit/prod/`). 로테이션 절차(`19-deployment.md` §2.2)는 재확인 못 함 |
| C-4 백업 | 🔶 | 일일 mysqldump + S3 업로드(코드). **binlog 5분 반출 ❌, 복구 리허설 ❌, 백업 성공 알람 ❌**. 실호스트 가동 미검증 |
| C-5 알람 | 🔶 | **설계 변경** — 아래 참고. outbox FAILED 알람만 코드 머지(#915), AWS 미적용 |
| C-6 | — | 범위 밖 그대로 |

**Phase 현황.** Phase 1(보안 핫픽스) ✅(B-5 사람 절차 제외) · Phase 2(데이터 책임) ✅(A-1 은 🔶) · Phase 3(빌드) 🔶 · Phase 4(인프라) 🔶(코드만) · Phase 5(실전 검증) ❌.

**C-5 설계 변경 (2026-11-24 결정).** 본문은 «prod 에 prometheus + alertmanager + node-exporter» 를 제안했으나, 컨테이너를 추가하지 않는 **CloudWatch + SNS + Lambda → Slack/Discord 웹훅** 으로 구현하기로 했다(알림 채널도 이때 Slack/Discord 웹훅으로 정함)(`infra/terraform/alarm.tf`, `infra/host/alarm/`). 호스트 timer 가 1분마다 백엔드 `shadowfit_outbox_failed` 게이지를 읽어 `OutboxFailed`(누적)·`OutboxFailedNew`(증가분)를 올린다. 알람은 증가분에 건다(FAILED 는 90일 보관이라 누적값은 두 번째 실패부터 알림이 없다) + 지표가 15분째 안 오면 «데이터 없음» 알람. **본문의 규칙 목록 10개 중 구현된 것은 1개**(outbox FAILED)다. 호스트가 죽으면 호스트 안의 timer 도 같이 죽지만 «데이터 없음» 알람이 호스트 밖(CloudWatch)에서 그것을 잡는다.

---

## 0. 먼저 정할 것 (코드로 못 닫는 결정)

| # | 결정 | 막는 항목 | 권장 |
|---|---|---|---|
| D-1 | **운영 도메인**(예: `shadowfit.xxx`)과 서브도메인 구성 | A4(앱 API 주소), C(TLS) | 단일 도메인 + `api.` / `admin.` 서브도메인 |
| D-2 | **스토어 개발자 계정 소유자**(개인/법인)와 bundle id 네임스페이스 | A4 | 계정 소유자 확정 후 `com.<소유자>.shadowfit` — 한 번 정하면 바꾸기 어렵다 |
| D-3 | **운영 호스트**(클라우드·크기·리전) | C | 단일 호스트 베타(현 구조가 1대 전제, `ai-horizontal-scaling.md`). 크기는 부하 실측 문서(`ai-coresidency-capacity.md`) 기준 산정 |
| D-4 | 탈퇴 시 **작성 글 처리**(그룹 이벤트 본문, 피드) | A3 | 본문 삭제(익명화가 아니라). 재식별 가능성이 있는 데이터는 남기지 않는다 |
| D-5 | 그룹장 탈퇴 정책 | A3 | 다른 ACTIVE 멤버가 있으면 **양도 후 탈퇴**(탈퇴 거절 + 안내). `leaveGroup`과 같은 정책 |
| D-6 | 접속 로그 등 **법정 보관 의무** 범위 | A1, C | 법률 확인 필요 항목으로 남김 |
| D-7 | 사용 중인 **외부 처리자**(Gemini, Expo Push, Sentry 등) 목록 | A1 | 처리방침의 위탁·국외이전 항목에 그대로 들어간다 |

### 확정 내역 (2026-11-18, 사용자 confirm)

| # | 결정 | 상태 |
|---|---|---|
| D-1 | 도메인 **새로 구매**, `api.` / `ai.` / `admin.` 서브도메인 | 방침 확정 — **도메인 이름 자체는 미정**(구매 후 `EXPO_PUBLIC_*`·Caddy 설정에 반영) |
| D-2 | 스토어 계정은 **본인 개인 계정** | 방침 확정 — **bundle id 문자열 미정**(개인 식별자를 포함하는 네임스페이스 선택 필요). 구글 플레이 신규 개인 계정의 테스트 요건은 제출 직전 확인 |
| D-3 | **AWS EC2 단일 호스트** | 방침 확정 — 인스턴스 크기·리전·비용은 미산정(`ai-coresidency-capacity.md` 기준으로 산정할 것) |
| D-4 | 탈퇴 시 작성 글 **본문 삭제** | 확정 |
| D-5 | 그룹장은 **양도 후 탈퇴**(다른 ACTIVE 멤버가 있으면 탈퇴 거절 + 안내) | 확정 |
| D-6 | 로그 등 법정 보관 범위 | **법률 확인 항목으로 남김** — 처리방침 초안에서 "확인 필요"로 표시 |
| D-7 | 외부 처리자 목록 | 코드로 확인되는 것만 잠정 기재: Gemini(주간 리포트), Expo Push(푸시 토큰 V18), AWS(호스팅·백업), Sentry(도입 예정). 각 처리자로 **나가는 데이터 항목은 A-0 확인 후 확정** |

**바로 시작 가능**: Phase 1(보안 핫픽스), Phase 2(탈퇴 완전성·동의) — 위 미정 값과 무관.
**값이 필요한 것**: Phase 3 빌드 식별자(bundle id), Phase 4 인프라(도메인 이름·인스턴스 크기).

---

## 1. A. 개인정보·탈퇴·스토어

### A-0. 먼저 확인할 사실 (설계의 전제)

고지 문구는 사실이어야 해서, 아래 둘이 확인되기 전에는 "저장하지 않습니다" 같은 문장을 쓰지 않는다.

1. **AI 서버가 카메라 프레임을 디스크·로그에 남기는가.** 38번 점검은 `ai-server/app`에서 `imwrite`·`write_bytes`가 없음을 grep으로만 확인했고 전수 조사는 아니다. 워크트리 `E:\init-wt-rep-frame`에 "대표 프레임 복사·원본 보존" 결정 문서가 미커밋으로 있다 — 이 기능이 프레임을 보존한다면 고지 내용이 완전히 달라진다.
2. **주간 리포트 프롬프트(`WeeklyReportPrompt`)에 어떤 값이 Gemini로 나가는가.** 이메일·이름은 없다는 것만 확인됐고 키·몸무게 포함 여부는 미확인.

### A-1. 동의 모델 + 화면

**데이터 (V36 신규)**

```
member_consents(
  id, member_id → users(id) ON DELETE CASCADE,
  type ENUM('TERMS','PRIVACY','HEALTH_INFO'),   -- 카메라 고지는 동의가 아니라 안내(아래)
  version VARCHAR(20),                          -- 문서 개정 시 재동의 판단용
  agreed_at DATETIME
)  UNIQUE(member_id, type, version)
```

- 필수: `TERMS`, `PRIVACY`. **`HEALTH_INFO`**(키·몸무게·운동 수준을 받는 온보딩 단계 직전)는 별도 동의로 둔다 — 건강정보로 볼 수 있어 일반 동의와 분리하는 쪽이 안전하다. **민감정보 해당 여부는 법률 확인 항목**(D-6과 같은 묶음).
- 만 14세 미만 가입 제한 문구를 가입 화면에 둔다(법정대리인 동의 흐름은 만들지 않는 것으로 범위 제한).
- 문서 버전이 올라가면 다음 로그인 때 재동의 화면. 로그인 응답에 `requiredConsents: [type…]`를 실어 앱이 분기한다(별도 API 불필요).

**API**
- `POST /member/signup` 요청에 `consents: [{type, version}]` 추가 — 필수 항목이 빠지면 400. 기존 앱(동의 필드 없음)은 신규 가입만 막히고 기존 사용자는 영향 없음.
- `POST /member/consents` — 재동의·`HEALTH_INFO` 동의 기록. 본인 것만(`@AuthenticationPrincipal`).
- 문서 본문은 **API가 아니라 정적 웹 페이지**로 호스팅한다. 스토어 제출에 처리방침 URL이 필요하고(A-4), 앱 밖에서도 열려야 하기 때문. 앱은 링크만 연다.

**앱 화면**
- `login.tsx` 가입 폼: 체크박스(전체 동의 + 개별) + 문서 링크.
- 온보딩 신체정보 단계 진입 전: 건강정보 동의.
- **카메라 사전 안내(pre-permission)**: 운동 첫 진입 시 OS 권한 팝업 전에 한 화면 — "자세 분석을 위해 카메라 영상이 서버로 전송됩니다 / 저장 여부(A-0 결과대로) / 분석 목적 외 사용 없음". 확인 후 `requestPermission()` 호출. 여기서 영구 거부되면 `Linking.openSettings()` 분기를 같이 넣는다(`exercise.tsx:500-514`).

**처리방침에 반드시 들어갈 항목(구조)**: 수집 항목(계정, 신체정보, 운동 좌표, 카메라 프레임의 처리 방식), 목적, 보유·이용 기간(좌표 TTL = 월 파티션 기준, 계정은 탈퇴 즉시), 파기 절차·방법, 처리 위탁·국외 이전(D-7: Gemini 등), 이용자 권리(열람·삭제, **앱 내 탈퇴**), 안전성 확보 조치, 개인정보 보호책임자 연락처, 변경 이력.

### A-2. 탈퇴 UI

- `mypage`에 "회원 탈퇴" → **비밀번호 재입력** → 삭제 항목·복구 불가 안내 → `DELETE`(기존 `deleteAccount`, `authService.ts:23-24`는 이미 정의됨).
- 운동 중이면 거절(`WITHDRAWAL_BLOCKED_BY_ACTIVE_SESSION`, 이미 구현) — 에러코드를 사용자 문구로 매핑.
- 그룹장 정책으로 거절되면(A-3) "그룹장을 다른 멤버에게 넘긴 뒤 탈퇴할 수 있어요" + 그룹 화면 이동.
- 구글 플레이는 **웹에서의 계정·데이터 삭제 요청 URL**도 요구한다(Data safety 양식). 최소한 처리방침 페이지에 삭제 요청 메일 주소와 절차를 적고, 가능하면 이메일 인증 기반 웹 삭제 요청 폼을 후속으로 둔다.

### A-3. 탈퇴 시 삭제 완전성 — 설계

현재 `MemberService.deleteAccount`는 회원 행을 지우고 FK CASCADE에 맡기며 pose_data만 afterCommit 비동기로 지운다(`:344-379`). 구멍 4곳을 이렇게 닫는다.

**① 그룹 — 순서가 중요하다.** 확인한 사실: `workout_groups.created_by`는 `NOT NULL`에 `ON DELETE CASCADE`(`V12:8,13`)이고 `Group.createdBy`도 `nullable=false`(`Group.java:53`). 그래서 **그룹장 자리를 넘겨도 생성자가 탈퇴하면 그룹이 통째로 삭제된다.** `leaveGroup`의 "양도 가드"만으로는 막히지 않는다.

탈퇴 트랜잭션 안에서, 회원 행 락(`findByIdForUpdate`) 직후:
1. 내가 속한 그룹을 그룹 행 `FOR UPDATE`로 순서대로 잠근다(데드락 방지: group id 오름차순).
2. 내가 **OWNER**인 그룹: 다른 ACTIVE 멤버가 있으면 → 탈퇴 거절(`OWNER_MUST_TRANSFER_FIRST` 재사용, D-5). 없으면 그룹 삭제(`leaveGroup`과 동일 동작).
3. 내가 **생성자(`created_by`)이지만 OWNER가 아닌** 그룹: `created_by`를 **현재 OWNER로 재지정**한 뒤 진행 → CASCADE에서 빠진다. 스키마를 바꾸지 않는 방식이라 `GroupResponseDto.createdById`(`:44`)도 그대로 유효하다.
4. 그 외 소속 그룹은 멤버십만 제거(CASCADE로 처리됨).

**② 피드 사진.** `FeedPhotoStorage.deleteQuietly`는 있으나 탈퇴 경로에서 호출이 없다(`MemberService`에 의존도 없음). 설계: 탈퇴 트랜잭션 안에서 해당 회원의 사진 파일명 목록을 먼저 수집(파일명을 보관한 테이블은 `GroupShareService`가 쓰므로 구현 시 정확한 컬럼 확인) → `afterCommit`에서 삭제. 삭제 실패 시 파일이 남으므로 ③의 outbox 경로에 같이 싣는다.

**③ pose_data·사진 삭제의 재시도.** 지금은 `@Async` 한 번 실패하면 끝이고, 최종 안전망이 월 파티션 TTL(최대 약 2개월)이라 "지체 없이 파기"에 약하다. 설계: 탈퇴 트랜잭션에서 **기존 outbox 인프라**(`AbstractOutboxPublisher`, 재시도·백오프·FAILED 훅이 이미 있음)에 `PURGE_MEMBER_DATA` 이벤트를 같은 트랜잭션으로 적재한다. 페이로드는 `{sessionIds, photoNames}`(크면 청크로 분할). 발행기가 `PoseDataCleanupService.cleanupBySessionIds` + 사진 삭제를 호출. 소진 시 FAILED → **알림 규칙(C-5)에 연결**. 이렇게 하면 비동기 삭제의 신뢰성이 나머지 outbox와 같은 수준이 된다.

**④ 남는 작성물**(D-4): `group_events.sender_id`는 SET NULL이라 탈퇴 후에도 본문이 남는다(`V12:54`). 본문에 개인 식별 가능한 내용이 들어갈 수 있으므로 탈퇴 시 해당 회원 작성 이벤트의 본문을 삭제한다. 반응(`event_reactions`)은 이벤트 CASCADE로 따라간다.

**⑤ outbox·로그에 남은 식별자.** outbox 행의 aggregate id는 FK가 없어 남는다. 대부분 숫자 ID라 단독으로 개인정보는 아니지만 PII 포함 여부는 **미확인** — 구현 시 페이로드 컬럼을 열어 확인하고, 포함되면 탈퇴 시 삭제 대상에 추가. D1(outbox 보존 정리 호출)과 같이 처리하면 무한 증가도 닫힌다.

**테스트(없으면 안 됨)**: 생성자≠OWNER 탈퇴 시 그룹 유지 / OWNER+타 멤버 탈퇴 거절 / 단독 OWNER 탈퇴 시 그룹 삭제 / 사진 파일 실제 삭제 / outbox 이벤트가 같은 트랜잭션에서 롤백과 함께 사라짐 / 삭제 실패 후 재시도로 완료. 기존 `탈퇴 CASCADE` 통합 테스트를 확장한다.

### A-4. 스토어 식별자·빌드

**`app.json`**: `name: "ShadowFit"`, `slug: "shadowfit"`, `scheme: "shadowfit"`, `ios.bundleIdentifier`, `android.package`(D-2), 버전은 `1.0.0` + 빌드 번호는 EAS가 증가(`eas.json`의 `appVersionSource: "remote"` + 프로덕션 프로파일 `autoIncrement`).
**권한 문구**: `expo-camera` 플러그인의 `cameraPermission`에 한국어 목적 문구("운동 자세를 분석하기 위해 카메라를 사용합니다"). 마이크는 코드가 안 쓰므로 요청하지 않는다(`android.permissions`를 명시해 불필요 권한 제거).
**`eas.json`**: 프로파일 3개 — `development`(dev client), `preview`(내부 배포, 실서버 대상), `production`. 환경변수는 프로파일별로 주입: `EXPO_PUBLIC_API_BASE_URL`(현재 `api.ts:35`는 하드코딩이라 **주입 경로 신설 필요**), `EXPO_PUBLIC_AI_BASE_URL`(https, 이미 릴리스에서 필수 분기 있음 `aiService.ts:297-311`), AI 공개 토큰은 B-4 설계가 적용되면 제거.
**스토어 양식**: 개인정보처리방침 URL, 계정 삭제 안내, Apple 개인정보 라벨 / 구글 Data safety(수집 항목: 이메일, 건강·피트니스 정보, 영상 처리 여부 — A-0 결과대로).
**크래시 리포팅**: Sentry(앱·백엔드·AI). **개인정보 마스킹 필수** — 이메일·토큰·서버 응답 본문(`login.tsx:228` 등이 지금 그대로 로그로 찍음)을 전송하지 않도록 `beforeSend` 필터와 `__DEV__` 가드 정리를 같은 작업으로 묶는다.
**비밀번호 재설정**(`reset-password.tsx` 가짜 구현): 출시 범위에서 **화면과 진입 링크를 제거**하는 쪽을 권장한다 — 실구현에는 메일 발송 인프라가 필요하고 베타에는 과하다. 제거하면 "비밀번호 분실 시 문의 메일" 안내로 대체.
**스토어 정책 확인 필요**: 신규 개인 개발자 계정은 구글 플레이에서 비공개 테스트 요건(테스터 수·기간)이 있을 수 있다. 정책이 바뀌므로 제출 직전 최신 요건을 확인할 것(이 문서는 확인하지 않았다).

---

## 2. B. 인증

### B-1. refresh 토큰으로 API 접근 (최우선, 반나절)

사실: refresh만 `ver` claim을 갖는다(`JwtUtil.java:86-90` — `tokenVersion != null`일 때만). 필터는 서명 유효성과 subject만 본다(`JwtAuthFilter.java:79,84`).

설계 — 2단계:
1. **즉시 방어(스키마 변경 없음)**: 필터에서 `parseClaims` 후 `ver` claim이 있으면 401. 이미 발급된 refresh 전부에 즉시 적용된다.
2. **정식**: 두 토큰 모두에 `typ` claim(`access`/`refresh`)을 넣고, 필터는 `typ == access`만 허용, 재발급 엔드포인트는 `typ == refresh`만 허용. 배포 직후 구버전 access 토큰(`typ` 없음)은 `ver`가 없으면 access로 간주하는 **전환 규칙**을 30분(access 만료) 유지 후 제거한다 — 배포로 전원 로그아웃되지 않게.

테스트: refresh로 보호 API 호출 → 401 / access로 재발급 호출 → 거절 / 구버전 access 통과(전환 기간).

### B-2. 계정 열거

`doLogin`이 계정 없음(`USER_NOT_FOUND`, 404)과 비밀번호 불일치(`LOGIN_INPUT_INVALID`, 401)를 다르게 돌려준다(`MemberService.java:112-115`). 설계: 둘 다 **같은 코드·같은 메시지·같은 상태(401)**. **응답 시간 차이**도 막는다 — 계정이 없을 때는 더미 해시와 `matches`를 한 번 실행해 BCrypt 비용을 맞춘다. `isAuthenticationFailure`(`:104-106`)가 두 코드를 따로 세므로 하나로 합치고 `LoginAttemptLimiter` 동작이 유지되는지 테스트로 확인한다. 가입 시 "이미 있는 이메일" 응답도 존재를 알려주는데, 이건 가입 UX상 불가피하므로 IP 단위 rate limit(이미 있음)으로 완화하고 문서에 한계로 남긴다.

### B-3. 이메일 로그 — `JwtUtil.java:80` 로그 줄 삭제(이미 `JwtAuthFilter`는 같은 이유로 지움).

### B-4. AI 서버 인증 재설계

사실: 지금은 **앱 번들에 들어가는 정적 공개 토큰** 하나로 `/pose`를 연다(`config.py:79-87`, 코드가 "누구나 호출 가능"을 인정). 그러나 이미 **세션별 비밀(`session_nonce`, V8)**이 있고 AI가 `session_id`·nonce 일치를 검증한다(`pose.py:311-326`). 약점은 두 곳이다: 세션 없이(`session_id=None`) 호출하는 경로가 풀 상한을 우회하고(`pose.py:221`), 보관된 nonce가 `None`이면 통과시킨다(`pose.py:299-310`).

설계: **세션 기반 인증으로 일원화** — 정적 공개 토큰을 없앤다.
- `/pose`는 `session_id` + `session_nonce` **필수**. 레지스트리에 없는 세션·nonce 불일치·`None` nonce는 모두 거절(레거시 허용 분기 제거 — 허용 이유 주석이 가리키는 상황이 아직 존재하는지 먼저 확인).
- nonce 비교는 `hmac.compare_digest`. nonce의 길이·엔트로피는 구현 시 확인(보안용 난수인지).
- 그러면 번들에 비밀이 없어지고 `EXPO_PUBLIC_AI_PUBLIC_TOKEN`·`frontend/.env`의 옛 토큰 문제가 사라진다(이슈 #187의 근본 해결).
- 보강: nginx `limit_req`(IP 기준) + 세션당 유입 상한(이미 있음), 세션 없는 경로·`/api/v1/video/analyze` 제거(B-6), 진단 엔드포인트(`diag/*`)는 prod에서 라우팅 제외.
- 순서: 앱·AI·Spring이 같이 바뀌므로 **AI가 두 방식을 모두 받는 릴리스 → 앱 릴리스 → 공개 토큰 방식 제거** 3단계.

### B-5. 토큰 로테이션 (설계가 아니라 절차 — 사람이 해야 함)

1. 커밋된 3곳의 토큰 형태 문자열과 현재 운영 토큰이 같은지 **해시 비교**(값을 화면에 출력하지 않고 `sha256`만 비교하는 스크립트로).
2. 같거나 확신이 없으면: `JWT_SECRET`, `INTERNAL_API_TOKEN`, `AI_PUBLIC_TOKEN`을 새로 생성해 교체(JWT 비밀 교체는 전원 재로그인을 유발하므로 점검 공지와 함께).
3. `frontend/.env`의 옛 `EXPO_PUBLIC_INTERNAL_API_TOKEN`이 번들에 들어간 적이 있다면 위와 같이 교체.
4. git 이력 재작성은 권하지 않는다 — 비밀을 교체하는 것이 정답이고, 이력을 다시 쓰면 오히려 워크트리 87개 브랜치와 충돌한다. 대신 해당 로그 파일은 이후 커밋에서 값을 마스킹한다.

### B-6. 기타 (작지만 같이)
- `/api/v1/video/analyze` 라우트 제거(`router.py:11`), 디코드 실패는 4xx.
- gRPC reflection을 prod에서 끔(`application.yml:159`), CORS를 admin 도메인 화이트리스트로(`WebConfig.java:84`) — RN 앱은 CORS 대상이 아니다.
- **REQUIREMENTS.md §9의 "role을 가입 시 클라이언트가 지정" 서술은 낡았다.** 코드는 이슈 #138 이후 role을 서버가 `USER`로 고정하고 회귀 테스트(`signup_withAdminRole_cannotAccessAdminApi`)가 있다(`MemberRequestDto` 주석, `MemberService.java:294-296`). 38번 문서의 B9는 "열려 있음"이 아니라 **문서 정정 항목**이다.
- 비밀번호 정책(서버·앱 둘 다 길이만 6자), 가입 DTO `@Email`·`@Size` 검증.

---

## 3. C. 운영 기반 (단일 호스트 베타 기준)

목표는 "10명을 안정적으로 받는 첫 배포"다. 무중단·다중 인스턴스·스테이징은 **P1 이후**로 미룬다(현 구조가 단일 노드 전제이고 `19-deployment.md` §3.3도 짧은 다운타임을 인정).

### C-1. 네트워크·TLS

```
인터넷 :443 ─ Caddy(자동 인증서) ─┬─ api.<도메인>   → backend:8080 (REST + WebSocket)
                                  ├─ ai.<도메인>    → ai-nginx:8000 (세션 어피니티 헤더 유지)
                                  └─ admin.<도메인> → admin-web 정적 파일
호스트 공개 포트: 80/443만. 8080·8000·6565·3306·9090은 호스트에 바인딩하지 않는다.
```

- 리버스 프록시는 Caddy 권장(설정이 짧고 인증서 갱신 자동). nginx도 가능 — 어느 쪽이든 `nginx-ai`는 내부 워커 라우팅용으로 그대로 둔다.
- **중요한 부작용**: 프록시 뒤로 가면 백엔드가 보는 클라이언트 IP가 전부 프록시 IP가 된다. `application-prod.yml`의 `forward-headers-strategy`는 지금 기본값(none, `:31`)이고, `AuthRateLimitFilter`는 IP 기준이다 → **전 사용자가 한 IP로 묶여 rate limit이 한 번에 걸리거나 무력해진다.** 프록시를 도입하는 같은 변경에서 `forward-headers-strategy: framework`(신뢰하는 프록시만) + AI 쪽 `X-Real-IP` 설정을 같이 넣고, rate limit 통합 테스트를 프록시 경유로 한 번 돌린다.
- 앱의 AI 호출을 `https://ai.<도메인>`으로(`aiService.ts:17`이 http 고정이라는 `reverse-proxy-and-tls.md` 지적 반영 — 릴리스 분기는 이미 https 필수).
- 서버 간 gRPC·DB 평문은 같은 도커 네트워크 안이라는 전제를 문서화하고 이번 범위에서는 유지(P2).

### C-2. 배포 파이프라인

현재 CD는 GHCR 푸시에서 끝나고 배포 job이 없다(`cd-backend.yml` 하단이 스스로 "미구현"이라 적음).
- 호스트에서 `docker compose -f docker-compose.prod.yml pull && up -d`를 **GitHub Actions가 SSH(또는 SSM Run Command)로 실행**하는 job 추가. 이미지는 `sha-<커밋>` 태그로 고정해 배포하고 `latest`는 쓰지 않는다 → **롤백 = 이전 sha로 다시 배포**.
- 배포 후 헬스체크 게이트(`/actuator/health`, AI `/health`) — 실패 시 이전 태그로 자동 복귀.
- 첫 배포는 사람이 호스트에서 절차대로 수행하며 **prod compose를 처음 실호스트에서 검증**한다(현재 0회). 이 한 번이 가장 많은 문제를 드러낸다.
- 호스트 접근은 SSH 키 대신 SSM 세션 매니저(포트 22 닫기)를 권장.

### C-3. 시크릿

- 저장: 클라우드 파라미터 스토어(암호화 문자열)에 두고 배포 때 호스트의 `.env`를 생성. 레포·이미지에는 두지 않는다(현재도 prod compose가 `:?` 필수라 이 방향과 맞다).
- 로테이션 절차(B-5)를 `19-deployment.md` §2.2(현재 TODO)에 채운다.
- dev compose의 이름이 "prod"인 JWT 폴백 시크릿(`docker-compose.yml:133`)은 이름을 바꾸고 폴백을 제거 — 오배포 방지.

### C-4. 백업·복구

- 방침은 이미 정해져 있다(`rpo-rto-targets.md` 구성 2: 백업 + binlog 서버 밖 반출). **빠진 것은 숫자와 자동화**다.
- 제안값(확정은 사용자): 전체 백업 일 1회, binlog 반출 5분 간격, 보존 30일, 오브젝트 스토리지 버킷은 별도 계정/권한(호스트 상실 시 함께 사라지지 않게). 베타 규모의 데이터라면 초기에는 `mysqldump --single-transaction`으로 충분하고, 용량이 커지면 물리 백업(실측 문서가 이미 있음)으로 전환.
- **복구 리허설을 정기 작업으로**: 월 1회 새 인스턴스에 복구해 앱 헬스·건수 대조까지. `E:\init-wt-drill`(`docs/recovery-drill`)의 호스트 상실 복구 훈련 문서(D1~D5)가 미머지 상태이므로 머지해서 절차의 기준으로 쓴다.
- 백업 성공 여부 자체를 알람(C-5)에 넣는다 — "마지막 성공 백업이 26시간 이상 전".

### C-5. 모니터링·알람

사실: 메트릭·Grafana 대시보드는 있고(`monitoring/`), **prod compose에는 관측 스택이 없으며 알람 규칙이 0건**이다. 또 관측 스택이 앱과 같은 호스트에 있으면 호스트가 죽을 때 알람도 같이 죽는다.
- prod에 최소 구성: prometheus + alertmanager + node-exporter(+ cadvisor는 선택). Grafana는 127.0.0.1 바인딩 + SSH/SSM 터널로 접근.
- **외부 생존 확인**을 별도로: 호스트 밖의 업타임 체크 서비스가 `https://api.<도메인>/actuator/health`를 1분마다 확인하고 메신저로 알림.
- 알림 채널: 디스코드/슬랙 웹훅 하나(팀 규모상 온콜 체계는 불필요).
- **첫 알람 규칙 목록**(전부 이미 있는 메트릭이거나 짧은 추가로 가능):

| 규칙 | 근거 메트릭·출처 |
|---|---|
| 인스턴스 다운 | `up == 0` |
| 5xx 비율 | actuator HTTP 서버 메트릭 |
| **outbox FAILED 발생·pending 지연** | `SessionMetrics` outbox lag·pending 게이지, `outboxDispatch("failed")` — D2·A-3③ |
| 낙관락 `exhausted` 발생 | `optimisticLockConflict("exhausted")` |
| 세션 IN_PROGRESS 정체 | 타임아웃 스케줄러 tick 실패는 지금 로그만 남음(`SessionTimeoutScheduler.java:133-135`) → **마지막 성공 tick 시각 게이지 추가** 후 "N분 이상 갱신 없음" |
| HikariCP 대기 증가 | `hikaricp_connections_pending` |
| AI 활성 세션이 풀 상한 근접 | `shadowfit_ai_active_sessions` — **지금은 `FRAME_PATH_METRICS` 켜야만 노출**(`main.py:131-141`) → 이 두 게이지는 기본 ON으로 분리 |
| 디스크 사용률 80% | node-exporter |
| 마지막 백업이 오래됨 | C-4의 백업 스크립트가 성공 시각을 텍스트파일/pushgateway로 남김 |
| 인증서 만료 임박 | Caddy 자동 갱신이 실패할 때 대비한 외부 체크 |

- 에러 추적은 Sentry(A-4와 같은 계정) — 알람이 "서비스가 죽음"을 보면 Sentry는 "무엇이 던졌나"를 본다.
- 로그: 컨테이너 json-file 드라이버에 `max-size`/`max-file` 설정(없으면 디스크가 차서 C-5의 디스크 알람이 먼저 울린다). 중앙 수집(Loki 등)은 P2.

### C-6. 이번 범위에서 하지 않는 것 (명시)
무중단 배포, 스테이징 환경(대신 `eas preview` 프로파일 + 로컬 `docker-compose.iso.yml`로 대체 — **D-3·D-1이 확정된 뒤 필요성 재판단**), 다중 인스턴스, 서비스 간 TLS, 중앙 로그 수집.

---

## 4. 의존성과 실행 순서

```
Phase 0 (결정·확인, 1일)    D-1~D-7 / A-0 두 가지 사실 확인 / B-5 토큰 해시 비교
Phase 1 (보안 핫픽스, ~1일) B-1(필터 방어) · B-2 · B-3 · B-6(video/analyze 제거) · B-5 교체   ← 배포 없이도 가능, 가장 값쌈
Phase 2 (데이터 책임)       A-3 탈퇴 완전성(V36, outbox 이벤트) → A-2 탈퇴 UI → A-1 동의(V37 + 화면 + 문서)
Phase 3 (빌드)              A-4 app.json·eas.json·Sentry·권한 문구 (D-1·D-2 필요)
Phase 4 (인프라)            C-1 → C-2 → C-3 → C-4 → C-5  (C-1이 forward-headers 변경을 포함하므로 rate limit 테스트와 한 묶음)
Phase 5 (실전 검증)         내부 배포(preview) → 소수 베타 → 복구 리허설 1회 → 스토어 제출
```

- 병렬 가능: Phase 2의 백엔드와 Phase 3의 앱 설정, Phase 4는 D-1·D-3만 있으면 시작 가능.
- B-4(AI 인증 일원화)는 앱·AI·Spring 3자 변경이라 Phase 2~3 사이에 별도 릴리스로 두고, Phase 1에서는 필터 방어와 세션 없는 경로 차단까지만 한다.
- 각 Phase의 완료 기준은 실제 동작 확인(통합 테스트 + 호스트에서의 수동 시나리오)이며, "코드가 들어갔다"가 아니다.

## 5. 이 설계에서 확인하지 못한 것

**갱신 (2026-11-26).** 11-18 의 미확인 중: A-0 ②(Gemini 프롬프트)는 **확인됨**(집계 숫자만). 사진 파일명 보관 위치는 **확인됨**(`group_events` 페이로드의 `photoUrl`, `GroupWithdrawalService.photoNameOf`). outbox 페이로드 PII 는 **삭제 이벤트만 확인**. 아래 원문 중 여전히 열려 있는 것: A-0 ①(프레임 보존 — grep 은 0건이지만 전수 아님), `session_nonce` 엔트로피·«nonce 가 None 이면 통과» 분기(코드 주석은 «실제로 열리는 경로는 재부착 하나뿐» 이라 설명하나 **정말 필요한지는 재검증하지 않았다**), 스토어·법정 보관 최신 요건, 호스트 크기·비용. **새로 생긴 미확인**: #915 알람의 실제 발화, 최초 프로비저닝 시 `terraform plan` 결과.

- A-0의 두 사실(프레임 보존 여부, Gemini 프롬프트 내용)
- 사진 파일명을 보관하는 테이블·컬럼의 정확한 이름, outbox 페이로드의 PII 여부
- `session_nonce`의 생성 방식(엔트로피), AI의 "nonce가 None이면 통과" 분기가 필요했던 상황이 아직 있는지
- 스토어·법정 보관 의무의 최신 요건(정책은 바뀐다)
- 운영 호스트 크기와 비용 — D-3 전에는 산정하지 않았다
