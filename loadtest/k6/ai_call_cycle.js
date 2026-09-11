// 4차 라운드(동시성 축) 드라이버 — 사이클(세션 시작 → 재부착 → 종료)을 VU c 개가 동시에 돈다.
// 설계: docs/decisions/grpc-webclient-concurrency-round.md §5-1
//
// 재는 것은 이 파일이 아니다. 측정값은 Spring 안의 TimedAiAnalysisClient(shadowfit.ai.call)가
// 낸다 — 이 드라이버는 그 호출을 «동시에 c 개» 촉발할 뿐이고, 드라이버↔Spring 구간은 측정 밖이다.
// 그래서 http_req_duration 은 판정에 안 쓰고, 처리량(iterations ÷ 벽시계)과 실패 계수만 남긴다.
//
// 🔴 per-vu-iterations 다 — 닫힌 루프. constant-arrival-rate(열린 루프)는 «초당 λ 건» 을 주지만
//    그 λ 를 고를 근거(서버 천장)가 아직 없다(설계 §8). 표본은 칸당 VUS × ITERS 사이클.
//
// 🔴 VU i 는 «자기 계정 묶음» 을 돌려 쓴다 — 반복 k 에서 계정 (i−1) + (k mod R)·VUS.
//    같은 계정을 바로 다음 반복에 다시 쓰면 409(SESSION_ALREADY_IN_PROGRESS)가 난다: `end` 는
//    endTime 만 적고 status 는 아웃박스 → AI → 콜백이 돌아와야 바뀐다(SessionService.endSession
//    주석). 로컬 스모크에서 VU 당 계정 1개로 돌리니 두 번째 반복이 전부 409 였다. 그래서 계정을
//    R 개 깊이로 돌려 재사용 간격을 R 사이클로 벌린다 — 그래도 409 가 나면 start_409 로 따로 세고
//    분석기가 그 칸을 판정에서 뺀다(규칙 3). 토큰은 rig 이 만든 tokens.txt(한 줄에 하나).
//    계정 수 < VUS 면 init 에서 멈춘다(조용히 나눠 쓰면 «같은 계정의 동시 세션» 을 재게 된다).
//
// 세 요청은 ai_call_ab_lib.sh 의 cycle() 과 같다 — 한쪽을 고치면 다른 쪽도.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '1', 10);
const ITERS = parseInt(__ENV.ITERS || '100', 10);
const EXERCISE_ID = parseInt(__ENV.EXERCISE_ID || '1', 10);
const SUMMARY = __ENV.SUMMARY || '';

// init 단계에서만 파일을 읽을 수 있다. VU 마다 다시 읽지 않는다.
const TOKENS = open(__ENV.TOKENS_FILE || 'tokens.txt').split('\n').map(s => s.trim()).filter(Boolean);
if (TOKENS.length < VUS) {
  throw new Error(`토큰 ${TOKENS.length}개 < VUS ${VUS} — 계정이 모자란다(ACCOUNTS 를 올릴 것)`);
}
const ROTATION = Math.floor(TOKENS.length / VUS);   // VU 당 계정 묶음 깊이

const cycleOk = new Counter('cycle_ok');
const startFail = new Counter('start_fail');
const start409 = new Counter('start_409');   // 계정 재사용 간격이 모자랐다는 신호 — ROTATION 을 올릴 것
const reattachBad = new Counter('reattach_bad');
const endBad = new Counter('end_bad');
const tCycle = new Trend('t_cycle', true);

export const options = {
  scenarios: {
    cycle: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ITERS,
      // 판이 통째로 멈추면 그 자리에서 끝낸다 — 다음 칸을 못 돌리는 것보다 낫다.
      maxDuration: __ENV.MAX_DURATION || '15m',
      gracefulStop: '30s',
    },
  },
  // 판정선 없음 — 이 라운드는 두 팔의 «모양» 을 재고, 임계값은 안 세운다
  // (feedback_no_arbitrary_threshold_values).
  thresholds: {},
  discardResponseBodies: false,
};

export default function () {
  const tok = TOKENS[(__VU - 1) + (__ITER % ROTATION) * VUS];
  const auth = { Authorization: `Bearer ${tok}` };
  const started = Date.now();

  const start = http.post(`${BASE}/exercises/sessions`, JSON.stringify({ exerciseId: EXERCISE_ID }), {
    headers: Object.assign({ 'Content-Type': 'application/json' }, auth),
    tags: { step: 'start' },
  });
  let sid = null;
  // 세션 시작은 202 Accepted 를 돌려준다(ExercisesController) — 2xx 전부를 성공으로 본다.
  if (start.status >= 200 && start.status < 300) {
    try { sid = start.json('sessionId'); } catch (_) { sid = null; }
  }
  if (!sid) { startFail.add(1); if (start.status === 409) start409.add(1); return; }

  // 재부착 — reference_poses 가 실려 나가는 «큰 요청» 팔. 사용자 요청 스레드에서 블로킹 호출.
  const re = http.post(`${BASE}/sessions/${sid}/reattach`, null, { headers: auth, tags: { step: 'reattach' } });
  if (re.status < 200 || re.status >= 300) reattachBad.add(1);

  // 종료 — 아웃박스 발행기가 StopAnalysis(작은 요청)를 비동기로 보낸다(판정 밖, 설계 §3-3).
  const end = http.patch(`${BASE}/sessions/${sid}/end`, null, { headers: auth, tags: { step: 'end' } });
  if (end.status < 200 || end.status >= 300) endBad.add(1);

  tCycle.add(Date.now() - started);
  cycleOk.add(1);
}

// 칸마다 JSON 하나 — 분석기가 iterations·벽시계·실패 계수를 여기서 읽는다.
export function handleSummary(data) {
  const out = {};
  const m = data.metrics;
  const val = (name, key) => (m[name] && m[name].values && m[name].values[key] !== undefined) ? m[name].values[key] : null;
  const summary = {
    vus: VUS,
    iters_per_vu: ITERS,
    rotation: ROTATION,
    iterations: val('iterations', 'count'),
    cycle_ok: val('cycle_ok', 'count') || 0,
    start_fail: val('start_fail', 'count') || 0,
    start_409: val('start_409', 'count') || 0,
    reattach_bad: val('reattach_bad', 'count') || 0,
    end_bad: val('end_bad', 'count') || 0,
    http_req_failed_rate: val('http_req_failed', 'rate'),
    // 드라이버 쪽 벽시계 — 판정엔 안 쓴다(측정 구간 밖). 처리량 분모는 rig 이 잰 칸 벽시계다.
    t_cycle_ms: { avg: val('t_cycle', 'avg'), p50: val('t_cycle', 'med'), p95: val('t_cycle', 'p(95)') },
    test_run_duration_ms: data.state ? data.state.testRunDurationMs : null,
  };
  if (SUMMARY) out[SUMMARY] = JSON.stringify(summary, null, 2);
  out.stdout = `k6 사이클 ${summary.cycle_ok}/${summary.iterations} · start_fail ${summary.start_fail}(409: ${summary.start_409}) · reattach_bad ${summary.reattach_bad} · end_bad ${summary.end_bad} · 계정 깊이 ${ROTATION}\n`;
  return out;
}
