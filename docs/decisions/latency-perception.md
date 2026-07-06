# Decision: Latency 단위(ms·s) 의미와 ShadowFit 컴포넌트 매핑

상태: REFERENCE
작성: 2026-05-27
배경: [`./tts-design.md`](./tts-design.md) § 8 (TTS 합성 비교), § 10.2 (시간축), 분기 7 (실시간 채널), [`./session-end-trigger.md`](./session-end-trigger.md)
용도: ShadowFit 모든 컴포넌트의 latency 결정을 *동일 기준* 으로 평가하기 위한 횡단 reference. 분기 결정문에서 "체감", "즉시", "지연" 같은 표현이 나올 때 본 문서 § 1 의 임계값 표를 인용

---

## 1. 사용자 지각 임계값 (도메인 무관 일반 기준)

| latency | 체감 | 근거·출처 |
|---|---|---|
| < 30ms | 동시성 인지 한계 — lip-sync, 음악 라이브 연주 동기 가능 | 청각·시각 통합 임계 (멀티모달 통합 연구) |
| **30~100ms** | **"즉시" — 직접 조작감** | Nielsen UX 0.1초 기준 (시스템 반응 = 사용자 행동 통합 인지) |
| 100~300ms | 살짝 늦은 느낌 — 반응형이라 인지하지만 자연스러움 | 키보드 응답·게임 컨트롤러 입력 한계 |
| 300ms~1s | 명확한 지연 — 사용자 시선 이탈, 다른 곳 봄 | 웹 페이지 first paint 한계 |
| 1~10s | 답답함 — 흐름 끊김, 사고 흐름 중단 | Nielsen 1초·10초 UX 기준 |
| 10s+ | 주의력 한계 — 작업 포기 / 백그라운드 전환 검토 | Nielsen 10초 임계 |

---

## 2. 운동 도메인 맥락 — latency 는 rep 사이클 대비 평가

| 단위 | 값 |
|---|---|
| rep 한 사이클 (스쿼트) | ~2~3초 |
| 세트당 rep 수 | 10~12회 |
| 세트 간 휴식 | 30~90초 ([`../12-persona-difficulty.md`](../12-persona-difficulty.md) 의 `restTimeSec`) |
| 한 세션 길이 | 15~30분 |

→ **운동 중 latency 는 rep 사이클 길이 대비 평가**. rep 의 5% 미만 (~100ms) 은 사용자 인지 한계 아래로 *즉시감* 영역.
→ **세트 간 latency 는 휴식 시간 대비 평가**. 휴식 30s 안에 끝나면 운동 흐름 무관 (BT-SET retry 0/5/15/35s backoff 가 이 정신).

---

## 3. ShadowFit 컴포넌트별 latency 매핑

### 3.1 운동 중 (rep 사이클 내부)

| 컴포넌트 | latency | 위치 | 사용자 체감 |
|---|---|---|---|
| 카메라 frame 캡처 → base64 | ~5ms | 단말 | 무시 |
| 단말 → AI HTTP `POST /pose` | ~10~30ms | 네트워크 (Wi-Fi) | rep 응답 누적의 일부 |
| MediaPipe landmark 추출 | ~20ms | AI 서버 | (분기 7 격상 시 변동) |
| rep 완료 분류 + priority 선택 | ~5ms | AI `squat_analyzer` | 무시 |
| AI → 단말 PoseResponse | ~20ms | 네트워크 | |
| **rep 종료 → OS TTS 첫 음** | **~75ms** | 단말 + 네트워크 | **인지 한계 아래, 즉시감** |
| OS TTS 합성 시작 → 첫 음 출력 | ~50ms | 단말 OS | 8-A 의 핵심 가정 |

### 3.2 세트 경계 / 휴식 중

