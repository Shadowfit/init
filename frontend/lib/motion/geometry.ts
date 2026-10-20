import type { Vec3 } from './types';

export const RAD2DEG = 180 / Math.PI;

export function sub(a: Vec3, b: Vec3): Vec3 { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
export function add(a: Vec3, b: Vec3): Vec3 { return [a[0] + b[0], a[1] + b[1], a[2] + b[2]]; }
export function scale(a: Vec3, k: number): Vec3 { return [a[0] * k, a[1] * k, a[2] * k]; }
export function dot(a: Vec3, b: Vec3): number { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
export function len(a: Vec3): number { return Math.hypot(a[0], a[1], a[2]); }
export function mid(a: Vec3, b: Vec3): Vec3 { return [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2]; }

/** b 를 꼭짓점으로 하는 a-b-c 의 내각(도). 점이 겹치면 NaN. */
export function angleAt(a: Vec3, b: Vec3, c: Vec3): number {
  const u = sub(a, b), v = sub(c, b);
  const n = len(u) * len(v);
  if (n < 1e-9) return NaN;
  return Math.acos(Math.max(-1, Math.min(1, dot(u, v) / n))) * RAD2DEG;
}

/** 위(+y) 방향에서 벡터가 벌어진 각도(도). 0 = 똑바로 위. */
export function tiltFromUp(v: Vec3): number {
  const n = len(v);
  if (n < 1e-9) return NaN;
  return Math.acos(Math.max(-1, Math.min(1, v[1] / n))) * RAD2DEG;
}

export function median(xs: number[]): number {
  if (!xs.length) return NaN;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

export function mean(xs: number[]): number {
  return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : NaN;
}

export function clamp(x: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, x));
}
