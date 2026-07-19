# Decision: pose_data 파티셔닝 실스키마 반영 — FK 제거에 따른 참조무결성 대체 설계

상태: **OPEN (사용자 결정 대기)**
작성: 2026-07-20
배경 문서: [`./db-portfolio-roadmap.md`](./db-portfolio-roadmap.md), [`../portfolio/realmysql-experiments.md`](../portfolio/realmysql-experiments.md) (파티션 실측), [`./load-test-strategy.md`](./load-test-strategy.md) §7 (batch INSERT 실측)
관련 이슈: [GitHub #41](https://github.com/Shadowfit/init/issues/41)

---

## 1. 배경 / 문제

`pose_data` 파티셔닝(Range by `created_at`, TTL 만료 시 `DROP PARTITION`이 `DELETE WHERE`보다 ~625배 빠름)은 `loadtest/measure_partition.sh`로 스크래치 테이블에서 실측 완료했으나, 실제 `mysql/schema.sql`에는 아직 반영 안 됨.

실스키마에 반영 시도 중 로컬 MySQL 8.0에서 다음 에러로 막힘:

```
ERROR 1506 (HY000): Foreign keys are not yet supported in conjunction with partitioning
```

**MySQL/InnoDB는 FK가 걸린 테이블의 파티셔닝을 지원하지 않는다.** 지금 `pose_data`엔 다음 FK가 있음:

```sql
FOREIGN KEY (session_id) REFERENCES exercise_sessions(id) ON DELETE CASCADE
```

파티션을 걸려면 이 FK를 반드시 제거해야 함. 그런데 이 FK(정확히는 `ON DELETE CASCADE`)가 실제로 하는 일이 있는지 확인한 결과, **애플리케이션에 직접적인 "세션 삭제" 기능은 없지만, 회원 탈퇴 경로를 통해 실제로 발동되는 CASCADE 체인이 존재함**을 확인:

```
users(id) ──ON DELETE CASCADE──▶ exercise_sessions.member_id
exercise_sessions(id) ──ON DELETE CASCADE──▶ pose_data.session_id  (+ 다른 2개 테이블도 동일 패턴)
```

`MemberController.deleteMember` → `MemberService.deleteMember`(`memberRepository.delete(member)`, `MemberService.java:90`) 호출 시, DB가 이 체인을 타고 회원의 모든 세션과 `pose_data`를 자동으로 지워준다. **FK를 제거하면 이 자동 정리가 사라지고, 회원 탈퇴 후 `pose_data`가 고아 행으로 남는다.**

---

## 2. 현재 보유 자원 · 제약

### 자원
- 파티션 스킴은 이미 실측·검증됨(`loadtest/measure_partition.sh`) — Range by `UNIX_TIMESTAMP(created_at)`, 월별 14개 파티션 + `pfuture`
- batch INSERT(JdbcTemplate) 이미 실코드 반영·검증 완료 — 파티션 반영과 별개로 적재 경로는 이미 안정
- `session_id → exercise_sessions(id) ON DELETE CASCADE`가 `pose_data` 외 2개 테이블(추정: `session_feedback_logs`, `reports`)에도 걸려 있으나, **이번 파티셔닝은 `pose_data`에만 적용** — 다른 2개 테이블의 FK·CASCADE는 그대로 유지되므로 영향 없음

### 제약
- MySQL/InnoDB의 원천적 제약(FK+파티션 비호환)이라 우회 불가 — FK를 없애거나 파티션을 포기해야 함
- `pose_data`는 세션당 ~750행 규모지만, 회원 하나가 세션을 많이 쌓으면 삭제 대상이 수만~수십만 행이 될 수 있음
- 앞서 실측한 것처럼 대량 행의 동기 `DELETE`는 느림(8.3M행 기준 18.6분) — 회원 탈퇴 API 응답 시간에 이 비용이 얹히면 안 됨
- 회원 탈퇴는 사용자 대면 API(`DELETE /members/{email}`) — 응답 지연에 민감

---

## 3. 결정해야 할 분기점

### 분기 A. FK 제거 후 파티셔닝을 진행할 것인가
### 분기 B. (A에서 진행 선택 시) `pose_data` 참조무결성을 어디서·언제 보장할 것인가

---

## 4. 분기 A: 파티셔닝 진행 여부

| 선택지 | 의미 | 장점 | 단점 |
|---|---|---|---|
| **A1. 파티셔닝 보류** | FK·CASCADE 유지, 파티션은 스크래치 테이블 실측으로만 남김 | 구현 비용 0, 리스크 0 | TTL 만료 시 625배 비용 차이를 실스키마에서 못 누림. 데이터 계속 쌓이면 `DELETE` 기반 만료가 운영 부담(18분+ 락) |
| **A2. 파티셔닝 진행** | FK 제거 + 분기 B의 대체 메커니즘 도입 | TTL 만료가 사실상 무료(DROP PARTITION), 장기 운영 관점에서 이득 큼 | 구현 비용 발생(분기 B), PK 복합키화(`(id, created_at)`) 동반 필요 |

**추천**: **A2 (파티셔닝 진행)**. 이유:
- `pose_data`는 세션마다 계속 쌓이는 구조라 시간이 지날수록 A1의 비용(만료 시 DELETE 부담)이 누적됨
- 분기 B의 구현 비용은 있으나, 일회성 설계 비용 대비 장기 이득(TTL 운영 비용 제거)이 큼
- 이미 파티션 스킴·batch INSERT 모두 실측·검증돼 있어 추가 검증 비용이 낮음

---

## 5. 분기 B: 참조무결성 대체 메커니즘

**전제**: FK `ON DELETE CASCADE`가 사라지면, 회원 탈퇴 시 `pose_data` 정리를 애플리케이션이 책임져야 함.

| 선택지 | 의미 | 삭제 시점 | 회원 탈퇴 API 지연 | 구현 비용 | 개인정보 즉시성 |
|---|---|---|---|---|---|
| **B1. 동기 명시적 삭제** | `MemberService.deleteMember` 트랜잭션 안에서 `pose_data` 명시적 `DELETE` | 탈퇴 즉시 | ⚠️ 세션·pose_data 많으면 API 응답 지연(실측: 대량 삭제 시 분 단위) | 낮음(삭제 쿼리 1개 추가) | ✅ 즉시 |
| **B2. 비동기 배치 정리** | 탈퇴 시 `member`/`exercise_sessions`만 즉시 처리(소프트 삭제 또는 즉시 삭제), `pose_data`는 별도 스케줄러가 주기적으로 "존재하지 않는 session_id" 정리 | 지연(배치 주기만큼) | ✅ 없음 | 중(배치 잡 신설) | ⚠️ 지연 — 정책 검토 필요 |
| **B3. 파티션 자연 만료에 위임** | 탈퇴 후 별도 정리 없이, 기존 TTL 만료(DROP PARTITION) 때 자연히 함께 제거되도록 방치 | TTL 주기만큼(최대 만료 주기) | ✅ 없음 | 낮음(추가 코드 없음) | ❌ 탈퇴 후에도 최대 TTL 기간만큼 개인 데이터 잔존 |
| **B4. B1+B2 혼합** | 소규모(세션 적은 회원)는 동기 삭제, 임계치 초과 시 비동기 배치로 전환 | 조건부 | 조건부 | 중~상(분기 로직 필요) | ✅ 대체로 즉시, 대량일 때만 지연 |

**추천**: **B2 (비동기 배치 정리)**, 단 개인정보 즉시성 요구사항 확인 후 확정.

사유:
- 회원 탈퇴 API는 사용자 대면 기능이라 지연 없는 응답이 우선순위 높음 — B1은 이 실측 프로젝트가 이미 증명한 "대량 DELETE는 느리다"는 사실과 정면으로 부딪힘
- B3은 구현이 가장 쉽지만 "탈퇴 후에도 개인 데이터가 최대 TTL 기간 남아있음"이 정책적으로 허용되는지 별도 확인 필요 — 확인되면 B3이 최선(구현 비용 0)
- B4는 안전하지만 지금 규모(신입 포폴 프로젝트, 실사용자 없음)에 과설계

**미결 질문**:
1. 회원 탈퇴 시 개인정보(원본 pose_data) 삭제의 **법적/정책적 즉시성 요구가 있는가?** (한국 개인정보보호법 등 — 있으면 B3 배제, B2도 배치 주기를 정책 요구에 맞춰야 함)
2. B2 선택 시 배치 주기는? (TTL 만료 스케줄러와 통합할지, 별도 잡으로 둘지)
3. `exercise_sessions` 자체는 탈퇴 시 즉시 삭제(현행 CASCADE 유지, `pose_data`만 예외)로 둘 것인가, 아니면 세션도 소프트 삭제로 바꿀 것인가?

---

## 6. 종합 권장 (디폴트 선택지)

| 분기 | 디폴트 | 이유 |
|---|---|---|
| A. 파티셔닝 진행 여부 | **A2 (진행)** | 장기 TTL 운영 비용 절감이 구현 비용보다 큼 |
| B. 참조무결성 대체 | **B2 (비동기 배치)** 잠정, 개인정보 즉시성 요구 확인 후 B3/B2 확정 | 회원 탈퇴 API 응답 지연 방지가 우선 |

**구현 시 함께 반영 필요**:
- `mysql/schema.sql`의 `pose_data`: PK `id` → `(id, created_at)` 복합키, FK 제거, `PARTITION BY RANGE (UNIX_TIMESTAMP(created_at))` 추가
- (B2 선택 시) 스케줄러 신설: `exercise_sessions`에 없는 `session_id`를 가진 `pose_data` 주기 정리
- 회원 탈퇴 흐름 문서화: `pose_data`만 CASCADE 대상에서 빠진다는 비대칭 구조를 코드 주석/문서에 명시(다음 사람이 "왜 pose_data만 다르지?" 헷갈리지 않도록)

---

## 7. 결정 로그

(결정 시 이 섹션에 날짜·선택지·사유 기록)
