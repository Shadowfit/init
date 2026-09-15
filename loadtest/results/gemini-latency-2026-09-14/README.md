# Gemini 응답시간 실측 — 2026-09-14 (report-generation-llm.md §14-4 1단계)

스크립트: `loadtest/llm/measure_gemini_latency.py` · 원자료 `raw.jsonl` · 자동 요약 `summary.md`
조건: 주간 집계 페이로드 1,228B(프롬프트 578 tok), 라운드 11(첫 라운드 버림) × 모델 3, 시작 순서 회전, 호출 간격 2s, temperature 0.2, JSON 스키마 강제. 로컬 PC(가정 회선) → `generativelanguage.googleapis.com`. 무료 티어 키.

## 결과

| 모델 | 성공 | p50 | p95 | max | 출력 tok | thinking tok | JSON ok | 인용 불일치 | 한국어만 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| gemini-3.5-flash-lite | 10/10 | 1.51s | 1.66s | 1.66s | 161 | 없음 | 10/10 | 0 | 10/10 |
| gemini-3.5-flash | 10/10 | 9.06s | 26.2s | **39.1s** | 289 | 2,022 | 10/10 | 0 | 10/10 |
| gemini-3.8-flash | 0/10 | — | — | — | — | — | — | — | — |

- **3.5-flash-lite**: 10회 전부 1.1~1.7s, 분산이 작다(꼬리 없음). thinking 없음.
- **3.5-flash**: 10회 중 9회 7~10s, 1회 39s. 응답의 대부분이 **thinking 1,400~2,300 tok** 이고 우리가 쓰는 출력은 ~290 tok 이다 — 3문장 요약에 사고 토큰이 7배. 39s 건은 thinking 이 특별히 많지 않았다(1,712) — 서버 쪽 지연으로 보이며 원인은 이 실측으로 못 가른다.
- **3.8-flash**: 11회 전부 `503 UNAVAILABLE "high demand"` — 무료 티어에서 이 모델은 이 시각에 아예 못 썼다. 가용성이 곧 실패 모드라는 뜻.
- `gemini-2.5-flash-lite` 는 `404 "no longer available to new users"` — 08-24 결정 로그가 예로 든 `gemini-1.5-flash` 계열도 같은 운명일 것. **모델 이름은 설정값이어야** 한다(`generation_model` 컬럼이 그래서 있다).
- 검증 예행: 세 조건(JSON 파싱·인용 숫자가 입력에 있음·한국어) 30회 중 위반 0. «한국어» 는 처음엔 «허용 문자만» 이었는데
  리뷰(#758)에서 영문 전용 문장도 통과한다는 지적을 받아 «한글 음절 최소 1자» 를 더했다 — raw.jsonl 을 새 규칙으로 재집계해도 20/20 그대로. 다만 flash-lite 초반 라운드는 `totalReps`·`repWeightedSyncRate` 같은 **필드명을 그대로 문장에 썼다** — 프롬프트 v1 에서 한국어 라벨을 입력에 같이 줘야 한다(측정 아닌 프롬프트 몫).

## 이 숫자가 정하는 것 (§14-4)

- **timeout-seconds**(HTTP 클라이언트): flash-lite 기준 max 1.66s. 3.5-flash 를 쓰면 39s 꼬리를 품어야 한다.
- **lock-timeout**(발행기 리스) = batch × max 여유. batch 를 기존 발행기와 같은 20으로 두면 flash-lite 는 20 × 1.66 ≈ 33s 로 기존 60s 안에 들어오고, 3.5-flash 는 20 × 39 ≈ 780s 라 기존 리스로는 **중복 호출이 난다** — §5-1 이 예견한 그대로.
- **max-retry / backoff**: 503(과부하)·429(한도)는 RETRY, 그 외는 TERMINAL. 횟수는 무료 티어 분당·일일 한도에서 역산 — **한도값은 이 실측에 없다**(ai.google.dev/gemini-api/docs/rate-limits 를 읽고 날짜와 함께 인용할 것).

## 추천 (결정 아님)

**gemini-3.5-flash-lite.** 이 작업(숫자 십수 개 → 3문장)은 thinking 이 필요 없고, flash-lite 가 세 검증을 전부 통과하면서 p95 가 6분의 1 이하다. 3.5-flash 의 39s 꼬리는 리스 설계를 통째로 바꾸게 만든다. 모델명은 `application.yml` 설정으로 두고 이 표를 근거로 기본값만 flash-lite 로.

## 한계

- N=10/모델, 한 시각(UTC 13:5x), 한 회선. 시간대별 변동(3.8-flash 503 이 그 증거)은 안 잰 것.
- 페이로드 1종 — 세션이 많은 주(rep 곡선 길어짐)의 프롬프트 길이 변화는 미측정. 578 tok 이라 길어져도 자릿수가 안 바뀔 것으로 보나 실측 아님.
- 무료 티어 큐 우선순위가 유료와 다를 수 있음 — 유료 전환 시 재측정.
