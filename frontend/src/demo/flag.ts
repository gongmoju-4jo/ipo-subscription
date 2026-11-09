// 시연 전용 기능 스위치 (API 기록 창, 처리 상세, DLT 내용, 설계 포인트, 운영 조회).
// 운영에서 끄기: 빌드할 때 VITE_DEMO=false. 완전히 지우기: src/demo/, ops-server/, 그리고 DEMO를 쓰는 줄 (grep DEMO)
export const DEMO = import.meta.env.VITE_DEMO !== 'false';
