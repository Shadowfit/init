import api from './api';
import type { NextSessionRecommendation } from '@/types/recommendation';

export const recommendationService = {
  // 서버는 스쿼트 기준으로만 계산한다(RecommendationService.SQUAT_EXERCISE_ID). 종목 파라미터가 아직 없다.
  getNextSession: () => api.get<NextSessionRecommendation>('/recommendations/next-session'),
};