| 컴포넌트 | latency | 위치 | 사용자 체감 |
|---|---|---|---|
| AI → Spring gRPC `ReportFeedbackBatch` (BT-SET) | ~10~50ms | gRPC | 휴식 중 — 운동 흐름 무관 |
| Spring `INSERT IGNORE` batch | ~20~100ms | DB | 휴식 중 처리 |
| BT-SET retry backoff (실패 시) | 0s / 5s / 15s / 35s | AI 자체 큐 | 최대 ~55s, 다음 세트 시작 전 마무리 |

### 3.3 세션 종료 후

| 컴포넌트 | latency | 위치 | 사용자 체감 |
|---|---|---|---|
| `PATCH /sessions/{id}/end` 응답 | ~50~150ms | Spring | "운동 완료" 화면 전환 — 즉시감 |
| afterCommit → AI gRPC `StopAnalysis` | ~50~200ms | gRPC | 사용자 체감 무관 (이미 운동 끝) |
| LLM 종합 리포트 (BE-03, 추후) | **1~10초** | 외부 LLM | "잠시 후 표시" UX 필수 — 진행 표시·비동기 |

### 3.4 탈락한 후보 (latency 가 기각 사유)

| 후보 | latency | 기각 분기 |
|---|---|---|
| 클라우드 TTS 실시간 (8-C) | 300~800ms | 분기 8 — 가치 2·3 위배 |
| LLM + TTS 운동 중 (8-G) | **1~3초** | 분기 8 — rep 사이클 초과, 동기성 파괴 |
| Spring 경유 실시간 (7-4) | RTT 2회 (~150ms+) | 분기 7 — 요구사항 §1 위배 |

---

## 4. 단위 표기 컨벤션 (모든 docs / 코드 / 로그 / Swagger)

| 범위 | 표기 | 예 |
|---|---|---|
| 0 ~ 1초 미만 | **`ms`** | `75ms`, `300ms` |
| 1초 ~ 60초 미만 | **`s`** | `1.5s`, `30s`, `55s` |
| 1분 ~ 60분 미만 | **`m`** 또는 `min` | `5m`, `30min` |
| 1시간 이상 | **`h`** | `2h`, `24h` |

**유의**:
- 숫자와 단위 사이 공백 없음 (`75ms` ✅ / `75 ms` ❌) — 코드·log 검색 정합
- 시각 표기(`2026-05-27T10:23:45`) 는 별도 컨벤션 — [`./tts-design.md`](./tts-design.md) § 12.5 (Asia/Seoul, timezone 마커 없음)
- 영문 단위만 사용 — "밀리초" / "초" 한국어 표기는 사용자 대화·UI 문구에 한정

---

## 5. 임계값 → 분기 결정 매핑

ShadowFit 의 모든 latency 관련 결정이 § 1 의 임계값과 어떻게 연결되는지.

| 사용자 인지 임계 | 우리 컴포넌트 한계·목표 | 결정 근거 |
|---|---|---|
| **100ms 즉시감** | rep 응답 ≤ 100ms 목표 | 분기 7-1 (HTTP 확장) 유지 — 측정 75ms 한계 안 |
| **300ms 명확 지연** | 8-C 즉시 탈락 | 가치 2·3 위배 |
| **1초 답답함** | 8-G 즉시 탈락 | LLM 추론 자체로 한계 초과 |
| **10초 작업 포기** | LLM 리포트 (BE-03) 비동기 UX 필수 | 사용자가 진행 표시 보며 대기 |

---

## 6. 재검토 트리거

| 사건 | 다시 봐야 할 결정 |
|---|---|
| rep 응답 latency 측정값이 200ms 초과 (분기 7-1 한계 초과) | 분기 7 격상 — 7-2 (gRPC bidi) 또는 7-3 (WS) |
| 단말 OS TTS 첫 음까지 150ms+ 사용자 보고 | [`./tts-design.md`](./tts-design.md) § 8.4 격상 트리거 강화 |
| 세션 종료 후 리포트 생성 30s+ 측정 | LLM 호출 방식 변경 (스트리밍 응답·요약 분할 등) |
| BT-SET retry 55s 안에 마무리 못 함 | 디스크 영속화 (옵션 C, AI SQLite WAL) 우선 도입 |
| 단말별 latency 편차가 95th percentile 에서 300ms+ | 단말 fingerprint 별 분기 또는 8-D 격상 |

