# 페르소나 & 적응형 난이도 시스템 가이드

> ## 🔁 2026-10-21 — 판정 기준을 페르소나와 분리, **종목당 하나**로 통일
>
> 페르소나별 싱크로율 기준(아래 표의 60/85/70/50)은 **더 이상 쓰지 않는다.** 판정 기준은 종목마다 두 값이다.
>
> | 값 | 기본 | 뜻 | 쓰는 곳 |
> |---|---|---|---|
> | 정석 기준 `sync_good_threshold` | 80 | 이 이상 = «자세 양호» | AI 회차 판정, 앱 싱크로율 색(라임)·리포트 색 |
> | 위험 기준 `sync_warn_threshold` | 60 | 이 미만 = «즉시 자세 수정 필요» | AI 회차 판정, 앱 색(빨강)·진동 경고 |
>
> 관리자 웹 › 운동 종목 › 판정 기준에서 바꾸고(PATCH `/admin/exercises/{id}/thresholds` `{good, warn}`), 세션 시작·재부착 때
> Spring 이 `AnalyzeRequest`(11·12번 필드)·`ReattachRequest`(12·13번 필드)로 AI 에 실어 보낸다. 0(미설정)이면 AI 는 80/60.
> 마이그레이션 V34 가 페르소나 컬럼 4개를 지우고 두 컬럼을 추가했다.
>
> **왜 바꿨나.** ① 같은 자세에 페르소나마다 다른 판정(초급자 60 «양호» vs 고급자 85 «보정 필요»)을 주면 «정확한 자세»라는
> 제품 약속과 어긋난다. 싱크로율 자체가 기준 동작과의 거리라, 잣대는 하나여야 한다. ② 실제로도 반쯤 죽어 있었다 —
> DB 의 페르소나 컬럼 4개는 아무도 안 읽었고, AI 는 상수(`SYNC_THRESHOLDS`)를, 앱은 80/60 을 따로 박아 썼다.
> 관리자가 화면에서 고쳐도 아무 데도 반영되지 않는 상태였다.
>
> **페르소나는 이제 피드백 말투만** 바꾼다(아래 «페르소나별 피드백 톤»). 판정·점수·색은 모두 같다.
> 아래 표와 «적응형 난이도» 절의 페르소나별 `baseSyncRate` 는 2026-03 설계 기록으로 남겨 둔다.

## 페르소나 정의 (회의록 3주차)

| 페르소나 | 대상 | 싱크로율 기준 | 특징 |
|---------|------|-------------|------|
> ## ✅ 이 표가 드디어 실제 코드와 일치한다 (2026-08-08 확인)
>
> 이 문서는 2026-03-30 설계안인데, **2026-07-22 까지 «문서에만» 있었다.** 그때까지 실제 상태는:
>
> | 층 | 당시 |
> |---|---|
> | Spring DB | 컬럼 **2개**(beginner/advanced)만 |
> | ai-server `constants.py` | 4개 dict 이 있었으나 **0~1 스케일의 죽은 값** — 아무도 안 읽었다 |
> | 실제 판정 로직 | 🔴 **페르소나 무관 고정값**(`sync_rate >= 70/40`) |
>
> 2026-07-22(`d8349f3`)에 실제로 연결됐고, 지금은 아래 네 값이 코드에 그대로 있다 — `ai-server/app/utils/constants.py:44` 의 `SYNC_THRESHOLDS = {BEGINNER: 60.0, ADVANCED: 85.0, DIET: 70.0, REHAB: 50.0}` 이고 `squat_analyzer.py:367` 이 `SYNC_THRESHOLDS.get(state.persona, …)` 로 읽는다. 경위: [`handoff/ai-persona-threshold-connection.md`](./handoff/ai-persona-threshold-connection.md).
>
> 📌 **4개월간 «설계는 있는데 안 걸려 있던» 상태였다.** 문서가 틀린 게 아니라 **문서만 맞았던** 경우다 — 이 저장소의 다른 드리프트(문서가 낡음)와 방향이 반대라 기록해둔다. 같은 형태가 지금도 하나 남아 있다: `ReportFeedbackBatch` 는 proto·수신부·테이블이 다 있는데 **AI 가 안 부른다**([`tasks/30-ai-remaining-work.md`](./tasks/30-ai-remaining-work.md) §1).

