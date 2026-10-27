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
docker-compose up -d postgres
```

1. IntelliJ로 **루트 폴더** 열기 → Gradle 탭 🔄
2. `EurekaServerApplication` 먼저 실행 → 나머지 앱 실행 (hotkey: cmd+8 / alt+8)
3. http://localhost:8761 에 실행한 앱이 모두 UP이면 끝

필요: **JDK 21**, Docker + Compose v2 이상, IntelliJ

> ✏️ docker-compose에는 현재 PostgreSQL, Kafka, Kafka-UI가 있습니다. 서비스 컨테이너, 모니터링이 추가되면 갱신합니다.

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
├── docker-compose.yml      로컬 인프라 (현재 PostgreSQL, Kafka, Kafka-UI)
├── docker/postgres/        서비스별 스키마 생성 스크립트
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

### 게이트웨이 라우팅

API 명세 목차 기준 **초안**. 명세 확정 시 `api-gateway/.../application.yml`과 함께 수정

| 경로 | 서비스 |
| --- | --- |
| `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/accounts/**` | account |
| `/api/v1/ipos/**`, `/api/v1/subscriptions/**`, `/api/v1/admin/**` | subscription |
| `/api/v1/allocations/**` | allocation |

- 새 외부 경로 → `api-gateway/.../application.yml`에 추가
- `/internal/**`은 라우팅 X (Feign 전용, 외부 접근 불가)

### 📌 게이트웨이 구현 시 확인

현재: 라우트 + 통합 Swagger만. 인증 필터, 공통 오류 응답 없음

- 📌 라우트 위치: `spring.cloud.gateway.server.webflux.routes` (Spring Cloud 2025.0 방식)
- 📌 JWT iss: **`account-service`**
- 📌 역할: **USER, ADMIN** (common `UserRole`과 같은 값). `/api/v1/admin/**`은 ADMIN만
- 📌 인증 공개 경로에 Swagger 추가: `/swagger-ui/**`, `/v3/api-docs/**`, `/*-service/v3/api-docs` (누락 시 401)
- 클라이언트가 보낸 `X-User-Id`, `X-User-Role` 제거 → 검증한 토큰 값으로 다시 설정 (서비스, Audit이 이 헤더를 신뢰)
- 라우트, Swagger 설정은 `GatewayRoutesTest`가 확인 → 라우트 변경 시 테스트도 수정

---

## 3. 실행

> 평소 개발은 **IntelliJ**로 하고 DB, Kafka만 Docker로 띄웁니다. 최종 동작은 **전체 Docker 실행**으로 확인합니다. 두 방식을 동시에 실행하면 포트가 충돌합니다.

### IntelliJ

1. `docker-compose up -d postgres kafka kafka-ui` (Kafka가 필요 없는 작업이면 `postgres`만)
2. `⌘8`/`Alt+8` Services 창 → Eureka 실행 → 나머지 실행
3. http://localhost:8080/api/v1/unknown → 404면 게이트웨이 정상 (인증 필터 추가 후엔 401)

> 💡 전부 켤 필요는 없습니다. **Eureka + 내 서비스 + 내 서비스가 호출하는 서비스**만 실행하면 됩니다.

### Docker

> ✏️ 서비스 컨테이너는 아직 compose에 없습니다. 추가된 뒤 아래 명령을 사용합니다.

```bash
./gradlew build -x test && docker-compose up -d --build   # 실행
docker-compose logs -f {서비스 이름}                        # 로그
docker-compose down                                        # 종료
docker-compose down -v                                     # 종료 + DB 초기화
```

- 📌 처음 한 번: `cp .env.example .env` → `JWT_SECRET`에 팀 공용 키
- ⚠️ 코드 수정 후엔 위 `실행` 명령 그대로 (`./gradlew build` 빠지면 예전 코드로 뜸)
- 📌 서비스 포트 직접 접속 ❌ → `localhost:8080`(게이트웨이)으로 요청
- ⚠️ **Docker 메모리 4GB 이상 권장** (Kafka 포함 시 2GB 부족)
  - Colima: `colima stop && colima start --cpus 4 --memory 6` / Docker Desktop: Settings → Resources → Memory

### Kafka

- 토픽, 파티션 키, 페이로드 → **이벤트 명세 문서** 기준 (코드보다 문서 먼저 수정)
- 실패 메시지: `{토픽}.DLT`
- 접속 주소 기본값: `localhost:29092` (서비스 `application.yml`의 `KAFKA_BOOTSTRAP_SERVERS`)
- 실행: `docker-compose up -d kafka kafka-ui`
- Kafka-UI: http://localhost:8989 (토픽, 컨슈머 그룹 확인)
- 호스트 포트 변경: `.env`의 `KAFKA_PORT`(기본 29092), `KAFKA_UI_PORT`(기본 8989)
  - `KAFKA_PORT`를 바꾸면 서비스 실행 시 `KAFKA_BOOTSTRAP_SERVERS`도 `localhost:{바꾼 포트}`로 맞춘다

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

전 서비스 공통 약속(응답 형식, 예외 처리, Audit, Feign 설정) 라이브러리

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
| `config/` | 위 기능 + Swagger 공통 설정 자동 등록 (AutoConfiguration) |

> 💡 서비스는 자기 패키지(`com.gongmoju.{서비스}`)만 스캔하므로 common 설정은 `AutoConfiguration.imports`로 자동 등록됩니다. 서비스에 `JpaAuditingConfig`, `SwaggerConfig`를 따로 만들지 않습니다.

### common에 넣는 기준

| common ✅ (도메인 모름) | 서비스 ❌ (도메인 아는 경우)                       |
| --- |-----------------------------------------|
| 응답 형식, 공통 에러 코드, 전역 예외 처리 | 엔티티, Repository, 비즈니스 로직                |
| Feign 공통 설정 (헤더 전달, 에러 디코더, 재시도) | `@FeignClient` 인터페이스, Feign DTO         |
| `BaseEntity`, JPA Auditing, Swagger 공통 설정 | 도메인 에러 enum (`{서비스}ErrorCode`)          |
| | Kafka 이벤트 DTO (서비스마다 따로, 계약은 이벤트 명세 문서) |

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
compileOnly 'io.swagger.core.v3:swagger-annotations-jakarta:2.2.22'   // @Schema
```

> 💡 `compileOnly`: common은 빌드할 때만 라이브러리를 참고하고, 서비스에 넘겨주지 않습니다.
> - 라이브러리는 **서비스가 직접** `build.gradle`에 추가해야 동작합니다.
> - 예) openfeign이 없는 서비스는 common Feign 설정도 붙지 않습니다. 필요하면 `openfeign` 의존성 + `@EnableFeignClients`만 추가합니다.

- common에 라이브러리 추가 → common엔 `compileOnly` + 서비스엔 `implementation`
  - 서비스쪽 누락 시 `ClassNotFoundException` → 서비스 `build.gradle`에 의존성 추가

---

## 6. 엔티티 규칙

> ✏️ 테이블, 컬럼은 **ERD 문서**가 기준입니다. 엔티티를 바꾸면 ERD도 같이 고치고, 다른 서비스가 참조하는 컬럼(이벤트 필드 포함)은 담당자에게 먼저 공유합니다.

| 규칙 | 비고 |
| --- | --- |
| `BaseEntity` 상속 | Audit 필드 자동. `ledger_entry`(원장)는 수정이 없어 상속 ❌ |
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
- 우상단 Authorize에 토큰만 입력 → `Bearer ` 자동 (인증 필터 추가 후)

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
- PostgreSQL 컨테이너 하나(`ipo-postgres`), database `ipo` 하나에 스키마 3개: `account`, `subscription`, `allocation` (`docker/postgres/init.sql`)
- 서비스는 접속 URL `?currentSchema={서비스}` + `hibernate.default_schema`로 자기 스키마에만 테이블 생성 (스키마가 없으면 `hibernate.hbm2ddl.create_namespaces`로 자동 생성)
- 물리 분리(DB 서버를 서비스마다 따로)는 아님. 로컬 시연 기준
- DB 툴(DBeaver 등) 접속: `localhost:5432` / DB `ipo` / ID `postgres` / PW `postgres` (`.env`의 `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`로 변경)

> ⚠️ `init.sql`은 볼륨이 비어 있을 때 한 번만 실행됩니다. 스키마를 추가, 변경했으면 `docker-compose down -v && docker-compose up -d postgres`로 다시 만듭니다.

### 테이블 생성

- 테이블: `ddl-auto: update` (엔티티로 자동 생성)
- 조건부 유니크 인덱스(`WHERE ...`) → 서비스별 `schema.sql`
  - `application.yml`에 `spring.sql.init.mode: always` + `spring.jpa.defer-datasource-initialization: true`
  - `IF NOT EXISTS` 필수
  - 조회 성능용 부분 인덱스도 `schema.sql` (예: outbox 미발행 조회)

> ⚠️ `update`는 컬럼 **추가만** 반영합니다 (삭제, 이름 변경 X). 테이블이 꼬이면 `docker-compose down -v`로 초기화합니다.

### 📌 설정 파일

| 파일 | 용도 | git |
| --- | --- | --- |
| `application.yml` | 포트, DB, Kafka, Eureka | ✅ |
| `application-secret.yml` | JWT 시크릿 (resources에 직접 생성) | ❌ |
| `.env` | Docker용 비밀값 (`.env.example` 복사) | ❌ |

> ⚠️ 비밀값은 커밋하지 않습니다. 공유는 슬랙 또는 노션으로 합니다.

### 📌 JWT 키

account-service(발급)와 api-gateway(검증)가 **같은 키** 사용 → 다르면 모든 요청 401

```yaml
# account-service, api-gateway 각각 src/main/resources/application-secret.yml
jwt:
  secret: 팀 공용 키 (슬랙, 노션)
```

- Docker는 `.env`의 `JWT_SECRET` 하나로 두 서비스에 같이 적용
- 토큰 규격: HS256, `sub` = userId, `role`(USER/ADMIN), `iss` = account-service

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

- **`Port already in use`**
  - 해당 포트 사용 중인 앱 종료

- **Windows에서 `bind: An attempt was made to access a socket in a way forbidden by its access permissions`**
  - Windows가 예약한 포트 범위에 걸린 것 (범위는 PC마다 다름). `netsh interface ipv4 show excludedportrange protocol=tcp`로 확인 후 `.env`에서 `KAFKA_PORT`, `KAFKA_UI_PORT`를 다른 값으로 변경

- **로그인은 되는데 다른 요청이 전부 401 `UNAUTHORIZED`**
  - account-service와 gateway의 JWT 키가 같은지 확인

- **Gateway 500/503: `Unable to find instance`**
  - 서비스 시작 직후 → Eureka 등록까지 약 30초 후 재시도

- **Eureka: `SELF PRESERVATION ... OFF`**
  - 의도된 설정, 무시

- **Docker에 코드 변경사항 미반영**
  - `./gradlew build -x test` → 이미지 재빌드, 컨테이너 재생성

- **Docker 앱이 종료됨**
  - 메모리 부족 확인 → Colima: `colima start --memory 6 --cpus 4`
