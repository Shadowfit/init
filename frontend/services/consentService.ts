import api from './api';
import type { ConsentItem, ConsentStatus } from '@/types/consent';

// 백엔드 ConsentController prefix: /member/consents — 대상은 경로가 아니라 인증된 본인이다.
export const consentService = {
  /** 동의 현황: 현재 문서 버전, 내 이력, 현재 버전에 아직 동의하지 않은 가입 필수 문서. */
  getStatus: () => api.get<ConsentStatus>('/member/consents'),

  /** 동의를 기록한다(멱등). 현재 버전이 아니면 서버가 409 로 거절한다. */
  record: (consents: ConsentItem[]) =>
    api.post<ConsentStatus>('/member/consents', { consents }),
};
