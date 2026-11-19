// 백엔드 동의 API (com.shadowfit.dto.consent, ConsentType) — GET·POST /member/consents

export type ConsentType = 'TERMS' | 'PRIVACY' | 'HEALTH_INFO';

/** 동의 한 건 — «이 종류의 문서를 이 버전으로 동의한다». */
export interface ConsentItem {
  type: ConsentType;
  version: string;
}

export interface ConsentStatus {
  /** 서버가 동의를 강제하는 중인가. 꺼져 있으면 기록만 한다. */
  enforced: boolean;
  /** 종류별 현재 문서 버전. */
  current: Record<ConsentType, string>;
  /** 이 회원의 동의 이력(개정 전 버전 포함). */
  agreed: { type: ConsentType; version: string; agreedAt: string }[];
  /** 가입 필수 문서 중 현재 버전에 아직 동의하지 않은 것. */
  missing: ConsentType[];
}
