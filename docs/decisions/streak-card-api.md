# Decision: 운동 스트릭 카드 API — 메인 화면 «내 연속 출석» 한 방 조회

상태: **✅ 확정 (2026-09-18 사용자 confirm)** — §2 b·§3 전 필드·§4 A·§5 `/attendance/mine` 권고안 그대로 채택, 같은 날 구현(§6 면적대로). §8 EXPLAIN 실측 완료(2,000행 계정 총 1.2~2.3ms, 커버링 인덱스·디스크 임시 테이블 0)
작성: 2026-09-18
배경: 2학기 구현 목록(캡스톤 발표 자료 2p «메인 화면 — 운동 스트릭 기능»)에서 ✔ 이 안 찍힌 항목. 확인해 보니 «연속일수 숫자» 자체는 이미 세 API 로 나가고 있어(§1) 부족한 건 숫자가 아니라 **카드 하나가 필요로 하는 항목 묶음**(오늘 여부·이번 주 7칸·최장 기록)이다. 범위는 «스트릭 카드»로 확정(2026-09-18 사용자 선택 — 마일스톤/배지·프리즈는 제외).
연관: [`./social-cheer-and-group-feed.md`](./social-cheer-and-group-feed.md) §3-B(출석 정의·streak 창), [`./friend-status-streak-fanout-experiment-design.md`](./friend-status-streak-fanout-experiment-design.md)(다중 회원 streak), [`./recommendation-algorithm.md`](./recommendation-algorithm.md) §10(인덱스 역방향 걷기 실측), [`../07-api-design.md`](../07-api-design.md), [`../tasks/35-frontend-api-handoff.md`](../tasks/35-frontend-api-handoff.md)

> 결정 ✅ 는 사용자 confirm 후 박제. 본 문서는 분석·권고.

---

## 1. 현황 — 숫자는 이미 셋, 계산기는 하나

| 노출 지점 | 필드 | 정의 | 계산기 |
|---|---|---|---|
| `GET /reports/calendar` (메인 달력) | `consecutiveDays` | 창 없음, COMPLETED, 오늘 또는 어제 앵커 | `AttendanceService.currentStreak` |
| `GET /friends` · `GET /groups/{id}/members/status` | `streak` + `attendedToday` | 위와 같음 | `AttendanceService.currentStreak(s)` |
| `GET /patterns/consistency` | `currentStreakDays` | **최근 4주 창 안에서의 연속** (08-30 confirm, 별개 정의) | `PatternAnalysisService.calculateStreak` |

출석 정의는 §3-B(2026-09-11) 로 한 곳에 고정돼 있다 — «그날 `status = COMPLETED` 세션 1건 이상, 날짜 귀속은 `start_time` 의 서버 LocalDate(Asia/Seoul)». 이 문서는 그 정의를 **바꾸지 않는다**. 스트릭 카드는 같은 계산기 위에 항목을 얹는 일이다.

**없는 것** — 카드가 필요로 하는 항목 중 지금 어디서도 안 나가는 것:
1. **최장 연속 기록**(길이 + 언제) — 계산 로직 자체가 없다
2. **이번 주 7칸**(월~일 각 날의 출석 여부) — `/reports/calendar` 가 그 달 전체를 주긴 하지만, 주가 월을 걸치면(예: 9/29~10/5) 두 번 불러야 하고 응답에 싱크로율·월 통계까지 딸려 온다
3. **내 `attendedToday`** — 친구 현황에는 있는데 «나» 에겐 없다(달력 `records` 에서 오늘 날짜를 찾아 프론트가 파생 가능하나 계약이 아님)

---

## 2. 왜 `/reports/calendar` 에 필드를 더 얹지 않는가

| 후보 | 장점 | 단점 |
|---|---|---|
| a. `/reports/calendar` 응답에 `longestStreak`·`thisWeek` 추가 | 새 엔드포인트 없음, 프론트 호출 1회 유지 | 달력 응답이 «그 달 통계 + 스트릭 카드» 두 관심사를 싣게 됨. `year/month` 파라미터가 있는 API 가 파라미터와 무관한 값(최장 기록·이번 주)을 돌려주는 모양. 달력을 안 보는 화면(예: 홈 상단 카드만)도 그 달 세션을 다 읽어야 함 |
| **b. 별도 `GET` 하나** (권고) | 카드 = 계약 1개. 파라미터 없음(오늘 기준). 달력과 독립 캐시·독립 비용 | 메인 화면 호출이 1회 늘어남(달력 + 카드) |
| c. 친구 현황 DTO(`MemberAttendanceStatusDto`)에 나를 포함 | 계약 재사용 | 최장 기록·7칸은 §3-G 가 «남에게 안 보이는 항목» 으로 노출 범위를 고정하지 않았고, 남의 최장 기록을 보여줄지는 별개 결정. 내 것과 남의 것을 한 DTO 에 섞으면 그 결정을 미리 해버리는 꼴 |

