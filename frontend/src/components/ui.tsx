import { useEffect, useState, type ReactNode } from 'react';
import { ApiError, exchangeLog, type ExchangeRecord } from '../api/client';
import type { Tone } from '../lib/status';

export function Card({ title, extra, children, className = '' }: { title?: ReactNode; extra?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || extra) && (
        <div className="row between" style={{ marginBottom: 14 }}>
          {title ? <h2 className="card-title" style={{ margin: 0 }}>{title}</h2> : <span />}
          {extra}
        </div>
      )}
      {children}
    </section>
  );
}

export function Badge({ tone, children }: { tone: Tone | 'warn'; children: ReactNode }) {
  return (
    <span className={`badge ${tone}`}>
      {tone === 'progress' && <span className="dot" />}
      {children}
    </span>
  );
}

export function Loading({ lines = 3 }: { lines?: number }) {
  return (
    <div className="stack" style={{ gap: 10 }}>
      {Array.from({ length: lines }).map((_, i) => (
        <div key={i} className="skeleton" style={{ width: `${90 - i * 15}%` }} />
      ))}
    </div>
  );
}

export function Empty({ title, desc, action }: { title: string; desc?: string; action?: ReactNode }) {
  return (
    <div style={{ textAlign: 'center', padding: '28px 8px' }}>
      <div style={{ fontWeight: 700 }}>{title}</div>
      {desc && <div className="muted" style={{ fontSize: 14, marginTop: 4 }}>{desc}</div>}
      {action && <div style={{ marginTop: 14 }}>{action}</div>}
    </div>
  );
}

export function ErrorBox({ error, title = '불러오지 못했어요' }: { error: unknown; title?: string }) {
  const e = error instanceof ApiError ? error : null;
  return (
    <div className="notice danger">
      <strong>{title}</strong>
      <div>{e ? e.message : error instanceof Error ? error.message : '알 수 없는 오류'}</div>
      {e && (
        <div style={{ fontSize: 12, opacity: 0.8, marginTop: 4 }}>
          {e.status ? `HTTP ${e.status}, ` : ''}
          {e.code}
        </div>
      )}
    </div>
  );
}

// 연결되지 않은 데이터는 지어내지 않고 이렇게 표시한다
export function NotConnected({ what, why }: { what: string; why: string }) {
  return (
    <div className="notice gray">
      <strong>{what}: 확인 불가</strong>
      <div>{why}</div>
    </div>
  );
}

export function Money({ value, size = 'mid' }: { value: number | null | undefined; size?: 'big' | 'mid' | 'sm' }) {
  const cls = size === 'big' ? 'big num' : size === 'mid' ? 'mid num' : 'num';
  if (value === null || value === undefined) return <span className={cls}>-</span>;
  return (
    <span className={cls}>
      {value.toLocaleString('ko-KR')}
      <span style={{ fontSize: '0.6em', fontWeight: 600, marginLeft: 2 }}>원</span>
    </span>
  );
}

export function DevDetails({ summary = '처리 상세 보기', data, children }: { summary?: string; data?: unknown; children?: ReactNode }) {
  return (
    <details className="dev">
      <summary>{summary}</summary>
      {children}
      {data !== undefined && <pre className="code">{JSON.stringify(data, null, 2)}</pre>}
    </details>
  );
}

// 이 화면에서 실제로 오간 API 요청, 응답 (토큰과 비밀번호는 가림)
export function RecentExchanges({ filter }: { filter?: (r: ExchangeRecord) => boolean }) {
  const [rows, setRows] = useState(exchangeLog.list());
  useEffect(() => {
    const off = exchangeLog.subscribe(() => setRows(exchangeLog.list()));
    return () => {
      off();
    };
  }, []);
  const shown = filter ? rows.filter(filter) : rows;
  return (
    <DevDetails summary={`실제 API 요청과 응답 (${shown.length}건)`}>
      {shown.length === 0 && <div className="hint" style={{ marginTop: 8 }}>아직 요청이 없어요</div>}
      {shown.slice(0, 8).map((r, i) => (
        <div key={i} style={{ marginTop: 10 }}>
          <div className="row" style={{ fontSize: 13 }}>
            <Badge tone={r.status >= 200 && r.status < 300 ? 'success' : r.status === 0 ? 'muted' : 'danger'}>{r.status || '연결 실패'}</Badge>
            <code>{r.method} {r.path}</code>
            <span className="muted">{new Date(r.at).toLocaleTimeString('ko-KR')}</span>
          </div>
          <pre className="code">{JSON.stringify({ request: r.requestBody, response: r.responseBody }, null, 2)}</pre>
        </div>
      ))}
    </DevDetails>
  );
}
