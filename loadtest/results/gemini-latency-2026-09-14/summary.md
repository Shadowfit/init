# Gemini 응답시간 실측 — 2026-09-14

페이로드: 주간 집계(A층 2주 + rep 곡선 8점 + worst 3) ≈ 1228 bytes. 라운드 11(첫 라운드 버림), 모델 시작 순서 회전, 호출 간격 2.0s, temperature 0.2, JSON 스키마 강제.

| 모델 | n | 성공 | p50 | p95 | max | 출력 tok 중앙 | thinking tok 중앙 | JSON ok | 인용 숫자 불일치 건 | 한국어만 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| gemini-3.5-flash-lite | 10 | 10 | 1.506 | 1.659 | 1.66 | 161.0 | - | 10/10 | 0 | 10/10 |
| gemini-3.5-flash | 10 | 10 | 9.061 | 26.2 | 39.149 | 289.0 | 2021.5 | 10/10 | 0 | 10/10 |
| gemini-3.8-flash | 10 | 0 | - | - | - | - | - | - | - | - |

오류 11건: r0 gemini-3.8-flash 503, r1 gemini-3.8-flash 503, r2 gemini-3.8-flash 503, r3 gemini-3.8-flash 503, r4 gemini-3.8-flash 503, r5 gemini-3.8-flash 503, r6 gemini-3.8-flash 503, r7 gemini-3.8-flash 503, r8 gemini-3.8-flash 503, r9 gemini-3.8-flash 503, r10 gemini-3.8-flash 503

## 샘플 출력 (모델별 마지막 성공 1건)

- **gemini-3.5-flash-lite**: 지난주보다 운동 횟수가 3회에서 4회로 늘었고 총 rep 수도 41회에서 58회로 늘었습니다. 반면 rep 가중 싱크로율은 74.8퍼센트에서 71.4퍼센트로 내려갔습니다. rep 곡선에서는 rep 4 이후 싱크로율이 내려가는 패턴을 보입니다.  
  cited_unknown=[]
- **gemini-3.5-flash**: 지난주 대비 운동 세션은 3회에서 4회로 늘었고, 총 횟수는 41회에서 58회로 증가했습니다. 반면 rep 가중 싱크로율은 74.8%에서 71.4%로 감소했습니다. rep 곡선에서는 repNumber가 1에서 8로 증가할 때 평균 싱크로율이 78.2%에서 63.9%로 감소하는 패턴을 보입니다.  
  cited_unknown=[]