→ **추천 b.** `consecutiveDays` 는 `/reports/calendar` 에 그대로 둔다(프론트 호환). 두 값은 같은 계산기라 어긋날 수 없다.

---

## 3. 응답 계약 (후보)

```
GET /attendance/mine            (이름은 §5)
Authorization: Bearer <JWT>
```

```json
{
  "today": "2026-09-18",
  "attendedToday": false,
  "currentStreak": 5,
  "currentStreakStart": "2026-09-13",
  "longestStreak": 12,
  "longestStreakStart": "2026-07-01",
  "longestStreakEnd": "2026-07-12",
  "thisWeek": [
    { "date": "2026-09-14", "attended": true },
    { "date": "2026-09-15", "attended": true },
    { "date": "2026-09-16", "attended": true },
    { "date": "2026-09-17", "attended": true },
    { "date": "2026-09-18", "attended": false },
    { "date": "2026-09-19", "attended": false },
    { "date": "2026-09-20", "attended": false }
  ]
}
```

| 필드 | 규칙 | 근거 |
|---|---|---|
| `today` | 서버 LocalDate(Asia/Seoul) | 프론트 기기 시계와 서버 날짜가 어긋날 때 «오늘» 이 어느 날인지 응답이 말해준다. 자정 근처 문제를 프론트가 추측하지 않게 |
| `attendedToday` | 오늘 COMPLETED 1건 이상 | 친구 현황과 같은 필드명·정의 |
| `currentStreak` | 오늘 또는 어제까지 이어진 연속 일수. 없으면 0 | `AttendanceService.currentStreak` 그대로(관대한 규칙, 08-30 confirm) |
| `currentStreakStart` | streak 첫날. 0 이면 null | «7/1부터 N일째» 문구용. 계산기가 이미 걸어 내려간 마지막 날 = 시작일이라 추가 비용 0 |
| `longestStreak` | 전 기간 최장 연속 일수. 세션 없으면 0 | §4 |
| `longestStreakStart/End` | 그 구간. 동률이면 **가장 최근** 구간. 0 이면 null | «갱신 중» 판정을 프론트가 `currentStreak == longestStreak && longestStreakEnd >= today-1` 로 할 수 있게 |
| `thisWeek` | 월요일 시작 7개, 오늘 이후 날은 `attended:false` | 월~일은 발표 자료 기준. 미래 날을 빼고 주면 칸 수가 요일마다 달라져 프론트가 채워야 함 → 항상 7개 |

**문구는 프론트가 파생한다** — `MemberAttendanceStatusDto` 와 같은 관례. «오늘 운동하면 6일째», «최고 기록 갱신 중», «어제까지 5일, 오늘 하면 이어짐» 전부 위 필드에서 나온다. 서버가 문구를 내지 않는다.

**넣지 않는 것**: 마일스톤(7·30·100일) 도달 여부 — 프론트가 `longestStreak`·`currentStreak` 으로 판정 가능, 서버 상태 없음. 이력(«언제 30일 달성») 은 이번 범위 밖.

---

## 4. 최장 기록 계산 — 분기점

현재 streak 은 «답의 크기» 만큼만 읽지만(§3-B 하위 C), 최장 기록은 **정의상 회원의 전 이력을 봐야** 한다. 어디서 보느냐가 갈린다.

### 가정
DAU 1,000, 회원당 하루 최대 몇 세션, 기록 나이 최대 2년(2학기 + 시연 기간을 넉넉히). 회원당 COMPLETED 행은 하루 1세션 기준 ≈ 730, 하루 3세션이면 ≈ 2,200. 읽는 인덱스는 `idx_session_member_status_start(member_id, status, start_time)` — `member_id`·`status` 등치 뒤 `start_time` 구간이라 **커버링 인덱스 전용 스캔**이고 표 본문은 안 읽는다. 부하 rig 의 `member_id=1`(1,680세션) 이 이 가정의 상한에 가까운 계정이라 실측 대상으로 쓸 수 있다.

