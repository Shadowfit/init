# 작업 분배표

마지막 업데이트: 2026-05-23
근거: [`20-feature-roadmap.md`](./20-feature-roadmap.md) 의 스택별 남은 작업을 사람 분배 가능한 단위로 쪼갠 결과.

읽는 법:
- **상태**: 📋 대기 / 🚧 진행 중 / ✅ 완료 / 🟦 보류 / 🟥 차단
- **우선**: 🔴 즉시 (시연 핵심) / 🟡 단기 (시연 풍성) / 🟢 장기 / ⚪ 보류
- **추정**: 코드 변경 분량 기준 *대략* (실제 디버깅 시간은 별도)
- **의존성**: 이 작업이 시작되려면 먼저 완료되어야 하는 다른 작업

---

## 1. Frontend (React Native) — 가장 큰 갭

| ID | 작업 | 우선 | 의존성 | 추정 | 담당 | 상태 |
|----|------|-----|--------|------|------|------|
| FE-01 | `services/exerciseService.ts` 신설 (startSession / stopSession) + `types/exercise.ts` | 🔴 | — | 1~2h | | 📋 |
| FE-02 | `exercise.tsx` 녹화 버튼 핸들러 — 시작 시 startSession, 종료 시 stopSession 호출 | 🔴 | FE-01 | 2h | | 📋 |
| FE-03 | `exercise.tsx` 의 DEV 패널(수동 syncRate) 제거 | 🔴 | FE-02 | 30m | | 📋 |
| FE-04 | 카메라 프레임 캡처·base64 인코딩 로직 (`expo-camera` `takePictureAsync` 또는 frame callback) | 🔴 | 분기 H | 3h | | 📋 |
| FE-05 | 프레임 송신 — `exerciseService.sendFrame()` (분기 H1 채택 시 백엔드 프록시 호출) | 🔴 | FE-04, BE-01 | 2h | | 📋 |
| FE-06 | 운동 결과 화면 — 종료 후 rep 수·sync_rate·feedback 표시 | 🔴 | FE-02 | 4h | | 📋 |
| FE-07 | TTS 재생 — `expo-speech` + `/preferences/tts` + `/exercises/{id}/feedback-templates` 매핑 | 🟡 | FE-02 | 3h | | 📋 |
| FE-08 | 관절 점 오버레이 시각화 — AI 응답의 landmarks 좌표로 카메라 위에 점 그리기 | 🟡 | FE-04 | 4h | | 📋 |
| FE-09 | 캘린더 화면 데이터 연동 — `GET /records/calendar` | 🟡 | — | 2~3h | | 📋 |
| FE-10 | 주간 통계 화면 데이터 연동 — `GET /reports/weekly` 또는 SessionService API | 🟡 | — | 2~3h | | 📋 |
| FE-11 | 리포트 상세 화면 — worst 구간·이전 기록 비교·자세 분석 차트 | 🟡 | BE-02 | 5~6h | | 📋 |
| FE-12 | 운동 타이머 UI — 진행 시간 표시 | 🟡 | FE-02 | 1h | | 📋 |
| FE-13 | 관리자 화면 — 대시보드·카테고리·운동 영상 관리 | 🟢 | BE-03~05 | 8h+ | | 📋 |

소계: 🔴 6개, 🟡 6개, 🟢 1개

---

## 2. Backend (Spring Boot)

| ID | 작업 | 우선 | 의존성 | 추정 | 담당 | 상태 |
|----|------|-----|--------|------|------|------|
| BE-01 | (H1 채택 시) `POST /exercises/sessions/{id}/frame` 프록시 endpoint — WebClient 로 AI `POST /pose` 전달 | 🔴 | 분기 H 결정 | 3h | | 📋 |
| BE-02 | worst 구간 선정 서비스 로직 보강 — `WorstSectionDto`·`SessionReportResponseDto` 채우는 메서드 | 🟡 | — | 3h | | 📋 |
| BE-03 | GPT/Claude 리포트 자동 생성 — `GptFeedbackService` 신설 (이미 env 에 `OPENAI_API_KEY`) | 🟡 | — | 6h | | 📋 |
| BE-04 | 카테고리 관리 CRUD API — `AdminCategoryController` | 🟢 | — | 3h | | 📋 |
| BE-05 | 관리자 대시보드 통계 API — 사용자/세션 집계 | 🟢 | — | 4h | | 📋 |
| BE-06 | 운동 목표 엔티티·CRUD API — `Goal` 도메인 신설 | 🟢 | — | 5h | | 📋 |
| BE-07 | 사용자 운동 패턴 분석 API — 주기성·강도 추세 | 🟢 | 데이터 축적 후 | 8h+ | | 📋 |
| BE-08 | 개인화 루틴 추천 API — 알고리즘 설계 필요 | 🟢 | BE-07 | 10h+ | | 📋 |
| BE-09 | 운동 세트 개념 도입 — DB 컬럼·DTO·gRPC 메시지 추가 ([`project-squat-first`](../../C:/Users/khjae/.claude/projects/E--init/memory/project_squat_first.md) 와 협의) | ⚪ | 새 운동 추가 시점 | 5h | | 🟦 |

소계: 🔴 1개, 🟡 2개, 🟢 5개, ⚪ 1개

---

## 3. AI Server (FastAPI) — 거의 없음

[`feedback-minimize-python-changes`](../../C:/Users/khjae/.claude/projects/E--init/memory/feedback_minimize_python_changes.md) 정책으로 손대지 않음. **현재 시연용 동작에는 추가 작업 없음.**

