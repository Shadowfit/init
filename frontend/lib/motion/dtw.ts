/**
 * 다변량 DTW (Dynamic Time Warping) — 와핑 경로까지 돌려준다.
 *
 * 국소 거리  c(i,j) = sqrt( Σ_k w_k · (a_ik − b_jk)² )     ← 가중 유클리드(특징은 눈금 보정 후 입력)
 * 누적 비용  D(i,j) = c(i,j) + min( D(i−1,j), D(i,j−1), D(i−1,j−1) )
 *
 * 서버(ai-server/app/core/dtw_calculator.py)의 DTW 는 «관절각 1개씩 따로 돌려 거리만 평균» 하고 경로가
 * 없다. 이 화면은 «어느 사용자 프레임이 기준의 어느 프레임에 대응하는가» 가 필요해서 다변량으로 새로 만들었다.
 * 둘은 서로 다른 지표다(서버 것은 실시간 참고 점수, 이것은 운동 뒤 정렬 분석).
 */
export interface DtwOptions {
  /** 특징별 가중치 (길이 = 특징 수) */
  weights: number[];
  /**
   * Sakoe-Chiba 밴드 반폭 — 경로가 기울기 m/n 인 대각선에서 벗어날 수 있는 최대 칸 수.
   * 생략하면 max(n,m)의 절반. 길이 차이가 이보다 크면 끝점에 닿지 못하므로 |n−m| 이상으로 올린다.
   */
  window?: number;
}

export interface DtwResult {
  /** 경로를 따라 더한 총 비용 */
  distance: number;
  /** 경로 길이로 나눈 평균 국소 거리 — 길이가 다른 반복끼리 비교할 수 있는 값 */
  meanDistance: number;
  /** [a 의 인덱스, b 의 인덱스] 쌍. (0,0) 에서 (n−1,m−1) 까지, 한 칸씩 단조 증가 */
  path: Array<[number, number]>;
}

export function localDistance(a: number[], b: number[], weights: number[]): number {
  let s = 0;
  for (let k = 0; k < weights.length; k++) {
    const d = a[k] - b[k];
    s += weights[k] * d * d;
  }
  return Math.sqrt(s);
}

export function dtw(a: number[][], b: number[][], opts: DtwOptions): DtwResult {
  const n = a.length, m = b.length;
  if (!n || !m) throw new Error('DTW 입력이 비어 있습니다');
  const band = Math.max(opts.window ?? Math.ceil(Math.max(n, m) / 2), Math.abs(n - m), 1);
  const INF = Number.POSITIVE_INFINITY;
  const D: Float64Array[] = Array.from({ length: n }, () => new Float64Array(m).fill(INF));
  // 후진 추적용: 0=대각, 1=위(i-1), 2=왼(j-1)
  const from: Uint8Array[] = Array.from({ length: n }, () => new Uint8Array(m));

  for (let i = 0; i < n; i++) {
    const center = n > 1 ? (i * (m - 1)) / (n - 1) : 0;
    const jLo = Math.max(0, Math.ceil(center - band));
    const jHi = Math.min(m - 1, Math.floor(center + band));
    for (let j = jLo; j <= jHi; j++) {
      const c = localDistance(a[i], b[j], opts.weights);
      if (i === 0 && j === 0) { D[i][j] = c; continue; }
      let best = INF, dir = 0;
      if (i > 0 && j > 0 && D[i - 1][j - 1] < best) { best = D[i - 1][j - 1]; dir = 0; }
      if (i > 0 && D[i - 1][j] < best) { best = D[i - 1][j]; dir = 1; }
      if (j > 0 && D[i][j - 1] < best) { best = D[i][j - 1]; dir = 2; }
      if (best < INF) { D[i][j] = c + best; from[i][j] = dir; }
    }
  }
  if (!Number.isFinite(D[n - 1][m - 1])) throw new Error('DTW 경로를 찾지 못했습니다(밴드가 너무 좁음)');

  const path: Array<[number, number]> = [];
  let i = n - 1, j = m - 1;
  path.push([i, j]);
  while (i > 0 || j > 0) {
    const dir = from[i][j];
    if (dir === 0) { i--; j--; } else if (dir === 1) { i--; } else { j--; }
    path.push([i, j]);
  }
  path.reverse();
  return { distance: D[n - 1][m - 1], meanDistance: D[n - 1][m - 1] / path.length, path };
}

