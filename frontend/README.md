# 공모주사조 웹 (frontend)

실제 백엔드(Gateway) API에 연결된 사용자 화면과, 처리 흐름과 정합성을 보여주는 운영 화면입니다.

## 실행

```bash
# 0. 백엔드 (저장소 루트)
./gradlew build -x test && docker-compose up -d --build

# 1. 프론트 (frontend/)
npm install
npm run dev          # http://localhost:5173
npm run ops          # (선택) 시연용 운영 조회 서버 http://127.0.0.1:8099
```

- 배포처럼 빌드해서 띄우기: `npm run build && npm run preview` (http://localhost:4173). nginx 없이 같은 프록시로 동작
- 배포 서버에 붙이기: `.env.example`을 `.env.local`로 복사해 `GATEWAY_URL`을 배포 주소(`https://{도메인}`)로 바꾼다. 배포 서버의 Prometheus는 외부에 열지 않으므로 `PROMETHEUS_URL`은 SSM 터널로 연 주소(`http://localhost:19090`, [포트 포워딩](../docs/PROJECT_SETUP.md#grafana-eureka-화면-포트-포워딩))를 넣는다

## 배포 (web 이미지)

배포 환경에서는 `Dockerfile`이 화면을 빌드해 Caddy 이미지에 넣고, 배포 워크플로가 ECR `ipo/web`에 올린다. 서버에서는 이 컨테이너(web)만 외부에 열린다 (80, 443)

- 라우팅은 `Caddyfile`
  - `/api/**`, 통합 Swagger 경로 → 게이트웨이
  - `/ops`, `/prom` → 404 (운영 조회 서버, Prometheus는 배포하지 않는다. 화면은 "확인 불가")
  - 나머지 → `dist/`. 없는 경로는 `index.html`을 줘서 `/accounts` 같은 화면 주소에서 새로고침해도 열린다
  - 빌드 파일(`/assets/*`)은 1년 캐시, 그 외는 매번 새로 확인 (`no-cache`)
- 인증서: 환경변수 `SITE_DOMAIN`의 인증서를 Let's Encrypt에서 자동으로 받는다. 비우면 `localhost`로 뜨고 Caddy 자체 인증서를 쓴다
- 시연 기능, 관리자 화면 스위치는 빌드할 때 정한다: `docker build --build-arg VITE_DEMO=false --build-arg VITE_ADMIN_UI=false ...` (배포는 기본값, 둘 다 켬)
- `ops-server/`, `.env`는 이미지에 넣지 않는다 (`.dockerignore`)
- 로컬에서 이미지 확인 (백엔드를 Docker로 띄운 상태)

```bash
docker build -t ipo-web frontend/
docker run --rm --network ipo-subscription_default -p 127.0.0.1:18443:443 ipo-web   # 네트워크 이름은 {프로젝트 폴더명}_default
# https://localhost:18443 (자체 인증서라 브라우저 경고가 정상), curl은 -k
```

## 구조

| 위치 | 역할 |
| --- | --- |
| `src/api/types.ts` | 백엔드 DTO와 같은 타입 |
| `src/api/client.ts` | 공통 호출 (JWT, 공통 응답 풀기, 오류, 최근 요청 기록) |
| `src/api/endpoints.ts` | 실제로 있는 백엔드 API만 |
| `src/api/queries.ts` | React Query 조회 훅 (처리 중이면 자동 재조회) |
| `src/api/ops.ts` | 시연용 운영 조회, Prometheus 조회 |
| `src/lib/status.ts` | 상태 코드 → 한국어 (증거금은 "인출") |
| `src/queue/` | 접속 대기열: 진입, 순번 조회, 이탈, 청약 가능 시간 (`useAdmission`), 대기 화면 (`QueueUi`) |
| `src/pages/` | 홈, 공모주(상세, 청약 화면), 청약, 계좌, 시스템 현황, 목표 달성 |
| `ops-server/server.mjs` | 시연 전용 운영 조회 서버 (읽기 전용) |

## 연결된 것과 아닌 것

| 기능 | 상태 |
| --- | --- |
| 가입, 로그인, 계좌 개설, 입금, 공모주 조회, 접속 대기열(진입, 순번 조회, 이탈), 청약, 상태 조회, 배정 결과 | 백엔드 API 연결 |
| 관리자 화면: 공모주 등록, 추첨 재현, 관리자 계정 생성 | 백엔드 API 연결. ADMIN 계정에만 보임 (권한 확인은 게이트웨이, 서비스). `VITE_ADMIN_UI=false`로 빌드하면 빠짐 |
| 청약 취소 | 백엔드 API 없음 → 버튼 비활성, "준비 중" 표시 |
| 원장 거래 내역, 청약 처리 흐름(Outbox, 처리 기록), Outbox 미발행, DLT, 정합성 검사 | 백엔드 API 없음 → 시연용 운영 조회 서버로만 표시. 서버가 없으면 "확인 불가" |
| 서비스 상태, 처리량, p95, Consumer 랙 | Prometheus 실제 지표 |
| 목표 달성의 측정값 | 측정 결과 파일 값 (출처 표시). 화면이 계산하지 않음 |

## 시연용 운영 조회 서버 (머지 전 팀 합의 필요)

- 정식 운영 조회 API가 없어서 시연 때만 DB와 Kafka를 직접 읽는다. 운영에 배포하지 않는다
- DB 세션은 읽기 전용, 127.0.0.1에만 열림, 토큰을 Gateway와 같은 방식(HS256)으로 검증하고 계좌, 청약은 본인 것만 돌려준다
- 저장소 루트 `.env`의 `DB_PASSWORD`, `JWT_SECRET`을 읽는다 (출력하지 않음)
- 시스템 전체 집계(`/ops/summary`)는 로그인한 누구나 볼 수 있다. 운영으로 옮긴다면 ADMIN만 보이도록 바꿔야 한다
