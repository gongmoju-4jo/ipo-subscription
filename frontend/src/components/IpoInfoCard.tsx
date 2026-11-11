import type { IpoResponse } from '../api/types';
import { formatDateTime } from '../lib/format';
import { Card } from './ui';

// 공모주 청약 정보 (공모주 상세, 청약 화면이 같이 쓴다)
export function IpoInfoCard({ ipo }: { ipo: IpoResponse }) {
  return (
    <Card title="청약 정보">
      <dl className="kv">
        <dt>청약 기간</dt>
        <dd>{formatDateTime(ipo.subscriptionStartAt)} ~ {formatDateTime(ipo.subscriptionEndAt)}</dd>
        <dt>환불일</dt>
        <dd>{ipo.refundDate}</dd>
        <dt>일반청약 물량</dt>
        <dd className="num">{ipo.retailQuantity.toLocaleString()}주</dd>
        <dt>균등배정 물량</dt>
        <dd className="num">{ipo.equalQuantity.toLocaleString()}주</dd>
        <dt>청약 수량</dt>
        <dd className="num">{ipo.minQuantity.toLocaleString()}주 ~ {ipo.maxQuantity.toLocaleString()}주</dd>
        <dt>증거금률</dt>
        <dd>{Math.round(Number(ipo.marginRate) * 100)}%</dd>
      </dl>
      <div className="notice gray" style={{ marginTop: 16 }}>
        마감 후 균등배정(최소 수량 이상 청약자에게 같은 몫)과 비례배정(청약 수량 비례)을 하고, 배정받지 못한 증거금은 예수금으로 돌려드려요.
      </div>
    </Card>
  );
}
