import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { adminIpoApi } from '../api/endpoints';
import { ApiError } from '../api/client';
import { keys } from '../api/queries';
import type { IpoCreateRequest, IpoCreateResponse } from '../api/types';
import { Badge, Card } from '../components/ui';
import { formatDateTime, formatWon } from '../lib/format';
import { ipoStatusLabel } from '../lib/status';

// 관리자 공모주 등록. 등록하면 OPEN 상태가 되고, 대기열 진입은 게이트웨이 목록 갱신(최대 5초) 후부터 가능하다
// 입력 검사는 화면 안내용이고, 최종 검사는 서버(Ipo.register)가 한다

const KST_OFFSET = '+09:00';
const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;
const END_PRESETS = [10, 30, 60];

// 시각 입력(datetime-local)은 한국 시간 기준 "YYYY-MM-DDTHH:mm" 문자열로 다룬다
function kstInput(ms: number): string {
  return new Date(ms + 9 * 60 * MINUTE).toISOString().slice(0, 16);
}
function kstDate(ms: number): string {
  return new Date(ms + 9 * 60 * MINUTE).toISOString().slice(0, 10);
}
function inputToMs(v: string): number {
  return new Date(`${v}:00${KST_OFFSET}`).getTime();
}

interface Form {
  name: string;
  offerPrice: string;
  retailQuantity: string;
  equalQuantity: string;
  minQuantity: string;
  maxQuantity: string;
  startAt: string;
  endAt: string;
  refundDate: string;
}

function initialForm(): Form {
  const now = Math.floor(Date.now() / MINUTE) * MINUTE;
  const end = now + 30 * MINUTE;
  return {
    name: '',
    offerPrice: '15000',
    retailQuantity: '1000',
    equalQuantity: '500',
    minQuantity: '10',
    maxQuantity: '100',
    startAt: kstInput(now),
    endAt: kstInput(end),
    refundDate: kstDate(end + 2 * DAY),
  };
}

// 양의 정수가 아니면 null
function toPositiveInt(v: string): number | null {
  if (!/^\d+$/.test(v.trim())) return null;
  const n = Number(v);
  return Number.isSafeInteger(n) && n >= 1 ? n : null;
}

// 서버 검사 규칙(IpoCreateRequest, Ipo.register)과 동일한 조건을 화면에서 먼저 안내한다
function validate(f: Form): Partial<Record<keyof Form, string>> {
  const e: Partial<Record<keyof Form, string>> = {};
  const name = f.name.trim();
  if (!name) e.name = '종목명을 입력해 주세요';
  else if (name.length > 30) e.name = '30자 이하로 입력해 주세요';

  const price = toPositiveInt(f.offerPrice);
  if (price === null) e.offerPrice = '1원 이상 정수로 입력해 주세요';
  const retail = toPositiveInt(f.retailQuantity);
  if (retail === null) e.retailQuantity = '1주 이상 정수로 입력해 주세요';
  const equal = toPositiveInt(f.equalQuantity);
  if (equal === null) e.equalQuantity = '1주 이상 정수로 입력해 주세요';
  else if (retail !== null && (equal * 2 < retail || equal > retail)) e.equalQuantity = `일반청약 물량의 절반 이상, 전체 이하 (${Math.ceil(retail / 2).toLocaleString()}-${retail.toLocaleString()}주)`;
  const min = toPositiveInt(f.minQuantity);
  if (min === null) e.minQuantity = '1주 이상 정수로 입력해 주세요';
  const max = toPositiveInt(f.maxQuantity);
  if (max === null) e.maxQuantity = '1주 이상 정수로 입력해 주세요';
  else if (min !== null && max < min) e.maxQuantity = '최소 청약 수량 이상으로 입력해 주세요';

  const start = inputToMs(f.startAt);
  const end = inputToMs(f.endAt);
  if (Number.isNaN(start)) e.startAt = '시작 시각을 입력해 주세요';
  if (Number.isNaN(end)) e.endAt = '마감 시각을 입력해 주세요';
  else if (end <= Date.now()) e.endAt = '마감 시각은 지금보다 이후여야 해요';
  else if (!Number.isNaN(start) && end <= start) e.endAt = '마감 시각은 시작 시각보다 이후여야 해요';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(f.refundDate)) e.refundDate = '환불일을 입력해 주세요';
  else if (!Number.isNaN(end) && f.refundDate <= kstDate(end)) e.refundDate = '환불일은 마감일 다음 날 이후여야 해요';
  return e;
}

