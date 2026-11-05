import { api, newRequestId } from './client';
import type {
  AccountResponse,
  AccountSummaryResponse,
  AllocationResponse,
  DepositResponse,
  IpoResponse,
  IpoSummaryResponse,
  LoginRequest,
  LoginResponse,
  PageResponse,
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
  open: () => api.post<AccountResponse>('/api/v1/accounts', { requestId: newRequestId() }),
  // requestId를 화면에서 받아 같은 입금을 다시 눌러도 한 번만 반영되게 한다
  deposit: (accountId: string, amount: number, requestId: string) =>
    api.post<DepositResponse>(`/api/v1/accounts/${accountId}/deposits`, { requestId, amount }),
};

export const ipoApi = {
  list: () => api.get<PageResponse<IpoSummaryResponse>>('/api/v1/ipos?size=50'),
  get: (ipoId: string) => api.get<IpoResponse>(`/api/v1/ipos/${ipoId}`),
};

export const subscriptionApi = {
  // 202는 "접수"일 뿐 확정이 아니다. 상태는 get으로 다시 조회한다
  create: (body: SubscriptionCreateRequest) => api.post<SubscriptionAcceptedResponse>('/api/v1/subscriptions', body),
  list: () => api.get<PageResponse<SubscriptionSummaryResponse>>('/api/v1/subscriptions?size=50'),
  get: (subscriptionId: string) => api.get<SubscriptionResponse>(`/api/v1/subscriptions/${subscriptionId}`),
};

export const allocationApi = {
  get: (subscriptionId: string) => api.get<AllocationResponse>(`/api/v1/allocations/subscriptions/${subscriptionId}`),
};