---

## 7. 측정 방법 (BE-30 / 운영 진입 전)

| 측정 대상 | 도구 | 위치 |
|---|---|---|
| rep 응답 전체 (단말 측 timestamp) | `console.time` + AsyncStorage 누적 | 단말 |
| `/pose` 핸들러 진입·종료 | FastAPI middleware | AI 서버 |
| gRPC RPC duration | gRPC interceptor metric | Spring + AI |
| Spring `INSERT IGNORE` 쿼리 시간 | `spring.jpa.properties.hibernate.generate_statistics` | Spring |
| OS TTS 첫 음까지 | `Speech.speak({ onStart })` callback timestamp | 단말 |

→ 측정 결과는 BE-30 (TTS 효과 분석) 영역에서 통합 대시보드화. 분기 7·8 격상 결정은 *측정값* 으로 박제.

---

## 8. 비교 감각 (참고)

| 동작 | 대략 latency | 사용자 체감 카테고리 |
|---|---|---|
| 키보드 키 누름 → 화면 글자 표시 | 30~100ms | 즉시감 |
| **ShadowFit rep 완료 → OS TTS 발화** | **75ms** | **즉시감** |
| 게임 컨트롤러 입력 → 캐릭터 반응 | 50~100ms | 즉시감 (수용 한계) |
| 화상 통화 양방향 RTT | 100~200ms | 자연스러움 |
| 웹 페이지 클릭 → 페이지 전환 | 200~500ms | 살짝 늦음 |
| 클라우드 TTS 실시간 (8-C) | 300~800ms | 명확한 지연 |
| Siri / Alexa 응답 | 500ms~1.5s | 음성 비서 수용 한계 |
| LLM 짧은 응답 | 500ms~3s | 답답함 시작 |
| LLM + TTS 직렬 (8-G) | 1~3s | 답답함 — 운동 중 사용 불가 |

→ § 10.2 의 75ms 는 *키 입력 응답성과 같은 카테고리*. 사용자는 "rep 끝나니 바로 들렸다" 로 인지.

---

## 9. ms 영역 vs s 영역 — 설계 철학 비교

§ 3 의 컴포넌트 매핑을 *latency 척도별 의사결정 패턴* 관점으로 재구성. 컴포넌트가 ms 영역에 속하는가 s 영역에 속하는가에 따라 *정반대의 설계 철학* 이 적용됨.

### 9.1 한눈에

| 척도 | 설계 철학 | 우선 가치 | 핵심 패턴 |
|---|---|---|---|
| **ms 영역 (10~200ms)** | **즉시감·동기 응답** | 가치 3 (인지) | 단일 RTT, 외부 의존 0, retry 없음 (실패=즉시 인지) |
| **s 영역 (1~수십초)** | **내구성·정확성** | 가치 2 (끊김) | retry/backoff, 멱등, safety net, 비동기 큐 |

### 9.2 ms 영역 — "동기·즉시 응답"

| 컴포넌트 | 측정 | 박힌 결정 | 결정의 형태 |
|---|---|---|---|
| 매 frame `POST /pose` 송신 | 10~30ms | [`./tts-design.md`](./tts-design.md) 분기 7-1 (HTTP 응답 확장) | 신규 채널·인프라 추가 ❌ — 기존 HTTP 그대로 |
| MediaPipe landmark 추출 | ~20ms | (AI 서버 단독 처리) | 클라 분기(7-5) 거부 → 단일 소스 |
| rep 완료 분류 + priority | ~5ms | 분기 1-B + 3-A | AI 내부 룰, LLM 거부 (1초 한계) |
| **rep 종료 → OS TTS 첫 음** | **~75ms** | 분기 8-A | `expo-speech` (50ms 합성) — 외부 API 거부 |
| OS TTS 첫 음 합성 | ~50ms | 8-A | 단말 OS 내장 — 0원·오프라인 |
| Audio ducking 활성화 | ~10ms | 분기 9-1 | `setAudioModeAsync` 1줄, OS 처리 |
| AI ↔ Spring gRPC RTT (피드백 batch) | 10~50ms | 2026-05-26 gRPC 통일 | REST 폐기, proto 계약 강제 |
| `PATCH /sessions/{id}/end` 응답 | 50~150ms | [`./session-end-trigger.md`](./session-end-trigger.md) ET-H | 단일 endpoint, afterCommit |

