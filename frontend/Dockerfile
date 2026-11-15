# 배포 환경의 웹 이미지: 프론트를 빌드해 Caddy가 서빙한다 (설정은 Caddyfile)
# 빌드: docker build -t ipo-web frontend/
# 시연 기능, 관리자 화면 스위치는 빌드할 때 정한다 (기본 둘 다 켬. 끄려면 --build-arg VITE_DEMO=false)

FROM node:22.23.5-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_DEMO=true
ARG VITE_ADMIN_UI=true
ENV VITE_DEMO=${VITE_DEMO} VITE_ADMIN_UI=${VITE_ADMIN_UI}
RUN npm run build

FROM caddy:2.11.9-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
