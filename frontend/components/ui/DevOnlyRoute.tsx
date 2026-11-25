import type { ReactNode } from 'react';
import { Redirect } from 'expo-router';

/**
 * 개발 빌드에서만 열리는 화면을 감싼다. 릴리스에서는 홈으로 돌려보낸다.
 *
 * 서버 API 가 없는 목업 화면(공지·Q&A)용이다. 진입 버튼은 이미 `__DEV__` 로 막혀 있지만 앱 scheme(`shadowfit://`)
 * 딥링크로는 버튼 없이도 라우트에 닿는다 — `shadowfit://board/notice` 를 열면 가짜 «서비스 점검 안내» 가 보이고
 * Q&A 에는 누구나 글을 «등록» 할 수 있다(저장은 되지 않는다). 그래서 진입점이 아니라 <b>라우트 자체</b>를 막는다.
 *
 * 실서비스로 열려면 공지·Q&A API 가 먼저 필요하다. 그때 이 래퍼를 걷어낸다.
 */
export default function DevOnlyRoute({ children }: { children: ReactNode }) {
  if (!__DEV__) return <Redirect href="/(tabs)" />;
  return <>{children}</>;
}