**공통 패턴**:
- 외부 API 사용 ❌ (LLM·클라우드 TTS·외부 Vision 모두 거부)
- retry 없음 — 실패하면 *사용자가 즉시 인지하고 다시 시도*
- 인프라 추가 ❌ — 기존 채널 재사용 (HTTP·gRPC)
- 캐시 우선 (템플릿 캐시 4-A, 매번 lookup 안 함)

### 9.3 s 영역 — "비동기·내구성"

| 컴포넌트 | 측정 | 박힌 결정 | 결정의 형태 |
|---|---|---|---|
| **BT-SET retry backoff** | 0s/5s/15s/35s | [`./tts-design.md`](./tts-design.md) 분기 2.A.BT | 휴식 시간(30~90s) 활용 retry |
| 세트 간 휴식 자체 | 30~90s | (Persona × difficulty, [`../12-persona-difficulty.md`](../12-persona-difficulty.md)) | restTimeSec — *retry 예산* 도 됨 |
| Spring `INSERT IGNORE` batch 처리 | 20~100ms | BE-13-G 멱등 | uniqueKey `(session_id, occurred_at, feedback_type)` |
| afterCommit → AI gRPC `StopAnalysis` | 50~200ms | ET-H | 트랜잭션 커밋 후 push |
| 세션 timeout safety net (Scheduler) | **~분 단위** | `SessionTimeoutScheduler` | IN_PROGRESS → FAILED 자동 정리 — 강제 종료 대응 |
| LLM 종합 리포트 (BE-03) | **1~10s** | (비동기 워커 결정) | "잠시 후 표시" UX |
| AI 세션 메모리 누적 → batch | 운동 전체 ~15~30분 | 분기 2-A | 세션 메모리 buffer, 종료 시 송신 |

**공통 패턴**:
- retry / backoff 필수 — 일시 실패 흡수
- 멱등 보장 — uniqueKey, `INSERT IGNORE`, idempotency-key
- safety net 별도 — Scheduler 가 미응답 세션 정리
- 사용자 체감 무관 — 운동 끝났거나 휴식 중이므로 1~수십초 OK

### 9.4 임계값별 결정 갈림

#### 100ms 선 — *외부 API 가능 여부*

- 100ms 안에 들어오는가? → **내부 처리** 채택
  - OS TTS (50ms) ✅ / MediaPipe landmark (20ms) ✅ / 룰 분류 (5ms) ✅
- 100ms 못 지키는가? → **즉시 탈락** 또는 비동기
  - 클라우드 TTS 실시간 8-C (300~800ms) ❌
  - 외부 Vision 판단 (500ms+) ❌
  - LLM 운동 중 텍스트 생성 (1~2s) ❌ (8-G 탈락)

#### 1초 선 — *동기/비동기 갈림*

- 1초 안: 사용자 체감 동기 인지 가능
- 1초 초과: *반드시 비동기* + 진행 표시 UX 필요
  - LLM 종합 리포트 (1~10s) → BE-03 비동기 워커
  - BT-SET retry 첫 시도 실패 → 5초 backoff (이미 비동기 영역)

#### 30초 선 — *retry 예산*

- 세트 간 휴식 30~90초가 *retry budget* 의 자연 한계
- BT-SET 5/15/35초 = 합 55초 → 가장 짧은 휴식 30초 안엔 1~2회만, 90초면 3회 모두 가능

