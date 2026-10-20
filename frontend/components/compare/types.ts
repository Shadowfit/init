import type { MotionPayload } from '@/lib/motion/viz';
import type { ReferenceCycle, SegmentId } from '@/lib/motion/types';

export type CompareMode = 'side' | 'overlay';

/** 3D 비교 장면에 보내는 명령 (WebView·iframe 공통). */
export interface CompareViewHandle {
  setMotion(payload: MotionPayload | null): void;
  play(on: boolean): void;
  seek(frac: number): void;
  setMode(mode: CompareMode): void;
  setSpeed(speed: number): void;
}

export interface PositionEvent {
  /** 0~1 — 선택한 반복 안에서의 재생 위치 */
  frac: number;
  index: number;
  playing: boolean;
  segment: SegmentId | null;
}

export interface CompareViewProps {
  exercise: 'squat' | 'lunge';
  /** 장면이 기준 시범 관절을 다 뽑았다 — 비교를 시작할 수 있다 */
  onReference: (cycles: ReferenceCycle[]) => void;
  onPosition: (e: PositionEvent) => void;
}

/** 장면(WebView/iframe)이 앱으로 보내는 메시지를 해석한다. 모르는 형태는 null. */
export function parseSceneMessage(raw: string):
  | { type: 'reference'; cycles: ReferenceCycle[] }
  | { type: 'pos'; event: PositionEvent }
  | null {
  try {
    const m = JSON.parse(raw);
    if (m?.type === 'reference' && Array.isArray(m.cycles)) return { type: 'reference', cycles: m.cycles };
    if (m?.type === 'pos') return { type: 'pos', event: { frac: m.frac, index: m.index, playing: !!m.playing, segment: m.segment ?? null } };
  } catch {
    /* 장면의 다른 메시지·잡음은 무시 */
  }
  return null;
}
