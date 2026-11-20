import api from './api';
import type { WorkoutGoal } from '@/types/goal';

export const goalService = {
  getGoals: () => api.get<WorkoutGoal[]>('/goals'),
  createWeeklySessionsGoal: (targetValue: number) =>
    api.post<WorkoutGoal>('/goals', { goalType: 'WEEKLY_SESSIONS', targetValue }),
  updateGoal: (goalId: number, targetValue: number) =>
    api.patch<WorkoutGoal>(`/goals/${goalId}`, { targetValue }),
};
