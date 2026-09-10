# 포폴: 닫힌 카드 재고 — `docs/decisions/` 120개에서 추린 13장

작성: 2026-09-10
대상: 백엔드(Spring) 신입 + DBA 신입 병행 지원. **면접에서 3분 안에 말할 수 있는 카드가 몇 장인지**를 세는 문서.
방법: `docs/decisions/*.md` 120개 전수 신호 스캔(측정 언급·결정 섹션·이슈 참조) → 상위 22개 정독 → **코드가 `main`에 실제로 있는지 대조**.
연관: [`problem-solving-log.md`](./problem-solving-log.md)(2026-08-07에서 멈춤, §6 참고) · [`one-pager.md`](./one-pager.md)(수치 정본) · [`db-deep-dive.md`](./db-deep-dive.md) · [`failure-modes.md`](./failure-modes.md)

> 이 문서는 **카드 목록**이지 결정 문서가 아니다. 어느 카드를 실제로 쓸지는 사용자가 정한다.
> 각 카드의 수치를 자소서·경력기술서로 옮길 때는 **조건을 떼지 말 것** — 원본 문서 링크를 그대로 따라갈 것.

---

## 0. 정직성 전제 (2026-09-10 실측으로 갱신)

커밋 저자 집계 (`git log --format=%an`, 본인 = `Khyojae` + `권효재`):

| 경로 | 본인 | 팀원 |
|---|---|---|
| `backend/src/main` | 224 | demetergod 4 · hojin.jeoung 2 |
| `backend/src/main/resources/db/migration` | 15 | 0 |
| `ai-server/app` | 63 | hojin.jeoung 4 · demetergod 1 |

🔴 **`problem-solving-log.md` §0의 "ai-server 커밋은 본인/팀원 경계 모호"는 절반만 맞다.** 실측하면 경계가 시간으로 갈린다.

- **AI 서버 골격은 팀원 것** — MediaPipe+DTW 마이크로서비스 분리(demetergod, 2026-03-30), 스쿼트 분석 로직·실시간 데모(hojin.jeoung, 2026-04-09), gRPC 연동 플로우(hojin.jeoung, 2026-04-28).
- **그 이후 성능·구조 작업은 본인** — `memory_ceiling`(2026-08-13), `orjson`·`pybase64` 스왑(2026-09-03) 전부 본인 도입 커밋.

⚠️ 따라서 AI 서버 카드(A-2·A-5·A-6)는 **"내가 만든 것"이 아니라 "팀원이 만든 것을 측정해서 원인을 찾고 고친 것"**으로 말해야 한다. 이 프레임이 오히려 강하다 — 남의 코드에서 천장의 원인을 갈라낸 이야기가 되기 때문이다. 반대로 "AI 서버를 만들었다"고 말하면 커밋으로 반증된다.

---

## 1. 한 장 요약

| # | 카드 | 축 | 코드 변경 | 상태 |
|---|---|---|---|---|
| A-1 | 아웃박스 — 세션 종료 통보 유실 | Spring | 있음 | 구현·측정 완료 (2026-07-29) |
| A-2 | AI 프로세스당 천장의 원인 | AI/성능 | 있음 | 원인 규명 완료 (2026-08-23~) |
| A-3 | `pose_data` 멱등 + 데드락 재시도 | DB | 있음 | 구현·검증 완료 (2026-08-17~20) |
| A-4 | 세션 인덱스 — 떼기가 아니라 합치기 | DB | 있음 | 채택 확정 (2026-08-07) |
| A-5 | 검출기 풀 상한 공식 | AI/운영 | 있음 | 반영 완료 (2026-09-02) |
| A-6 | 글루 코드 직렬화 (orjson·pybase64) | AI/성능 | 있음 | 17판 재확인 (2026-09-02~03) |
| B-1 | 내구성 완화 역전 — 재현 안 됨 | DB | 없음 | 완료 (2026-08-27) |
| B-2 | 파티션 구멍 유의성 — n 늘려도 p>0.05 | DB | 없음 | 완료 (2026-09-06) |
| B-3 | 그룹 WS 풀 pending — 1판 잡음이었다 | Spring | 없음 | 완료 (2026-09-02) |
| B-4 | 프레임 경로 백프레셔 — 전부 현행 유지 | AI/설계 | **없음(의도)** | 결정 완료 |
| C-1 | 백업·복구 RTO/RPO | DBA | 없음 | EC2 측정 완료 (2026-08-13~14) |
| C-2 | 복제 지연과 semisync | DBA | 없음 | 2대 라운드 완료 (2026-08-22) |
| C-3 | 무중단 스키마 변경 | DBA | 없음 | EC2 측정 완료 (2026-08-12) |

