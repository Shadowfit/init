# 해상도 티어 — 다중 그레인 롤업 설계 (continuous aggregate by hand)

상태: **설계 — 빌드/세부 결정은 사용자 confirm 후**
작성: 2026-06-14
대상: 백엔드(Spring) 포폴. pose_data 시계열을 **여러 해상도(grain)로 롤업**하는 계층을 설계 — 시계열 정석(다운샘플·continuous aggregate)을 MySQL로 손구현. 데이터 "확장 4축" 중 **C(해상도 티어)** 1순위안의 구체화.
연관: [`./report-read-path.md`](./report-read-path.md) §6(precompute), [`./portfolio-benchmark.md`](./portfolio-benchmark.md) §2.2(시계열 정정), [`../portfolio/portfolio-narrative.md`](../portfolio/portfolio-narrative.md) §2, [`./pose-ingest-downsampling.md`](./pose-ingest-downsampling.md), [`../portfolio/db-deep-dive.md`](../portfolio/db-deep-dive.md)

> ✅=있음, ★=빠진 고리/신규, 🔶=설계됨, ⬜=계획. **빌드 결정 마크는 사용자 confirm 후에만.**

---

## 0. 한 줄 요약

pose_data(프레임/세션 ~750행)를 **프레임 → 렙 → 세션 → 일 → 주/월** 5단으로 롤업한다. **위 두 단(렙·세션)은 세션 종료 시 on-write, 아래 두 단(일·주/월)은 스케줄 배치**로 갈라 계산 = continuous aggregate를 손으로 구현. 단순 롤업 테이블은 흔하므로, **정합성 드리프트·백필·멱등**까지 다뤄야 차별이 된다. 해상도를 *더* 높이는 길은 **(가) 더 미세한 시간 그레인**과 **(나) 시간 외 차원(관절·피드백·운동별) 큐브** 두 축이나, 우리 규모엔 **수확 체감**이라 패턴 시연이 목적이지 티어 개수 자랑이 아니다.

---

## 1. 목표 계층 (grain · 행수 · 소스 · 계산 시점)

| 티어 | grain | 세션당 행수 | 소스 | 계산 시점 | 상태 |
|---|---|---|---|---|---|
| **pose_data** | 프레임 | ~750 | (적재) | 실시간 적재 | ✅ 있음 |
| **session_reps** | 렙 | ~10~30 | pose_data 롤업 | 세션 종료 on-write | ★ 빠짐 |
| **reports** | 세션 | 1 | reps/frames 롤업 | 세션 종료(precompute) | 🔶 행 미생성(③) |
| **daily_logs** | 일 | 1/일 | sessions 롤업 | 스케줄 배치 | ✅ 테이블만(집계 빈칸) |
| **reports** `WEEKLY/MONTHLY` | 주/월 | 1/주·월 | daily 롤업 | 스케줄 배치 | ⬜ enum만 준비 |

핵심 분리: **렙·세션 = on-write / 일·주월 = 배치.** 이 경계가 설계의 뼈대.

---

## 2. 빠진 고리 — `session_reps` (렙 티어)

프레임(750)과 세션(1) 사이가 비어 있다. 렙 단위를 넣으면 "다운샘플 1단"이 명시적으로 생기고, **사용자에게 보여줄 단위**(렙별 피드백)와도 도메인 정합.

**테이블(안)**:
```sql
CREATE TABLE session_reps (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  rep_number INT NOT NULL,            -- 1,2,3...
  start_sec DECIMAL(10,3), end_sec DECIMAL(10,3),
  tempo_sec DECIMAL(6,3),             -- end-start (템포)
  min_sync_rate DECIMAL(5,2),         -- 렙 내 최저 (worst 지점)
  avg_sync_rate DECIMAL(5,2),
  is_correct BOOLEAN,
  dominant_feedback VARCHAR(500),
  FOREIGN KEY (session_id) REFERENCES exercise_sessions(id) ON DELETE CASCADE,
  INDEX idx_session_rep (session_id, rep_number)
);
```

**유도**: pose_data 프레임을 **렙 경계로 그룹핑** → 렙당 min/avg/tempo 집계. `exercise_sessions.total_reps`로 렙 개수는 이미 안다. 경계 판정은 두 갈래(§6 결정):
- **(a) AI가 렙 마커 제공** — 정확. 단 ai-server 계약 변경(메모리: Python 최소 변경·확인 후)
- **(b) Spring에서 sync_rate/depth 패턴으로 렙 경계 검출** — ai 안 건드림, 단 검출 로직 필요

---

## 3. 계산 시점 — 설계의 핵심

| 묶음 | 시점 | 이유 | 기술 |
|---|---|---|---|
| rep + session | 세션 종료 **on-write** | 세션당 1회, raw가 따뜻할 때 한 번에 | precompute([`./report-read-path.md`](./report-read-path.md) §6) 확장 — `completeAnalysis`에서 reps+report 동시 생성 |
| daily + weekly/monthly | **스케줄 배치** | 여러 세션 가로집계, 자정/주말 | **Spring Batch**(또는 `@Scheduled`) |

→ continuous aggregate를 손으로 구현. **Spring Batch는 채용 단골 키워드**([`./portfolio-benchmark.md`](./portfolio-benchmark.md))라 legibility도 챙김.

---

## 4. 어려운 부분 = 포폴 깊이 (여기가 차별)

테이블 추가가 아니라 **롤업의 진짜 문제**를 다뤄야 깊이가 난다(벤치마크: 남들은 메커니즘에서 멈춤).

