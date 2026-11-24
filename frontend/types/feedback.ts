// 백엔드 model/exercise/FeedbackType(8종) + dto/exercises/feedback/* 매칭

export type FeedbackType =
  | 'KNEE_OUT'
  | 'KNEE_IN'
  | 'HIP_LOW'
  | 'HIP_HIGH'
  | 'BACK_BENT'
  | 'SHOULDER_TILT'
  | 'ELBOW_BENT'
  | 'HEAD_DOWN';

// 자세 결함 한글 라벨 (운동 화면 토스트 / 보고서 집계 공용)
export const FEEDBACK_TYPE_LABEL: Record<FeedbackType, string> = {
  KNEE_OUT: '무릎이 발끝보다 나감',
  KNEE_IN: '무릎이 안쪽으로 모임',
  HIP_LOW: '엉덩이 처짐',
  HIP_HIGH: '엉덩이 과도하게 들림',
  BACK_BENT: '등 굽음',
  SHOULDER_TILT: '어깨 비대칭',
  ELBOW_BENT: '팔꿈치 굽음',
  HEAD_DOWN: '고개 숙임',
};

// GET /exercises/{id}/feedback-templates (FeedbackTemplateDto)
export interface FeedbackTemplate {
  feedbackType: FeedbackType;
  message: string;
  priority: number;
}

// GET /sessions/{id}/feedback-summary (SessionFeedbackSummaryDto)
export interface FeedbackTypeBucket {
  feedbackType: FeedbackType;
  count: number;
  avgSyncRate: number;
  minSyncRate: number;
  maxSyncRate: number;
}

// GET /sessions/{id}/feedbacks (SessionFeedbackEventDto) — 결함이 «언제» 났는지. 집계(위)와 달리 이벤트 하나하나다.
export interface SessionFeedbackEvent {
  id: number;
  feedbackType: FeedbackType;
  // 그 순간의 싱크로율. 점수를 못 낸 세션(측정 안 됨)이면 null 일 수 있다
  syncRateAtTrigger: number | null;
  // "2026-10-06T11:40:33" — 타임존 없는 로컬 시각
  occurredAt: string;
}

export interface SessionFeedbackSummary {
  sessionId: number;
  totalCount: number;
  byType: FeedbackTypeBucket[];
}
