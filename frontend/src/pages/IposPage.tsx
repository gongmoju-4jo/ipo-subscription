import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useIpos } from '../api/queries';
import { Badge, Card, Empty, ErrorBox, Loading } from '../components/ui';
import { formatDateTime, formatWon } from '../lib/format';
import { ipoStatusLabel } from '../lib/status';

type Filter = 'open' | 'all';

export function IposPage() {
  const ipos = useIpos();
  const [filter, setFilter] = useState<Filter>('open');
  const all = ipos.data?.items ?? [];
  const shown = filter === 'all' ? all : all.filter((i) => ipoStatusLabel(i.status, i.subscriptionStartAt, i.subscriptionEndAt).label === '청약 중');

  return (
    <div className="stack">
      <div>
        <div className="page-title">공모주</div>
        <p className="page-sub">증거금률 100%라 청약 금액만큼 예수금이 필요해요</p>
      </div>
      <div className="tabs">
        <button className={filter === 'open' ? 'on' : ''} onClick={() => setFilter('open')}>청약 중</button>
        <button className={filter === 'all' ? 'on' : ''} onClick={() => setFilter('all')}>전체</button>
      </div>
      <Card>
        {ipos.isLoading ? (
          <Loading lines={5} />
        ) : ipos.error ? (
          <ErrorBox error={ipos.error} />
        ) : shown.length === 0 ? (
          <Empty title={filter === 'open' ? '지금 청약 중인 공모주가 없어요' : '등록된 공모주가 없어요'} />
        ) : (
          <div className="list">
            {shown.map((i) => {
              const st = ipoStatusLabel(i.status, i.subscriptionStartAt, i.subscriptionEndAt);
              return (
                <Link key={i.ipoId} to={`/ipos/${i.ipoId}`} className="list-item link">
                  <div>
                    <div className="row" style={{ gap: 8 }}>
                      <span className="item-title">{i.name}</span>
                      <Badge tone={st.tone}>{st.label}</Badge>
                    </div>
                    <div className="item-meta">
                      {formatDateTime(i.subscriptionStartAt)} ~ {formatDateTime(i.subscriptionEndAt)}, 일반청약 {i.retailQuantity.toLocaleString()}주
                    </div>
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <div className="num" style={{ fontWeight: 800, fontSize: 17 }}>{formatWon(i.offerPrice)}</div>
                    <div className="item-meta">공모가</div>
                  </div>
                </Link>
              );
            })}
          </div>
        )}
      </Card>
    </div>
  );
}