| 헬린이 (BEGINNER) | 운동 초심자, 홈트 입문자 | 60% 이상 | 직관적 가이드, 쉬운 피드백 |
| 헬창 (ADVANCED) | 중/상급자 | 85% 이상 | ROM 분석, 좌우 불균형 데이터 |
| 다이어트 (DIET) | 체중 감량 목적 | 70% 이상 | 칼로리 소모 중심, 운동량 강조 |
| 재활 (REHAB) | 부상 회복 중 | 50% 이상 | 안전 범위 초과 경고, 저강도 |

## 페르소나별 피드백 톤

### 헬린이 (BEGINNER)
```
"좋아요! 자세가 점점 좋아지고 있어요 💪"
"무릎 위치만 조금 신경 쓰면 완벽해요!"
"첫 번째 세트 완료! 잘하고 있어요!"
```

### 헬창 (ADVANCED)
```
"좌측 무릎 각도 78도, 우측 82도 - 좌우 편차 4도"
"ROM 범위 92% 달성. 바텀 포지션에서 2도 부족"
"이전 대비 평균 싱크로율 3.2% 향상"
```

### 다이어트 (DIET)
```
"현재 예상 칼로리 소모: 85kcal! 계속 화이팅!"
"이번 세트 심박수 구간 도달. 효율적인 운동 중!"
"목표 운동량까지 30% 남았어요!"
```

### 재활 (REHAB)
```
"⚠️ 관절 각도가 안전 범위를 벗어났습니다. 천천히 줄여주세요."
"좋습니다. 안전한 범위 내에서 운동 중이에요."
"무리하지 말고 통증이 있으면 바로 중단해주세요."
```

## 적응형 난이도 조절 로직

```typescript
interface DifficultyConfig {
  level: number;           // 1-10
  targetReps: number;      // 목표 반복 횟수
  targetSyncRate: number;  // 목표 싱크로율
  restTimeSec: number;     // 세트 간 휴식 시간
}

const adjustDifficulty = (
  currentLevel: number,
  completedReps: number,
  targetReps: number,
  avgSyncRate: number,
  targetSyncRate: number,
  persona: string
): number => {
  const repCompletion = completedReps / targetReps;
  const syncAchievement = avgSyncRate / targetSyncRate;

  // 성공 판정: 목표 반복 횟수 달성 + 싱크로율 기준 충족
  if (repCompletion >= 1.0 && syncAchievement >= 1.0) {
    // 성공 → 난이도 상승
    return Math.min(currentLevel + 1, 10);
  } else if (repCompletion < 0.5 || syncAchievement < 0.7) {
    // 실패 (크게 미달) → 난이도 하락
    return Math.max(currentLevel - 1, 1);
  } else {
    // 부분 성공 → 난이도 유지
    return currentLevel;
  }
};

// 난이도별 설정
const getDifficultyConfig = (level: number, persona: string): DifficultyConfig => {
  const baseReps = persona === 'REHAB' ? 5 : 10;
  const baseSyncRate = {
    BEGINNER: 60,
    ADVANCED: 85,
    DIET: 70,
    REHAB: 50,
  }[persona] || 60;

  return {
    level,
    targetReps: baseReps + (level - 1) * 2,
    targetSyncRate: Math.min(baseSyncRate + (level - 1) * 2, 95),
    restTimeSec: Math.max(90 - (level - 1) * 5, 30),
  };
};
```

## GPT 피드백 생성 (운동 종료 후)

```typescript
// Backend에서 GPT API 호출
const generateFeedback = async (sessionData: SessionData, persona: string) => {
  const prompt = `
    사용자 페르소나: ${persona}
    운동: ${sessionData.exerciseName}
    평균 싱크로율: ${sessionData.avgSyncRate}%
    총 반복 횟수: ${sessionData.totalReps}
    운동 시간: ${sessionData.duration}분
    주요 문제점: ${sessionData.issues.join(', ')}

    위 운동 결과를 바탕으로 ${persona} 페르소나에 맞는 톤으로
    1) 운동 결과 요약 (2-3문장)
    2) 잘한 점 (1-2개)
    3) 개선 포인트 (1-2개)
    4) 다음 운동 추천사항
    을 작성해주세요.
  `;

  // OpenAI API 호출
  // ...
};
```
