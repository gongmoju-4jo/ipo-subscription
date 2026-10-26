# 프로젝트 세팅 가이드

> 📌 표시가 있는 곳은 꼭 읽어주세요.

## 목차

1. [빠른 시작](#1--빠른-시작)
2. [구조](#2-구조)
3. [실행](#3-실행)
4. [패키지 구조](#4-패키지-구조)
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

> ✏️ docker-compose는 지금 PostgreSQL만 있습니다. Kafka, 서비스 컨테이너, 모니터링과 서비스 모듈은 추가되는 대로 이 절을 갱신합니다.

---

## 2. 구조

Gradle 멀티프로젝트

```
ipo-subscription/
├── build.gradle            공통 버전, 의존성 (Boot 3.5.16, Cloud 2025.0.3, Java 21)
├── settings.gradle         모듈 목록
├── Dockerfile              서비스 공용
├── common/                 공통 라이브러리 (실행 X)
├── eureka-server/
├── api-gateway/
├── account-service/        회원, 계좌, 증거금, 원장 (추가 예정)
├── subscription-service/   공모주, 청약 (추가 예정)
└── allocation-service/     배정 (추가 예정)
```

### 포트

| 앱 | 포트 |
| --- | --- |
| eureka / gateway | 8761 / 8080 |
| 서비스 3개 | 서비스 모듈 추가 시 기입 |

### 게이트웨이 라우팅

경로는 API 명세 목차 기준 **초안**입니다. 명세가 확정되면 `api-gateway/src/main/resources/application.yml`과 이 표를 같이 고칩니다.

| 경로 | 서비스 |
| --- | --- |
| `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/accounts/**` | account |
| `/api/v1/ipos/**`, `/api/v1/subscriptions/**`, `/api/v1/admin/**` | subscription |
| `/api/v1/allocations/**` | allocation |

- 새 외부 경로 → `api-gateway/.../application.yml`에 추가
- `/internal/**`은 라우팅 X (Feign 전용, 외부 접근 불가)

### 📌 게이트웨이 구현할 때 확인할 것 (logistics-msa와 달라진 점)

현재 api-gateway에는 라우트와 통합 Swagger만 있습니다. 인증 필터는 logistics-msa `api-gateway/src/main/java/.../global/`을 참고해서 구현합니다.

- 📌 스타터 이름이 바뀌었습니다: `spring-cloud-starter-gateway` → `spring-cloud-starter-gateway-server-webflux` (Spring Cloud 2025.0, 반영 완료)
- 📌 라우트 설정 위치: `spring.cloud.gateway.server.webflux.routes` (새 방식, 반영 완료). 옛 위치 `spring.cloud.gateway.routes`도 지금 버전에서 동작은 하지만 새 위치로 통일합니다
- 📌 JWT 발급자(iss)는 `user-service`가 아니라 **`account-service`**
- 📌 역할은 **USER, ADMIN** 두 개 (common `UserRole`과 같은 값 유지). `/api/v1/admin/**`은 ADMIN만
- 📌 Swagger 경로를 인증 공개 경로에 넣습니다: `/swagger-ui/**`, `/v3/api-docs/**`, `/*-service/v3/api-docs` (안 넣으면 Swagger가 401로 막힘)
- 라우트, Swagger 설정이 적용되는지는 `GatewayRoutesTest`가 확인합니다. 라우트를 바꾸면 테스트도 같이 고칩니다

---

## 3. 실행

> 평소 개발은 **IntelliJ** (DB, Kafka만 Docker). 요구사항상 최종 동작은 **전체 Docker 실행**으로 확인. 두 방식 동시 실행 ❌ (포트 충돌)

### IntelliJ

1. `⌘8`/`Alt+8` Services 창 → Eureka 실행 → 나머지 실행
2. http://localhost:8080/api/v1/unknown → 404 JSON이면 게이트웨이 정상 (인증 필터가 들어온 뒤에는 401)

> 💡 전부 안 켜도 됨: **Eureka + 내 서비스 + 내 서비스가 호출하는 서비스**만 실행

### Docker

```bash
./gradlew build -x test && docker-compose up -d --build   # 실행
docker-compose logs -f {서비스 이름}                        # 로그
docker-compose down                                        # 종료
docker-compose down -v                                     # 종료 + DB 초기화
```

- 📌 처음 한 번: `cp .env.example .env` → `JWT_SECRET`에 팀 공용 키 넣기
- ⚠️ 코드 수정 후엔 위 `실행` 명령 그대로 (`./gradlew build`가 빠지면 예전 코드로 뜸)
- 📌 서비스 포트로 직접 접속 ❌ → `localhost:8080`(게이트웨이)으로 요청
- ⚠️ **Docker 메모리 4GB 이상 권장**: Kafka까지 띄우면 2GB(Colima 기본)로는 부족할 수 있음
  - Colima: `colima stop && colima start --cpus 4 --memory 6` / Docker Desktop: Settings → Resources → Memory

> ✏️ docker-compose.yml은 지금 PostgreSQL만 있습니다. 서비스, Kafka가 추가되면 목록과 의존 관계(healthcheck 등)를 여기에 적습니다.

### Kafka

- 토픽, 파티션 키, 페이로드는 **이벤트 명세 문서**를 기준으로 합니다 (코드보다 문서 먼저 갱신)
- 실패 메시지는 `{토픽}.DLT`
- 접속 주소, 설정 키는 docker-compose 추가 시 기입

---

## 4. 패키지 구조

도메인 → 계층 순으로 나눔

```
subscription-service/.../com/gongmoju/subscription/
├── global/
│   ├── config/           SwaggerConfig 등
│   └── exception/        SubscriptionErrorCode
└── subscription/
    ├── controller/       Controller
    ├── dto/
    │   ├── request/
    │   └── response/
    ├── service/          Service
    ├── repository/       Repository
    ├── entity/           Entity, enum
    └── client/           {대상서비스}Client
        └── dto/          Feign 요청, 응답 DTO
```

- 서비스 안의 도메인 폴더 나누기는 담당자가 정합니다
- 엔티티 그대로 응답 ❌ → DTO로 변환
- Feign, 외부 호출은 `service`에서만 (controller, entity에서 ❌)
- Feign DTO는 `client/dto/`에 따로 (내 API용 `dto/`와 섞지 않음, 필요한 필드만)

---

## 5. common 모듈

전 서비스가 똑같이 따라야 하는 약속(응답 형식, 예외 처리, Audit, Feign 설정)을 모은 라이브러리

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
| `feign/` | 헤더 전달, 에러 디코더, 재시도, Feign 예외 처리 (Feign 있는 서비스만 적용) |
| `config/` | 위 기능을 서비스에 자동 등록 (AutoConfiguration) |

> 💡 서비스는 자기 패키지(`com.gongmoju.{서비스}`)만 스캔하므로, common의 예외 처리, JPA Auditing, Feign 설정은 `common/src/main/resources/META-INF/spring/...AutoConfiguration.imports`로 자동 등록됩니다. 서비스에서 `JpaAuditingConfig`를 따로 만들지 않습니다

### common에 넣는 기준

| common ✅ (도메인을 모르는 것) | 서비스 ❌ (도메인을 아는 것) |
| --- | --- |
| 응답 형식, 공통 에러 코드, 전역 예외 처리 | 엔티티, Repository, 비즈니스 로직 |
| Feign 공통 설정 (헤더 전달, 에러 디코더, 재시도) | `@FeignClient` 인터페이스, Feign DTO (`client/`) |
| `BaseEntity`, JPA Auditing | 도메인 에러 enum (`{서비스}ErrorCode`) |
| | Kafka 이벤트 DTO (서비스마다 따로 두고, 계약은 이벤트 명세 문서로 맞춤) |
| | `SwaggerConfig` (서비스 `global/`) |

> ⚠️ common이 바뀌면 전 서비스가 영향을 받습니다. "여러 서비스가 쓰니까"만으로 넣지 말고, **도메인을 모르는 공통 약속**인지 확인 후 팀에 먼저 공유

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

- 반환할 데이터가 없으면 `ApiResponse.success(null)` → `data` 필드 빠짐
- Feign으로 다른 서비스 호출 시에도 `ApiResponse<XxxResponse>`로 받아서 `.data()` 사용

### 목록 조회

- 컨트롤러 파라미터에 `@ParameterObject Pageable pageable` → `?page=0&size=20&sort=createdAt,desc`
- 기본값은 common에서 자동 적용: `page=0`, `size=20` (최대 100, 넘으면 100으로), 정렬 미지정 시 `createdAt DESC`
- `Page`를 그대로 반환 ❌ → `PageResponse.from(page)` 또는 `PageResponse.from(entityPage, XxxResponse::from)`
- 정렬 가능 필드는 API별로 제한 (API 명세 1.4). 없는 필드로 정렬 요청 시 400 `VALIDATION_ERROR`

### 사용

서비스 `build.gradle`에 아래 한 줄 추가

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

> 💡 `compileOnly`: common은 빌드할 때만 라이브러리를 참고하고, 서비스에 같이 넘겨주지 않음
> - 라이브러리는 **서비스가 직접** `build.gradle`에 추가해야 동작
> - 예) Feign을 안 쓰는 서비스는 openfeign 의존성이 없으므로 common Feign 설정도 안 붙음
>   - 필요해지면 `openfeign` 의존성 + `@EnableFeignClients`만 추가 (공통 설정 자동 적용)

- common에 라이브러리 추가하는 경우 → common엔 `compileOnly` + 서비스엔 `implementation`
  - 서비스쪽 추가 누락 시 `ClassNotFoundException` 오류 → 서비스 `build.gradle`에 해당 의존성 추가하여 해결 가능

---

## 6. 엔티티 규칙

> ✏️ 테이블, 컬럼은 **ERD 문서**가 기준입니다. 엔티티를 바꾸면 ERD 문서도 같이 갱신하고, 다른 서비스가 참조하는 컬럼(이벤트 필드 포함)을 바꿀 땐 해당 담당자에게 먼저 공유하세요.

| 규칙 | 비고 |
| --- | --- |
| `BaseEntity` 상속 | Audit 필드 자동. 단 `ledger_entry`(원장)는 수정이 없어 상속하지 않음 |
| 📌 테이블 이름: 접두사 없음, snake_case 단수형 (`@Table(name = "subscription")`) | 서비스마다 DB가 따로라 이름이 겹치지 않음. 회원만 `users` (`user`는 PostgreSQL 예약어) |
| PK: UUID (`@GeneratedValue(strategy = UUID)` + `@Column(name = "subscription_id")`) | API 명세의 ID 형식과 같음 |
| 다른 서비스 엔티티 참조 ❌ → `UUID`만 | 다른 서비스 데이터는 이벤트나 Feign으로 |
| 같은 서비스 안은 `@ManyToOne(fetch = LAZY)` | 예: Margin → Account |
| `@OneToOne` ❌ → `@ManyToOne` | |
| `@Enumerated(EnumType.STRING)` | 상태값은 상태 전이 문서 기준 |
| 금액은 `long`(원 단위) | API 명세의 금액 형식과 같음 |
| `@NoArgsConstructor(access = PROTECTED)` + `@Builder` | |
| setter ❌ → 의미 있는 메서드 | `subscription.confirm()`, `margin.voidPending()` |
| 조건부 유니크는 `schema.sql`에 | 예: 이중청약 방지 `UNIQUE(ipo_id, investor_id) WHERE status NOT IN ('REJECTED','CANCELLED')` → [10장](#테이블-생성) |

---

## 7. Audit 필드와 삭제

### Audit 필드

| 필드 | 채워지는 방식 |
| --- | --- |
| `created_at`, `updated_at` | 자동 |
| `created_by`, `updated_by` | 자동. 요청이면 `X-User-Id`, 요청이 아니면(Kafka Consumer, 스케줄러) `SYSTEM:{서비스 이름}` |

> 💡 `X-User-Id`는 게이트웨이가 JWT 검증 후 넣어주는 헤더입니다. 서비스 포트로 직접 요청해 테스트할 때 헤더가 없으면 `SYSTEM:{서비스 이름}`이 들어갑니다.

### 📌📌 삭제하지 않습니다 (소프트 삭제도 없음)

- `deleted_at`, `deleted_by` 컬럼이 없습니다
- 취소, 거절, 해제는 **상태값**으로 남깁니다 (예: 청약 `CANCELLED`, `REJECTED` / 증거금 `VOIDED`)
- 원장(`ledger_entry`)은 **추가만** 합니다. 수정, 삭제 ❌ → 정정은 반대 거래를 새로 추가
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

도메인 에러는 `global/exception/{서비스}ErrorCode`에 추가하고, API 명세 오류 표에도 같이 적습니다.

```java
// 404: 리소스별 NOT_FOUND 코드를 만들지 않고 RESOURCE_NOT_FOUND + message + field
throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않는 계좌입니다.", "accountId");

// 도메인 에러: {서비스}ErrorCode에 추가 후 던지기 (아래 코드 이름은 예시)
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

> 💡 서비스에서 특정 예외를 다르게 처리하고 싶으면 서비스에 `@RestControllerAdvice` + `@Order(Ordered.HIGHEST_PRECEDENCE)`로 추가 (common보다 먼저 적용)

### Feign(내부 API) 호출 규칙

> 서비스 간 통신은 기본이 Kafka입니다. Feign은 **배정 → 청약 확정 목록 조회** 하나만 씁니다.

- 경로 `/internal/**`, 게이트웨이에 등록 ❌ / `@FeignClient(name = "subscription-service")`처럼 Eureka 서비스 이름 사용
- `X-User-Id`, `X-User-Role`은 common `FeignHeaderInterceptor`가 자동 전달 (직접 넣지 않음)
- 내부 API도 `ApiResponse` 형식으로 응답
- 실패는 common `FeignErrorDecoder`가 예외로 변환
- 📌 **Circuit Breaker(Resilience4j)와 Timeout은 common에 없습니다.** Feign을 쓰는 서비스에서 직접 설정합니다 (발제 필수)

| 상황 | 예외 | 의미 | 안 잡으면 |
| --- | --- | --- | --- |
| 상대가 4xx | `FeignClientException` (status, code, message, details) | 업무 실패 | 상대의 상태, error 그대로 응답 |
| 상대가 5xx | `FeignServerException` | 재시도, 보상 대상 | 502 `DEPENDENCY_FAILURE` |
| 타임아웃, 연결 실패 | `feign.RetryableException` | 재시도, 보상 대상 | 502 `DEPENDENCY_FAILURE` |

> 💡 5xx, 타임아웃, 연결 실패는 최대 2회 재시도 후 예외 (4xx는 재시도 없이 바로 예외). 재시도 후에도 예외 종류는 위 표와 같음

- 내부 POST API는 같은 요청을 다시 받아도 결과가 같게(멱등) 만든다 (재시도 때문에)
- 내부 클라이언트에서 재시도를 끄려면 `Retryer.NEVER_RETRY` 대신 `common.feign.retry.max-retries: 0` 또는 `FeignRetryer.noRetry()`를 쓴다 (`NEVER_RETRY`면 5xx가 `FeignServerException`이 아니라 `RetryableException`으로 나옴)
- 서비스가 `Retryer` 빈을 직접 만들 때도 `FeignRetryer`를 써야 예외 종류가 유지된다

재시도 설정 (서비스 `application.yml`에서 변경, 없으면 기본값)

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
    // 예: 배정을 시작하지 않고 실패로 기록한 뒤 재시도
    throw e;
}
```

---

## 9. 📌 Swagger

- **통합 (게이트웨이)**: http://localhost:8080/swagger-ui/index.html → 오른쪽 위 드롭다운에서 서비스 선택
  - fetch error 500 발생 가능: 서비스가 Eureka에 등록되고 → 게이트웨이가 Eureka에서 그 목록을 받아오기까지 최대 30초 소요됨
- 서비스별: `http://localhost:{서비스 포트}/swagger-ui/index.html` (IntelliJ 실행 시만. Docker는 서비스 포트가 외부에 안 열림)
- 우상단 Authorize에 토큰만 입력 → `Bearer ` 자동 붙여줌 (인증 필터가 들어온 뒤)

> 💡 통합 Swagger의 Try it out 요청은 **게이트웨이를 거쳐** 서비스로 갑니다 (실제 인증 흐름 그대로). 새 서비스를 추가하면 게이트웨이 `application.yml`에 api-docs 라우팅과 `springdoc.swagger-ui.urls`를 추가하세요.

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

---

## 10. DB와 설정

- 📌 **서비스별 DB 분리** (발제 필수). 다른 서비스 DB 직접 조회 ❌ → 이벤트 또는 Feign
- PostgreSQL 컨테이너 하나(`ipo-postgres`)에 DB 3개: `account_db`, `subscription_db`, `allocation_db` (`docker/postgres/init.sql`)
- DB 툴(DBeaver 등) 접속: `localhost:5432` / ID `postgres` / PW `postgres` (`.env`의 `DB_USERNAME`, `DB_PASSWORD`로 변경 가능)

> ⚠️ `init.sql`은 볼륨이 비어 있을 때 한 번만 실행됩니다. DB를 추가, 변경했으면 `docker-compose down -v && docker-compose up -d postgres`

### 테이블 생성

- 테이블: `ddl-auto: update` (엔티티로 자동 생성)
- 조건부 유니크 인덱스(`WHERE ...`)는 서비스별 `schema.sql`에 둔다
  - `application.yml`에 `spring.sql.init.mode: always` + `spring.jpa.defer-datasource-initialization: true`
  - `IF NOT EXISTS` 필수
  - 조회 성능용 부분 인덱스도 `schema.sql`에 둔다 (예: outbox 미발행 조회)

> ⚠️ `update`는 컬럼 **추가만** 반영합니다 (삭제, 이름 변경 X). 테이블이 꼬이면 `docker-compose down -v`로 DB를 초기화하세요.

### 📌 설정 파일

| 파일 | 용도 | git |
| --- | --- | --- |
| `application.yml` | 포트, DB, Eureka | ✅ |
| `application-secret.yml` | JWT 시크릿 (resources에 직접 생성) | ❌ |
| `.env` | Docker용 비밀값 (`.env.example` 복사) | ❌ |

> ⚠️ 비밀값은 커밋 ❌ 공유는 슬랙 또는 노션 사용.

### 📌 JWT 키

account-service(발급)와 api-gateway(검증)가 **같은 키**를 써야 함 → 다르면 모든 요청 401

```yaml
# account-service, api-gateway 각각 src/main/resources/application-secret.yml
jwt:
  secret: 팀 공용 키 (슬랙, 노션)
```

- Docker는 `.env`의 `JWT_SECRET` 하나로 두 서비스에 같이 들어감
- 토큰 규격: HS256, `sub` = userId, `role`(USER/ADMIN), `iss` = account-service

로그인 흐름

1. `POST /api/v1/auth/login` → 토큰 발급 (응답 형식은 API 명세 기준)
2. 이후 요청 헤더: `Authorization: Bearer {accessToken}` (Swagger는 Authorize 버튼에 토큰만 입력)
3. 게이트웨이가 검증 후 `X-User-Id`, `X-User-Role`을 서비스로 전달

---

## 11. 트러블슈팅

### 🔧 자주 발생하는 오류 및 해결 방법

- **Gradle 탭 또는 실행 버튼(▶)이 안 보일 때**
  - 루트 `build.gradle` 우클릭 → `Link Gradle Project`
  - 해결되지 않으면 IntelliJ 종료 후 `.idea` 삭제하고 다시 열기

- **`Unsupported class file major version` / 빌드가 Java 17로 돎**
  - IntelliJ: Settings → Build Tools → Gradle → Gradle JVM을 **21**로, Project Structure → SDK도 21로

- **`Connection refused` (5432)**
  - PostgreSQL 컨테이너 실행 확인

- **`password authentication failed`**
  - 다른 PostgreSQL이 5432 포트를 사용 중인지 확인 후 중지

- **`Port already in use`**
  - 해당 포트를 사용 중인 애플리케이션 종료

- **로그인은 되는데 다른 요청이 전부 401 `UNAUTHORIZED`**
  - account-service와 gateway의 JWT 키가 다른지 확인 (같은 공용 키여야 함)

- **Gateway 500/503: `Unable to find instance`**
  - 서비스 시작 직후라면 Eureka 등록을 기다린 뒤 약 30초 후 재시도

- **Eureka: `SELF PRESERVATION ... OFF`**
  - 의도된 설정이므로 경고 무시

- **Docker에 코드 변경사항이 반영되지 않을 때**
  - `./gradlew build -x test` 실행 후 이미지 재빌드 및 컨테이너 재생성

- **Docker 애플리케이션이 종료될 때**
  - 메모리 부족 여부 확인
  - Colima 리소스 조정: `colima start --memory 6 --cpus 4`
