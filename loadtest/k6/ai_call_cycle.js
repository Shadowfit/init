// 4차 라운드(동시성 축) 드라이버 — VU c 개가 각각 «세션 1개를 열고 그 세션에 재부착을 N 번» 보낸다.
// 설계: docs/decisions/grpc-webclient-concurrency-round.md §5-1
//
// 재는 것은 이 파일이 아니다. 측정값은 Spring 안의 TimedAiAnalysisClient(shadowfit.ai.call)가
// 낸다 — 이 드라이버는 그 호출을 «동시에 c 개» 촉발할 뿐이고, 드라이버↔Spring 구간은 측정 밖이다.
// 그래서 http_req_duration 은 판정에 안 쓰고, 처리량(재부착 건수 ÷ 벽시계)과 실패 계수만 남긴다.
//
// 🔴 왜 «사이클(시작→재부착→종료)» 이 아니라 «재부착 반복» 인가 (2026-09-11, 로컬 스모크 결과):
//    사이클 안에서 AI 호출은 ~8ms 이고 사이클은 수백 ms 다. VU 32 개가 사이클을 돌려도 동시에
//    떠 있는 재부착 호출은 c × (8ms/사이클) ≈ c 의 3% 뿐이라, 설계 §4-2 의 문턱(이벤트루프 4 ·
//    풀 16 · 대기열 32)에 닿지 않는다. VU 마다 세션 하나를 열어두고 재부착만 반복하면 동시
//    재부착 호출 ≈ c 가 된다. AI 쪽은 «이미 분석 중 — 상태 보존» 경로인데, 2·3차도 정확히 이
//    경로였다(start 는 fire-and-forget 이라 reattach 가 닿을 때 AI 에 이미 세션이 있다).
//    덤으로 «같은 계정 연속 사이클 → 409» 와 «종료 뒤 stop 대기 중인 세션이 검출기 풀을 잡고
//    있는» 문제가 사라진다 — 칸당 세션은 VU 당 정확히 1개다.
//
// 반복 구조 (per-vu-iterations, VU 당 ITERS 회):
//   __ITER == 0        : POST /exercises/sessions (start) → sid
//   매 반복             : POST /sessions/{sid}/reattach   ← 측정 대상
//   __ITER == ITERS-1  : PATCH /sessions/{sid}/end        (아웃박스 → stop, 판정 밖)
//   start 가 실패하면 그 VU 는 남은 반복을 전부 건너뛴다(반쪽 표본을 만들지 않는다).
//
// 🔴 계정: VU i 는 계정 (i−1) + (CELL_SEQ mod R)·MAX_C 를 쓴다. 이웃한 칸에서 같은 계정으로
//    다시 start 하면, 앞 칸의 세션 status 가 아직 IN_PROGRESS(콜백 전)일 때 409 가 난다 —
//    칸 사이 배수가 있어도 콜백은 그 뒤에 온다. 그래서 칸마다 계정 묶음을 바꾼다(R=2 면 두 묶음
//    교대). 그래도 409 면 start_409 로 세고 분석기가 그 칸을 뺀다(규칙 3).
//
// 세 요청은 ai_call_ab_lib.sh 의 cycle() 과 같다 — 한쪽을 고치면 다른 쪽도.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '1', 10);
const ITERS = parseInt(__ENV.ITERS || '100', 10);
const EXERCISE_ID = parseInt(__ENV.EXERCISE_ID || '1', 10);
const SUMMARY = __ENV.SUMMARY || '';
const CELL_SEQ = parseInt(__ENV.CELL_SEQ || '0', 10);   // 칸 일련번호 — 계정 묶음 교대용
const MAX_C = parseInt(__ENV.MAX_C || String(VUS), 10); // 묶음 하나의 크기 = 라운드의 최대 c

// init 단계에서만 파일을 읽을 수 있다. VU 마다 다시 읽지 않는다.
const TOKENS = open(__ENV.TOKENS_FILE || 'tokens.txt').split('\n').map(s => s.trim()).filter(Boolean);
if (TOKENS.length < VUS) {
  throw new Error(`토큰 ${TOKENS.length}개 < VUS ${VUS} — 계정이 모자란다(ACCOUNTS 를 올릴 것)`);
}
const ROTATION = Math.max(1, Math.floor(TOKENS.length / MAX_C));   // 계정 묶음 수

