import api from './api';
import type { FriendAttendance, WorkoutGroup } from '@/types/social';

export const socialService = {
  getFriends: () => api.get<FriendAttendance[]>('/friends'),
  getMyGroups: () => api.get<WorkoutGroup[]>('/groups/mine'),
  createGroup: (name: string) => api.post<WorkoutGroup>('/groups', { name }),
  joinGroup: (inviteCode: string) => api.post<WorkoutGroup>('/groups/join', { inviteCode }),
};
