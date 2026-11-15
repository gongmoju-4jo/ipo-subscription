# 배포 환경의 웹 이미지: 프론트를 빌드해 Caddy가 서빙한다 (설정은 Caddyfile)
# 빌드: docker build -t ipo-web frontend/
# 시연 기능, 관리자 화면 스위치는 빌드할 때 정한다. 배포 기본값은 시연 기능 끔, 관리자 화면 켬
#   시연 기능(API 기록 창, 처리 상세, DLT, 설계 포인트, 시스템 현황, 성과 화면)은 운영 조회 서버, Prometheus를 부르거나 내부 설계를 보여 줘서 배포 화면에 넣지 않는다
#   끄면 해당 코드가 빌드 결과에서 빠진다. 로컬에서 시연 기능까지 넣어 보려면 --build-arg VITE_DEMO=true

FROM node:22.23.5-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_DEMO=false
ARG VITE_ADMIN_UI=true
ENV VITE_DEMO=${VITE_DEMO} VITE_ADMIN_UI=${VITE_ADMIN_UI}
RUN npm run build

FROM caddy:2.11.9-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