const reattachOk = new Counter('reattach_ok');
const startFail = new Counter('start_fail');
const start409 = new Counter('start_409');   // 이웃 칸과 계정이 겹쳤다는 신호 — ROTATION(계정 수)을 올릴 것
const reattachBad = new Counter('reattach_bad');
const endBad = new Counter('end_bad');
const tReattach = new Trend('t_reattach', true);

export const options = {
  scenarios: {
    reattach: {
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

// VU 별 상태 — k6 는 VU 마다 JS 런타임이 따로라 모듈 변수가 곧 VU 로컬이다.
let sid = null;
let dead = false;

export default function () {
  const tok = TOKENS[(__VU - 1) + (CELL_SEQ % ROTATION) * MAX_C];
  const auth = { Authorization: `Bearer ${tok}` };

  if (__ITER === 0) {
    const start = http.post(`${BASE}/exercises/sessions`, JSON.stringify({ exerciseId: EXERCISE_ID }), {
      headers: Object.assign({ 'Content-Type': 'application/json' }, auth),
      tags: { step: 'start' },
    });
    // 세션 시작은 202 Accepted 를 돌려준다(ExercisesController) — 2xx 전부를 성공으로 본다.
    if (start.status >= 200 && start.status < 300) {
      try { sid = start.json('sessionId'); } catch (_) { sid = null; }
    }
    if (!sid) {
      startFail.add(1);
      if (start.status === 409) start409.add(1);
      dead = true;
    }
  }
  if (dead) return;

  // 재부착 — reference_poses 가 실려 나가는 «큰 요청». 사용자 요청 스레드에서 블로킹 호출.
  const t0 = Date.now();
  const re = http.post(`${BASE}/sessions/${sid}/reattach`, null, { headers: auth, tags: { step: 'reattach' } });
  tReattach.add(Date.now() - t0);
  if (re.status >= 200 && re.status < 300) reattachOk.add(1); else reattachBad.add(1);

  if (__ITER === ITERS - 1) {
    // 종료 — 아웃박스 발행기가 StopAnalysis(작은 요청)를 비동기로 보낸다(판정 밖, 설계 §3-3).
    const end = http.patch(`${BASE}/sessions/${sid}/end`, null, { headers: auth, tags: { step: 'end' } });
    if (end.status < 200 || end.status >= 300) endBad.add(1);
  }
}

// 칸마다 JSON 하나 — 분석기가 재부착 건수·실패 계수를 여기서 읽는다.
export function handleSummary(data) {
  const out = {};
  const m = data.metrics;
  const val = (name, key) => (m[name] && m[name].values && m[name].values[key] !== undefined) ? m[name].values[key] : null;
  const summary = {
    vus: VUS,
    iters_per_vu: ITERS,
    cell_seq: CELL_SEQ,
    rotation: ROTATION,
    iterations: val('iterations', 'count'),
    // 처리량 분자 — 성공한 재부착 호출 수(칸당 기대치 = VUS × ITERS).
    reattach_ok: val('reattach_ok', 'count') || 0,
    start_fail: val('start_fail', 'count') || 0,
    start_409: val('start_409', 'count') || 0,
    reattach_bad: val('reattach_bad', 'count') || 0,
    end_bad: val('end_bad', 'count') || 0,
    http_req_failed_rate: val('http_req_failed', 'rate'),
    // 드라이버 쪽 벽시계 — 판정엔 안 쓴다(측정 구간 밖). 처리량 분모는 rig 이 잰 칸 벽시계다.
    t_reattach_ms: { avg: val('t_reattach', 'avg'), p50: val('t_reattach', 'med'), p95: val('t_reattach', 'p(95)') },
    test_run_duration_ms: data.state ? data.state.testRunDurationMs : null,
  };
  if (SUMMARY) out[SUMMARY] = JSON.stringify(summary, null, 2);
  out.stdout = `k6 재부착 ${summary.reattach_ok}/${VUS * ITERS} · start_fail ${summary.start_fail}(409: ${summary.start_409}) · reattach_bad ${summary.reattach_bad} · end_bad ${summary.end_bad} · 계정 묶음 ${CELL_SEQ % ROTATION}/${ROTATION}\n`;
  return out;
}
