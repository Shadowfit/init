// 서버 응답 캐시 (react-query).
//
// 화면들이 `useFocusEffect` 로 «포커스될 때마다» 같은 GET 을 다시 보냈다(탭을 오가거나 뒤로 가기만 해도).
// 그중 «거의 안 바뀌는 것» 만 여기서 신선도(staleTime) 를 두고 재사용한다.
//
// 🔑 훅(`useQuery`)이 아니라 **`queryClient.fetchQuery`** 로 부른다. 기존 화면은 `useFocusEffect` +
//    `useState` 로 짜여 있어(로딩·실패·재시도 상태가 화면마다 다르다), 훅으로 갈아엎으면 화면 동작이
//    같이 바뀐다. `fetchQuery` 는 «신선하면 캐시, 아니면 요청» 만 해 주므로 호출부 한 줄만 바뀐다.
//
// ⚠️ 이 클라이언트는 **세션이 바뀔 때 반드시 비워야 한다** — `services/sessionVersion.ts` 의
//    `bumpSessionVersion()` 이 `queryClient.clear()` 를 부른다(로그인·로그아웃·강제 로그아웃).
//    안 비우면 로그아웃 뒤 다른 계정으로 들어와 «앞 사람의 모임·프로필» 이 캐시에서 보인다.

import { QueryClient } from '@tanstack/react-query';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 기존 화면은 재시도를 안 했다(실패하면 «실패» 를 바로 보여 준다). 같은 동작을 지킨다.
      retry: false,
      // `fetchQuery` 는 관찰자가 없어서 기본 gcTime(5분)이면 아래 30분 신선도가 무의미해진다.
      gcTime: 60 * 60 * 1000,
    },
  },
});

// 신선도. «이 시간 안에 다시 부르면 서버에 안 간다».
export const STALE = {
  /** 과거 월 캘린더·지난 날짜 기록 — 한번 생기면 사실상 안 바뀐다(자정을 넘겨 도착하는 리포트 정도). */
  immutable: 30 * 60 * 1000,
  /** 프로필·TTS 설정 — 이 앱 안에서 고칠 때만 바뀐다(고치는 곳에서 무효화한다). */
  profile: 5 * 60 * 1000,
  /** 모임 상세 — 다른 멤버가 들어오고 나가면 바뀐다. 짧게. */
  group: 60 * 1000,
} as const;

// 키 한 곳에 모아 둔다 — 호출부와 무효화 지점이 같은 키를 써야 한다.
export const queryKeys = {
  calendar: (year: number, month: number) => ['report', 'calendar', year, month] as const,
  daily: (date: string) => ['report', 'daily', date] as const,
  groupDetail: (groupId: number) => ['group', groupId, 'detail'] as const,
  /** 모임 전체(상세 포함) 무효화용 접두 */
  groupAll: ['group'] as const,
  onboarding: (email: string) => ['member', 'onboarding', email] as const,
  /** 온보딩 전체 무효화용 접두 */
  onboardingAll: ['member', 'onboarding'] as const,
  tts: (email: string) => ['preferences', 'tts', email] as const,
};
