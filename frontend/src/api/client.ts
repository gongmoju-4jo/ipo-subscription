import type { ApiEnvelope, ApiErrorBody } from './types';
import { DEMO } from '../demo/flag';

// 모든 백엔드 호출은 여기를 거친다. 화면 컴포넌트에서 fetch를 직접 쓰지 않는다
// - Gateway(/api)는 JWT가 필요하다. 토큰은 tokenStore에서 꺼내 Authorization에 넣는다
// - 공통 응답(ApiResponse)을 풀어서 data만 돌려주고, 실패는 ApiError로 던진다

let accessToken: string | null = null;
let onUnauthorized: (() => void) | null = null;

export const tokenStore = {
  get: () => accessToken,
  set: (token: string | null) => {
    accessToken = token;
  },
  onUnauthorized: (fn: () => void) => {
    onUnauthorized = fn;
  },
};

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: ApiErrorBody['details'];
  readonly path: string;

  constructor(status: number, body: ApiErrorBody | undefined, path: string) {
    super(body?.message || `요청이 실패했습니다 (HTTP ${status})`);
    this.status = status;
    this.code = body?.code || (status === 0 ? 'NETWORK_ERROR' : 'UNKNOWN_ERROR');
    this.details = body?.details;
    this.path = path;
  }
}

// 시연용 API 기록 창에 보여줄 최근 요청, 응답 (DEMO일 때만 기록. 토큰, 비밀번호는 가림)
export interface ExchangeRecord {
  id: number;
  at: string;
  method: string;
  path: string;
  status: number;
  durationMs: number;
  requestHeaders: Record<string, string>;
  requestBody?: unknown;
  responseBody?: unknown;
}
const RECENT_LIMIT = 100;
let seq = 0;
const recent: ExchangeRecord[] = [];
const listeners = new Set<() => void>();
export const exchangeLog = {
  list: () => recent.slice(),
  subscribe: (fn: () => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
};
function remember(rec: Omit<ExchangeRecord, 'id'>) {
  if (!DEMO) return;
  recent.unshift({ ...rec, id: ++seq });
  recent.length = Math.min(recent.length, RECENT_LIMIT);
  listeners.forEach((fn) => fn());
}
// 응답은 { success, data: { accessToken } }처럼 중첩돼서 안쪽까지 내려가며 가린다
const SENSITIVE_KEYS = new Set(['password', 'accessToken']);
function redact(body: unknown): unknown {
  if (Array.isArray(body)) return body.map(redact);
  if (body && typeof body === 'object') {
    return Object.fromEntries(
      Object.entries(body as Record<string, unknown>).map(([k, v]) => [k, SENSITIVE_KEYS.has(k) ? '(가림)' : redact(v)]),
    );
  }
  return body;
}

export async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;

  const shownHeaders = { ...headers, ...(headers.Authorization ? { Authorization: 'Bearer (가림)' } : {}) };
  const started = performance.now();
  let res: Response;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    remember({ at: new Date().toISOString(), method, path, status: 0, durationMs: Math.round(performance.now() - started), requestHeaders: shownHeaders, requestBody: redact(body) });
    throw new ApiError(0, { code: 'NETWORK_ERROR', message: '서버에 연결할 수 없습니다. 서비스가 실행 중인지 확인해 주세요.' }, path);
  }

  let json: ApiEnvelope<T> | undefined;
  const text = await res.text();
  if (text) {
    try {
      json = JSON.parse(text) as ApiEnvelope<T>;
    } catch {
      json = undefined;
    }
  }
  remember({ at: new Date().toISOString(), method, path, status: res.status, durationMs: Math.round(performance.now() - started), requestHeaders: shownHeaders, requestBody: redact(body), responseBody: redact(json ?? text) });

  if (!res.ok || json?.success === false) {
    // 운영 조회 서버(/ops)의 401은 그 서버 설정 문제일 수 있어서 로그아웃시키지 않는다
    if (res.status === 401 && !path.startsWith('/ops/') && onUnauthorized) onUnauthorized();
    throw new ApiError(res.status, json?.error, path);
  }
  return (json?.data ?? (json as unknown)) as T;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
};

export function newRequestId(): string {
  return crypto.randomUUID();
}
