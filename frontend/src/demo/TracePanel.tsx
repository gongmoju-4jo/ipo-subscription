import { useState } from 'react';
import { useTrace } from '../api/queries';
import { ApiError } from '../api/client';
import { Badge, Card, NotConnected } from '../components/ui';
import { formatTimeSec, formatWon, shortId } from '../lib/format';
import { ledgerTypeLabel, topicLabel } from '../lib/status';
import { DesignNote } from './DesignNote';

// 청약 1건의 실제 처리 기록 (시연 전용 운영 조회 서버). 펼쳤을 때만 조회한다
export function TracePanel({ subscriptionId, live }: { subscriptionId: string; live: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <Card
      title={
        <span className="row" style={{ gap: 8 }}>
          처리 상세 <span className="pill-ops">시연용 운영 조회</span>
        </span>
      }
      extra={<button className="btn btn-ghost btn-sm" onClick={() => setOpen((v) => !v)}>{open ? '접기' : '처리 상세 보기'}</button>}
    >
      {open ? <TraceBody subscriptionId={subscriptionId} live={live} /> : <div className="hint">Kafka 이벤트, Outbox 발행, 처리 기록(중복 방지), 원장을 실제 DB 기록으로 보여줘요</div>}
    </Card>
  );
}

function TraceBody({ subscriptionId, live }: { subscriptionId: string; live: boolean }) {
  const trace = useTrace(subscriptionId, live);
  if (trace.isLoading) return <div className="hint">불러오는 중…</div>;
  if (trace.error) {
    const e = trace.error;
    const status = e instanceof ApiError ? e.status : 0;
    const why =
      status === 403 ? '본인 청약만 볼 수 있어요'
      : status === 404 ? '청약이 아직 저장되지 않았거나 없어요'
      : status === 401 ? '운영 조회 서버가 토큰을 거부했어요. 서버의 JWT_SECRET이 Gateway와 같은지 확인해 주세요'
      : '운영 조회 서버(frontend/ops-server)가 실행 중이 아니에요. `npm run ops`로 실행하면 보여요';
    return <NotConnected what="처리 기록" why={why} />;
  }
  const t = trace.data!;
  const events = [...t.events].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  return (
    <div className="stack">
      <div className="notice gray" style={{ fontSize: 13 }}>
        <ul className="dots">
          <li>청약 접수는 API가 청약과 증거금 인출 요청(<code>margin.reserve-requested</code>)을 한 트랜잭션에 저장한 뒤 202를 줘서, 정상 접수된 청약은 이 표의 첫 줄이 증거금 인출 요청이에요. 이중청약으로 거절된 청약은 인출 요청을 기록하지 않아 표가 비어 있어요.</li>
          <li>서로 다른 Kafka 토픽 사이에는 순서가 보장되지 않아요. 아래는 각 서비스 Outbox에 기록된 시각 순서예요.</li>
          <li>"소비"는 받는 쪽 처리 기록(processed_event)에 같은 이벤트 ID가 있다는 뜻이에요.</li>
          <li>이 기록 때문에 같은 이벤트가 두 번 와도 한 번만 반영돼요.</li>
        </ul>
      </div>
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>단계</th>
              <th>Kafka 토픽</th>
              <th>보낸 서비스</th>
              <th>메시지 키</th>
              <th>이벤트 ID</th>
              <th>기록</th>
              <th>발행</th>
              <th>소비</th>
            </tr>
          </thead>
          <tbody>
            {events.length === 0 && (
              <tr><td colSpan={8} className="muted">아직 기록된 이벤트가 없어요</td></tr>
            )}
            {events.map((e) => (
              <tr key={e.eventId}>
                <td style={{ fontWeight: 700 }}>{topicLabel[e.topic] ?? e.topic}</td>
                <td><code>{e.topic}</code></td>
                <td>{e.service}</td>
                <td><code>{shortId(e.messageKey)}</code></td>
                <td><code>{shortId(e.eventId)}</code></td>
                <td className="num">{formatTimeSec(e.createdAt)}</td>
                <td>{e.publishedAt ? <Badge tone="success">발행 {formatTimeSec(e.publishedAt)}</Badge> : <Badge tone="warn">미발행</Badge>}</td>
                <td>
                  {e.consumedBy.length === 0 ? (
                    <span className="muted">-</span>
                  ) : (
                    e.consumedBy.map((c) => (
                      <div key={c.consumer} style={{ fontSize: 12 }}>
                        <code>{c.consumer}</code> {formatTimeSec(c.processedAt)}
                      </div>
                    ))
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="grid grid-narrow-left">
        <div>
          <div className="card-title">증거금</div>
          {t.margin ? (
            <dl className="kv kv-left">
              <dt>상태</dt><dd><code>{String(t.margin.status)}</code></dd>
              <dt>금액</dt><dd className="num">{formatWon(Number(t.margin.amount))}</dd>
              <dt>정산 금액</dt><dd className="num">{t.margin.posted_amount == null ? '-' : formatWon(Number(t.margin.posted_amount))}</dd>
            </dl>
          ) : (
            <div className="hint">증거금 행 없음 (인출되지 않음)</div>
          )}
        </div>
        <div>
          <div className="card-title">이 청약의 원장</div>
          {t.ledger.length === 0 ? (
            <div className="hint">원장 기록 없음 (돈이 움직이지 않음)</div>
          ) : (
            <table className="table">
              <thead><tr><th>거래</th><th className="r">금액</th><th className="r">거래 후 예수금</th><th className="r">거래 후 증거금</th></tr></thead>
              <tbody>
                {t.ledger.map((l, i) => (
                  <tr key={i}>
                    <td>{ledgerTypeLabel[l.type] ?? l.type}</td>
                    <td className="r num">{formatWon(l.amount)}</td>
                    <td className="r num">{formatWon(l.cashBalanceAfter)}</td>
                    <td className="r num">{formatWon(l.marginBalanceAfter)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
      <DesignNote title="이벤트가 유실되지 않는 이유 (Transactional Outbox)">
        <ul className="dots">
          <li>상태 변경과 보낼 이벤트를 같은 DB 트랜잭션에 저장(Outbox)해요.</li>
          <li>발행기가 1초마다 Kafka로 보내요.</li>
          <li>Kafka가 잠깐 멈춰도 Outbox에 남아 있다가 복구되면 나가요 (통합 테스트에서 Kafka 정지 뒤 0.1초 만에 확정 확인).</li>
        </ul>
      </DesignNote>
      <div className="hint">조회 시각 {formatTimeSec(t.checkedAt)}{live ? ', 처리 중이라 2초마다 새로 고침' : ''}</div>
    </div>
  );
}
