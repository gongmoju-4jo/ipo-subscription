import type { CancelReason, IpoStatus, RejectReason, SubscriptionStatus } from '../api/types';

// 상태 코드를 사용자에게 보여줄 한국어로 바꾼다. 증거금은 "인출"로 통일한다
export type Tone = 'neutral' | 'progress' | 'success' | 'danger' | 'muted';

export const subscriptionStatusLabel: Record<SubscriptionStatus, { label: string; tone: Tone; hint: string }> = {
  PENDING: { label: '청약 접수 중', tone: 'progress', hint: '청약을 받았고 증거금을 인출하는 중이에요' },
  CONFIRMED: { label: '청약 확정', tone: 'success', hint: '증거금 인출이 끝나 청약이 확정됐어요' },
  REJECTED: { label: '청약 거절', tone: 'danger', hint: '증거금을 인출하지 않아 돈이 움직이지 않았어요' },
  COMPENSATING: { label: '증거금 반환 중', tone: 'progress', hint: '처리 중 문제가 생겨 인출한 증거금을 돌려드리는 중이에요' },
  CANCEL_REQUESTED: { label: '취소 처리 중', tone: 'progress', hint: '취소 요청을 처리하고 있어요' },
  CANCELLED: { label: '청약 취소', tone: 'muted', hint: '증거금을 모두 돌려드렸어요. 다시 청약할 수 있어요' },
  ALLOCATED: { label: '배정 완료', tone: 'success', hint: '배정 수량이 정해졌고 정산을 기다리는 중이에요' },
  REFUNDED: { label: '환불 완료', tone: 'success', hint: '배정 대금을 정산하고 남은 증거금을 돌려드렸어요' },
};

export const rejectReasonLabel: Record<RejectReason, string> = {
  INSUFFICIENT_BALANCE: '예수금이 부족해요',
  ALREADY_SUBSCRIBED: '이 종목에 이미 청약했어요 (다른 계좌 포함)',
  ACCOUNT_NOT_FOUND: '청약에 쓸 수 없는 계좌예요',
};

export const cancelReasonLabel: Record<CancelReason, string> = {
  CONFIRM_FAILED: '청약 확정 중 오류가 나서 자동으로 취소했어요',
  TIMEOUT: '계좌 처리 응답이 늦어 자동으로 취소했어요',
  USER_CANCEL: '고객님이 취소했어요',
};

export function ipoStatusLabel(status: IpoStatus, startAt: string, endAt: string, now = Date.now()) {
  if (status === 'ALLOCATED') return { label: '배정 완료', tone: 'muted' as Tone };
  if (status === 'CLOSED') return { label: '청약 마감', tone: 'muted' as Tone };
  if (now < new Date(startAt).getTime()) return { label: '청약 예정', tone: 'neutral' as Tone };
  if (now >= new Date(endAt).getTime()) return { label: '마감 처리 중', tone: 'neutral' as Tone };
  return { label: '청약 중', tone: 'progress' as Tone };
}

// 아직 끝나지 않은 상태 (화면이 자동으로 다시 조회한다)
export const inProgressStatuses: SubscriptionStatus[] = ['PENDING', 'COMPENSATING', 'CANCEL_REQUESTED', 'ALLOCATED'];

export const ledgerTypeLabel: Record<string, string> = {
  DEPOSIT: '입금',
  MARGIN_PENDING: '증거금 인출',
  MARGIN_POST: '배정 대금 정산',
  MARGIN_VOID: '증거금 반환',
  REFUND: '환불',
};

export const topicLabel: Record<string, string> = {
  'subscription.requested': '청약 접수',
  'margin.reserve-requested': '증거금 인출 요청',
  'margin.reserved': '증거금 인출 완료',
  'margin.reserve-failed': '증거금 인출 거절',
  'margin.void-requested': '증거금 반환 요청',
  'margin.voided': '증거금 반환 완료',
  'ipo.closed': '청약 마감',
  'allocation.result': '배정 결과',
  'margin.settled': '정산, 환불 완료',
  'allocation.finished': '배정 종료',
};
