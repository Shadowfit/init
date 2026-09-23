# outbox_events 보존 정리 — 무엇을, 어떻게, 얼마나 자주 지우나

작성일: 2026-09-23
상태: **분기점 — 결정 전.** 후보와 트레이드오프, 측정 설계까지만 적었다. 채택은 사용자 confirm 뒤 §8 에 박제한다.
연관: [#793](https://github.com/Shadowfit/init/issues/793) · [`./outbox-reliable-messaging.md`](./outbox-reliable-messaging.md) §4-1-2 · [ADR 0001](../adr/0001-use-transactional-outbox-for-session-end-notification.md) · [`loadtest/results/delete-fragmentation-2026-08-09/`](../../loadtest/results/delete-fragmentation-2026-08-09/README.md)

---

## 0. 한 줄 요약

07-29 에 «주기 DELETE, SENT 는 짧게·FAILED 는 길게» 로 정해 놓고 **정리를 부르는 코드를 안 만들었다**(#793). 이 문서가 새로 정할 것은 «지울까 말까» 가 아니라, 07-29 결정이 비워 둔 다섯 칸이다. 보존 기간 값, 지우는 쿼리의 모양, 인덱스, 다중 인스턴스, 관측. 그리고 그 결정이 못 본 위험 하나를 더한다. **정리 DELETE 가 발행기의 상태 전이(`markSent`)와 락으로 부딪칠 수 있다**(§3, 추정·미측정).

---

## 1. 현재 사실 (main 731f2274)

| 항목 | 사실 | 근거 |
|---|---|---|
| 정리 메서드 | `deleteByStatusAndCreatedAtBefore(status, threshold)` — JPQL 벌크 DELETE, `LIMIT` 없음 | `OutboxEventRepository` |
| 호출처 | main 0 · test 0 | #793 |
| 상태 | `ENUM('PENDING','PROCESSING','SENT','FAILED')` | `V1__baseline.sql` outbox_events |
| PK | `id BIGINT AUTO_INCREMENT` | 같음 |
| 보조 인덱스 | `idx_outbox_dispatch (status, next_retry_at)` · `idx_outbox_report_dispatch (event_type, status, next_retry_at)` | V1, V21 |
| `created_at` 인덱스 | 없음 | 같음 |
| 발행기 선점 | `SELECT … WHERE status='PENDING' … ORDER BY id LIMIT n FOR UPDATE SKIP LOCKED`, 회수분은 `status='PROCESSING' AND lock_expires_at <= now` | `lockPendingBatch`, `lockStaleProcessingBatch` |
| SENT 전이 | `UPDATE … SET status=SENT … WHERE id=? AND locked_by=? AND status=PROCESSING` — `next_retry_at` 은 그대로 둔다 | `markSent` |
| 격리 수준 | 설정 없음 → MySQL 기본 REPEATABLE READ | `application.yml` 에 isolation 항목 없음 |
| SENT 행을 다시 읽는 코드 | **없다.** 중복 적재 가드(`existsByAggregateIdAndEventTypeAndStatusIn`)는 PENDING·PROCESSING 만 본다 | `ExerciseAnalysisService.enqueueReattachForWorker` |
| FAILED 관측 | 카운터(`shadowfit.outbox.dispatch{outcome}`)라 행을 지워도 지표는 안 바뀐다. 게이지는 PENDING 만(`countByStatus(PENDING)`) | `SessionMetrics`, `OutboxPublisher` |
| 스케줄러 중복 실행 방지 | 없음(ShedLock 미도입). 지금은 단일 인스턴스 | `outbox-reliable-messaging.md` §4-2 ⑦ |
| 운영 DB | 없다. 배포 호스트가 없어 «첫 실행 때 쌓인 양» 은 로컬·측정 rig 에만 있다 | `USE-CASES.md` §5 OP-02 |

**지워도 기능이 안 깨진다는 점은 확인됐다.** 남은 질문은 전부 «어떻게 지우나» 다.

### 1-1. 얼마나 쌓이나 (가정에서 유도)

- 가정: DAU 1,000 × 1.5세션/일 = 1,500세션/일(`ai-sticky-routing.md` 의 정본 가정).
- 세션당 행: `STOP_ANALYSIS` 1 + `SESSION_COMPLETED` 최대 1(모임 소속일 때) → 최대 3,000행/일. 여기에 알림 푸시(기기 등록 회원의 재촉·응원)와 주간 리포트(회원·주당 최대 1)가 더해진다. 둘은 사용 패턴 가정이 없어 **세지 않았다**.
- 행 크기: 약 108 B/행(파편화 실측 때 쓴 이 표의 행 모양, 인덱스 제외).
- 그러면 정리가 없을 때 1년 ≈ 110만 행 ≈ 120 MB(데이터만).

**크기 자체는 급하지 않다.** 이 문서가 크기를 착수 근거로 쓰지 않는 이유다. 착수 근거는 둘이다. (1) 07-29 결정과 ADR 0001 이 «돈다» 고 적은 것이 실제로는 안 돈다. (2) 정리를 켜는 순간 생기는 락 상호작용(§3)은 표 크기와 무관하게 메커니즘으로 성립한다.

---

## 2. 분기 A — 보존 모델: 무엇을 남기나

| 후보 | 내용 | 얻는 것 | 잃는 것 |
|---|---|---|---|
| **A1** 현 결정 | SENT 는 짧게, FAILED 는 길게, 둘 다 주기 DELETE | 07-29 결정 그대로. 새 표 없음 | 실제 삭제 모양이 «구멍 뚫기» 다(FAILED 가 섞여 살아남음) → +24% 계단 한 번(실측, 누적 없음) |
| A2 발송 즉시 삭제 | `markSent` 를 UPDATE 대신 DELETE 로. FAILED 만 남는다 | 정리 스케줄러가 SENT 에는 필요 없다. 표가 늘 «처리 중인 것 + 실패» 크기 | SENT 이력이 없다. 07-29 에 «실패 조사 시 이력이 없다» 로 기각. 다만 **FAILED 는 남으므로 그 기각 사유가 SENT 에도 해당하는지는 다시 볼 여지가 있다** — SENT 이력으로 답하는 질문이 무엇인지(«이 세션에 STOP 이 갔나?») 적힌 곳이 없다 |
| A3 파티션 + DROP | `created_at` 기준 월 파티션, 오래된 달 DROP | DELETE 가 없다. `pose_data` 에서 검증된 패턴(DROP 이 DELETE 의 625배) | PK 를 `(id, created_at)` 로 바꿔야 한다. SENT·FAILED 보존 기간이 다르면 한 파티션을 통째로 못 지운다 → FAILED 를 먼저 옮기거나 SENT 도 FAILED 만큼 들고 있어야 한다. 07-29 의 전환 조건 «누적이 관측되면» 이 충족되지 않았다 |
| A4 FAILED 를 별도 표로 | 터미널 FAILED 를 `outbox_dead_letters` 로 옮기고 본 표는 SENT 만 FIFO 로 지운다 | 본 표 삭제가 순수 FIFO → 실측상 파편화 누적 0, 계단도 없다 | 표와 이동 로직이 하나씩 는다. 얻는 것이 +24% 계단 한 번뿐이라 비용 대비 작다 |

**추천: A1 유지.** 계단은 한 번 밟고 평탄해지고(구멍 밀도 20배에서도 같음), 이 표는 작다. A2 는 «SENT 이력이 무슨 질문에 답하나» 가 비어 있어서 기각 사유를 다시 확인할 가치는 있지만, 확인 없이 뒤집을 근거도 없다.

---

## 3. 분기 C — 지우는 쿼리의 모양 (이 문서의 핵심)

### 3-1. 왜 지금 메서드를 그대로 켜면 안 되나

1. **`LIMIT` 이 없다.** 처음 켜는 순간 쌓인 행 전체를 문장 하나로 지운다. 긴 트랜잭션, undo 증가, 복제가 있으면 한 덩어리 지연.
2. **락 범위가 발행기와 겹칠 수 있다 (추정, 미측정).** 조건 `status='SENT' AND created_at < ?` 에 맞는 인덱스가 없어 `idx_outbox_dispatch` 의 `status` 선두만 탈 것으로 보인다(EXPLAIN 안 함). REPEATABLE READ 에서 DELETE 는 훑은 인덱스 레코드와 그 사이 간격에 next-key 락을 건다. 그런데 발행기의 `markSent` 는 PROCESSING 행을 SENT 로 바꾸면서 **같은 `(status='SENT', next_retry_at)` 구간에 새 인덱스 항목을 넣는다**(`next_retry_at` 을 그대로 두므로 첫 송신 성공분은 NULL, 재시도분은 과거 시각). 그 구간이 정리 DELETE 의 간격 락 안이면 `markSent` 가 DELETE 가 끝날 때까지 기다린다. 발행기는 한 스레드에서 순차로 도므로 한 건이 막히면 tick 전체가 밀리고, 종료 통보(`STOP_ANALYSIS`)가 늦어진다.

두 번째는 표 크기와 무관하게 성립할 수 있는 메커니즘이라 착수 전에 재야 한다(§6 M1).

### 3-2. 후보

| 후보 | 쿼리 모양 | 락이 닿는 곳 | 얻는 것 | 잃는 것 |
|---|---|---|---|---|
| C1 현 메서드 | `DELETE WHERE status=? AND created_at<?` (벌크) | `status` 인덱스의 SENT 구간 전체 + 간격 | 코드 0줄 | §3-1 의 두 문제 그대로 |
| C2 LIMIT 반복 | 네이티브 `DELETE … WHERE status=? AND created_at<? ORDER BY id LIMIT n` 을 0건 될 때까지 | C1 과 같은 인덱스, 문장당 n행 | 트랜잭션이 짧아진다 | 락이 닿는 **구간**은 C1 과 같다(SENT 구간). 문장이 짧아질 뿐 `markSent` 와의 겹침은 남는다 |
| **C3** PK 순회 | 가장 오래된 쪽부터 PK 로 n행씩 걷는다. ① `SELECT id, created_at … WHERE id > :cursor ORDER BY id LIMIT n` ② 그 구간에서 `DELETE … WHERE id BETWEEN :from AND :to AND status = ? AND created_at < ?` ③ 구간의 가장 이른 `created_at` 이 기준 시각을 넘으면 멈춘다. `created_at` 인덱스는 필요 없다 | PK 의 오래된 구간만. 새로 SENT 가 되는 행은 id 가 크므로 닿지 않는다 | `markSent` 와 구간이 겹치지 않는다(가설). 인덱스 추가 불필요 | 쿼리 2개와 커서가 필요하다. `id` 와 `created_at` 이 대체로 같이 커진다는 전제(AUTO_INCREMENT + `DEFAULT CURRENT_TIMESTAMP`)에 기댄다. 늦게 커밋한 트랜잭션 때문에 경계에서 몇 행이 한 주기 늦게 지워질 수 있다(무해). 오래 남는 FAILED 가 앞쪽에 쌓이면 매 주기 그 구간을 다시 걷는다 → 커서를 «지난번 멈춘 곳» 부터 시작하도록 저장할지 정해야 한다 |

**추천: C3.** 락 구간을 «새로 쓰이는 곳» 과 물리적으로 떼는 유일한 안이다. 단 «겹치지 않는다» 는 가설이라 M1 로 확인한다.

### 3-3. 배치 크기 n

**값을 정하지 않는다.** 근거 없는 숫자를 박지 않는 원칙이다. 유도할 제약은 있다. 발행기 tick 이 1초이고 한 스레드에서 순차로 돈다. 그러면 «배치 하나의 락 보유 시간이 tick 보다 충분히 짧아야 한다» 가 상한 조건이다. n 은 M1 에서 n 별 문장 시간을 재고 그 조건을 만족하는 가장 큰 값으로 정한다.

---

## 4. 분기 D — 인덱스

| 후보 | 얻는 것 | 잃는 것 |
|---|---|---|
| **D1** 추가 없음 (C3 와 짝) | 쓰기 비용 0 | C1·C2 를 고르면 쓸 수 없다 |
| D2 `(status, created_at)` 추가 | C1·C2 의 조건에 딱 맞는다 | **07-29 에 같은 모양으로 실패한 적이 있다.** 선두가 `status` 인 인덱스가 둘이 되자 옵티마이저가 구분하지 못하고 양쪽 다 `status` 프리픽스만 썼다(key_len 1, filtered 33~40%). 하나를 지우자 정상화됐다(V1 주석). 같은 선두의 인덱스를 다시 얹으면 발행기 쿼리가 다시 무너질 위험이 있다 |
| D3 `(created_at)` 단독 | 선두가 달라 D2 의 충돌은 없다 | 모든 INSERT 에 인덱스 쓰기 1회. C3 면 필요 없다 |

**추천: D1.** D2 는 이 표에서 이미 실측으로 한 번 무너진 모양이다.

---

## 5. 분기 B·E·F — 값·실행·관측

### 5-1. B 보존 기간 (값은 사용자가 정한다)

07-29 문서의 7일·90일은 «예:» 로 적힌 값이다. **여기서도 값을 추천하지 않는다.** 대신 값을 묶을 기준 후보를 적는다.

| 상태 | 기능상 하한 | 값을 묶을 기준 후보 |
|---|---|---|
| SENT | **0** — SENT 행을 다시 읽는 코드가 없다(§1) | (a) 앱 로그 보존 기간 — SENT 행의 쓸모는 `correlation_id` 로 로그와 잇는 것인데, 로그가 먼저 사라지면 행만 남아도 이을 곳이 없다 (b) 백업 주기 — 복구 뒤 대조용 (c) A2 로 가서 0 |
| FAILED | 사람이 확인할 때까지 | (a) 시간 TTL (b) 시간 대신 «확인함» 표시 뒤 삭제 — 지금은 확인 절차도 화면도 없다 (c) 무기한 + 누적 게이지로 알람 |

⚠️ 로그 보존 기간이 이 프로젝트에 정해져 있지 않다(stdout). (a) 를 고르면 그걸 먼저 정해야 한다.

### 5-2. E 다중 인스턴스

| 후보 | 내용 | 판단 |
|---|---|---|
| **E1** 감수 | DELETE 는 멱등이라 두 인스턴스가 같이 돌아도 결과는 같다. 경합만 생긴다 | 지금은 단일 인스턴스라 비용 0 |
| E2 ShedLock | 스케줄러 3개(타임아웃·파티션·고아 감시)와 함께 도입 | 07-29 에 «T3 별도 카드» 로 분리한 그 작업. 정리 하나 때문에 당겨올 근거는 없다 |
| E3 `GET_LOCK()` | MySQL 명명 락 | 의존성 없이 되지만 스케줄러마다 따로 짜야 한다 |

**추천: E1.** 다중 인스턴스로 갈 때 E2 카드와 같이 본다.

### 5-3. F 관측

- 지운 건수를 카운터로(`shadowfit.outbox.cleanup.deleted{status}`). 한 번에 몇 건이 지워지는지가 보여야 배치 반복이 끝나는지 안다.
- FAILED 누적 게이지(`countByStatus(FAILED)`)를 추가할지. `idx_outbox_dispatch` 의 `status` 프리픽스로 세므로 싸다. 07-29 문서의 «FAILED 가 꾸준히 쌓이면 그 자체가 알람» 을 실제로 볼 수단이 지금은 없다(카운터는 발생 수라 «지금 몇 건 남아 있나» 를 모른다).

---

## 6. 측정 설계 (착수 전)

로컬 박스(i3-6100)는 절대 시간을 못 잰다. 아래는 **같은 박스 안 팔 간 상대 비교**만 본다. 팔마다 버림판 1 + 반복 3판 이상, 팔 순서는 라틴 방격으로 돌린다.

| ID | 질문 | 방법 | 지표 |
|---|---|---|---|
| M0 | 각 DELETE 모양이 무슨 인덱스를 타나 | C1·C2·C3 의 `EXPLAIN` / `EXPLAIN ANALYZE` | type·key·key_len·rows |
| **M1** | 정리 DELETE 가 `markSent` 를 막나 (§3-1 가설) | SENT 20만 행 + FAILED 섞음(파편화 실측과 같은 행 모양)을 깔고, 발행기에 일정한 적재를 걸어 둔 채 C1·C2·C3 를 돌린다. 대조군은 정리 없음 | `performance_schema.data_lock_waits` 에서 `markSent` 대기 건수·시간, `shadowfit.outbox.lag` p99, `Innodb_row_lock_time` 증분 |
| M2 | 배치 크기 n 의 상한 | C3 에서 n 을 바꿔 가며 문장 시간 | 문장 시간 vs tick 1초 |
| M3 | 첫 실행 비용 | 1년치 추정(110만 행)을 깔고 C3 로 처음 돌린다 | 총 시간, 복제 rig 가 있으면 레플리카 지연 |

**판정선은 결과에서 유도한다.** 대조군(정리 없음) 판 사이의 흔들림 폭을 잡음으로 보고, 그 폭을 넘는 차이만 효과로 읽는다(파편화 실측 때와 같은 방식).

M1 이 «C3 도 막힌다» 로 나오면 §3 추천을 거둔다. «C1 도 안 막힌다» 로 나오면 C1·C2 와의 차이가 트랜잭션 길이뿐이라 C2 로 충분할 수 있다.

---

## 7. 추천 묶음 (결정 아님)

| 분기 | 추천 | 확인 조건 |
|---|---|---|
| A 보존 모델 | A1 유지 | — |
| B 보존 기간 | **사용자 결정** (값을 묶을 기준부터) | 로그 보존 기간이 정해져 있는지 |
| C 쿼리 모양 | C3 PK 범위 배치 | M1 |
| D 인덱스 | D1 추가 없음 | M0 |
| E 다중 인스턴스 | E1 감수 | 다중 인스턴스 전환 시 재검토 |
| F 관측 | 삭제 카운터 + FAILED 게이지 | — |
| 배치 크기 | 값 없음, M2 로 유도 | M2 |

구현 면적(추정): 스케줄러 1개, 리포지토리 네이티브 쿼리 2개(상한 id·범위 DELETE), 설정값 2~3개, race 프로파일 테스트 1개(기준 시각 이전 SENT 만 지워지는지, PENDING·PROCESSING 은 절대 안 지워지는지, 반복이 끝나는지). 마이그레이션 없음.

## 8. 결정 로그

- 2026-09-23: 신설. #793 의 «정할 것» 다섯 개를 분기로 풀고, 락 상호작용 가설(§3-1)과 측정 설계(§6)를 더했다. 결정 없음.
