// 피드 글 리액션(💗🔥)의 표시·낙관적 갱신 규칙. 화면에서 떼어 둔 이유는 «눌렀을 때 숫자가 어떻게 바뀌는가» 를
// 폰 없이 node 로 시험하려는 것이다.
import type { ReactionKind, ReactionSummary } from '@/types/social';

export const REACTION_KINDS: readonly ReactionKind[] = ['HEART', 'FIRE'];

export const REACTION_EMOJI: Record<ReactionKind, string> = {
  HEART: '💗',
  FIRE: '🔥',
};

/** 서버가 `reactionSummary` 를 안 주거나 모양이 어긋나도 그릴 수 있게 하는 빈 값 */
export function emptySummary(): ReactionSummary {
  return { reactions: {}, myReactions: [] };
}

/** 받은 값을 믿지 않고 안전한 모양으로 맞춘다. */
export function normalizeSummary(raw: Partial<ReactionSummary> | null | undefined): ReactionSummary {
  const reactions: Partial<Record<ReactionKind, number>> = {};
  for (const kind of REACTION_KINDS) {
    const n = raw?.reactions?.[kind];
    if (typeof n === 'number' && Number.isFinite(n) && n > 0) reactions[kind] = Math.floor(n);
  }
  const mine = Array.isArray(raw?.myReactions)
    ? raw!.myReactions!.filter((k): k is ReactionKind => REACTION_KINDS.includes(k as ReactionKind))
    : [];
  return { reactions, myReactions: Array.from(new Set(mine)) };
}

export function countOf(summary: ReactionSummary, kind: ReactionKind): number {
  return summary.reactions[kind] ?? 0;
}

export function hasReacted(summary: ReactionSummary, kind: ReactionKind): boolean {
  return summary.myReactions.includes(kind);
}

/**
 * 누른 직후 화면에 먼저 그릴 값(낙관적 갱신). 서버 응답이 오면 그 값으로 덮어쓴다.
 * 내가 이미 달았으면 취소(−1, 0 아래로 안 내려간다), 아니면 추가(+1). 같은 글의 다른 종류는 건드리지 않는다 —
 * 서버가 한 글에 HEART·FIRE 를 둘 다 허용한다.
 */
export function withToggled(summary: ReactionSummary, kind: ReactionKind): ReactionSummary {
  const base = normalizeSummary(summary);
  const reacted = hasReacted(base, kind);
  const nextCount = Math.max(0, countOf(base, kind) + (reacted ? -1 : 1));
  const reactions = { ...base.reactions };
  if (nextCount > 0) reactions[kind] = nextCount;
  else delete reactions[kind];
  const myReactions = reacted ? base.myReactions.filter((k) => k !== kind) : [...base.myReactions, kind];
  return { reactions, myReactions };
}
