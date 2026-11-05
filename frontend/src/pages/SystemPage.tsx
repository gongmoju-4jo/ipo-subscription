import { useQuery } from '@tanstack/react-query';
import { promQuery, type PromSample } from '../api/ops';
import { useOpsSummary } from '../api/queries';
import { Badge, Card, ErrorBox, Loading, NotConnected } from '../components/ui';
import { formatNumber, formatTimeSec } from '../lib/format';
import { DEMO } from '../demo/flag';
import { DltPanel } from '../demo/DltPanel';
import { DesignNote } from '../demo/DesignNote';

// 시스템 현황: Prometheus 실제 지표 + 시연용 운영 조회(Outbox, DLT, 정합성)
// 쿼리는 팀 Grafana 측정 대시보드(docker/grafana/dashboards/measurement.json)와 같은 식을 쓴다

const SERVICES = ['api-gateway', 'account-service', 'subscription-service', 'allocation-service', 'eureka-server'];
const Q = {
  up: 'up',
  rps: 'sum by (job) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[1m]))',
  p95: 'histogram_quantile(0.95, sum by (le, job) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[1m])))',
  err5xx: 'sum by (job) (rate(http_server_requests_seconds_count{status=~"5..",uri!~"/actuator.*"}[1m]))',
  lag: 'sum by (client_group) (label_replace(kafka_consumer_fetch_manager_records_lag, "client_group", "$1", "client_id", "consumer-(.+)-[0-9]+"))',
  pending: 'sum by (job) (hikaricp_connections_pending)',
};

function usePromAll() {
  return useQuery({
    queryKey: ['prom', 'system'],
    queryFn: async () => {
      const entries = await Promise.all(Object.entries(Q).map(async ([k, q]) => [k, await promQuery(q)] as const));
      return Object.fromEntries(entries) as Record<keyof typeof Q, PromSample[]>;
    },
    refetchInterval: 5000,
    retry: false,
  });
}

const byJob = (rows: PromSample[] | undefined, job: string) => rows?.find((r) => r.labels.job === job)?.value;

