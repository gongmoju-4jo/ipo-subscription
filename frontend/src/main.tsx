import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';
import { IposPage } from './pages/IposPage';
import { IpoDetailPage } from './pages/IpoDetailPage';
import { IpoSubscribePage } from './pages/IpoSubscribePage';
import { SubscriptionsPage } from './pages/SubscriptionsPage';
import { SubscriptionDetailPage } from './pages/SubscriptionDetailPage';
import { AccountPage } from './pages/AccountPage';
import { SystemPage } from './pages/SystemPage';
import { GoalsPage } from './pages/GoalsPage';
import { DEMO } from './demo/flag';
import { ADMIN_UI } from './admin/flag';
import { RequireAdmin } from './admin/RequireAdmin';
import { AdminIpoCreatePage } from './admin/AdminIpoCreatePage';
import { AdminUserCreatePage } from './admin/AdminUserCreatePage';
import './styles/global.css';

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 3000, refetchOnWindowFocus: true, retry: 1 } },
});

function RequireAuth({ children }: { children: JSX.Element }) {
  const { session } = useAuth();
  return session ? children : <Navigate to="/login" replace />;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route
              element={
                <RequireAuth>
                  <Layout />
                </RequireAuth>
              }
            >
              <Route index element={<DashboardPage />} />
              <Route path="ipos" element={<IposPage />} />
              <Route path="ipos/:ipoId" element={<IpoDetailPage />} />
              <Route path="ipos/:ipoId/subscribe" element={<IpoSubscribePage />} />
              <Route path="subscriptions" element={<SubscriptionsPage />} />
              <Route path="subscriptions/:subscriptionId" element={<SubscriptionDetailPage />} />
              <Route path="account" element={<AccountPage />} />
              {ADMIN_UI && <Route path="admin/ipos/new" element={<RequireAdmin><AdminIpoCreatePage /></RequireAdmin>} />}
              {ADMIN_UI && <Route path="admin/users/new" element={<RequireAdmin><AdminUserCreatePage /></RequireAdmin>} />}
              {DEMO && <Route path="system" element={<SystemPage />} />}
              {DEMO && <Route path="goals" element={<GoalsPage />} />}
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
);
