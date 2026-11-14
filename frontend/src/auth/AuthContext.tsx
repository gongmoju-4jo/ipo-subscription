import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { tokenStore } from '../api/client';
import { authApi } from '../api/endpoints';

// 로그인 상태. 토큰은 탭을 닫으면 사라지도록 sessionStorage에만 둔다 (localStorage에 두지 않음)
// 사용자 ID는 백엔드가 토큰으로 판단한다. 프론트는 화면 표시용으로만 토큰을 읽는다 (검증은 Gateway)

export type UserRole = 'USER' | 'ADMIN';

interface Session {
  token: string;
  loginId: string;
  expiresAt: number;
  role: UserRole;
}

interface AuthValue {
  session: Session | null;
  login: (loginId: string, password: string) => Promise<void>;
  logout: () => void;
}

const KEY = 'ipo.session';

// 토큰의 role로 관리자 메뉴 표시 여부만 결정한다. 서명 검증은 하지 않고, 권한 확인은 Gateway가 한다
function roleOf(token: string): UserRole {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const json = JSON.parse(atob(payload.padEnd(Math.ceil(payload.length / 4) * 4, '='))) as { role?: string };
    return json.role === 'ADMIN' ? 'ADMIN' : 'USER';
  } catch {
    return 'USER';
  }
}
const AuthContext = createContext<AuthValue | null>(null);

function readSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const s = JSON.parse(raw) as Session;
    // role이 없는 이전 형식 세션은 토큰에서 다시 읽는다
    return s.expiresAt > Date.now() ? { ...s, role: roleOf(s.token) } : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => {
    const s = readSession();
    tokenStore.set(s?.token ?? null);
    return s;
  });
  const queryClient = useQueryClient();

  const logout = useCallback(() => {
    tokenStore.set(null);
    try {
      sessionStorage.removeItem(KEY);
    } catch {
      /* 저장소를 못 쓰는 환경 */
    }
    setSession(null);
    queryClient.clear();
  }, [queryClient]);

  useEffect(() => {
    tokenStore.onUnauthorized(logout);
  }, [logout]);

  const login = useCallback(async (loginId: string, password: string) => {
    const res = await authApi.login({ loginId, password });
    const s: Session = { token: res.accessToken, loginId, expiresAt: Date.now() + res.expiresIn * 1000, role: roleOf(res.accessToken) };
    tokenStore.set(s.token);
    try {
      sessionStorage.setItem(KEY, JSON.stringify(s));
    } catch {
      /* 저장 실패해도 이 탭에서는 로그인 유지 */
    }
    setSession(s);
  }, []);

  const value = useMemo(() => ({ session, login, logout }), [session, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const v = useContext(AuthContext);
  if (!v) throw new Error('AuthProvider 안에서만 쓸 수 있습니다');
  return v;
}