export function SystemPage() {
  const prom = usePromAll();
  const ops = useOpsSummary();

  return (
    <div className="stack">
      <div>
        <div className="page-title">시스템 현황</div>
        <p className="page-sub">운영, 발표용 화면이에요. 값은 Prometheus와 DB에서 실시간으로 읽고, 연결되지 않은 값은 "확인 불가"로 표시해요 (5초마다 새로 고침)</p>
      </div>

      <Card title={<>서비스 상태 <small>Prometheus</small></>}>
        {prom.isLoading ? (
          <Loading />
        ) : prom.error ? (
          <NotConnected what="Prometheus 지표" why="Prometheus(기본 http://localhost:9090)에 연결할 수 없어요. docker-compose로 prometheus가 떠 있는지 확인해 주세요" />
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr><th>서비스</th><th>상태</th><th className="r">요청/초 (1분)</th><th className="r">p95 응답</th><th className="r">5xx/초</th><th className="r">DB 커넥션 대기</th></tr>
              </thead>
              <tbody>
                {SERVICES.map((job) => {
                  const up = byJob(prom.data!.up, job);
                  const p95 = byJob(prom.data!.p95, job);
                  return (
                    <tr key={job}>
                      <td style={{ fontWeight: 700 }}>{job}</td>
                      <td>{up === undefined ? <Badge tone="muted">수집 대상 없음</Badge> : up >= 1 ? <Badge tone="success">정상</Badge> : <Badge tone="danger">응답 없음</Badge>}</td>
                      <td className="r num">{formatNumber(byJob(prom.data!.rps, job), 1)}</td>
                      <td className="r num">{p95 === undefined || Number.isNaN(p95) ? '-' : `${formatNumber(p95 * 1000, 1)}ms`}</td>
                      <td className="r num">{formatNumber(byJob(prom.data!.err5xx, job) ?? 0, 2)}</td>
                      <td className="r num">{byJob(prom.data!.pending, job) === undefined ? '-' : formatNumber(byJob(prom.data!.pending, job))}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <div className="grid grid-2">
        <Card title={<>Kafka Consumer 랙 <small>아직 처리 안 한 메시지 수</small></>}>
          {prom.isLoading ? (
            <Loading />
          ) : prom.error ? (
            <NotConnected what="Consumer 랙" why="Prometheus 미연결" />
          ) : prom.data!.lag.length === 0 ? (
            <div className="hint">아직 보고된 Consumer가 없어요</div>
          ) : (
            <table className="table">
              <thead><tr><th>Consumer 그룹</th><th className="r">랙</th></tr></thead>
              <tbody>
                {[...prom.data!.lag].sort((a, b) => (a.labels.client_group ?? '').localeCompare(b.labels.client_group ?? '')).map((r) => (
                  <tr key={r.labels.client_group}>
                    <td><code>{r.labels.client_group}</code></td>
                    <td className="r num">{r.value === 0 ? <Badge tone="success">0</Badge> : <Badge tone="warn">{formatNumber(r.value)}</Badge>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        {DEMO && (
          <Card title={<>Outbox 미발행 <small>DB에 저장됐지만 아직 Kafka로 안 나간 이벤트</small> <span className="pill-ops">시연용 운영 조회</span></>}>
            {ops.isLoading ? (
              <Loading />
            ) : ops.error ? (
              <NotConnected what="Outbox" why="운영 조회 서버가 실행 중이 아니에요 (`npm run ops`)" />
            ) : (
              <div className="stack">
                <table className="table">
                  <thead><tr><th>서비스</th><th className="r">미발행</th></tr></thead>
                  <tbody>
                    {ops.data!.outboxUnpublished.map((o) => (
                      <tr key={o.service}><td>{o.service}</td><td className="r num">{o.count === 0 ? <Badge tone="success">0</Badge> : <Badge tone="warn">{o.count}</Badge>}</td></tr>
                    ))}
                  </tbody>
                </table>
                <div className="hint">조회 시각 {formatTimeSec(ops.data!.checkedAt)}</div>
              </div>
            )}
          </Card>
        )}
      </div>

      {DEMO && <DltPanel />}

      {DEMO && (
      <Card title={<>금액 정합성 검사 <span className="pill-ops">시연용 운영 조회</span></>}>
        {ops.isLoading ? (
          <Loading />
        ) : ops.error ? (
          <NotConnected what="정합성 검사" why="운영 조회 서버가 실행 중이 아니에요. 검사를 실행하지 않았으므로 통과로 표시하지 않아요" />
        ) : (
          <div className="list">
            {ops.data!.checks.map((c) => (
              <div key={c.id} className="list-item">
                <div>
                  <div className="item-title" style={{ fontSize: 15 }}>{c.name}</div>
                  <div className="item-meta">{c.description}</div>
                </div>
                {c.violations === null ? (
                  <Badge tone="muted">확인 불가</Badge>
                ) : c.violations === 0 ? (
                  <Badge tone="success">위반 0</Badge>
                ) : (
                  <Badge tone="danger">위반 {c.violations}</Badge>
                )}
              </div>
            ))}
          </div>
        )}
        {ops.data?.checks.some((c) => c.error) && <ErrorBox error={new Error(ops.data.checks.find((c) => c.error)!.error!)} title="일부 검사를 실행하지 못했어요" />}
        <div style={{ marginTop: 12 }}>
          <DesignNote title="금액이 맞는지 어떻게 증명하나">
            loadtest/sql/verify.sql의 검사식을 지금 DB에 그대로 실행해요. 위반이 0이면 모든 계좌에서 잔고, 증거금, 원장, 정산 금액이 서로 맞는다는 뜻이에요.
            부하 테스트 1,000건 뒤에도 같은 검사로 위반 0을 확인했어요 (통합 테스트 E2E-3).
          </DesignNote>
        </div>
      </Card>
      )}
    </div>
  );
}
