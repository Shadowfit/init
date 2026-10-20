// 백엔드 호출 — vite 프록시가 /api 를 Spring(8080)으로 넘긴다.
// 토큰은 localStorage(관리자 웹 전용 키). access 가 만료돼 401 이면 refresh 로 한 번 재발급 후 재시도(토큰 회전 — 둘 다 저장).
export const API_BASE = '/api';

const ACCESS = 'sf_admin_access';
const REFRESH = 'sf_admin_refresh';
const EMAIL = 'sf_admin_email';

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export const tokens = {
  access: () => localStorage.getItem(ACCESS),
  refresh: () => localStorage.getItem(REFRESH),
  email: () => localStorage.getItem(EMAIL),
  save(access: string, refresh: string, email?: string) {
    localStorage.setItem(ACCESS, access);
    localStorage.setItem(REFRESH, refresh);
    if (email) localStorage.setItem(EMAIL, email);
  },
  clear() {
    localStorage.removeItem(ACCESS);
    localStorage.removeItem(REFRESH);
    localStorage.removeItem(EMAIL);
  },
};

let onSessionExpired: () => void = () => {};
export function setSessionExpiredHandler(fn: () => void) {
  onSessionExpired = fn;
}

type Query = Record<string, string | number | boolean | null | undefined>;

interface Options {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  query?: Query;
  body?: unknown;
  form?: FormData;
  auth?: boolean;
}

function url(path: string, query?: Query) {
  const qs = new URLSearchParams();
  Object.entries(query ?? {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') qs.set(k, String(v));
  });
  const s = qs.toString();
  return `${API_BASE}${path}${s ? `?${s}` : ''}`;
}

let reissuing: Promise<boolean> | null = null;
async function reissue(): Promise<boolean> {
  const refresh = tokens.refresh();
  if (!refresh) return false;
  reissuing ??= (async () => {
    try {
      const res = await fetch(url('/member/reissue'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: refresh }),
      });
      if (!res.ok) return false;
      const data = await res.json();
      tokens.save(data.accessToken, data.refreshToken);
      return true;
    } catch {
      return false;
    } finally {
      setTimeout(() => (reissuing = null), 0);
    }
  })();
  return reissuing;
}

export async function api<T>(path: string, opts: Options = {}, retried = false): Promise<T> {
  const headers: Record<string, string> = {};
  const access = tokens.access();
  if (opts.auth !== false && access) headers.Authorization = `Bearer ${access}`;
  let body: BodyInit | undefined;
  if (opts.form) body = opts.form;
  else if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(opts.body);
  }

  let res: Response;
  try {
    res = await fetch(url(path, opts.query), { method: opts.method ?? 'GET', headers, body });
  } catch {
    throw new ApiError(0, '서버에 연결할 수 없어요. 백엔드(8080)가 켜져 있는지 확인해주세요.');
  }

  if (res.status === 401 && opts.auth !== false && !retried) {
    if (await reissue()) return api<T>(path, opts, true);
    tokens.clear();
    onSessionExpired();
    throw new ApiError(401, '로그인이 만료됐어요. 다시 로그인해주세요.');
  }
  if (res.status === 204) return undefined as T;

  const text = await res.text();
  const data = text ? safeJson(text) : undefined;
  if (!res.ok) {
    const msg = (data as { message?: string } | undefined)?.message;
    throw new ApiError(res.status, msg ?? defaultMessage(res.status));
  }
  return data as T;
}

function safeJson(t: string): unknown {
  try {
    return JSON.parse(t);
  } catch {
    return undefined;
  }
}

function defaultMessage(status: number) {
  if (status === 403) return '관리자 권한이 필요해요.';
  if (status === 404) return '대상을 찾을 수 없어요.';
  if (status >= 500) return '서버 오류가 났어요. 잠시 후 다시 시도해주세요.';
  return '요청을 처리하지 못했어요.';
}

export const photoSrc = (path: string | null | undefined) => (path ? `${API_BASE}${path}` : null);
