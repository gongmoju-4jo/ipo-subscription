import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useAccounts, useIpo, keys } from '../api/queries';
import { subscriptionApi } from '../api/endpoints';
import { ApiError, newRequestId } from '../api/client';
import { Badge, Card, ErrorBox, Loading, Money, RecentExchanges } from '../components/ui';
import { formatDateTime, formatWon } from '../lib/format';
import { ipoStatusLabel } from '../lib/status';

export function IpoDetailPage() {
  const { ipoId = '' } = useParams();
  const ipo = useIpo(ipoId);
  const accounts = useAccounts();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [qty, setQty] = useState<number>(0);
  const [accountId, setAccountId] = useState('');
  // 같은 신청을 여러 번 눌러도 한 건이 되도록 requestId를 화면이 열린 동안 유지한다 (멱등키)
  const [requestId, setRequestId] = useState(newRequestId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (ipo.data && qty === 0) setQty(ipo.data.minQuantity);
  }, [ipo.data, qty]);
  useEffect(() => {
    const first = accounts.data?.items[0];
    if (first && !accountId) setAccountId(first.accountId);
  }, [accounts.data, accountId]);

  const account = accounts.data?.items.find((a) => a.accountId === accountId);
  const expected = useMemo(() => (ipo.data ? Math.round(qty * ipo.data.offerPrice * Number(ipo.data.marginRate)) : 0), [qty, ipo.data]);

  if (ipo.isLoading) return <Card><Loading lines={6} /></Card>;
  if (ipo.error || !ipo.data) return <ErrorBox error={ipo.error} />;
  const d = ipo.data;
  const st = ipoStatusLabel(d.status, d.subscriptionStartAt, d.subscriptionEndAt);
  const canSubscribe = st.label === '청약 중';
  const qtyError = qty < d.minQuantity ? `최소 ${d.minQuantity.toLocaleString()}주부터 청약할 수 있어요` : qty > d.maxQuantity ? `최대 ${d.maxQuantity.toLocaleString()}주까지 청약할 수 있어요` : null;
  const short = account ? expected > account.cashBalance : false;

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const res = await subscriptionApi.create({ requestId, ipoId, accountId, requestedQuantity: qty });
      queryClient.invalidateQueries({ queryKey: keys.subscriptions });
      queryClient.invalidateQueries({ queryKey: keys.accounts });
      navigate(`/subscriptions/${res.subscriptionId}?from=new`);
    } catch (e) {
      setError(e);
      // 업무 오류(마감 등)는 같은 요청을 다시 보내도 결과가 같아서 새 요청 ID로 바꾼다
      if (e instanceof ApiError && e.status < 500) setRequestId(newRequestId());
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <Link to="/ipos" className="muted" style={{ fontSize: 14 }}>← 공모주 목록</Link>
      <div className="row between wrap">
        <div>
          <div className="row" style={{ gap: 10 }}>
            <div className="page-title" style={{ margin: 0 }}>{d.name}</div>
            <Badge tone={st.tone}>{st.label}</Badge>
          </div>
          <p className="page-sub" style={{ marginTop: 6 }}>공모가 {formatWon(d.offerPrice)}</p>
        </div>
      </div>

      <div className="grid grid-2">
        <Card title="청약 정보">
          <dl className="kv">
            <dt>청약 기간</dt>
            <dd>{formatDateTime(d.subscriptionStartAt)} ~ {formatDateTime(d.subscriptionEndAt)}</dd>
            <dt>환불일</dt>
            <dd>{d.refundDate}</dd>
            <dt>일반청약 물량</dt>
            <dd className="num">{d.retailQuantity.toLocaleString()}주</dd>
            <dt>균등배정 물량</dt>
            <dd className="num">{d.equalQuantity.toLocaleString()}주</dd>
            <dt>청약 수량</dt>
            <dd className="num">{d.minQuantity.toLocaleString()}주 ~ {d.maxQuantity.toLocaleString()}주</dd>
            <dt>증거금률</dt>
            <dd>{Math.round(Number(d.marginRate) * 100)}%</dd>
          </dl>
          <div className="notice gray" style={{ marginTop: 16 }}>
            마감 후 균등배정(최소 수량 이상 청약자에게 같은 몫)과 비례배정(청약 수량 비례)을 하고, 배정받지 못한 증거금은 예수금으로 돌려드려요.
          </div>
        </Card>

        <Card title="청약하기">
          {!canSubscribe ? (
            <div className="notice gray">지금은 청약할 수 없어요 ({st.label})</div>
          ) : accounts.data && accounts.data.items.length === 0 ? (
            <div className="notice info">
              청약하려면 계좌가 필요해요. <Link to="/account" style={{ fontWeight: 700 }}>계좌 만들기 →</Link>
            </div>
          ) : (
            <div className="stack">
              <div className="field">
                <label>계좌</label>
                <select className="input" value={accountId} onChange={(e) => setAccountId(e.target.value)}>
                  {accounts.data?.items.map((a) => (
                    <option key={a.accountId} value={a.accountId}>
                      {a.accountNumber} (예수금 {formatWon(a.cashBalance)})
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label>청약 수량</label>
                <input
                  className="input num"
                  type="number"
                  min={d.minQuantity}
                  max={d.maxQuantity}
                  value={qty || ''}
                  onChange={(e) => setQty(Math.floor(Number(e.target.value)))}
                />
                {qtyError && <span className="error-text">{qtyError}</span>}
              </div>
              <div className="card" style={{ background: 'var(--surface-2)', boxShadow: 'none', padding: 16 }}>
                <div className="row between">
                  <span className="sub">예상 청약증거금</span>
                  <Money value={expected} size="mid" />
                </div>
                <div className="hint" style={{ marginTop: 4 }}>
                  {qty.toLocaleString()}주 x {formatWon(d.offerPrice)} x 증거금률 {Math.round(Number(d.marginRate) * 100)}%. 최종 금액은 청약 확정 때 서버가 정해요
                </div>
                {short && <div className="error-text" style={{ marginTop: 8 }}>예수금이 {formatWon(expected - (account?.cashBalance ?? 0))} 부족해요. 신청하면 거절돼요</div>}
              </div>
              {error !== null && <ErrorBox error={error} title="청약을 접수하지 못했어요" />}
              <button className="btn btn-primary btn-block" disabled={busy || !!qtyError || !accountId} onClick={submit}>
                {busy ? '접수 중…' : `${qty.toLocaleString()}주 청약하기`}
              </button>
              <div className="hint">신청하면 먼저 "접수"되고, 증거금 인출이 끝나면 "확정"돼요.</div>
            </div>
          )}
        </Card>
      </div>
      <RecentExchanges filter={(r) => r.path.startsWith('/api/v1/subscriptions') || r.path.startsWith('/api/v1/ipos')} />
    </div>
  );
}
