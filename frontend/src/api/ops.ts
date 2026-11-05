import { request } from './client';

// 시연 전용 운영 조회 서버(frontend/ops-server)와 Prometheus 조회.
// 백엔드에 정식 운영 조회 API가 없어서 시연 때만 쓴다. 연결이 안 되면 화면에 "미연결"로 표시한다

export interface OpsLedgerRow {
  type: string;
  amount: number;
  cashBalanceAfter: number;
  marginBalanceAfter: number;
  subscriptionId: string | null;
  createdAt: string;
}

export interface OpsEventRow {
  service: 'account' | 'subscription' | 'allocation';
  topic: string;
  messageKey: string;
  eventId: string;
  createdAt: string;
  publishedAt: string | null;
  payload: Record<string, unknown>;
  consumedBy: { consumer: string; processedAt: string }[];
}

export interface OpsTrace {
  subscription: Record<string, unknown> | null;
  margin: Record<string, unknown> | null;
  allocation: Record<string, unknown> | null;
  ledger: OpsLedgerRow[];
  events: OpsEventRow[];
  checkedAt: string;
}

export interface OpsCheck {
  id: string;
  name: string;
  description: string;
  violations: number | null;
  error?: string;
}

export interface OpsSummary {
  outboxUnpublished: { service: string; count: number }[];
  dlt: { topic: string; messages: number }[] | null;
  dltError?: string;
  checks: OpsCheck[];
  checkedAt: string;
}

export const opsApi = {
  ledger: (accountId: string) => request<OpsLedgerRow[]>('GET', `/ops/accounts/${accountId}/ledger`),
  trace: (subscriptionId: string) => request<OpsTrace>('GET', `/ops/subscriptions/${subscriptionId}/trace`),
  summary: () => request<OpsSummary>('GET', '/ops/summary'),
};

// Prometheus HTTP API (/prom → Prometheus). 값이 없으면 null
export interface PromSample {
  labels: Record<string, string>;
  value: number;
}

export async function promQuery(query: string): Promise<PromSample[]> {
  const res = await fetch(`/prom/api/v1/query?query=${encodeURIComponent(query)}`);
  if (!res.ok) throw new Error(`Prometheus 조회 실패 (HTTP ${res.status})`);
  const body = await res.json();
  if (body.status !== 'success') throw new Error('Prometheus 조회 실패');
  return (body.data.result as { metric: Record<string, string>; value: [number, string] }[]).map((r) => ({
    labels: r.metric,
    value: Number(r.value[1]),
  }));
}
