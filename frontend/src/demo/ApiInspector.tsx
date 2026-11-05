import { useEffect, useState } from 'react';
import { exchangeLog, type ExchangeRecord } from '../api/client';
import { DEMO } from './flag';

// 시연용 API 기록 창. 화면 오른쪽 아래 버튼으로 열고 닫는다. 이 앱이 보낸 모든 API 요청과 응답 원문을 보여준다
export function ApiInspector() {
  const [rows, setRows] = useState<ExchangeRecord[]>(exchangeLog.list());
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<number | null>(null);

  useEffect(() => {
    const off = exchangeLog.subscribe(() => setRows(exchangeLog.list()));
    return () => {
      off();
    };
  }, []);

  if (!DEMO) return null;
  const current = rows.find((r) => r.id === selected) ?? rows[0];

  return (
    <>
      <button className="inspector-fab" onClick={() => setOpen((v) => !v)} aria-label="API 요청과 응답 보기">
        <span className="inspector-fab-dot" />
        API {rows.length}
      </button>
      {open && (
        <aside className="inspector">
          <div className="inspector-head">
            <div>
              <div style={{ fontWeight: 800 }}>API 요청과 응답</div>
              <div className="hint">이 화면이 Gateway로 보낸 실제 요청. 토큰과 비밀번호는 가림</div>
            </div>
            <button className="btn btn-ghost btn-sm" onClick={() => setOpen(false)}>닫기</button>
          </div>
          {rows.length === 0 ? (
            <div className="hint" style={{ padding: 16 }}>아직 요청이 없어요</div>
          ) : (
            <div className="inspector-body">
              <ul className="inspector-list">
                {rows.map((r) => (
                  <li key={r.id} className={r.id === current?.id ? 'on' : ''} onClick={() => setSelected(r.id)}>
                    <span className={`method m-${r.method}`}>{r.method}</span>
                    <span className="path">{r.path.replace(/^\/api\/v1/, '')}</span>
                    <span className={`code c-${Math.floor(r.status / 100)}`}>{r.status || 'ERR'}</span>
                  </li>
                ))}
              </ul>
              {current && (
                <div className="inspector-detail">
                  <div className="row wrap" style={{ gap: 8 }}>
                    <span className={`method m-${current.method}`}>{current.method}</span>
                    <code style={{ fontSize: 13 }}>{current.path}</code>
                  </div>
                  <div className="hint" style={{ marginTop: 6 }}>
                    HTTP {current.status || '연결 실패'}, {current.durationMs}ms, {new Date(current.at).toLocaleTimeString('ko-KR')}
                  </div>
                  <div className="inspector-label">요청 헤더</div>
                  <pre className="code">{JSON.stringify(current.requestHeaders, null, 2)}</pre>
                  {current.requestBody !== undefined && (
                    <>
                      <div className="inspector-label">요청 본문</div>
                      <pre className="code">{JSON.stringify(current.requestBody, null, 2)}</pre>
                    </>
                  )}
                  <div className="inspector-label">응답 본문</div>
                  <pre className="code">{typeof current.responseBody === 'string' ? current.responseBody : JSON.stringify(current.responseBody, null, 2)}</pre>
                </div>
              )}
            </div>
          )}
        </aside>
      )}
    </>
  );
}
