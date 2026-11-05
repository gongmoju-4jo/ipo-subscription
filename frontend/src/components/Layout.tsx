import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { LogoMark } from './Logo';

export function Layout() {
  const { session, logout } = useAuth();
  const navigate = useNavigate();
  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <NavLink to="/" className="brand">
            <LogoMark />
            공모주사조
          </NavLink>
          <nav className="nav">
            <NavLink to="/" end>홈</NavLink>
            <NavLink to="/ipos">공모주</NavLink>
            <NavLink to="/subscriptions">내 청약</NavLink>
            <NavLink to="/account">계좌</NavLink>
            <NavLink to="/system" className="ops-link">시스템 현황</NavLink>
            <NavLink to="/goals" className="ops-link">목표 달성</NavLink>
          </nav>
          <div className="user-chip">
            <span>{session?.loginId}님</span>
            <button
              className="btn btn-ghost btn-sm"
              onClick={() => {
                logout();
                navigate('/login');
              }}
            >
              로그아웃
            </button>
          </div>
        </div>
      </header>
      <main className="page">
        <Outlet />
      </main>
    </>
  );
}
