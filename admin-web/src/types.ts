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
  // 싱크로율 판정 기준(2026-10-21 «하나로 통일»): good 이상 = 정석, warn 미만 = 위험, 사이 = 교정 필요.
  // AI 의 회차 판정·앱의 색/진동이 이 두 값을 쓴다. 페르소나는 피드백 말투만 바꾼다.
  syncGoodThreshold: number;
  syncWarnThreshold: number;
  expectedDurationMinutes: number | null;
  analysisSupported: boolean;
}

/**
 * 기준 동작의 깊이 판정 — 최저 무릎각(minKneeDeg) 기준. 기준이 너무 깊으면(STRICT, < 60°) 대부분 감점되고,
 * 너무 얕으면(LENIENT, > 95°) 얕은 스쿼트도 만점이 된다. 백엔드 AdminReferenceSummaryDto 와 같은 경계.
 */
export type DepthVerdict = 'STRICT' | 'OK' | 'LENIENT';
/** 마지막 기준 등록 시도의 상태. EXTRACTING 은 업로드 영상을 AI 가 읽는 중(10분 넘으면 서버가 FAILED 로 보고) */
export type ExtractionStatus = 'NONE' | 'EXTRACTING' | 'READY' | 'FAILED';

/** 종목의 기준 좌표 요약 (GET /admin/exercises/{id}/reference) */
export interface ReferenceSummary {
  frameCount: number;
  source: 'MANNEQUIN_3D' | 'VIDEO' | null;
  durationSec: number | null;
  minKneeDeg?: number | null;
  minHipDeg?: number | null;
  depthVerdict?: DepthVerdict | null;
  extractionStatus?: ExtractionStatus;
  extractionError?: string | null;
  extractionAt?: ServerDate;
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
