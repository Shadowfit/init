import api from './api';
import type {
  CreateGroupRequest,
  Group,
  GroupAttendanceCalendar,
  GroupDetail,
  GroupFeedResponse,
  Invitation,
  InviteCodeResponse,
  MemberAttendanceStatus,
  ReactionKind,
  ReactionSummary,
} from '@/types/social';

// 백엔드 GroupController / GroupFeedController / GroupInvitationController
export const groupService = {
  // 모임 만들기 — 생성자가 OWNER, 응답에 inviteCode(8자)
  create: (data: CreateGroupRequest) => api.post<Group>('/groups', data),

  // 초대 코드로 참여 — 승인 없이 바로 ACTIVE. 없는 코드 404 G008, 이미 멤버 409 G003
  join: (inviteCode: string) => api.post<Group>('/groups/join', { inviteCode }),

  // 내가 ACTIVE 인 모임 목록
  listMine: () => api.get<Group[]>('/groups/mine'),

  // 상세 (members[] 포함). 멤버 아니면 403
  getDetail: (groupId: number) => api.get<GroupDetail>(`/groups/${groupId}`),

  // 구성원 운동 현황 — /friends 와 같은 항목, 이 모임 하나로 한정
  getMemberStatuses: (groupId: number) =>
    api.get<MemberAttendanceStatus[]>(`/groups/${groupId}/members/status`),

  // 출석 캘린더 — 날짜별 attendedCount / activeMemberCount. 칸 농도는 프론트가 계산
  getAttendance: (groupId: number, year: number, month: number) =>
    api.get<GroupAttendanceCalendar>(`/groups/${groupId}/attendance`, { params: { year, month } }),

  // 초대 코드 재발급 — OWNER 만 (403 G007). 이전 코드 즉시 무효
  regenerateInviteCode: (groupId: number) =>
    api.post<InviteCodeResponse>(`/groups/${groupId}/invite-code`),

  // 그룹장 양도 — OWNER 가 탈퇴하려면 먼저 해야 한다 (409 G010)
  transferOwnership: (groupId: number, memberId: number) =>
    api.put<void>(`/groups/${groupId}/owner`, { memberId }),

  // 탈퇴 — 남긴 글·리액션은 남는다
  leave: (groupId: number) => api.delete<void>(`/groups/${groupId}/members/me`),

  // 피드 — 최신순 keyset. beforeSeq 생략 = 최신부터, 응답 nextBeforeSeq 가 null 이면 끝
  getFeed: (groupId: number, beforeSeq?: number | null, size = 20) =>
    api.get<GroupFeedResponse>(`/groups/${groupId}/feed`, {
      params: { size, ...(beforeSeq != null ? { beforeSeq } : {}) },
    }),

  // 리액션 달기/취소 — 둘 다 멱등 200, 응답은 갱신된 요약
  addReaction: (groupId: number, seq: number, kind: ReactionKind) =>
    api.put<ReactionSummary>(`/groups/${groupId}/events/${seq}/reactions/${kind}`),
  removeReaction: (groupId: number, seq: number, kind: ReactionKind) =>
    api.delete<ReactionSummary>(`/groups/${groupId}/events/${seq}/reactions/${kind}`),

  // 초대
  invite: (groupId: number, inviteeId: number) =>
    api.post<Invitation>(`/groups/${groupId}/invitations`, { inviteeId }),
  listMyInvitations: () => api.get<Invitation[]>('/invitations/mine'),
  acceptInvitation: (invitationId: number) => api.post<void>(`/invitations/${invitationId}/accept`),
  declineInvitation: (invitationId: number) => api.post<void>(`/invitations/${invitationId}/decline`),
};
