// 백엔드 dto/report/* 와 1:1 매칭되는 프론트 타입.
// 필드명/형태는 Spring DTO 기준 (camelCase JSON 직렬화 그대로).
import type { RejectReason } from './pose';

/* ── 달력 (record/CalendarMainResponseDto, CalendarDayDto) ── */
export interface CalendarDay {
  date: string;                    // "2026-06-04"
  hasRecord: boolean;              // 운동 기록 존재 여부 (점 표시용)
  dailyAvgSyncRate: number | null; // 그 날 평균 싱크로율
}

export interface CalendarMainResponse {
  monthlyExerciseDays: number;     // 이번 달 운동 일수
  totalAvgSyncRate: number;        // 이번 달 평균 싱크로율
  consecutiveDays: number;         // 연속 운동 일수
  year: number;
  month: number;
  records: CalendarDay[];
}

/* ── 주간 활동 (record/WeeklyActivityResponseDto 등) ── */
export interface DailyLogSummary {
  dayOfWeek: string;               // "월", "화" ...
  workoutMinutes: number;          // 그 요일 운동 시간 (막대 높이)
  isToday: boolean;
}

export interface ExerciseSession {
  sessionId: number;               // 클릭 시 상세 이동용
  exerciseName: string;            // "스쿼트"
  setSummary: string;              // "1세트 x 12회"
  syncRate: number;
  scoreAvailable?: boolean;
}

export interface WeeklyActivityResponse {
  dateRange: string;               // "6월 2일 - 8일"
  totalWorkouts: number;
  totalMinutes: number;
  totalCalories: number;
  dailyLogs: DailyLogSummary[];
  todayDetails: ExerciseSession[];
}

/* ── 특정 날짜 운동 목록 (record/DailyActivityResponseDto) ── */
export interface DailyActivityResponse {
  date: string;
  totalWorkouts: number;
  sessions: ExerciseSession[];
}

/* ── 세션 상세 보고서 (detailreport/*) ── */
export interface WorstSection {
  repNumber: number;               // 2 — repTrend 의 어느 점이 worst 인지 잇는 키
  exerciseName: string;
  timeStamp: string;               // "01:15" — 그 회차의 중앙 프레임
  /**
   * "2회차 · 싱크로율 75%"
   *
   * ⚠️ 문구는 잠정이다(이슈 #80). 회차를 알아야 하면 이 문자열을 파싱하지 말고
   * repNumber 를 쓸 것 — 문구가 확정되면 파싱은 깨진다.
   */
  reason: string;
}

/**
 * 회차(rep) 하나의 싱크로율 — 추이 그래프의 한 점.
 * 2026-10-21 부터 «미인정 시도» 도 같은 목록에 시간순으로 섞여 온다(counted=false, repNumber=null, attemptNumber=k).
 * 구버전 서버·옛 리포트는 counted 가 없다 → 인정 회차로 본다.
 */
export interface RepSyncRate {
  repNumber: number | null;        // 1부터, 세션 전체 기준 연번. 미인정 시도는 null
  syncRate: number;                // 소수 1자리
  timeStamp: string;               // "01:15" — 그 회차의 중앙 프레임
  counted?: boolean;
  attemptNumber?: number | null;   // 미인정 시도 번호(1부터)
  rejectReason?: RejectReason | null;
  timestampSec?: number | null;
}

/** 인정 회차인가 — counted 가 없으면(구버전) 인정으로 본다 */
export const isCounted = (r: { counted?: boolean }) => r.counted !== false;

export interface ExerciseSyncRate {
  exerciseId: number;
  name: string;
  setInfo: string;                 // "1세트 x 12회"
  syncRate: number;
}

export interface ComparisonWithPrevious {
  syncRateDiff: number;
  workoutMinutesDiff: number;
  // 둘 중 한쪽이라도 칼로리를 못 잰 세션(체중 미입력)이면 null
  caloriesDiff: number | null;
}

/* ── 스쿼트 깊이 모드 (2026-09-21) ── */
export type SquatDepthMode = 'FULL' | 'HALF';
export type SquatDepthGrade = 'EXCELLENT' | 'GOOD' | 'FAIR' | 'NEEDS_WORK' | 'POOR';

