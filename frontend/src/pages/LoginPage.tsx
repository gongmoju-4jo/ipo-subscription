import { useState, type FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { authApi } from '../api/endpoints';
import { ApiError } from '../api/client';

export function LoginPage() {
  const { session, login } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<'login' | 'signup'>('login');
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | Error | null>(null);

  if (session) return <Navigate to="/" replace />;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === 'signup') await authApi.signUp({ loginId, password, name });
      await login(loginId, password);
      navigate('/');
    } catch (err) {
      setError(err as Error);
    } finally {
      setBusy(false);
    }
  }

  const details = error instanceof ApiError ? error.details : undefined;
  return (
    <div className="center">
      <div className="auth-card stack">
        <div style={{ textAlign: 'center' }}>
          <div className="brand" style={{ justifyContent: 'center', fontSize: 22 }}>
            <span className="brand-mark">공</span>공모주사조
          </div>
          <p className="muted" style={{ marginTop: 8 }}>마감 직전에도 밀리지 않는 청약</p>
        </div>
        <form className="card stack" onSubmit={submit}>
          <div className="tabs" style={{ width: '100%' }}>
            <button type="button" className={mode === 'login' ? 'on' : ''} style={{ flex: 1 }} onClick={() => setMode('login')}>로그인</button>
            <button type="button" className={mode === 'signup' ? 'on' : ''} style={{ flex: 1 }} onClick={() => setMode('signup')}>회원가입</button>
          </div>
          <div className="field">
            <label htmlFor="loginId">아이디</label>
            <input id="loginId" className="input" value={loginId} onChange={(e) => setLoginId(e.target.value)} autoComplete="username" required />
          </div>
          <div className="field">
            <label htmlFor="password">비밀번호</label>
            <input id="password" className="input" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete={mode === 'login' ? 'current-password' : 'new-password'} required />
          </div>
          {mode === 'signup' && (
            <div className="field">
              <label htmlFor="name">이름</label>
              <input id="name" className="input" value={name} onChange={(e) => setName(e.target.value)} required />
            </div>
          )}
          {error && (
            <div className="notice danger">
              {error.message}
              {details?.map((d, i) => (
                <div key={i} style={{ fontSize: 13 }}>{d.field ? `${d.field}: ` : ''}{d.reason}</div>
              ))}
            </div>
          )}
          <button className="btn btn-primary btn-block" disabled={busy}>
            {busy ? '처리 중…' : mode === 'login' ? '로그인' : '가입하고 시작하기'}
          </button>
        </form>
      </div>
    </div>
  );
}
