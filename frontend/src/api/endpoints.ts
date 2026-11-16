import { api, newRequestId } from './client';
import type {
  AccountResponse,
  AccountSummaryResponse,
  AllocationReplayResponse,
  AllocationResponse,
  DepositResponse,
  IpoCreateRequest,
  IpoCreateResponse,
  LedgerEntryResponse,
  IpoResponse,
  IpoSummaryResponse,
  LoginRequest,
  LoginResponse,
  PageResponse,
  QueueEntryResponse,
  SignUpRequest,
  SignUpResponse,
  SubscriptionAcceptedResponse,
  SubscriptionCreateRequest,
  SubscriptionResponse,
  SubscriptionSummaryResponse,
} from './types';

// 실제 백엔드에 있는 API만 둔다 (develop 기준). 없는 API는 여기에 만들지 않는다

export const authApi = {
  signUp: (body: SignUpRequest) => api.post<SignUpResponse>('/api/v1/users', body),
  login: (body: LoginRequest) => api.post<LoginResponse>('/api/v1/auth/login', body),
};

export const accountApi = {
  list: () => api.get<PageResponse<AccountSummaryResponse>>('/api/v1/accounts?size=50'),
  get: (accountId: string) => api.get<AccountResponse>(`/api/v1/accounts/${accountId}`),
  ledger: (accountId: string) => api.get<PageResponse<LedgerEntryResponse>>(`/api/v1/accounts/${accountId}/ledger-entries?size=50`),
  open: () => api.post<AccountResponse>('/api/v1/accounts', { requestId: newRequestId() }),
  // requestId를 화면에서 받아 같은 입금을 다시 눌러도 한 번만 반영되게 한다
  deposit: (accountId: string, amount: number, requestId: string) =>
    api.post<DepositResponse>(`/api/v1/accounts/${accountId}/deposits`, { requestId, amount }),
};

export const ipoApi = {
  list: () => api.get<PageResponse<IpoSummaryResponse>>('/api/v1/ipos?size=50'),
  get: (ipoId: string) => api.get<IpoResponse>(`/api/v1/ipos/${ipoId}`),
};

// 관리자 전용 (Gateway가 ADMIN이 아니면 403으로 거부한다)
// 관리자 계정 생성 (ADMIN만, 성공 201). 응답은 회원가입과 같은 형식이고 만든 계정의 역할은 항상 ADMIN이다
export const adminUserApi = {
  create: (body: SignUpRequest) => api.post<SignUpResponse>('/api/v1/admin/users', body),
};

export const adminIpoApi = {
  // 등록하면 OPEN 상태, 성공 201. 게이트웨이 대기열은 최대 5초 후부터 이 공모주로 진입 가능하다
  create: (body: IpoCreateRequest) => api.post<IpoCreateResponse>('/api/v1/admin/ipos', body),
};

export const subscriptionApi = {
  // 202는 "접수"일 뿐 확정이 아니다. 상태는 get으로 다시 조회한다
  create: (body: SubscriptionCreateRequest) => api.post<SubscriptionAcceptedResponse>('/api/v1/subscriptions', body),
  list: () => api.get<PageResponse<SubscriptionSummaryResponse>>('/api/v1/subscriptions?size=50'),
  get: (subscriptionId: string) => api.get<SubscriptionResponse>(`/api/v1/subscriptions/${subscriptionId}`),
};

// 접속 대기열 (게이트웨이가 직접 응답). 청약 신청은 입장 허가가 있어야 서비스로 간다 (없으면 403 QUEUE_ADMISSION_REQUIRED)
export const queueApi = {
  // 새로 진입 201, 이미 대기 중이거나 허가 상태면 200 (처음 순번 유지)
  enter: (ipoId: string) => api.post<QueueEntryResponse>(`/api/v1/queues/${ipoId}/entries`),
  // 대기 중도 아니고 허가도 없으면 404 (이탈 처리됐거나 허가가 만료됨)
  me: (ipoId: string) => api.get<QueueEntryResponse>(`/api/v1/queues/${ipoId}/entries/me`),
  // 대기열 이탈: 대기 순번과 허가를 모두 지운다. 없어도 200
  leave: (ipoId: string, options?: { keepalive?: boolean }) => api.delete<void>(`/api/v1/queues/${ipoId}/entries/me`, options),
};

export const allocationApi = {
  get: (subscriptionId: string) => api.get<AllocationResponse>(`/api/v1/allocations/subscriptions/${subscriptionId}`),
  // 관리자 전용. 배정 회차가 없으면 404, 배정이 끝나지 않았으면 422
  replay: (ipoId: string) => api.get<AllocationReplayResponse>(`/api/v1/admin/allocations/ipos/${ipoId}/replay`),
};
