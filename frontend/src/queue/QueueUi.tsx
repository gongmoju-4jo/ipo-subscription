import { useEffect, useRef, useState } from 'react';
import type { QueueEntryResponse } from '../api/types';
import type { AdmissionPhase } from './useAdmission';

// 남은 시간 표시용 현재 시각 (interval마다 갱신)
function useNow(intervalMs: number) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);
  return now;
}

function formatWait(seconds: number) {
  if (seconds < 60) return '1분 안';
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return s === 0 ? `약 ${m}분` : `약 ${m}분 ${s}초`;
}

function formatClock(ms: number) {
  const total = Math.max(0, Math.ceil(ms / 1000));
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}

// 바뀐 숫자가 부드럽게 따라가게 한다 (순번이 줄어드는 게 눈에 보이게)
function useEased(target: number) {
  const [shown, setShown] = useState(target);
  const from = useRef(target);
  useEffect(() => {
    const start = performance.now();
    const begin = from.current;
    let frame = 0;
    const step = (t: number) => {
      const k = Math.min(1, (t - start) / 600);
      const eased = 1 - (1 - k) ** 3;
      const v = Math.round(begin + (target - begin) * eased);
      setShown(v);
      from.current = v;
      if (k < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [target]);
  return shown;
}

interface SheetProps {
  ipoName: string;
  phase: AdmissionPhase;
  entry: QueueEntryResponse | null;
  firstPosition: number | null;
  unstable: boolean;
  onLeave: () => void;
  onRejoin: () => void;
  onBack: () => void;
}

/**
 * 청약 화면 입장 대기 창. 넓은 화면은 가운데 창, 좁은 화면은 아래에서 올라오는 시트.
 * 대기 중(waiting), 이탈 처리됨(removed), 마감(closed)일 때만 띄운다.
 */
export function QueueSheet({ ipoName, phase, entry, firstPosition, unstable, onLeave, onRejoin, onBack }: SheetProps) {
  const position = entry?.position ?? 0;
  const ahead = useEased(Math.max(0, position - 1));
  const waiting = useEased(entry?.waitingCount ?? 0);
  const start = firstPosition ?? position;
  // 처음 순번에서 얼마나 줄었는지. 처음부터 1번이면 거의 다 온 것으로 보여 준다
  const progress = start <= 1 ? 0.92 : Math.min(0.97, Math.max(0.04, (start - position) / (start - 1)));

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && phase === 'waiting') onLeave(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [phase, onLeave]);

  return (
    <div className="queue-overlay" role="dialog" aria-modal="true" aria-labelledby="queue-title">
      <div className="queue-sheet">
        <div className="queue-grip" aria-hidden />
        <div className="row between" style={{ alignItems: 'flex-start' }}>
          <div>
            <div className="queue-eyebrow">{ipoName}</div>
            <h2 id="queue-title" className="queue-title">
              {phase === 'removed' ? '대기열에서 이탈 처리됐어요' : phase === 'closed' ? '청약이 마감됐어요' : '청약 화면 입장 대기 중'}
            </h2>
          </div>
          {phase === 'waiting' && (
            <button className="queue-close" onClick={onLeave} aria-label="대기열 이탈" title="대기열 이탈">
              <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden><path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" /></svg>
            </button>
          )}
        </div>

        {phase === 'waiting' && entry && (
          <>
            <div className="queue-hero">
              <div className="queue-label">내 앞 대기 인원</div>
              <div className="queue-number num">{ahead.toLocaleString()}<span>명</span></div>
            </div>
            <div className="queue-bar" aria-hidden><div className="queue-bar-fill" style={{ transform: `scaleX(${progress})` }} /></div>
            <dl className="queue-stats">
              <div><dt>예상 대기</dt><dd>{formatWait(entry.estimatedWaitSeconds)}</dd></div>
              <div><dt>전체 대기 인원</dt><dd className="num">{waiting.toLocaleString()}명</dd></div>
            </dl>
            {unstable ? (
              <div className="notice warn">연결이 원활하지 않아요. 대기 순번은 유지한 채 다시 확인하고 있어요</div>
            ) : (
              <div className="queue-note"><span className="queue-pulse" />차례가 되면 청약 화면이 바로 열려요. 이 화면을 닫거나 다른 탭에 오래 두면 대기열에서 이탈 처리될 수 있어요</div>
            )}
          </>
        )}

        {phase === 'removed' && (
          <>
            <p className="queue-desc">순번 조회가 한동안 끊겨 대기열에서 이탈 처리됐어요. 다시 진입하면 대기열 맨 뒤부터 기다려요</p>
            <div className="queue-actions">
              <button className="btn btn-ghost" onClick={onBack}>공모주로 돌아가기</button>
              <button className="btn btn-primary" onClick={onRejoin}>대기열 다시 진입</button>
            </div>
          </>
        )}

        {phase === 'closed' && (
          <>
            <p className="queue-desc">청약 기간이 끝나 더 이상 신청할 수 없어요</p>
            <div className="queue-actions">
              <button className="btn btn-primary" onClick={onBack}>공모주로 돌아가기</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

/**
 * 청약 가능 시간 (입장 허가 유효 시간). 남은 시간을 글자로만 보여 주고, 1분 아래면 글자색만 주황으로 바꾼다.
 * 남은 시간은 이 기기 시계로 계산한다 (만료 판정은 서버가 하고, 지나서 신청하면 403을 받아 대기열에 다시 진입하게 한다).
 */
export function AdmissionTimer({ expiresAt }: { expiresAt: string }) {
  const now = useNow(500);
  const remaining = Math.max(0, Date.parse(expiresAt) - now);

  return (
    <div className="admit-timer" role="timer" aria-live="off">
      <span>청약 가능 시간</span>
      <strong className={`num ${remaining <= 60_000 ? 'admit-timer-soon' : ''}`}>{formatClock(remaining)}</strong>
      <span className="admit-timer-hint">시간 안에 청약을 완료해 주세요</span>
    </div>
  );
}