#### 분(min) 선 — *safety net 영역*

- 사용자가 명시 종료 안 함 + AI 무응답 → Scheduler 가 분 단위로 cleanup
- 운영 알람·on-call 영역 (실시간 사용자 트래픽 무관)

### 9.5 같은 컴포넌트 ms→s 전환 사례

같은 동작이 *맥락에 따라* ms·s 양쪽에 등장:

| 동작 | ms 맥락 | s 맥락 |
|---|---|---|
| **gRPC 호출 자체** | RPC 1회 RTT 10~50ms | BT-SET retry 큐 안에서 55s 예산 |
| **Spring DB INSERT** | 쿼리 단발 20~100ms | 휴식 동안 다음 세트 시작 전 마무리 |
| **AI 측 feedback 누적** | 매 rep 추가 ~1ms | 세션 누적 15~30분 후 송신 |
| **세션 종료 신호** | PATCH 응답 50~150ms | afterCommit + AI 무응답 시 safety net 분 단위 |

→ 같은 RPC 라도 *언제 호출되는가* 에 따라 ms 가치(즉시감)·s 가치(내구성) 의 무게가 뒤바뀜.

### 9.6 아키텍처 함의

#### ms 영역 = "운동 중" = 인프라 보수적

운동 중 컴포넌트는 *기존 인프라 재사용* 으로 일관:
- HTTP `POST /pose` (이미 동작 중) ← 분기 7-1
- gRPC `ReportFeedbackBatch` (이미 두 RPC 운영 중) ← 2026-05-26 채널 통일
- OS TTS (이미 단말 내장) ← 분기 8-A
- audio ducking (OS 기능 1줄) ← 분기 9-1

→ **운동 중에는 새 인프라 도입 0** — *premature optimization 금지* 원칙

#### s 영역 = "운동 전후/사이" = 견고성 투자

운동 외부 컴포넌트는 *멱등·retry·safety net* 패턴 일관:
- BT-SET uniqueKey + INSERT IGNORE
- ET-H afterCommit + SessionTimeoutScheduler
- 향후 BE-03 비동기 워커

→ **사용자 체감 무관 영역에 견고성 투자** — *눈에 안 보이지만 데이터 무결성 핵심*

### 9.7 새 분기 등장 시 적용 가이드

새 분기가 등장할 때 *어느 영역에 속하는가* 부터 판별 후 해당 영역 패턴 적용:

| 새 분기 예시 | 영역 | 적용할 패턴 |
|---|---|---|
| 운동 중 새 피드백 채널 도입 검토 | ms | 기존 채널 재사용 우선 검증 |
| 리포트 LLM 호출 추가 | s | 비동기 워커 + 진행 표시 UX |
| 단말 ↔ AI 연결 끊김 처리 | ms→s 전환 | ms 안엔 즉시 재연결, 그 이상은 safety net |
| 외부 알림 발송 | s | retry + dead-letter queue |
| 실시간 leaderboard 갱신 | ms | 캐시 + 비동기 갱신 |

---

## 10. End-to-end latency 합산 — 시나리오별

§ 3 의 컴포넌트별 latency 를 *사용자 시나리오 단위* 로 누적. 정상 경로·실패 경로·시스템 vs 사용자 체감 구분.

### 10.1 시나리오 1: 매 frame 사이클 (rep 완료 아님)

가장 빈번한 사이클 — 운동 중 매초 ~10~30회 발생.

| t (ms) | 어디서 | 무엇이 | 누적 |
|---:|---|---|---:|
| 0 | 단말 | 카메라 frame 캡처 | 0 |
| 5 | 단말 | base64 인코딩 | 5 |
| 10 | 단말→AI | HTTP `POST /pose` 송신 시작 | 10 |
| 25 | AI | 수신 완료 (네트워크 15ms) | 25 |
| 45 | AI | MediaPipe landmark 추출 | 45 |
| 50 | AI→단말 | PoseResponse (rep_completed=false) | 50 |
| 60 | 단말 | 응답 수신 | **60** |

