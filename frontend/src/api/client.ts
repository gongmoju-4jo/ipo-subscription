import type { ApiEnvelope, ApiErrorBody } from './types';

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

// 처리 상세 보기에 쓰려고 최근 요청, 응답을 몇 개 기억한다 (토큰, 비밀번호는 남기지 않음)
export interface ExchangeRecord {
  at: string;
  method: string;
  path: string;
  status: number;
  requestBody?: unknown;
  responseBody?: unknown;
}
const RECENT_LIMIT = 30;
const recent: ExchangeRecord[] = [];
const listeners = new Set<() => void>();
export const exchangeLog = {
  list: () => recent.slice(),
  subscribe: (fn: () => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
};
function remember(rec: ExchangeRecord) {
  recent.unshift(rec);
  recent.length = Math.min(recent.length, RECENT_LIMIT);
  listeners.forEach((fn) => fn());
}
function redact(body: unknown): unknown {
  if (body && typeof body === 'object') {
    const copy: Record<string, unknown> = { ...(body as Record<string, unknown>) };
    for (const k of ['password', 'accessToken']) if (k in copy) copy[k] = '(가림)';
    return copy;
  }
  return body;
}

export async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;

  let res: Response;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    remember({ at: new Date().toISOString(), method, path, status: 0, requestBody: redact(body) });
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
  remember({ at: new Date().toISOString(), method, path, status: res.status, requestBody: redact(body), responseBody: redact(json?.data ?? json) });

  if (!res.ok || json?.success === false) {
    if (res.status === 401 && onUnauthorized) onUnauthorized();
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
