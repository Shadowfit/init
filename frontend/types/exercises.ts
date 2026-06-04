// 백엔드 com.shadowfit.dto.exercises.* 매핑

// 백엔드 Status enum
export type ExerciseSessionStatus =
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'FAILED';

// ─── 세션 시작 ─────────────────────────────────────
// 백엔드 VideoRequestDto
export interface StartSessionRequest {
  exerciseId: number;
}

// 백엔드 ExercisesResponseDto
export interface StartSessionResponse {
  sessionId: number;
  exerciseId: number;
  startTime: string; // "yyyy-MM-dd'T'HH:mm:ss"
  status: ExerciseSessionStatus;
}
