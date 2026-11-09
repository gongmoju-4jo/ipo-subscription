import { Link } from 'react-router-dom';
import { useAccounts, useIpos, useSubscriptions } from '../api/queries';
import { Badge, Card, Empty, ErrorBox, Loading, Money } from '../components/ui';
import { formatDateTime, formatWon } from '../lib/format';
import { inProgressStatuses, ipoStatusLabel, subscriptionStatusLabel } from '../lib/status';
import { useAuth } from '../auth/AuthContext';

export function DashboardPage() {
  const { session } = useAuth();
  const accounts = useAccounts();
  const subs = useSubscriptions();
  const ipos = useIpos();

  const items = accounts.data?.items ?? [];
  const cash = items.reduce((a, b) => a + b.cashBalance, 0);
  const margin = items.reduce((a, b) => a + b.marginBalance, 0);
  const active = (subs.data?.items ?? []).filter((s) => s.status === 'CONFIRMED' || inProgressStatuses.includes(s.status));
  const open = (ipos.data?.items ?? []).filter((i) => ipoStatusLabel(i.status, i.subscriptionStartAt, i.subscriptionEndAt).label === '청약 중');

  return (
    <div className="stack">
      <div>
        <div className="page-title">{session?.loginId}님, 안녕하세요</div>
        <p className="page-sub">계좌와 진행 중인 청약을 한눈에 확인하세요</p>
      </div>

      <section className="card hero">
        {accounts.isLoading ? (
          <Loading lines={2} />
        ) : accounts.error ? (
          <ErrorBox error={accounts.error} />
        ) : items.length === 0 ? (
          <div className="row between wrap">
            <div>
              <div className="mid">아직 계좌가 없어요</div>
              <div className="muted" style={{ marginTop: 4 }}>계좌를 만들고 예수금을 넣으면 청약할 수 있어요</div>
            </div>
            <Link to="/account" className="btn btn-primary">계좌 만들기</Link>
          </div>
        ) : (
          <div className="grid grid-3">
            <div>
              <div className="label">총 자산 (계좌 {items.length}개)</div>
              <Money value={cash + margin} size="big" />
            </div>
            <div>
              <div className="label">예수금 (청약에 쓸 수 있는 돈)</div>
              <Money value={cash} size="mid" />
            </div>
            <div>
              <div className="label">청약증거금 (청약에 인출된 돈)</div>
              <Money value={margin} size="mid" />
            </div>
          </div>
        )}
      </section>

      <div className="grid grid-2">
        <Card title="진행 중인 청약" extra={<Link to="/subscriptions" className="muted" style={{ fontSize: 14 }}>전체 보기</Link>}>
          {subs.isLoading ? (
            <Loading />
          ) : subs.error ? (
            <ErrorBox error={subs.error} />
          ) : active.length === 0 ? (
            <Empty title="진행 중인 청약이 없어요" desc="청약 중인 공모주를 확인해 보세요" />
          ) : (
            <div className="list">
              {active.slice(0, 5).map((s) => {
                const st = subscriptionStatusLabel[s.status];
                return (
                  <Link key={s.subscriptionId} to={`/subscriptions/${s.subscriptionId}`} className="list-item link">
                    <div>
                      <div className="item-title">{s.ipoName}</div>
                      <div className="item-meta">{s.requestedQuantity.toLocaleString()}주, 증거금 {formatWon(s.marginAmount)}</div>
                    </div>
                    <Badge tone={st.tone}>{st.label}</Badge>
                  </Link>
                );
              })}
            </div>
          )}
        </Card>

        <Card title="지금 청약할 수 있는 공모주" extra={<Link to="/ipos" className="muted" style={{ fontSize: 14 }}>전체 보기</Link>}>
          {ipos.isLoading ? (
            <Loading />
          ) : ipos.error ? (
            <ErrorBox error={ipos.error} />
          ) : open.length === 0 ? (
            <Empty title="청약 중인 공모주가 없어요" />
          ) : (
            <div className="list">
              {open.slice(0, 5).map((i) => (
                <Link key={i.ipoId} to={`/ipos/${i.ipoId}`} className="list-item link">
                  <div>
                    <div className="item-title">{i.name}</div>
                    <div className="item-meta">마감 {formatDateTime(i.subscriptionEndAt)}</div>
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <div className="num" style={{ fontWeight: 700 }}>{formatWon(i.offerPrice)}</div>
                    <div className="item-meta">공모가</div>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
