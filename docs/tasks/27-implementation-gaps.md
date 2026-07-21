# 구현 갭 정리 (2026-07-20 코드 검증 기준)

작성: 2026-07-20. 오늘 대화 중 실제 코드/문서로 재검증해서 확인한 미구현·미결정 항목 정리. `22-backend-tasks-detail.md`는 방대하지만 일부 항목이 실제 구현 상태를 못 따라가고 있어(예: 세션 피드백 조회 API는 실제로는 구현돼 있는데 그 문서엔 미구현으로 표시) 전체 신뢰는 어려움 — 이 문서는 오늘 직접 코드 확인한 것만 담음.

---

## 1. DB/백엔드 핵심 — 오늘 코드로 확인한 미구현

- [ ] **precompute-on-write** — 리포트 worst 구간을 세션 종료 시 미리 계산해 `reports`에 저장하는 구조. 지금은 `ReportService.getSessionReport`가 조회할 때마다 `pose_data`를 즉석 재계산(`selectWorstSection`). 설계는 [`db-deep-dive.md §B-3`](../portfolio/db-deep-dive.md) ⬜.
- [ ] **TTL 자동 만료 스케줄러** — `mysql/schema.sql`에 파티션 스키마(월별 RANGE, PK `(id, created_at)`)는 PR #43로 반영됨. 근데 오래된 파티션을 주기적으로 `DROP PARTITION`하는 `@Scheduled` 잡이 코드에 없음. 설계는 [`db-deep-dive.md §D`](../portfolio/db-deep-dive.md).
- [ ] **개별 세션 삭제 기능** — 세션 1건만 지우는 API 자체가 없음(회원 탈퇴 전체 삭제 경로만 있음). [`pose-data-partition-fk-tradeoff.md §1-1`](../decisions/pose-data-partition-fk-tradeoff.md)에서 발견된 별도 feature gap.
- [ ] **report 생성 멱등성** — `reports`에 `session_id` 유니크 제약 없음, 세션 종료 재시도 시 중복 생성 가능성. [`db-deep-dive.md §C`](../portfolio/db-deep-dive.md)에 🔶 후보로 남아있음.

## 2. 판단 완료, 착수 여부만 미결정

- [ ] **다운샘플링(1초 다운샘플)** — 위치(A: AI서버 / B: Spring / C: 안 함)도, 착수 여부도 미결정. 효과는 실측 완료(R≈5에서 RPS +126%, p99 −70%, 저장 5배↓). [`pose-ingest-downsampling.md §7`](../decisions/pose-ingest-downsampling.md).
- [ ] **Redis 도입** — "MVP 단계 MySQL 부족 미증명"으로 보류 확정(CLOSED). T1~T5 트리거 발생 시 재검토. [`redis-introduction.md`](../decisions/redis-introduction.md).

## 3. 이미 스스로 인지한 약점

- [ ] **관측성/모니터링/N+1 점검** — `portfolio-narrative.md` §6에 🔴로 자체 표시.
- [ ] **테스트 커버리지** — 테스트 파일 5개뿐.
- [ ] **외부 통합 다양성 부족** — OAuth2·S3·결제·푸시·검색 등 0개. [`25-portfolio-strategy.md`](./25-portfolio-strategy.md).
- [ ] **AI→Spring 콜백 방향 장애 보호 없음** — 서킷브레이커/재시도가 Spring→AI 방향에만 있음, 반대 방향은 fire-and-forget(의도적 스코프 제외, `production-signal-checklist.md` §2-3-4-2).

## 4. 남겨둔 검증 작업(코드 아님, 실험)

- [ ] **소량 DELETE 반복 시 파편화 실험** — 미실험. 메모리: `project_pending_delete_fragmentation_experiment`.
- [ ] **풀 사이징 AWS(RDS) 재검증** — 로컬 2코어 동거 환경 종속 결론, 비용 문제로 미착수·미결정. 메모리: `project_pending_aws_pool_sizing_reverify`.

## 5. 2학기 계획 범위(지금 범위 밖)

- [ ] **다른 운동 종목 확장**(런지·플랭크 등) — 지금은 스쿼트만. `project_squat_first` 방침.
- [ ] **베타 테스트/실사용자 피드백** — `24-semester2-plan.md` 참고.

---

## 우선순위 제안

인터뷰 준비 관점에서는 **1번(DB 핵심 미구현)**이 가장 중요 — 오늘 리뷰한 실험 깊이(precompute·TTL·정합성) 바로 다음 단계라 자연스럽게 이어지는 이야기가 됨. 2~3번은 "왜 아직 안 했는지"를 정직하게 설명할 수 있으면 충분. 4~5번은 지금 당장 급하지 않음.
