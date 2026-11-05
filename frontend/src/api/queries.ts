import { useQuery } from '@tanstack/react-query';
import { accountApi, allocationApi, ipoApi, subscriptionApi } from './endpoints';
import { ApiError } from './client';
import { opsApi } from './ops';
import { inProgressStatuses } from '../lib/status';

// 서버 상태(조회 결과)는 React Query가 관리한다. 화면 상태(입력값 등)는 각 화면이 관리한다

export const keys = {
  accounts: ['accounts'] as const,
  ipos: ['ipos'] as const,
  ipo: (id: string) => ['ipo', id] as const,
  subscriptions: ['subscriptions'] as const,
  subscription: (id: string) => ['subscription', id] as const,
  allocation: (id: string) => ['allocation', id] as const,
  ledger: (id: string) => ['ledger', id] as const,
  trace: (id: string) => ['ops', 'trace', id] as const,
  opsSummary: ['ops', 'summary'] as const,
};

export const useAccounts = () => useQuery({ queryKey: keys.accounts, queryFn: accountApi.list });
export const useIpos = () => useQuery({ queryKey: keys.ipos, queryFn: ipoApi.list });
export const useIpo = (id: string) => useQuery({ queryKey: keys.ipo(id), queryFn: () => ipoApi.get(id) });

export const useSubscriptions = () =>
  useQuery({
    queryKey: keys.subscriptions,
    queryFn: subscriptionApi.list,
    // 진행 중인 청약이 있으면 2초마다 다시 조회한다
    refetchInterval: (q) => (q.state.data?.items.some((s) => inProgressStatuses.includes(s.status)) ? 2000 : false),
  });

export const useSubscription = (id: string) =>
  useQuery({
    queryKey: keys.subscription(id),
    queryFn: () => subscriptionApi.get(id),
    // 202 직후에는 아직 저장 전이라 404일 수 있다 (백엔드 명세). 404는 잠시 뒤 다시 조회한다
    retry: (count, err) => err instanceof ApiError && err.status === 404 && count < 15,
    retryDelay: 1000,
    refetchInterval: (q) => (q.state.data && inProgressStatuses.includes(q.state.data.status) ? 1500 : false),
  });

export const useAllocation = (id: string, enabled: boolean) =>
  useQuery({
    queryKey: keys.allocation(id),
    queryFn: () => allocationApi.get(id),
    enabled,
    retry: false,
  });

export const useLedger = (accountId: string | undefined) =>
  useQuery({ queryKey: keys.ledger(accountId ?? ''), queryFn: () => accountApi.ledger(accountId!), enabled: !!accountId });

// 시연용 운영 조회 (연결 안 되면 error로 남고 화면은 "확인 불가"를 보여준다)

export const useTrace = (id: string, live: boolean) =>
  useQuery({ queryKey: keys.trace(id), queryFn: () => opsApi.trace(id), retry: false, refetchInterval: live ? 2000 : false });

export const useOpsSummary = () =>
  useQuery({ queryKey: keys.opsSummary, queryFn: opsApi.summary, retry: false, refetchInterval: 5000 });