**C-1~C-3이 DBA 축 결손 3개(백업·복제·무중단DDL)와 정확히 맞는다** — 코드 라인으로는 안 보이지만 DBA 지원에서는 A급 코드 카드보다 더 직접적인 소재다.

---

## 2. A급 — 4단 완결 (문제 → 원인 → 코드 → 검증)

네 칸이 전부 있고, **코드가 `main`에 있는 것까지 대조한** 카드만 여기 넣는다.

### A-1. 아웃박스 — 세션 종료 통보 유실

- **문제**: 세션 종료 통보가 dual-write라 3회 실패 시 유실 → 분석 결과가 영영 회수되지 않는다.
- **원인**: 실코드 재대조(2026-07-29)로 피해 기술을 정정하고, 서킷 스킵 경로가 따로 있다는 것을 발견.
- **코드**: `backend/src/main/java/com/shadowfit/model/outbox/*`, `service/exercise/OutboxPublisher.java` (PR #60·#63·#67)
- **검증**: "통보 유실 0"이 주장이 아니라 실측. ⚠️ 단 **네트워크 단절·서킷 OPEN 시나리오에 한하며, AI 프로세스 재시작 시엔 통보가 전달돼도 분석 결과는 유실된다.**
- 출처: [`../decisions/outbox-reliable-messaging.md`](../decisions/outbox-reliable-messaging.md)

### A-2. AI 프로세스당 천장의 원인

- **문제**: 워커를 늘려도 프로세스당 처리량 천장이 안 뚫린다. 원인이 GIL인지 이벤트 루프인지 앱 후처리인지 갈리지 않았다.
- **원인**: 요청 시간의 **44.7%가 단일 이벤트 루프**에서 소모(`wait` 22.0% + `post_loop` 22.7%). 앱 후처리는 **1.1%** — 즉 앱을 최적화해봐야 소용없다는 것이 먼저 판명됐다.
- **코드**: `AI_WORKER_COUNT`(기본 3)로 프로세스 분리, `entrypoint.sh` 워커별 포트, `ai-nginx` 고정 라우팅.
- **검증**: 2단계까지 실행, GIL 갈래까지 닫힘(2026-08-24 EC2).
- **말하는 법**: "스레드가 아니라 프로세스를 늘린 이유"가 이 카드의 한 줄이다. 남의 코드에서 원인을 갈라낸 이야기(§0).
- 출처: [`../decisions/ai-process-ceiling-cause.md`](../decisions/ai-process-ceiling-cause.md)

### A-3. `pose_data` 멱등 + 데드락 재시도 ★

- **문제**: AI→Spring 세 콜백 중 `SavePoseDataBatch`만 재전송도 수신측 멱등도 없다. **지금 중복이 안 나는 이유는 방어가 아니라 부재** — 재시도를 붙이는 순간 중복이 생기고, 둘은 따로 고칠 수 없다.
- **원인**: 분기 6개(A~F)로 쪼갬 — 이벤트 시각을 어디서 얻나 / 중복을 무엇으로 흡수하나 / 유니크 키의 값 / 기존 위반 행 / 부하 rig / 재시도 값.
- **코드**: `V6__add_pose_data_idempotency_key.sql`, `V9__normalize_pose_created_at.sql`, `model/exercise/PoseData.java`, `service/exercise/PoseDataService.java`
- **검증**: §9 구현 검증(2026-08-17) → §10 **고친 것이 새 실패(데드락)를 만들었고, 재시도 값도 실측으로 정했다**(2026-08-20).
- **말하는 법**: 이 저장소에서 가장 센 3분 카드. "파티셔닝을 얻은 대가로 유니크 키에 `created_at`이 낀다"는 제약까지 마이그레이션 주석에 남아 있다. `problem-solving-log.md` #3(INSERT IGNORE 멱등)을 **대체**한다 — #219가 그 함정을 잡았다.
- 출처: [`../decisions/pose-batch-idempotency-implementation.md`](../decisions/pose-batch-idempotency-implementation.md)

### A-4. 세션 인덱스 — 떼기가 아니라 합치기

- **문제**: `member_id` 선두 인덱스 3종이 겹친다. 6번째를 얹기 전에 재검토해야 한다 (이슈 #110).
- **원인**: 실측 결과 `(member_id, status)`가 **일하는 척만** 하고 있었다 — `ORDER BY start_time LIMIT 1`에서 정렬을 못 받쳐 옵티마이저가 다른 인덱스로 도망간다.
- **코드**: `V1__baseline.sql`의 통합 인덱스 + **왜 합쳤는지가 주석으로 남아 있다.**
- **검증**: 측정 장치 `loadtest/measure_index_overlap.sh` (PR #112).
- **말하는 법**: 질문("떼도 되나")과 답("떼면 안 되고 합쳐야 한다")이 어긋난 카드. 겹침 해소가 아니라 **새 이득**이 나왔다는 게 핵심.
- 출처: [`../decisions/session-index-composition.md`](../decisions/session-index-composition.md)

### A-5. 검출기 풀 상한 공식

- **문제**: 실측은 나왔는데 **코드가 안 따라갔다.**
- **원인**: 「공식 212」가 코드가 실제로 내는 값이 아니라는 것을 먼저 정정.
- **코드**: `ai-server/app/core/mediapipe_detector.py`의 `memory_ceiling()`, `app/config.py` — 검출기 1개 ≈ 98.7MB(실측)에서 `(mem_limit − 기본 RSS) / 98.7MB`로 유도. **근거가 없으면 기동을 거부한다.**
- **검증**: §9 반영 확인 (2026-09-02).
- **말하는 법**: "근거 없는 숫자를 코드에 안 박는다"가 원칙으로 코드에 들어간 사례.
- 출처: [`../decisions/detector-pool-ceiling-formula.md`](../decisions/detector-pool-ceiling-formula.md)

### A-6. 글루 코드 직렬화 (orjson · pybase64)

- **문제**: JSON 직렬화·base64 디코드가 프레임 경로 비용에 얼마나 무는가.
- **원인**: N=1(박스 61%)과 N=3(박스 96% 포화, 실배포 조건)에서 **개선 폭이 완전히 다르다**는 것을 분리.
- **코드**: `orjson` 3파일(`api/endpoints/pose.py`·`core/reference_store.py`·`grpc/exercise_servicer.py`), `pybase64`(`utils/image_utils.py`) — **둘 다 `main` 반영 확인.**
- **검증**: 17판(8판씩 재확인), Welch t=13.24 / −11.29.
- **말하는 법**: ⚠️ **"8% 빨라진다"는 N=1 한정 문장이다.** 실배포 체감은 "작지만 확실하게 1~3%"가 정직하다. 이 축소 자체가 카드의 일부 — 유리한 수치를 조건 없이 인용하지 않았다는 증거로 쓴다.
- 출처: [`../decisions/ai-glue-code-orjson.md`](../decisions/ai-glue-code-orjson.md) · [`../decisions/pose-frame-base64-cost.md`](../decisions/pose-frame-base64-cost.md)

---

## 3. B급 ① — 음성 결과 (재현 실패를 실패로 박제)

코드는 안 바뀌었지만 **"틀린 것을 스스로 뒤집은 기록"**이라 희소하다. 면접에서 "측정 결과가 기대와 달랐던 적이 있나"에 그대로 답이 된다.

| 카드 | 기대 | 실제 |
|---|---|---|
| **B-1 내구성 완화 역전** | 완화했는데 더 느리다 → 워크로드 특이성? | AWS 재현에서 **역전 자체가 재현 안 됨**. 원인은 「R4가 1판짜리 잡음」 |
| **B-2 파티션 구멍 유의성** | n을 늘리면 p<0.05로 갈 것 | 13→35블록으로 늘렸더니 p=0.092 → **0.229로 더 멀어짐.** 방향 쏠림도 76.9%→61.8% |
| **B-3 그룹 WS 풀 pending** | 300연결에서 pending=15 → 풀 경계 발견? | **반복 없는 1판 노이즈.** 3판 재현 시 항상 0. 개선안 3개 전부 근거 없음으로 폐기 |
| **B-4 프레임 경로 백프레셔** | 재시도·순서보장·유실감지가 없으니 메워야 | 갈림길 4개 전부 **"현행 유지"**로 확정. 문서가 코드 변경 없음을 자인 |

> B-1·B-2·B-3은 전부 같은 함정의 사례다 — **팔당 1판이면 「팔」과 「판 순서」가 분리되지 않는다.** 세 번 다 걸렸고 세 번 다 반복 측정(버림판 + 라틴 방격)으로 뒤집었다. 이 셋을 묶어서 하나의 방법론 카드로 말하는 편이 낫다.

출처: [`../decisions/durability-relaxation-inversion.md`](../decisions/durability-relaxation-inversion.md) · [`../decisions/partition-hole-drop-significance-retest.md`](../decisions/partition-hole-drop-significance-retest.md) · [`../decisions/group-websocket-capacity-deep-dive.md`](../decisions/group-websocket-capacity-deep-dive.md) · [`../decisions/ai-frame-path-backpressure.md`](../decisions/ai-frame-path-backpressure.md)

---

## 4. B급 ② — 운영 측정 (DBA 축)

| 카드 | 측정한 것 | 무대 |
|---|---|---|
| **C-1 백업·복구** | RTO 약 21분(팔 A) · 논리↔물리 **약 7배** · PITR 확인 | EC2 (2026-08-13~14) |
| **C-2 복제·semisync** | 2대 라운드로 Q1·Q2에 값이 붙음 | EC2 (2026-08-22) |
| **C-3 무중단 스키마 변경** | 96분 차단 ALTER를 도구로 재실행, 질문 5개 중 4개에 답 | EC2 (2026-08-12) |

C-1은 본 측정이 남긴 결함 2건(#201·#202)이 #210 교정까지 닫혔다 — **결함을 발견하고 교정한 경위 자체**가 카드에 포함된다.

출처: [`../decisions/backup-restore-rto-rpo.md`](../decisions/backup-restore-rto-rpo.md) · [`../decisions/replication-lag-and-semisync.md`](../decisions/replication-lag-and-semisync.md) · [`../decisions/online-ddl-vs-blocking-alter.md`](../decisions/online-ddl-vs-blocking-alter.md)

---

## 5. 이 목록의 한계 (정직하게 비어 있는 것)

- **전수가 아니다.** 120개 중 신호 상위 22개만 정독했다. 하위 98개에 놓친 완결 카드가 있을 수 있다.
- **"3분에 말해진다"는 미검증이다.** 이 목록은 문서와 코드를 대조해서 만든 것이지, 실제로 말해본 결과가 아니다.
- **열린 축은 제외했다** — 풀 사이징 10~20(9라운드에서 원인이 「App 배포 방식」으로 좁혀지는 중, #708), 주간/월간 집계 선집계(2026-09-09 신설), 소급 귀속(2026-09-10 신설).
- **카드당 소요 시간은 미측정이다.** "카드 하나가 2주"라는 식의 환산은 근거가 없으므로 쓰지 않는다.

---

## 6. `problem-solving-log.md` 와의 관계

그 문서는 **2026-08-07에서 갱신이 멈췄다**(카드 #1~#11). 이 문서의 A-3·A-5·A-6과 B급 7장은 전부 그 이후에 닫힌 것이다. 겹치는 지점 하나:

- `problem-solving-log.md` **#3 「at-least-once gRPC 콜백 → 멱등성 (INSERT IGNORE)」** 는 이 문서 **A-3이 대체**한다. `INSERT IGNORE` 함정은 이슈 #219로 잡혔고, 실제 채택은 유니크 키 + 데드락 재시도다. **#3을 지금 형태로 면접에서 말하면 안 된다.**

두 문서를 합칠지, 이 문서를 8월 이후 전용으로 둘지는 정하지 않았다.

---

## 7. 관련

- [`one-pager.md`](./one-pager.md) — 수치 정본. 이 문서의 수치를 회사에 내보낼 때는 그쪽 조건 표기를 따른다.
- [`../decisions/experiment-inventory.md`](../decisions/experiment-inventory.md) — 실험 재고(측정 관점). 이 문서는 **포폴 카드 관점**으로 같은 대상을 본다.
- [`failure-modes.md`](./failure-modes.md) — 실패 모드 카탈로그. A-1이 E1에 대응.
