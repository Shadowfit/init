// GET /recommendations/next-session 응답 (백엔드 NextSessionRecommendationResponseDto).
// 저장된 값이 아니라 매 호출마다 최근 완료 세션 평균과 프로필로 계산한다.
export interface NextSessionRecommendation {
  /** 1~10. 프로필 기반 시작 단계에서 최근 3세션 평균으로 한 단계 조정한 값 */
  difficultyLevel: number;
  /** 세트당 목표 횟수 */
  targetReps: number;
  /** 목표 싱크로율(%) — BigDecimal 이라 JSON 에서는 숫자(80, 82.5 등)로 온다 */
  targetSyncRate: number;
  /** 세트 사이 휴식(초) */
  restTimeSec: number;
  /** 왜 이 값인지 — 서버가 만든 한국어 한 줄. 그대로 보여 준다 */
  reason: string;
}
