// gRPC vs WebClient A/B — REST 팔 (docs/decisions/grpc-webclient-empirical-comparison.md §9.3)
//
// ghz 의 `-c C -n N`(닫힌 루프, 총 N건)과 같은 모양을 만들려고 shared-iterations 를 쓴다.
// ramping/constant-arrival-rate 는 «열린 루프»라 대상이 느려지면 큐가 쌓여 다른 것을 재게 된다 —
// 이 라운드가 재려는 건 처리량 천장이 아니라 «같은 일을 시켰을 때의 왕복시간»이다.
//
// 🔴 본문은 loadtest/ghz/gen_ab_payloads.py 가 만든 파일을 **그대로** 보낸다. 같은 파일이
//    gRPC 팔에서는 ghz 가 protobuf 로 바꿔 보내는 입력이 된다 — 두 팔의 논리적 요청이
//    한 글자도 다르지 않게 하는 장치다.
import http from 'k6/http';
import { Trend, Counter } from 'k6/metrics';

const URL = __ENV.URL;
const TOKEN = __ENV.TOKEN;
const WORKER = __ENV.WORKER || '0';
const VUS = parseInt(__ENV.VUS || '1', 10);
const ITERS = parseInt(__ENV.ITERS || '200', 10);

// init 단계에서만 파일을 읽을 수 있다. VU 마다 다시 읽지 않으므로 디스크가 측정에 안 낀다.
const BODY = open(__ENV.BODY_FILE);

const tCall = new Trend('t_call', true);
const badStatus = new Counter('bad_status');

export const options = {
  scenarios: {
    ab: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERS,
      // 판이 통째로 멈추면 그 자리에서 끝낸다 — 다음 판을 못 돌리는 것보다 낫다.
      maxDuration: __ENV.MAX_DURATION || '10m',
    },
  },
  // 🔴 thresholds 를 판정으로 쓰지 않는다. 이 라운드엔 아직 판정선이 없다 —
  //    두 팔의 «차이»를 재는 게 목적이고, 임계값은 그 결과를 보고 별도로 정한다.
  //    (feedback_no_arbitrary_threshold_values)
  thresholds: {},
  discardResponseBodies: false,
};

export default function () {
  const res = http.post(URL, BODY, {
    headers: {
      'Content-Type': 'application/json',
      // gRPC 팔은 AuthInterceptor 가 같은 값을 gRPC 메타데이터로 받는다. 이 경로가
      // AI_PUBLIC_TOKEN(앱 번들 배포값)이 아니라 INTERNAL_API_TOKEN 을 요구하는 이유는
      // ai-server/app/middleware/auth.py 주석 참조.
      Authorization: `Bearer ${TOKEN}`,
      // 이 rig 는 **모든 팔을 워커 0 에 고정**한다. 안 그러면 REST 팔만 3개 워커로
      // 흩어지고 gRPC 팔(단일 포트)은 1개에 몰려서, 프로토콜 차이가 아니라 병렬도
      // 차이를 재게 된다. 흩어짐(라우팅) 자체는 별도 probe 로 본다.
      'X-AI-Worker': WORKER,
    },
    tags: { name: 'internal_analysis' },
  });

  tCall.add(res.timings.duration);
  // 이 두 페이로드는 «업무적으로는 실패»(success=false)지만 HTTP 는 200 이 정상이다.
  // 401 이면 토큰, 404 면 라우터 미등록, 000 이면 아예 못 닿은 것 — 전부 판을 버려야 한다.
  if (res.status !== 200) {
    badStatus.add(1);
  }
}
