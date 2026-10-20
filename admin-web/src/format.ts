import type { Persona, SessionStatus, WorkoutLevel } from './types';

export const PERSONA: Record<Persona, string> = {
  BEGINNER: '헬린이',
  ADVANCED: 'FM 교관',
  DIET: '다이어터',
  REHAB: '재활 전문',
};

export const LEVEL: Record<WorkoutLevel, string> = {
  STARTER: '입문',
  BEGINNER: '초급',
  INTERMEDIATE: '중급',
  ADVANCED: '고급',
  EXPERT: '전문가',
};

export const SESSION_STATUS: Record<SessionStatus, { label: string; tone: string }> = {
  COMPLETED: { label: '완료', tone: 'b-ok' },
  IN_PROGRESS: { label: '진행 중', tone: 'b-info' },
  CANCELLED: { label: '취소', tone: 'b-neutral' },
  FAILED: { label: '실패', tone: 'b-danger' },
};

const pad = (n: number) => String(n).padStart(2, '0');

// 서버 날짜는 두 형식으로 온다 — 기존 관리자 API(회원·세션·종목·통계)는 [2026,10,9,20,21,0] 배열,
// 새 API(회원 상세·모임·피드)는 "2026-10-09T20:21:00" 문자열. 둘 다 받아 로컬 Date 로 바꾼다.
export type ServerDate = string | number[] | null | undefined;

export function toDate(v: ServerDate): Date | null {
  if (v == null) return null;
  if (Array.isArray(v)) {
    const [y, mo = 1, d = 1, h = 0, mi = 0, s = 0] = v;
    return new Date(y, mo - 1, d, h, mi, s);
  }
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function dt(v: ServerDate): string {
  const d = toDate(v);
  if (!d) return '—';
  return `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function day(v: ServerDate): string {
  const d = toDate(v);
  if (!d) return '—';
  return `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())}`;
}

export function ago(v: ServerDate): string {
  const d = toDate(v);
  if (!d) return '—';
  const diff = (Date.now() - d.getTime()) / 60000;
  if (diff < 1) return '방금';
  if (diff < 60) return `${Math.floor(diff)}분 전`;
  if (diff < 60 * 24) return `${Math.floor(diff / 60)}시간 전`;
  if (diff < 60 * 24 * 30) return `${Math.floor(diff / 60 / 24)}일 전`;
  return day(v);
}

export function minutesBetween(a: ServerDate, b: ServerDate): number | null {
  const x = toDate(a), y = toDate(b);
  if (!x || !y) return null;
  return Math.max(0, Math.round((y.getTime() - x.getTime()) / 60000));
}

export const syncColor = (v: number) => (v >= 80 ? 'var(--ok)' : v >= 60 ? 'var(--warn)' : 'var(--danger)');
