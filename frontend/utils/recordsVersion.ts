// «운동 기록이 바뀌었다» 신호 — 기록을 지운 화면이 올리고, 홈이 포커스될 때 읽어 일별 목록을 다시 받는다.
//
// 왜 필요한가: 홈의 일별 목록은 날짜를 바꿀 때만 다시 받는다(포커스마다 받는 것은 캘린더뿐). 리포트 화면에서 세션을
// 지우고 홈으로 돌아오면 지운 세션이 목록에 그대로 남아, 누르면 «보고서를 찾을 수 없어요» 가 된다.
// 화면 사이에 값을 넘기려고 라우트 파라미터나 전역 스토어를 새로 들이는 대신, 의존성 0 인 카운터 하나로 둔다
// (services/sessionVersion.ts 와 같은 방식).
let version = 0;

export function currentRecordsVersion(): number {
  return version;
}

export function bumpRecordsVersion(): void {
  version += 1;
}
