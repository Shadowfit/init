import type { LandmarkTuple, MotionExercise, MotionRecording, RecordedFrame, Side } from './types';

/**
 * 운동 중 AI 응답의 관절 좌표를 시간순으로 모아 두는 기록기 (3D 운동 비교의 입력).
 *
 * 저장은 **메모리뿐**이다 — 앱을 완전히 종료하면 사라진다. 백엔드는 프레임마다 관절 각도만 저장하고 좌표는
 * 저장하지 않으며(pose_data), 폰에 영속 저장소를 쓰려면 AsyncStorage 네이티브 모듈 버전(package.json 3.1.1 ↔
 * 설치된 2.2.0)부터 맞춰야 해서 이번에는 넣지 않았다. 운동을 끝내고 바로 «내 동작 3D 비교» 를 보는 흐름은 이 기록으로 충분하다.
 */
const MAX_SESSIONS = 5;
/** 한 세션에 쌓는 프레임 상한 — 3fps 로 약 30분. 넘으면 오래된 쪽부터 버리지 않고 기록을 멈춘다(반복 번호가 어긋나므로). */
const MAX_FRAMES = 6000;
/** 서버가 비율을 안 줄 때(구버전 서버) 쓰는 값 — 폰을 세로로 든 4:3 사진(EXIF 회전 반영)의 너비/높이 */
export const DEFAULT_ASPECT = 3 / 4;

interface Live {
  exercise: MotionExercise;
  startedAtMs: number;
  aspect: number | null;
  frames: RecordedFrame[];
  reps: number;
  lead: Side | null;
}

const sessions = new Map<number, Live>();
const order: number[] = [];

/** AI 응답의 랜드마크 배열({index,x,y,z,visibility}) → 인덱스 순서의 튜플. 33개가 아니면 null. */
export function toTuples(landmarks: Array<{ index: number; x: number; y: number; z: number; visibility: number }> | null | undefined): LandmarkTuple[] | null {
  if (!landmarks || landmarks.length < 33) return null;
  const out: LandmarkTuple[] = new Array(33);
  for (const p of landmarks) {
    if (p.index < 0 || p.index > 32) continue;
    out[p.index] = [round(p.x), round(p.y), round(p.z), Math.round(p.visibility * 1000) / 1000];
  }
  return out.every((p) => p) ? out : null;
}

const round = (v: number) => Math.round(v * 10000) / 10000;

export const motionRecorder = {
  start(sessionId: number, exercise: string, nowMs: number = Date.now()): void {
    if (exercise !== 'squat' && exercise !== 'lunge') return;   // 기준 시범이 있는 두 종목만 기록한다
    sessions.set(sessionId, { exercise, startedAtMs: nowMs, aspect: null, frames: [], reps: 0, lead: null });
    const at = order.indexOf(sessionId);
    if (at >= 0) order.splice(at, 1);
    order.push(sessionId);
    while (order.length > MAX_SESSIONS) sessions.delete(order.shift()!);
  },

  /** 프레임 하나 기록. 좌표가 없는 응답(사람 못 찾음)도 시간 간격을 남기려고 lm=null 로 넣는다. */
  push(sessionId: number, f: {
    atMs: number;
    landmarks?: Array<{ index: number; x: number; y: number; z: number; visibility: number }> | null;
    aspect?: number | null;
    repCount?: number | null;
    lead?: Side | null;
  }): void {
    const s = sessions.get(sessionId);
    if (!s || s.frames.length >= MAX_FRAMES) return;
    if (f.aspect && Number.isFinite(f.aspect) && f.aspect > 0) s.aspect = f.aspect;
    if (typeof f.repCount === 'number' && f.repCount >= s.reps) s.reps = f.repCount;
    if (f.lead) s.lead = f.lead;
    const t = Math.max(0, (f.atMs - s.startedAtMs) / 1000);
    const last = s.frames[s.frames.length - 1];
    if (last && t <= last.t) return;   // 시각은 단조 증가해야 한다(DTW·보간이 가정한다)
    s.frames.push({ t: Math.round(t * 1000) / 1000, lm: toTuples(f.landmarks), reps: s.reps, lead: s.lead });
  },

  get(sessionId: number): MotionRecording | null {
    const s = sessions.get(sessionId);
    if (!s || !s.frames.length) return null;
    return { exercise: s.exercise, aspect: s.aspect ?? DEFAULT_ASPECT, frames: s.frames, source: `session ${sessionId}` };
  },

  /** 기록이 있고 반복이 하나 이상 세어졌는가 — 비교 버튼을 보여 줄지 정한다 */
  hasRepsFor(sessionId: number): boolean {
    const s = sessions.get(sessionId);
    return !!s && s.reps > 0 && s.frames.some((f) => f.lm);
  },

  /** 이미 만들어진 기록을 그대로 넣는다 (시험·개발 도구용 — 앱 흐름은 start/push 를 쓴다). */
  load(sessionId: number, recording: MotionRecording): void {
    const last = recording.frames[recording.frames.length - 1];
    sessions.set(sessionId, {
      exercise: recording.exercise, startedAtMs: 0, aspect: recording.aspect, frames: recording.frames.slice(),
      reps: last ? last.reps : 0, lead: null,
    });
    const at = order.indexOf(sessionId);
    if (at >= 0) order.splice(at, 1);
    order.push(sessionId);
    while (order.length > MAX_SESSIONS) sessions.delete(order.shift()!);
  },

  clear(sessionId?: number): void {
    if (sessionId === undefined) { sessions.clear(); order.length = 0; return; }
    sessions.delete(sessionId);
    const at = order.indexOf(sessionId);
    if (at >= 0) order.splice(at, 1);
  },
};

// 개발 중 브라우저 콘솔에서 기록을 넣어 비교 화면을 시험할 수 있게 한다(운영 번들에서는 __DEV__ 가 false 라 빠진다).
if (typeof __DEV__ !== 'undefined' && __DEV__) {
  (globalThis as unknown as { __motionRecorder?: typeof motionRecorder }).__motionRecorder = motionRecorder;
}
