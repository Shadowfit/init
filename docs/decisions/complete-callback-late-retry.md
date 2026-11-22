# CompleteAnalysis 콜백 유실 — «느린 재시도» (#799)

작성일: 2026-11-22
상태: ✅ **구현됨** (사용자 결정 — 추천안 C2: 재시도 정책 확장 + 유실 지표)
연관: 이슈 #799 · [`ai-backend-integration.md`](../architecture/ai-backend-integration.md) · 안건 문서 «AI 서버 합의 안건» 안건 C

## 문제

AI → Spring 완료 통보(`CompleteAnalysis`)는 **세션당 한 번뿐**이고, 실패하면 운동 결과(rep·점수)가 사라진다. Spring → AI 방향(종료 통보)은 아웃박스로 보장되지만 이 방향은 아니었다. 즉시 재시도는 3회(최대 약 19초)였고 다 쓰면 ERROR 로그만 남기고 포기했다. Spring 이 배포·재기동 중이면(앱 기동 12~18초 실측) 그 안에 못 돌아올 수 있다.

## 결정

1. **즉시 재시도(3회·약 19초)는 그대로 두고, 다 쓰면 포기하는 대신 «느린 재시도» 로 넘긴다.** `COMPLETE_RETRY_WINDOW_SEC`(기본 600초) 동안 `COMPLETE_RETRY_INTERVAL_SEC`(기본 30초) 간격으로 같은 요청을 다시 보낸다. 창이 0 이면 느린 재시도를 끈다(예전 동작).
2. **타이머로 이어 가고 풀 워커를 붙잡지 않는다.** 콜백 풀은 워커 수가 고정이라, 워커가 몇 분 sleep 하면 Spring 이 내려가 있는 동안 **다른 세션의 콜백까지** 큐에서 기다린다. 즉시 재시도가 끝나면 호출을 반환하고, 이후 시도는 `threading.Timer` 가 단일 시도로 한다(시도마다 자체 데드라인).
3. **창 600초는 Spring 유휴 타임아웃(`exercise.session.timeout.idle-minutes` = 10분)의 복사본이다.** 그 안에 성공하면 사용자는 세션이 FAILED 로 걷히는 것을 보지 않는다. 넘어서도 되살릴 수는 있다 — `SessionService.completeSession` 은 FAILED 로 걷힌 세션도 COMPLETED 로 덮어쓰게 만들어져 있다(낙관적 락 충돌 처리) — 하지만 그 동안 화면은 FAILED 이고, 결과를 메모리에 쥐는 비용도 있어 이 선에서 멈춘다. Spring 설정이 바뀌면 같이 바꿀 것. 간격 30초는 Spring 기동(12~18초)을 한 번은 넘기는 길이이고, 그 이상의 근거는 없다.
4. **중복은 Spring 의 멱등 처리가 흡수한다.** 같은 요청·같은 metadata(correlation id)를 다시 보내고, `applyComplete` 는 이미 COMPLETED 면 아무것도 바꾸지 않는다.

## 지표

`shadowfit_ai_spring_callback_total{rpc="CompleteAnalysis", outcome}`:

| outcome | 뜻 |
|---|---|
| `ok` | 즉시 재시도 안에 성공 |
| `retried` | 한 번 실패하고 다시 시도하는 중(즉시·느린 재시도 모두) |
| `deferred` | 즉시 재시도를 다 쓰고 느린 재시도로 넘어감 — **아직 유실이 아니다** |
| `recovered_late` | 느린 재시도로 성공 — 이 건이 이 변경이 살린 결과다 |
| `rejected` | Spring 이 영구 실패로 답함(NOT_FOUND 등) — 재시도하지 않음 |
| `exhausted` | **창까지 다 써서 유실** — 이전에는 즉시 재시도 3회 뒤에 세던 값이다(의미가 «진짜 유실» 로 좁아졌다) |

게이지 `shadowfit_ai_complete_callback_late_pending` = 지금 느린 재시도를 기다리는 결과 수. 0 이 아닌 채로 오래가면 Spring 이 계속 안 받는 것이다.

## 이 결정이 하지 않는 것

- **AI 프로세스가 죽거나 재시작하면 대기 중인 결과는 사라진다**(메모리). 그 한계를 넘으려면 디스크 같은 영구 큐(AI 쪽 아웃박스, 후보 C3)가 필요하다 — 하지 않았다.
- 창 안에 Spring 이 돌아오지 않으면 여전히 유실된다(`exhausted`). 얼마나 자주 일어나는지는 **운영 지표 없이는 모른다** — `exhausted` 와 `recovered_late` 가 그 근거가 된다. 이 숫자를 본 뒤에 C3·C4(Spring 이 결과를 끌어오기)를 할지 정한다.
- `COMPLETE_RETRY_*` 를 운영에서 바꾸려면 compose 가 컨테이너에 넘겨야 한다 — 지금 `docker-compose*.yml` 은 이 값들을 넘기지 않아 `.env` 값이 무시되고 기본값이 쓰인다(세션 정리 설정 `SESSION_IDLE_TTL_SEC` 등과 같은 상태).
- 실제 Spring 을 내렸다 올리는 시험은 하지 않았다(스텁으로 «언제 몇 번 던지는가» 만 확인).
