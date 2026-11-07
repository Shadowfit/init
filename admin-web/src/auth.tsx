import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, API_BASE, ApiError, setSessionExpiredHandler, tokens } from './api';
import type { Role } from './types';

interface User {
  email: string;
  role: Role;
  username: string | null;
}

interface AuthState {
  user: User | null;
  ready: boolean;
  login: (email: string, password: string) => Promise<User>;
  logout: () => void;
}

const Ctx = createContext<AuthState | null>(null);

// JWT payload 에서 role 을 읽는다 — 서버가 «발급 시점의 role» 을 claim 으로 싣는다.
function roleFromToken(token: string | null): Role | null {
  if (!token) return null;
  try {
    const json = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return (json.role as Role) ?? null;
  } catch {
    return null;
  }
}

async function usernameOf(email: string): Promise<string | null> {
  try {
    const r = await api<{ username: string }>(`/member/onboarding/${encodeURIComponent(email)}`);
    return r.username;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);

  const logout = useCallback(() => {
    const had = tokens.access();
    tokens.clear();
    setUser(null);
    if (had) api('/member/logout', { method: 'POST' }).catch(() => {});
  }, []);

  useEffect(() => {
    setSessionExpiredHandler(() => setUser(null));
    const email = tokens.email();
    const role = roleFromToken(tokens.access());
    if (email && role === 'ADMIN') {
      setUser({ email, role, username: null });
      usernameOf(email).then((username) => setUser((u) => (u ? { ...u, username } : u)));
    } else {
      tokens.clear();
    }
    setReady(true);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const r = await api<{ accessToken: string; refreshToken: string; role: Role }>('/member/login', {
      method: 'POST',
      body: { email, password },
      auth: false,
    });
    if (r.role !== 'ADMIN') {
      // 관리자 웹은 관리자 계정만 — 방금 받은 토큰은 저장하지 않고 서버에서도 폐기한다
      fetch(`${API_BASE}/member/logout`, { method: 'POST', headers: { Authorization: `Bearer ${r.accessToken}` } }).catch(() => {});
      throw new ApiError(403, '관리자 계정이 아니에요. 관리자 계정으로 로그인해 주세요.');
    }
    tokens.save(r.accessToken, r.refreshToken, email);
    const next: User = { email, role: r.role, username: await usernameOf(email) };
    setUser(next);
    return next;
  }, []);

  const value = useMemo(() => ({ user, ready, login, logout }), [user, ready, login, logout]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const v = useContext(Ctx);
  if (!v) throw new Error('AuthProvider 밖에서 useAuth');
  return v;
}