→ **사용자 체감: 0** (rep 완료 아니라 발화 없음, 화면은 다음 frame 캡처 사이클)
→ **시스템 누적: ~60ms** — frame 사이 빈도(33ms@30fps) 보다 길어서 *프레임 드롭* 발생 가능 — 측정 필요

### 10.2 시나리오 2: rep 완료 → 발화 (핵심 사용자 체감)

| t (ms) | 어디서 | 무엇이 | 누적 |
|---:|---|---|---:|
| 0 | 단말 | 카메라 frame 캡처 (rep 마지막 프레임) | 0 |
| 5 | 단말 | base64 인코딩 | 5 |
| 10 | 단말→AI | HTTP `POST /pose` | 10 |
| 30 | AI | landmark + rep 완료 감지 | 30 |
| 36 | AI | 4 룰 평가 → `KNEE_OUT` | 36 |
| 37 | AI | priority 선택 + 세션 누적 | 37 |
| 40 | AI→단말 | PoseResponse(feedback_type=KNEE_OUT) | 40 |
| 60 | 단말 | 응답 수신 | 60 |
| 61 | 단말 | templateCache lookup | 61 |
| 62 | 단말 | `Speech.speak()` 호출 | 62 |
| 65 | OS | TTS 합성 시작 | 65 |
| **110** | **OS** | **스피커 첫 음 출력 🔊** | **110** |

→ **사용자 체감 latency**: rep 종료 → 발화까지 약 **75ms** (사용자는 *동작 끝나는 순간* = t≈35 부터 인지)
→ **시스템 누적**: 110ms (frame 캡처 → 첫 음)
→ § 1 임계값 대비: **100ms 즉시감 영역 안** ✅

### 10.3 시나리오 3: 세트 경계 batch 송신 (정상)

세트 마지막 rep 완료 시 추가로 발생.

| t (ms) | 어디서 | 무엇이 | 누적 |
|---:|---|---|---:|
| 0 | AI | 세트 마지막 rep 감지 → `send_set_batch(is_final=False)` | 0 |
| 5 | AI | gRPC client 직렬화 | 5 |
| 20 | AI→Spring | gRPC `ReportFeedbackBatch` 송신 | 20 |
| 35 | Spring | 수신, JWT 검증 | 35 |
| 40 | Spring | `FeedbackLogService.saveBatch` 진입 | 40 |
| 60 | Spring | INSERT IGNORE batch (10 events) | 60 |
| 75 | Spring | 트랜잭션 commit | 75 |
| 90 | Spring→AI | `FeedbackBatchResponse(saved_count)` | 90 |
| 100 | AI | events_buffer 비우기 | **100** |

→ **사용자 체감**: 0 — 세트 끝나고 휴식 중이라 비동기 처리
→ **시스템 누적**: ~100ms (휴식 30~90s 안에 여유롭게 마무리)

### 10.4 시나리오 4: 세트 경계 batch (retry 발동)

Spring 일시 장애 시.

| t | 무엇이 | 누적 |
|---:|---|---:|
| 0s | 1차 시도 → 실패 (5xx 또는 timeout) | 0s |
| 5s | 2차 시도 (5초 backoff) | 5s |
| 5.1s | 성공 | **~5s** |

최악의 경우:
| t | 무엇이 | 누적 |
|---:|---|---:|
| 0s | 1차 실패 | 0s |
| 5s | 2차 실패 | 5s |
| 20s | 3차 실패 (15초 backoff) | 20s |
| 55s | 4차 시도 (35초 backoff) — 성공 | **~55s** |

→ **사용자 체감**: 0~55s — *휴식 30~90s 안에 마무리되면 무관*
→ § 1 임계값 대비: 30s 휴식 안엔 1~2회만, 90s 휴식엔 3회 모두 — 가장 짧은 휴식에서도 INSERT 누락 방지

### 10.5 시나리오 5: 세션 종료 (ET-H)