export interface AsymmetricDtwOptions {
  weights: number[];
  /** 사용자 프레임 한 칸 사이에 기준이 건너뛸 수 있는 최대 프레임 수 (사용자가 시범보다 빠를수록 큼) */
  maxSkip?: number;
  /** 경로가 기준의 앞쪽 이 비율 안에서 시작해도 되는 정도 (사용자 반복의 앞 여유가 시범과 다르므로) */
  openBegin?: number;
  /** 경로가 기준의 뒤쪽 이 비율 안에서 끝나도 되는 정도 */
  openEnd?: number;
}

/**
 * 비대칭 단계 패턴 DTW — **사용자 프레임마다 기준 프레임을 정확히 하나** 고른다.
 *
 *   D(i,j) = c(i,j) + min_{p = j−maxSkip .. j} D(i−1, p)      (p = j 는 «기준이 그대로» = 사용자가 더 느림)
 *
 * 대칭 패턴은 양쪽 모든 프레임이 경로에 한 번은 들어가야 해서, 사용자가 3fps 로 듬성듬성 들어오고 기준이
 * 촘촘하면 «건너뛴 기준 프레임» 마다 거리가 더해진다 — 같은 동작인데도 거리가 커진다(합성 시험에서 같은 속도의
 * 같은 동작이 79점이었다). 비대칭 패턴은 기준을 촘촘한 «연속 곡선» 으로 보고 각 사용자 프레임이 그 위의 가장
 * 가까운 점을 단조롭게 찾아간다. 거리는 사용자 프레임 수로 나눈다. (경로는 사용자 프레임 수만큼의 쌍이다.)
 */
export function dtwAsymmetric(a: number[][], b: number[][], opts: AsymmetricDtwOptions): DtwResult {
  const n = a.length, m = b.length;
  if (!n || !m) throw new Error('DTW 입력이 비어 있습니다');
  const maxSkip = opts.maxSkip ?? 12;
  const jBegin = Math.floor((opts.openBegin ?? 0.25) * (m - 1));
  const jEndMin = Math.ceil((1 - (opts.openEnd ?? 0.25)) * (m - 1));
  const INF = Number.POSITIVE_INFINITY;
  const D: Float64Array[] = Array.from({ length: n }, () => new Float64Array(m).fill(INF));
  const from: Int32Array[] = Array.from({ length: n }, () => new Int32Array(m).fill(-1));

  for (let j = 0; j <= jBegin; j++) D[0][j] = localDistance(a[0], b[j], opts.weights);
  for (let i = 1; i < n; i++) {
    for (let j = 0; j < m; j++) {
      let best = INF, arg = -1;
      for (let p = Math.max(0, j - maxSkip); p <= j; p++) {
        if (D[i - 1][p] < best) { best = D[i - 1][p]; arg = p; }
      }
      if (arg >= 0) { D[i][j] = localDistance(a[i], b[j], opts.weights) + best; from[i][j] = arg; }
    }
  }
  let jEnd = -1, bestEnd = INF;
  for (let j = Math.max(0, jEndMin); j < m; j++) if (D[n - 1][j] < bestEnd) { bestEnd = D[n - 1][j]; jEnd = j; }
  if (jEnd < 0) throw new Error('DTW 경로를 찾지 못했습니다');
  const path: Array<[number, number]> = new Array(n);
  let j = jEnd;
  for (let i = n - 1; i >= 0; i--) { path[i] = [i, j]; if (i > 0) j = from[i][j]; }
  return { distance: bestEnd, meanDistance: bestEnd / n, path };
}
