import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useAccounts, useIpo, keys } from '../api/queries';
import { subscriptionApi } from '../api/endpoints';
import { ApiError, newRequestId } from '../api/client';
import { Card, ErrorBox, Loading, Money } from '../components/ui';
import { formatWon } from '../lib/format';
import { ipoStatusLabel } from '../lib/status';
import { DesignNote } from '../demo/DesignNote';
import { IpoInfoCard } from '../components/IpoInfoCard';
import { useAdmission } from '../queue/useAdmission';
import { AdmissionTimer, QueueSheet } from '../queue/QueueUi';

// 청약 화면. 들어오면 대기열에 진입하고, 입장 허가를 받으면 입력할 수 있다 (허가 시간 안에 청약해야 함).
// 청약 신청은 게이트웨이가 입장 허가를 확인한다. 허가 없이 보내면 403 QUEUE_ADMISSION_REQUIRED
export function IpoSubscribePage() {
  const { ipoId = '' } = useParams();
  const ipo = useIpo(ipoId);
  const accounts = useAccounts();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const st = ipo.data ? ipoStatusLabel(ipo.data.status, ipo.data.subscriptionStartAt, ipo.data.subscriptionEndAt) : null;
  const canSubscribe = st?.label === '청약 중';
  const hasAccount = (accounts.data?.items.length ?? 0) > 0;
  const admission = useAdmission(ipoId, {
    enabled: canSubscribe && hasAccount,
    closesAt: ipo.data?.subscriptionEndAt,
  });

  const [qty, setQty] = useState<number>(0);
  const [accountId, setAccountId] = useState('');
  // 결과가 불명확한 요청은 제출 당시 조건과 함께 보존한다.
  const [pending, setPending] = useState<{
    ipoId: string; accountId: string; qty: number; requestId: string; subscriptionId?: string;
  } | null>(null);
  const submitting = useRef(false);
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

  if (ipo.isLoading || accounts.isLoading) return <Card><Loading lines={6} /></Card>;
  if (ipo.error || !ipo.data) return <ErrorBox error={ipo.error} />;
  if (accounts.error) return <ErrorBox error={accounts.error} />;
  const d = ipo.data;
  const admitted = admission.phase === 'admitted';
  const qtyError = qty < d.minQuantity ? `최소 ${d.minQuantity.toLocaleString()}주부터 청약할 수 있어요` : qty > d.maxQuantity ? `최대 ${d.maxQuantity.toLocaleString()}주까지 청약할 수 있어요` : null;
  const short = account ? expected > account.cashBalance : false;
  const toDetail = () => navigate(`/ipos/${ipoId}`);

  async function submit() {
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true);
    setError(null);
    const sameConditions = pending && pending.ipoId === ipoId && pending.accountId === accountId && pending.qty === qty;
    try {
      if (pending && !sameConditions) {
        // 원래 내용으로 재접수해 응답을 잃은 청약 ID를 복구한다.
        // 조회 404도 저장 지연일 수 있으므로 새 요청을 허용하지 않는다.
        const subscriptionId = pending.subscriptionId ?? (await subscriptionApi.create({
          requestId: pending.requestId, ipoId: pending.ipoId,
          accountId: pending.accountId, requestedQuantity: pending.qty,
        })).subscriptionId;
        setPending({ ...pending, subscriptionId });
        const previous = await subscriptionApi.get(subscriptionId);
        if (previous.status !== 'REJECTED' && previous.status !== 'CANCELLED') {
          navigate(`/subscriptions/${subscriptionId}`);
          return;
        }
        setPending(null);
      }

      const sent = sameConditions ? pending : { ipoId, accountId, qty, requestId: newRequestId() };
      setPending(sent);
      try {
        const res = await subscriptionApi.create({
          requestId: sent.requestId, ipoId: sent.ipoId,
          accountId: sent.accountId, requestedQuantity: sent.qty,
        });
        setPending(null);
        queryClient.invalidateQueries({ queryKey: keys.subscriptions });
        queryClient.invalidateQueries({ queryKey: keys.accounts });
        navigate(`/subscriptions/${res.subscriptionId}?from=new`);
      } catch (e) {
        // 최초 제출의 4xx만 확정 실패다. 재시도 오류는 이전 접수 여부를 확정하지 못한다.
        if (!sameConditions && e instanceof ApiError && e.status >= 400 && e.status < 500) setPending(null);
        throw e;
      }
    } catch (e) {
      // 입장 허가가 없거나 만료됨: 오류 대신 대기열 다시 진입 안내로 바꾼다 (게이트웨이가 막아 서비스로 가지 않은 요청)
      if (e instanceof ApiError && e.code === 'QUEUE_ADMISSION_REQUIRED') admission.markExpired();
      else setError(e);
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }

  const showSheet = admission.phase === 'waiting' || admission.phase === 'removed' || admission.phase === 'closed';

  return (
    <div className="stack">
      <Link to={`/ipos/${ipoId}`} className="muted" style={{ fontSize: 14 }}>← {d.name}</Link>
      <div>
        <div className="page-title" style={{ margin: 0 }}>{d.name} 청약</div>
        <p className="page-sub" style={{ marginTop: 6 }}>공모가 {formatWon(d.offerPrice)} · {d.minQuantity.toLocaleString()}주 ~ {d.maxQuantity.toLocaleString()}주</p>
      </div>

      <div className="subscribe-layout">
        <Card>
          {!canSubscribe ? (
            <div className="notice gray">지금은 청약할 수 없어요 ({st?.label})</div>
          ) : !hasAccount ? (
            <div className="notice info">
              청약하려면 계좌가 필요해요. <Link to="/account" style={{ fontWeight: 700 }}>계좌 만들기 →</Link>
            </div>
          ) : admission.error instanceof ApiError && admission.error.status === 422 ? (
            // 게이트웨이가 청약 중인 공모주가 아니라고 판단 (화면을 여는 사이 마감됐거나 아직 시작 전)
            <div className="stack">
              <div className="notice gray">지금은 청약할 수 없는 공모주예요</div>
              <button className="btn btn-ghost" onClick={toDetail}>공모주로 돌아가기</button>
            </div>
          ) : admission.error !== null ? (
            <div className="stack">
              <ErrorBox error={admission.error} title="대기열에 들어가지 못했어요" />
              <button className="btn btn-ghost" onClick={admission.join}>다시 시도</button>
            </div>
          ) : admission.phase === 'expired' ? (
            <div className="stack admit-expired">
              <div className="notice warn">
                <strong>청약 가능 시간이 지났어요</strong>
                <div>청약하려면 대기열에 다시 진입해 주세요.{pending ? ' 확인하지 못한 이전 청약은 다시 들어오면 같은 내용으로 확인할 수 있어요' : ''}</div>
              </div>
              <div className="row">
                <button className="btn btn-ghost" onClick={toDetail}>공모주로 돌아가기</button>
                <button className="btn btn-primary" style={{ flex: 1 }} onClick={admission.join}>대기열 다시 진입</button>
              </div>
            </div>
          ) : !admitted ? (
            <div className="stack">
              {/* 대기 창은 진입 응답을 받은 뒤에 열리므로, 첫 진입이 503, 네트워크 오류로 재시도 중이면 여기서 알린다 */}
              {admission.phase === 'entering' && admission.unstable && (
                <div className="notice warn">연결이 원활하지 않아요. 대기열 진입을 다시 시도하고 있어요</div>
              )}
              <Loading lines={5} />
            </div>
          ) : (
            <div className="stack">
              {admission.entry?.admissionExpiresAt && <AdmissionTimer expiresAt={admission.entry.admissionExpiresAt} />}
              <div className="field">
                <label>계좌</label>
                <select className="input" value={accountId} disabled={busy || pending !== null} onChange={(e) => setAccountId(e.target.value)}>
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
                  disabled={busy || pending !== null}
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
              {pending && !busy && (
                <div className="notice gray">이전 청약 결과를 확인하지 못했어요. 같은 계좌, 수량으로 다시 확인할 때까지 입력을 바꿀 수 없어요</div>
              )}
              <button className="btn btn-primary btn-block" disabled={busy || !!qtyError || !accountId} onClick={submit}>
                {busy ? '접수 중…' : pending ? `${pending.qty.toLocaleString()}주 청약 결과 확인하기` : `${qty.toLocaleString()}주 청약하기`}
              </button>
              <div className="hint">신청하면 먼저 "접수"되고, 증거금 인출이 끝나면 "확정"돼요.</div>
            </div>
          )}
        </Card>

        <div className="stack">
          <IpoInfoCard ipo={d} />
          <DesignNote title="몰릴 때는 대기열로 순서대로">
            <ul className="dots">
              <li>청약 화면은 게이트웨이의 공모주별 대기열을 지나야 열려요. 1초마다 서비스가 받을 수 있는 만큼(433명)만 입장시켜요.</li>
              <li>입장 허가를 받으면 허가 유효 시간 안에 청약해야 하고, 게이트웨이가 청약 신청마다 그 공모주의 입장 허가를 확인해요.</li>
              <li>대기열 순번은 청약 화면에 들어가는 순서일 뿐이고 배정 순위와는 관계없어요.</li>
            </ul>
          </DesignNote>
          <DesignNote title="몰려도 밀리지 않는 접수">
            <ul className="dots">
              <li>청약 요청은 DB 처리를 기다리지 않고 Kafka에 저장된 것을 확인(acks=all)한 뒤 바로 202로 답해요.</li>
              <li>증거금 인출과 확정은 Consumer가 뒤에서 처리해요.</li>
              <li>정식 측정에서 오픈 직후 피크 433건/초를 평균 8.0ms, 오류 0으로 받았어요 (정식측정-9).</li>
            </ul>
          </DesignNote>
          <DesignNote title="버튼을 여러 번 눌러도 청약은 1건">
            <ul className="dots">
              <li>이 화면이 만든 요청 ID(requestId)로 청약 ID를 정해서, 같은 요청이 다시 와도 같은 청약으로 처리해요.</li>
              <li>계좌 서비스는 처리 기록(processed_event)으로 같은 이벤트를 한 번만 반영해 증거금이 두 번 인출되지 않아요.</li>
            </ul>
          </DesignNote>
        </div>
      </div>

      {showSheet && (
        <QueueSheet
          ipoName={d.name}
          phase={admission.phase}
          entry={admission.entry}
          firstPosition={admission.firstPosition}
          unstable={admission.unstable}
          onLeave={async () => { await admission.leave(); toDetail(); }}
          onRejoin={admission.join}
          onBack={toDetail}
        />
      )}
    </div>
  );
}
