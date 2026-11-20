# 프로덕션 클라이언트 지연 A/B — gRPC(grpc-java) vs WebClient(Reactor Netty)

설계: [`docs/decisions/grpc-webclient-production-client-round.md`](../../../docs/decisions/grpc-webclient-production-client-round.md)
선행: [`grpc-webclient-empirical-comparison.md` §10](../../../docs/decisions/grpc-webclient-empirical-comparison.md) (1차 — 도구가 AI 를 직접 친 판)
실행: 2026-09-10 · `RUN_ID=ec2-20260910-134357` · 본 측정 767초

---

## 0. 이 라운드가 답한 것

1차 라운드는 **ghz(Go)·k6** 로 AI 를 직접 쳤고, 84KB 페이로드에서 **도구를 바꾸니 델타 부호가
뒤집혔다**(§10.2). 즉 그 델타를 지배한 것은 서버가 아니라 **클라이언트의 인코딩 비용**이었고,
정작 두 도구 모두 이 서비스가 안 쓰는 구현이다. 이 라운드는 그 자리를 **실제로 쓰는 클라이언트**
(`GrpcAiAnalysisClient` / `WebClientAiAnalysisClient`)로 다시 쟀다.

| RPC | 페이로드 | grpc 평균 범위(블록별) | webclient 평균 범위 | 판정 |
|---|---|--:|--:|---|
| **ReattachAnalysis** | **큼** — 기준좌표 37프레임 **97.6KB**(로컬 DB 실측, JSON 원자료 기준) | **5.779 ~ 7.592 ms** | **8.629 ~ 9.094 ms** | ✅ 범위 안 겹침 — **gRPC 가 빠르다** |
| **StopAnalysis** | 작음 — `session_id` 하나 | **1.814 ~ 2.689 ms** | **3.847 ~ 4.110 ms** | ✅ 범위 안 겹침 — **gRPC 가 빠르다** |
| StartAnalysis (fire-and-forget, 참고) | 큼 | 6.693 ~ 11.601 ms | 10.382 ~ 10.706 ms | 🟡 **판별 불가**(겹침 — §3 블록4 이상치) |

판정 규칙은 실행 전에 못박은 것을 그대로 썼다 — [`load-test-strategy.md §2-1`](../../../docs/decisions/load-test-strategy.md):
**교차 반복에서 얻은 두 분포가 겹치지 않을 때만** 「효과」라고 부른다. 배수 기준은 안 쓴다.

**크기 감각**: 큰 요청에서 차이는 **약 +3ms**, 작은 요청에서 **약 +1.8ms**(webclient 가 더 느린 쪽).
이 두 RPC 는 **세션당 몇 번**이라 사용자 체감 시간에서 차지하는 몫은 작다 — 「어느 쪽이 빠른가」와
「그래서 바꿔야 하는가」는 다른 질문이고, 후자는 이 라운드가 답하지 않는다.

---

## 1. 무대

| | |
|---|---|
| 인스턴스 | `c7i.2xlarge` 1대 (ap-northeast-2) · gp3 100GB · `ROLE=client-ab` |
| 사는 것 | mysql + backend + ai(워커 3) + ai-nginx **전부 한 박스** |
| 부하기 | **없음** — 재는 구간(Spring→AI)이 박스 안에서 닫히므로 드라이버는 트리거일 뿐이다 |
| 코드 | `feat/grpc-webclient-ab` @ `83f7c098` — 🔴 **main(`6130b2d1`) 로 rebase 하지 않았다** |
| 계기 | `shadowfit.ai.call` 타이머(`TimedAiAnalysisClient`), 태그 `protocol`·`rpc`·`outcome` |

**팔 전환은 백엔드 재기동**이다(`ai.client-type` 이 스타트업 프로퍼티라). 그래서 매 전환마다
컨테이너를 다시 세우고, `printenv` 로 팔이 실제로 바뀌었는지 **게이트**를 통과해야 다음으로 간다.

**설계 그대로 지킨 것**: 버림 블록(팔당 1) · 팔 순서 반전(홀수 블록 grpc 먼저, 짝수 webclient
먼저) · 블록마다 워밍업 10사이클 · 블록 경계에서 **stop 배수 대기**(stop 은 아웃박스 발행기가
비동기로 보내므로, 안 기다리면 다음 팔의 백엔드가 그것을 보내 팔 귀속이 무너진다).

---

## 2. 결과 (블록별)

전문: [`clientab/summary.txt`](clientab/summary.txt) · 원자료: [`clientab/scrape/`](clientab/scrape/)(블록 전/후 20개) · 실행 로그: [`clientab/run.log`](clientab/run.log)

집계는 **버킷 차분**이다 — 타이머가 누적이라 블록 시작/끝 스크레이프를 빼야 그 블록의 분포가 나온다.

### ReattachAnalysis (큰 요청)

| 팔 | b1 | b2 | b3 | b4 | b5 |
|---|--:|--:|--:|--:|--:|
| grpc 평균(ms) | 5.880 | 5.779 | 5.836 | *7.592* | 5.882 |
| webclient 평균(ms) | 8.629 | 9.094 | 8.919 | 8.827 | 8.777 |

p50 도 같은 방향이다(grpc 5.09~5.19 · webclient 7.94~8.11). **블록4 grpc 는 §3 참고.**

### StopAnalysis (작은 요청)