export const DEPTH_GRADE_LABEL: Record<SquatDepthGrade, string> = {
  EXCELLENT: '매우 정확해요',
  GOOD: '좋아요',
  FAIR: '양호해요',
  NEEDS_WORK: '조금 더 신경 써보세요',
  POOR: '깊이를 다시 확인해보세요',
};

/** 회차(rep) 하나의 깊이 정확도 — RepSyncRate의 깊이 버전. 미인정 시도도 counted=false 로 섞여 온다 */
export interface RepDepth {
  repNumber: number | null;
  counted?: boolean;
  attemptNumber?: number | null;
  minKneeAngle: number;
  depthScore: number;              // 0-100
  timeStamp: string;                // "01:15" — 가장 깊었던 프레임
}

/**
 * 스쿼트 깊이 정확도 리포트. 런지 세션·구버전 세션·깊이 데이터가 없는 세션은 null —
 * scoreAvailable 처럼 화면에서 이 필드로 카드 표시 여부를 결정한다.
 *
 * ⚠️ HALF 모드의 목표 밴드(115~140도)는 실촬영 하프 스쿼트 영상으로 검증되기 전까지 잠정값이다
 * (docs/decisions/squat-repetition-counting.md 2026-09-21 절 참고).
 */
export interface SquatDepthAnalysis {
  depthMode: SquatDepthMode;
  /**
   * FULL 모드 만점 기준 — 이 무릎 각도 이하로 앉으면 그 회차 100점, 1° 얕을 때마다 4점 감점(2026-10-21).
   * 기준 동작(3D 시범)의 최저 무릎 + 허용 5° 라 싱크로율과 같은 잣대다. HALF·구버전 서버는 null/없음.
   */
  targetKneeDeg?: number | null;
  depthScore: number;              // 세션 평균, 0-100
  depthGrade: SquatDepthGrade;
  repDepths: RepDepth[];
  narrative: string[];             // 회차별 한국어 코멘트, 최대 4문장
}

/* ── 런지 깊이 정확도 (2026-09-27) ── */
// 스쿼트와 등급 라벨을 공유한다 — 채점 공식(100도 목표선, FULL과 같은 모양)이 같아서다
// (backend LungeDepthAnalysisCalculator 참고).
export type LungeDepthGrade = SquatDepthGrade;

/** 회차(rep) 하나의 깊이 정확도 — RepDepth의 런지 버전. side가 추가된 것만 다르다. */
export interface LungeRepDepth {
  repNumber: number;
  side: 'left' | 'right' | null;
  minKneeAngle: number;
  depthScore: number;              // 0-100
  timeStamp: string;                // "01:15" — 가장 깊었던 프레임
}

/**
 * 런지 깊이 정확도 리포트. 스쿼트 세션·구버전 세션(좌우 구분 데이터가 없는 V24 이전 런지)은
 * null — scoreAvailable과 같은 널 가드 규약이다.
 *
 * leftAvgKneeAngle/rightAvgKneeAngle은 그 다리로 시작한 회차가 하나도 없으면 null이다
 * (예: 한쪽 다리로만 촬영된 짧은 세션).
 */
export interface LungeDepthAnalysis {
  depthScore: number;              // 세션 평균, 0-100
  depthGrade: LungeDepthGrade;
  leftAvgKneeAngle: number | null;
  rightAvgKneeAngle: number | null;
  repDepths: LungeRepDepth[];
  narrative: string[];             // 회차별 한국어 코멘트, 최대 4문장
}

/* ── 체간(몸통) 기울기 정확도 (2026-09-28) ── */
// 스쿼트·런지 공용 셰이프 — 백엔드 TrunkLeanAnalysisDto와 1:1 대응. 좌우/모드 구분이 있는
// 깊이 타입과 달리 이 축은 두 운동이 같은 계산(rep 평균 체간 기울기)을 쓴다.
export type TrunkLeanGrade = SquatDepthGrade; // 등급 5단계 이름을 그대로 공유

/** 회차(rep) 하나의 체간 자세 정확도. 미인정 시도도 counted=false 로 섞여 온다 */
export interface RepTrunkLean {
  repNumber: number | null;
  counted?: boolean;
  attemptNumber?: number | null;
  avgTiltDeg: number;
  tiltScore: number;                // 0-100
  timeStamp: string;                 // "01:15" — 그 rep의 첫 프레임 기준
}

