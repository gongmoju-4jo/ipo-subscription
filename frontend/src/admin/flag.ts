import { useAuth } from '../auth/AuthContext';

// 관리자 화면 스위치 (공모주 등록, 추첨 재현, 관리자 계정 생성).
// 끄기: 빌드할 때 VITE_ADMIN_UI=false. 완전히 삭제: src/admin/ 폴더, 그리고 ADMIN_UI, src/admin을 사용하는 줄 (grep -rn "admin/" src)
// 연결 위치는 4곳: main.tsx 라우트, IposPage 등록 버튼, IpoDetailPage 추첨 재현 카드, Layout 계정 영역의 관리자 계정 추가 링크
export const ADMIN_UI = import.meta.env.VITE_ADMIN_UI !== 'false';

// 관리자 메뉴 표시 여부. 권한 확인은 Gateway가 하고, 이 값은 화면 표시에만 사용한다
export function useIsAdmin(): boolean {
  const { session } = useAuth();
  return ADMIN_UI && session?.role === 'ADMIN';
}
