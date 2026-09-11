# gRPC vs WebClient 실측 비교 — 착수 여부

작성일: 2026-09-08
최종 갱신: 2026-09-11 (4차 실측 완료 — §11 표에 반영)
상태: **네 라운드 완료(EC2) · 채택 미결정 — 결정 차례** — 현재 상태 한 장은 [§11 정리](#11-정리--세-라운드가-답한-것과-남은-것-2026-09-10), 4차 설계는 [`grpc-webclient-concurrency-round.md`](./grpc-webclient-concurrency-round.md). (채택 ✅ 는 사용자 confirm 후)
연관: [`./grpc-vs-webclient.md`](./grpc-vs-webclient.md)(선행 분석 — 이 문서가 그 뒤를 잇는다) ·
[`./ai-sticky-routing-probe.md`](./ai-sticky-routing-probe.md)(같은 방법론의 선례 — 정적 vs 해시 라우팅을
같은 rig로 실측 비교) · `docs/tasks/24-semester2-plan.md`(BE-07/08, 이 작업과 시간을 다투는 이미
계획된 항목) · `project_portfolio_benchmark.md`(memory) · `feedback_decision_doc.md`(memory)

---

## 1. 배경

`grpc-vs-webclient.md`가 "① 현행 유지"를 추천한 뒤, 대화로 그 근거를 한 겹씩 재검증했다:

1. **스키마 계약** — 이 프로젝트 히스토리에서 실제로 크로스언어 불일치를 잡아준 사례를 못 찾았다.
   오히려 반대 사례(`39c07e10`, #288)가 있다 — proto3가 "미설정"과 "진짜 0"을 구분 못 해
   `occurred_at`이 1970-01-01로 저장되는 버그가 났고, gRPC 계약이 이걸 막아주지 못했다.
2. **확장성** — 원 근거가 아니었다. 오히려 AI 서버를 프로세스 3개로 확장할 때 gRPC 채널 고정
   버그가 났다.
3. **스트리밍 옵션가치** — WebClient도 `Flux`/SSE로 서버 push 스트리밍이 된다(spring-webflux
   내장, 별도 의존성 불필요). gRPC만의 진짜 이점은 duplex(양방향 동시) 스트리밍뿐인데 확인된
   수요가 없다.
4. **"이진화라 빠르다"** — 🔴 **2026-09-10 실측으로 이 근거는 닫혔다.** 3차 라운드
   ([`transport-cost-breakdown`](./grpc-webclient-transport-cost-breakdown.md) ·
   [결과](../../loadtest/results/transport-breakdown-aws-2026-09-10/README.md))가 델타를 뺄셈으로
   갈랐더니, **279바이트짜리 요청에서도 1.7ms 차이**가 나고 그 대부분이 **크기와 무관한 몫**이다.
   즉 두 팔의 차이를 만드는 것은 직렬화 형식이 아니라 **클라이언트 구조**(블로킹 브리지)다 —
   「이진화라 빠르다」로 gRPC 를 정당화할 수는 없다. 원래 도입 계기가 지도교수의 이 조언이었다는 게 이번에 확인됐다.
   일반론으로는 맞는 말이지만(protobuf가 JSON보다 작고 파싱도 빠름), 이 프로젝트의 gRPC 경로는
   세션당 몇 번 안 되는 저빈도 unary 호출이라 이 스케일에서 체감되지 않는다(`grpc-vs-webclient.md`
   §2가 이미 확인한 바).

이 재검증 과정에서 **새로운 근거**가 하나 나왔다 — §3 참고. 사용자가 "그럼 WebClient 어댑터를
하나 더 만들어서 시연은 gRPC, 포폴은 WebClient로 나눠 쓰면 어떤가"를 제안했으나, 이건 (a) 8개가
아니라 7개뿐인 RPC를 영구히 두 벌 유지해야 하는 비용과 (b) "실제로 안 쓰는 코드를 이력서용으로만
짰다"로 읽힐 정직성 리스크 때문에 기각했다. 대신 **실측 비교**(둘 다 구현해서 같은 rig로 재고,
데이터로 하나를 고른 뒤 다른 하나는 걷어낸다)로 방향을 좁혔다 — `ai-sticky-routing-probe.md`가
이미 쓴 방법론과 같은 결이다.

---

## 2. 이번 라운드에서 새로 확인한 것 — 라우팅 메커니즘 중복

코드를 직접 확인한 결과, 이 시스템엔 **"어느 AI 프로세스로 보낼까"를 푸는 라우팅 메커니즘이 이미
두 개** 있다:

- **REST 경로** (`nginx-ai/default.conf:15-25`) — 프론트→AI 직결(`/pose`)은 Spring이 세션 시작
  응답으로 알려준 `X-AI-Worker` 헤더를 nginx `map` 디렉티브로 그대로 포트(8000/8001/8002)에
  꽂는다. 표준적이고, 버그 이력 없음.
- **gRPC 경로** (`ExerciseAnalysisService.java:74-110`) — Spring의 gRPC 채널은 **nginx를 아예
  거치지 않고** AI 서버에 직결한다. 그래서 `ManagedChannel` 풀 + `session_id % 3` 라우팅을
  손으로 짰고, 코드 주석에 실제 버그가 남아 있다 — "채널 인덱스와 포트(8585/8586/8587)를
  잘못 짝지어 세션 6개가 전부 워커 0으로 몰렸다(pid=7 6/6)."

즉 같은 문제를 REST 쪽은 검증된 nginx 설정으로, gRPC 쪽은 별도로 손으로 짠 Java 코드로 푼다 —
그리고 후자에서만 실제 버그가 났다. WebClient로 갔다면 Spring의 gRPC 전용 채널 풀 코드
(`ExerciseAnalysisService.java:88-95`, `aiChannelPool`/`aiAsyncStubPool`/`aiBlockingStubPool`)
자체가 필요 없었을 것 — 이미 검증된 nginx 라우팅을 그대로 재사용했을 것이기 때문이다.

> 🟢 **2026-09-10 보강**: 3차 라운드가 그 「검증된 라우팅을 재사용하는 비용」을 직접 쟀다 —
> **0.01~0.88ms**(1차의 독립 측정 0.1~0.8ms 와 일치). 즉 nginx 를 거치는 대가는 작고,
> WebClient 가 느린 이유도 홉이 아니다. 이 절의 논거는 **약해지지 않았다.**

**단, 이 논거는 방향이 있다.** 채널 고정 문제는 **Spring → AI(요청을 보내는 쪽)**에서만
생긴다 — AI가 프로세스 3개라 "어느 프로세스로 보낼까"를 골라야 하는 쪽이 이 문제를 겪는다.
**AI → Spring(콜백)** 방향은 Spring이 단일 인스턴스라 이 문제 자체가 없다. §4에서 이걸 RPC
스코프에 반영한다.

---

## 3. 현재 자원 (실측 근거)

- **RPC는 7개**(`backend/src/main/proto/exercise.proto:14-34`) — `grpc-vs-webclient.md` §2가
  "8개"라고 적은 건 오류였다(이번에 확인·정정).
  - **Spring → AI (요청, 4개)**: `ExtractReferenceData`, `StartAnalysis`, `ReattachAnalysis`,
    `StopAnalysis` — §2의 라우팅 중복 논거가 적용되는 쪽.
  - **AI → Spring (콜백, 3개)**: `SavePoseDataBatch`, `CompleteAnalysis`, `ReportFeedbackBatch`
    (`ExerciseGrpcService.java`가 서버 구현) — 라우팅 문제가 없는 쪽. 이 3개를 REST로 바꿔도
    "라우팅 재사용" 이득은 없다. 순수 프로토콜 비교(직렬화·개발 인체공학)만 남는다.
- **기존 gRPC 부하테스트 rig 존재**: `loadtest/measure_grpc_single_request_latency.sh`(ghz 기반),
  `ai-sticky-routing-probe.md`의 방법론(정적 vs 해시 실측 비교) — WebClient 쪽 대응 스크립트만
  새로 짜면 같은 비교 틀을 재사용할 수 있다.
- **기회비용**: 현재 브랜치(`feat/be07-pattern-analysis-skeleton`)가 이미 BE-07(패턴 분석,
  6h 계획)을 진행 중이고, BE-08(추천, 4h)이 그 위에 얹힌다(`24-semester2-plan.md` Week 7-8).
  이 실측 비교 작업은 이 둘과 정면으로 시간을 다툰다.

---

## 4. 선택지

| # | 선택지 | 범위 | 한 줄 |
|---|---|---|---|
| ① | **착수 안 함** | — | `grpc-vs-webclient.md`의 "① 현행 유지"를 그대로 따른다. §2의 라우팅 중복 발견은 그 문서 §5(추천 이유) 갱신에만 반영 |
| ② | **전체 실측 비교** | RPC 7개 전부 | Spring·FastAPI 양쪽에 REST 엔드포인트 7쌍 + WebClient/httpx 클라이언트 추가, 같은 rig로 gRPC와 나란히 재고 하나를 채택 |
| ③ | **좁은 실측 비교** | Spring→AI 4개만 | §2의 라우팅 재사용 논거가 실제로 적용되는 4개(요청 방향)만 REST화해서 비교. 콜백 3개는 손 안 댐 |
| ④ | **문서만 갱신** | — | 코드 변경 없이 §2의 라우팅 중복 발견을 `grpc-vs-webclient.md`에 반영만 하고, "그럼에도 유지한다면 이 비용을 알고 유지하는 것"이라는 각주로 남김 |

---

## 5. 트레이드오프

| 선택지 | 장점 | 단점 | 비용 |
|---|---|---|---|
| ① 착수 안 함 | 비용 0, 지금 스케줄(BE-07/08) 안 건드림 | §2의 구체적 발견(라우팅 중복)이 실측 없이 주장으로만 남음 | 0 |
| ② 전체 실측 | 7개 전부 비교하면 "완전히 검증했다"는 서사가 가장 강함 | 콜백 3개는 애초에 라우팅 이득이 없어(§2) 비교해도 "차이 없음"이 나올 공산이 큼 — 절반의 작업이 결론에 안 붙는 실측이 됨. BE-07/08과 정면 충돌 | 큼 — 7쌍 엔드포인트(Java+Python) + 부하테스트 rig 확장 + 재검증 |
| ③ 좁은 실측 | §2가 실제로 주장하는 지점(요청 방향 라우팅 재사용)만 정확히 겨냥 — 작업량 대비 결론이 뚜렷함 | 콜백 경로(3개)는 여전히 gRPC로 남아 "완전 전환"은 아님 — 하이브리드 상태가 영구화될 수 있음(전에 §3에서 비추천했던 "③ 하이브리드"와 결이 겹침, 다만 이번엔 "잠정"이 아니라 "이 4개만 처음부터 이유가 있어 REST, 나머지 3개는 이유가 없어 유지"라는 게 다름) | 중간 — 4쌍 엔드포인트 + rig 확장 |
| ④ 문서만 | 비용 거의 0, §2 발견을 잃지 않고 기록에 남김 | 실측 없이 "라우팅 재사용하면 더 나을 것"이라는 주장에 머무름 — 포폴 서사에서 "실측했다"고는 못 씀 | 아주 작음 — 문서 편집 |

---

## 6. 추천

**④(문서만 갱신)를 우선 추천하고, 시간 여유가 실제로 생기면 ③(좁은 실측)을 다음 단계로 추천한다.
②(전체 실측)는 비추천한다.**

이유:
- ②는 콜백 3개(§4 후자 그룹)에 대해서는 애초에 "라우팅 재사용" 논거가 성립 안 하므로(§2 마지막
  단락), 그 3개를 REST로 바꿔서 재는 건 처음부터 "차이 없음"이 나올 걸 알면서 하는 실측에 가깝다
  — `feedback_measure_design_needs_repeats.md`가 경고하는 것과는 다른 종류지만, "결론이 이미
  보이는 실험에 공수를 쓴다"는 점에서 비효율은 같다.
- ③은 §2가 실제로 발견한 것(요청 방향 라우팅 중복 + 그중 하나에서만 버그 이력)을 정확히 겨냥한
  실측이라 작업량 대비 서사가 뚜렷하다 — "라우팅을 재사용하면 정말 처리량 손해가 없는가"를
  `ai-sticky-routing-probe.md`와 같은 rig로 답할 수 있다.
- 다만 지금 이 순간은 BE-07이 진행 중이고 BE-08이 뒤따르는 시점(§3)이라, ③조차 "지금 당장"은
  기회비용이 크다. 그래서 1차로는 ④만 하고, ③은 BE-07/08이 끝난 뒤 여유가 있을 때 다시 논의하는
  걸 권한다.

---

## 7. 미결 질문

- ③을 실제로 착수한다면 시점을 언제로 볼지 — BE-07/08 완료 후인지, 2학기 계획(`24-semester2-plan.md`)
  전체가 끝난 뒤인지.
- ③의 결과가 "REST가 더 낫다"로 나오면, 이미 검증된 gRPC 4개 요청 RPC를 실제로 걷어낼지 —
  아니면 결과만 포폴 서사에 쓰고 프로덕션 코드는 유지할지(이 경우 §1에서 기각한 "이력서용
  코드" 리스크가 다시 생기므로, 결과가 나오면 반드시 하나로 정리해야 함).

---

## 8. 구체 설계 — ③ 착수 시 쓸 스펙 (2026-09-08 추가, 착수는 아직 아님)

BE-07/08 이후 ③을 실제로 시작할 때 바로 쓸 수 있도록 설계만 미리 굳혀 둔다. **코드는 아직
없다** — §6의 순서(④ 먼저, ③은 여유 생기면)는 안 바뀐다.

### 8.1 스코프 — 4개 RPC → REST 엔드포인트

`exercise.proto`(7개 RPC 확인, §3)의 요청 방향 4개만 대상:

| gRPC RPC | 요청 메시지 | 제안 REST 엔드포인트 |
|---|---|---|
| `ExtractReferenceData` | `ExtractRequest`(exercise_id, youtube_url, extracted_poses[]) | `POST /internal/reference/extract` |
| `StartAnalysis` | `AnalyzeRequest`(exercise_id, session_id, reference_source, reference_poses[], persona, session_nonce) | `POST /internal/sessions/start` |
| `ReattachAnalysis` | `ReattachRequest`(exercise_id, session_id, reference_poses[], persona, initial_rep_count, elapsed_sec, session_nonce) | `POST /internal/sessions/{session_id}/reattach` |
| `StopAnalysis` | `StopRequest`(session_id) | `POST /internal/sessions/{session_id}/stop` |

응답 메시지(`ExtractResponse`/`AnalyzeResponse`/`ReattachResponse`/`StopResponse`)와 공용 메시지
`PoseDataRequest`는 그대로 Pydantic 모델로 1:1 대응시킨다 — 새 필드를 추가하지 않는다(순수
프로토콜 비교가 목적이라, 계약을 동시에 바꾸면 무엇 때문에 결과가 달라졌는지 못 가른다).

### 8.2 인증 — 🔴 지금 그냥 얹으면 안 된다

`ai-server/app/middleware/auth.py`는 **모든 HTTP 요청**(공개 경로 제외)에
`Authorization: Bearer <AI_PUBLIC_TOKEN>`을 요구한다. 이 토큰은 **앱 번들에 들어가 배포되는
값**이다(`config.py:56` 주석, 이슈 #134). 반면 이 4개 RPC는 지금 gRPC `AuthInterceptor`가
**`INTERNAL_API_TOKEN`**(서버 밖으로 안 나가는 값)로 지킨다. `config.py:178-183`엔 두 값이
같으면 기동을 거부하는 가드까지 있다(#230) — 이 프로젝트가 이미 "번들 추출 토큰으로 내부
RPC를 못 치게" 하는 걸 명시적 불변식으로 못 박아 둔 것이다.

**그래서 새 REST 엔드포인트를 그냥 `InternalAuthMiddleware` 아래 두면 안 된다** — 그러면
`AI_PUBLIC_TOKEN`(추출 가능)만으로 세션 시작·재부착까지 칠 수 있게 되어 #134가 막은 구멍이
재발한다. 두 방향 중 하나를 골라야 하고, 이건 **사용자 confirm이 필요한 설계 선택**이다:

- (a) `PUBLIC_PATHS`처럼 이 4개 경로를 미들웨어에서 예외 처리하고, 별도로 `X-Internal-Token`
  헤더 + `INTERNAL_API_TOKEN` 대조를 얹는다(Spring 쪽 `/internal/exercises/pose-data`가 이미
  쓰는 패턴과 동형). 기존 보안 경계를 그대로 유지 — 권장.
- (b) 그냥 `AI_PUBLIC_TOKEN`을 재사용한다. 구현은 제일 쉽지만 #134가 고친 문제를 이 4개
  RPC에서만 되돌리는 셈이라, 착수 시점에 반드시 사용자에게 다시 확인받아야 한다.

### 8.3 라우팅 — 이 실험의 실질 목적

Spring이 계산하는 `routingKey`(`ExerciseAnalysisService.java:160`의 `session_id % 3`과 같은
값)를 WebClient 요청에 `X-AI-Worker` 헤더로 실어 `ai-nginx`(`nginx-ai/default.conf:15-25`)를
거치게 한다. 대상은 nginx가 이미 듣는 8000 포트 — gRPC 전용 8585-8587이나 Spring의 수동
`ManagedChannel` 풀은 이 경로에 없다.

### 8.4 Java 쪽 인터페이스

```java
public interface AiAnalysisClient {
    ExtractResult extractReferenceData(...);
    AnalyzeResult startAnalysis(...);
    ReattachResult reattachAnalysis(...);
    StopResult stopAnalysis(...);
}
```
`GrpcAiAnalysisClient`(기존 로직 이관) / `WebClientAiAnalysisClient`(신규) 두 구현체,
`ai.client.type=grpc|webclient` 프로퍼티로 스위치. 반환 타입은 gRPC 메시지 클래스에 묶이지
않는 평범한 DTO로 둬서 호출부(`ExerciseAnalysisService`의 나머지 로직)가 어느 구현체든
그대로 쓸 수 있게 한다. 에러는 `StatusRuntimeException`/`WebClientResponseException`을
각각 같은 `BusinessException`/`ErrorCode`로 매핑. `CircuitBreakerRegistry`는 프로토콜
무관이니 그대로 재사용.

### 8.5 부하테스트

`loadtest/measure_grpc_single_request_latency.sh`(ghz 기반)와 나란히 REST 버전 스크립트를
추가 — 같은 부하 패턴으로 4개 엔드포인트를 때려 지연·스레드 수·라우팅 정확도(어느 워커가
받았는지 로그로 대조)를 gRPC와 델타 비교한다. 로컬 박스([[project_loadtest_env_constraint]])로는
상대 비교까지만, 절대 처리량 수치가 필요해지면 AWS 라운드로 넘긴다.

---

## 9. 착수 현황 (2026-09-10 기준)

⚠️ **§6 이 권한 순서(④ 먼저, ③ 은 BE-07/08 이후)와 실제 진행이 다르다.** 코드는 ③(좁은 실측)의
구현부가 먼저 들어갔고, ④(문서 갱신)는 오히려 이 절이 처음이다. 이 문서는 진행된 사실을 적을
뿐이고, **어느 프로토콜을 채택하는지는 여전히 미결정**이다 — 결정 로그는 아래에 비어 있다.

들어온 것 넷:

| 무엇 | 내용 |
|---|---|
| 설계 문서 | 이 문서 |
| 추상화 | `AiAnalysisClient` 인터페이스 분리 + `GrpcAiAnalysisClient` 로 전송 로직 이관, `AiCallOutcome` 으로 에러 정규화 (§8.4) |
| 두 번째 구현체 | `WebClientAiAnalysisClient` — `ai.client-type` 스위치(기본 `grpc`), `X-AI-Worker` 헤더로 `ai-nginx:8000` 경유 (§8.3) |
| AI 미러 | REST 4개 — `POST /api/v1/internal/analysis/{extract-reference,start,reattach,stop}` (§8.1) |

### 9.1 설계 대비 달라진 것

- **경로 이름이 §8.1 표와 다르다.** 실제 구현은 `/internal/reference/extract`·`/internal/sessions/start`
  같은 모양이 아니라 `/api/v1/internal/analysis/*` 한 접두사 아래에 4개를 모았다 — 인증 분기를
  경로 접두사 하나(`INTERNAL_TOKEN_PREFIX`)로 판정하기 위해서다(9.2). §8.1 표는 제안이었고,
  실물은 이쪽이다.
- **§8.2 는 (a) 로 갔다.** 새 4개 경로는 `AI_PUBLIC_TOKEN`(앱 번들 배포값)이 아니라
  `INTERNAL_API_TOKEN` 으로 지킨다(`ai-server/app/middleware/auth.py`). #134/#230 이 세운 경계를
  그대로 유지한 쪽이다. 🔴 다만 §8.2 는 이걸 **사용자 confirm 이 필요한 선택**으로 적어뒀는데,
  구현이 confirm 을 거쳤다는 기록이 이 문서에 없다 — 되짚을 필요가 있으면 여기다.
- **REST 미러는 로직을 복제하지 않는다.** 기존 `ExerciseServicer`(gRPC 서비서) 메서드를 in-process
  로 부르는 얇은 어댑터다. `context.abort()` 는 `_FakeContext` 로 흉내낸 뒤 HTTP 400 으로 옮긴다.

### 9.2 부작용 — 관측 태그가 하나 합쳐졌다 (2026-09-10 (다)안으로 닫음)

`AiCallOutcome` 이 프로토콜 특유 예외 타입을 호출자에 안 흘리므로, `shadowfit.ai.stop.result` 의
`outcome` 태그에서 `grpc-error`(옛 `StatusRuntimeException`)와 `error`(그 외)의 구분이 사라지고
**`error` 하나로 합쳐졌다**(`ExerciseAnalysisService.java:438-452`).

🔴 **아직 안 정한 것**: `shadowfit.ai.reattach.result` 와 `shadowfit.session.transitions` 는 여전히
`grpc-error` 라는 이름의 태그를 쓴다. `ai.client-type=webclient` 로 돌리면 gRPC 가 한 줄도 안
끼는데 태그는 `grpc-error` 라고 적힌다 — **이름이 사실과 어긋난다.** 바꾸려면 대시보드·알림이
보는 태그 값을 건드리는 계약 변경이라, 실측 라운드를 돌리기 전에 정하는 게 낫다:

| # | 안 | 대가 |
|---|---|---|
| (가) | 그대로 둔다 | webclient 라운드의 지표를 읽을 때 사람이 매번 "이건 HTTP 에러다" 를 번역해야 함 |
| (나) | `transport-error` 로 개명 | 기존 시계열과 태그가 끊긴다 — 옛 데이터와 나란히 못 본다 |
| (다) | `grpc-error` 를 유지하되 `protocol` 태그(grpc/webclient)를 하나 더 붙인다 | 카디널리티 +2배, 대신 A/B 를 지표에서 바로 가를 수 있다 |

✅ **(다) 채택** (2026-09-10 사용자 결정). `shadowfit.session.transitions`·`shadowfit.ai.stop.result`·
`shadowfit.ai.reattach.result` 세 지표에 `protocol` 태그가 붙었다(값은 `ai.client-type`). 이름이
아니라 **라벨을 더한** 것이라 기존 PromQL·Grafana 패널은 그대로 매칭된다 — (나) 개명이었으면
시계열이 끊겼다. 🔴 남은 것: `grpc-error` 라는 **값** 자체는 webclient 팔에서 여전히 이름이
어긋난다. 그 개명은 시계열이 끊기는 별개 결정이라 안 했다.

### 9.3 아직 안 된 것

- ✅ §8.5 rig 는 만들었고 1차 라운드를 돌렸다 — §10.
- ~~🔴 두 구현체의 동작 동등성은 **단위 테스트 수준**까지만 확인됐다(backend 37건 · ai-server 15건 통과,
  2026-09-10). 컨테이너를 띄워 `ai.client-type=webclient` 로 **세션을 끝까지 태워 본 적은 없다.**~~
  ✅ **2026-09-10 저녁, webclient 팔로 세션 한 번을 끝까지 태웠다 — §9.4.**
- `docs/architecture/` 반영은 2026-09-10 에 했다.
- 🔴 **여전히 안 된 것**: §10.4 가 1순위로 지목한 **프로덕션 클라이언트 지연 재측정**은 이
  통주행과 별개다 — 여기는 팔당 1판·로컬이라 지연을 안 잰다(「돈다」와 「같은 값이 나온다」까지다).

### 9.4 webclient 팔 통주행 (2026-09-10, 로컬 docker compose)

**무엇을 했나**(webclient 팔): `AI_CLIENT_TYPE=webclient` 로 백엔드를 재기동하고(이 값은 이번에 compose 로
뚫었다 — 아래), 가입→로그인→온보딩→세션 시작→프레임 유입(3fps)→세션 종료→리포트 조회를
한 사람 몫으로 한 번 통과시켰다. 프레임 입력은 저장소 밖 스쿼트 영상(39초)이고, 드라이버는
`ai-server/scripts/e1_walkthrough.py` 의 흐름에 **전송 페이싱만 얹은 임시 사본**이다(아래 ⚠️).

**결과 — 배관**:

| 확인 | 근거 |
|---|---|
| Spring→AI 4개 중 2개가 **REST 로 나갔다** | `ai-nginx` 액세스 로그에 `POST /api/v1/internal/analysis/start` · `.../stop` 각 200, User-Agent `ReactorNetty/1.2.18`(= Spring WebClient). gRPC 였다면 8585 직결이라 nginx 에 한 줄도 안 남는다 |
| AI 쪽 REST 미러가 받았다 | ai-server 로그에 같은 두 경로 200 |
| **콜백은 여전히 gRPC 다** | `pose_data` 26행이 쌓였고(SavePoseDataBatch), 세션이 `COMPLETED` 로 뒤집힌 출처가 `source="ai-callback"` |
| 지표가 팔을 가른다 | `shadowfit_ai_stop_result_total{outcome="ok",protocol="webclient"}` · `shadowfit_session_transitions_total{protocol="webclient",source="ai-callback",status="COMPLETED"}` — §9.2 (다)안이 실제로 동작한다 |

**결과 — 내용**(배관만 통과하고 값이 0 이었던 #196 을 안 반복하려고 따로 센다): rep **8회** ·
`totalReps` 8 · `avgSyncRate` **85** · `repTrend` 8건 · `worstSection` 채워짐(3회차 79%) ·
`outbox_events` = `STOP_ANALYSIS:SENT`. **즉 이 팔에서 사슬이 끊기는 자리는 없었다.**

**대조 판 — grpc 팔도 같은 입력으로 한 번 (같은 날 이어서)**: 백엔드를 기본 팔(`grpc`)로
재기동해 **같은 영상·같은 드라이버**로 다시 태웠다(세션 104119).

| | webclient(104118) | grpc(104119) |
|---|---|---|
| rep 완성 프레임 번호 | 13·34·43·53·63·75·86·116 | **동일** |
| `totalReps` / `avgSyncRate` / `repTrend` | 8 / 85 / 8건 | **8 / 85 / 8건** |
| `pose_data` | 26행 | 26행 |
| nginx `internal/analysis` 로그 | start·stop 각 200 | **한 줄도 없음**(8585 직결) |
| `protocol` 태그 | `webclient` | `grpc` |

즉 **전송을 바꿔도 판정 결과가 안 바뀐다**는 것까지는 이 판이 보인다(같은 입력 1판 기준).

**한계 — 이 판이 답하지 않는 것**:
- **팔당 1판이다.** 지연·처리량은 안 쟀다([[feedback_measure_design_needs_repeats]] — 팔당 1판은 효과를 못 가른다). 판정은 「돈다」와 「같은 값이 나온다」까지다.
- `session_feedback_logs` 는 **0행**이다 — 프로토콜과 무관한 기존 결함(#193, 감지기 자체가 없다)이라 이 통주행의 실패로 세지 않는다.
- 로컬 2코어 박스다([[project_loadtest_env_constraint]]).

⚠️ **드라이버를 그대로 쓰면 안 된다 — 저장소 스크립트에 페이싱 손잡이가 없다.** 첫 시도는
`e1_walkthrough.py` 를 그대로 썼는데 11장 중 **4장이 `RATE_LIMITED`** 로 잘렸다. 서버가
`MIN_FRAME_INTERVAL_SEC`(**300ms**, `ai-server/app/grpc/session_state.py:64` — 클라 규약
`exercise.tsx intervalMs=330` 보다 한 칸 아래로 둔 값이다)로 판정 유입을 자르는데 스크립트는
읽는 속도대로 쏘기 때문이다. 350ms 간격으로 페이싱한 사본에서는 **117장 전부 판정에 들어갔다**.
드라이버 자체의 결함으로 [#714](https://github.com/Shadowfit/init/issues/714) 에 남겼다 —
이 PR 에서 스크립트를 고치지는 않았다.
(첫 시도는 영상도 3.4초짜리라 rep 0 이었다 — 두 원인이 겹쳐 있었다.)

**부수 변경**: `docker-compose.yml` 백엔드 서비스에 `AI_CLIENT_TYPE: ${AI_CLIENT_TYPE:-grpc}` 를
추가했다. 없으면 `application.yml` 의 `${AI_CLIENT_TYPE:grpc}` 가 컨테이너 환경에서 안 잡혀
**webclient 팔로 띄울 방법 자체가 없다**(재빌드 없이 팔을 바꾸는 다른 손잡이들과 같은 패턴).

---

## 10. 1차 실측 (2026-09-10, EC2)

전문: [`../../loadtest/results/grpc-webclient-ab-2026-09-10/README.md`](../../loadtest/results/grpc-webclient-ab-2026-09-10/README.md)

측정 층은 **AI 직접**(Spring 미경유)으로 좁혔다 — Spring 을 태우면 Start/Extract 가
fire-and-forget 이라 응답 지연이 AI 호출 비용을 거의 안 비추고, DB·아웃박스가 델타를 덮는다.
대상은 AI 만 사는 `c7i.2xlarge`, 부하기는 별도 `c7i.large`. 5블록(0 버림)·라틴 방격 회전·
전 셀 fail=0.

### 10.1 답이 나온 것

| 질문 | 답 (평균, 유효 4블록 중앙값) |
|---|---|
| **nginx 홉 비용** — «검증된 라우팅 재사용» 의 값 | **0.1~0.8 ms/호출.** 같은 도구끼리의 뺄셈이라 도구 오프셋이 안 낀다 — 이 라운드에서 가장 단단한 숫자 |
| **작은 요청(24B)의 프로토콜 비용** | **0.45~1.38 ms/호출.** 도구를 바꿔 재도 같은 값이 나왔다 |
| **nginx 가 헤더대로 갈라 보내나** | ✅ `X-AI-Worker` 0/1/2 로 친 3건이 각각 다른 프로세스에서 처리됨(로그 근거 있음) |

### 10.2 🔴 답이 «안» 나온 것 — 대조군이 본 라운드를 뒤집었다

큰 페이로드(84KB)의 프로토콜 비용은 **도구를 바꾸니 부호까지 뒤집혔다**:

| 크기 | ghz 로 잰 gRPC | k6 로 잰 gRPC |
|---|--:|--:|
| S(24B) c=1 | +0.459 | +0.450 ✅ |
| L(84KB) c=1 | +1.073 | **−0.480** 🔴 |
| L(84KB) c=3 | +2.874 | **+0.740** 🔴 |

두 gRPC 클라이언트가 다른 답을 준다 = 그 델타는 서버 쪽 프로토콜 차이가 아니라 **클라이언트가
84KB 를 인코딩하는 비용**이 지배한다. 그리고 **둘 다 Spring 의 Java 클라이언트가 아니다.**

이걸 잡으려고 대조군을 붙인 것이고, 실제로 잡혔다. 대조군이 없었으면 «gRPC 가 큰 페이로드에서
2.9ms 빠르다» 를 그대로 썼을 것이다.

### 10.3 §1 의 근거들에 이 라운드가 미치는 영향

- **«이진화라 빠르다»(§1-4, 지도교수 조언)** — 작은 요청에선 **방향이 맞다**(gRPC 가 빠르다).
  다만 **크기가 1ms 미만**이고, 「클수록 더 벌어진다」는 부분은 이 판이 **못 받친다**(10.2).
- **«라우팅 재사용»(§2)** — 비용이 0.1~0.8ms 로 작다는 게 실측으로 나왔다. §2 의 논거가
  **약해지지 않았다.**
- **제품 관점** — 이 4개 RPC 는 세션당 몇 번 안 일어난다. 호출당 0.5~1ms 는 분 단위 세션에서
  의미 있는 차이가 아니다.

#### 10.4 ~~다음 라운드가 답해야 할 것 (1순위)~~ → ✅ **2026-09-10 실행됨**

> **2차 라운드 결과**: [`ai-call-latency-ab-aws-2026-09-10`](../../loadtest/results/ai-call-latency-ab-aws-2026-09-10/README.md) ·
> 설계 [`grpc-webclient-production-client-round.md`](./grpc-webclient-production-client-round.md).
> 프로덕션 클라이언트로 재니 **큰 요청(Reattach 97.6KB)에서 grpc 5.8~7.6ms ↔ webclient 8.6~9.1ms**,
> **작은 요청(Stop)에서 1.8~2.7ms ↔ 3.8~4.1ms** 로 **두 자리 다 범위가 안 겹친다** — 즉 §10.2 가
> 「판정 불가」로 남긴 자리가 이 무대에서는 닫혔고, **방향은 gRPC 우세**다. 크기는 큰 요청 +3ms ·
> 작은 요청 +1.8ms 이고, 이 두 RPC 는 세션당 몇 번이라 **사용자 체감에서의 몫은 여전히 작다.**
> 🔴 fire-and-forget(Start)은 블록4 이상치로 **판별 불가**이고, 채택 결정은 그대로 열려 있다.

**프로덕션 클라이언트로 재기.** 10.2 가 보여준 대로 클라이언트 구현이 델타를 지배할 수 있는데,
정작 실제로 쓰이는 Java gRPC / Spring WebClient 로는 한 번도 안 쟀다. 큰 페이로드 답이 필요하면
거기서 재야 한다 — Spring 을 태우되 **블로킹 RPC**(`ReattachAnalysis`/`StopAnalysis`)로 좁히면
fire-and-forget 문제를 피할 수 있다.

---

## 11. 정리 — 세 라운드가 답한 것과 남은 것 (2026-09-10)

세 라운드가 끝났다. 여기가 **이 주제의 현재 상태 한 장**이고, 개별 라운드의 정본은 각 문서다.

### 11-1. 라운드 셋

| 라운드 | 무엇을 쟀나 | 답 | 한계 |
|---|---|---|---|
| **1차** ([§10](#10-1차-실측-2026-09-10-ec2)) | ghz·k6 로 **AI 직접** | nginx 홉 **0.1~0.8ms** · 작은 요청 프로토콜 비용 0.45~1.38ms | 🔴 큰 페이로드는 **도구를 바꾸니 부호가 뒤집혀** 판정 불가 |
| **2차** ([설계](./grpc-webclient-production-client-round.md) · [결과](../../loadtest/results/ai-call-latency-ab-aws-2026-09-10/README.md)) | **프로덕션 클라이언트**의 왕복(`shadowfit.ai.call`) | Stop **+1.8ms** · Reattach **+3.0ms**(webclient 가 느림), 블록 범위 안 겹침 | 🔴 델타의 **기제**를 모름. 작은 요청에서도 1.8ms 라 직렬화로 설명 안 됨 |
| **3차** ([설계](./grpc-webclient-transport-cost-breakdown.md) · [결과](../../loadtest/results/transport-breakdown-aws-2026-09-10/README.md)) | 팔 3개 **뺄셈**(홉 ↔ 잔여) | **기제는 홉이 아니라 클라이언트 쪽** — 홉 0.01~0.88ms, 잔여 1.35~3.03ms. 커넥션 수립도 아님(재사용률 98.8) | 🔴 잔여의 **내부 분해**(브리지 ↔ 직렬화) 안 함 · 동시성 미측정. **4차가 정정**: 잔여에는 AI 쪽 경로 차이(REST 미러 ↔ gRPC 서비서)도 들어 있다 |
| **4차** ([설계](./grpc-webclient-concurrency-round.md) · [결과](../../loadtest/results/ai-call-concurrency-aws-2026-09-11/README.md)) | 같은 두 팔을 **c=1~32 동시 재부착**으로 — 지연·처리량·컨테이너 CPU/호출 | 델타는 c 와 함께 **커진다**(1.2~3.9 → 9.7~29.0ms) — 그러나 꺾이는 자리는 클라이언트 문턱이 아니라 **AI 포화(c=8)**. **AI 가 REST 미러에서 호출당 +1~3 cpu-ms**(5수준 안 겹침) → 포화 처리량 **−15%**. Spring CPU 대가는 **검출 안 됨** | 🔴 Spring CPU/호출 판별 불가(칸 1~14초) · AI 쪽 1~3ms 내부 미분해 · 재시작 칸 1개 원인 미검증 |

### 11-2. gRPC 를 정당화하던 근거 넷 — 지금 상태

| 근거 | 상태 |
|---|---|
| 스키마 계약이 크로스언어 불일치를 막는다 | 🔴 **반증에 가깝다** — 이 저장소에서 그 사례 0건, 오히려 proto3 때문에 `occurred_at`=1970 버그(#288) |
| 확장성 | 🔴 **원 근거가 아니었다** — 오히려 프로세스 3개로 늘릴 때 gRPC 채널 고정 버그가 났다 |
| 스트리밍 옵션가치 | 🟡 **duplex 만 고유**. WebClient 도 `Flux`/SSE 로 서버 push 가 된다. **확인된 수요 없음** |
| "이진화라 빠르다"(지도교수 조언) | 🟡 **절반 다시 열렸다(4차)** — 3차는 「형식이 아니라 클라이언트 구조」로 닫았는데, 4차에서 **AI 프로세스가 REST 경로에서 호출당 1~3 cpu-ms 를 더 쓴다**는 것이 나왔다(JSON 파싱·검증일 가능성, 내부 미분해). 즉 «직렬화 형식의 서버 쪽 비용» 은 있다. 다만 Spring 쪽에서는 여전히 크기 무관 몫이 지배한다 |

즉 **지연을 이유로 gRPC 를 유지한다는 근거는 세 라운드를 거치며 얇아졌다.** 반대로 §2 의
「라우팅 이원화」 논거는 3차에서 홉이 싸다는 것이 확인돼 **강해졌다.**

### 11-3. 그럼에도 «바꿔야 한다» 는 아직 아니다

- 사용자가 실제로 기다리는 자리는 **재부착 한 곳**뿐이다(Start 는 fire-and-forget, Stop 은 아웃박스
  발행기). 거기서 3ms 다.
- 팔 비교의 고전적 유의성은 블록 5개 기준 부호검정 양측 **p=0.0625** 라 「통계적으로 유의」로
  쓰면 과장이다(3차의 홉↔잔여 판정은 범위가 자릿수로 벌어져 이 논쟁에 덜 민감하다).
- ~~**동시성 축이 통째로 비어 있다.**~~ **4차로 채워졌다(2026-09-11)**: 브리지·루프 쪽 동시성 대가는 **검출되지 않았고**,
  동시성에서 갈리는 것은 **AI 쪽 호출당 비용 → 포화 처리량 −15%** 였다. Spring CPU 는 판별 불가로 남았다.

### 11-4. 남은 선택지

| # | 선택 | 필요한 것 |
|---|---|---|
| ㄱ | **지금 결정한다** | 추가 측정 없이, 위 표만으로 «유지» 또는 «REST 로 정리» 를 고르고 반대편 코드를 걷어낸다 |
| ㄴ | **4차(동시성 축) 먼저** | 같은 두 팔을 c=1 이 아니라 동시 호출로 재서 스레드 점유·처리량 대가를 본다. 기제(브리지)가 지연보다 여기서 크게 나올 수 있다 |
| ㄷ | **잔여 내부 분해 먼저** | 클라이언트 안에 계기를 더 넣어 «브리지 ↔ 직렬화» 를 가른다. 학술적으로는 깔끔하나 **채택 판단을 바꾸지는 않는다** |

**추천: ㄴ → 결정.** ㄷ 는 결정에 안 쓰이므로 뒤로 미룬다.

**ㄴ 은 끝났다(2026-09-11).** 이제 표에 오른 것: c=1 델타 1.2~3.9ms · 포화 처리량 −15% · AI 호출당 +1~3 cpu-ms ·
홉 0.5 cpu-ms + 114KB 본문 디스크 버퍼링 · Spring 쪽 동시성 대가 미검출 · 라우팅 이원화 논거(변함없음). **결정은 사용자 몫.**

✅ **2026-09-11 사용자 결정: ㄴ.** 설계는 [`grpc-webclient-concurrency-round.md`](./grpc-webclient-concurrency-round.md) —
팔 2(풀 3) × c ∈ {1, 4, 8, 16, 32}(구조 문턱) × 지표 5(지연·start 콜백·처리량·Spring CPU/사이클·AI CPU).
착수 전 그 문서 §7 의 미결 6개(팔 구성·서버 스레드 상한·아웃박스 배치·드라이버 자리·도구·계기)를 확인해야 한다.

---

## 결정 로그

(채택은 여전히 **미결** — 사용자 confirm 대기.)

- 2026-09-10: 1차 실측(§10). «nginx 홉이 싸다»·«작은 요청 차이는 1ms 안팎» 둘만 확정됐고,
  큰 페이로드는 도구 의존으로 판정 불가였다.
- 2026-09-10: 2차 실측(프로덕션 클라이언트). 큰 요청 +3.0ms · 작은 요청 +1.8ms 로 두 자리 다
  갈렸다. **크기는 나왔지만 기제는 미규명.**
- 2026-09-10: 3차 실측(전송 비용 분해). **기제는 nginx 홉이 아니라 클라이언트 쪽**이고, 커넥션
  수립도 아니다. 그 결과 「이진화라 빠르다」 근거가 닫혔고 「라우팅 재사용은 싸다」가 확인됐다.
- 2026-09-11: **§11-4 는 ㄴ 으로 결정(사용자).** 4차(동시성 축) 설계 문서를 세웠다 —
  `grpc-webclient-concurrency-round.md`. 실행·착수 시점은 미정.
- 2026-09-11: **4차 실측 완료(동시성 축).** 델타는 c 와 함께 커지나 기제는 AI 쪽 호출당 비용(포화 처리량 −15%),
  Spring 클라이언트 구조의 대가는 검출 안 됨. 3차의 「잔여 = 클라이언트」는 «클라이언트 + 서버 경로» 로 정정.
- **아직 안 정한 것**: 어느 프로토콜을 쓸지(4차까지 끝났으니 이제 결정 차례). 결정하면 **반대편 구현을
  걷어내는 것까지가 한 작업**이다 — 두 벌을 영구히 두는 것은 §1 에서 이미 기각했다.
