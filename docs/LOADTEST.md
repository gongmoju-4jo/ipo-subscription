# 부하 테스트 실행 가이드

청약 API(`POST /api/v1/subscriptions`)를 Gateway 경유로 부하 테스트하는 환경과 실행 절차 초안입니다. 스크립트는 `loadtest/`, 자원 고정은 `compose.perf.yml`에 있습니다.

## 목차

1. [구성](#1-구성)
2. [로컬과 컨테이너](#2-로컬과-컨테이너)
3. [준비 (한 번)](#3-준비-한-번)
4. [회차 실행 (매 회차)](#4-회차-실행-매-회차)
5. [시나리오](#5-시나리오)
6. [결과 파일](#6-결과-파일)
7. [환경변수](#7-환경변수)
8. [주의](#8-주의)

---

## 1. 구성

| 파일 | 역할 |
| --- | --- |
| `compose.perf.yml` | 컨테이너별 CPU, 메모리 상한과 JVM 힙 고정, 슬로우 쿼리 설정 |
| `loadtest/seed/seed_users.py` | 회원, 계좌, 예수금, 로그인 토큰 CSV 생성 |
| `loadtest/seed/new_ipo.sh` | 회차용 새 공모주 삽입 (`--closed`는 마감된 공모주) |
| `loadtest/precheck.sh` | 회차 시작 전 점검 (Eureka, 토큰 나이, Outbox, DLT, Consumer lag) |
| `loadtest/run-k6.sh` | 시나리오 실행, 원본 저장 |
| `loadtest/k6/` | 시나리오 스크립트 `s0.js` ~ `s4.js`, 공통 코드 `lib.js` |
| `loadtest/verify/check_requests.py` | 요청 로그 분석 (결과 분류, 재전송 응답 일치) |
| `loadtest/verify.sh`, `loadtest/sql/verify.sql` | 정합성 SQL 10종 실행 |
| `loadtest/sql/slow-queries.sql` | 슬로우 쿼리 확인 |

모니터링은 `docker-compose up`에 함께 올라옵니다: Prometheus `http://localhost:9090`, Grafana `http://localhost:3000` (계정 `admin`, 비밀번호는 `.env`의 `GRAFANA_ADMIN_PASSWORD`). Grafana에 "측정 대시보드"가 자동으로 등록됩니다.

스크립트는 bash로 실행합니다 (Windows는 Git Bash). Windows Git Bash에서 컨테이너 안 경로(`/loadtest/...`)가 바뀌지 않도록 `export MSYS_NO_PATHCONV=1`을 먼저 실행합니다.

## 2. 로컬과 컨테이너

**측정 대상 서비스는 항상 Docker(compose)로 띄웁니다.** IntelliJ로 띄운 서비스는 Prometheus 수집 대상이 아니라서 측정에 쓰지 않습니다. 구분이 생기는 곳은 부하를 거는 스크립트(k6, Python)를 어디서 실행하느냐입니다.

| | 로컬 (내 PC에서 실행) | 컨테이너 (compose 네트워크 안에서 실행) |
| --- | --- | --- |
| k6 | k6를 설치하고 실행 (`K6_MODE=local`) | `grafana/k6` 이미지로 실행 (`K6_MODE=container`) |
| Gateway 주소 | `http://localhost:8080` | `http://api-gateway:8080` (호스트 포트를 거치지 않음) |
| 회원 시드 (Python) | Python 3이 설치돼 있으면 그대로 실행 | `python:3.12-slim` 컨테이너로 실행 |
| 자원 상한 | 없음 (내 PC 자원을 그대로 씀) | `K6_CPUS`, `K6_MEMORY`로 k6 컨테이너 상한 설정 |

- `K6_MODE`를 주지 않으면(`auto`) k6가 설치돼 있을 때 설치형, 없으면 컨테이너로 실행합니다.
- 어느 쪽이든 서비스와 같은 머신에서 실행하면 k6도 CPU를 씁니다. **어디서 실행했는지는 측정 환경 기록에 적습니다.** 개선 전후 비교는 같은 방식으로 합니다.
- 설치형 k6로 실행하는 경로는 아직 검증하지 못했습니다. 컨테이너 실행을 기준으로 검증했습니다.

## 3. 준비 (한 번)

### 3-1. 기동

```bash
cp .env.example .env     # GRAFANA_ADMIN_PASSWORD에 직접 정한 값을 넣는다 (기본값 없음, 비어 있으면 Grafana가 시작되지 않는다)
./gradlew build -x test && docker-compose up -d --build
```

회원 시드는 BCrypt 때문에 CPU를 많이 써서, 자원 상한을 건 상태에서는 1,000명에 2분 넘게 걸립니다. **시드는 상한 없이 먼저 하고, 시드가 끝난 뒤 상한을 적용합니다.**

### 3-2. 회원 시드

회원 CSV(`loadtest/data/*.csv`)에는 로그인 토큰이 들어 있어서 커밋하지 않습니다 (`.gitignore` 대상).

| 용도 | 명령 (로컬 실행) |
| --- | --- |
| S0, S1, S2 | `python loadtest/seed/seed_users.py --run-id r1` |
| S3 (100번~199번 회원에게 두 번째 계좌) | `python loadtest/seed/seed_users.py --run-id r1 --second-account-count 100` |
| S4 (10% 잔액 부족, 별도 묶음) | `python loadtest/seed/seed_users.py --run-id r1 --prefix lb --low-balance-ratio 0.1 --out loadtest/data/users-s4.csv` |

S0~S3는 같은 묶음(`loadtest/data/users.csv`)을 씁니다. S3를 하려면 `--second-account-count 100`으로 시드해야 합니다.

컨테이너로 실행하려면 같은 명령을 `python:3.12-slim`으로 감쌉니다.

```bash
export MSYS_NO_PATHCONV=1
docker run --rm --network ipo-subscription_default -v "$(pwd -W 2>/dev/null || pwd)/loadtest:/loadtest" python:3.12-slim \
  python /loadtest/seed/seed_users.py --base-url http://api-gateway:8080 --run-id r1 --out /loadtest/data/users.csv
```

- 회원은 회차마다 새로 만들지 않고 재사용합니다. 가입, 계좌 개설은 이미 있으면 그대로 쓰고, 예수금은 `--run-id`마다 한 번 입금합니다 (같은 run-id로 다시 실행해도 중복 입금되지 않습니다).
- 토큰은 1시간 만료입니다. 만료되면 `--refresh-tokens`로 토큰만 다시 발급합니다.
- 네트워크 이름은 `{프로젝트 폴더명}_default`입니다 (`docker network ls`로 확인).

### 3-3. 자원 상한 적용

```bash
docker-compose -f docker-compose.yml -f compose.perf.yml up -d
```

- 컨테이너가 상한과 함께 다시 만들어집니다 (DB, Kafka 데이터는 유지).
- 상한과 JVM 힙(G1 명시)은 `compose.perf.yml` 맨 위 표를 봅니다. 머신에 맞게 고치고, 쓴 값은 측정 환경 기록에 적습니다.
- `kafka-ui`는 이 구성에서 기본 실행에서 빠집니다 (필요하면 `docker-compose -f docker-compose.yml -f compose.perf.yml up -d kafka-ui`).

## 4. 회차 실행 (매 회차)

회차마다 새 공모주를 씁니다. 같은 공모주를 다시 쓰면 회원 전원이 이중청약으로 거절됩니다.

```bash
RUN=S0-r1-1102                               # run_id: 시나리오-회차-날짜
IPO=$(loadtest/seed/new_ipo.sh)              # 새 공모주 ID (S2는 --closed)

loadtest/precheck.sh --run-id $RUN           # 1. 사전 점검 (실패하면 해결한 뒤 진행)
docker exec -i ipo-postgres psql -U postgres -d ipo -v reset=1 -f - < loadtest/sql/slow-queries.sql   # 2. 슬로우 쿼리 통계 초기화
loadtest/run-k6.sh s0 --ipo $IPO --run-id $RUN                                                        # 3. 실행
python loadtest/verify/check_requests.py loadtest/results/$RUN/requests.log --expect-requests 1000    # 4. 요청 로그 분석
loadtest/verify.sh --ipo $IPO --run-id $RUN                                                           # 5. 정합성 SQL
docker exec -i ipo-postgres psql -U postgres -d ipo -f - < loadtest/sql/slow-queries.sql              # 6. 슬로우 쿼리 확인
```

- 2, 6번은 `compose.perf.yml`로 띄웠을 때만 동작합니다 (`pg_stat_statements` 필요).
- 3번에서 시나리오(`s0`~`s4`)와 회원 CSV를 바꿉니다: `--users data/users-s4.csv`, 값 조정은 `-e KEY=VALUE` (예: `-e RATE=300 -e DURATION=60s`).
- 3번 종료 코드가 99이면 합격 기준을 어긴 것입니다 (6장).
- 5번의 정합성 SQL 대부분은 Consumer가 구현된 뒤에야 의미 있는 값이 나옵니다. 그 전에는 청약 행이 생기지 않습니다.
- 5번 전에 Kafka Consumer lag이 0이고 `PENDING`이 0건인지 확인합니다 (Kafka-UI Consumers 탭 또는 `kafka-consumer-groups.sh --describe`).
- 측정 후 Grafana "측정 대시보드"에서 측정 구간을 캡처합니다.

같은 조건으로 최소 3회 반복하고, 첫 회차는 워밍업(JVM, 커넥션 풀)으로 버립니다. 개선 전후 비교는 한 번에 하나만 바꿉니다.

## 5. 시나리오

| 시나리오 | 요청 구성 | 공모주 | 회원 CSV | HTTP 기대 |
| --- | --- | --- | --- | --- |
| S0 | 회원 1,000명이 각 1건 (100명 × 10회, 1초에 걸쳐 시작) | 새 공모주 | `users.csv` | 전부 202 |
| S1 | 일정 속도로 유지 (`RATE` 기본 200건/초, `DURATION` 기본 60초) | 새 공모주 | `users.csv` | 전부 202 |
| S2 | S0과 같은 구성으로 마감된 공모주에 요청 | `new_ipo.sh --closed` | `users.csv` | 전부 422 `SUBSCRIPTION_CLOSED` |
| S3 | 정상 800 + 같은 requestId 재전송 100 + 두 번째 계좌 100 | 새 공모주 | `users.csv` (두 번째 계좌 시드) | 전부 202, 재전송 응답의 `subscriptionId`가 원래 요청과 같음 |
| S4 | S0과 같은 구성, 10% 회원이 잔액 부족 | 새 공모주 | `users-s4.csv` | 전부 202 (잔액 부족 판정은 Consumer) |

- **S1과 S2의 조건(요청 속도, 유지 시간, 요청 구성)은 임시 값입니다.** 첫 측정 전에 확정하고, 측정을 시작한 뒤에는 바꾸지 않습니다.
- S3의 비율은 가정한 값이라 `S3_RESEND`, `S3_SECOND`, `S3_SECOND_FROM`으로 바꿀 수 있습니다.
- S0~S4 모두 `VUS`, `ITERATIONS`로 규모를 줄일 수 있습니다 (예: 스모크는 `-e VUS=1 -e ITERATIONS=1`).

## 6. 결과 파일

`loadtest/results/{run_id}/`에 저장됩니다.

| 파일 | 내용 |
| --- | --- |
| `summary.json` | k6 요약 (처리량, 평균, p90, p95, p99, 오류율, 임계값 판정) |
| `requests.log` | 요청마다 requestId, subscriptionId, 상태 코드 |
| `precheck.txt` | 사전 점검 결과 (DLT 메시지 수 포함) |
| `sql_result.txt` | 정합성 SQL 결과 |

- 합격 기준은 처리량 200건/초 이상, 평균 500ms 이하, 오류율 1% 미만이고, 목표 기준은 400건/초 이상, 평균 250ms 이하, p95 500ms 이하, 오류 0입니다. 기본은 합격 기준이고 `-e THRESHOLD=goal`로 목표 기준을 씁니다. 기준은 측정 전에 정하고 측정 후에는 바꾸지 않습니다.
- 기준을 어기면 종료 코드 99입니다. **`summary.json`의 `thresholds`는 `true`가 위반, `false`가 통과**입니다.
- 오류율은 시스템 오류(5xx, 타임아웃, 연결 실패)만 셉니다. 업무 거절(이중청약, 잔액 부족, 마감)은 오류율에서 제외하고 `check_requests.py`의 결과 분류로 봅니다.
- 결과 폴더를 레포에 올릴지는 정해지지 않았습니다 (`.gitignore`에 넣지 않았습니다).

## 7. 환경변수

| 변수 | 기본값 | 쓰는 곳 |
| --- | --- | --- |
| `PG_CONTAINER` | `ipo-postgres` | `new_ipo.sh`, `verify.sh`, `precheck.sh` |
| `KAFKA_CONTAINER` | `ipo-kafka` | `precheck.sh` |
| `EUREKA_URL` | `http://localhost:8761` | `precheck.sh` |
| `K6_MODE` | `auto` | `run-k6.sh` (`auto`, `local`, `container`) |
| `K6_IMAGE` | `grafana/k6:1.3.0` | `run-k6.sh` (컨테이너 실행) |
| `K6_NETWORK` | `ipo-subscription_default` | `run-k6.sh` (컨테이너 실행, compose 네트워크 이름) |
| `K6_CPUS`, `K6_MEMORY` | 없음 | `run-k6.sh` (컨테이너 실행, 예: `1.0`, `512m`) |
| `BASE_URL` | 로컬 `http://localhost:8080`, 컨테이너 `http://api-gateway:8080` | `run-k6.sh`, `seed_users.py` |
| `LOADTEST_PASSWORD` | `loadtest1234` | `seed_users.py` (시드 회원의 비밀번호) |

## 8. 주의

- 측정 중에는 다른 무거운 프로그램(브라우저, 빌드, IDE 인덱싱)을 끕니다. IntelliJ를 켜 두었다면 서비스는 Docker에서만 띄우고, 노트북은 전원을 연결합니다.
- 토큰은 발급 후 1시간 안에 모든 회차를 끝냅니다. 넘으면 401 `TOKEN_EXPIRED`가 섞입니다 (`precheck.sh`가 50분을 넘으면 알려 줍니다).
- 한 요청은 한 회원입니다. 같은 회원이 같은 공모주에 여러 번 요청하면 이중청약 거절이 섞입니다.
- 결과를 쓸 때는 "로컬 단일 머신, Docker, 인스턴스 1개" 같은 환경 한계를 같이 씁니다. 측정하지 않은 값은 "측정하지 않음"이라고 씁니다.
