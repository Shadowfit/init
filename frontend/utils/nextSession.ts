// 다음 세션 추천 카드의 표시 문구. 화면 코드에서 떼어 둔 이유는 서버 값이 «이상해도» 카드가 깨지지 않는지를
// node 로 시험하려는 것이다(폰 없이).
import type { NextSessionRecommendation } from '@/types/recommendation';

export interface NextSessionView {
  level: string;
  reps: string;
  sync: string;
  rest: string;
  reason: string;
}

/** 유한한 숫자만 통과시킨다. 서버가 null·문자열·NaN 을 주면 undefined — 호출부가 «값 없음» 으로 그린다. */
function finite(n: unknown): number | undefined {
  return typeof n === 'number' && Number.isFinite(n) ? n : undefined;
}

/** 90 → «1분 30초», 60 → «1분», 45 → «45초», 0 → «없음» */
export function formatRest(sec: unknown): string {
  const s = finite(sec);
  if (s === undefined || s < 0) return '-';
  if (s === 0) return '없음';
  const whole = Math.round(s);
  const m = Math.floor(whole / 60);
  const r = whole % 60;
  if (m === 0) return `${r}초`;
  return r === 0 ? `${m}분` : `${m}분 ${r}초`;
}

/** 80 → «80%», 82.5 → «82.5%», 82.50 → «82.5%». 소수 첫째 자리까지만. */
export function formatPercent(rate: unknown): string {
  const v = finite(rate);
  if (v === undefined) return '-';
  return `${Math.round(v * 10) / 10}%`;
}

/** 추천 응답을 카드에 그릴 문자열로 바꾼다. 어떤 값이 와도 던지지 않는다. */
export function toNextSessionView(rec: Partial<NextSessionRecommendation> | null | undefined): NextSessionView {
  const level = finite(rec?.difficultyLevel);
  const reps = finite(rec?.targetReps);
  return {
    level: level === undefined ? '-' : `Lv.${level}`,
    reps: reps === undefined ? '-' : `${reps}회`,
    sync: formatPercent(rec?.targetSyncRate),
    rest: formatRest(rec?.restTimeSec),
    reason: typeof rec?.reason === 'string' ? rec.reason.trim() : '',
  };
}