| 문제 | 설계 포인트 |
|---|---|
| **정합성 드리프트** | 롤업이 raw와 어긋남 → 재집계 트리거·검증(체크섬/카운트 대조) |
| **백필(backfill)** | 집계 로직 바뀌면 과거 daily/weekly 재계산 — Spring Batch 재실행 |
| **멱등성** | 배치 재실행 시 중복 집계 금지 — UPSERT/덮어쓰기 키(`uk_member_date` 활용) |
| **늦은 데이터** | 세션이 자정 넘겨 끝나면 어느 daily에? — `occurred_at`/`end_time` 기준 귀속 규칙 |

→ "롤업 테이블 만들었다"는 흔하고, **"백필·멱등·드리프트까지"가 드뭄.**

---

## 5. 해상도를 *더* 높이려면 — 두 축 (단, 수확 체감)

"5단으로 끝이 아니라 더"의 길은 두 방향. **단 우리 규모(DAU 1,000 가정, 세션당 750)엔 패턴 시연이 목적이지 티어 개수 경쟁이 아님** — 무한정 쪼개면 over-engineering.

### (가) 더 미세한 *시간* 그레인 (수직 ↓↑)

| 추가 그레인 | 의미 | 가치 / 함정 |
|---|---|---|
| **phase (렙 하위)** — 하강/바텀/상승 | 스쿼트 1렙을 국면으로 분해 | 🟢 도메인 organic(폼 분석은 국면별). intra-rep 해상도. 단 프레임에서 또 검출 필요 |
| **set (렙 상위)** — 세트 묶음 | 여러 렙 = 1세트 | 🔶 운동 도메인엔 자연스러우나 현재 세션 모델에 세트 개념 없음 → 스키마 추가 |
| **lifetime (주/월 상위)** — 분기·전체·PR | 회원 생애 누적·개인기록(PR) | 🔶 long-horizon. 단 시간 지평 볼륨(확장 D) 필요, 합성 한계 |

> 프레임보다 *아래*(더 미세)는 불가 — 프레임이 적재 최소 단위. 더 미세 = fps↑(볼륨이지 해상도 구조 아님).

### (나) *시간 외* 차원 롤업 — 1D 체인 → 큐브 (수평 →)

시간만이 아니라 **다른 축으로도 집계**하면 OLAP 큐브처럼 입체화:

| 차원 | 롤업 예 | 가치 |
|---|---|---|
| **관절별(per-joint)** | 33 관절 중 어느 관절이 자주 틀리나(시간×관절) | 🟢 fat JSON(joint_coordinates)을 *처음으로 실사용* → over-fetch 서사와 연결 |
| **피드백별(per-feedback)** | feedback_type별 빈도·추세(session_feedback_logs 활용) | 🟢 이미 있는 이벤트 로그 활용 |
| **운동별(per-exercise)** | 운동 종류별 평균 sync(스쿼트 우선이라 지금은 단일) | ⬜ 멀티 운동 후속(메모리: 스쿼트 우선) |

→ (나)가 (가)보다 **포폴 신선도 높음**: 단순 시간 다운샘플은 흔한데, **다차원 롤업(특히 관절별 = fat 컬럼 실사용)**은 드물고 우리 데이터에서만 가능.

### 정직 라인

- 티어/차원을 **많이 쌓는다고 깊이가 선형 증가 안 함** — 5단→9단이 9/5배 가치 아님. **패턴(다운샘플·큐브)을 보여주면 충분.**
- 더 높이는 가치 순: **관절별 차원(나) > phase 그레인(가) > set/lifetime**(스키마·볼륨 추가 비용).

---

## 6. 미결정 (사용자 confirm 필요)

- [ ] **렙 경계**: (a) AI 마커 vs (b) Spring 검출 — ai-server 계약 손댈지(메모리 규칙)
- [ ] **저장**: 신규 `session_reps` + `daily_logs` 빈 집계 컬럼 채우기 vs 다른 구조
- [ ] **일/주월 계산**: Spring Batch vs `@Scheduled` 단순 잡
- [ ] **착수 범위**: 렙 티어만(on-write) 먼저 vs 일·주월 배치까지
- [ ] **해상도 더 높이기 채택 여부**: 관절별 큐브(나) / phase 그레인(가) — 어디까지가 ROI

---

## 7. 포폴 가치 한 줄

> pose_data → 렙 → 세션 → 일 → 주/월 **5단 롤업**을 on-write+배치로 갈라 **continuous aggregate를 손구현**하고, **백필·멱등·드리프트**까지 다루면 시계열 정석 + Spring Batch 키워드 + 희소한 운영 깊이. 해상도를 더 높이면 **관절별 다차원 큐브**(fat 컬럼 실사용)가 가장 신선하나, 우리 규모엔 패턴 시연이 목적 — 티어 개수가 아님.

---

## 8. 관련 문서
- [`./report-read-path.md`](./report-read-path.md) — 읽기 경로(§6 precompute = session 티어 계산)
- [`./portfolio-benchmark.md`](./portfolio-benchmark.md) — 채울 키워드(Spring Batch)·시계열 천장 정정
- [`./pose-ingest-downsampling.md`](./pose-ingest-downsampling.md) — 적재 다운샘플(프레임 수 자체)
- [`../portfolio/portfolio-narrative.md`](../portfolio/portfolio-narrative.md) §2 — DB 깊이 축
- 코드: `ExerciseAnalysisService.java`(completeAnalysis = on-write 자리), `PoseDataRepository.java`, `mysql/schema.sql`(daily_logs·reports)
