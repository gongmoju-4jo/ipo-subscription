import { useState } from 'react';
import { allocationApi } from '../api/endpoints';
import { ApiError } from '../api/client';
import type { AllocationReplayResponse, IpoStatus } from '../api/types';
import { Badge, Card, ErrorBox } from '../components/ui';
import { shortId } from '../lib/format';
import { useIsAdmin } from './flag';

// 관리자 추첨 재현. 저장된 추첨 시드, 공모주 조건, 청약 수량으로 배정을 다시 계산해 저장된 결과와 청약마다 비교한다
// 읽기만 하는 API라 여러 번 실행해도 데이터 변경 없음. 배정 완료(ALLOCATED) 공모주에서만 표시한다
export function LotteryReplayCard({ ipoId, status }: { ipoId: string; status: IpoStatus }) {
  const isAdmin = useIsAdmin();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<AllocationReplayResponse | null>(null);

  if (!isAdmin || status !== 'ALLOCATED') return null;

  async function run() {
    setBusy(true);
    setError(null);
    try {
      setResult(await allocationApi.replay(ipoId));
    } catch (e) {
      setResult(null);
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title={<>추첨 재현 <small>관리자</small></>}>
      <div className="stack">
        <div className="sub" style={{ fontSize: 14, lineHeight: 1.6 }}>
          저장된 추첨 시드로 배정을 다시 계산해, 저장된 배정 결과와 청약마다 일치하는지 확인해요
        </div>

        {result && (
          <div className={`notice ${result.matched ? 'success' : 'danger'}`}>
            <div className="row" style={{ gap: 8 }}>
              <Badge tone={result.matched ? 'success' : 'danger'}>{result.matched ? '전부 일치' : '불일치 발견'}</Badge>
              <span>
                청약 {result.comparedCount.toLocaleString()}건 비교, 불일치 {result.mismatchedSubscriptionIds.length.toLocaleString()}건
              </span>
            </div>
            {result.mismatchedSubscriptionIds.length > 0 && (
              <div style={{ fontSize: 13, marginTop: 8 }}>
                불일치 청약: {result.mismatchedSubscriptionIds.slice(0, 20).map(shortId).join(', ')}
                {result.mismatchedSubscriptionIds.length > 20 && ` 외 ${result.mismatchedSubscriptionIds.length - 20}건`}
              </div>
            )}
          </div>
        )}

        {error !== null && <ErrorBox error={error} title={replayErrorTitle(error)} />}

        <button className="btn btn-dark btn-block" onClick={run} disabled={busy}>
          {busy ? '재계산 중' : result ? '다시 재현' : '추첨 재현 실행'}
        </button>
      </div>
    </Card>
  );
}

function replayErrorTitle(error: unknown): string {
  if (!(error instanceof ApiError)) return '재현하지 못했어요';
  if (error.status === 404) return '배정 기록이 없어요';
  if (error.status === 422) return '아직 재현할 수 없어요 (배정 진행 중)';
  if (error.status === 403) return '관리자만 재현할 수 있어요';
  return '재현하지 못했어요';
}
