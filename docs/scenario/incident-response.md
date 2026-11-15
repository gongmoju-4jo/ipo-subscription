# 장애 대응 절차

> Slack 장애 알림을 받으면
> - ① 알림 종류로 이 문서의 절을 찾고
> - ② 확인 명령으로 원인을 확인하고
> - ③ 원인 해결 후 재처리, 복구를 확인한다.
    >
- **잔고, 원장, 상태값은 SQL로 직접 수정하지 않는다** (계좌 서비스의 정해진 메서드로만 변경, 원장은 추가만)

- **대상**: Grafana 알림 규칙 7개 (`docker/grafana/provisioning/alerting/rules.yml`), DLT 메시지, 원장 대사 불일치
- **알림 규칙 조건, 설정**: [PROJECT_SETUP.md "Slack 장애 알림"](../PROJECT_SETUP.md#slack-장애-알림) (이 문서는 받은 후 할 일만 다룸)
- **명령 표기**: 문서의 `docker-compose {명령}`은 장애가 난 환경에서 실행한다
    - 배포 서버: Session Manager로 접속, `sudo -i`, `cd /opt/ipo` 후 `docker-compose` 자리에
      `docker compose -f docker-compose.yml -f compose.perf.yml -f compose.prod.yml` 사용
    - 로컬: 그대로 실행
    - `docker exec ipo-...` 명령은 두 환경 모두 그대로 (컨테이너 이름 동일)

## 1. 한눈에 보기

> 알림 7개는 두 종류로 나뉜다
> - 상태 알림 5개: 원인을 해결하면 복구 메시지가 발송된다
> - 사건 알림 2개(DLT, ERROR): 복구 메시지가 없어 처리 완료를 사람이 확인한다

| 알림                        | 의미                                   | 먼저 확인                          | 조치 절                                |
|---------------------------|--------------------------------------|--------------------------------|-------------------------------------|
| `ServiceDown`             | 서비스 지표 수집 1분 넘게 실패 (컨테이너 종료, 응답 없음)  | `docker-compose ps`, 해당 서비스 로그 | [2-1](#2-1-servicedown)             |
| `HttpServerErrorRateHigh` | 서비스별 5xx 비율 2분 넘게 5% 초과              | 해당 서비스 ERROR 로그, 의존 서비스 상태     | [2-2](#2-2-httpservererrorratehigh) |
| `HikariConnectionPending` | DB 커넥션 대기 1분 넘게 지속                   | PostgreSQL 실행 쿼리, 잠금 대기        | [2-3](#2-3-hikariconnectionpending) |
| `JvmHeapUsageHigh`        | 힙 사용률 5분 넘게 90% 초과                   | 힙 추이, GC 시간                    | [2-4](#2-4-jvmheapusagehigh)        |
| `QueueAdmissionDown`      | 대기열 입장 처리 1분 넘게 실패 → **청약 신청 전체 차단** | Redis 컨테이너, 알림의 `reason`       | [2-5](#2-5-queueadmissiondown)      |
| `DltMessageArrived` (사건)  | `{토픽}.DLT`에 새 메시지                    | Kafka-UI의 DLT 메시지 헤더           | [3](#3-dlt-메시지-처리)                  |
| `ErrorLogDetected` (사건)   | 서비스 ERROR 로그 발생                      | 해당 서비스 로그의 ERROR 줄             | [4](#4-error-로그별-조치)                |

- **동시 발생**: DLT 전송 대부분은 ERROR 로그도 같이 기록해서 `DltMessageArrived`와 `ErrorLogDetected`가 함께 발생한다 → [3](#3-dlt-메시지-처리)부터 처리
- **원장 대사 불일치**: 별도 알림 없이 `ErrorLogDetected`로 발송된다 → [5](#5-원장-대사-불일치)

## 2. 상태 알림

### 2-1. ServiceDown

| 단계 | 할 일                                                           | 명령                                                                          |
|----|---------------------------------------------------------------|-----------------------------------------------------------------------------|
| 1  | 컨테이너 상태 확인 (`Exited`, `unhealthy`, 재시작 반복)                    | `docker-compose ps`                                                         |
| 2  | 종료 원인 확인 (`APPLICATION FAILED`, `OutOfMemoryError`, `Killed`) | `docker-compose logs --tail 200 {서비스}`                                      |
| 3  | 메모리 상한으로 강제 종료됐는지 확인 (`OOMKilled: true`)                      | `docker inspect {컨테이너} --format '{{.State.OOMKilled}} {{.State.ExitCode}}'` |
| 4  | 원인 해결 후 재시작                                                   | `docker-compose up -d {서비스}`                                                |
| 5  | 복구 확인: 1-2분 안에 `✅ 복구 ServiceDown` 메시지                         | Eureka(8761)에 서비스 등록 확인                                                     |

- **서비스별 영향** (중단 동안)

| 서비스   | 영향                                   | 복구 후 자동 처리                                                                   |
|-------|--------------------------------------|------------------------------------------------------------------------------|
| 계좌    | 증거금 인출, 반환, 정산 처리 지연 (Kafka에 메시지 적재) | 재시작 후 미처리 메시지부터 이어서 처리. 30초 넘게 응답 없는 청약은 청약 서비스가 인출 재요청 (최대 3회, 이후 시간 초과 보상) |
| 청약    | 청약 신청 5xx, 확정, 환불 표시 지연              | 재시작 후 Kafka 메시지 이어서 처리, Outbox 미발행 행 발행                                      |
| 배정    | 마감 후 배정 지연                           | `ipo.closed` 재수신 시 `FAILED`, `RUNNING` 회차를 저장한 시드 그대로 다시 실행                  |
| 게이트웨이 | 외부 요청 전체 실패                          | 이어서 처리할 작업 없음. 중단 동안 실패한 요청은 저장되지 않아 클라이언트가 다시 요청 (청약은 같은 `requestId`로 재전송해도 1건만 저장) |

- **근거**: S6(부하 중 계좌 서비스 강제 종료) 7회 측정 모두 불일치 0건 (PR #143, `loadtest/s6.sh`)

### 2-2. HttpServerErrorRateHigh

| 단계 | 할 일                                                             | 명령                                             |
|----|-----------------------------------------------------------------|------------------------------------------------|
| 1  | 5xx 응답 코드 확인: 500(내부 오류), 502(다른 서비스 호출 실패), 503(서비스 이용 불가)     | Grafana 측정 대시보드 "5xx 응답 (건/초)" 패널, 서비스 로그      |
| 2  | 500이면 예외 확인 (`Unhandled exception`, `Data integrity violation`) | `docker-compose logs {서비스} \| grep -A20 ERROR` |
| 3  | 502면 호출 대상 서비스 상태 확인 (배정 → 청약 확정 목록 조회 Feign)                   | 대상 서비스 `ServiceDown` 여부, 서킷 브레이커 상태            |
| 4  | 503이면 Eureka 등록 대기(재시작 후 최대 30초) 또는 대상 인스턴스 없음                  | Eureka(8761)                                   |

- **청약 신청 5xx**: 클라이언트는 같은 `requestId`로 재전송 가능 (같은 `requestId`는 1건만 저장)

### 2-3. HikariConnectionPending

| 단계 | 할 일                                                              | 명령                |
|----|------------------------------------------------------------------|-------------------|
| 1  | 오래 실행 중인 쿼리, 잠금 대기 확인                                            | 아래 SQL 1          |
| 2  | 잠금 대기면 잠금을 획득한 세션 확인                                             | 아래 SQL 2          |
| 3  | 부하 증가가 원인이면 처리량, 응답 시간 확인 후 기다림 (정식 부하 측정 600건/초에서도 대기 최장 약 25초) | Grafana Hikari 패널 |

- **세션 강제 종료(`pg_terminate_backend`)는 하지 않는다**: 트랜잭션이 롤백되어 Kafka 재시도, 재처리로 이어지고 원인 확인이 어려워진다. 원인 확인 전 판단은 담당자와 상의

<details>
<summary>확인 SQL (슈퍼유저 접속, 조회만)</summary>

접속: `docker exec -it ipo-postgres psql -U postgres -d ipo`

```sql
-- SQL 1. 서비스 계정별 실행 중 세션, 대기 이벤트, 실행 시간 (오래된 순)
SELECT usename, state, wait_event_type, wait_event, now() - xact_start AS xact_age, left (query, 120) AS query
FROM pg_stat_activity
WHERE usename IN ('account_app', 'subscription_app', 'allocation_app') AND state <> 'idle'
ORDER BY xact_start NULLS LAST;

-- SQL 2. 잠금 대기 세션과 잠금을 획득한 세션
SELECT w.pid AS waiting_pid, left (w.query, 80) AS waiting_query, b.pid AS blocking_pid, left (b.query, 80) AS blocking_query, now() - b.xact_start AS blocking_age
FROM pg_stat_activity w
    JOIN LATERAL unnest(pg_blocking_pids(w.pid)) AS bp(pid)
ON true
    JOIN pg_stat_activity b ON b.pid = bp.pid;
```

</details>

### 2-4. JvmHeapUsageHigh

| 단계 | 할 일                                                   | 명령                             |
|----|-------------------------------------------------------|--------------------------------|
| 1  | 힙이 GC 후에도 감소하지 않는지 확인 (계속 증가 = 메모리 누수 의심, 증감 반복 = 부하) | Grafana 힙, GC 패널               |
| 2  | 재시작                                                   | `docker-compose restart {서비스}` |

- **힙 덤프**: 서비스 이미지가 JRE(`eclipse-temurin:21-jre`)라 `jcmd`가 없다. 원인 분석이 필요하면 재시작 전에 담당자와 상의

- **기준값 근거**: 정식 부하 측정(S1 600건/초) 힙 최대 76% (청약 서비스)

### 2-5. QueueAdmissionDown

> 입장 허가가 발급되지 않아 **모든 청약 신청이 차단**된다. 가장 먼저 처리

| 알림 `reason`            | 할 일                                              | 명령                                                            |
|------------------------|--------------------------------------------------|---------------------------------------------------------------|
| `redis_timeout`        | Redis 컨테이너 실행 여부 확인 (멈춘 경우도 이 사유)                | `docker-compose ps redis`, `docker-compose start redis`       |
| `redis_connection`     | Redis 컨테이너, 게이트웨이의 `REDIS_HOST`, `REDIS_PORT` 확인 | `docker-compose logs api-gateway \| grep "대기열 입장 처리 실패"`      |
| `redis_oom`            | Redis 메모리 사용량, `maxmemory` 확인                    | `docker exec ipo-redis redis-cli INFO memory`                 |
| `redis_error`, `other` | 게이트웨이 로그의 예외 확인                                  | `docker-compose logs api-gateway \| grep -A10 "대기열 입장 처리 실패"` |

- **복구 확인**: 1-2분 안에 `✅ 복구 QueueAdmissionDown`

## 3. DLT 메시지 처리

> DLT 메시지는 원인에 따라 두 가지로 나뉜다
> - ① 원인 해결 후 원래 토픽으로 재발행
    -> 이미 처리된 메시지는 이벤트 처리 기록으로 중복 반영되지 않는다
> - ② 재발행해도 같은 결과라 개발자 확인

### 3-1. DLT로 전송되는 경우

| 경우                                              | 동작                        | 헤더 `kafka_dlt-exception-cause-fqcn`                               | 재발행                  |
|-------------------------------------------------|---------------------------|-------------------------------------------------------------------|----------------------|
| 일시 장애 (DB 연결 끊김, 시간 초과 등)                       | 1초 → 2초 → 4초 재시도 3회 후 DLT | `InvalidEventException` 외 (예: `CannotCreateTransactionException`) | **가능**: 원인 해결 후 재발행  |
| 메시지 형식 오류 (JSON 아님, 필수 필드 없음, 값 범위)             | 재시도 없이 바로 DLT             | `...InvalidEventException` + 메시지 "JSON", "필수 필드", "형식"            | **불가**: 보낸 서비스 코드 확인 |
| 데이터 불일치 (증거금 계좌와 요청 계좌 불일치, 이미 정산된 증거금 반환 요청 등) | 재시도 없이 바로 DLT             | `...InvalidEventException` + 서비스별 사유 ([4](#4-error-로그별-조치) 표)     | **불가**: 개발자가 데이터 확인  |

- **헤더 주의**: `kafka_dlt-exception-fqcn`은 항상 감싼 예외(`ListenerExecutionFailedException`)라 판단에 사용하지 않는다. 실제 원인은
  `kafka_dlt-exception-cause-fqcn`, `kafka_dlt-exception-message`
- **DB 장애 시 DLT까지 시간**: 약 2분 (11/16 실측 2분 7초. 시도마다 DB 커넥션 대기 시간 초과 + 재시도 대기 7초)

- **`margin.reserved` (청약 확정)**: 재시도 3회 실패 시 DLT 전에 보상(증거금 반환 요청)을 먼저 시작한다. 보상 시작도 실패하면 DLT (
  `MarginReservedCompensationRecoverer`)
- **순서**: 같은 파티션의 다음 메시지는 재시도 동안 대기하고(블로킹 재시도), DLT 전송 후 이어서 처리된다
- **원본 보존**: DLT 메시지는 원본 JSON, 키 그대로이고 같은 파티션 번호의 `{토픽}.DLT`에 적재된다

### 3-2. 확인

| 단계 | 할 일                                                                                                               | 위치                                              |
|----|-------------------------------------------------------------------------------------------------------------------|-------------------------------------------------|
| 1  | 알림의 DLT 토픽, 건수 확인                                                                                                 | Slack 메시지                                       |
| 2  | 메시지 헤더 확인: `kafka_dlt-exception-cause-fqcn`(원인 예외), `kafka_dlt-exception-message`(사유), `kafka_dlt-original-topic` | Kafka-UI(8989) → Topics → `{토픽}.DLT` → Messages |
| 3  | 본문의 `subscriptionId`, `eventId`와 DLT 메시지의 Partition, Offset 확인 (재발행에 사용)                                          | 같은 화면                                           |
| 4  | 같은 시각 서비스 로그 확인 (`eventId`로 검색)                                                                                   | `docker-compose logs {서비스} \| grep {eventId}`   |
| 5  | [3-1](#3-1-dlt로-전송되는-경우) 표로 재발행 가능 여부 판단                                                                          |                                                 |

### 3-3. 재발행 (원인 해결 후, 재발행 가능한 경우만)

- **방식**: DLT 메시지 1건을 키, 본문 그대로 원래 토픽에 다시 발행 → Consumer가 같은 `eventId`로 처리
- **중복 방지**: 이미 처리된 `eventId`면 이벤트 처리 기록(`processed_event`)으로 반영하지 않는다
- **주의**: 원인을 해결하지 않고 재발행하면 같은 이유로 다시 DLT에 적재된다

```bash
# 변수: DLT 토픽의 파티션, 오프셋 (Kafka-UI 3-2 화면의 Partition, Offset), 원래 토픽
TOPIC=margin.reserve-requested; PARTITION=0; OFFSET=0

# 1) DLT 메시지 1건 확인 (키|본문)
docker exec ipo-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 \
  --topic "$TOPIC.DLT" --partition "$PARTITION" --offset "$OFFSET" --max-messages 1 \
  --formatter-property print.key=true --formatter-property key.separator='|'

# 2) 같은 1건을 원래 토픽으로 재발행 (키가 같아 원래와 같은 파티션으로 발행됨)
docker exec ipo-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 \
  --topic "$TOPIC.DLT" --partition "$PARTITION" --offset "$OFFSET" --max-messages 1 \
  --formatter-property print.key=true --formatter-property key.separator='|' 2>/dev/null \
| docker exec -i ipo-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:29092 \
  --topic "$TOPIC" --reader-property parse.key=true --reader-property key.separator='|'

# 3) DLT에 새 메시지가 적재됐는지 확인 (파티션별 마지막 오프셋. 재발행 전과 같으면 성공)
docker exec ipo-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:29092 --topic "$TOPIC.DLT"
```

- **확인**: 해당 청약 상태가 다음 단계로 변경됐는지 [6](#6-확인-sql) SQL 3, 4로 확인
- **실측 (11/16, 로컬)**: DB 중지 중 인출 요청 1건 → 재시도 3회 후 `margin.reserve-requested.DLT` 적재 → DB 시작 후 재발행 → 계좌 서비스가 처리(
  `processed_event` 1건) → 같은 메시지 다시 재발행 → `중복 수신, 건너뜀` 로그, 잔고 변화 없음
- **DLT 메시지는 삭제하지 않는다**: 처리 기록으로 보관. 재발행한 메시지는 Slack 스레드에 토픽, 파티션, 오프셋, 처리 결과를 기록
- **Git Bash**: `docker` 명령 바로 이전에 `MSYS_NO_PATHCONV=1 ` 추가

## 4. ERROR 로그별 조치

> 로그 문구로 원인을 찾는다
> - "DLT 대상", "DLT로 보냄": [3](#3-dlt-메시지-처리)
> - "운영자 확인 필요": 자동 처리가 멈춘 상태라 아래 표대로 확인

- **조회**: `docker-compose logs --since 10m {서비스} | grep -B2 -A20 ' ERROR '`

| 서비스 | 로그 문구 (일부)                                                                                           | 의미                                            | 조치                                                                                                               |
|-----|------------------------------------------------------------------------------------------------------|-----------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| 계좌  | `[증거금 인출] 잘못된 메시지` / `[증거금 반환] 잘못된 메시지` / `[증거금 정산] 잘못된 메시지`                                         | 메시지 형식 오류, DLT 전송                             | [3](#3-dlt-메시지-처리), 보낸 서비스 확인                                                                                    |
| 계좌  | `[증거금 인출] 기존 증거금과 요청이 달라 DLT 대상`                                                                     | 같은 청약에 금액, 계좌가 다른 인출 요청                       | 개발자 확인 (청약, 증거금 비교: SQL 4)                                                                                       |
| 계좌  | `[증거금 반환] ... 계좌와 요청 계좌가 달라` / `이미 정산된 증거금` / `처리할 수 없는 증거금 상태`                                      | 반환 요청과 증거금 불일치                                | 개발자 확인 (SQL 4). 청약은 `COMPENSATING`, `CANCEL_REQUESTED`에 머무름                                                      |
| 계좌  | `[증거금 정산] DLT 대상`                                                                                    | 정산 요청이 증거금, 배정과 불일치 (없음, 초과, 계좌 불일치)          | 개발자 확인 (SQL 4)                                                                                                   |
| 계좌  | `[원장 대사] 불일치` / `[원장 대사] 실행 실패`                                                                      | 원장 대사 결과                                      | [5](#5-원장-대사-불일치)                                                                                                |
| 청약  | `정상적으로는 오지 않는 ... 받음`                                                                                | 현재 상태에서 받을 수 없는 이벤트 (순서 역전, 중복)               | 청약 상태 확인 (SQL 3). DLT로 간 경우 [3](#3-dlt-메시지-처리)                                                                   |
| 청약  | `... 맞지 않음. DLT로 보냄` / `이미 저장한 ... 결과와 다름`                                                           | 배정, 정산 결과가 청약과 불일치                            | 개발자 확인 (SQL 3, 4)                                                                                                |
| 청약  | `청약 확정에 실패해서 보상을 시작함` / `시간 초과로 보상을 시작함`                                                             | 보상 시작 (자동 처리 중)                               | 조치 없음. 반환 완료(`CANCELLED`)까지 SQL 3으로 확인                                                                           |
| 청약  | `반환 재요청이 최대 횟수에 도달했지만 계좌 응답이 없음. ... 운영자 확인 필요`                                                      | 증거금 반환 응답 없음, 자동 처리 중단                        | `margin.void-requested.DLT`, `margin.voided.DLT` 확인 → 계좌 서비스 상태 확인 → 원인 해결 후 [3-3](#3-3-재발행-원인-해결-후-재발행-가능한-경우만) |
| 청약  | `공모주 마감이 대기 시간 뒤 ... 넘게 막힘. ... 운영자 확인 필요`                                                           | 처리 중 청약(`PENDING` 등)이 남아 마감 불가                | SQL 5로 남은 청약 확인 → `margin.*.DLT` 확인 → 원인 해결 후 재발행                                                                |
| 청약  | `응답 시간 초과 처리 중 오류` / `반환 재요청 처리 중 오류` / `공모주 마감 처리 중 오류`                                             | 스케줄러 1회 실패, 다음 주기에 재시도                        | 같은 오류가 반복되면 예외 확인                                                                                                |
| 청약  | `배정 전체 수량이 일반청약 물량보다 많음` / `배정 청약 건수가 확정 청약 수와 다름`                                                   | 배정 결과 검증 실패                                   | 개발자 확인 (배정 회차, 추첨 시드)                                                                                            |
| 배정  | `배정 회차를 FAILED로 기록하지 못했습니다`                                                                          | 배정 실패 + 실패 기록도 실패                             | 조치 없음. 다음 재시도에서 `RUNNING` 회차를 다시 실행                                                                              |
| 공통  | `Outbox 발행 연속 실패`                                                                                    | 같은 Outbox 행 발행 5회 연속 실패 (발행은 계속 시도)           | Kafka 상태 확인 (`docker-compose ps kafka`), SQL 6                                                                   |
| 공통  | `Outbox 발행 완료 표시 실패`                                                                                 | 발행 후 DB 기록 실패 → 다음 실행에서 재발행 (Consumer가 중복 제거) | DB 상태 확인                                                                                                         |
| 공통  | `Unhandled exception` / `Data integrity violation`                                                   | API 처리 중 예외 (500)                             | [2-2](#2-2-httpservererrorratehigh)                                                                              |
| 공통  | `SqlExceptionHelper ... Connection is not available` / `Unexpected error occurred in scheduled task` | DB 연결 실패 (Consumer, 스케줄러 모두 실패)               | `docker-compose ps postgres` → 시작 후 해당 시간 DLT 확인 ([3](#3-dlt-메시지-처리))                                            |

- **ERROR가 없는 DLT**: 서킷 브레이커가 열려 호출하지 못한 경우 등은 ERROR 로그 없이 DLT로 전송된다 → `DltMessageArrived`만 발생

## 5. 원장 대사 불일치

> 원장 대사(매일 04:00, `ledger.reconciliation.cron`)가 불일치를 찾으면 ERROR로 기록한다
> - **자동으로 수정하지 않는다**
> - 대상 계좌의 원장을 조회해 원인을 확인한다

| 검사                      | 확인 내용                    | 불일치 시 의심 원인                       |
|-------------------------|--------------------------|-----------------------------------|
| `MARGIN_BALANCE`        | 청약증거금 = PENDING 증거금 합계   | 인출, 정산, 반환 중 잔고만 변경되고 증거금 상태 미반영  |
| `LEDGER_SUM`            | 예수금, 청약증거금 = 원장 금액 합계    | 원장 없이 잔고 변경                       |
| `MARGIN_PENDING_LEDGER` | 증거금마다 인출 원장 1줄, 금액 = 증거금 | 중복 인출, 인출 원장 누락                   |
| `MARGIN_STATUS_LEDGER`  | 증거금 상태별 정산, 환불, 반환 원장 금액 | 상태와 원장 불일치 (예: POSTED인데 환불 원장 없음) |
| `LEDGER_MARGIN_ACCOUNT` | 원장 계좌 = 연결된 증거금의 계좌      | 다른 계좌의 증거금에 원장 연결                 |

| 단계 | 할 일                                | 명령                                                        |
|----|------------------------------------|-----------------------------------------------------------|
| 1  | 로그에서 검사 이름, 대상 ID, 상세(기대값, 실제값) 확인 | `docker-compose logs account-service \| grep '\[원장 대사\]'` |
| 2  | 대상 계좌의 원장 전체를 시간순으로 조회             | SQL 7                                                     |
| 3  | 원인 거래 확인 후 개발자 보고                  |                                                           |

- **수정 방법**: 잔고를 SQL로 바꾸지 않는다. 정정은 반대 거래 원장 추가로만 한다 (`PROJECT_SETUP.md` 7장). 정정 기능은 아직 없어 개발자 판단 필요
- **실행 실패** (`[원장 대사] 실행 실패`): 다음 실행 시각에 다시 실행. DB 연결 상태 확인

## 6. 확인 SQL

> 모두 조회만 한다
> - 접속: 슈퍼유저 (`docker exec -it ipo-postgres psql -U postgres -d ipo`)
> - 부하 테스트 정합성 검사 전체: `loadtest/sql/verify.sql` (22종)

<details>
<summary>SQL 3-7 (청약 상태, 청약과 증거금 비교, 마감 지연, Outbox 미발행, 계좌 원장)</summary>

```sql
-- SQL 3. 청약 1건의 상태 (DLT 본문의 subscriptionId)
SELECT subscription_id,
       ipo_id,
       account_id,
       status,
       reject_reason,
       cancel_reason,
       margin_amount,
       margin_id,
       received_at,
       confirmed_at,
       updated_at
FROM subscription.subscription
WHERE subscription_id = '<subscriptionId>';

-- SQL 4. 청약과 증거금 비교 (금액, 계좌, 상태)
SELECT s.status     AS sub_status,
       s.account_id AS sub_account,
       s.margin_amount,
       m.margin_id,
       m.account_id AS margin_account,
       m.status     AS margin_status,
       m.amount,
       m.posted_amount
FROM subscription.subscription s
         LEFT JOIN account.margin m ON m.subscription_id = s.subscription_id
WHERE s.subscription_id = '<subscriptionId>';

-- SQL 5. 마감되지 않은 공모주의 처리 중 청약 (공모주 마감 지연 ERROR의 ipoId)
SELECT status, count(*), min(updated_at) AS oldest
FROM subscription.subscription
WHERE ipo_id = '<ipoId>'
  AND status IN ('PENDING', 'COMPENSATING', 'CANCEL_REQUESTED')
GROUP BY status;

-- SQL 6. Outbox 미발행 행 (서비스별 건수, 가장 오래된 행, 최대 실패 횟수)
SELECT 'account' AS svc, topic, count(*), min(created_at), max(retry_count)
FROM account.outbox
WHERE published_at IS NULL
GROUP BY topic
UNION ALL
SELECT 'subscription', topic, count(*), min(created_at), max(retry_count)
FROM subscription.outbox
WHERE published_at IS NULL
GROUP BY topic;

-- SQL 7. 계좌 1개의 원장 전체 (원장 대사 대상 accountId)
SELECT created_at, type, amount, cash_balance_after, margin_balance_after, margin_id
FROM account.ledger_entry
WHERE account_id = '<accountId>'
ORDER BY created_at, ledger_entry_id;
```

</details>

## 7. 한계

- **DLT 재발행 도구 없음**: 1건씩 명령으로 재발행. 여러 건은 반복 실행
- **정정 기능 없음**: 원장 대사 불일치, 데이터 불일치 DLT는 개발자가 원인 확인 후 별도 조치
- **인스턴스 2개 이상**: 원장 대사 알림 중복, Outbox 중복 발행 가능 (#149 진행 중)
- **배포 환경 확인**: 이 문서의 명령은 로컬 Docker에서 확인함. 배포 환경은 compose 파일 접두어만 다름
