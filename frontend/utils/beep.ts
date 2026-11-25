/**
 * 운동 중 비프음 — «어떤 이벤트에 어떤 소리를, 얼마나 자주» 만 정한다. React Native 를 import 하지 않는 순수 함수라
 * node 로 바로 시험된다(`node utils/beep.test.cjs`). 실제 재생은 lib/beepPlayer.ts.
 *
 * 소리 3종(합성 방식은 scripts/generate-beeps.cjs):
 *   success  rep 완료        상승 2음
 *   warning  자세 결함·rep 미인정  낮은 단음
 *   notice   전신/관절이 안 보임   짧은 2연속
 *
 * <b>말이 아니라 소리다.</b> 어떤 자세가 틀렸는지는 소리로 알 수 없고 화면 문구(coachingNotice·lastFeedback)를 봐야 한다.
 * 대신 말을 듣기 어려운 운동 중에도 «지금 뭔가 알려 준다» 는 신호가 빠르고, 서버 문구에 맞출 필요가 없다.
 */

export type BeepKind = 'success' | 'warning' | 'notice';

/** 같은 종류 소리 사이의 최소 간격(ms). 0 이면 제한 없음. */
export const BEEP_COOLDOWN_MS: Record<BeepKind, number> = {
  // rep 은 1초 이상 간격으로 끝나므로 따로 막을 필요가 없다 — 한 번 센 것은 한 번 울린다.
  success: 0,
  // 자세 결함은 프레임마다(약 3fps) 같은 판정이 반복될 수 있다. 2.5초 안에 또 울리면 «경보 소음» 이 된다.
  warning: 2500,
  // 전신이 안 보이는 상태는 한동안 이어진다. 급하지 않아서 길게 둔다.
  notice: 6000,
};

/** 종류와 상관없이 직전 소리 뒤 최소 간격(ms). 서로 다른 소리가 겹쳐 뭉개지는 것을 막는다(가장 긴 소리 ≈ 260ms). */
export const MIN_GAP_MS = 300;

export interface BeepGate {
  /** 종류별로 마지막에 «실제로 울린» 시각(ms). */
  lastByKind: Partial<Record<BeepKind, number>>;
  /** 종류 무관, 마지막에 울린 시각. 없으면 null. */
  lastAny: number | null;
}

export function createBeepGate(): BeepGate {
  return { lastByKind: {}, lastAny: null };
}

/**
 * 지금 울려도 되는가. 입력 상태를 바꾸지 않고 새 상태를 돌려준다.
 * 막힌 요청(play=false)은 상태를 갱신하지 않는다 — 안 그러면 막힌 것이 쿨다운을 계속 연장해 영영 못 울린다.
 */
export function decideBeep(
  gate: BeepGate,
  kind: BeepKind,
  now: number,
  enabled: boolean,
): { play: boolean; gate: BeepGate } {
  if (!enabled) return { play: false, gate };

  if (gate.lastAny != null && now - gate.lastAny < MIN_GAP_MS) return { play: false, gate };

  const last = gate.lastByKind[kind];
  if (last != null && now - last < BEEP_COOLDOWN_MS[kind]) return { play: false, gate };

  return { play: true, gate: { lastByKind: { ...gate.lastByKind, [kind]: now }, lastAny: now } };
}

/** AI 분석 응답(`/pose`) 중 소리에 쓰는 필드. */
export interface BeepResponse {
  message?: string | null;
  rep_completed?: boolean | null;
  feedback_type?: string | null;
}

/**
 * 응답 한 건이 어떤 소리를 내야 하는가. 없으면 null.
 * message 가 있으면 rep 단위 안내다 — 완료면 success, 아니면(미인정·자세 무너짐) warning.
 * message 가 없고 feedback_type 만 있으면 프레임 단위 자세 결함이라 warning.
 */
export function beepKindForResponse(r: BeepResponse): BeepKind | null {
  if (r.message) return r.rep_completed === true ? 'success' : 'warning';
  if (r.feedback_type) return 'warning';
  return null;
}

/** ReadinessBanner 의 톤(components/exercise/ReadinessBanner.tsx). */
export type ReadinessToneLike = 'idle' | 'wait' | 'warn' | 'ready' | 'active' | 'error';

/**
 * 운동 중 «전신이 안 보여요» 로 <b>막 바뀐 순간</b>인가. 이미 warn 인 채 이어지는 동안은 false —
 * 매 렌더마다 울리지 않고, 쿨다운과 별개로 «전이» 에서만 한 번 알린다.
 * 연결 오류(error)는 소리를 내지 않는다 — 화면 문구로 충분하고 사용자가 할 수 있는 일이 다르다.
 */
export function shouldNoticeOnReadiness(
  prevTone: ReadinessToneLike | null,
  tone: ReadinessToneLike,
  isRecording: boolean,
): boolean {
  return isRecording && tone === 'warn' && prevTone !== 'warn';
}
