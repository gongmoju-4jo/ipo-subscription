import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// 브라우저는 같은 주소(/api, /ops, /prom)만 부르고, 이 서버가 실제 주소로 넘긴다.
// 그래서 게이트웨이에 CORS 설정이 없어도 되고, nginx 없이 `npm run preview`로도 같은 방식으로 돈다.
// 운영 조회 서버는 127.0.0.1에만 열려서 localhost(IPv6 ::1로 풀릴 수 있음) 대신 127.0.0.1로 붙는다
// 주소는 frontend/.env(.local)의 GATEWAY_URL, OPS_URL, PROMETHEUS_URL로 바꾼다 (배포 서버로 붙일 때)
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxy = {
    '/api': { target: env.GATEWAY_URL || 'http://localhost:8080', changeOrigin: true },
    '/ops': { target: env.OPS_URL || 'http://127.0.0.1:8099', changeOrigin: true },
    '/prom': {
      target: env.PROMETHEUS_URL || 'http://localhost:9090',
      changeOrigin: true,
      rewrite: (p: string) => p.replace(/^\/prom/, ''),
    },
  };
  return {
    plugins: [react()],
    server: { port: 5173, proxy },
    preview: { port: 4173, proxy },
  };
});
