# Decision: `exercises.code` — 종목을 무엇으로 식별할 것인가

상태: 🟢 **컬럼 추가 방향 확정** (2026-09-10, 사용자: *"exercises.code 도 같이 넣는 걸로"*).
      **세부는 열려 있다** — 값 체계(§3) · proto 확장 범위(§4) · 적용 시점(§6)은 미결.
작성: 2026-09-10
대상: `exercises` 에 **자연키가 없어서** AI 서버가 대리키(`id`)를 소스에 하드코딩하고 있는 상태.
      런지(2회차 종목 확장)를 붙이기 전에 닫는 것이 맞는 선결 과제.
연관: `ai-server/app/core/analyzer_registry.py`(문제를 스스로 기록해 둔 파일) ·
[`reference-style-identity.md`](./reference-style-identity.md)(같은 «식별이 안 닿는다» 계열이지만 대상이 `exercise_references` 라 별건) ·
[`ai-backend-coupling.md`](./ai-backend-coupling.md) · [[project_squat_first]]

> 🟢=제안, 🔶=열림, ❌=스코프 밖.

---

## 0. 한 줄 요약

`exercises` 에는 **종목을 가리킬 안정적인 값이 없다.** `id` 는 시드 순서에 불과한 대리키고 `name` 은 한국어라 코드로 못 쓴다. 그래서 AI 서버가 **DB 의 `id` 를 파이썬 dict 에 적어두는** 방식으로 버티고 있고, 그 파일이 스스로 위험을 적어놨다. `code` 컬럼 하나가 이 결합을 끊는다.

---

## 1. 지금 상태 — 대리키가 서비스 경계를 넘어 새고 있다

`ai-server/app/core/analyzer_registry.py`:

```python
# exercises.id → 분석기 키.
#
# ⚠️ **DB 의 id 를 여기 적어둔 것이라 결합이 약하다.** 시드가 바뀌면(마이그레이션에서 id 를
#    다시 매기면) 이 표는 조용히 틀린다. 제대로 하려면 Spring 이 종목 코드를 실어 보내야
#    하는데, `exercises` 에 코드 컬럼이 없고 `name` 이 한국어라 컬럼 추가 마이그레이션이
#    따라온다. 그건 별도 결정으로 두고, 지금은 표를 좁게 유지한다.
_EXERCISE_ID_TO_TYPE: dict[int, str] = {
    1: "squat",
}
```

**이 문서가 그 "별도 결정"이다.**

`exercises` 의 현재 컬럼(`V1__baseline.sql`)에서 식별에 쓸 수 있는 후보:

| 컬럼 | 자연키로 쓸 수 있나 |
|---|---|
| `id BIGINT AUTO_INCREMENT` | ❌ 대리키. 시드 순서일 뿐이고 재採番되면 의미가 바뀐다 |
| `name VARCHAR(100)` | ❌ 한국어('스쿼트'). 게다가 **UNIQUE 제약도 없다** — 같은 이름 두 행을 DB 가 안 막는다 |
| `category ENUM` | ❌ 종목이 아니라 부위 |

즉 **자연키가 아예 없다.** 그래서 대리키가 프로세스 경계를 넘어 다른 언어의 소스 코드에 박혔다.

### 1-1. 지금 안 터지고 있는 이유

`resolve_exercise_type()` 이 **매핑에 없는 id 를 거절**하도록 좁게 짜여 있다. 표가 낡으면 «되던 것이 안 되는» 쪽으로 틀리지 «안 될 것이 되는» 쪽으로 틀리지 않는다. 지금 위험이 잠복해 있는 것은 이 방어 덕분이지 구조가 안전해서가 아니다.

