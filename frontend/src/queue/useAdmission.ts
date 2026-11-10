import { useCallback, useEffect, useRef, useState } from 'react';
import { queueApi } from '../api/endpoints';
import { ApiError } from '../api/client';
import type { QueueEntryResponse } from '../api/types';

// 청약 화면 입장 대기열 상태.
// - entering: 진입 요청 중 (첫 응답 전)
// - waiting: 대기 중. 백엔드가 알려준 간격(pollIntervalMillis)마다 순서를 다시 조회한다
// - admitted: 입장 허가. admissionExpiresAt까지 청약할 수 있다
// - expired: 허가 시간이 지났거나, 청약 신청이 403 QUEUE_ADMISSION_REQUIRED로 거절됨
// - removed: 이탈 처리됨 (조회 404: 조회가 오래 끊겨 이탈 처리됐거나 허가가 만료됨)
// - closed: 기다리는 동안 청약이 마감됨 (게이트웨이는 마감 시각을 모르므로 화면이 공모주 마감 시각으로 판단)
// - left: 사용자가 이탈함
export type AdmissionPhase = 'idle' | 'entering' | 'waiting' | 'admitted' | 'expired' | 'removed' | 'closed' | 'left';

// 503(Redis 장애)이나 네트워크 오류 뒤 다시 시도하기까지 기다리는 시간
const RETRY_MS = 3000;
// 화면을 떠날 때 보내는 이탈 요청을 잠깐 미룬다. 개발 모드(StrictMode)가 화면을 지웠다 다시 띄우거나
// 같은 공모주 화면으로 곧바로 다시 들어오면 이탈 요청을 취소해, 진입하자마자 스스로 이탈하지 않게 한다
const LEAVE_DELAY_MS = 400;
const pendingLeaves = new Map<string, number>();

function scheduleLeave(ipoId: string) {
  cancelLeave(ipoId);
  pendingLeaves.set(ipoId, window.setTimeout(() => {
    pendingLeaves.delete(ipoId);
    queueApi.leave(ipoId, { keepalive: true }).catch(() => undefined);
  }, LEAVE_DELAY_MS));
}

function cancelLeave(ipoId: string) {
  const id = pendingLeaves.get(ipoId);
  if (id !== undefined) {
    window.clearTimeout(id);
    pendingLeaves.delete(ipoId);
  }
}

// 다시 시도하면 되는 오류: 서버 연결 실패, 503 등 5xx
function isTransient(e: unknown) {
  return e instanceof ApiError && (e.status === 0 || e.status >= 500);
}

export interface Admission {
  phase: AdmissionPhase;
  entry: QueueEntryResponse | null;
  /** 처음 받은 순번 (진행 막대 계산용) */
  firstPosition: number | null;
  /** 연결이 불안정해 다시 시도하는 중 */
  unstable: boolean;
  /** 다시 시도해도 소용없는 진입 실패 (400 등) */
  error: unknown;
  join: () => void;
  leave: () => Promise<void>;
  markExpired: () => void;
}

