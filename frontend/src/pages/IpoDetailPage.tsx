import { Link, useParams } from 'react-router-dom';
import { useAccounts, useIpo } from '../api/queries';
import { Badge, Card, ErrorBox, Loading } from '../components/ui';
import { formatDateTime, formatWon } from '../lib/format';
import { ipoStatusLabel } from '../lib/status';

// 공모주 상세. 정보는 누구나 보고, [청약 화면으로]를 누르면 청약 화면(대기열 → 입장 허가 → 입력)으로 간다
export function IpoDetailPage() {
  const { ipoId = '' } = useParams();
  const ipo = useIpo(ipoId);
  const accounts = useAccounts();

  if (ipo.isLoading) return <Card><Loading lines={6} /></Card>;
  if (ipo.error || !ipo.data) return <ErrorBox error={ipo.error} />;
  const d = ipo.data;
  const st = ipoStatusLabel(d.status, d.subscriptionStartAt, d.subscriptionEndAt);
  const canSubscribe = st.label === '청약 중';
  const noAccount = accounts.data !== undefined && accounts.data.items.length === 0;

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

      <div className="grid grid-wide-left">
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

        <Card title="청약">
          {!canSubscribe ? (
            <div className="notice gray">지금은 청약할 수 없어요 ({st.label})</div>
          ) : noAccount ? (
            <div className="notice info">
              청약하려면 계좌가 필요해요. <Link to="/account" style={{ fontWeight: 700 }}>계좌 만들기 →</Link>
            </div>
          ) : (
            <div className="stack">
              <div className="sub" style={{ fontSize: 14, lineHeight: 1.6 }}>
                마감 {formatDateTime(d.subscriptionEndAt)}
              </div>
              <Link to={`/ipos/${ipoId}/subscribe`} className="btn btn-primary btn-block btn-go">
                청약 화면으로
                <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden><path d="M5 12h14M13 6l6 6-6 6" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" fill="none" /></svg>
              </Link>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