| 팔 | b1 | b2 | b3 | b4 | b5 |
|---|--:|--:|--:|--:|--:|
| grpc 평균(ms) | 2.689 | 2.133 | 2.239 | 1.814 | 2.386 |
| webclient 평균(ms) | 4.069 | 3.847 | 3.948 | 4.008 | 4.110 |

---

## 3. 🔴 블록4 grpc 는 이상치다 — 그대로 인용하면 안 된다

집계기가 스스로 뱉은 경고: `블록 4/grpc reattach outcome=failure 6건` · `start outcome=failure 9건`.
그 블록의 reattach 성공 표본은 **100이 아니라 10**이다(나머지는 아예 기록이 없다).

**가설(미확정)**: 실패가 누적돼 **서킷브레이커가 열렸고**, OPEN 인 동안 호출부가 AI 를 아예 안
부른다(`ExerciseAnalysisService` 가 `tryAcquirePermission()` 실패 시 즉시 반환·예외) — 타이머는
클라이언트를 감싸므로 **안 부른 호출은 기록되지 않는다.** 그래서 표본이 사라진 모양이 된다.
🔴 이건 로그로 확인하지 않았다 — 박스는 이미 terminate 됐다. **다음 라운드에서 확인할 것.**

**판정에 미치는 영향**: reattach 는 블록4 를 빼도 결론이 안 바뀐다(grpc 5.779~5.882 ↔ webclient
8.629~9.094, 여전히 안 겹침). **start 는 이 이상치 때문에 겹쳐서 판별 불가**가 됐다 — 즉 start
결과는 「차이가 없다」가 아니라 **「이 판으로는 못 가른다」**다.

---

## 4. 이 라운드가 답하지 않는 것

- **배포 구성의 절대값**. mysql·backend·ai 가 한 박스에 동거하는 무대의 값이다.
- **동시성 하의 거동**. 닫힌 루프 c=1 이다. WebClient 이벤트루프와 gRPC 채널 풀이 동시 호출에서
  다르게 굽는지는 별개 질문이다.
- **차이의 내부 분해**. 왕복 총합만 봤다 — 직렬화 몫과 네트워크/nginx 홉 몫을 가르지 않았다
  (1차 라운드가 nginx 홉을 0.1~0.8ms 로 따로 쟀다는 것만 옆에 둘 수 있다).
- **채택 여부**. 수치를 만들 뿐이고 어느 프로토콜을 쓸지는 사용자 결정이다.

---

## 5. 從 — 같이 회수한 것

| 항목 | 결과 |
|---|---|
| **R1 worst-section** | ✅ **처음으로 «앱 트래픽이 도는 무대» 에서 걷혔다** — `reports` **163행** · `exercise_sessions` **1,120행**. 🔴 단 `detailed_analysis` **0** · `pose_data` **0** 이다: 이 라운드의 사이클은 프레임을 안 보내므로(시작→재부착→종료) 채워질 재료가 없다. **원 질문(«EC2 배포분에 detailed_analysis 채워진 행이 있는가»)은 여전히 미답** — 프레임을 태우는 무대가 필요하다 |
| **R3 3-way 조인** | ✅ **처음 실행됨** — [`ridealong/R3_hash_join.txt`](ridealong/R3_hash_join.txt). 인덱스 없는 조건에서 옵티마이저가 **hash join(cost 4241)** 을 고르고, 끄면 **nested loop(cost 4520)** 다 — 「고르는 이유」가 cost 차이로 보인다. ⚠️ 시딩이 단일 템플릿이라 **플랜 모양만** 읽는다 |
| **R11 박스 보정값** | 🟡 **명령은 돌았지만 표가 비었다** — `aws/README.md` 가 「아직 안 돌려봤다」고 적어둔 도커 판 한 줄을 여기서 처음 밟았다. 컨테이너 안에서 스크립트는 실행됐고(MediaPipe 초기화 로그까지 나옴) 헤더도 찍혔는데 **데이터 행이 0**이다([`clientab/calibration_scaling.txt`](clientab/calibration_scaling.txt)). 그 줄은 **여전히 미완성**이다 |

---

## 6. 이 라운드를 세 번 헛돌린 기록 (다음 사람용)

본 측정 전에 EC2 를 **네 번** 띄웠고 앞의 셋은 측정 없이 끝났다. 원인은 전부 **「로컬에서 안 밟아본
코드를 EC2 에서 처음 밟은 것」**이다.

| 판 | 죽은 자리 | 원인 |
|---|---|---|
| A | 부트스트랩 35초 | `Duration.ofMicros` — 없는 메서드. 전체 테스트를 돌린 **뒤에** 그 줄을 얹고 컴파일 검증 없이 푸시 |
| B | (측정 전 중단) | rig 이 stop 배수를 고정 10초만 기다림 — 팔 귀속이 깨질 수 있어 내가 내림 |
| C/D | 단계 자체가 안 돎 | `run_all.sh` 디스패치 `case` 에 `clientab` 미등록 → 「알 수 없는 단계 — 건너뛴다」로 **조용히** 통과 |

그 뒤 로컬에서 rig 을 N=2·블록1 로 한 판 태웠고, 그 한 판이 결함 넷을 더 잡았다(위 C/D 포함,
팔 전환 `docker compose` rc 미검사 → **조용히 오염된 데이터**, 계정 username 고정 → 재실행 전멸,
분석기가 1블록에서도 「효과 있음」 판정). **로컬 스모크 한 판이 EC2 네 판보다 쌌다.**
