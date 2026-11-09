import { Link } from 'react-router-dom';
import { useSubscriptions } from '../api/queries';
import { Badge, Card, Empty, ErrorBox, Loading } from '../components/ui';
import { formatDateTime, formatWon } from '../lib/format';
import { subscriptionStatusLabel } from '../lib/status';

export function SubscriptionsPage() {
  const subs = useSubscriptions();
  const items = subs.data?.items ?? [];
  return (
    <div className="stack">
      <div>
        <div className="page-title">내 청약</div>
        <p className="page-sub">처리 중인 청약은 자동으로 새로 고쳐져요</p>
      </div>
      <Card>
        {subs.isLoading ? (
          <Loading lines={5} />
        ) : subs.error ? (
          <ErrorBox error={subs.error} />
        ) : items.length === 0 ? (
          <Empty title="아직 청약 내역이 없어요" action={<Link to="/ipos" className="btn btn-primary btn-sm">공모주 보러 가기</Link>} />
        ) : (
          <div className="list">
            {items.map((s) => {
              const st = subscriptionStatusLabel[s.status];
              return (
                <Link key={s.subscriptionId} to={`/subscriptions/${s.subscriptionId}`} className="list-item link">
                  <div>
                    <div className="item-title">{s.ipoName}</div>
                    <div className="item-meta">
                      {s.requestedQuantity.toLocaleString()}주, 증거금 {formatWon(s.marginAmount)}, 신청 {formatDateTime(s.receivedAt)}
                    </div>
                  </div>
                  <Badge tone={st.tone}>{st.label}</Badge>
                </Link>
              );
            })}
          </div>
        )}
      </Card>
    </div>
  );
}
