# 프로젝트 세팅 가이드

> 📌 표시는 꼭 읽어주세요.

## 목차

1. [빠른 시작](#1--빠른-시작)
2. [구조](#2-구조)
3. [실행](#3-실행)
4. [패키지 구조](#4--패키지-구조)
5. [common 모듈](#5-common-모듈)
6. [엔티티 규칙](#6-엔티티-규칙)
7. [Audit 필드와 삭제](#7-audit-필드와-삭제)
8. [예외 처리](#8-예외-처리)
9. [Swagger](#9--swagger)
10. [DB와 설정](#10-db와-설정)
11. [트러블슈팅](#11-트러블슈팅)

---

## 1. 📌 빠른 시작

```bash
git clone {레포 주소}
cd ipo-subscription
cp .env.example .env    # .env의 DB_PASSWORD, ACCOUNT_DB_PASSWORD, SUBSCRIPTION_DB_PASSWORD, ALLOCATION_DB_PASSWORD에 직접 정한 값을, JWT_SECRET에 팀 공용 키를 넣는다 (아래 "JWT 키")
docker-compose up -d postgres kafka kafka-init kafka-ui redis
```

1. IntelliJ로 **루트 폴더** 열기 → Gradle 탭 🔄
2. `EurekaServerApplication` 먼저 실행 → 나머지 앱 실행 (hotkey: cmd+8 / alt+8)
3. http://localhost:8761 에 실행한 앱이 모두 UP이면 끝

필요: **JDK 21**, Docker + Compose v2 이상, IntelliJ

> ✏️ docker-compose에는 PostgreSQL, Kafka, Kafka-UI, Redis(접속 대기열), 서비스 컨테이너 5개, 모니터링(Prometheus, Grafana)이 있습니다.

### 📌 Claude Code

- 루트 `CLAUDE.md`(팀 공용) 자동 적용 → 유비쿼터스 언어, 이 문서, GitHub 가이드 함께 읽음
- 📌 개인 설정은 `CLAUDE.local.md` (git ❌). 공용 `CLAUDE.md`에 개인 설정 ❌
- 📌 공용 `CLAUDE.md`, `docs/` 수정 시 팀 전원 Claude에 영향 → 수정 전 공유
- 용어, 상태값 변경 → `docs/UBIQUITOUS_LANGUAGE.md`부터 수정

---

## 2. 구조

Gradle 멀티프로젝트

```
ipo-subscription/
├── build.gradle            공통 버전, 의존성 (Boot 3.5.16, Cloud 2025.0.3, Java 21)
├── settings.gradle         모듈 목록
├── Dockerfile              서비스 공용
├── docker-compose.yml      로컬 인프라(PostgreSQL, Kafka, Kafka-UI, Redis), 서비스 컨테이너 5개, 모니터링(Prometheus, Grafana)
├── compose.perf.yml        부하 테스트용 자원 고정 ([부하 테스트 가이드](./LOADTEST.md)). 배포 환경도 같은 값을 쓴다
├── compose.prod.yml        배포 환경(EC2)용 (ECR 이미지, 게이트웨이 80번 공개, 자동 재시작)
├── deploy/                 서버 배포 스크립트, 배포 후 스모크 ([배포](#배포-aws))
├── loadtest/               부하 테스트 스크립트 (시드, k6 시나리오, 검증)
├── docker/postgres/        서비스별 스키마, DB 계정 생성 스크립트
├── common/                 공통 라이브러리 (실행 X)
├── eureka-server/
├── api-gateway/
├── account-service/        회원, 계좌, 증거금, 원장
├── subscription-service/   공모주, 청약
└── allocation-service/     배정
```

### 포트

| 앱 | 포트 |
| --- | --- |
| eureka / gateway | 8761 / 8080 |
| account / subscription / allocation | 8081 / 8082 / 8083 |
| PostgreSQL | 5432 |
| Kafka / Kafka-UI | 29092 / 8989 |
| Redis | 6379 (접속 대기열. 게이트웨이만 사용) |
| Prometheus / Grafana | 9090 / 3000 |
| gateway 관리 포트 | 18090 (헬스체크, 지표 수집 전용. IntelliJ로 실행하면 내 PC에 열리고, Docker에서는 열지 않음) |

### 게이트웨이 라우팅

API 명세 목차 기준 **초안**. 명세 확정 시 `api-gateway/.../application.yml`과 함께 수정

| 경로 | 서비스 |
| --- | --- |
| `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/accounts/**` | account |
| `/api/v1/ipos/**`, `/api/v1/subscriptions/**`, `/api/v1/admin/**` | subscription |
| `/api/v1/allocations/**` | allocation |

- 새 외부 경로 → `api-gateway/.../application.yml`에 추가
- `/internal/**`은 라우팅 X (Feign 전용, 외부 접근 불가)
- `/api/v1/queues/**`는 라우팅 X. 게이트웨이가 직접 응답한다 (접속 대기열, [아래](#접속-대기열))

### 📌 게이트웨이 인증

현재: 라우트 + 통합 Swagger + **JWT 인증 필터** (토큰 검증, 사용자 헤더 설정, 공개/관리자 경로, 401·403 공통 응답)

- 📌 **Gateway(8080)를 거치는 요청은 토큰이 필요**하다. 공개 경로만 예외: `POST /api/v1/users`, `POST /api/v1/auth/login`, Swagger(`GET /swagger-ui/**`, `/v3/api-docs/**`, `/*-service/v3/api-docs`). 공개 경로는 메서드와 경로가 정확히 맞아야 하고(끝 슬래시가 붙으면 공개가 아님), 토큰을 읽지 않는다
- 📌 JWT 검증: HS256 서명, 만료, `iss`=**`account-service`**(문자열 비교), `sub`, `role`. `exp`가 없는 토큰은 거절. 실패하면 401 (만료는 `TOKEN_EXPIRED`, 그 외는 `UNAUTHORIZED`)
- 📌 역할: **USER, ADMIN** (common `UserRole`과 같은 값). `/api/v1/admin/**`은 ADMIN만, 일반 회원은 403 `FORBIDDEN`
- 클라이언트가 보낸 `X-User-Id`, `X-User-Role`, `X-Request-Received-At`를 모두 제거한 뒤 Gateway가 다시 설정한다 (서비스, Audit이 이 헤더를 신뢰)
  - `X-User-Id` = 토큰의 `sub`, `X-User-Role` = 토큰의 `role` (토큰이 없는 공개 경로에는 붙지 않음)
  - `X-Request-Received-At` = Gateway 도착 시각 (UTC ISO-8601, 밀리초, 모든 요청). 청약 마감 판단 기준
- Gateway는 `common`을 쓰지 않아 401·403 오류 응답을 직접 공통 형식으로 만든다 (`details`는 없으면 생략)
- 컨테이너 헬스체크와 Prometheus 수집용으로 **관리 포트(18090)의 `/actuator/health`, `/actuator/prometheus`만** 토큰 없이 열려 있다 (`SecurityConfig`의 관리 체인). 8080의 `/actuator/**`와 관리 포트의 다른 엔드포인트는 계속 토큰이 필요하다 (`ManagementEndpointSecurityTest`가 확인)
- 서비스 포트(IntelliJ 실행)로 직접 호출하면 Gateway를 거치지 않아 토큰 없이 `X-User-Id` 헤더만으로 호출된다 (개발용. Docker는 서비스 포트 비공개)
- 📌 라우트 위치: `spring.cloud.gateway.server.webflux.routes` (Spring Cloud 2025.0 방식)
- 라우트, Swagger, 인증 규칙은 `GatewayRoutesTest`와 `gateway/security/` 테스트가 확인 → 변경 시 테스트도 수정

### 접속 대기열

청약이 몰릴 때 게이트웨이가 공모주별 줄을 세우고(Redis), 차례가 온 사람에게만 입장 허가를 준다. 코드는 `api-gateway/.../queue/`

| API | 설명 |
| --- | --- |
| `POST /api/v1/queues/{ipoId}/entries` | 대기열 진입. 새로 진입 201, 이미 줄에 있거나 입장 허가 상태면 200 (순번 유지) |
| `GET /api/v1/queues/{ipoId}/entries/me` | 순서 조회. 줄에도 없고 허가도 없으면 404 `RESOURCE_NOT_FOUND` (화면은 다시 진입) |

- 인증은 다른 API와 같이 JWT. 대기열 주인은 토큰의 `sub`
- 응답: `status`(`WAITING`, `ADMITTED`), `position`, `waitingCount`, `estimatedWaitSeconds`, `enteredAt`, `admissionExpiresAt`, `pollIntervalMillis`. 시각은 KST
- 화면은 `pollIntervalMillis`마다 순서를 조회한다. 간격 = max(1초, 대기 인원 ÷ `queue.poll-budget`)라서 대기자가 많아도 초당 조회 수가 예산을 넘지 않는다
- 조회가 받은 간격의 `queue.dropout-factor`배(3배) 동안 끊기면 입장 스케줄러가 줄에서 뺀다 (이탈). 진입, 조회할 때마다 기한이 늘어난다
- 입장 스케줄러가 1초마다 `queue.admit-rate`명(전체 공모주 합, 기본 433)에게 앞에서부터 허가를 준다. 허가는 `queue.admission-ttl`(5분) 동안 유효
- 공모주가 실제로 있는지, 청약 중인지는 확인하지 않는다 (접속이 몰리는 경로에 서비스 호출을 넣지 않음)
- Redis에 접속할 수 없으면 503 `SERVICE_UNAVAILABLE` (대기열 없이 통과시키지 않음)

---

## 3. 실행

> 평소 개발은 **IntelliJ**로 하고 DB, Kafka만 Docker로 띄웁니다. 최종 동작은 **전체 Docker 실행**으로 확인합니다. 두 방식을 동시에 실행하면 포트가 충돌합니다.

### IntelliJ

1. `docker-compose up -d postgres kafka kafka-init kafka-ui redis` (Kafka가 필요 없는 작업이면 `postgres`만. 게이트웨이를 띄우면 `redis`도 필요). `.env`가 없으면 [빠른 시작](#1--빠른-시작)의 `cp .env.example .env`부터
2. `⌘8`/`Alt+8` Services 창 → Eureka 실행 → 나머지 실행
3. http://localhost:8080/api/v1/unknown → 401 JSON(`UNAUTHORIZED`)이면 게이트웨이 정상 (토큰이 필요한 경로라서)

> 💡 전부 켤 필요는 없습니다. **Eureka + 내 서비스 + 내 서비스가 호출하는 서비스**만 실행하면 됩니다.

### Docker

> 서비스 5개(eureka-server, api-gateway, account, subscription, allocation)를 컨테이너로 띄워 전체를 확인하는 방식입니다. **개발 방식을 바꾸는 것이 아니라** 통합 확인, 부하 테스트, 배포 전 점검용입니다. 코드는 그대로이고 접속 주소는 compose가 환경변수로 넣어 줍니다.

```bash
./gradlew build -x test && docker-compose up -d --build   # 실행 (jar를 먼저 빌드해야 한다)
docker-compose ps                                          # 서비스 5개가 healthy인지 확인 (기동에 1~2분, 한 번 출력하고 끝나므로 반복해서 확인), kafka-init은 Exited (0)가 정상
docker-compose logs -f {서비스 이름}                        # 로그
docker-compose down                                        # 종료
docker-compose down -v                                     # 종료 + DB, Kafka 데이터 초기화
```

- 📌 처음 한 번: `cp .env.example .env` → `JWT_SECRET`에 팀 공용 키, DB 비밀번호(관리자 1개, 서비스별 3개), `GRAFANA_ADMIN_PASSWORD`(직접 정한 값, 기본값 없음). compose가 `.env`를 읽어 컨테이너에 환경변수로 전달한다 (컨테이너 안에는 `.env` 파일이 없다)
- ⚠️ 코드 수정 후엔 위 `실행` 명령 그대로 (`./gradlew build` 빠지면 예전 코드로 뜸). 한 서비스만 고쳤다면 `./gradlew build -x test && docker-compose up -d --build {서비스 이름}`
- 📌 서비스 컨테이너 중 내 PC에 열리는 포트는 `http://localhost:8080`(게이트웨이)과 `http://localhost:8761`(Eureka 대시보드) 둘뿐이다 (둘 다 내 PC에서만 접속되도록 127.0.0.1에 바인딩. PostgreSQL, Kafka, Kafka-UI, Redis는 별도로 열린다). 서비스 포트(8081~8083)는 열지 않는다 → 서비스 포트 직접 접속 ❌
  - 서비스는 토큰 없이 `X-User-Id` 헤더만으로 호출되므로 외부에 열면 안 된다. 서비스별 Swagger도 쓸 수 없고 통합 Swagger(`http://localhost:8080/swagger-ui/index.html`)를 쓴다
  - Eureka 등록 확인은 IntelliJ 때와 같이 `http://localhost:8761` 화면에서 한다
  - Eureka 포트가 이미 사용 중이거나 Windows 예약 포트라 기동되지 않으면 `.env`에서 `EUREKA_PORT`(기본 8761)를 바꾼다
- 기동 순서는 compose가 제어한다: postgres, kafka 준비 → 토픽 생성(`kafka-init`) → eureka-server → 서비스. 서비스가 모두 `healthy`여도 Gateway가 서비스를 찾는 데 최대 30초 걸려 처음 요청이 503일 수 있다
- ⚠️ **이름 없이 `docker-compose up`을 치면 서비스 컨테이너와 모니터링(Prometheus, Grafana)까지 전부 올라온다.** `GRAFANA_ADMIN_PASSWORD`가 비어 있으면 Grafana만 시작되지 않는다. jar를 빌드하지 않았으면 이미지 빌드에서 실패한다. 인프라만 필요하면 이름을 적는다 (`docker-compose up -d postgres kafka kafka-init kafka-ui redis`)
- ⚠️ **같은 서비스를 Docker와 IntelliJ에서 동시에 띄우지 않는다** (포트 충돌, Eureka 중복 등록). 특히 IntelliJ의 Eureka를 켠 채 Docker 전체를 띄우면 8761이 충돌한다. 일부 서비스만 IntelliJ로 띄워 컨테이너 Eureka에 붙이는 구성은 확인하지 않았고 지원하지 않는다
- 📌 **Docker 메모리 6GB 이상 권장** (Kafka 포함 시 2GB 부족. 모니터링 포함 전체 실행 시 컨테이너 사용량 합계가 부하 중 약 3.4GB였다)
  - Colima: `colima stop && colima start --cpus 4 --memory 6` / Docker Desktop: Settings → Resources → Memory

#### 필요한 서비스만 띄우기

> 전부 띄울 필요는 없습니다. 이름을 적은 서비스와 그 서비스가 의존하는 컨테이너만 올라옵니다.

```bash
./gradlew build -x test && docker-compose up -d --build api-gateway account-service   # 필요한 서비스만 실행
docker-compose stop {서비스 이름}                                                      # 한 서비스만 중지
docker-compose restart {서비스 이름}                                                   # 한 서비스만 재시작 (코드를 고쳤다면 위의 `--build` 명령)
```

| 서비스 | 같이 올라오는 것 |
| --- | --- |
| `eureka-server` | 없음 |
| `api-gateway` | `eureka-server`, `redis` |
| `account-service`, `subscription-service`, `allocation-service` | `postgres`, `kafka`, `kafka-init`(토픽을 만들고 종료), `eureka-server` |
| `prometheus` | 없음 (수집 대상이 떠 있지 않아도 올라온다) |
| `grafana` | `prometheus` |

- 서비스를 Gateway(`http://localhost:8080`)로 호출하려면 `api-gateway`와 호출할 서비스를 함께 적는다
- `kafka-ui`는 의존 대상이 아니라 따로 적어야 올라온다

#### 모니터링

- Prometheus: http://localhost:9090 (서비스 5개의 `/actuator/prometheus`를 5초마다 수집)
- Grafana: http://localhost:3000, 계정 `admin` / 비밀번호는 `.env`의 `GRAFANA_ADMIN_PASSWORD`. "측정 대시보드"가 자동으로 등록된다 (응답 시간, 처리량, Hikari, Tomcat 스레드, Kafka Producer 지연, CPU, 힙, GC)
- 둘 다 내 PC에서만 접속되도록 127.0.0.1에 바인딩한다. 포트가 겹치거나 Windows 예약 포트면 `.env`에서 `PROMETHEUS_PORT`, `GRAFANA_PORT`를 바꾼다
- 부하 테스트 절차는 [부하 테스트 가이드](./LOADTEST.md)

#### Slack 장애 알림

Grafana 알림 규칙이 30초마다 지표를 검사하고, 조건이 일정 시간 계속되면 Slack 채널로 장애 메시지를, 정상으로 돌아오면 복구 메시지를 보낸다.

| 규칙 | 조건 | 지속 시간 |
| --- | --- | --- |
| `ServiceDown` | Prometheus가 서비스 지표 수집에 실패 (`up < 1`, 컨테이너 종료, 응답 없음) | 1분 |
| `HttpServerErrorRateHigh` | 서비스별 5xx 응답 비율 5% 초과 (`/actuator/**` 제외) | 2분 |
| `HikariConnectionPending` | DB 커넥션을 기다리는 요청이 있음 (`hikaricp_connections_pending > 0`) | 1분 |
| `JvmHeapUsageHigh` | 힙 사용률 90% 초과 | 5분 |

- DB 커넥션 대기, 힙 기준값은 정식 부하 측정(S1 600건/초)에서 울리지 않는 값이다 (대기가 이어진 최장 시간 약 25초, 힙 최대 76%)
- 설정 파일은 `docker/grafana/provisioning/alerting/`: `rules.yml`(규칙), `contact-points.yml`(Slack 연락처, 메시지 형식), `policies.yml`(묶어 보내기, 반복 주기)
  - 파일로 넣은 규칙은 Grafana 화면에서 수정할 수 없다. 파일을 고친 뒤 `docker-compose restart grafana`
  - 연락처, 정책(메시지 형식 포함)은 재시작 후 약 1분 뒤에 적용된다. 그 사이에 나가는 알림은 이전 설정을 쓴다
- 처음 발생하면 30초 동안 같은 묶음을 모아 보내고, 묶음에 바뀐 것(새 알림, 복구)은 1분 간격으로 보낸다. 복구되지 않으면 1시간마다 다시 보낸다
- 장애 메시지에는 서비스, 내용, 시작 시각이, 복구 메시지에는 서비스, 복구된 장애, 복구 시각이 나온다. 제목 앞에 환경 이름이 붙는다 (예: `[local] 🚨 장애 발생 ServiceDown`)

`.env` (모두 선택, 기본값은 주석 처리된 채로 둔다)

| 키 | 뜻 | 로컬 | 배포 환경 |
| --- | --- | --- | --- |
| `SLACK_WEBHOOK_URL` | Slack Incoming Webhook URL (비밀값, 커밋 ❌) | 비워 둠. 알림을 확인할 때만 **개인 테스트 채널** URL | 장애대응 채널 URL (배포 환경 비밀값에만) |
| `ALERT_ENV` | 메시지 제목 앞 환경 이름 | 기본값 `local` | `prod` |
| `GF_SERVER_ROOT_URL` | 메시지 안 Grafana 링크 주소 | 기본값 `http://localhost:{GRAFANA_PORT}` | 실제 접속 주소 |

- 📌 장애대응 채널 URL은 로컬 `.env`에 넣지 않는다. 넣으면 로컬에서 서비스를 껐다 켤 때마다 장애대응 채널에 알림이 간다
- `SLACK_WEBHOOK_URL`이 비어 있으면 Grafana는 그대로 시작하고 알림만 나가지 않는다 (로그: `SLACK_WEBHOOK_URL이 비어 있어 Slack 장애 알림이 나가지 않습니다.`)
- `.env`를 바꾼 뒤에는 `docker-compose up -d grafana` (재시작만으로는 환경변수가 바뀌지 않는다)
- 배포 환경에서 부하 테스트를 할 때는 Grafana → Alerting → Silences에서 알림을 일시 중지한다
- Grafana가 내려가 있으면 알림도 나가지 않는다

동작 확인 (개인 테스트 채널 URL을 넣은 상태)

1. `docker-compose up -d grafana` 후 로그에 `finished to provision alerting`이 있는지 본다 (`docker-compose logs grafana`)
2. `docker-compose stop allocation-service` → 약 1분 30초 뒤 `[local] 🚨 장애 발생 ServiceDown`
3. `docker-compose start allocation-service` → 서비스가 뜨고 1~2분 안에 `[local] ✅ 복구 ServiceDown`

#### IntelliJ 실행과 Docker 실행의 차이

| | IntelliJ (개발) | Docker (확인) |
|---|---|---|
| 서비스 실행 | IntelliJ에서 앱 실행 | 컨테이너 |
| DB, Kafka, Eureka 주소 | `localhost` (기본값) | `postgres`, `kafka:29092`, `eureka-server` (compose가 환경변수로 전달) |
| 설정 값(비밀번호, 키) | 앱이 `.env`를 직접 읽음 | compose가 `.env`를 읽어 환경변수로 전달 |
| 서비스 포트(8081~8083) | 내 PC에 열림 (토큰 없이 직접 호출 가능) | 열지 않음, Gateway 경유만 |
| 코드 반영 | 앱 재시작 | jar 빌드 후 이미지 재빌드 |

- DB, Kafka는 두 방식 모두 같은 컨테이너(`ipo-postgres`, `ipo-kafka`)를 써서 **데이터가 이어진다**
- 컨테이너로 확인하는 것이 필요한 경우: 토큰 없는 직접 호출로는 못 잡는 Gateway 인증, `X-Request-Received-At`(청약 마감 판단 기준) 흐름, 환경변수 전달 누락
- 처음부터 확인하는 순서: 통합 Swagger에서 회원가입 → 로그인 → Authorize에 토큰 입력 → 계좌 개설 → 입금 → 공모주 조회

### Kafka

- 토픽, 파티션 키, 페이로드 → **이벤트 명세 문서** 기준 (코드보다 문서 먼저 수정)
- 실패 메시지: `{토픽}.DLT`
- 접속 주소 기본값: `localhost:29092` (서비스 `application.yml`의 `KAFKA_BOOTSTRAP_SERVERS`)
- 실행: `docker-compose up -d kafka kafka-init kafka-ui`
- Kafka-UI: http://localhost:8989 (토픽, 컨슈머 그룹 확인)
- 토픽 생성: 토픽 **자동 생성은 꺼져 있고**, `kafka-init`이 Kafka 기동 후 한 번 만들고 종료한다 (`docker/kafka/create-topics.sh`, 종료 상태가 정상)
  - 토픽 목록, 파티션 수는 `docker/kafka/create-topics.sh` **한 곳에서 관리**한다
  - 토픽 10개 + `{토픽}.DLT` 10개 = 20개: `subscription.requested`, `margin.reserve-requested`, `margin.reserved`, `margin.reserve-failed`, `margin.void-requested`, `margin.voided`, `ipo.closed`, `allocation.result`, `margin.settled`, `allocation.finished`
  - 파티션 수 기본 6 (`.env`의 `KAFKA_TOPIC_PARTITIONS`로 바꿀 수 있음), 복제 1 (단일 브로커)
  - 📌 파티션 수는 계좌, 청약, 배정 서비스의 Consumer 수(`application.yml`의 `spring.kafka.listener.concurrency`, 6)와 같게 둔다. 바꾸면 청약 서비스 테스트의 `KafkaTestSupport.PARTITIONS`도 같이 바꾼다
    - Consumer가 파티션보다 많으면 남는 Consumer는 일하지 않고, 적으면 동시 처리 수가 Consumer 수에 묶인다
    - 같은 메시지 키(사람, 계좌, 공모주)는 늘 같은 파티션으로 가서 키 단위 순서는 파티션 수와 관계없이 지켜진다
  - 이미 있는 토픽은 건너뛴다 (여러 번 실행해도 됨). 파티션 수를 바꿔도 기존 토픽에는 적용되지 않는다
    - ⚠️ 파티션 수가 다른 기존 토픽이 있으면 Kafka 데이터를 지우고 다시 만든다. Kafka에 쌓인 메시지가 지워진다 (DB는 유지)
      ```bash
      docker-compose stop kafka && docker-compose rm -f kafka
      docker volume rm ipo-subscription_kafka-data   # 이름은 {프로젝트 폴더명}_kafka-data, `docker volume ls`로 확인
      docker-compose up -d kafka kafka-init          # 서비스까지 띄울 때는 평소 실행 명령
      ```
    - 토픽 파티션 수 확인: `docker exec ipo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --describe`의 `PartitionCount`
    - 실행 중인 토픽을 `kafka-topics.sh --alter --partitions`로 늘리지 않는다. 늘린 순간부터 같은 키가 다른 파티션으로 가서 순서가 잠깐 뒤집힐 수 있고, 실패 메시지는 원래 토픽과 같은 파티션 번호의 `{토픽}.DLT`로 보내므로 DLT도 같은 수여야 한다. 파티션은 줄일 수 없다
  - 새 토픽 → 이벤트 명세 문서 먼저 수정한 뒤 스크립트의 `TOPICS`에 추가
- 호스트 포트 변경: `.env`의 `KAFKA_PORT`(기본 29092), `KAFKA_UI_PORT`(기본 8989)
  - `KAFKA_PORT`를 바꾸면 서비스 실행 시 `KAFKA_BOOTSTRAP_SERVERS`도 `localhost:{바꾼 포트}`로 맞춘다

### CI (GitHub Actions)

`develop`을 대상으로 PR을 올리거나 `develop`에 머지되면 자동으로 `./gradlew build`(전체 모듈 빌드 + 테스트)가 실행된다. 설정은 `.github/workflows/ci.yml`. `.env`와 비밀 환경변수 없이 통과해야 한다.

- 결과 확인: PR 화면 아래 Checks의 `build`
  - 통과: 초록 체크 / 실패: 빨간 ❌ → Details에서 실패한 step의 로그 확인
  - PR 본문 맨 끝 `## CI 결과` 표에도 진행 중, 통과, 실패가 표시된다. 이 구간(`<!-- ci-status:start -->` ~ `<!-- ci-status:end -->`)은 CI가 자동으로 갱신하니 지우거나 고치지 않는다
- 실패했을 때: 실행 페이지 맨 아래 **Artifacts**의 `test-reports`(7일 보관)를 받아 압축을 풀고 실패한 모듈의 `{모듈}/build/reports/tests/test/index.html`(예: `account-service/build/reports/tests/test/index.html`)을 열면 로컬과 같은 테스트 리포트가 보인다
- 로컬에서 먼저 확인: `./gradlew build` (Testcontainers 테스트가 있어 Docker를 켜 둔다)
- 새로 push하면 같은 PR의 이전 실행은 취소되고 새로 실행된다
- 포크에서 올린 PR은 본문 표시가 갱신되지 않는다 (빌드, 테스트는 실행됨)
- 머지 차단: `build`를 필수 체크로 지정하기 전에는 CI가 실패해도 머지 버튼은 눌린다. 지정되면 통과해야 머지할 수 있다
- `develop → main` PR에 대한 CI는 아직 없다 (첫 릴리스 때 추가)
- develop에는 `build` 통과가 머지 조건으로 걸려 있다 (Ruleset). 실패하면 머지할 수 없다

### 배포 (AWS)

EC2 한 대(서울 리전)에 PostgreSQL, Kafka, 서비스 5개, 모니터링을 모두 컨테이너로 띄운다. 로컬 Docker 실행과 같은 `docker-compose.yml`에 `compose.perf.yml`(자원 상한, JVM 힙), `compose.prod.yml`(배포용)을 겹쳐 쓴다.

#### 흐름

```
develop 머지 → CI(build) 성공 → Deploy 워크플로
  → jar 빌드 → 이미지 5개 빌드, ECR 푸시 (태그 = 커밋 SHA)
  → SSM Run Command로 서버에서 deploy/deploy.sh 실행
      (레포를 그 커밋으로 맞춤 → Parameter Store 값으로 .env 생성 → 이미지 받기 → up -d → 서비스 5개 healthy 대기
       → 서버 안에서 스모크: deploy/smoke.sh로 스모크 전용 계정 로그인 → 계좌 → 입금)
  → 공개 주소 확인 (Swagger 200, 토큰 없는 API 401. 자격 증명은 보내지 않음)
```

- 비밀번호가 담긴 스모크는 서버 안에서 게이트웨이(`127.0.0.1:80`)로 보낸다. 배포 주소가 HTTP라 비밀번호를 인터넷으로 보내지 않기 위해서다. 서버 밖과는 SSM의 암호화된 채널로만 오간다

- 설정: `.github/workflows/deploy.yml`. CI가 실패한 커밋, PR에서 돈 CI로는 배포하지 않는다
- 배포는 한 번에 하나만 실행된다. 머지가 겹치면 앞 배포가 끝난 뒤 다음 배포가 실행된다
- 자동 배포는 CI가 끝난 커밋이 아직 develop의 최신일 때만 한다. 그사이 새 커밋이 머지됐으면 건너뛰고(`gate`에 "건너뜀" 표시), 최신 커밋의 CI가 끝나면 그 커밋을 배포한다
- 워크플로의 외부 액션은 커밋 SHA로 고정했다. 버전을 올릴 때는 해당 릴리스의 커밋 SHA로 바꾸고 주석의 버전도 같이 고친다
- 배포 중에는 서비스가 1~2분 다시 뜬다. 첫 요청이 503일 수 있다 (Eureka 등록 대기, 최대 30초)
- AWS 권한은 GitHub OIDC로 받는다. GitHub에 AWS 액세스 키를 두지 않는다
- 서버 SSH(22번)는 열지 않는다. 서버 접속은 AWS 콘솔 → EC2 → 인스턴스 → 연결 → Session Manager

#### 접속 주소

- 서비스: `http://{고정 IP}/` (게이트웨이만 80번으로 공개). 통합 Swagger는 `http://{고정 IP}/swagger-ui/index.html`
- 📌 HTTPS가 아니라 로그인 비밀번호와 토큰이 암호화되지 않고 오간다. **시연용 테스트 계정만 쓴다**
- 서비스 포트(8081~8083), Eureka, PostgreSQL, Kafka, Prometheus, Grafana는 외부에 열지 않는다 (서비스 포트를 열면 `X-User-Id` 헤더만으로 게이트웨이 인증을 우회할 수 있다)

#### 수동 실행, 롤백

- GitHub → Actions → Deploy → **Run workflow**. `sha`를 비우면 선택한 브랜치의 최신 커밋, 넣으면 그 커밋을 배포한다
- develop에 머지되어 CI가 성공한 커밋만 배포할 수 있다. 작업 브랜치의 커밋을 넣으면 AWS에 접근하기 전에 멈춘다
- 롤백은 이전 커밋 SHA를 넣어 실행한다. ECR에 이미지가 있으면 빌드 없이 바로 배포한다 (ECR에는 서비스마다 최근 이미지 5개만 남고, 그보다 오래된 커밋은 다시 빌드한다)
- 서버에는 healthy와 스모크까지 통과한 마지막 배포 SHA가 `/opt/ipo/.last-tag`, 그 전 SHA가 `/opt/ipo/.prev-tag`에 남는다
- DB 스키마는 되돌리지 않는다 (`ddl-auto: update`는 컬럼 추가만 반영). 스키마를 바꾸는 변경은 시연 직전에 넣지 않는다

#### 비밀값

- AWS Systems Manager Parameter Store `/ipo/prod/` (SecureString): `DB_PASSWORD`, `ACCOUNT_DB_PASSWORD`, `SUBSCRIPTION_DB_PASSWORD`, `ALLOCATION_DB_PASSWORD`, `JWT_SECRET`, `GRAFANA_ADMIN_PASSWORD`, `SLACK_WEBHOOK_URL`(선택), `SMOKE_LOGIN_ID`, `SMOKE_PASSWORD`
- `SMOKE_LOGIN_ID`(영문 소문자, 숫자 4~20자), `SMOKE_PASSWORD`(8~20자, 작은따옴표 `'` 불가)는 배포 후 스모크 전용 계정이다. 형식이 틀리면 서비스를 바꾸기 전에 배포가 멈춘다. 이 계정 하나를 계속 쓴다: 처음 배포 때 가입하고 계좌를 하나 연 뒤로는 로그인과 입금(1,000원)만 해서 배포마다 회원이 늘지 않는다. 시연에는 쓰지 않는다 (배포마다 잔고가 늘어난다). `.env`에는 넣지 않는다
- 배포할 때마다 서버의 `/opt/ipo/.env`(권한 600)를 다시 만든다. 서버의 `.env`를 손으로 고치지 않는다 (다음 배포 때 덮어쓴다)
- ⚠️ DB 비밀번호 4개는 첫 배포 뒤 바꾸지 않는다. PostgreSQL 볼륨이 처음 만들어질 때 한 번만 계정을 만들어서, 바꾸면 서비스가 DB에 접속하지 못한다
- ⚠️ `GRAFANA_ADMIN_PASSWORD`도 Grafana가 처음 뜰 때 한 번만 쓴다. 바꾸려면 Grafana 화면에서 바꾸고 파라미터도 같은 값으로 맞춘다
- 로컬 `.env`와 운영 값은 서로 관계없다. 로컬은 지금 값을 그대로 쓴다

#### 저장소 변수 (Settings → Secrets and variables → Actions → Variables)

| 이름 | 값 |
| --- | --- |
| `AWS_REGION` | `ap-northeast-2` |
| `AWS_DEPLOY_ROLE_ARN` | 배포용 IAM 역할 ARN |
| `ECR_REGISTRY` | `{계정 ID}.dkr.ecr.ap-northeast-2.amazonaws.com` |
| `EC2_INSTANCE_ID` | 배포 서버 인스턴스 ID |
| `DEPLOY_BASE_URL` | `http://{고정 IP}` (공개 주소 확인이 사용) |

- 인스턴스를 새로 만들면 `EC2_INSTANCE_ID`와 배포 역할의 SSM 권한 대상 인스턴스를 같이 바꾼다

#### 서버에서 직접 확인

Session Manager 터미널은 `sh`로 열린다. `sudo -i`로 root가 된 뒤 확인한다 (명령은 한 줄씩 붙여 넣는다. 여러 줄을 한꺼번에 붙이면 줄바꿈이 빠질 수 있다)

```bash
cd /opt/ipo
docker compose -f docker-compose.yml -f compose.perf.yml -f compose.prod.yml ps
docker compose -f docker-compose.yml -f compose.perf.yml -f compose.prod.yml logs --tail 100 {서비스 이름}
```

- ⚠️ `down -v`는 쓰지 않는다. DB, Kafka 데이터가 지워진다
- ⚠️ 서버의 `/opt/ipo` 파일을 직접 고치지 않는다. 배포 스크립트가 레포를 배포 커밋으로 바꿀 때 실패한다

#### Grafana, Eureka 화면 (포트 포워딩)

Grafana, Eureka, Prometheus는 외부에 열지 않는다. 팀원은 본인 IAM 사용자로 AWS에 로그인한 뒤 SSM 터널을 열어 내 PC 주소(`localhost`)로 본다.

1. 처음 한 번 설치 (AWS CLI 2.32.0 이상, Session Manager 플러그인)

| OS | AWS CLI | Session Manager 플러그인 |
| --- | --- | --- |
| Windows | `msiexec.exe /i https://awscli.amazonaws.com/AWSCLIV2.msi` (관리자 권한) | [설치 파일](https://s3.amazonaws.com/session-manager-downloads/plugin/latest/windows/SessionManagerPluginSetup.exe) 실행 (관리자 권한) |
| macOS | `curl "https://awscli.amazonaws.com/AWSCLIV2.pkg" -o "AWSCLIV2.pkg" && sudo installer -pkg AWSCLIV2.pkg -target /` | Apple 칩: `curl "https://s3.amazonaws.com/session-manager-downloads/plugin/latest/mac_arm64/session-manager-plugin.pkg" -o "session-manager-plugin.pkg"` (Intel은 경로의 `mac_arm64`를 `mac`으로)<br>`sudo installer -pkg session-manager-plugin.pkg -target /`<br>`sudo ln -s /usr/local/sessionmanagerplugin/bin/session-manager-plugin /usr/local/bin/session-manager-plugin` |

   - 새 터미널에서 `aws --version`(2.32.0 이상), `session-manager-plugin`(설치 확인 메시지)으로 확인한다
   - Windows는 설치 후 터미널을 다시 열어야 명령이 잡힌다

2. 로그인: `aws login --region ap-northeast-2` → 브라우저에서 본인 IAM 사용자로 로그인 (MFA 포함). 최대 12시간 유지되고, 끝나면 다시 `aws login`
   - 액세스 키를 만들지 않는다. `aws configure`도 쓰지 않는다
   - IAM 사용자에게 `AdministratorAccess`가 없으면 `SignInLocalDevelopmentAccess` 정책이 있어야 `aws login`이 된다
3. 터널 열기 (창을 열어 둔 동안만 연결된다)

```bash
aws ssm start-session --region ap-northeast-2 --target {인스턴스 ID} --document-name AWS-StartPortForwardingSession --parameters portNumber="3000",localPortNumber="13000"
```

- `{인스턴스 ID}`: 저장소 변수 `EC2_INSTANCE_ID` 값 (AWS 콘솔 → EC2 → 인스턴스의 `i-`로 시작하는 ID와 같다)
- Windows PowerShell, macOS 터미널 모두 위 명령 그대로 쓴다. 서버가 중지돼 있으면 연결되지 않는다

4. 브라우저로 열기

| 화면 | 터널의 `portNumber` → `localPortNumber` | 주소 | 로그인 |
| --- | --- | --- | --- |
| Grafana | `3000` → `13000` | `http://localhost:13000` | `admin` / Parameter Store의 `GRAFANA_ADMIN_PASSWORD` (콘솔 → Systems Manager → Parameter Store → 값 표시) |
| Eureka | `8761` → `18761` | `http://localhost:18761` | 없음 |
| Prometheus | `9090` → `19090` | `http://localhost:19090` | 없음 |

- 화면마다 터널 창을 하나씩 연다 (명령의 두 숫자만 바꾼다)
- 로컬 포트를 `13000`처럼 바꾸는 이유: 내 PC에서 로컬 Docker의 Grafana(3000) 등이 떠 있어도 겹치지 않게
- 연결이 안 되면: `aws login`이 만료됐는지(`aws sts get-caller-identity`), 서버가 실행 중인지, 플러그인이 설치됐는지(`session-manager-plugin`) 순서로 확인한다

#### 비용, 중지

- 쓰지 않을 때는 EC2를 **중지**한다 (콘솔 → 인스턴스 상태 → 인스턴스 중지). **종료**는 서버와 디스크를 지운다
- 중지 중에도 디스크와 고정 IP 요금은 나온다. 다시 시작하면 컨테이너는 `restart: unless-stopped`로 자동으로 뜬다
- 중지 중에 develop에 머지되면 Deploy가 실패한다. 서버를 켠 뒤 Run workflow로 다시 배포한다
- 배포 환경에서는 부하 테스트를 하지 않는다 (시연 데이터, 장애 알림이 섞인다)

#### 배포 브랜치를 main으로 바꿀 때

- `.github/workflows/deploy.yml`의 `branches: [develop]`을 `[main]`으로 바꾸고, `.github/workflows/ci.yml`의 `push` 대상에도 `main`을 추가한다 (CI가 성공해야 배포되므로)
- AWS 쪽 권한은 `main`, `develop` 모두 허용되어 있어 바꿀 것이 없다

---

## 4. 📌 패키지 구조

**계층형 4계층** (발제 필수). 서비스 3개 모두 같은 구조. 계층별 역할은 각 폴더 `package-info.java` 참고

```
subscription-service/.../com/gongmoju/subscription/
├── global/             계층 공통: 도메인 에러 코드(`exception/`), Swagger 어노테이션(`swagger/`)
├── presentation/       Controller, 요청/응답 DTO
├── application/        유스케이스 흐름, 트랜잭션 경계
├── domain/             엔티티, 값 객체, 도메인 서비스, Repository 인터페이스
└── infrastructure/     Repository 구현(JPA), Kafka Producer/Consumer, Feign Client
```

| 계층 | 의존 가능 | ❌ |
| --- | --- | --- |
| presentation | application | 엔티티 그대로 반환, Repository 직접 호출 |
| application | domain | 비즈니스 규칙 직접 구현 (→ domain 메서드로) |
| domain | 없음 (자바 + JPA 어노테이션) | 스프링 웹, Kafka, Feign 의존 |
| infrastructure | domain (인터페이스 구현) | 비즈니스 규칙 |

- 의존 방향: presentation → application → domain ← infrastructure
- 계층 안 하위 폴더(도메인별, 기능별)는 담당자가 결정
- Feign, Kafka 메시지 DTO는 `infrastructure/`에 따로 (API DTO와 분리, 필요한 필드만)

---

## 5. common 모듈

전 서비스 공통 약속(응답 형식, 예외 처리, Audit, Feign 설정, Outbox, 처리 기록) 라이브러리

| 파일 | 내용 |
| --- | --- |
| `entity/BaseEntity` | Audit 필드 4개 (`created_at`, `created_by`, `updated_at`, `updated_by`) |
| `audit/RequestAuditorAware` | `*_by` 값 결정 (요청자 ID 또는 `SYSTEM:{서비스 이름}`) |
| `auth/UserRole` | 역할 USER, ADMIN |
| `exception/ErrorCode` | 에러 코드 인터페이스 |
| `exception/CommonErrorCode` | 공통 에러 코드 (API 명세 1.6) |
| `exception/BusinessException` | 비즈니스 예외 (message, field 지정 가능) |
| `exception/GlobalExceptionHandler` | 전역 예외 처리 → `ApiResponse` 실패 응답 |
| `response/ApiResponse`, `ApiError`, `ApiErrorDetail` | 공통 성공, 실패 응답 |
| `response/PageResponse` | 공통 목록 응답 |
| `swagger/` | 오류 응답 예시 자동 생성 (`@ErrorCodeExamples`, `ErrorCodeOperationCustomizer`) |
| `feign/` | 헤더 전달, 에러 디코더, 재시도, Feign 예외 처리 (Feign 있는 서비스만) |
| `outbox/` | Outbox 엔티티, 저장소, 발행기, 설정(`outbox.publisher.*`), 자동 설정 (Kafka, JPA 있는 서비스만) |
| `event/` | 처리한 이벤트 기록(중복 수신 방지): 엔티티, 포트(`ProcessedEventRepository`), 구현, 자동 설정 (JPA 있는 서비스만) |
| `config/` | 위 기능 + Swagger 공통 설정 자동 등록 (AutoConfiguration). `outbox/`, `event/`의 자동 설정은 각 폴더에 있음 |

> 💡 서비스는 자기 패키지(`com.gongmoju.{서비스}`)만 스캔하므로 common 설정은 `AutoConfiguration.imports`로 자동 등록됩니다. 서비스에 `JpaAuditingConfig`, `SwaggerConfig`를 따로 만들지 않습니다.
>
> 💡 `Outbox`, `ProcessedEvent` 엔티티와 저장소도 자동 설정이 서비스의 JPA 스캔 대상에 더합니다. 서비스에 `@EnableJpaRepositories`, `@EntityScan`을 직접 붙이면 기본 스캔이 꺼져 등록이 빠질 수 있으니 붙이지 않습니다.

### common에 넣는 기준

| common ✅ (도메인 모름) | 서비스 ❌ (도메인 아는 경우)                       |
| --- |-----------------------------------------|
| 응답 형식, 공통 에러 코드, 전역 예외 처리 | 엔티티, Repository, 비즈니스 로직                |
| Feign 공통 설정 (헤더 전달, 에러 디코더, 재시도) | `@FeignClient` 인터페이스, Feign DTO         |
| `BaseEntity`, JPA Auditing, Swagger 공통 설정 | 도메인 에러 enum (`{서비스}ErrorCode`)          |
| Outbox, 처리 기록 (이벤트를 저장, 발행만 하고 도메인 값은 모름) | Kafka 이벤트 DTO (서비스마다 따로, 계약은 이벤트 명세 문서) |
| | 이벤트별 Outbox 기록 클래스 (`OutboxMarginEventPublisher` 등) |

> ⚠️ common을 바꾸면 전 서비스가 영향을 받습니다. **도메인을 모르는 공통 약속**인지 확인하고 팀에 먼저 공유합니다.

### 응답 형식

```json
// 성공: ApiResponse.success(data) 또는 ApiResponse.success(data, "메시지")
{ "success": true, "data": { "id": "8b2d..." }, "message": "요청이 성공했습니다." }

// 목록: ApiResponse.success(PageResponse.from(page), "목록을 조회했습니다.")
{ "success": true,
  "data": { "items": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 },
  "message": "목록을 조회했습니다." }

// 실패: 예외를 던지면 GlobalExceptionHandler가 자동 변환 (details는 없으면 생략)
{ "success": false,
  "error": { "code": "RESOURCE_NOT_FOUND", "message": "존재하지 않는 계좌입니다.",
             "details": [{ "field": "accountId", "reason": "존재하지 않는 계좌입니다." }] } }
```

- 반환 데이터 없음 → `ApiResponse.success(null)` → `data` 필드 빠짐
- Feign 응답도 `ApiResponse<XxxResponse>`로 받아서 `.data()` 사용

### 목록 조회

- 컨트롤러 파라미터 `@ParameterObject Pageable pageable` → `?page=0&size=20&sort=createdAt,desc`
- 기본값 자동 적용: `page=0`, `size=20` (최대 100), 정렬 미지정 시 `createdAt DESC`
- `Page` 그대로 반환 ❌ → `PageResponse.from(page)` 또는 `PageResponse.from(entityPage, XxxResponse::from)`
- 정렬 가능 필드는 API별로 제한 (API 명세 1.4). 없는 필드 → 400 `VALIDATION_ERROR`

### 사용

서비스 `build.gradle` (서비스 3개는 추가 완료)

```groovy
implementation project(':common')
```

### `compileOnly`

```groovy
// common/build.gradle
compileOnly 'org.springframework.boot:spring-boot-starter-web'        // 예외 처리, 자동 설정, AuditorAware
compileOnly 'org.springframework.boot:spring-boot-starter-data-jpa'   // BaseEntity, JPA Auditing, PageResponse
compileOnly 'org.springframework.cloud:spring-cloud-starter-openfeign' // feign/
compileOnly 'org.springframework.kafka:spring-kafka'                  // outbox/ (발행기)
compileOnly 'io.swagger.core.v3:swagger-annotations-jakarta:2.2.22'   // @Schema
```

> 💡 `compileOnly`: common은 빌드할 때만 라이브러리를 참고하고, 서비스에 넘겨주지 않습니다.
> - 라이브러리는 **서비스가 직접** `build.gradle`에 추가해야 동작합니다.
> - 예) openfeign이 없는 서비스는 common Feign 설정도 붙지 않습니다. 필요하면 `openfeign` 의존성 + `@EnableFeignClients`만 추가합니다.
> - 예) Outbox는 `spring-kafka`와 JPA가 모두 있어야, 처리 기록은 JPA만 있으면 붙습니다. Kafka 없이 JPA만 쓰는 서비스에도 `processed_event` 표가 만들어질 수 있습니다.

- common에 라이브러리 추가 → common엔 `compileOnly` + 서비스엔 `implementation`
  - 서비스쪽 누락 시 `ClassNotFoundException` → 서비스 `build.gradle`에 의존성 추가

---

## 6. 엔티티 규칙

> ✏️ 테이블, 컬럼은 **ERD 문서**가 기준입니다. 엔티티를 바꾸면 ERD도 같이 고치고, 다른 서비스가 참조하는 컬럼(이벤트 필드 포함)은 담당자에게 먼저 공유합니다.

| 규칙 | 비고 |
| --- | --- |
| `BaseEntity` 상속 | Audit 필드 자동. 예외(상속 ❌): `ledger_entry`(원장, 수정 없음), `outbox`, `processed_event`(이벤트 인프라 기록, 자체 시각 컬럼) |
| 📌 테이블 이름: 접두사 없음, snake_case 단수 (`@Table(name = "subscription")`) | 서비스별 스키마라 이름 충돌 없음. 회원만 `users` (`user`는 PostgreSQL 예약어) |
| PK: UUID (`@GeneratedValue(strategy = UUID)` + `@Column(name = "subscription_id")`) | API 명세 ID 형식과 동일 |
| 다른 서비스 엔티티 참조 ❌ → `UUID`만 | 다른 서비스 데이터는 이벤트 또는 Feign |
| 같은 서비스 안은 `@ManyToOne(fetch = LAZY)` | 예: Margin → Account |
| `@OneToOne` ❌ → `@ManyToOne` | |
| `@Enumerated(EnumType.STRING)` | 상태값은 상태 전이 문서 기준 |
| 금액 `long` (원 단위), DB `BIGINT` | 원화 정수만이라 오차 없음. 곱셈은 `Math.multiplyExact` (오버플로 시 예외) |
| 📌 시각 `Instant`, DB `TIMESTAMPTZ` | 서버, DB 시간대 설정과 관계없이 같은 시각. 응답 DTO에서만 KST(`+09:00`)로 변환. `LocalDateTime` ❌ |
| `@NoArgsConstructor(access = PROTECTED)` + `@Builder` | |
| setter ❌ → 의미 있는 메서드 | `subscription.confirm()`, `margin.voidPending()` |
| 조건부 유니크 → `schema.sql` | 예: 이중청약 방지 `UNIQUE(ipo_id, user_id) WHERE status NOT IN ('REJECTED','CANCELLED')` → [10장](#테이블-생성) |

---

## 7. Audit 필드와 삭제

### Audit 필드

| 필드 | 채워지는 방식 |
| --- | --- |
| `created_at`, `updated_at` | 자동 (`Instant`, DB `TIMESTAMPTZ`) |
| `created_by`, `updated_by` | 자동. 요청 → `X-User-Id` / 요청 아님(Kafka Consumer, 스케줄러) → `SYSTEM:{서비스 이름}` |

> 💡 `X-User-Id`는 게이트웨이가 JWT 검증 후 넣는 헤더입니다. 서비스 포트로 직접 요청할 때 헤더가 없으면 `SYSTEM:{서비스 이름}`이 들어갑니다.

### 📌📌 삭제 ❌ (소프트 삭제도 없음)

- `deleted_at`, `deleted_by` 컬럼 없음
- 취소, 거절, 해제 → **상태값**으로 남김 (청약 `CANCELLED`, `REJECTED` / 증거금 `VOIDED`)
- 원장(`ledger_entry`)은 **추가만**. 수정, 삭제 ❌ → 정정은 반대 거래 추가
- `repository.delete()` ❌

---

## 8. 예외 처리

공통 에러 코드 (`CommonErrorCode`, API 명세 1.6)

| HTTP | 코드 | 의미 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR`, `INVALID_STATUS` | 필수값 누락, 형식 오류, 잘못된 상태값 |
| 401 | `UNAUTHORIZED` | 인증 실패 |
| 403 | `FORBIDDEN` | 권한 부족 |
| 404 | `RESOURCE_NOT_FOUND` | 없는 리소스 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 메서드 |
| 409 | `DUPLICATE_RESOURCE` | 중복 |
| 422 | `BUSINESS_RULE_VIOLATION` | 업무 규칙 위반 |
| 429 | `TOO_MANY_REQUESTS` | 요청 횟수 제한 |
| 500 | `INTERNAL_SERVER_ERROR` | 서버 내부 오류 |
| 502 | `DEPENDENCY_FAILURE` | 다른 서비스 호출 실패 (Feign 5xx, 타임아웃) |
| 503 | `SERVICE_UNAVAILABLE` | 서비스 이용 불가 |

도메인 에러 → `global/exception/{서비스}ErrorCode`에 추가 + API 명세 오류 표에도 기입 (현재 빈 enum)

```java
// 404: 리소스별 NOT_FOUND 코드 ❌ → RESOURCE_NOT_FOUND + message + field
throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않는 계좌입니다.", "accountId");

// 도메인 에러: {서비스}ErrorCode에 추가 후 던지기 (코드 이름은 예시)
INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "예수금이 부족합니다."),
throw new BusinessException(AccountErrorCode.INSUFFICIENT_BALANCE);
```

`GlobalExceptionHandler` 자동 처리

| 예외 | 응답 |
| --- | --- |
| `BusinessException` | 지정한 코드, 메시지, details |
| `@Valid` 실패 | 400 `VALIDATION_ERROR` + 실패한 필드 **전부** details |
| JSON 형식 오류 | 400 `VALIDATION_ERROR` |
| 파라미터 타입 오류, 필수 파라미터/헤더 누락, 없는 정렬 필드 | 400 `VALIDATION_ERROR` + 해당 필드 details |
| 없는 URL / 메서드 오류 | 404 `RESOURCE_NOT_FOUND` / 405 `METHOD_NOT_ALLOWED` |
| DB 유니크 위반 (동시 요청 등) | 409 `DUPLICATE_RESOURCE` (NOT NULL, FK 위반은 500) |
| Feign 실패 | 아래 표 |
| 그 외 | 500 `INTERNAL_SERVER_ERROR` |

> 💡 특정 예외만 다르게 처리하려면 서비스에 `@RestControllerAdvice` + `@Order(Ordered.HIGHEST_PRECEDENCE)`로 추가합니다. common보다 먼저 적용됩니다.

### Feign(내부 API) 호출 규칙

> 서비스 간 통신은 기본이 Kafka입니다. Feign은 **배정 → 청약 확정 목록 조회** 하나에만 씁니다.

- 경로 `/internal/**`, 게이트웨이 등록 ❌ / `@FeignClient(name = "subscription-service")`처럼 Eureka 서비스 이름
- `X-User-Id`, `X-User-Role`은 common `FeignHeaderInterceptor`가 자동 전달 (직접 넣기 ❌)
- 내부 API도 `ApiResponse` 형식으로 응답
- 실패는 common `FeignErrorDecoder`가 예외로 변환
- 📌 **Circuit Breaker(Resilience4j), Timeout은 common에 없음** → Feign 쓰는 서비스에서 직접 설정 (발제 필수)

| 상황 | 예외 | 의미 | 안 잡으면 |
| --- | --- | --- | --- |
| 상대가 4xx | `FeignClientException` (status, code, message, details) | 업무 실패 | 상대의 상태, error 그대로 응답 |
| 상대가 5xx | `FeignServerException` | 재시도, 보상 대상 | 502 `DEPENDENCY_FAILURE` |
| 타임아웃, 연결 실패 | `feign.RetryableException` | 재시도, 보상 대상 | 502 `DEPENDENCY_FAILURE` |

> 💡 5xx, 타임아웃, 연결 실패는 최대 2회 재시도한 뒤 예외가 납니다 (4xx는 재시도하지 않습니다). 재시도 후에도 예외 종류는 위 표와 같습니다.

- 내부 POST API는 멱등하게 (같은 요청 재수신 시 결과 동일, 재시도 때문)
- 재시도 끄기: `common.feign.retry.max-retries: 0` 또는 `FeignRetryer.noRetry()` (`Retryer.NEVER_RETRY` ❌ → 5xx가 `RetryableException`으로 나옴)
- `Retryer` 빈 직접 생성 시에도 `FeignRetryer` 사용 (예외 종류 유지)

재시도 설정 (서비스 `application.yml`, 없으면 기본값)

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `common.feign.retry.max-retries` | `2` | 재시도 횟수 (처음 호출 제외). `0`이면 재시도 안 함 |
| `common.feign.retry.interval` | `200` | 첫 재시도 전 대기(ms). 이후 1.5배씩 증가 |
| `common.feign.retry.max-interval` | `1000` | 대기 상한(ms) |
| `common.feign.retry.retry-on-read-timeout` | `true` | 응답 지연(read timeout)도 재시도할지 |

```java
try {
    subscriptionClient.getConfirmedSubscriptions(ipoId, page);
} catch (FeignServerException | RetryableException e) {
    // 예: 배정 시작하지 않고 실패 기록 후 재시도
    throw e;
}
```

---

## 9. 📌 Swagger

- **통합 (게이트웨이)**: http://localhost:8080/swagger-ui/index.html → 오른쪽 위 드롭다운에서 서비스 선택
  - fetch error 500 가능: 서비스 Eureka 등록 → 게이트웨이 반영까지 최대 30초
- 서비스별: `http://localhost:{서비스 포트}/swagger-ui/index.html` (IntelliJ 실행 시만. Docker는 서비스 포트 비공개)
- 우상단 Authorize에 토큰만 입력 → `Bearer ` 자동 (통합 Swagger는 로그인으로 받은 `accessToken`이 필요)

> 💡 통합 Swagger의 Try it out 요청은 **게이트웨이를 거쳐** 서비스로 갑니다 (실제 인증 흐름 그대로). 새 서비스를 추가하면 게이트웨이 `application.yml`에 api-docs 라우트와 `springdoc.swagger-ui.urls`를 추가합니다.

```java
@Tag(name = "Subscription", description = "청약 API")
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    @Operation(summary = "청약 신청")
    @PostMapping
    public ResponseEntity<ApiResponse<SubscriptionResponse>> subscribe(@Valid @RequestBody SubscriptionRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(subscriptionService.subscribe(request)));
    }
}
```

- DTO 필드에 `@Schema(description, example)` 필수
- `@ApiErrorCodes(common = {...}, value = {...})`: 적은 에러가 상태별 응답 예시로 자동 표시 (`common` = 공통 에러, `value` = 서비스 도메인 에러)
  - 어노테이션은 서비스마다 `global/swagger/ApiErrorCodes` (속성 타입이 서비스 enum이라 하나씩), 그리는 로직은 common `ErrorCodeOperationCustomizer`
- 문서 제목(`{서비스 이름} API`), JWT Authorize 설정은 common `CommonSwaggerAutoConfiguration`이 자동 등록 → 서비스에 `SwaggerConfig` 만들기 ❌ (바꾸려면 서비스에 `OpenAPI` 빈 직접 생성)

---

## 10. DB와 설정

- 📌 **서비스별 스키마 분리** (발제: 서비스별 DB 분리). 다른 서비스 스키마 직접 조회 ❌ → 이벤트 또는 Feign
- PostgreSQL 컨테이너 하나(`ipo-postgres`), database `ipo` 하나에 스키마 3개: `account`, `subscription`, `allocation` (`docker/postgres/init.sh`)
- 📌 **서비스별 DB 계정**: `account_app`, `subscription_app`, `allocation_app`. 각 계정은 자기 스키마의 소유자이고, 다른 서비스 스키마는 조회할 수 없음 (`permission denied for schema`). `public` 스키마 권한은 회수
  - 비밀번호는 `.env`의 `ACCOUNT_DB_PASSWORD`, `SUBSCRIPTION_DB_PASSWORD`, `ALLOCATION_DB_PASSWORD`에 **직접 정한 값**을 넣음 (기본값 없음, 값은 커밋하지 않음. 따옴표, 공백, `$`, `#` 없이 영문, 숫자로 적는다. docker-compose와 서비스가 `.env`를 다르게 해석한다). docker-compose(계정 생성)와 서비스가 같은 `.env`를 읽으므로 **한 곳에만 적으면 되고** IntelliJ 설정은 필요 없음. 계정 이름은 `application.yml`에 고정
  - 슈퍼유저 `postgres`는 초기화, DB 툴 접속용. 앱은 쓰지 않음
- 서비스는 접속 URL `?currentSchema={서비스}` + `hibernate.default_schema`로 자기 스키마에만 테이블 생성 (스키마는 `init.sh`가 만든다. 서비스 계정은 DB `CREATE` 권한이 없어서 `hibernate.hbm2ddl.create_namespaces`는 동작하지 않아도 됨)
- 물리 분리(DB 서버를 서비스마다 따로)는 아님. 같은 PostgreSQL이라 CPU, 디스크, 커넥션은 공유. 로컬 시연 기준
- DB 툴(DBeaver 등) 접속: `localhost:5432` / DB `ipo` / 슈퍼유저 ID `postgres` / PW는 `.env`의 `DB_PASSWORD`에 정한 값 (기본값 없음. `DB_NAME`, `DB_USERNAME`은 바꿀 때만). 서비스 계정으로도 접속 가능 (자기 스키마만 보임)

> ⚠️ `init.sh`는 볼륨이 비어 있을 때 한 번만 실행됩니다. 스키마, 계정을 추가, 변경했거나 DB 비밀번호를 바꿨으면 **PostgreSQL 볼륨만** 지우고 다시 만듭니다 (DB 데이터는 지워지고 Kafka 데이터는 유지됩니다).

```bash
docker-compose stop postgres
docker-compose rm -f postgres
docker volume rm ipo-subscription_postgres-data   # 이름은 {프로젝트 폴더명}_postgres-data, `docker volume ls`로 확인
docker-compose up -d postgres
```

Kafka 데이터까지 모두 지우려면 `docker-compose down -v` (토픽은 `kafka-init`이 다시 만듭니다)

### 테이블 생성

- 테이블: `ddl-auto: update` (엔티티로 자동 생성)
- 조건부 유니크 인덱스(`WHERE ...`) → 서비스별 `schema.sql`
  - `application.yml`에 `spring.sql.init.mode: always` + `spring.jpa.defer-datasource-initialization: true`
  - `IF NOT EXISTS` 필수
  - 조회 성능용 부분 인덱스도 `schema.sql` (예: outbox 미발행 조회)
- `outbox`, `processed_event` 엔티티는 `common`에 있어도 각 서비스 스키마(`hibernate.default_schema`)에 생성된다

> ⚠️ `update`는 컬럼 **추가만** 반영합니다 (삭제, 이름 변경 X). 테이블이 꼬이면 위의 PostgreSQL 볼륨 초기화 절차로 초기화합니다.

### 📌 설정 파일

| 파일 | 용도 | git |
| --- | --- | --- |
| `application.yml` | 포트, DB, Kafka, Eureka | ✅ |
| `.env` | Docker, 서비스가 함께 읽는 비밀값: DB 비밀번호, `JWT_SECRET` (`.env.example` 복사) | ❌ |

> ⚠️ 비밀값은 커밋하지 않습니다. 공유는 팀에서 승인한 비밀 관리 도구로 합니다.

### 📌 JWT 키

account-service(발급)와 api-gateway(검증)가 **같은 키** 사용 → 다르면 모든 요청 401

```bash
# 루트 .env (두 서비스가 같은 파일을 읽는다. IntelliJ, Docker 모두 해당)
JWT_SECRET=팀 공용 키 (팀에서 승인한 비밀 관리 도구로 공유)
```

- 형식: **표준 Base64 문자열, 디코딩 후 32바이트 이상**. 비어 있거나 Base64가 아니거나 짧으면 account-service와 api-gateway가 기동할 때 실패한다
- 새 키 만들기: `openssl rand -base64 33` (PowerShell: `$b = New-Object byte[] 33; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`)
- Docker도 `.env`의 `JWT_SECRET` 하나로 두 서비스에 같이 적용
- 토큰 규격: HS256, `sub` = userId, `role`(USER/ADMIN), `iss` = account-service, `iat`, `exp`(필수, 1시간)

로그인 흐름

1. `POST /api/v1/auth/login` → 토큰 발급 (응답 형식은 API 명세 기준)
2. 이후 요청 헤더: `Authorization: Bearer {accessToken}` (Swagger는 Authorize에 토큰만)
3. 게이트웨이 검증 후 `X-User-Id`, `X-User-Role`을 서비스로 전달

---

## 11. 트러블슈팅

### 🔧 자주 발생하는 오류 및 해결 방법

- **Gradle 탭 또는 실행 버튼(▶)이 안 보일 때**
  - 루트 `build.gradle` 우클릭 → `Link Gradle Project`
  - 안 되면 IntelliJ 종료 → `.idea` 삭제 → 다시 열기

- **`Unsupported class file major version` / Java 17로 빌드됨**
  - IntelliJ: Settings → Build Tools → Gradle → Gradle JVM **21**, Project Structure → SDK **21**

- **`Connection refused` (5432)**
  - `docker-compose up -d postgres`

- **`password authentication failed`**
  - 다른 PostgreSQL이 5432 사용 중인지 확인 후 중지
  - 서비스 기동 시 `user "account_app"`(서비스별 계정)이면: `.env`에 비밀번호를 채웠는지, 값이 DB를 처음 만들 때와 같은지 확인. 서비스 계정이 없는 옛 볼륨이면 [10장](#10-db와-설정)의 PostgreSQL 볼륨 초기화 절차로 다시 만든다 (DB 데이터가 지워지고 Kafka 데이터는 유지)

- **`entrypoint.sh: ...이(가) 비어 있습니다`** (postgres가 종료됨)
  - `.env`에 DB 비밀번호(`DB_PASSWORD`, 서비스별 3개)를 채우고 다시 `docker-compose up -d`. DB를 만들기 전에 막으므로 볼륨을 지울 필요 없음

- **`Port already in use`**
  - 해당 포트 사용 중인 앱 종료

- **Windows에서 `bind: An attempt was made to access a socket in a way forbidden by its access permissions`**
  - Windows가 예약한 포트 범위에 걸린 것 (범위는 PC마다 다름). `netsh interface ipv4 show excludedportrange protocol=tcp`로 확인 후 `.env`(없으면 `cp .env.example .env`로 만든다)에서 `KAFKA_PORT`, `KAFKA_UI_PORT`, `REDIS_PORT`, `EUREKA_PORT`, `PROMETHEUS_PORT`, `GRAFANA_PORT`를 다른 값으로 변경

- **로그인은 되는데 다른 요청이 전부 401 `UNAUTHORIZED`**
  - account-service와 gateway의 JWT 키가 같은지 확인

- **account-service 또는 api-gateway가 기동할 때 `JWT_SECRET` 오류**
  - `.env`의 `JWT_SECRET`이 비어 있거나, Base64가 아니거나, 디코딩 후 32바이트 미만 → [JWT 키](#-jwt-키)대로 값을 채움

- **Gateway(8080)로 호출하면 `401`, 서비스 포트로 직접 호출하면 정상**
  - 정상. Gateway는 `Authorization: Bearer {accessToken}`이 필요 (공개 경로 제외). 만료는 `TOKEN_EXPIRED`이니 다시 로그인

- **Gateway 500/503: `Unable to find instance`**
  - 서비스 시작 직후 → Eureka 등록까지 약 30초 후 재시도

- **Eureka: `SELF PRESERVATION ... OFF`**
  - 의도된 설정, 무시

- **Docker에 코드 변경사항 미반영**
  - `./gradlew build -x test` → 이미지 재빌드, 컨테이너 재생성

- **Docker 앱이 종료됨**
  - 메모리 부족 확인 → Colima: `colima start --memory 6 --cpus 4`

- **Grafana가 바로 종료됨 (`GRAFANA_ADMIN_PASSWORD가 비어 있습니다`)**
  - `.env`의 `GRAFANA_ADMIN_PASSWORD`에 직접 정한 값을 넣고 다시 `docker-compose up -d grafana`

- **Slack 장애 알림이 오지 않음**
  - `docker-compose logs grafana`에 `SLACK_WEBHOOK_URL이 비어 있어`가 있으면 `.env`에 URL이 없거나, `.env`를 바꾼 뒤 `docker-compose up -d grafana`를 하지 않은 것
  - `Failed to provision alerting`이 있으면 `docker/grafana/provisioning/alerting/`의 파일 형식 오류. 로그의 파일 이름과 내용을 확인한다
  - 알림은 조건이 지속 시간(1~5분) 동안 이어지고 30초를 더 기다린 뒤에 나간다. 바로 오지 않는 것은 정상
  - Grafana → Alerting → Alert rules에서 규칙 상태(Normal, Pending, Firing)와 오류를 확인한다
  - ⚠️ Slack 전송이 실패하면 Grafana 오류 로그에 Webhook URL이 그대로 찍힐 수 있다. 로그를 이슈, PR, 메신저에 붙일 때는 `hooks.slack.com/services/...` 부분을 지운다

- **Docker에서 서비스가 `unhealthy`이거나 `starting`에서 안 넘어감**
  - `docker-compose logs {서비스 이름}`에서 `APPLICATION FAILED`나 `ERROR`를 확인한다
  - `JWT_SECRET`(account, gateway) 또는 서비스별 DB 비밀번호가 `.env`에 비어 있거나 형식이 틀리면 기동에 실패한다 → [JWT 키](#-jwt-키), [DB와 설정](#10-db와-설정)
  - 서비스는 postgres, kafka, `kafka-init`(토픽 생성 완료), eureka-server가 준비된 뒤에 시작한다. 앞 단계가 `healthy`인지 `docker-compose ps`로 본다

- **`docker-compose up`을 쳤더니 이미지 빌드 오류 (`build/libs/*.jar` 없음)**
  - jar를 먼저 빌드한다: `./gradlew build -x test`. 인프라만 필요하면 서비스 이름 없이 `up` 하지 말고 `docker-compose up -d postgres kafka kafka-init kafka-ui redis`
