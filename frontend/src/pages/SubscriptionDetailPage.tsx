import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useAllocation, useSubscription } from '../api/queries';
import { ApiError } from '../api/client';
import type { SubscriptionResponse } from '../api/types';
import { Badge, Card, ErrorBox, Loading, Money } from '../components/ui';
import { TracePanel } from '../demo/TracePanel';
import { DesignNote } from '../demo/DesignNote';
import { DEMO } from '../demo/flag';
import { formatDateTime, formatWon } from '../lib/format';
import { cancelReasonLabel, inProgressStatuses, rejectReasonLabel, subscriptionStatusLabel } from '../lib/status';

type StepState = 'done' | 'now' | 'fail' | 'todo';
interface Step {
  title: string;
  desc: string;
  state: StepState;
}

// 청약 응답만으로 사용자에게 보여줄 진행 단계를 만든다 (판단은 백엔드 상태값 그대로)
function buildSteps(s: SubscriptionResponse): Step[] {
  const received: Step = { title: '청약 접수', desc: formatDateTime(s.receivedAt), state: 'done' };
  switch (s.status) {
    case 'PENDING':
      return [received, { title: '증거금 인출', desc: '예수금에서 청약증거금으로 옮기는 중', state: 'now' }, { title: '청약 확정', desc: '', state: 'todo' }, { title: '배정, 환불', desc: '마감 후', state: 'todo' }];
    case 'REJECTED':
      return [received, { title: '청약 거절', desc: s.rejectReason ? rejectReasonLabel[s.rejectReason] : '거절됐어요', state: 'fail' }];
    case 'COMPENSATING':
      return [received, { title: '증거금 인출', desc: '인출됨', state: 'done' }, { title: '증거금 반환', desc: s.cancelReason ? cancelReasonLabel[s.cancelReason] : '반환 중', state: 'now' }];
    case 'CANCEL_REQUESTED':
      return [received, { title: '증거금 인출', desc: '인출됨', state: 'done' }, { title: '취소, 증거금 반환', desc: '처리 중', state: 'now' }];
    case 'CANCELLED':
      return [received, { title: '증거금 인출', desc: '인출됨', state: 'done' }, { title: '증거금 반환 완료', desc: s.cancelReason ? cancelReasonLabel[s.cancelReason] : '반환 완료', state: 'fail' }];
    case 'CONFIRMED':
      return [received, { title: '증거금 인출, 청약 확정', desc: formatDateTime(s.confirmedAt), state: 'done' }, { title: '배정', desc: '청약 마감 후 진행돼요', state: 'todo' }, { title: '정산, 환불', desc: '', state: 'todo' }];
    case 'ALLOCATED':
      return [received, { title: '증거금 인출, 청약 확정', desc: formatDateTime(s.confirmedAt), state: 'done' }, { title: '배정 완료', desc: `${(s.allocatedQuantity ?? 0).toLocaleString()}주 배정`, state: 'done' }, { title: '정산, 환불', desc: '처리 중', state: 'now' }];
    case 'REFUNDED':
      return [received, { title: '증거금 인출, 청약 확정', desc: formatDateTime(s.confirmedAt), state: 'done' }, { title: '배정 완료', desc: `${(s.allocatedQuantity ?? 0).toLocaleString()}주 배정`, state: 'done' }, { title: '환불 완료', desc: `${formatWon(s.refundAmount)} 돌려받음`, state: 'done' }];
  }
}

