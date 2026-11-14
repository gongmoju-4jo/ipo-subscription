import { useState, type FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { authApi } from '../api/endpoints';
import { ApiError } from '../api/client';
import { LogoMark } from '../components/Logo';

type Field = 'loginId' | 'password' | 'passwordConfirm' | 'name';
type FieldErrors = Partial<Record<Field, string>>;

// 계좌 서비스 UserInputRules와 같게 유지한다 (바꾸면 AdminUserCreatePage 검사도 같이)
const MAX_LENGTH = 20;

// 가입할 때 20자를 넘는 순간 그 칸 아래에 바로 경고한다 (글자를 막으면 붙여 넣은 비밀번호가 말없이 잘린다)
// 나머지 형식 검사는 서버가 하고, 서버가 짚어 준 사유를 칸 아래에 보여준다
function liveSignupErrors(loginId: string, password: string, name: string): FieldErrors {
  const e: FieldErrors = {};
  if (loginId.length > MAX_LENGTH) e.loginId = '4~20자의 영문 소문자, 숫자만 사용할 수 있어요';
  if (password.length > MAX_LENGTH) e.password = '8자 이상 20자 이하로 입력해 주세요';
  if (name.length > MAX_LENGTH) e.name = '20자 이하로 입력해 주세요';
  return e;
}

// 서버가 필드를 짚어 준 사유(400 형식 오류, 409 중복)는 그 입력칸 아래에 한 줄로 보여준다
function fieldErrorsOf(error: unknown): FieldErrors {
  const e: FieldErrors = {};
  if (!(error instanceof ApiError)) return e;
  for (const d of error.details ?? []) {
    if ((d.field === 'loginId' || d.field === 'password' || d.field === 'name') && !e[d.field]) e[d.field] = d.reason;
  }
  return e;
}

export function LoginPage() {
  const { session, login } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<'login' | 'signup'>('login');
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  // 가입할 때만 받는다. 서버로 보내지 않고 화면에서만 비교한다
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | Error | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});

  if (session) return <Navigate to="/" replace />;

  // 입력을 고치면 그 칸의 오류는 지운다
  const clearField = (f: Field) => setFieldErrors(({ [f]: _, ...rest }) => rest);

  function switchMode(next: 'login' | 'signup') {
    setMode(next);
    setError(null);
    setFieldErrors({});
    setPasswordConfirm('');
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});
    if (mode === 'signup') {
      const live = liveSignupErrors(loginId, password, name);
      if (Object.keys(live).length > 0) return;
      if (passwordConfirm !== password) {
        setFieldErrors({ passwordConfirm: '비밀번호가 서로 달라요' });
        return;
      }
    }
    setBusy(true);
    try {
      if (mode === 'signup') {
        await authApi.signUp({ loginId, password, name });
        // 가입 뒤 로그인이 실패해 다시 누르면 가입은 건너뛰고 로그인만 하게
        setMode('login');
      }
      await login(loginId, password);
      navigate('/');
    } catch (err) {
      setError(err as Error);
      setFieldErrors(fieldErrorsOf(err));
    } finally {
      setBusy(false);
    }
  }

  // 필드 오류로 다 보여준 경우는 박스를 띄우지 않는다 (같은 문장이 두 번 보이지 않게)
  const showBox = error !== null && Object.keys(fieldErrorsOf(error)).length === 0;
  // 가입 중 20자 초과는 바로, 그 외는 서버나 제출 때 정해진 오류
  const live = mode === 'signup' ? liveSignupErrors(loginId, password, name) : {};
  const shown = (f: Field) => live[f] ?? fieldErrors[f];
  const hint = (text: string) => (mode === 'signup' ? <span className="hint">{text}</span> : null);
  return (
    <div className="center">
      <div className="auth-card stack">
        <div style={{ textAlign: 'center' }}>
          <div className="brand" style={{ justifyContent: 'center', fontSize: 22 }}>
            <LogoMark size={36} />공모주사조
          </div>
          <p className="muted" style={{ marginTop: 8 }}>마감 직전에도 밀리지 않는 청약</p>
        </div>
        <form className="card stack" onSubmit={submit}>
          <div className="tabs" style={{ width: '100%' }}>
            {/* 요청 중에는 탭을 바꾸지 못하게 한다. 가입 중 로그인 탭으로 바꾸면 이름 칸 오류를 보여 줄 칸이 없어 실패 사유가 사라진다 */}
            <button type="button" className={mode === 'login' ? 'on' : ''} style={{ flex: 1 }} disabled={busy} onClick={() => switchMode('login')}>로그인</button>
            <button type="button" className={mode === 'signup' ? 'on' : ''} style={{ flex: 1 }} disabled={busy} onClick={() => switchMode('signup')}>회원가입</button>
          </div>
          <div className="field">
            <label htmlFor="loginId">아이디</label>
            <input id="loginId" className="input" value={loginId} onChange={(e) => { setLoginId(e.target.value); clearField('loginId'); }} autoComplete="username" required />
            {shown('loginId') ? <span className="error-text">{shown('loginId')}</span> : hint('4~20자 영문 소문자, 숫자')}
          </div>
          <div className="field">
            <label htmlFor="password">비밀번호</label>
            <input id="password" className="input" type="password" value={password} onChange={(e) => { setPassword(e.target.value); clearField('password'); }} autoComplete={mode === 'login' ? 'current-password' : 'new-password'} required />
            {shown('password') ? <span className="error-text">{shown('password')}</span> : hint('8자 이상 20자 이하')}
          </div>
          {mode === 'signup' && (
            <div className="field">
              <label htmlFor="passwordConfirm">비밀번호 확인</label>
              <input id="passwordConfirm" className="input" type="password" value={passwordConfirm} onChange={(e) => { setPasswordConfirm(e.target.value); clearField('passwordConfirm'); }} autoComplete="new-password" required />
              {shown('passwordConfirm') && <span className="error-text">{shown('passwordConfirm')}</span>}
            </div>
          )}
          {mode === 'signup' && (
            <div className="field">
              <label htmlFor="name">이름</label>
              <input id="name" className="input" value={name} onChange={(e) => { setName(e.target.value); clearField('name'); }} required />
              {shown('name') && <span className="error-text">{shown('name')}</span>}
            </div>
          )}
          {showBox && <div className="notice danger">{error?.message}</div>}
          <button className="btn btn-primary btn-block" disabled={busy}>
            {busy ? '처리 중…' : mode === 'login' ? '로그인' : '가입하고 시작하기'}
          </button>
        </form>
      </div>
    </div>
  );
}
