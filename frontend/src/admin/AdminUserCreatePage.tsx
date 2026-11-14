import { cloneElement, useEffect, useId, useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { adminUserApi } from '../api/endpoints';
import { ApiError } from '../api/client';
import type { SignUpResponse } from '../api/types';
import { Card } from '../components/ui';
import { formatDateTime } from '../lib/format';

// 관리자 계정 생성. 만든 계정은 바로 ADMIN이고, 그 계정으로 로그인하면 관리자 화면이 보인다
// 입력 검사는 화면 안내용이고, 최종 검사는 서버(AdminUserCreateRequest)가 한다. 규칙은 회원가입과 같다
// 비밀번호 변경 API가 없어서 오타를 막으려고 비밀번호 확인을 받는다 (확인 값은 서버로 보내지 않는다)
// 로그인 ID, 비밀번호 칸에는 maxLength를 두지 않는다. 길게 붙여 넣으면 브라우저가 말없이 잘라 다른 비밀번호로 만들어지기 때문
// 대신 20자를 넘는 순간 제출 전이라도 그 칸 아래에 바로 경고를 띄운다

interface Form {
  loginId: string;
  name: string;
  password: string;
  passwordConfirm: string;
}

const EMPTY: Form = { loginId: '', name: '', password: '', passwordConfirm: '' };
const SERVER_FIELDS: (keyof Form)[] = ['loginId', 'name', 'password'];
const MAX_LENGTH = 20;

// 서버가 필드를 짚어 준 사유(400 형식 오류, 409 중복)는 그 입력칸 아래에 보여준다
function fieldErrorsOf(error: unknown): Partial<Record<keyof Form, string>> {
  const e: Partial<Record<keyof Form, string>> = {};
  if (!(error instanceof ApiError)) return e;
  for (const d of error.details ?? []) {
    const field = SERVER_FIELDS.find((f) => f === d.field);
    if (field && !e[field]) e[field] = d.reason;
  }
  return e;
}

// 서버 검사 규칙(AdminUserCreateRequest)과 같은 조건을 화면에서 먼저 안내한다
// 숫자(4~20자, 8~20자, 이름 20자)는 계좌 서비스 UserInputRules와 같게 유지한다 (바꾸면 LoginPage 회원가입 검사도 같이)
function validate(f: Form): Partial<Record<keyof Form, string>> {
  const e: Partial<Record<keyof Form, string>> = {};
  if (!f.loginId) e.loginId = '로그인 ID를 입력해 주세요';
  else if (!/^[a-z0-9]{4,20}$/.test(f.loginId)) e.loginId = '4~20자의 영문 소문자, 숫자만 사용할 수 있어요';
  const name = f.name.trim();
  if (!name) e.name = '이름을 입력해 주세요';
  else if (f.name.length > 20) e.name = '20자 이하로 입력해 주세요';
  if (!f.password.trim()) e.password = '비밀번호를 입력해 주세요';
  else if (f.password.length < 8 || f.password.length > 20) e.password = '8자 이상 20자 이하로 입력해 주세요';
  if (!f.passwordConfirm) e.passwordConfirm = '비밀번호를 한 번 더 입력해 주세요';
  else if (f.passwordConfirm !== f.password) e.passwordConfirm = '비밀번호가 서로 달라요';
  return e;
}

export function AdminUserCreatePage() {
  const [form, setForm] = useState<Form>(EMPTY);
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  // 서버가 짚어 준 필드 오류. 그 필드를 고치면 지운다
  const [serverErrors, setServerErrors] = useState<Partial<Record<keyof Form, string>>>({});
  const [created, setCreated] = useState<SignUpResponse | null>(null);

  const errors = validate(form);
  const hasError = Object.keys(errors).length > 0;
  // 제출 전에는 20자를 넘은 칸만 바로 경고한다 (나머지 검사는 제출할 때 보여준다)
  const shown = (k: keyof Form) => (submitted || form[k].length > MAX_LENGTH ? errors[k] : undefined) ?? serverErrors[k];
  const set = (k: keyof Form) => (v: string) => {
    setForm((f) => ({ ...f, [k]: v }));
    setServerErrors(({ [k]: _, ...rest }) => rest);
  };
  // 필드 오류로 다 보여준 경우는 아래 박스를 띄우지 않는다
  const boxError = error !== null && Object.keys(fieldErrorsOf(error)).length === 0 ? error : null;

  async function submit(ev: FormEvent) {
    ev.preventDefault();
    setSubmitted(true);
    setError(null);
    setServerErrors({});
    if (hasError || busy) return;
    setBusy(true);
    try {
      setCreated(await adminUserApi.create({ loginId: form.loginId, password: form.password, name: form.name }));
    } catch (e) {
      setError(e);
      setServerErrors(fieldErrorsOf(e));
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    setForm(EMPTY);
    setSubmitted(false);
    setError(null);
    setServerErrors({});
    setCreated(null);
  }

  // 만든 뒤 화면에서 상단의 관리자 계정 추가를 다시 누르면 같은 주소라 화면이 그대로 남는다. 이동할 때마다 바뀌는 key로 처음 화면으로 돌린다
  const locationKey = useLocation().key;
  useEffect(() => {
    reset();
  }, [locationKey]);

  if (created) return <Created user={created} onAnother={reset} />;

  return (
    <div className="stack" style={{ maxWidth: 520 }}>
      <div>
        <div className="page-title">관리자 계정 만들기</div>
        <p className="page-sub">관리자 전용. 만든 계정은 공모주 등록, 추첨 재현 같은 관리자 기능을 쓸 수 있어요</p>
      </div>

      <form onSubmit={submit} noValidate>
        <Card>
          <div className="stack">
            <Field label="로그인 ID" error={shown('loginId')} hint="4~20자 영문 소문자, 숫자">
              <input className="input" value={form.loginId} autoComplete="off" onChange={(e) => set('loginId')(e.target.value)} />
            </Field>
            <Field label="이름" error={shown('name')}>
              <input className="input" value={form.name} maxLength={20} autoComplete="off" onChange={(e) => set('name')(e.target.value)} placeholder="예: 운영자" />
            </Field>
            <Field label="비밀번호" error={shown('password')} hint="8자 이상 20자 이하">
              <input className="input" type="password" value={form.password} autoComplete="new-password" onChange={(e) => set('password')(e.target.value)} />
            </Field>
            <Field label="비밀번호 확인" error={shown('passwordConfirm')}>
              <input className="input" type="password" value={form.passwordConfirm} autoComplete="new-password" onChange={(e) => set('passwordConfirm')(e.target.value)} />
            </Field>

            {submitted && hasError && <div className="error-text">입력값을 확인해 주세요 ({Object.keys(errors).length}개 항목)</div>}
            {boxError !== null && <SubmitError error={boxError} />}
            <button type="submit" className="btn btn-primary btn-block" disabled={busy || (submitted && hasError)}>
              {busy ? '만드는 중' : '관리자 계정 만들기'}
            </button>
          </div>
        </Card>
      </form>
    </div>
  );
}

// 클립보드 API는 HTTPS(또는 localhost)에서만 있다. 배포 주소는 HTTP라서 예전 방식(execCommand)으로 복사한다
async function copyText(text: string): Promise<boolean> {
  try {
    if (window.isSecureContext && navigator.clipboard) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // 아래 방식으로 다시 시도한다
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try {
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    ta.remove();
  }
}

// 만든 뒤 화면. 계정 주인에게 전달할 로그인 ID를 크게 보여주고 바로 복사할 수 있게 한다
function Created({ user, onAnother }: { user: SignUpResponse; onAnother: () => void }) {
  const navigate = useNavigate();
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const t = setTimeout(() => setCopied(false), 1500);
    return () => clearTimeout(t);
  }, [copied]);

  async function copy() {
    if (await copyText(user.loginId)) setCopied(true);
  }

  return (
    <div className="created">
      <div className="created-check" aria-hidden>
        <svg viewBox="0 0 24 24" width="28" height="28"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
      </div>
      <div className="created-title">관리자 계정이 생겼어요</div>
      <button type="button" className={`created-id${copied ? ' copied' : ''}`} onClick={copy} title="눌러서 로그인 ID 복사">
        <span>{user.loginId}</span>
        <span className="created-id-hint">{copied ? '복사됨' : '복사'}</span>
      </button>
      <div className="created-meta">{user.name} · {formatDateTime(user.createdAt)}</div>
      <div className="created-actions">
        <button type="button" className="btn btn-primary" onClick={() => navigate('/')}>완료</button>
        <button type="button" className="link-btn" onClick={onAnother}>다른 계정 만들기</button>
      </div>
    </div>
  );
}

// 레이블과 입력 요소를 id로 연결한다 (스크린 리더가 입력 필드를 구분 가능)
function Field({ label, error, hint, children }: { label: string; error?: string; hint?: string; children: JSX.Element }) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {cloneElement(children, { id })}
      {error ? <span className="error-text">{error}</span> : hint && <span className="hint">{hint}</span>}
    </div>
  );
}

// 필드와 관계없는 실패만 보여준다 (403 권한, 5xx, 응답 없음). 제목 한 줄과 서버 메시지 한 줄
// 응답을 받지 못한 경우는 만들어졌을 수 있어 같은 ID로 다시 시도해 확인하도록 안내한다
function SubmitError({ error }: { error: unknown }) {
  const e = error instanceof ApiError ? error : null;
  const unknownResult = !e || e.status === 0 || e.status >= 500;
  const title = e?.status === 403 ? '관리자만 만들 수 있어요' : '만들지 못했어요';
  return (
    <div className="notice danger">
      <strong>{title}</strong>
      <div>{e ? e.message : '알 수 없는 오류'}</div>
      {unknownResult && (
        <div style={{ fontSize: 13, marginTop: 4 }}>
          만들어졌을 수 있어요. 같은 로그인 ID로 다시 시도해 "이미 사용 중"이 나오면 만들어진 거예요
        </div>
      )}
    </div>
  );
}
