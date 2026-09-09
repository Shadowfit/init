// gRPC vs WebClient A/B — **같은 도구 대조군**의 gRPC 팔
// (docs/decisions/grpc-webclient-empirical-comparison.md §9.3)
//
// ## 왜 ghz 가 있는데 이걸 또 만드나
//
// 본 측정의 두 델타 중 신뢰도가 다르다:
//
//   rest-nginx − rest-direct  → 둘 다 k6. **같은 도구**라 도구 오프셋이 뺄셈에서 사라진다.
//   rest-direct − grpc(ghz)   → k6 대 ghz. **도구가 다르다.** 이 차이에 «프로토콜» 말고
//                               «클라이언트 구현» 이 얼마나 섞였는지 이 판으로는 못 가른다.
//
// 두 번째가 이 라운드의 핵심 주장(«REST 가 더 내는 값이 nginx 홉인가 직렬화인가»)을 받치는
// 자리라, 그 자리가 도구 교차인 채로 결론을 쓰면 안 된다. 그래서 **k6 하나로 두 프로토콜을
// 다 재는 팔**을 따로 둔다. 이 팔과 rest-* 팔의 차이는 도구가 같으므로 프로토콜 차이다.
//
// ## 지표를 벽시계로 재는 이유
//
// k6 의 gRPC 모듈은 HTTP 처럼 `res.timings` 를 안 준다. 그래서 여기선 `Date.now()` 왕복만
// 잴 수 있다. 나란히 놓으려면 **비교 상대도 같은 방식**이어야 하므로, HTTP 스크립트
// (ab_internal_analysis.js)에도 같은 `t_wall` 트렌드를 넣어 뒀다.
// 🔴 t_wall 은 JS 실행 오버헤드를 포함한다 — 그래서 **절대값이 t_call 보다 크다.**
//    이 값은 «같은 팔끼리의 뺄셈» 에만 쓴다.
import grpc from 'k6/net/grpc';
import { Trend, Counter } from 'k6/metrics';

const ADDR = __ENV.GRPC_ADDR;           // host:port
const TOKEN = __ENV.TOKEN;
const METHOD = __ENV.GRPC_METHOD;       // 예: ExerciseService/StopAnalysis
const VUS = parseInt(__ENV.VUS || '1', 10);
const ITERS = parseInt(__ENV.ITERS || '200', 10);

// ghz 와 **같은 파일**을 쓴다 — 두 팔의 논리 요청이 한 글자도 다르지 않게 하는 장치.
const BODY = JSON.parse(open(__ENV.BODY_FILE));

const client = new grpc.Client();
client.load([__ENV.PROTO_DIR], 'exercise.proto');

const tWall = new Trend('t_wall', true);
const tCall = new Trend('t_call', true);   // 이 팔에서는 t_wall 과 같은 값(추출기 호환용)
const badStatus = new Counter('bad_status');

export const options = {
  scenarios: {
    ab: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERS,
      maxDuration: __ENV.MAX_DURATION || '10m',
    },
  },
  thresholds: {},
};

export default function () {
  // VU 당 연결을 한 번만 연다. 매 반복 connect 하면 «연결 수립» 을 재게 된다 —
  // HTTP 팔이 keep-alive 로 연결을 재사용하는 것과 조건을 맞춘다.
  if (!__ITER) {
    client.connect(ADDR, { plaintext: true });
  }

  const started = Date.now();
  const res = client.invoke(METHOD, BODY, {
    metadata: { authorization: `Bearer ${TOKEN}` },
  });
  const elapsed = Date.now() - started;

  tWall.add(elapsed);
  tCall.add(elapsed);
  if (res.status !== grpc.StatusOK) {
    badStatus.add(1);
  }
}

export function teardown() {
  client.close();
}
