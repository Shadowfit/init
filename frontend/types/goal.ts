export interface WorkoutGoal {
  id: number;
  goalType: 'WEEKLY_SESSIONS' | 'WEEKLY_MINUTES';
  targetValue: number;
  currentValue: number;
  status: 'IN_PROGRESS' | 'ACHIEVED';
  createdAt: string;
}