| t (ms) | 어디서 | 무엇이 | 누적 |
|---:|---|---|---:|
| 0 | 단말 | 사용자 "운동 종료" 버튼 클릭 | 0 |
| 10 | 단말→Spring | `PATCH /sessions/{id}/end` | 10 |
| 30 | Spring | JWT 검증 + Session 조회 | 30 |
| 50 | Spring | endTime 기록, status=COMPLETED commit | 50 |
| 60 | Spring→단말 | 200 OK 응답 송신 | 60 |
| 80 | 단말 | "운동 완료" 화면 전환 | **80 (사용자 체감 끝)** |
| **(afterCommit, 비동기)** | | | |
| 70 | Spring | afterCommit hook 진입 | — |
| 90 | Spring→AI | gRPC `StopAnalysis` | — |
| 110 | AI | 수신, final batch 송신 시작 | — |
| 130 | AI→Spring | gRPC `ReportFeedbackBatch(is_final=true)` | — |
| 180 | Spring | 최종 INSERT IGNORE + commit | — |
| 200 | AI | 세션 메모리 정리 | **200 (시스템 완료)** |

→ **사용자 체감**: **80ms** — "운동 완료" 화면 즉시 전환 (즉시감 영역)
→ **시스템 누적**: ~200ms — 최종 batch + 메모리 정리까지
→ batch 실패 시: § 10.4 의 retry 영역 + SessionTimeoutScheduler safety net

### 10.6 시나리오 6: 운동 진입 (Setup)

| t (ms) | 어디서 | 무엇이 | 누적 |
|---:|---|---|---:|
| 0 | 단말 | "운동 시작" 버튼 클릭 | 0 |
| 10 | 단말→Spring | `POST /sessions` (세션 생성) | 10 |
| 80 | Spring | session row insert, session_id 응답 | 80 |
| 100 | 단말→Spring | `GET /exercises/{id}/feedback-templates` (페르소나 자동 필터) | 100 |
| 150 | Spring | templates 응답 | 150 |
| 160 | 단말 | templateCache 메모리 적재 (분기 4-A) | 160 |
| 170 | 단말→Spring | `GET /preferences/tts` | 170 |
| 200 | Spring | tts_enabled/speed 응답 | 200 |
| 210 | 단말 | `setAudioModeAsync` ducking 활성화 (분기 9-1) | 210 |
| 220 | 단말 | 카메라 권한 확인, 첫 frame 캡처 시작 | **220** |

→ **사용자 체감**: ~220ms ("시작" → 첫 화면 안정)
→ § 1 임계값 대비: **300ms 미만, 살짝 늦은 느낌 영역 안** ✅
→ 최적화 여지: setup API 3개 병렬 호출 (`Promise.all`) 로 100ms 까지 단축 가능

### 10.7 전체 시나리오 합산 표

| # | 시나리오 | 사용자 체감 | 시스템 누적 | § 1 임계 대비 |
|:--:|---|---:|---:|---|
| 1 | 매 frame 사이클 | **0ms** (발화 없음) | ~60ms | n/a |
| 2 | **rep 완료 → 발화** ⭐ | **~75ms** | ~110ms | 100ms 즉시감 영역 ✅ |
| 3 | 세트 경계 batch (정상) | 0ms (휴식 중) | ~100ms | 30s 휴식 대비 무관 ✅ |
| 4 | 세트 경계 batch (retry) | 0~55s | 최대 55s | 30s 휴식 한계, 90s 여유 |
| 5 | 세션 종료 (ET-H) | **~80ms** | ~200ms | 100ms 즉시감 영역 ✅ |
| 6 | 운동 진입 (Setup) | ~220ms | ~220ms | 300ms 자연스러움 영역 ✅ |
| (탈락) | LLM + TTS 운동 중 (8-G) | 1~3s | 1~3s | 1s 답답함 ❌ |
| (탈락) | 클라우드 TTS 실시간 (8-C) | 300~800ms | 300~800ms | 300ms 명확 지연 ❌ |

