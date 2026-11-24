// «운동 기록이 바뀌었다» 신호 — 기록을 지운 화면이 올리고, 홈이 포커스될 때 읽어 일별 목록을 다시 받는다.
//
// 왜 필요한가: 홈의 일별 목록은 날짜를 바꿀 때만 다시 받는다(포커스마다 받는 것은 캘린더뿐). 리포트 화면에서 세션을
// 지우고 홈으로 돌아오면 지운 세션이 목록에 그대로 남아, 누르면 «보고서를 찾을 수 없어요» 가 된다.
// 화면 사이에 값을 넘기려고 라우트 파라미터나 전역 스토어를 새로 들이는 대신, 카운터 하나로 둔다
// (services/sessionVersion.ts 와 같은 방식).
//
// 🔴 카운터만으로는 모자란다 — 서버 응답 캐시(lib/queryClient.ts)가 있기 때문이다. 홈은 지난 날짜의 일별 목록과
// 지난 달 캘린더를 30분 캐시하므로, «다시 받아라» 신호가 와도 캐시가 신선하면 **지운 세션이 든 옛 목록**이 그대로
// 돌아온다(누르면 «보고서를 찾을 수 없어요»). 그래서 신호를 올리는 이 자리에서 기록 파생 캐시를 같이 무효화한다 —
// 신호를 올리는 곳이 늘어도 무효화를 빠뜨릴 수 없다. 모임·프로필 같은 기록과 무관한 캐시는 건드리지 않는다.
import { queryClient, queryKeys } from '../lib/queryClient';

let version = 0;

export function currentRecordsVersion(): number {
  return version;
}

export function bumpRecordsVersion(): void {
  version += 1;
  void queryClient.invalidateQueries({ queryKey: queryKeys.reportAll });
}