export function SubscriptionDetailPage() {
  const { subscriptionId = '' } = useParams();
  const [params] = useSearchParams();
  const sub = useSubscription(subscriptionId);
  const s = sub.data;
  const allocated = s?.status === 'ALLOCATED' || s?.status === 'REFUNDED';
  const allocation = useAllocation(subscriptionId, !!allocated);

  if (sub.isLoading || (sub.error instanceof ApiError && sub.error.status === 404 && sub.isFetching)) {
    return (
      <Card>
        <div className="row" style={{ marginBottom: 14 }}>
          <Badge tone="progress">청약 접수 중</Badge>
          <span className="sub">청약을 받았어요. 처리 상태를 불러오는 중이에요</span>
        </div>
        <Loading lines={4} />
      </Card>
    );
  }
  if (sub.error || !s) return <ErrorBox error={sub.error} />;

  const st = subscriptionStatusLabel[s.status];
  const steps = buildSteps(s);
  const live = inProgressStatuses.includes(s.status);

  return (
    <div className="stack">
      <Link to="/subscriptions" className="muted" style={{ fontSize: 14 }}>← 내 청약</Link>
      {params.get('from') === 'new' && s.status === 'PENDING' && (
        <div className="notice info">청약이 접수됐어요. 증거금 인출이 끝나면 자동으로 "청약 확정"으로 바뀌어요.</div>
      )}
      <div className="row between wrap">
        <div>
          <div className="row" style={{ gap: 10 }}>
            <div className="page-title" style={{ margin: 0 }}>{s.ipoName}</div>
            <Badge tone={st.tone}>{st.label}</Badge>
          </div>
          <p className="page-sub" style={{ marginTop: 6 }}>{st.hint}</p>
        </div>
      </div>

      <div className="grid grid-2">
        <Card title="진행 상태" extra={null}>
          <div className="steps">
            {steps.map((step, i) => (
              <div key={i} className={`step ${step.state}`}>
                <div className="step-icon">{step.state === 'done' ? '✓' : step.state === 'fail' ? '!' : i + 1}</div>
                <div>
                  <div className="step-title">{step.title}</div>
                  {step.desc && <div className="step-desc">{step.desc}</div>}
                </div>
              </div>
            ))}
          </div>
          <div style={{ marginTop: 16 }}>
            <DesignNote title="중간에 실패해도 돈이 묶이지 않는 이유">
              청약(청약 서비스)과 증거금(계좌 서비스)은 DB가 달라 한 트랜잭션으로 묶을 수 없어요. 청약 서비스가 단계를 지휘하는 SAGA로 처리하고,
              증거금을 인출한 뒤 확정에 실패하거나 계좌 응답이 늦으면 반환 요청(보상)을 보내 증거금 전액을 돌려줘요. 그래서 상태는 접수 중 → 확정 또는 반환 중 → 취소로만 움직여요.
            </DesignNote>
          </div>
        </Card>

        <Card title="청약 내용">
          <dl className="kv">
            <dt>청약 수량</dt>
            <dd className="num">{s.requestedQuantity.toLocaleString()}주</dd>
            <dt>청약증거금</dt>
            <dd className="num">{formatWon(s.marginAmount)}</dd>
            <dt>신청 시각</dt>
            <dd>{formatDateTime(s.receivedAt)}</dd>
            <dt>확정 시각</dt>
            <dd>{formatDateTime(s.confirmedAt)}</dd>
          </dl>
          {s.status === 'REJECTED' && s.rejectReason && <div className="notice danger" style={{ marginTop: 14 }}>{rejectReasonLabel[s.rejectReason]}. 예수금은 그대로예요.</div>}
          {(s.status === 'CANCELLED' || s.status === 'COMPENSATING') && s.cancelReason && (
            <div className="notice warn" style={{ marginTop: 14 }}>{cancelReasonLabel[s.cancelReason]}. 인출된 증거금은 전액 예수금으로 돌려드려요.</div>
          )}
          {s.status === 'CONFIRMED' && (
            <div style={{ marginTop: 16 }}>
              <button className="btn btn-ghost btn-block" disabled title="백엔드에 청약 취소 API가 아직 없어요">
                청약 취소 (준비 중)
              </button>
              <div className="hint" style={{ marginTop: 6 }}>청약 취소 기능은 아직 제공되지 않아요</div>
            </div>
          )}
        </Card>
      </div>

      {allocated && (
        <Card title="배정과 환불">
          <div className="grid grid-4">
            <div>
              <div className="label">배정 수량</div>
              <div className="mid num">{(s.allocatedQuantity ?? 0).toLocaleString()}주</div>
              {allocation.data && (
                <div className="hint">균등 {allocation.data.equalAllocatedQuantity.toLocaleString()}주 + 비례 {allocation.data.proportionalAllocatedQuantity.toLocaleString()}주</div>
              )}
            </div>
            <div>
              <div className="label">배정 대금 (정산)</div>
              <Money value={s.allocatedAmount} />
            </div>
            <div>
              <div className="label">돌려받은 금액</div>
              <Money value={s.refundAmount} />
            </div>
            <div>
              <div className="label">청약증거금</div>
              <Money value={s.marginAmount} />
            </div>
          </div>
          {s.allocatedQuantity === 0 && <div className="notice info" style={{ marginTop: 14 }}>이번에는 배정받지 못해 증거금 전액을 돌려드렸어요.</div>}
          {allocation.error && !(allocation.error instanceof ApiError && allocation.error.status === 404) && <ErrorBox error={allocation.error} title="배정 상세를 불러오지 못했어요" />}
        </Card>
      )}

      {DEMO && <TracePanel subscriptionId={subscriptionId} live={live} />}
    </div>
  );
}
