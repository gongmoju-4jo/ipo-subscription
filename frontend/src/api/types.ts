// 백엔드 DTO(presentation 계층 record)와 같은 필드. 백엔드가 바뀌면 여기부터 맞춘다
// 시각은 OffsetDateTime(+09:00) 문자열, 금액은 원 단위 정수

export interface ApiErrorDetail {
  field?: string;
  reason?: string;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  details?: ApiErrorDetail[];
}

export interface ApiEnvelope<T> {
  success: boolean;
  data?: T;
  message?: string;
  error?: ApiErrorBody;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// 회원 (account-service)
export interface SignUpRequest {
  loginId: string;
  password: string;
  name: string;
}
export interface SignUpResponse {
  userId: string;
  loginId: string;
  name: string;
  createdAt: string;
}
export interface LoginRequest {
  loginId: string;
  password: string;
}
export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

// 계좌 (account-service)
export interface AccountSummaryResponse {
  accountId: string;
  accountNumber: string;
  cashBalance: number;
  marginBalance: number;
  createdAt: string;
}
export interface AccountResponse extends AccountSummaryResponse {
  userId: string;
}
export interface DepositResponse {
  depositId: string;
  accountId: string;
  amount: number;
  cashBalance: number;
  marginBalance: number;
  createdAt: string;
}

// 공모주 (subscription-service)
export type IpoStatus = 'OPEN' | 'CLOSED' | 'ALLOCATED';
export interface IpoSummaryResponse {
  ipoId: string;
  name: string;
  offerPrice: number;
  retailQuantity: number;
  subscriptionStartAt: string;
  subscriptionEndAt: string;
  status: IpoStatus;
}
export interface IpoResponse extends IpoSummaryResponse {
  equalQuantity: number;
  minQuantity: number;
  maxQuantity: number;
  marginRate: number;
  refundDate: string;
}

// 접속 대기열 (api-gateway가 직접 응답)
export type QueueStatus = 'WAITING' | 'ADMITTED';
export interface QueueEntryResponse {
  ipoId: string;
  status: QueueStatus;
  /** 내 앞 인원 + 1 (1부터). 입장 허가 상태면 0 */
  position: number;
  waitingCount: number;
  /** 어림값 (초) */
  estimatedWaitSeconds: number;
  enteredAt: string;
  /** 입장 허가 상태일 때 이 시각까지 청약해야 한다. 대기 중이면 null */
  admissionExpiresAt: string | null;
  /** 다음 순번 조회까지 기다릴 시간 */
  pollIntervalMillis: number;
}

// 청약 (subscription-service)
export type SubscriptionStatus =
  | 'PENDING'
  | 'CONFIRMED'
  | 'REJECTED'
  | 'COMPENSATING'
  | 'CANCEL_REQUESTED'
  | 'CANCELLED'
  | 'ALLOCATED'
  | 'REFUNDED';
export type RejectReason = 'ALREADY_SUBSCRIBED' | 'INSUFFICIENT_BALANCE' | 'ACCOUNT_NOT_FOUND';
export type CancelReason = 'CONFIRM_FAILED' | 'TIMEOUT' | 'USER_CANCEL';

export interface SubscriptionCreateRequest {
  requestId: string;
  ipoId: string;
  accountId: string;
  requestedQuantity: number;
}
export interface SubscriptionAcceptedResponse {
  subscriptionId: string;
  requestId: string;
  status: string;
  statusUrl: string;
}
export interface SubscriptionSummaryResponse {
  subscriptionId: string;
  ipoId: string;
  ipoName: string;
  accountId: string;
  requestedQuantity: number;
  marginAmount: number;
  status: SubscriptionStatus;
  receivedAt: string;
  confirmedAt: string | null;
}
export interface SubscriptionResponse extends SubscriptionSummaryResponse {
  requestId: string;
  rejectReason: RejectReason | null;
  cancelReason: CancelReason | null;
  allocatedQuantity: number | null;
  allocatedAmount: number | null;
  refundAmount: number | null;
}

// 원장 (account-service GET /api/v1/accounts/{id}/ledger)
export type LedgerEntryType = 'DEPOSIT' | 'MARGIN_PENDING' | 'MARGIN_POST' | 'MARGIN_VOID' | 'REFUND';
export interface LedgerEntryResponse {
  ledgerEntryId: string;
  type: LedgerEntryType;
  amount: number;
  cashBalanceAfter: number;
  marginBalanceAfter: number;
  subscriptionId: string | null;
  createdAt: string;
}

// 배정 (allocation-service)
export interface AllocationResponse {
  ipoId: string;
  subscriptionId: string;
  equalAllocatedQuantity: number;
  proportionalAllocatedQuantity: number;
  allocatedQuantity: number;
  allocatedAmount: number;
}
