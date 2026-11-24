// 세션 자세 결함 «이벤트 목록» 의 표시 규칙. 화면에서 떼어 둔 이유는 서버 값이 어긋나도 목록이 깨지지 않는지
// 폰 없이 node 로 시험하려는 것이다.
// 값(라벨 표)을 가져오므로 node 시험에서 `@/` 별칭이 안 풀린다 — 상대 경로로 쓴다(services/sessionVersion.ts 와 같은 이유).
import { FEEDBACK_TYPE_LABEL, type FeedbackType, type SessionFeedbackEvent } from '../types/feedback';

/** 접힌 상태에서 보여 주는 개수. 서버는 페이징이 없어 한 세션의 이벤트가 몇 개든 한 번에 온다. */
export const FEEDBACK_PREVIEW = 20;

/** "2026-10-06T11:40:33" → "11:40:33". 읽을 수 없으면 «-». 타임존이 없는 로컬 시각이라 Date 로 바꾸지 않는다. */
export function formatClock(iso: unknown): string {
  if (typeof iso !== 'string') return '-';
  const m = /T(\d{2}:\d{2}:\d{2})/.exec(iso);
  return m ? m[1] : '-';
}

/** 모르는 종류(서버가 새 유형을 추가했는데 앱이 옛 버전)는 코드 그대로 보여 준다 — 빈 칸보다 낫다. */
export function feedbackLabel(type: unknown): string {
  if (typeof type !== 'string') return '-';
  return FEEDBACK_TYPE_LABEL[type as FeedbackType] ?? type;
}

/** 소수 첫째 자리까지. null·NaN·문자열이면 undefined — 호출부가 «점수 없음» 으로 그린다. */
export function syncText(rate: unknown): string | undefined {
  if (typeof rate !== 'number' || !Number.isFinite(rate)) return undefined;
  return `${Math.round(rate * 10) / 10}%`;
}

export interface FeedbackRow {
  key: number | string;
  time: string;
  label: string;
  sync?: string;
}

/** 이벤트 → 한 줄. `hasScore` 가 false(점수를 못 낸 세션)면 싱크로율 칸을 비운다. */
export function toRows(events: readonly SessionFeedbackEvent[] | null | undefined, hasScore: boolean): FeedbackRow[] {
  if (!Array.isArray(events)) return [];
  return events.map((e, i) => ({
    key: e?.id ?? `i${i}`,
    time: formatClock(e?.occurredAt),
    label: feedbackLabel(e?.feedbackType),
    sync: hasScore ? syncText(e?.syncRateAtTrigger) : undefined,
  }));
}

/** 접힘 상태면 앞 N개만. 남은 개수도 같이 돌려줘 «전체 N개 보기» 문구에 쓴다. */
export function visibleRows<T>(rows: readonly T[], expanded: boolean, preview = FEEDBACK_PREVIEW): { shown: T[]; hidden: number } {
  if (expanded || rows.length <= preview) return { shown: [...rows], hidden: 0 };
  return { shown: rows.slice(0, preview), hidden: rows.length - preview };
}