export function useAdmission(ipoId: string, { enabled, closesAt }: { enabled: boolean; closesAt?: string }): Admission {
  const [phase, setPhase] = useState<AdmissionPhase>('idle');
  const [entry, setEntry] = useState<QueueEntryResponse | null>(null);
  const [firstPosition, setFirstPosition] = useState<number | null>(null);
  const [unstable, setUnstable] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const phaseRef = useRef<AdmissionPhase>('idle');
  // 진입, 이탈, 다시 진입할 때마다 늘린다. 이전 흐름에서 늦게 도착한 응답과 예약된 조회는 버린다
  const generation = useRef(0);
  // 진입, 순번 조회 요청마다 붙이는 번호. 응답은 보낸 순서대로 오지 않을 수 있어서
  // 이미 처리한 응답보다 먼저 보낸 요청의 응답은 버린다 (늦게 온 WAITING이 ADMITTED를 되돌리지 않게)
  const requestSeq = useRef(0);
  const settledSeq = useRef(0);
  const pollTimer = useRef<number>();
  const expiryTimer = useRef<number>();
  const closeTimer = useRef<number>();

  const go = useCallback((next: AdmissionPhase) => {
    phaseRef.current = next;
    setPhase(next);
  }, []);

  const clearTimers = useCallback(() => {
    window.clearTimeout(pollTimer.current);
    window.clearTimeout(expiryTimer.current);
    window.clearTimeout(closeTimer.current);
  }, []);

  const isClosed = useCallback(() => (closesAt ? Date.now() >= Date.parse(closesAt) : false), [closesAt]);

  // 타이머가 부르는 함수는 항상 최신 것을 쓰도록 ref에 둔다
  const actions = useRef<{ poll: (gen: number) => void; enter: (gen: number) => void }>({ poll: () => undefined, enter: () => undefined });

  const closeOut = useCallback(() => {
    generation.current++;
    clearTimers();
    go('closed');
    queueApi.leave(ipoId).catch(() => undefined);
  }, [clearTimers, go, ipoId]);

  // 이 세대의 응답이고, 이미 처리한 응답보다 나중에 보낸 요청이면 처리한다
  const accept = useCallback((gen: number, seq: number) => {
    if (gen !== generation.current || seq < settledSeq.current) return false;
    settledSeq.current = seq;
    return true;
  }, []);

  const apply = useCallback((gen: number, seq: number, res: QueueEntryResponse) => {
    if (!accept(gen, seq)) return;
    setUnstable(false);
    setEntry(res);
    window.clearTimeout(pollTimer.current);
    if (res.status === 'ADMITTED') {
      go('admitted');
      const left = res.admissionExpiresAt ? Date.parse(res.admissionExpiresAt) - Date.now() : 0;
      window.clearTimeout(expiryTimer.current);
      expiryTimer.current = window.setTimeout(() => {
        if (gen === generation.current && phaseRef.current === 'admitted') go('expired');
      }, Math.max(0, left));
      return;
    }
    go('waiting');
    setFirstPosition((p) => p ?? res.position);
    pollTimer.current = window.setTimeout(() => actions.current.poll(gen), res.pollIntervalMillis);
  }, [accept, go]);

  const fail = useCallback((gen: number, seq: number, e: unknown, retry: 'poll' | 'enter') => {
    if (!accept(gen, seq)) return;
    if (retry === 'poll' && e instanceof ApiError && e.status === 404) {
      clearTimers();
      go('removed');
      return;
    }
    if (isTransient(e)) {
      setUnstable(true);
      window.clearTimeout(pollTimer.current);
      pollTimer.current = window.setTimeout(() => actions.current[retry](gen), RETRY_MS);
      return;
    }
    // 401은 공통 처리(로그인 화면)로 넘어가고, 그 밖의 4xx는 다시 시도해도 같다
    clearTimers();
    setError(e);
    go('idle');
  }, [accept, clearTimers, go]);

  actions.current.poll = (gen: number) => {
    if (gen !== generation.current) return;
    if (isClosed()) {
      closeOut();
      return;
    }
    const seq = ++requestSeq.current;
    queueApi.me(ipoId).then((res) => apply(gen, seq, res), (e) => fail(gen, seq, e, 'poll'));
  };
  actions.current.enter = (gen: number) => {
    if (gen !== generation.current) return;
    const seq = ++requestSeq.current;
    queueApi.enter(ipoId).then((res) => apply(gen, seq, res), (e) => fail(gen, seq, e, 'enter'));
  };

  const join = useCallback(() => {
    const gen = ++generation.current;
    clearTimers();
    cancelLeave(ipoId);
    setEntry(null);
    setFirstPosition(null);
    setUnstable(false);
    setError(null);
    if (isClosed()) {
      go('closed');
      return;
    }
    go('entering');
    // 기다리는 동안 마감되면 조회를 멈추고 마감으로 바꾼다 (조회 간격이 길어도 제때 바뀌게)
    if (closesAt) {
      const untilClose = Date.parse(closesAt) - Date.now();
      // setTimeout은 약 24.8일을 넘으면 바로 실행되므로 그보다 먼 마감은 조회 때 확인한다
      if (untilClose < 2_000_000_000) {
        closeTimer.current = window.setTimeout(() => {
          if (gen === generation.current && (phaseRef.current === 'waiting' || phaseRef.current === 'entering')) closeOut();
        }, untilClose);
      }
    }
    actions.current.enter(gen);
  }, [clearTimers, closeOut, closesAt, go, ipoId, isClosed]);

  const leave = useCallback(async () => {
    generation.current++;
    clearTimers();
    cancelLeave(ipoId);
    go('left');
    try {
      await queueApi.leave(ipoId);
    } catch {
      // 이탈 요청이 실패해도 조회가 끊기면 이탈 기한 뒤 대기열에서 이탈 처리된다
    }
  }, [clearTimers, go, ipoId]);

  const markExpired = useCallback(() => {
    generation.current++;
    clearTimers();
    go('expired');
  }, [clearTimers, go]);

  // 화면에 들어오면 대기열에 진입한다 (이미 대기 중이면 처음 순번, 이미 허가 상태면 바로 허가)
  useEffect(() => {
    if (!enabled) return undefined;
    join();
    return () => {
      generation.current++;
      clearTimers();
      // 대기 중에 화면을 떠나면 이탈한다. 허가를 받은 뒤에는 남겨 둔다 (허가 시간 안에 다시 들어올 수 있게)
      if (phaseRef.current === 'waiting' || phaseRef.current === 'entering') scheduleLeave(ipoId);
      phaseRef.current = 'idle';
    };
  }, [enabled, ipoId, join, clearTimers]);

  // 다른 탭에 있다가 돌아오면 바로 다시 조회한다 (브라우저가 숨은 탭의 타이머를 늦추기 때문)
  // 창을 닫거나 다른 사이트로 가면 대기 중이던 대기열에서 바로 이탈한다
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible' && phaseRef.current === 'waiting') {
        window.clearTimeout(pollTimer.current);
        actions.current.poll(generation.current);
      }
    };
    const onPageHide = () => {
      if (phaseRef.current === 'waiting' || phaseRef.current === 'entering') {
        queueApi.leave(ipoId, { keepalive: true }).catch(() => undefined);
      }
    };
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('pagehide', onPageHide);
    };
  }, [ipoId]);

  return { phase, entry, firstPosition, unstable, error, join, leave, markExpired };
}
