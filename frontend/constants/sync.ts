import { COLORS } from './Colors';

/**
 * 싱크로율 판정 기준 — 종목마다 한 세트(정석 good · 위험 warn). 2026-10-21 «하나로 통일» 결정:
 * 예전엔 페르소나별 4개 값이 DB 에 있었지만 아무도 읽지 않았고, 화면은 80/60 을 하드코딩했다.
 * 이제 관리자가 종목별로 정하고(admin › 운동 종목 › 판정 임계값), 서버가 세션 시작·재부착·리포트 응답에 실어 준다.
 *   rate ≥ good → 정석(라임) · rate ≥ warn → 교정 필요(주황) · 그 아래 → 위험(빨강, 진동)
 * 서버 값이 없을 때(구버전 응답·여러 종목이 섞인 목록)는 기본값 80 / 60 을 쓴다.
 */
export interface SyncThresholds {
  good: number;
  warn: number;
}

export const DEFAULT_SYNC_THRESHOLDS: SyncThresholds = { good: 80, warn: 60 };

/** 서버 응답의 syncGoodThreshold / syncWarnThreshold → 판정 기준. 값이 없거나 이상하면 기본값. */
export function syncThresholdsFrom(src?: { syncGoodThreshold?: number | null; syncWarnThreshold?: number | null } | null): SyncThresholds {
  const good = Number(src?.syncGoodThreshold);
  const warn = Number(src?.syncWarnThreshold);
  if (!Number.isFinite(good) || !Number.isFinite(warn) || warn <= 0 || good <= warn || good > 100) return DEFAULT_SYNC_THRESHOLDS;
  return { good, warn };
}

export type SyncLevel = 'good' | 'warn' | 'risk';

export function syncLevel(rate: number, t: SyncThresholds = DEFAULT_SYNC_THRESHOLDS): SyncLevel {
  if (rate >= t.good) return 'good';
  if (rate >= t.warn) return 'warn';
  return 'risk';
}

export function syncColor(rate: number, t: SyncThresholds = DEFAULT_SYNC_THRESHOLDS): string {
  const level = syncLevel(rate, t);
  return level === 'good' ? COLORS.syncHigh : level === 'warn' ? COLORS.syncMid : COLORS.syncLow;
}
