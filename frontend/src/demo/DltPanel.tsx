import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { opsApi } from '../api/ops';
import { useOpsSummary } from '../api/queries';
import { Badge, Card, Loading, NotConnected } from '../components/ui';
import { formatTimeSec } from '../lib/format';
import { topicLabel } from '../lib/status';
import { DesignNote } from './DesignNote';

// DLT 토픽별 건수와 메시지 내용 (실패 원인 헤더 포함). 시연용 운영 조회
export function DltPanel() {
  const ops = useOpsSummary();
  const [topic, setTopic] = useState<string | null>(null);
  const withMessages = ops.data?.dlt?.filter((d) => d.messages > 0) ?? [];

  return (
    <Card title={<>DLT <small>재시도를 다 실패해 따로 모아 둔 메시지</small> <span className="pill-ops">시연용 운영 조회</span></>}>
      {ops.isLoading ? (
        <Loading />
      ) : ops.error ? (
        <NotConnected what="DLT" why="운영 조회 서버가 실행 중이 아니에요 (`npm run ops`)" />
      ) : ops.data!.dlt === null ? (
        <NotConnected what="DLT" why={ops.data!.dltError ?? 'Kafka에 연결할 수 없어요'} />
      ) : (
        <div className="stack">
          <div className="dlt-grid">
            {ops.data!.dlt.map((d) => (
              <button
                key={d.topic}
                className={`dlt-tile ${d.messages > 0 ? 'has' : ''} ${topic === d.topic ? 'on' : ''}`}
                onClick={() => setTopic(d.messages > 0 ? (topic === d.topic ? null : d.topic) : null)}
                disabled={d.messages === 0}
              >
                <span className="dlt-name">{topicLabel[d.topic.replace(/\.DLT$/, '')] ?? d.topic}</span>
                <code>{d.topic}</code>
                <span className="dlt-count">{d.messages}</span>
              </button>
            ))}
          </div>
          {withMessages.length > 0 && !topic && <div className="hint">빨간 칸을 누르면 메시지 내용과 실패 원인을 볼 수 있어요</div>}
          {topic && <DltMessages topic={topic} />}
          <DesignNote title="실패한 메시지를 잃지 않는 방법">
            Consumer가 처리하다 실패하면 1초, 2초, 4초 간격으로 다시 시도하고, 그래도 실패하면 원본 그대로 <code>{'{토픽}.DLT'}</code>로 옮겨 다음 메시지가 막히지 않게 해요.
            원인을 고친 뒤 DLT 메시지를 원래 토픽으로 다시 보내면, 처리 기록(processed_event) 덕분에 한 번만 반영돼요. 잔액 부족 같은 업무 거절은 DLT로 보내지 않고 거절 이벤트로 응답해요.
          </DesignNote>
        </div>
      )}
    </Card>
  );
}

function DltMessages({ topic }: { topic: string }) {
  const q = useQuery({ queryKey: ['ops', 'dlt', topic], queryFn: () => opsApi.dlt(topic), retry: false });
  if (q.isLoading) return <Loading lines={3} />;
  if (q.error) return <NotConnected what="DLT 메시지" why="Kafka에서 읽지 못했어요" />;
  const data = q.data!;
  return (
    <div className="stack" style={{ gap: 10 }}>
      <div className="hint">{data.topic} 전체 {data.total}건 중 최근 {data.messages.length}건</div>
      {data.messages.map((m) => (
        <details key={`${m.partition}-${m.offset}`} className="dev">
          <summary>
            <span className="row wrap" style={{ display: 'inline-flex', gap: 8 }}>
              <Badge tone="danger">실패</Badge>
              <span>{formatTimeSec(m.timestamp)}</span>
              <span className="muted">파티션 {m.partition}, 오프셋 {m.offset}</span>
              {m.exceptionMessage && <span className="muted" style={{ maxWidth: 520, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.exceptionMessage}</span>}
            </span>
          </summary>
          <dl className="kv" style={{ marginTop: 10 }}>
            <dt>원래 토픽</dt><dd><code>{m.originalTopic ?? '-'}</code></dd>
            <dt>원래 오프셋</dt><dd>{m.originalOffset ?? '-'}</dd>
            <dt>메시지 키</dt><dd><code>{m.key ?? '-'}</code></dd>
            <dt>예외</dt><dd style={{ wordBreak: 'break-all' }}><code>{m.exceptionClass ?? '-'}</code></dd>
            <dt>원인</dt><dd style={{ wordBreak: 'break-all' }}><code>{m.causeClass ?? '-'}</code></dd>
          </dl>
          {m.exceptionMessage && <div className="notice danger" style={{ marginTop: 10, fontSize: 13, wordBreak: 'break-all' }}>{m.exceptionMessage}</div>}
          <pre className="code">{pretty(m.value)}</pre>
        </details>
      ))}
    </div>
  );
}

function pretty(v: string | null) {
  if (!v) return '(빈 메시지)';
  try {
    return JSON.stringify(JSON.parse(v), null, 2);
  } catch {
    return v;
  }
}
