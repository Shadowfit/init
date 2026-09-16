// 백엔드 dto/group/*, dto/notification/* 와 1:1 매칭되는 프론트 타입.
// 계약 원본: docs/07-api-design.md «모임·소셜 API», docs/tasks/35-frontend-api-handoff.md

/* ── 친구/구성원 현황 (MemberAttendanceStatusDto) ── */
export interface MemberAttendanceStatus {
  memberId: number;
  username: string;
  profileImageUrl: string | null;
  attendedToday: boolean;          // 오늘 COMPLETED 세션이 있는가
  streak: number;                  // 연속 운동 일수
}

/* ── 알림 (NotificationDto) ── */
export type NotificationType = 'NUDGE' | 'CHEER';

export interface Notification {
  id: number;
  type: NotificationType;
  senderId: number | null;         // 보낸 사람 탈퇴 시 null
  senderUsername: string | null;
  senderProfileImageUrl: string | null;
  targetDate: string;              // "2026-09-17" — 하루 1회 판정 날짜
  message: string | null;          // CHEER 본문. NUDGE 는 null
  read: boolean;
  readAt: string | null;
  createdAt: string;               // "2026-09-17T08:00:00"
}

// dto/common/PageResponse
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/* ── 모임 (GroupResponseDto, GroupDetailResponseDto, GroupMemberResponseDto) ── */
export type GroupRole = 'OWNER' | 'MEMBER';
export type GroupMemberStatus = 'ACTIVE' | 'LEFT';

export interface Group {
  id: number;
  name: string;
  description: string | null;
  inviteCode: string;
  createdById: number;
  createdAt: string;
}

export interface GroupMember {
  memberId: number;
  username: string;
  role: GroupRole;
  status: GroupMemberStatus;
  joinedAt: string;
}

export interface GroupDetail {
  id: number;
  name: string;
  description: string | null;
  inviteCode: string;
  createdAt: string;
  members: GroupMember[];
}

export interface CreateGroupRequest {
  name: string;
  description?: string;
}

export interface InviteCodeResponse {
  inviteCode: string;
}

/* ── 초대 (InvitationResponseDto) ── */
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED';

export interface Invitation {
  id: number;
  groupId: number;
  groupName: string;
  inviterId: number;
  status: InvitationStatus;
  createdAt: string;
}

/* ── 출석 캘린더 (GroupAttendanceCalendarDto) ── */
export interface AttendanceDay {
  date: string;                    // "2026-09-03"
  attendedCount: number;           // 그 날 COMPLETED 세션이 있는 ACTIVE 멤버 수
}

export interface GroupAttendanceCalendar {
  year: number;
  month: number;
  activeMemberCount: number;       // 농도의 분모
  days: AttendanceDay[];
}

/* ── 피드 (GroupFeedResponseDto, ReactionSummaryDto) ── */
export type ReactionKind = 'HEART' | 'FIRE';

export interface ReactionSummary {
  reactions: Partial<Record<ReactionKind, number>>;
  myReactions: ReactionKind[];
}

export interface GroupFeedItem {
  seq: number;
  groupId: number;
  type: string;                    // SESSION_COMPLETED | MEMBER_JOINED | 그 외(무시)
  senderId: number | null;
  payload: string;                 // ⚠️ JSON **문자열** — parseFeedPayload 로 풀어서 쓴다
  occurredAt: string;
  reactionSummary: ReactionSummary;
}

export interface GroupFeedResponse {
  items: GroupFeedItem[];
  nextBeforeSeq: number | null;    // null 이면 끝
}

// 서버가 만드는 두 타입의 payload 모양
export interface SessionCompletedPayload {
  sessionId: number;
  memberId: number;
  username: string;
  exerciseName: string;
}

export interface MemberJoinedPayload {
  memberId: number;
  username: string;
}

export type FeedPayload =
  | { type: 'SESSION_COMPLETED'; data: SessionCompletedPayload }
  | { type: 'MEMBER_JOINED'; data: MemberJoinedPayload }
  | { type: 'UNKNOWN' };

// 모르는 type·깨진 JSON 은 UNKNOWN 으로 접는다 — 소켓으로 보낸 임의 type 도 같은 표에 쌓이기 때문(핸드오프 문서).
export function parseFeedPayload(item: GroupFeedItem): FeedPayload {
  try {
    const data = JSON.parse(item.payload);
    if (item.type === 'SESSION_COMPLETED') return { type: 'SESSION_COMPLETED', data };
    if (item.type === 'MEMBER_JOINED') return { type: 'MEMBER_JOINED', data };
  } catch {
    // payload 가 JSON 이 아니면 아래 UNKNOWN
  }
  return { type: 'UNKNOWN' };
}