/**
 * 체간 기울기 정확도 리포트. 해당 종목이 아니거나 구버전 세션(V25 이전)이면 null —
 * scoreAvailable과 같은 널 가드 규약이다. 런지 임계값은 스쿼트보다 관대하고 실촬영 검증 전
 * 잠정값이다(backend LungeTrunkLeanAnalysisCalculator 참고) — 화면 문구에서 단정적으로
 * 표현하지 않는다.
 */
export interface TrunkLeanAnalysis {
  tiltScore: number;                // 세션 평균, 0-100
  tiltGrade: TrunkLeanGrade;
  repTrunkLeans: RepTrunkLean[];
  narrative: string[];              // 회차별 한국어 코멘트, 최대 4문장
}

/* ── 런지 보폭 정확도 (2026-09-28) ── */
// 런지 전용 — 스쿼트는 보폭 개념이 없어 대응 타입이 없다.
export type StepLengthGrade = SquatDepthGrade;

/** 회차(rep) 하나의 보폭 정확도. */
export interface RepStepLength {
  repNumber: number;
  staggerRatio: number;
  stepScore: number;                // 0-100
  timeStamp: string;                 // "01:15"
}

/**
 * 런지 보폭 정확도 리포트. 스쿼트 세션·구버전 세션(V26 이전)·보폭 데이터가 없는 세션은
 * null. ⚠️ 임계값(1.0/0.6)은 실촬영 3개 관찰 + 문헌 근거의 잠정값이다(backend
 * LungeStepLengthAnalysisCalculator 참고) — 화면 문구에서 단정적으로 표현하지 않는다.
 */
export interface LungeStepLengthAnalysis {
  stepScore: number;                // 세션 평균, 0-100
  stepGrade: StepLengthGrade;
  repStepLengths: RepStepLength[];
  narrative: string[];              // 회차별 한국어 코멘트, 최대 4문장
}

/** 세트별 요약 (V26 세션 세트). 세트 도입 전 세션은 빈 배열 */
export interface SessionSet {
  setNo: number;
  reps: number;
  avgSyncRate: number | null;     // null = 채점된 rep 이 없는 세트(횟수만 세는 종목, V33)
  startedSec: number;
  endedSec: number;
}

export interface SessionReportResponse {
  sessionId: number;
  startTime: string;               // "2026-10-06T11:40:33" — 헤더 날짜
  // 이 종목의 싱크로율 판정 기준(정석·위험) — 색 구분에 쓴다. 구버전 서버는 없음 → 80/60
  syncGoodThreshold?: number | null;
  syncWarnThreshold?: number | null;
  sets: SessionSet[];
  // 평균 싱크로율 — 2026-10-21 부터 인정 회차 + 미인정 시도 «모든 시도» 평균
  avgSyncRate: number;
  scoreAvailable?: boolean;
  totalReps: number;               // 인정 횟수(세트·목표의 기준)
  // 미인정 시도 수와 사유별 개수(2026-10-21). 구버전 서버·옛 리포트는 없음 → 0
  rejectedReps?: number | null;
  rejectedRepsByReason?: Partial<Record<RejectReason, number>> | null;
  workoutMinutes: number;
  // 추정 소모 칼로리(kcal) — 백엔드가 체중 × 운동 시간 × MET 3.0 으로 계산(2026-10-21). 체중을 모르면 null
  caloriesBurned: number | null;
  aiSafetyReport: string | null;
  worstSection: WorstSection | null;
  /**
   * 회차별 싱크로율 추이 (rep 오름차순). 측정된 회차가 없으면 빈 배열.
   *
   * syncRateDetails 는 이름과 달리 세션당 한 줄(운동 종목 단위 요약)이라 회차 흐름을 볼 수 없다.
   * worst 점은 repTrend.find(r => r.repNumber === worstSection?.repNumber) 로 찾는다.
   */
  repTrend: RepSyncRate[];
  squatDepthAnalysis: SquatDepthAnalysis | null;
  lungeDepthAnalysis: LungeDepthAnalysis | null;
  squatTrunkLeanAnalysis: TrunkLeanAnalysis | null;
  lungeTrunkLeanAnalysis: TrunkLeanAnalysis | null;
  lungeStepLengthAnalysis: LungeStepLengthAnalysis | null;
  syncRateDetails: ExerciseSyncRate[];
  comparisonWithPrevious: ComparisonWithPrevious | null;
}
