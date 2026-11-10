// 공모주사조 로고: 짙은 사각형 안에 올라가는 막대 3개와 상승 점 (청약 몰림을 받아내는 성장 그래프)
export function LogoMark({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" role="img" aria-label="공모주사조">
      <defs>
        <linearGradient id="lg-bg" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#1f2a3a" />
          <stop offset="1" stopColor="#111821" />
        </linearGradient>
        <linearGradient id="lg-bar" x1="0" y1="1" x2="0" y2="0">
          <stop offset="0" stopColor="#2f6fed" />
          <stop offset="1" stopColor="#6ea0ff" />
        </linearGradient>
      </defs>
      <rect x="0" y="0" width="32" height="32" rx="9" fill="url(#lg-bg)" />
      <rect x="7" y="17" width="4.2" height="8" rx="1.6" fill="#ffffff" opacity="0.35" />
      <rect x="13.9" y="13" width="4.2" height="12" rx="1.6" fill="#ffffff" opacity="0.6" />
      <rect x="20.8" y="9" width="4.2" height="16" rx="1.6" fill="url(#lg-bar)" />
      <circle cx="22.9" cy="5.6" r="1.7" fill="#6ea0ff" />
    </svg>
  );
}
