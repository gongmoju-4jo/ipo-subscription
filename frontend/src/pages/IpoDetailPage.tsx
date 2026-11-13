import { Link, useParams } from 'react-router-dom';
import { useAccounts, useIpo } from '../api/queries';
import { Badge, Card, ErrorBox, Loading } from '../components/ui';
import { IpoInfoCard } from '../components/IpoInfoCard';
import { formatDateTime, formatWon } from '../lib/format';
import { ipoStatusLabel } from '../lib/status';
import { LotteryReplayCard } from '../admin/LotteryReplayCard';

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
        <IpoInfoCard ipo={d} />

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

      {/* 공모주가 바뀌면 카드를 새로 생성해 이전 공모주의 재현 결과, 진행 중 요청을 초기화한다 */}
      <LotteryReplayCard key={ipoId} ipoId={ipoId} status={d.status} />
    </div>
  );
}
