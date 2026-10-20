export interface FriendAttendance {
  memberId: number;
  username: string;
  profileImageUrl: string | null;
  attendedToday: boolean;
  streak: number;
}

export interface WorkoutGroup {
  id: number;
  name: string;
  description: string | null;
  inviteCode: string;
  createdById: number;
  createdAt: string;
}
