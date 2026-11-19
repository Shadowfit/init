import type { ConsentType } from '@/types/consent';

/**
 * 앱이 «동의했다» 고 보내는 문서 버전.
 *
 * 🔴 서버의 `consent.version.*`(backend application.yml)와 **같아야 한다.** 다르면 서버가 409(U006)로 거절한다 —
 * 가입은 로그인 전이라 서버의 현재 버전을 물어볼 수 없어서 앱이 이 값을 들고 있다. 문서를 개정해 서버 버전을 올릴 때
 * 이 값도 같이 올리고 앱을 배포해야 한다(그때까지 옛 앱의 가입은 «약관이 개정되었습니다» 로 거절된다).
 * 로그인 뒤의 재동의는 `GET /member/consents` 의 `current` 를 쓴다.
 */
export const CONSENT_VERSION: Record<ConsentType, string> = {
  TERMS: 'v1',
  PRIVACY: 'v1',
  HEALTH_INFO: 'v1',
};

export type LegalDocument = 'terms' | 'privacy';

/**
 * 약관·처리방침을 볼 주소. 문서를 게시할 도메인이 아직 없어서(법률 검토 전 초안, docs/legal) 빌드 환경변수로 받는다.
 * 비어 있으면 앱은 «아직 게시되지 않았어요» 를 보여 준다 — 없는 주소를 지어내 열지 않는다.
 */
export const LEGAL_URL: Record<LegalDocument, string | undefined> = {
  terms: process.env.EXPO_PUBLIC_TERMS_URL || undefined,
  privacy: process.env.EXPO_PUBLIC_PRIVACY_URL || undefined,
};