| 후보 | 방법 | 읽기 | 전송 | 테스트 | 비고 |
|---|---|---|---|---|---|
| **A. DISTINCT 날짜를 받아 자바에서 gaps 순회** (권고) | `SELECT DISTINCT CAST(start_time AS date) … WHERE member_id=? AND status='COMPLETED'` (기존 `findDistinctActiveDates` 에서 기간 조건만 뺀 것) → 정렬된 날짜를 한 번 훑으며 연속 구간 최대값 | 인덱스 행 전부(≤ 2,200) | 날짜 수(≤ 730) | H2 로 됨 | 코드 10줄. `findDistinctActiveDates` 관례 그대로 |
| B. MySQL 윈도우 함수 gaps-and-islands 한 방 | `d − ROW_NUMBER() OVER (ORDER BY d)` 로 구간 키를 만들고 `GROUP BY` → `ORDER BY len DESC, end DESC LIMIT 1` | A 와 같음 | 1행 | **H2 안 됨**(`DATE_SUB` + 윈도우) → `race` 프로파일만. `findCompletedStartTimesBeforeBatch` 와 같은 처지 | 전송량은 줄지만 DISTINCT + 윈도우 + GROUP BY 가 임시 테이블 2단. 전송 730행 vs 임시 테이블의 우열은 **미측정** |
| C. `users.longest_streak` 저장 컬럼, 세션 완료 시 갱신 | `applyComplete` 에서 현재 streak 를 계산해 최댓값 갱신 | 조회 0 | 1행 | 쉬움 | **삭제 드리프트** — `DELETE /sessions/{id}` 가 최장 구간 안의 날을 지우면 저장값이 틀리고, 고치려면 삭제 시 전체 재계산(=A 를 어차피 갖고 있어야 함). `daily_logs` 를 출석 원천으로 안 쓴 이유(#718)와 같은 모양. 기존 회원 backfill 마이그레이션도 필요 |
| D. C + 삭제 시 재계산 | C 에 `deleteSession` 훅 추가 | — | — | — | A 를 품은 C. 읽기 비용을 아끼려고 쓰기 두 경로에 부수효과를 더하는 셈인데, 그 읽기 비용이 A 에서 «회원당 인덱스 ≤ 2,200행 1회» 라 아낄 게 있는지가 먼저 실측돼야 함 |

→ **추천 A.** 이유: (1) 출석 원천은 `exercise_sessions` 원본 하나라는 §3-B 결정과 정합 — 파생 저장을 만들지 않는다. (2) 비용 상한이 가정 안에서 커버링 인덱스 스캔 ≤ 2,200행이고, 같은 인덱스의 같은 회원(1,680세션)이 §10 에서 이미 실측된 적 있다 — 다만 그건 LIMIT 3 이었고 전량 스캔은 **미측정**이라 착수 시 `EXPLAIN ANALYZE` 한 번은 찍는다. (3) B 는 포트폴리오 서사로는 매력적이지만 H2 테스트가 빠지고, 우열도 미측정이라 «측정 없이 더 복잡한 쪽» 이 된다. A 로 만들고 B 는 «전송량 vs 임시 테이블» 델타를 재는 후속 실험 후보로 남긴다.

**A 가 C 로 바뀌어야 하는 조건**(적어 두는 것): 회원당 이력이 가정을 넘어 스캔이 ms 단위를 벗어나거나, 메인 화면 호출 빈도 × 회원 수가 이 쿼리를 DB 상위 쿼리로 올릴 때. 지금은 둘 다 아니다(가정 §4).

### 현재 streak 시작일
`StreakWalk` 가 걸어 내려간 마지막 `expected + 1` 이 시작일이다. 계산기가 이미 아는 값이라 `currentStreak` 와 함께 돌려주면 된다 — 지금은 `int` 만 반환하므로 반환형을 `(length, start)` 레코드로 바꾸는 소폭 변경. 호출부 3곳(달력·친구·모임)은 `length` 만 쓰면 되니 영향은 시그니처뿐.

### 이번 주 7칸
`findDistinctActiveDates(memberId, [COMPLETED], 월요일 00:00, 일요일 23:59:59)` 1회. `attendedToday` 는 이 결과에서 오늘을 찾으면 되므로 **별도 `exists` 쿼리가 필요 없다**(친구 현황은 여러 회원이라 따로 물었던 것).

### 쿼리 수
요청 1회당 3개 — 현재 streak(페이지 1~n, 대부분 1), 이번 주(1), 최장(1). 전부 같은 인덱스.

---

## 5. 엔드포인트 이름

| 후보 | 근거 |
|---|---|
| **`GET /attendance/mine`** (권고) | 도메인 이름이 «출석»(`AttendanceService`, §3-B, `/groups/{id}/attendance`)이고, «내 것» 은 `/groups/mine`·`/invitations/mine` 관례 |
| `GET /streaks/mine` | 화면 이름과 같아 프론트가 찾기 쉬움. 대신 `thisWeek`·`attendedToday` 는 streak 가 아니라 출석이라 이름이 응답보다 좁음 |
| `GET /me/attendance` | `/me` 프리픽스 관례가 이 저장소에 없음(`/member/…` 는 인증 계열) |

컨트롤러는 새로 `AttendanceController`(권고) — `ExerciseRecordController`(`/reports`) 에 넣으면 리포트 계열과 섞인다.

---

## 6. 구현 면적 (A 기준)

| 파일 | 변경 |
|---|---|
| `AttendanceService` | `currentStreak` 반환형 → `StreakResult(length, start)`; `longestStreak(memberId)` 추가; `attendedDaysBetween(memberId, from, to)` 는 기존 `findDistinctActiveDates` 래핑 |
| `SessionRepository` | `findDistinctCompletedDates(memberId)` — 기간 조건 없는 판 1개 |
| `AttendanceController` (신규) | `GET /attendance/mine` |
| `dto/attendance/MyAttendanceResponseDto` (신규) | §3 |
| 호출부 3곳 | `currentStreak(...)` → `.length()` |
| 테스트 | 서비스 단위(H2): 세션 없음 / 오늘만 / 어제까지 / 이틀 비어 끊김 / 동률 구간은 최근 것 / 같은 날 세션 2건 / 주 경계가 월을 걸침 / 미래 start_time 무시. 통합 1개: 인증·응답 형태 |
| 문서 | `07-api-design.md` 소셜 절 옆에 항목, `35-frontend-api-handoff.md` 에 줄 추가, `README.md`(decisions) 인덱스 |

마이그레이션 없음(A). 인덱스 추가 없음 — 기존 `idx_session_member_status_start` 로 3개 쿼리 전부 커버.

---

## 7. 결정 항목

- [x] **§2** ✅ **b. 별도 엔드포인트** (2026-09-18) — `/reports/calendar` 의 `consecutiveDays` 는 그대로
- [x] **§3** ✅ **전 필드 채택** (2026-09-18) — `today`·`currentStreakStart`·`longestStreakStart/End` 포함, `thisWeek` 월~일 7개 고정
- [x] **§4** ✅ **A. DISTINCT 날짜를 자바에서 순회** (2026-09-18) — 저장 컬럼(C·D) 안 만듦, 윈도우 함수(B)는 후속 실험 후보로만
- [x] **§5** ✅ **`GET /attendance/mine`**, 컨트롤러 `AttendanceController` (2026-09-18)
- [x] §4 최장 기록 쿼리 `EXPLAIN ANALYZE` — ✅ §8 (2026-09-18, 로컬 MySQL 8.0.46, 계정 4종 × 10회)

---

## 8. 실측 — 최장 기록 쿼리는 커버링 인덱스 스캔 + 메모리 임시 테이블, 2,000행 계정에서 1~2ms (2026-09-18)

§4 가 «전량 스캔은 미측정» 으로 남겼던 것. 로컬 `shadowfit-mysql`(MySQL 8.0.46, i3-6100 동거 박스 — 절대값이 아니라 **모양과 기울기**만 본다) 의 실 계정으로 Hibernate 가 만드는 SQL 그대로 `EXPLAIN ANALYZE`, 계정 크기별 10회 반복.

**쿼리** (`findDistinctDatesByStatus` 가 생성하는 SQL):
```sql
select distinct cast(s1_0.start_time as date) from exercise_sessions s1_0
where s1_0.member_id=? and s1_0.status='COMPLETED' order by cast(s1_0.start_time as date)
```

**계획** (member 577, COMPLETED 2,000행 / 203일):
```
-> Sort: cast(start_time as date)                          (actual 3.4..3.41  rows=203)
   -> Table scan on <temporary>                            (actual 3.21..3.24 rows=203)
      -> Temporary table with deduplication                (actual 3.21       rows=203)
         -> Covering index lookup on s1_0 using idx_session_member_status_start
            (member_id=577, status='COMPLETED')            (actual 0.31..2.17 rows=2000)
Handler_read_key=1  Handler_read_next=2000  Handler_write=2203  Created_tmp_tables=2  Created_tmp_disk_tables=0  Sort_rows=203
```
- 읽기는 **커버링 인덱스 한 구간**(`Handler_read_key` 1 + `read_next` 2,000) — 표 본문 접근 0, §4 가정대로.
- `DISTINCT CAST(...)` 는 표현식이라 **메모리 임시 테이블**(`Handler_write` 2,203 = 2,000 + 203) → 그 203행을 정렬. 디스크 임시 테이블 0.

**계정 크기별** (10회, 총 시간 = Sort 노드 actual 끝):

| 계정 | COMPLETED 행 | 출석일 | 총 median | min~max | 인덱스 스캔 median |
|---|---:|---:|---:|---|---:|
| 577 | 2,000 | 203 | **1.21 ms** | 0.99~2.13 | 0.60 |
| 121 | 2,000 | 181 | **2.25 ms** | 1.99~3.33 | 1.30 |
| 532 | 1,000 | 103 | 1.20 ms | 0.55~1.58 | 0.65 |
| 520 | 100 | 13 | 0.09 ms | 0.07~0.16 | 0.05 |

- 행수에 **선형** — 대략 행당 0.5~1 µs. 같은 2,000행인데 121 이 577 의 두 배인 건 121 의 `start_time` 이 무작위(rig 생성)라 리프 페이지가 더 퍼진 것으로 보이나 그 원인은 **미검증**.
- §4 가정 상한(≤ 2,200행)에서 **한 자릿수 ms**. 요청당 다른 두 쿼리(현재 streak 페이지 ≈ 0.4 ms(§10 재인용)·이번 주 range scan 0.07 ms — 같은 판에서 확인)보다 이 쿼리가 지배적이지만, 슬로우 로그 부류가 아니다.
- **A → C(저장 컬럼) 전환 조건은 여전히 안 밟혔다** — 이 쿼리가 DB 상위 쿼리로 올라오려면 «회원당 수만 행» 또는 «메인 화면 호출이 초당 수백» 이어야 하고 둘 다 가정 밖. 후보 B(윈도우 함수)는 전송량(203행 → 1행)만 줄이고 스캔·임시 테이블은 같아서, 이 크기에선 잴 델타가 없다 — 실험 후보에서 내린다.

**재는 법** (다음에 같은 부류 쿼리를 잴 때): `docker exec shadowfit-mysql mysql -uroot -p… <db>` 로 들어가 ① Hibernate SQL 을 파라미터만 리터럴로 바꿔 `EXPLAIN ANALYZE …\G` ② `FLUSH STATUS` 뒤 실제 실행 → `SHOW SESSION STATUS WHERE Variable_name IN ('Handler_read_key','Handler_read_next','Handler_write','Created_tmp_tables','Created_tmp_disk_tables','Sort_rows')` 로 «몇 행을 어떻게 읽었나»를 계획이 아니라 카운터로 확인 ③ 계정 크기 3~4종 × 10회 반복해 median 과 기울기. 1회 값은 이 박스에서 2~6× 튄다(577 의 1회차 인덱스 스캔 11.4 ms 가 그 예).

## 이력

- 2026-09-18: 작성. 범위 «스트릭 카드»(현재·오늘·7칸·최장) 로 좁힘(사용자 선택). 마일스톤·프리즈 제외.
- 2026-09-19: 머지 후 자체 리뷰에서 결함 — 최장 기록 쿼리에 상한이 없어 미래 start_time 이 들어갔다(현재 streak 은 제외). `findDistinctDatesBefore(today+1)` 로 경계를 맞춤(#780).
- 2026-09-18: §8 EXPLAIN 실측 — 커버링 인덱스 + 메모리 임시 테이블, 2,000행 계정 1.2~2.3 ms, 행수 선형. B 는 실험 후보에서 내림.
- 2026-09-18: §2·§3·§4·§5 권고안 그대로 확정(사용자 confirm), 같은 날 구현. `AttendanceService.currentStreak` 은 시그니처 유지하고 `currentStreakRun`(구간) 을 옆에 뒀다 — 호출부 3곳 무변경. 새 쿼리는 `findDistinctDatesByStatus` 하나, 이번 주는 기존 `findDistinctActiveDates` 재사용이라 `attendedToday` 의 별도 exists 쿼리 없음(요청당 3쿼리). 테스트 12개(단위 5 + 통합 7 — 처음엔 14개로 잘못 셌다). EXPLAIN 은 같은 날 §8 로 닫음.
