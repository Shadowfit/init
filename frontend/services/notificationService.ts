import api from './api';
import type { Notification, PageResponse } from '@/types/social';

// 백엔드 NotificationController prefix: /notifications
export const notificationService = {
  // 내 알림함 — 최신순, size 기본 20·최대 100
  list: (page = 0, size = 20) =>
    api.get<PageResponse<Notification>>('/notifications', { params: { page, size } }),

  // 건별 읽음 처리 («모두 읽음» API 는 없다)
  markRead: (notificationId: number) =>
    api.patch<Notification>(`/notifications/${notificationId}/read`),
};
