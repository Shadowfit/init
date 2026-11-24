import api from './api';
import type { WorkoutGoal } from '@/types/goal';

export const goalService = {
  getGoals: () => api.get<WorkoutGoal[]>('/goals'),
  createWeeklySessionsGoal: (targetValue: number) =>
    api.post<WorkoutGoal>('/goals', { goalType: 'WEEKLY_SESSIONS', targetValue }),
  updateGoal: (goalId: number, targetValue: number) =>
    api.patch<WorkoutGoal>(`/goals/${goalId}`, { targetValue }),
  // 목표 삭제 (DELETE /goals/{id} → 204). 본인 목표가 아니거나 이미 없으면 404.
  deleteGoal: (goalId: number) => api.delete<void>(`/goals/${goalId}`),
};
