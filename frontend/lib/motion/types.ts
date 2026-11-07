// 3D 운동 비교(기준 시범 모션 ↔ 사용자 모션) 공용 타입.
// 이 폴더(lib/motion)는 React Native 에 의존하지 않는 순수 TS 다 — node 로 그대로 시험한다(run-tests.cjs).

export type MotionExercise = 'squat' | 'lunge';
export type Side = 'left' | 'right';
export type Vec3 = [number, number, number];

/** MediaPipe Pose 랜드마크 인덱스 (필요한 것만). 'left' 는 촬영된 사람 본인의 왼쪽이다. */
export const LM = {
  LEFT_SHOULDER: 11, RIGHT_SHOULDER: 12,
  LEFT_HIP: 23, RIGHT_HIP: 24,
  LEFT_KNEE: 25, RIGHT_KNEE: 26,
  LEFT_ANKLE: 27, RIGHT_ANKLE: 28,
  LEFT_HEEL: 29, RIGHT_HEEL: 30,
  LEFT_FOOT: 31, RIGHT_FOOT: 32,
} as const;

/** 랜드마크 한 점: [x, y, z, visibility]. x=너비 기준 0~1, y=높이 기준 0~1(아래로 증가). */
export type LandmarkTuple = [number, number, number, number];

/** 운동 중 한 프레임의 기록 (AI 응답에서 그대로 옮긴다). lm 이 null 이면 사람을 못 찾은 프레임. */
export interface RecordedFrame {
  /** 세션 시작 기준 경과 초 (앱이 프레임 도착 시각으로 만든다) */
  t: number;
  lm: LandmarkTuple[] | null;
  /** 이 프레임까지 AI 가 센 누적 횟수 */
  reps: number;
  /** 런지에서 AI 가 인식한 앞다리 */
  lead: Side | null;
}

export interface MotionRecording {
  exercise: MotionExercise;
  source?: string;
  /** 이미지 너비/높이. x 를 이 값으로 곱해야 y 와 같은 길이 단위가 된다. */
  aspect: number;
  frames: RecordedFrame[];
}

/** 3D 관절 위치 (미터, y 위쪽, z 앞쪽, x 는 사람의 왼쪽이 +). 기준 모션(마네킹)에서 읽는다. */
export interface Joints3D {
  lShoulder: Vec3; rShoulder: Vec3;
  lHip: Vec3; rHip: Vec3;
  lKnee: Vec3; rKnee: Vec3;
  lAnkle: Vec3; rAnkle: Vec3;
}
export type JointName = keyof Joints3D;
export const JOINT_NAMES: JointName[] = [
  'lShoulder', 'rShoulder', 'lHip', 'rHip', 'lKnee', 'rKnee', 'lAnkle', 'rAnkle',
];

/** 동작 구간. 기준 모션의 단계 이름이 이 다섯 개로 묶인다. */
export type SegmentId = 'start' | 'descend' | 'bottom' | 'ascend' | 'end';
export const SEGMENT_LABEL: Record<SegmentId, string> = {
  start: '시작', descend: '내려가는', bottom: '최저점', ascend: '올라오는', end: '마무리',
};

/** 한 프레임에서 잰 각도·비율 특징. null 은 그 쪽 다리가 안 보였다는 뜻. */
export interface FrameFeatures {
  t: number;
  kneeL: number; kneeR: number;
  hipL: number; hipR: number;
  /** 몸통 기울기(도). 수직에서 앞으로 숙인 각도, 0 = 직립 */
  trunk: number;
  /** 엉덩이 높이 = (엉덩이 중점 − 발목 중점) / 몸통 길이. 길이 단위와 무관하지만 다리/몸통 비율은 사람마다 다르다 */
  hipH: number;
  /**
   * 엉덩이가 내려간 정도 = 1 − hipH / (그 사람이 서 있을 때의 hipH). 0 = 서 있음, 클수록 낮게 앉음.
   * 다리·몸통 비율과 카메라 높이에 따른 차이가 «서 있을 때의 값» 으로 나뉘어 사라진다. 기준에서는 prepareReference,
   * 사용자에서는 compareRecording 이 채운다(그 전에는 NaN).
   */
  drop: number;
  /**
   * 상체를 «서 있을 때보다» 더 숙인 각도(도) = trunk − (그 사람의 서 있을 때 trunk). 같은 이유로 기준과 사용자 모두
   * 자기 서 있는 자세를 기준선으로 삼는다 — 촬영 방향·카메라 높이 때문에 서 있어도 몇 도 기울어 보이는 값을 뺀다.
   */
  lean: number;
}

export interface ReferenceSample {
  /** 시범 시간(초) — 장면의 시계 그대로 */
  t: number;
  phase: string;
  joints: Joints3D;
}

/** 시범 모션 한 번(반복 1회)의 관절 시계열. 마네킹 장면이 자기 자세 계산 그대로 뽑아 준다. */
export interface ReferenceCycle {
  exercise: MotionExercise;
  /** 런지만: 앞다리 */
  lead: Side | null;
  /** 샘플 간격(초) */
  dt: number;
  /** 이 사이클이 장면 시계에서 시작하는 시각(초). 런지 왼발은 오른발 반 주기 뒤에서 시작한다. 없으면 0(런지 왼발은 4.8) */
  t0?: number;
  samples: ReferenceSample[];
  /** 장면이 쓰는 모델 치수(m) — 사용자 골격을 같은 크기로 맞추는 데 쓴다 */
  torso: number;
  ankleY: number;
}
