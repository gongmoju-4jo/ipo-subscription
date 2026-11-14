import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { LogoMark } from './Logo';
import { ApiInspector } from '../demo/ApiInspector';
import { DEMO } from '../demo/flag';
import { useIsAdmin } from '../admin/flag';

export function Layout() {
  const { session, logout } = useAuth();
  const navigate = useNavigate();
  const isAdmin = useIsAdmin();
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
            {DEMO && <NavLink to="/system" className="ops-link">시스템 현황</NavLink>}
            {DEMO && <NavLink to="/goals" className="ops-link">성과와 로드맵</NavLink>}
          </nav>
          <div className="user-chip">
            {/* 관리자 기능은 사용자 메뉴와 섞지 않고 계정 영역에 둔다 */}
            {isAdmin && <NavLink to="/admin/users/new" className="admin-link">관리자 계정 추가</NavLink>}
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
      <ApiInspector />
    </>
  );
}
