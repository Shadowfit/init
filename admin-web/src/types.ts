// 백엔드 dto/admin/* 와 1:1. 날짜는 API 마다 문자열 또는 배열로 온다 — ServerDate(format.ts) 참고.
import type { ServerDate } from './format';

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type Role = 'USER' | 'ADMIN' | 'TRAINER';
export type Persona = 'BEGINNER' | 'ADVANCED' | 'DIET' | 'REHAB';
export type WorkoutLevel = 'STARTER' | 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED' | 'EXPERT';
export type SessionStatus = 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'FAILED';

export interface StatsOverview {
  baseDate: ServerDate;
  todaySessionCount: number;
  sessionCountByStatus: Partial<Record<SessionStatus, number>>;
  todayAverageSyncRate: number | null;
  todayNewMemberCount: number;
  activeMemberCount: number;
  activeMemberWindowDays: number;
}

export interface DailyStat {
  date: ServerDate;
  sessionCount: number;
  completedCount: number;
  averageSyncRate: number | null;
  newMemberCount: number;
}

export interface CommunityStats {
  groupCount: number;
  activeMembershipCount: number;
  sharedPostCount: number;
  cheerCount: number;
  todayNudgeCount: number;
}

export interface MemberListItem {
  id: number;
  username: string;
  email: string;
  selectedPersona: Persona;
  workoutLevel: WorkoutLevel | null;
  onboardingCompleted: boolean;
  createdAt: ServerDate;
}

export interface MemberDetail {
  id: number;
  username: string;
  email: string;
  role: Role;
  sex: 'MALE' | 'FEMALE' | 'NONE' | null;
  selectedPersona: Persona;
  workoutLevel: WorkoutLevel | null;
  height: number | null;
  weight: number | null;
  onboardingCompleted: boolean;
  createdAt: ServerDate;
  sessionCount: number;
  completedSessionCount: number;
  averageSyncRate: number | null;
  lastSessionAt: ServerDate;
  groups: { groupId: number; name: string; role: 'OWNER' | 'MEMBER' }[];
  recentSessions: { id: number; exerciseName: string; status: SessionStatus; startTime: ServerDate; totalReps: number | null; avgSyncRate: number | null }[];
}

export interface SessionListItem {
  id: number;
  memberId: number;
  username: string;
  exerciseId: number;
  exerciseName: string;
  status: SessionStatus;
  startTime: ServerDate;
  endTime: ServerDate;
  totalReps: number | null;
  avgSyncRate: number | null;
}

export interface Category {
  id: number;
  name: string;
  createdAt: ServerDate;
}

export interface ExerciseListItem {
  id: number;
  name: string;
  code: string | null;
  categoryId: number;
  categoryName: string;
  analysisSupported: boolean;
  expectedDurationMinutes: number | null;
  createdAt: ServerDate;
}

export interface ExerciseDetail {
  id: number;
  name: string;
  code: string | null;
  categoryId: number;
  categoryName: string;
  description: string | null;
  preferredUrl: string | null;
  referenceVideoPath: string | null;
  targetJoints: string | null;
  syncThresholdBeginner: number | null;
  syncThresholdAdvanced: number | null;
  syncThresholdDiet: number | null;
  syncThresholdRehab: number | null;
  expectedDurationMinutes: number | null;
  analysisSupported: boolean;
}

export interface GroupListItem {
  id: number;
  name: string;
  description: string | null;
  inviteCode: string;
  ownerUsername: string | null;
  activeMemberCount: number;
  sharedPostCount: number;
  createdAt: ServerDate;
  lastActivityAt: ServerDate;
}

export interface GroupDetail {
  summary: GroupListItem;
  members: { memberId: number; username: string; role: 'OWNER' | 'MEMBER'; status: 'ACTIVE' | 'LEFT'; joinedAt: ServerDate }[];
}

export interface FeedPost {
  postId: number;
  groupId: number;
  groupName: string;
  seq: number;
  authorId: number | null;
  authorUsername: string | null;
  exerciseName: string | null;
  totalReps: number | null;
  caption: string | null;
  photoUrl: string | null;
  cheerCount: number;
  createdAt: ServerDate;
}
