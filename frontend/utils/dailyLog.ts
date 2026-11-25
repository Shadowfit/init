/**
 * 운동 일지(메모·기분) — 입력 정규화·변경 판단·요청 만들기. React Native 를 import 하지 않는 순수 함수라
 * node 로 바로 시험된다(`node utils/dailyLog.test.cjs`).
 *
 * 백엔드 계약(`POST /reports/daily-logs`, DailyLogRequestDto)
 *   - 같은 날이면 <b>덮어쓴다</b>(upsert). memo·mood 를 <b>둘 다</b> 그대로 대입하므로, 한쪽만 보내면 다른 쪽이 지워진다.
 *     그래서 언제나 둘 다 보낸다 — 입력 화면이 서버에서 받은 현재 값으로 시작하는 이유다(`/reports/daily` 응답의 memo·mood).
 *   - 비운 값은 null 로 보낸다. null 은 «지움» 이다.
 */

/** 백엔드 `dto/report/record/Mood` 와 같다(순서 = 좋음 → 나쁨). */
export type Mood = 'GREAT' | 'GOOD' | 'NORMAL' | 'BAD' | 'TERRIBLE';

export const MOODS: { value: Mood; emoji: string; label: string }[] = [
  { value: 'GREAT', emoji: '😄', label: '최고예요' },
  { value: 'GOOD', emoji: '🙂', label: '좋아요' },
  { value: 'NORMAL', emoji: '😐', label: '보통이에요' },
  { value: 'BAD', emoji: '🙁', label: '별로예요' },
  { value: 'TERRIBLE', emoji: '😣', label: '힘들어요' },
];

/**
 * 메모 최대 글자 수. DB 컬럼은 TEXT 라 서버 한도는 사실상 없다 — 이 값은 «한 줄 소감» 용도에 맞춘 앱의 제한이다
 * (긴 글이 달력 카드를 밀어내지 않게). 서버가 받는 한 더 늘려도 된다.
 */
export const MEMO_MAX = 500;

export interface DailyLogDraft {
  memo: string | null;
  mood: Mood | null;
}

/** 입력 문자열을 서버로 보낼 값으로. 앞뒤 공백을 걷고, 비면 null, 한도를 넘으면 자른다. */
export function normalizeMemo(text: string | null | undefined): string | null {
  if (text == null) return null;
  const trimmed = text.trim();
  if (trimmed === '') return null;
  return trimmed.length > MEMO_MAX ? trimmed.slice(0, MEMO_MAX) : trimmed;
}

/** 서버에서 받은 값을 입력 화면의 초기값으로. mood 가 모르는 문자열이면(서버가 값을 늘린 경우) null 로 본다. */
export function toDraft(memo: string | null | undefined, mood: string | null | undefined): DailyLogDraft {
  const known = MOODS.some((m) => m.value === mood);
  return { memo: normalizeMemo(memo), mood: known ? (mood as Mood) : null };
}

/** 저장된 값(base)과 입력 중인 값(draft)이 다른가 — 같으면 저장 버튼을 막는다. 공백만 다른 것은 같은 값이다. */
export function isDirty(base: DailyLogDraft, draft: { memo: string | null; mood: Mood | null }): boolean {
  return normalizeMemo(base.memo) !== normalizeMemo(draft.memo) || base.mood !== draft.mood;
}

/** 일지를 쓸 수 있는 날짜인가 — 오늘까지만. 미래 날짜의 «소감» 은 있을 수 없다. YYYY-MM-DD 문자열은 사전순 = 시간순이다. */
export function canWriteLog(date: string, today: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(date) && date <= today;
}

export interface DailyLogRequest {
  logDate: string;
  memo: string | null;
  mood: Mood | null;
}

/** POST 본문. memo·mood 를 항상 둘 다 담는다(위 계약). */
export function buildDailyLogRequest(
  date: string,
  draft: { memo: string | null; mood: Mood | null },
): DailyLogRequest {
  return { logDate: date, memo: normalizeMemo(draft.memo), mood: draft.mood ?? null };
}

/** 카드에 보일 기분 표시(이모지+문구). 없거나 모르는 값이면 null. */
export function moodDisplay(mood: string | null | undefined): { emoji: string; label: string } | null {
  const m = MOODS.find((x) => x.value === mood);
  return m ? { emoji: m.emoji, label: m.label } : null;
}
