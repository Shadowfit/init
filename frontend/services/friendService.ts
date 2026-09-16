import api from './api';
import type { MemberAttendanceStatus, Notification } from '@/types/social';

// 백엔드 FriendController prefix: /friends — 친구 = 내 모임들의 ACTIVE 멤버 합집합
export const friendService = {
  // 친구 운동 현황 (오늘 완료 → 진행 중 → 기록 없음 순으로 정렬돼 온다)
  getStatuses: () => api.get<MemberAttendanceStatus[]>('/friends'),

  // 재촉하기 — 같은 사람 하루 1회 (409 N002). 오늘 완료한 친구에겐 버튼을 안 보이는 게 프론트 규칙
  nudge: (memberId: number) => api.post<Notification>(`/friends/${memberId}/nudge`),

  // 응원 보내기 — 1~100자, 같은 사람 하루 1회 (409 N004). 재촉과 별개로 센다
  cheer: (memberId: number, message: string) =>
    api.post<Notification>(`/friends/${memberId}/cheer`, { message }),
};