function toRequest(f: Form): IpoCreateRequest {
  return {
    name: f.name.trim(),
    offerPrice: Number(f.offerPrice),
    retailQuantity: Number(f.retailQuantity),
    equalQuantity: Number(f.equalQuantity),
    minQuantity: Number(f.minQuantity),
    maxQuantity: Number(f.maxQuantity),
    subscriptionStartAt: `${f.startAt}:00${KST_OFFSET}`,
    subscriptionEndAt: `${f.endAt}:00${KST_OFFSET}`,
    refundDate: f.refundDate,
  };
}

export function AdminIpoCreatePage() {
  const queryClient = useQueryClient();
  const [form, setForm] = useState<Form>(initialForm);
  // 균등 물량을 직접 수정하기 전까지는 일반청약 물량의 절반으로 자동 계산한다
  const [equalTouched, setEqualTouched] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [created, setCreated] = useState<IpoCreateResponse | null>(null);

  const errors = validate(form);
  const hasError = Object.keys(errors).length > 0;
  const shown = (k: keyof Form) => (submitted ? errors[k] : undefined);

  const set = (k: keyof Form) => (v: string) =>
    setForm((f) => {
      const next = { ...f, [k]: v };
      if (k === 'retailQuantity' && !equalTouched) {
        const retail = toPositiveInt(v);
        next.equalQuantity = retail === null ? f.equalQuantity : String(Math.ceil(retail / 2));
      }
      return next;
    });

  // 시작은 지금, 마감은 N분 후로 설정한다 (시연에서 바로 청약 중으로 등록)
  const applyPreset = (minutes: number) => {
    const now = Math.floor(Date.now() / MINUTE) * MINUTE;
    const end = now + minutes * MINUTE;
    setForm((f) => ({ ...f, startAt: kstInput(now), endAt: kstInput(end), refundDate: kstDate(end + 2 * DAY) }));
  };

  async function submit(ev: FormEvent) {
    ev.preventDefault();
    setSubmitted(true);
    setError(null);
    if (hasError || busy) return;
    setBusy(true);
    try {
      const r = await adminIpoApi.create(toRequest(form));
      setCreated(r);
      queryClient.invalidateQueries({ queryKey: keys.ipos });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    setForm(initialForm());
    setEqualTouched(false);
    setSubmitted(false);
    setError(null);
    setCreated(null);
  }

  if (created) {
    return (
      <div className="stack">
        <Link to="/ipos" className="muted" style={{ fontSize: 14 }}>← 공모주 목록</Link>
        <div className="page-title">공모주를 등록했어요</div>
        <Card>
          <dl className="kv">
            <dt>종목명</dt>
            <dd>{created.name}</dd>
            <dt>공모가</dt>
            <dd className="num">{formatWon(created.offerPrice)}</dd>
            <dt>청약 기간</dt>
            <dd>{formatDateTime(created.subscriptionStartAt)} - {formatDateTime(created.subscriptionEndAt)}</dd>
            <dt>일반청약 물량</dt>
            <dd className="num">{created.retailQuantity.toLocaleString()}주 (균등 {created.equalQuantity.toLocaleString()}주)</dd>
            <dt>상태</dt>
            <dd><CreatedStatus ipo={created} /></dd>
          </dl>
          <div className="notice info" style={{ marginTop: 16 }}>대기열 진입은 등록 후 최대 5초 후부터 가능해요</div>
          <div className="row" style={{ marginTop: 16 }}>
            <Link to={`/ipos/${created.ipoId}`} className="btn btn-primary" style={{ flex: 1 }}>공모주 상세로</Link>
            <button className="btn btn-ghost" style={{ flex: 1 }} onClick={reset}>하나 더 등록</button>
          </div>
        </Card>
      </div>
    );
  }

  return (
    <div className="stack">
      <Link to="/ipos" className="muted" style={{ fontSize: 14 }}>← 공모주 목록</Link>
      <div>
        <div className="page-title">공모주 등록</div>
        <p className="page-sub">관리자 전용. 등록하면 바로 청약 중(OPEN) 상태가 돼요</p>
      </div>

      <form onSubmit={submit} noValidate>
        <div className="grid grid-2">
          <Card title="종목, 물량">
            <div className="stack">
              <Field label="종목명" error={shown('name')}>
                <input className="input" value={form.name} maxLength={30} onChange={(e) => set('name')(e.target.value)} placeholder="예: 테스트 공모주 A" />
              </Field>
              <Field label="공모가 (원)" error={shown('offerPrice')}>
                <input className="input num" inputMode="numeric" value={form.offerPrice} onChange={(e) => set('offerPrice')(e.target.value)} />
              </Field>
              <Field label="일반청약 물량 (주)" error={shown('retailQuantity')}>
                <input className="input num" inputMode="numeric" value={form.retailQuantity} onChange={(e) => set('retailQuantity')(e.target.value)} />
              </Field>
              <Field label="균등배정 물량 (주)" error={shown('equalQuantity')} hint={equalTouched ? undefined : '일반청약 물량의 절반으로 자동 계산'}>
                <input
                  className="input num"
                  inputMode="numeric"
                  value={form.equalQuantity}
                  onChange={(e) => {
                    setEqualTouched(true);
                    set('equalQuantity')(e.target.value);
                  }}
                />
              </Field>
              <div className="grid grid-2">
                <Field label="최소 청약 수량 (주)" error={shown('minQuantity')}>
                  <input className="input num" inputMode="numeric" value={form.minQuantity} onChange={(e) => set('minQuantity')(e.target.value)} />
                </Field>
                <Field label="1인 청약 한도 (주)" error={shown('maxQuantity')}>
                  <input className="input num" inputMode="numeric" value={form.maxQuantity} onChange={(e) => set('maxQuantity')(e.target.value)} />
                </Field>
              </div>
              <div className="hint">증거금률은 100% 고정이에요</div>
            </div>
          </Card>

          <Card title="청약 기간">
            <div className="stack">
              <div className="row wrap" style={{ gap: 8 }}>
                <span className="hint">지금 시작, 마감</span>
                {END_PRESETS.map((m) => (
                  <button key={m} type="button" className="btn btn-ghost btn-sm" onClick={() => applyPreset(m)}>
                    {m < 60 ? `${m}분 후` : `${m / 60}시간 후`}
                  </button>
                ))}
              </div>
              <Field label="청약 시작 (한국 시간)" error={shown('startAt')} hint="이미 지난 시각이면 등록 즉시 청약 중이에요">
                <input className="input" type="datetime-local" value={form.startAt} onChange={(e) => set('startAt')(e.target.value)} />
              </Field>
              <Field label="청약 마감 (한국 시간)" error={shown('endAt')}>
                <input className="input" type="datetime-local" value={form.endAt} onChange={(e) => set('endAt')(e.target.value)} />
              </Field>
              <Field label="환불일" error={shown('refundDate')} hint="마감일 다음 날 이후">
                <input className="input" type="date" value={form.refundDate} onChange={(e) => set('refundDate')(e.target.value)} />
              </Field>

              {submitted && hasError && <div className="error-text">입력값을 확인해 주세요 ({Object.keys(errors).length}개 항목)</div>}
              {error !== null && <SubmitError error={error} />}
              <button type="submit" className="btn btn-primary btn-block" disabled={busy || (submitted && hasError)}>
                {busy ? '등록 중' : '공모주 등록'}
              </button>
            </div>
          </Card>
        </div>
      </form>
    </div>
  );
}

function CreatedStatus({ ipo }: { ipo: IpoCreateResponse }) {
  const st = ipoStatusLabel(ipo.status, ipo.subscriptionStartAt, ipo.subscriptionEndAt);
  return <Badge tone={st.tone}>{st.label}</Badge>;
}

function Field({ label, error, hint, children }: { label: string; error?: string; hint?: string; children: JSX.Element }) {
  return (
    <div className="field">
      <label>{label}</label>
      {children}
      {error ? <span className="error-text">{error}</span> : hint && <span className="hint">{hint}</span>}
    </div>
  );
}

// 서버 거절(400, 403)은 필드별 사유를 보여준다. 응답을 받지 못한 경우는 등록됐을 수 있어 목록 확인을 안내한다
function SubmitError({ error }: { error: unknown }) {
  const e = error instanceof ApiError ? error : null;
  const unknownResult = !e || e.status === 0 || e.status >= 500;
  return (
    <div className="notice danger">
      <strong>{e?.status === 403 ? '관리자만 등록할 수 있어요' : '등록하지 못했어요'}</strong>
      <div>{e ? e.message : '알 수 없는 오류'}</div>
      {e?.details?.map((d, i) => (
        <div key={i} style={{ fontSize: 13 }}>{d.field ? `${d.field}: ` : ''}{d.reason}</div>
      ))}
      {unknownResult && (
        <div style={{ fontSize: 13, marginTop: 4 }}>
          등록됐을 수 있어요. <Link to="/ipos" style={{ fontWeight: 700 }}>공모주 목록</Link>을 확인한 후 다시 시도해 주세요
        </div>
      )}
    </div>
  );
}