| ID | 작업 | 우선 | 의존성 | 추정 | 담당 | 상태 |
|----|------|-----|--------|------|------|------|
| AI-01 | `ExtractReferenceData` 실제 구현 — YouTube 다운로드 + MediaPipe 추출 | ⚪ | 새 운동 추가 시점 | 6h | (원작자) | 🟦 |
| AI-02 | 런지·플랭크 분석기 추가 | ⚪ | 위와 동일 | 운동당 4h+ | (원작자) | 🟦 |
| AI-03 | 운동 세트 자동 구분 분석 | ⚪ | BE-09 와 협의 | 4h | (원작자) | 🟦 |

소계: ⚪ 3개 (모두 보류)

---

## 4. Infra / Ops — 배포 시점

| ID | 작업 | 우선 | 의존성 | 추정 | 담당 | 상태 |
|----|------|-----|--------|------|------|------|
| OP-01 | A6 운영 알람 — Slack 웹훅 + Spring `AlertService` 헬퍼 + 감지 지점 3곳 | 🟢 | 배포 직전 | 3h | | 📋 |
| OP-02 | HTTPS 종료 + 도메인 (`api.shadowfit.com`?) | 🟢 | 도메인 발급 | 2h+ | | 📋 |
| OP-03 | MySQL 호스트 노출 차단 (운영용 `docker-compose` 분리) | 🟢 | — | 1h | | 📋 |
| OP-04 | DB 마이그레이션 도구 (Flyway) 도입 | 🟢 | — | 4h | | 📋 |
| OP-05 | dependabot — frontend npm `audit fix` (axios·@xmldom/xmldom 등) | 🟡 | — | 30m | | 📋 |
| OP-06 | dependabot — ai-server pip 신중 업그레이드 (`python-multipart`, `protobuf`) | 🟡 | proto 호환성 확인 | 1~2h | | 📋 |
| OP-07 | CI 도입 — GitHub Actions 로 PR 마다 `./gradlew test` | 🟢 | — | 2h | | 📋 |
| OP-08 | 모니터링 스택 — Spring Actuator + Prometheus + Grafana | 🟢 | — | 6h+ | | 📋 |

소계: 🟡 2개, 🟢 6개

---

## 5. 미결 결정 (작업 시작 전 답 필요)

각각 [`decisions/ai-backend-coupling.md`](./decisions/ai-backend-coupling.md) §해당 절에 트레이드오프 정리됨.

| 결정 | 막는 작업 | 추천 |
|------|---------|------|
| **분기 H** 카메라 프레임 송신 경로 | BE-01, FE-04, FE-05 | H1 (백엔드 프록시) |
| **분기 B** proto 단일 소스 (루트 `proto/`) | (없음, 별도 정리 작업) | B3 또는 B1 유지 |
| **분기 D** AI 세션 in-memory vs 외부화 | (멀티 인스턴스 필요 시) | D1 (in-memory) |
| TTS 알람 채널 (Slack/이메일/SaaS) | OP-01 | A6-CH-1 (Slack 웹훅) |

---

## 6. 의존성 그래프 — 시연까지의 Critical Path

```
                    [분기 H 결정] ◀────────┐
                          │                │
                          ▼                │
              ┌───────────────────┐        │
              │ BE-01 백엔드 프록시 │        │
              └─────────┬─────────┘        │
                        │                  │
                        ▼                  │
   [FE-01]────►[FE-02]──┴──►[FE-04]──►[FE-05]──┐
   service     녹화 핸들러    프레임 캡처    송신   │
                  │                              │
                  └─────────────────────────►[FE-06]
                                              결과 화면
                                                 │
                                                 ▼
                                         ━━━━━━━━━━━━━━
                                         🎯 시연 가능 시점
                                         ━━━━━━━━━━━━━━
```

Critical path = 약 6개 작업 (BE-01 + FE-01·02·04·05·06) + 분기 H 결정.
**작업 총량 약 13~15시간**. 두 명이 병렬로 가면 1주일, 한 명이면 2주.

---

## 7. 병렬화 가능한 그룹

같은 시점에 여러 사람이 동시 작업 가능한 묶음:

| 그룹 | 작업 | 누구 |
|------|------|------|
| A (분기 H 결정 전) | FE-01, FE-02, FE-03 | 프론트 1명 |
| A (병렬) | BE-02 (worst 구간) | 백엔드 1명 |
| A (병렬) | OP-05 (npm audit fix) | DevOps 1명 |
| B (H 결정 후) | BE-01 (백엔드 프록시), FE-04 (프레임 캡처) | 분담 |
| B (병렬) | FE-09, FE-10 (캘린더·통계 화면) | 프론트 2명째 |
| C (B 완료 후) | FE-05 (송신 연결), FE-06 (결과 화면) | 프론트 |
| C (병렬) | BE-03 (GPT 리포트), FE-07 (TTS) | 백엔드 + 프론트 |
| D (시연 직후) | OP-01 (알람), OP-02 (HTTPS), OP-03 (MySQL 차단) | 인프라 |

---

## 8. 권장 다음 액션 (1주차)

1. **분기 H 결정** — 사용자가 H1 확정 (`decisions §5-β` 결정 로그)
2. **FE-01·02·03·BE-01 4개 동시 진행**
3. **FE-04·FE-05·FE-06 순서대로 마무리**
4. **수동 e2e 1회** ([`18-testing-guide.md`](./18-testing-guide.md) §8)
5. 시연 가능 시점 도달 후 🟡 작업들 분배

---

## 관련 문서
- [`20-feature-roadmap.md`](./20-feature-roadmap.md) — PPT 요구사항 ↔ 코드 매핑
- [`decisions/ai-backend-coupling.md`](./decisions/ai-backend-coupling.md) — 미결 분기 트레이드오프
- [`18-testing-guide.md`](./18-testing-guide.md) §8 — 수동 e2e 절차
- [`architecture/ai-backend-integration.md`](./architecture/ai-backend-integration.md) — 결합 현황