### 10.8 핵심 관찰

1. **사용자 체감 latency 가 시스템 누적의 절반 이하** — 단말이 frame 캡처를 *동작 시작 시점이 아닌 마지막 시점* 으로 사용자 인지 기준점이 잡혀, 누적 110ms 중 사용자 체감은 ~75ms
2. **모든 정상 시나리오가 § 1 임계값 안** — 즉시감(100ms) 또는 자연스러움(300ms) 영역
3. **최악 시나리오 (BT-SET retry 55s) 도 휴식 시간 안에서 흡수** — 사용자 체감 0
4. **운동 진입 220ms 가 최적화 1순위** — Setup API 3개 병렬화로 ~100ms 단축 가능. 베타 진입 전 검토
5. **§ 10.1 의 frame 사이클 60ms 가 33ms 보다 김** — 30fps frame 송신 시 일부 frame 드롭 가능성. 실측 후 분기 7 격상(7-2 gRPC stream) 또는 송신 frame rate 조정 검토

### 10.9 사용자 시점 vs 시스템 시점

**사용자가 인지하는 latency 측정 기준점**:
- 발화 latency: *동작 끝나는 순간* 부터 *첫 음 들리는 순간* 까지
- 종료 latency: *버튼 누르는 순간* 부터 *완료 화면 표시* 까지
- 진입 latency: *시작 버튼 누르는 순간* 부터 *카메라 켜지는 순간* 까지

**시스템 누적 측정 기준점**:
- 가장 먼저 발생한 측정 가능 시점부터 가장 늦은 처리 완료 시점까지
- 디버깅·성능 개선 목적

→ § 6 재검토 트리거에서는 *사용자 체감 시점* 측정값으로 판단. 시스템 누적은 *내부 최적화 의사결정* 용.

---

## 결정 로그

- **2026-05-27 (초안)**: ShadowFit 횡단 latency reference 신설. § 1 (지각 임계값), § 2 (운동 도메인 맥락), § 3 (컴포넌트 매핑), § 4 (단위 표기), § 5 (임계 → 결정 매핑), § 6 (재검토 트리거), § 7 (측정), § 8 (비교 감각). 분기 7·8 의 latency 관련 결정 근거를 본 문서로 참조 가능하게 정리.
- **2026-05-27 (갱신 1)**: § 9 "ms 영역 vs s 영역 — 설계 철학 비교" 추가. § 3 의 컴포넌트 매핑을 *척도별 의사결정 패턴* 관점에서 재구성 — ms 영역(동기·즉시감, 외부 의존 0)과 s 영역(비동기·내구성, retry·멱등·safety net)의 정반대 철학을 박제. 100ms/1s/30s/분(min) 4개 임계선이 각각 어떤 결정 갈림(외부 API 가능 여부 / 동기-비동기 / retry 예산 / safety net)을 만드는지 명시. § 9.7 에 새 분기 등장 시 적용 가이드 추가.
- **2026-05-27 (갱신 2)**: § 10 "End-to-end latency 합산" 추가. 6개 시나리오(매 frame / rep→발화 / 세트 batch 정상 / retry / 세션 종료 / 운동 진입) 의 단계별 누적 ms 표 + 사용자 체감 vs 시스템 누적 구분. § 10.7 합산 표에 탈락한 8-C·8-G 비교 포함. § 10.8 핵심 관찰 5건 — frame 사이클 60ms 가 30fps 한계 보다 길어 향후 측정 후 분기 7 격상 검토 트리거.

---

## 관련 문서

- [`./tts-design.md`](./tts-design.md) § 8.1·8.7·10.2 — 합성 방식별 latency, 시간축
- [`./session-end-trigger.md`](./session-end-trigger.md) — ET-H afterCommit 흐름의 latency 분석
- [`../REQUIREMENTS.md`](../REQUIREMENTS.md) §1 — "실시간 부하 분리" 정신
- [`../tasks/22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) — BE-30 (TTS 효과 분석) 측정 도구
