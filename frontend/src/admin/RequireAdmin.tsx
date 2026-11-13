import { Link } from 'react-router-dom';
import { useIsAdmin } from './flag';

// 관리자가 아니면 화면 대신 안내를 표시한다 (권한 확인은 Gateway가 하고, 이 화면은 메뉴 표시 보조)
export function RequireAdmin({ children }: { children: JSX.Element }) {
  const isAdmin = useIsAdmin();
  if (isAdmin) return children;
  return (
    <div className="notice gray">
      관리자만 사용 가능한 화면이에요. <Link to="/ipos" style={{ fontWeight: 700 }}>공모주 목록 →</Link>
    </div>
  );
}