원래 사건([#147](https://github.com/Shadowfit/init/issues/147))은 반대 방향이었다 — `StartAnalysis` 가 `exercise_type="squat"` 을 못박아, **런지로 세션을 시작해도 스쿼트 기준으로 채점**됐다. 에러가 아니라 조용히 틀린 점수였다.

---

## 2. 왜 지금인가 — 런지의 선결 과제

런지를 켜려면 `_EXERCISE_ID_TO_TYPE` 에 `2: "lunge"` 를 더해야 한다. **지금 구조로 붙이면 종목이 늘어날수록 그 dict 가 커지고, 시드 id 에 대한 종속이 깊어진다.** 컬럼을 먼저 넣으면 런지는 dict 가 아니라 데이터로 붙는다.

❗ 세트 도입(BE-09)과는 **독립**이다. 세트는 `pose_data`/`session_sets` 쪽이고 이건 `exercises` 쪽이라 서로 안 겹친다. 순서 제약도 없다.

---

## 3. 🔶 값 체계 — 무엇을 코드로 쓸 것인가

| 안 | 예 | 장점 | 대가 |
|---|---|---|---|
| **ㄱ. 분석기 키와 동일** | `squat` · `lunge` · `plank` | **변환표가 아예 안 생긴다.** AI 의 `_ANALYZERS` 키와 값이 같으므로 Spring 이 보낸 값이 그대로 조회 키가 된다 | AI 내부 명명이 DB 값 규약이 된다(결합의 방향이 바뀔 뿐 사라지진 않음) |
| **ㄴ. 대문자 스네이크** | `SQUAT` · `LUNGE` | 이 저장소의 다른 enum 컬럼 관행(`ReportType`·`GoalType`)과 결이 같다 | AI 쪽에서 소문자로 내리는 변환 한 줄이 생긴다 |

> 🟢 제안: **ㄱ**. 이 변경의 목적이 «중간 매핑표 제거» 인데 ㄴ 은 매핑을 dict 에서 함수로 옮길 뿐이다. 다만 DB 값 컨벤션 일관성을 더 중히 본다면 ㄴ 도 방어된다 — 사용자 판단.

**제약은 어느 안이든 동일하다:**

```sql
code VARCHAR(50) NOT NULL,
UNIQUE KEY uk_exercises_code (code)
```

`UNIQUE` 가 이 변경의 핵심이다. 그게 있어야 `code` 가 **자연키**가 되고, 없으면 그냥 라벨 컬럼 하나 더 생긴 것에 불과하다.

---

## 4. 🔶 proto 를 어디까지 바꾸나

현재 `AnalyzeRequest`:

```protobuf
message AnalyzeRequest {
  int64 exercise_id = 1;
  ...
}
```

| 안 | 내용 | 비고 |
|---|---|---|
| **A. 필드 추가만** | `string exercise_code = 8;` 를 더하고 `exercise_id` 는 남긴다 | 배포 순서에 안전하다 — 빈 문자열이면 AI 가 기존 id 경로로 폴백. `session_nonce` 가 쓴 것과 **같은 compat 수법**(§`AnalyzeRequest` 주석) |
| **B. 교체** | `exercise_id` 제거 | 깔끔하지만 배포 중 진행 세션이 끊길 수 있다 |

> 🟢 제안: **A**. 이 저장소는 이미 `session_nonce` 에서 *"빈 문자열이면 검증을 건너뛴다. 안 그러면 배포 순간 아직 nonce 를 모르는 진행 중 세션이 전부 끊긴다"* 는 방식을 썼다. 같은 패턴을 재사용하고, `exercise_id` 제거는 나중에 별도로.

⚠️ **양쪽 `.proto` 가 바이트 단위로 동일해야 한다** — `backend/src/main/proto/exercise.proto` 와 `ai-server/app/proto/exercise.proto`. CI(`proto-sync-check.yml`)가 diff 로 잡지만 로컬에서 먼저 맞출 것.

---

## 5. DDL 초안 (미적용)

```sql
-- V__add_exercise_code.sql (번호는 적용 시점에 확정)
-- 종목 자연키 도입 — AI 서버가 대리키(id)를 하드코딩하던 결합을 끊는다.
-- 근거: docs/decisions/exercise-code-identity.md

ALTER TABLE exercises
    ADD COLUMN code VARCHAR(50) NULL COMMENT '종목 자연키. AI 분석기 조회 키와 같은 값';

-- 기존 3행 백필 (V2__seed_master_data.sql 기준: 1=스쿼트 2=런지 3=플랭크)
UPDATE exercises SET code = 'squat' WHERE id = 1;
UPDATE exercises SET code = 'lunge' WHERE id = 2;
UPDATE exercises SET code = 'plank' WHERE id = 3;

ALTER TABLE exercises
    MODIFY COLUMN code VARCHAR(50) NOT NULL COMMENT '종목 자연키. AI 분석기 조회 키와 같은 값',
    ADD UNIQUE KEY uk_exercises_code (code);
```

**NULL 로 넣고 → 백필 → NOT NULL 로 조이는 3단계**로 쓴 이유: 기존 행이 있는 표에 `NOT NULL` 컬럼을 한 번에 붙이면 기본값 문제가 생기고, 백필 값을 마이그레이션이 직접 책임지는 편이 재현 가능하다.

⚠️ **`exercises` 는 행이 3개다.** DDL 비용을 따질 표가 아니다 — `pose_data` 의 무중단 DDL 논의를 여기 인용하면 과잉이다.

---

## 6. 🔶 적용 시점

| 후보 | 비고 |
|---|---|
| **런지 착수와 동시** | 목적이 런지의 선결 과제이므로 가장 자연스럽다 |
| **지금 단독으로** | 스쿼트만 있는 지금 넣으면 **바꿀 행이 3개뿐**이라 가장 싸고, 런지 작업에서 스키마 변경이 빠진다 |

> 🟢 제안: **지금 단독.** 종목이 늘어난 뒤에 넣으면 백필 대상과 검증 범위가 같이 는다. 지금은 `_EXERCISE_ID_TO_TYPE` 이 1줄이라 AI 쪽 변경도 최소다([[feedback_minimize_python_changes]] — 면적을 최소로).

---

## 7. 같이 바뀌는 것

| 파일 | 변경 |
|---|---|
| `model/exercise/Exercise.java` | `code` 필드 추가 |
| `backend/.../proto/exercise.proto` · `ai-server/app/proto/exercise.proto` | `AnalyzeRequest.exercise_code` (§4 A안) |
| `service/exercise/ExerciseGrpcService.java` | 요청 빌드 시 `code` 를 실어 보냄 |
| `ai-server/app/core/analyzer_registry.py` | `_EXERCISE_ID_TO_TYPE` 제거, `code` 로 `_ANALYZERS` 직접 조회. `resolve_exercise_type()` 시그니처 변경 |
| `AdminExerciseController` / `AdminExerciseService` | 종목 등록·수정 시 `code` 입력 (관리자 화면에 필드 하나 추가) |

⚠️ **`docs/architecture/` 갱신 대상이다.** proto 밖 계약이 바뀌는 게 아니라 proto 자체가 바뀌므로, Spring↔AI 결합면을 다루는 네 문서가 같이 갱신돼야 한다(CLAUDE.md 규칙).

---

## 8. 미결정 목록

- [x] `code` 컬럼을 넣는다 — ✅ 2026-09-10 사용자 확인
- [ ] §3 값 체계 (ㄱ 분석기 키 동일 / ㄴ 대문자 스네이크)
- [ ] §4 proto 범위 (A 추가만 / B 교체)
- [ ] §6 적용 시점 (지금 단독 / 런지와 동시)
- [ ] `analysis_supported` 와의 관계 — `code` 가 생기면 «분석 가능 여부» 를 DB 플래그와 AI 분석기 보유 중 어디를 진실로 볼지 다시 정리할 여지가 있다. 지금은 둘 다 각자 거절한다(이중 방어라 안전한 방향이므로 급하지 않음)

---

## 결정 로그

| 날짜 | 내용 |
|---|---|
| 2026-09-10 | `exercises.code` 컬럼 도입 방향 확정(사용자). 세부 3건은 열어둔 채 문서화 |
