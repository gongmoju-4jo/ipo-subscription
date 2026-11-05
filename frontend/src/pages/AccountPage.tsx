import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAccounts, useLedger, keys } from '../api/queries';
import { accountApi } from '../api/endpoints';
import { ApiError, newRequestId } from '../api/client';
import { Card, Empty, ErrorBox, Loading, Money, RecentExchanges } from '../components/ui';
import { formatDateTime, formatWon } from '../lib/format';
import { ledgerTypeLabel } from '../lib/status';

const QUICK = [100_000, 500_000, 1_000_000];

export function AccountPage() {
  const accounts = useAccounts();
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState('');
  const [amount, setAmount] = useState(1_000_000);
  const [depositRequestId, setDepositRequestId] = useState(newRequestId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const items = accounts.data?.items ?? [];
  useEffect(() => {
    if (!selected && items[0]) setSelected(items[0].accountId);
  }, [items, selected]);
  const account = items.find((a) => a.accountId === selected);
  const ledger = useLedger(account?.accountId);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: keys.accounts });
    if (account) queryClient.invalidateQueries({ queryKey: keys.ledger(account.accountId) });
  };

  async function open() {
    setBusy(true);
    setError(null);
    try {
      const a = await accountApi.open();
      setSelected(a.accountId);
      refresh();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  async function deposit() {
    if (!account) return;
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      const r = await accountApi.deposit(account.accountId, amount, depositRequestId);
      setDone(`${formatWon(r.amount)} 입금했어요. 예수금 ${formatWon(r.cashBalance)}`);
      setDepositRequestId(newRequestId());
      refresh();
    } catch (e) {
      setError(e);
      if (e instanceof ApiError && e.status < 500) setDepositRequestId(newRequestId());
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <div className="row between wrap">
        <div>
          <div className="page-title">계좌</div>
          <p className="page-sub">가상 계좌예요. 실제 돈은 움직이지 않아요</p>
        </div>
        <button className="btn btn-ghost" onClick={open} disabled={busy}>+ 계좌 만들기</button>
      </div>

      {accounts.isLoading ? (
        <Card><Loading /></Card>
      ) : accounts.error ? (
        <ErrorBox error={accounts.error} />
      ) : items.length === 0 ? (
        <Card><Empty title="계좌가 없어요" desc="계좌를 만들면 가상 예수금을 넣고 청약할 수 있어요" action={<button className="btn btn-primary" onClick={open} disabled={busy}>계좌 만들기</button>} /></Card>
      ) : (
        <>
          {items.length > 1 && (
            <div className="tabs wrap">
              {items.map((a) => (
                <button key={a.accountId} className={a.accountId === selected ? 'on' : ''} onClick={() => setSelected(a.accountId)}>{a.accountNumber}</button>
              ))}
            </div>
          )}
          {account && (
            <section className="card hero">
              <div className="label">계좌번호 {account.accountNumber}</div>
              <div className="grid grid-3" style={{ marginTop: 10 }}>
                <div><div className="label">총 금액</div><Money value={account.cashBalance + account.marginBalance} size="big" /></div>
                <div><div className="label">예수금</div><Money value={account.cashBalance} /></div>
                <div><div className="label">청약증거금</div><Money value={account.marginBalance} /></div>
              </div>
            </section>
          )}
          <div className="grid grid-2">
            <Card title="가상 입금">
              <div className="stack">
                <div className="row wrap">
                  {QUICK.map((q) => (
                    <button key={q} className={`btn btn-sm ${amount === q ? 'btn-dark' : 'btn-ghost'}`} onClick={() => setAmount(q)}>{formatWon(q)}</button>
                  ))}
                </div>
                <input className="input num" type="number" min={1} value={amount || ''} onChange={(e) => setAmount(Math.floor(Number(e.target.value)))} />
                {done && <div className="notice success">{done}</div>}
                {error !== null && <ErrorBox error={error} title="처리하지 못했어요" />}
                <button className="btn btn-primary btn-block" onClick={deposit} disabled={busy || amount <= 0}>{busy ? '처리 중…' : `${formatWon(amount)} 입금하기`}</button>
                <div className="hint">같은 입금을 두 번 눌러도 요청 ID가 같아서 한 번만 반영돼요</div>
              </div>
            </Card>
            <Card title="거래 내역">
              {ledger.isLoading ? (
                <Loading />
              ) : ledger.error ? (
                <ErrorBox error={ledger.error} title="거래 내역을 불러오지 못했어요" />
              ) : (ledger.data?.items ?? []).length === 0 ? (
                <Empty title="거래 내역이 없어요" />
              ) : (
                <div className="table-wrap">
                  <table className="table">
                    <thead><tr><th>시각</th><th>거래</th><th className="r">금액</th><th className="r">거래 후 예수금</th><th className="r">거래 후 증거금</th></tr></thead>
                    <tbody>
                      {ledger.data!.items.slice(0, 15).map((l, i) => (
                        <tr key={i}>
                          <td>{formatDateTime(l.createdAt)}</td>
                          <td>{ledgerTypeLabel[l.type] ?? l.type}</td>
                          <td className="r num">{formatWon(l.amount)}</td>
                          <td className="r num">{formatWon(l.cashBalanceAfter)}</td>
                          <td className="r num">{formatWon(l.marginBalanceAfter)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>
          </div>
        </>
      )}
      <RecentExchanges filter={(r) => r.path.startsWith('/api/v1/accounts')} />
    </div>
  );
}
