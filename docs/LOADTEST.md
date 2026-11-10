# 부하 테스트 실행 가이드

청약 API(`POST /api/v1/subscriptions`)를 Gateway 경유로 부하 테스트하는 환경과 실행 절차 초안입니다. 스크립트는 `loadtest/`, 자원 고정은 `compose.perf.yml`에 있습니다.

## 목차

1. [구성](#1-구성)
2. [로컬과 컨테이너](#2-로컬과-컨테이너)
3. [준비 (한 번)](#3-준비-한-번)
4. [정식 측정 (official.sh)](#4-정식-측정-officialsh)
5. [회차를 하나씩 실행](#5-회차를-하나씩-실행)
6. [시나리오](#6-시나리오)
7. [결과 파일](#7-결과-파일)
8. [환경변수](#8-환경변수)
9. [스크립트 테스트](#9-스크립트-테스트)
10. [주의](#10-주의)

---

## 1. 구성

| 파일 | 역할 |
| --- | --- |
| `compose.perf.yml` | 컨테이너별 CPU, 메모리 상한과 JVM 힙 고정, 슬로우 쿼리 설정 |
| `loadtest/official.sh` | 정식 측정 전체 실행 (빌드, 배포, 버전 기록, S0, S1, 후처리, 판정). 4장 |
| `loadtest/g2_run.sh` | 대량 환불(마감 → 배정 → 정산 → 환불) 회차 실행, 수집, 검증, 판정 (확정 청약 스냅샷을 순서대로). 4장 끝 |
| `loadtest/verify/g2_estimate.py` | 대량 환불 1만, 10만 건 결과로 212만 건 소요 시간 추정과 속도 판정 (`추정.md`). 4장 끝 |
| `loadtest/verify/judge.py` | 정식 측정의 G1, G2 판정, S3, S4 정합성 판정과 S0 대비 (`판정.md`) |
| `loadtest/baseline.sh` | 기준 상태 스냅샷 저장(`save`), 복원(`restore`, DB와 Kafka), 확인(`check`), 덤프(`dump`). `ALLOW_SUBSCRIPTIONS=1 save`는 확정 청약이 쌓인 DB 저장. 3-4절 |
| `loadtest/verify/warmup.py` | 정식 측정 워밍업 판정 (시간 구간별 응답 시간 추세의 기울기) |
| `loadtest/verify/docker_stats.py` | 정식 측정 중 기록한 docker stats를 CSV와 실행별 컨테이너 CPU, 메모리 표(`자원.md`)로 정리 |
| `loadtest/verify/consumer_stats.py` | 정식 측정이 끝난 뒤 따로 실행. S1의 증거금 인출 Consumer 처리량, 랙 추이, 오류율, 병목 의심 단계 (`인출.md`, `인출.csv`, `인출-prometheus.json`). `python loadtest/verify/consumer_stats.py --batch <이름>` |
| `loadtest/tests/` | `official.sh`, `g2_run.sh`, `baseline.sh`, `seed_users_sql.sh`, `g2_ipo.sh`, `g2-quantity.js`, `g2_estimate.py`, `judge.py`, `warmup.py`, `docker_stats.py`, `consumer_stats.py`, `autovacuum.py`, `synthetic/validate.py` 테스트 (Docker, DB 없이 실행). 9장 |
| `loadtest/synthetic/` | S3, S4 Synthetic Data: LLM 프롬프트와 응답 원문, 행동 CSV, 생성 코드, 검증(`validate.py`). 6장, `loadtest/synthetic/README.md` |
| `loadtest/seed/seed_users.py` | 회원, 계좌, 예수금, 로그인 토큰 CSV 생성 (`--behavior`로 S3 두 번째 계좌, S4 입금액을 행동 CSV에서 읽음) |
| `loadtest/seed/seed_users_sql.sh` | 회원, 계좌, 예수금 입금을 SQL 한 트랜잭션으로 넣기 (회원이 많을 때. 토큰은 `seed_users.py --refresh-tokens`). 3-2절 |
| `loadtest/seed/new_ipo.sh` | 회차용 새 공모주 삽입 (`--closed`는 마감된 공모주, `--far`는 종료 시각이 10년 뒤인 공모주, `--count N`은 N개를 SQL 한 번으로) |
| `loadtest/seed/g2_ipo.sh` | 대량 환불 스냅샷용 워밍업 공모주 W, 측정 공모주 M 삽입 (건수, 케이스로 일반청약 물량 계산, 다른 OPEN 공모주 종료 시각을 먼 미래로). 3-4절 |
| `loadtest/precheck.sh` | 회차 시작 전 점검 (Eureka, 토큰 나이, Outbox, DLT, Consumer lag) |
| `loadtest/run-k6.sh` | 시나리오 실행, 원본 저장 |
| `loadtest/s2.sh` | S2 마감 경계 한 회차 실행 (워밍업, 공모주 준비, 대상 공모주 마감 시각 설정, 실행, 판정). 6장 |
| `loadtest/verify/s2_judge.py` | S2 한 회차 판정 (회차 유효성, `verify.sql` 20~22번 결과, 202인데 청약 행이 없는 요청. `s2-judge.txt`) |
| `loadtest/k6/` | 시나리오 스크립트 `s0.js` ~ `s4.js`, 공통 코드 `lib.js`, 대량 환불 스냅샷용 청약 시드 `g2.js`(수량은 `g2-quantity.js`) |
| `loadtest/verify/check_requests.py` | 요청 로그 분석 (결과 분류, 재전송 응답 일치) |
| `loadtest/verify.sh`, `loadtest/sql/verify.sql` | 정합성 SQL 22종 실행 (11~15는 배정, 정산 뒤 검사, 16~19는 재전송, 이중청약, 잔액 부족 검사, 20~22는 마감된 공모주 검사로 마감 전이면 20, 22번 건너뜀. `--each-dir`는 공모주 여러 개를 psql 한 번으로 검사하고, 전체 계좌 기준 검사 2개는 첫 공모주만) |
| `loadtest/report.py` | 회차 보고서 생성 (k6 요약, 요청 로그, 같은 시간대의 Prometheus 지표를 `report.md`로) |
| `loadtest/stage_table.py` | S1 단계별 표 생성 (목표 속도, 실제 처리량, 지연, 실패율) |
| `loadtest/find-limit.sh` | S1 요청 속도를 정하기 위한 한계 탐색 (속도를 올려 가며 짧게 실행) |
| `loadtest/sql/slow-queries.sql` | 슬로우 쿼리 확인 |
| `loadtest/verify/autovacuum.py` | 정식 측정 실행별 autovacuum, autoanalyze 정리 (`autovacuum.md`) |

모니터링은 `docker-compose up`에 함께 올라옵니다: Prometheus `http://localhost:9090`, Grafana `http://localhost:3000` (계정 `admin`, 비밀번호는 `.env`의 `GRAFANA_ADMIN_PASSWORD`). Grafana에 "측정 대시보드"가 자동으로 등록됩니다.

스크립트는 bash로 실행합니다 (Windows는 Git Bash, macOS 기본 bash 3.2, Linux). Windows Git Bash에서 컨테이너 안 경로(`/loadtest/...`)가 바뀌지 않도록 `export MSYS_NO_PATHCONV=1`을 먼저 실행합니다 (`official.sh`는 스스로 설정합니다).

## 2. 로컬과 컨테이너

**측정 대상 서비스는 항상 Docker(compose)로 띄웁니다.** IntelliJ로 띄운 서비스는 Prometheus 수집 대상이 아니라서 측정에 쓰지 않습니다. 구분이 생기는 곳은 부하를 거는 스크립트(k6, Python)를 어디서 실행하느냐입니다.

| | 로컬 (내 PC에서 실행) | 컨테이너 (compose 네트워크 안에서 실행) |
| --- | --- | --- |
| k6 | k6를 설치하고 실행 (`K6_MODE=local`) | `grafana/k6` 이미지로 실행 (`K6_MODE=container`) |
| Gateway 주소 | `http://localhost:8080` | `http://api-gateway:8080` (호스트 포트를 거치지 않음) |
| 회원 시드, 보고서 (Python) | Python 3이 설치돼 있으면 그대로 실행 | `python:3.12-slim` 컨테이너로 실행 |
| Prometheus 주소 (보고서) | `http://127.0.0.1:9090` | `PROMETHEUS_URL=http://prometheus:9090` |
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

기동 직후 약 30초는 Gateway가 서비스를 찾지 못해 503이 날 수 있습니다. 시드 스크립트는 최대 90초까지 기다리며 다시 시도합니다.

회원 시드는 BCrypt 때문에 CPU를 많이 써서, 자원 상한을 건 상태에서는 1,000명에 2분 넘게 걸립니다. **시드는 상한 없이 먼저 하고, 시드가 끝난 뒤 상한을 적용합니다.**

### 3-2. 회원 시드

회원 CSV(`loadtest/data/*.csv`)에는 로그인 토큰이 들어 있어서 커밋하지 않습니다 (`.gitignore` 대상).

| 용도 | 명령 (로컬 실행) |
| --- | --- |
| S0, S1, S2 | `python loadtest/seed/seed_users.py --run-id r1` |
| S3 (행동 CSV의 `SECOND_ACCOUNT` 투자자에게 두 번째 계좌) | `python loadtest/seed/seed_users.py --run-id s34-acc2 --behavior loadtest/synthetic/s3-behavior.csv --amount 100000000` |
| S4 (잔액 부족 묶음, 투자자별 입금액은 행동 CSV) | `python loadtest/seed/seed_users.py --run-id s34-lb --prefix lb --behavior loadtest/synthetic/s4-behavior.csv --out loadtest/data/users-s4.csv` |

S0~S3는 같은 묶음(`loadtest/data/users.csv`)을 씁니다. S3를 하려면 S3 행동 CSV로 한 번 더 시드해 두 번째 계좌를 엽니다.

- `--behavior`는 시드 전에 `synthetic/validate.py` 규칙으로 행동 CSV를 검사하고, 위반이 있으면 회원을 만들지 않고 멈춥니다.
- S3 시드는 두 번째 계좌에 `--amount`를 넣습니다. 기본 묶음의 다른 계좌와 같은 금액(1억)으로 넣어야 정식 측정의 최소 예수금 확인에서 멈추지 않습니다. 새 `--run-id`라서 첫 계좌에도 같은 금액이 한 번 더 입금됩니다.
- S4 시드는 투자자마다 행동 CSV의 입금액을 넣습니다 (`--amount`는 쓰지 않음). 0번부터 `--users`-1번까지 모든 회원의 입금액이 CSV에 있어야 합니다.

컨테이너로 실행하려면 같은 명령을 `python:3.12-slim`으로 감쌉니다.

```bash
export MSYS_NO_PATHCONV=1
docker run --rm --network ipo-subscription_default -v "$(pwd -W 2>/dev/null || pwd)/loadtest:/loadtest" python:3.12-slim \
  python /loadtest/seed/seed_users.py --base-url http://api-gateway:8080 --run-id r1 --out /loadtest/data/users.csv
```

- 회원은 회차마다 새로 만들지 않고 재사용합니다. 가입, 계좌 개설은 이미 있으면 그대로 쓰고, 예수금은 `--run-id`마다 한 번 입금합니다 (같은 run-id로 다시 실행해도 중복 입금되지 않습니다).
- 토큰은 1시간 만료입니다. 만료되면 `--refresh-tokens`로 토큰만 다시 발급합니다.
- 네트워크 이름은 `{프로젝트 폴더명}_default`입니다 (`docker network ls`로 확인).

#### SQL로 회원 넣기 (회원이 많을 때)

API 가입은 회원마다 요청 4번(가입, 로그인, 계좌, 입금)이라 10만 명이면 몇 시간 걸립니다. `seed_users_sql.sh`는 회원, 계좌, 입금 원장을 SQL 한 트랜잭션으로 넣고, 토큰만 `seed_users.py --refresh-tokens`로 받습니다.

```bash
# 1. 회원, 계좌, 입금 (토큰 없는 CSV를 쓴다)
bash loadtest/seed/seed_users_sql.sh --users 100000 --prefix lt --bcrypt-cost 4 --out loadtest/data/users.csv
# 2. 토큰 발급 (CSV의 token 열을 채운다. 1시간 만료라 측정 직전에)
python loadtest/seed/seed_users.py --run-id <id> --prefix lt --users 100000 --out loadtest/data/users.csv --refresh-tokens
```

| 옵션 | 기본값 | 뜻 |
| --- | --- | --- |
| `--users`, `--start` | `1000`, `0` | 번호 `--start`부터 `--users`-1까지 넣는다 (기존 1,000명 뒤에 더하려면 `--start 1000 --users 10000`) |
| `--prefix` | `lt` | loginId 접두사. loginId는 `seed_users.py`와 같은 `<접두사><번호 4자리>` (1만 이상은 자리수 그대로) |
| `--deposits` | `30000000,70000000` | 계좌마다 넣는 입금액 (쉼표로 나눈 만큼 입금 원장이 생긴다) |
| `--bcrypt-cost` | `10` | 비밀번호 BCrypt 비용 (4 ~ 12). 10은 API 가입과 같다 |
| `--out` | `loadtest/data/users.csv` | 이 접두사의 회원 전체(DB 기준)를 CSV로 쓴다. token 열은 비어 있다 |

- 회원이 많으면 `--bcrypt-cost 4`로 넣습니다. 로그인(토큰 발급)이 BCrypt 비교라 계좌 서비스 CPU를 씁니다. 1,000명 토큰 발급(16스레드, 자원 상한 적용)이 비용 10이면 약 63초, 비용 4면 약 3초라서, 비용 10으로는 10만 명 토큰 발급이 토큰 만료(1시간)보다 오래 걸립니다 (선형 추정 약 105분)
- 비밀번호 해시는 하나를 만들어 모든 회원에 씁니다 (API 가입은 회원마다 솔트가 다릅니다. 로그인과 측정에는 차이가 없습니다). 해시는 관리용 DB(`postgres`)의 pgcrypto로 만들어 `ipo` DB에는 확장을 남기지 않습니다
- 한 트랜잭션입니다. 같은 loginId가 이미 있으면 아무것도 넣지 않고 실패합니다 (종료 코드 4)
- `created_by`는 API로 넣을 때와 같은 값입니다 (회원은 `SYSTEM:account-service`, 계좌와 원장은 회원 ID)

### 3-3. 자원 상한 적용

```bash
docker-compose -f docker-compose.yml -f compose.perf.yml up -d
```

- 컨테이너가 상한과 함께 다시 만들어집니다 (DB, Kafka 데이터는 유지).
- 상한과 JVM 힙(G1 명시)은 `compose.perf.yml` 맨 위 표를 봅니다. 머신에 맞게 고치고, 쓴 값은 측정 환경 기록에 적습니다.
- `kafka-ui`는 이 구성에서 기본 실행에서 빠집니다 (필요하면 `docker-compose -f docker-compose.yml -f compose.perf.yml up -d kafka-ui`).

### 3-4. 기준 상태 스냅샷 만들기 (한 번)

정식 측정은 매번 같은 DB 상태(회원, 계좌, 예수금만 있고 청약이 없음)와 빈 Kafka에서 시작합니다. 측정할 때마다 청약, 원장, outbox가 쌓이면 DB가 커지고 정리 작업(autovacuum) 시점이 달라져서, 설정을 바꾸지 않아도 결과가 달라지기 때문입니다.

```bash
# 1. 빈 DB, 빈 Kafka에서 띄운다 (PostgreSQL, Kafka 볼륨을 지우면 그 안의 데이터는 사라진다. 필요하면 먼저 baseline.sh dump로 백업)
docker-compose -f docker-compose.yml -f compose.perf.yml down
docker volume rm ipo-subscription_postgres-data ipo-subscription_kafka-data   # 이름은 docker volume ls로 확인
docker-compose up -d                # 시드는 자원 상한 없이 (3-1절: BCrypt가 CPU를 많이 써서 상한을 걸면 느림)
# 2. 회원 1,000명 (가입, 계좌 개설, 입금 모두 API). 예수금은 측정 여러 번에 충분하게
python loadtest/seed/seed_users.py --run-id baseline --amount 100000000 --base-url http://127.0.0.1:8080
# 3. 저장 (청약, 증거금이 있으면 거부)
bash loadtest/baseline.sh save      # → loadtest/data/baseline/ipo-baseline.dump, ipo-baseline.meta
# 4. 자원 상한 적용 (3-3절). 정식 측정과 baseline.sh restore는 이 구성에서 돈다
docker-compose -f docker-compose.yml -f compose.perf.yml up -d
docker-compose stop kafka-ui        # 1단계에서 상한 없이 뜬 kafka-ui는 위 명령으로 꺼지지 않는다. 측정 중 Kafka를 조회하며 CPU를 쓰므로 끈다
```

- `.env`에 `DB_PASSWORD`가 있어야 빈 볼륨에서 PostgreSQL이 만들어집니다.
- 스냅샷은 `loadtest/data/`(커밋하지 않음)에 있습니다. meta에 상태(회원 수, 계좌 수, 예수금 합계, 원장 수, 청약 0)와 sha256이 남고, 복원할 때 둘 다 확인합니다.
- `baseline.sh restore`는 계좌, 청약, 배정 서비스를 멈추고 데이터베이스 `ipo`를 지운 뒤 스냅샷으로 다시 만들고(`ANALYZE`, `CHECKPOINT` 포함), Kafka 볼륨을 비워 토픽을 다시 만든 다음 서비스를 띄웁니다. 복원 뒤 상태가 meta와 다르면 실패합니다. 정식 측정(`official.sh`)이 시작할 때 자동으로 실행합니다.
- 회원 CSV(`loadtest/data/users.csv`)는 스냅샷을 만들 때의 시드 결과라 계좌 ID가 스냅샷과 같습니다. 회원을 다시 시드했으면 스냅샷도 다시 만듭니다.

#### S3, S4 기준 스냅샷 (한 번)

S3, S4는 두 번째 계좌와 잔액 부족 묶음이 더 필요해서, 위 스냅샷을 바탕으로 별도 폴더에 따로 만듭니다. 위 스냅샷은 그대로 둡니다.

```bash
bash loadtest/baseline.sh restore   # 위 스냅샷으로 되돌린다 (지금 DB는 사라진다. 필요하면 먼저 dump)
docker-compose up -d                # 시드는 자원 상한 없이
python loadtest/seed/seed_users.py --run-id s34-acc2 --behavior loadtest/synthetic/s3-behavior.csv --amount 100000000 --base-url http://127.0.0.1:8080
python loadtest/seed/seed_users.py --run-id s34-lb --prefix lb --behavior loadtest/synthetic/s4-behavior.csv --out loadtest/data/users-s4.csv --base-url http://127.0.0.1:8080
BASELINE_DIR=loadtest/data/baseline-s34 bash loadtest/baseline.sh save
docker-compose -f docker-compose.yml -f compose.perf.yml up -d
docker-compose stop kafka-ui
```

- 확인: 기본 묶음 계좌 1,060개(두 번째 계좌 60개), 잔액 부족 묶음 회원 1,000명 중 예수금 150,000원 미만 97명, 청약 0
- 계좌 개설은 같은 requestId면 기존 계좌를 돌려주므로 `users.csv`의 첫 계좌 ID는 위 스냅샷과 같습니다. 두 번째 계좌(`accountId2`)는 이 스냅샷에만 있습니다

#### 확정 청약 스냅샷 (마감 직전 상태에서 시작하는 측정)

대량 환불(마감 → 배정 → 정산 → 환불)처럼 확정 청약이 쌓인 상태에서 시작하는 측정은 그 상태를 스냅샷으로 저장해 두고 회차마다 복원합니다. 기본 `save`는 청약이 있으면 거부하므로 `ALLOW_SUBSCRIPTIONS=1`을 줍니다. 별도 폴더에 저장하고 위 스냅샷은 그대로 둡니다.

```bash
# 회원, 공모주, 청약을 넣고 모든 청약이 확정(또는 거절)된 뒤
ALLOW_SUBSCRIPTIONS=1 BASELINE_DIR=loadtest/data/<폴더> bash loadtest/baseline.sh save
# 회차마다
BASELINE_DIR=loadtest/data/<폴더> bash loadtest/baseline.sh restore
```

대량 환불 측정 스냅샷은 기준 스냅샷 위에 회원(1천 건은 기존 1,000명 그대로), 워밍업 공모주 W(케이스 A 1,000건), 측정 공모주 M(케이스 A 또는 B, N건)을 넣고 모두 확정된 상태입니다. 아래는 1만 건 케이스 B 예시입니다.

```bash
export MSYS_NO_PATHCONV=1
bash loadtest/baseline.sh restore                   # 기준 스냅샷 (회원 1,000명 lt0000 ~ lt0999). 지금 DB는 사라진다
# 종료 시각이 지난 개발용 공모주가 마감될 때까지 기다린다 (0이 될 때까지)
docker exec ipo-postgres psql -U postgres -d ipo -tAc "select count(*) from subscription.ipo where status='OPEN' and subscription_end_at <= now()"
# 1. 회원: 1천 건이면 건너뛴다. 기존 1,000명 뒤에 SQL로 더한다 (3-2절, 토큰 발급이 빠르게 --bcrypt-cost 4)
bash loadtest/seed/seed_users_sql.sh --start 1000 --users 10000 --prefix lt --bcrypt-cost 4 --out loadtest/data/users-g2.csv
# 2. 토큰 (1시간 만료라 청약 직전에). 1천 건이면 --users 1000, --out loadtest/data/users.csv
python loadtest/seed/seed_users.py --run-id g2 --prefix lt --users 10000 --out loadtest/data/users-g2.csv --refresh-tokens
# 3. 공모주 W, M. ID 파일은 스냅샷 폴더의 ipo-ids.txt로 둔다 (회차 실행기 g2_run.sh가 읽는다. "CASE B", "W1 <id>", "M <id>")
bash loadtest/seed/g2_ipo.sh --case B --count 10000 --out loadtest/data/g2-B-10k/ipo-ids.txt
W=$(awk '$1=="W1"{print $2}' loadtest/data/g2-B-10k/ipo-ids.txt); M=$(awk '$1=="M"{print $2}' loadtest/data/g2-B-10k/ipo-ids.txt)
# 4. 청약: W는 앞 1,000명이 172주, M은 CSV 전원이 케이스 B 수량
bash loadtest/run-k6.sh g2 --ipo "$W" --run-id g2-B-10k-W --users data/users-g2.csv -e CASE=A -e COUNT=1000
bash loadtest/run-k6.sh g2 --ipo "$M" --run-id g2-B-10k-M --users data/users-g2.csv -e CASE=B
# 5. 접수 토픽 랙이 0이고(LAG 열), 처리 중 청약이 0이고, W 1,000건, M N건이 모두 CONFIRMED이면 저장
docker exec ipo-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 --describe --group subscription-requested
docker exec ipo-postgres psql -U postgres -d ipo -tAc "select status, count(*) from subscription.subscription group by 1"
docker exec ipo-postgres psql -U postgres -d ipo -tAc "select ipo_id, status, count(*) from subscription.subscription where ipo_id in ('$W', '$M') group by 1, 2"
ALLOW_SUBSCRIPTIONS=1 BASELINE_DIR=loadtest/data/g2-B-10k bash loadtest/baseline.sh save
```

- 케이스 A는 모든 청약이 172주, 케이스 B는 회원 순번 50개 단위로 10주 35개, 50주 10개, 200주 4개, 1000주 1개(70 / 20 / 8 / 2%, 평균 53주)입니다. 같은 순번은 늘 같은 수량이고, B의 건수는 50의 배수여야 합니다
- 일반청약 물량은 청약 수 x 평균 수량(A 172, B 53) / 69(경쟁률 69:1, 주식 수 기준)를 반올림하고, 홀수면 1을 더합니다 (균등 물량이 물량 / 2이고 DB 제약이 균등 x 2 ≥ 물량이라 짝수여야 합니다). 1,000건이면 A 2,494주, B 768주
- `g2_ipo.sh`는 W, M을 종료 시각 10년 뒤(`new_ipo.sh --far`, 환불일은 종료일 + 3)로 넣고, 다른 `OPEN` 공모주도 종료 시각을 10년 뒤로 옮깁니다. 연습처럼 W가 2개 필요하면 `--warmups 2`
- `g2.js`는 시드라서 G1 처리량, 응답 기준은 보지 않고, 202가 아닌 응답이 하나라도 있으면 실패합니다 (종료 코드 99). 시간 상한 없이 `COUNT`건(기본: CSV 전체)을 `VUS`(기본 50)명이 나눠 보냅니다
- 계좌 예수금은 기준 스냅샷과 SQL 시드 기본값 모두 1억 원이라, 가장 큰 청약(M 1000주 1,500만 원 + W 172주 258만 원)도 잔액 부족이 나지 않습니다

- 아래가 모두 0일 때만 저장합니다. 하나라도 있으면 종료 코드 2, 조회에 실패하면 종료 코드 4입니다
  - 처리 중 청약 (`PENDING`, `COMPENSATING`, `CANCEL_REQUESTED`)
  - 계좌, 청약, 배정 서비스 outbox의 미발행 메시지
  - ⚠️ 접수 토픽(`subscription.requested`) 랙은 보지 않습니다. 청약 행은 접수 Consumer가 만들어서, 202를 받은 요청도 처리 전에는 행이 없어 위 조건을 통과합니다. 복원하면 Kafka를 비우므로 그 요청은 사라지고, `g2_run.sh`는 그때 DB의 확정 수를 기대 수로 쓰므로 건수가 모자란 채로 측정됩니다. 그래서 5번처럼 랙 0과 W, M 확정 수를 직접 확인합니다
  - 종료 시각이 지난 `OPEN` 공모주 (복원 직후 저절로 마감되지 않게. 측정할 공모주와 개발용 공모주의 종료 시각을 먼 미래로 두고, 측정할 때 종료 시각을 바꿔 마감시킵니다)
- meta에는 청약 수가 든 상태 줄과 `allow_subscriptions` 줄이 남습니다. `restore`, `check`는 옵션 없이 meta의 상태(청약 수 포함)와 비교합니다
- `ALLOW_SUBSCRIPTIONS`는 `1`일 때만 켜집니다. 다른 값이면 기본 `save`처럼 청약 있는 DB를 거부합니다

## 4. 정식 측정 (official.sh)

S0, S1 각각에서 G1(처리량, 응답, 오류)과 G2(접수→확정 시간, 소진 시간)를 판정할 수 있게 한 번에 실행합니다. 커밋한 뒤, 3장 준비(시드, 자원 상한, 기준 스냅샷)를 마친 상태에서 실행합니다.

```bash
bash loadtest/official.sh --batch official-1103-a     # 결과: loadtest/results/official-1103-a/ (약 1시간 10분~1시간 20분, 추정. 워밍업 3~15분 포함)
SCENARIOS=S0,S3,S4 BASELINE_DIR=loadtest/data/baseline-s34 bash loadtest/official.sh --batch official-s34-a   # S3, S4를 S0과 같은 세션에서 비교
```

`SCENARIOS`(기본 `S0,S1`)로 실행할 시나리오를 고릅니다. S0은 필수입니다 (워밍업 뒤 기준값이자 S3, S4의 비교 대상).

| `SCENARIOS` | 워밍업 뒤 순서 | 필요한 것 |
| --- | --- | --- |
| `S0,S1` (기본) | S0 3회 → S1 단계 | 3-4절 기준 스냅샷 |
| `S0,S3,S4` | S3, S4 경로 예열 각 1회 → S0, S3, S4를 번갈아 3회씩 | 3-4절 S3, S4 기준 스냅샷(`BASELINE_DIR`)과 그때의 회원 CSV(`users.csv`, `users-s4.csv`) |
| `S0,S1,S3,S4` | 위 순서 뒤 S1 단계 | 위와 같음 |

- 번갈아 실행하는 이유: 세션을 나누면 세션 간 편차가 섞이고, 시나리오별로 몰아서 돌리면 세션 중 DB 증가와 JVM 상태 변화가 한 시나리오에만 쏠립니다.
- 번갈아 도는 순서는 `MEASURE_ORDER`(기본 `S0,S3,S4`)로 정합니다. 실행하는 시나리오를 한 번씩 정확히 적어야 하고 아니면 시작 전에 멈춥니다. 측정 3회는 모두 같은 순서이고, 경로 예열은 순서와 관계없이 먼저 합니다. 워밍업 직후 첫 측정이 늘 같은 시나리오면 그 시나리오만 불리할 수 있어서, 세션 세 번을 `S0,S3,S4` / `S3,S4,S0` / `S4,S0,S3`으로 나눠 첫 측정을 돌려 씁니다. 순서는 `env.txt`의 `설정 MEASURE_ORDER=`에 남습니다.
  ```bash
  MEASURE_ORDER=S3,S4,S0 SCENARIOS=S0,S3,S4 BASELINE_DIR=loadtest/data/baseline-s34 bash loadtest/official.sh --batch official-s34-b
  ```
- 경로 예열 이유: 워밍업은 모든 요청이 확정되는 경로라 이중청약, 잔액 부족 거절 경로가 데워지지 않은 채 첫 측정에 들어갑니다. 경로 예열 회차는 구간 `warmup`으로 남고, 성능은 참고값이지만 정합성은 판정합니다.
- S3, S4는 시작 전(부하 전)에 데이터를 확인하고 맞지 않으면 멈춥니다 (종료 코드 4): 행동 CSV 규칙(이 측정의 청약 금액, 4회 기준), S3 투자자의 두 번째 계좌, S4 회원 CSV가 잔액 부족 묶음인지, DB의 잔액 부족 묶음 예수금이 행동 CSV대로인지. S4 행동 CSV는 청약 금액 150,000원(`S0_PRICE` x `QUANTITY`) 기준이라 금액을 바꾸면 시작하지 않습니다.
- `S0,S3,S4`는 S1이 없어 약 25~35분입니다 (추정. 워밍업 3~15분 포함). S1이 없으므로 `consumer_stats.py`(S1 전용)는 해당하지 않습니다.

하는 일 (순서대로, 중간에 사람이 끼어들지 않습니다)

1. 커밋하지 않은 변경이 있으면 실행을 거부합니다. 이 커밋으로 jar를 빌드해 배포하고(`./gradlew build -x test`, `compose up -d --build`), 기준 스냅샷으로 DB와 Kafka를 되돌린 뒤(`baseline.sh restore`, 3-4절. 스냅샷 sha256과 상태를 `env.txt`에 남김), 컨테이너 안 jar와 빌드한 jar의 sha256이 같은지 확인해 `env.txt`에 남깁니다. 컨테이너별 CPU, 메모리 상한과 JVM 힙 옵션도 `env.txt`에 남기고(`자원 ...` 줄), 이때부터 부하가 끝날 때까지 `docker stats`를 `STATS_INTERVAL`초마다 기록합니다 (PostgreSQL, Kafka, k6 포함 모든 컨테이너).
2. 토큰을 새로 받고(회원 수 확인. S4가 있으면 잔액 부족 묶음도), S3, S4 데이터를 확인하고, 예수금이 최대로 필요한 금액 이상인지 확인합니다.
3. gateway, subscription, account를 재시작(콜드 상태)하고, healthy와 Eureka UP을 기다린 뒤 45초 쉬고 사전 점검(`precheck.sh`)을 합니다.
4. 회차에 쓸 공모주를 모두 미리 만듭니다 (부하 중에는 만들지 않습니다).
5. 워밍업: 측정 앞의 독립 단계입니다. `WARM_RATE`(기본 200건/초)로 일정한 부하를 걸고(모든 요청이 확정되게 공모주를 돌림), `WARM_BUCKET`(15초)마다 게이트웨이 평균 응답 시간을 Prometheus에서 읽습니다. 최근 `WARM_WINDOW`개 구간(2분)에 직선을 맞춰, 기울기가 분당 창 평균의 ±`WARM_SLOPE_PCT`%(5%) 이내이거나 분당 ±`WARM_SLOPE_MS`ms(1ms) 이내인 판정이 `WARM_CONSEC`번(2번) 연속이면 정상 상태로 보고 끝냅니다. 최소 `WARM_MIN`(180초), 최대 `WARM_MAX`(900초)이고, 최대까지 닿지 않으면 중단합니다. 구간마다 `warmup.csv`에 남고, 끝나면 소진을 확인하고 쉽니다.
   - 회차마다 흔들림으로 판정하던 방식(연속 3회차 ±20%)은 아직 빨라지는 중이어도 통과할 수 있어서, 워밍업이 끝나는 시점이 실행마다 달라지고 측정값에 섞였습니다. 시간 구간의 추세(기울기)로 보면 내려가는 중에는 통과하지 않습니다.
   - 절대 기울기(분당 ±1ms)를 함께 보는 이유: 데워진 뒤 응답이 2~7ms로 작으면 ±5%의 허용 폭이 분당 약 0.25ms까지 좁아져, 평평한 상태의 작은 흔들림도 통과하지 못하고 최대 시간에 닿습니다. 분당 1ms는 2분 창 전체로 2ms 차이라 G1(평균 500ms), G2(15초) 판정에 영향이 없는 크기이고, 응답이 아직 내려가는 중(분당 1ms보다 빠르게 감소)이면 통과하지 않습니다. `WARM_SLOPE_MS=0`이면 상대 기울기만 봅니다.
6. S0: 1,000건(100명 x 10회) 측정 3회를 실행합니다. S3, S4가 있으면 경로 예열 뒤 S0, S3, S4를 번갈아 3회씩 실행합니다.
7. S1(`SCENARIOS`에 있을 때): 속도 단계(`STAGES`)마다 `DUR`초 측정을 `S1_REPEAT`(기본 3)회 연달아 독립 k6 실행으로 합니다 (팀 규칙: 같은 조건 최소 3회, 중앙값). 반복마다 새 공모주를 돌려 써서 모든 요청이 확정 경로를 탑니다 (`IPO_IDS`, 6장).
8. 부하가 끝나면 실행마다 보고서(`report.py`), 요청 분류(`check_requests.py`), 정합성 SQL(`verify.sh`)을 만들고, 판정(`judge.py`)을 `판정.md`로 씁니다. docker stats 기록은 `docker-stats.csv`와 `자원.md`(실행마다 k6 구간의 컨테이너별 CPU, 메모리 최대)로 정리합니다. 어떤 실행의 k6 구간에 샘플이 없으면 후처리 실패(종료 코드 10)로 셉니다. 기록 간격(5초)의 2배보다 짧은 실행은 샘플이 빠질 수 있어 경고로만 남깁니다.
9. 측정이 끝난 DB를 `db-after.dump`로 남깁니다(`DB_DUMP=1`). 다음 측정은 기준 스냅샷으로 되돌리므로 이번 측정의 원본 행은 여기에만 남습니다.

- 쿼리 통계: 첫 실행 직전에 `pg_stat_statements`를 비우고(`loadtest/sql/slow-queries.sql`), 실행마다 소진 확인 뒤 그 실행의 통계를 `slow-queries.txt`에 남기고 다시 비웁니다. 요청과 소진까지의 Consumer 쿼리가 들어가고, 실행 사이에 토큰 재발급이 끼면 그 로그인 쿼리도 다음 실행에 섞입니다. 조회가 실패해도 측정은 이어 가고 후처리 실패(종료 코드 10)로 셉니다. `compose.perf.yml`로 띄우지 않아 확장을 쓸 수 없으면 시작 전에 멈춥니다(종료 코드 9).
- autovacuum: PostgreSQL은 바꾸거나 지운 행의 옛 버전(죽은 행)을 남기고, autovacuum이 쌓인 죽은 행을 백그라운드에서 치웁니다 (autoanalyze는 쿼리 계획용 통계 갱신). 측정 중 청약 상태 변경, outbox 발행 표시처럼 행 변경이 많아 측정 도중에 자동으로 돌고, 돌 때 CPU, IO를 써서 그 실행이 느려질 수 있습니다. 통계(`pg_stat_user_tables`)에는 누적 횟수와 마지막 시각만 남아서, 첫 실행 직전과 실행마다 소진 확인 뒤 테이블별 누적 횟수를 남기고(`autovacuum-before.csv`, 실행별 `autovacuum.csv`) 후처리에서 앞 기록과의 차이로 실행마다 돈 테이블을 `autovacuum.md`에 정리합니다. 차이에는 앞 실행 뒤 대기 시간도 들어가고, 통계 반영이 몇 초 늦을 수 있어 경계 근처의 1회는 앞뒤 실행으로 밀릴 수 있습니다. 기록이 실패해도 측정은 이어 가고 후처리 실패(종료 코드 10)로 셉니다.
- **모든 부하 실행(S0 회차, S1 단계) 뒤에 이번 공모주의 `PENDING`/`COMPENSATING` 0, 필수 Consumer 그룹 lag 0, outbox 미발행 0을 확인하고 `GAP`초 쉰 뒤 다음 실행을 합니다.** 앞 실행의 처리 대기가 다음 실행의 G2에 섞이지 않게 하는 것은 소진 확인이 맡습니다. `GAP`(기본 30초)은 보고서의 서버 지표 구간(요청 구간 앞뒤 10초, Prometheus 5초 수집)이 앞뒤 실행과 섞이지 않게 하는 용도입니다. JVM GC(G1은 쉬는 동안 스스로 돌지 않음)와 DB 체크포인트는 대기로 피할 수 없습니다. 개선 전후를 비교할 때는 같은 `GAP`을 씁니다.
- psql, Kafka, git, 토큰 재발급, k6 조회가 실패하면 0으로 보지 않고 중단합니다. Consumer lag은 `LOG-END-OFFSET - CURRENT-OFFSET`으로 계산하고(커밋 오프셋이 `-`인데 메시지가 있으면 대기로 셉니다), 필수 그룹(`REQUIRED_GROUPS`)이 없으면 실패입니다.
- 토큰이 다음 실행 중에 만료될 것 같으면 실행 사이(요청 없는 구간)에 재발급하고 `GAP`초 쉽니다. 재발급 시점은 `진행.log`에 남습니다.
- 같은 이름의 결과 폴더가 있으면 거부합니다(이전 결과를 덮어쓰지 않음). 두 개를 동시에 실행할 수 없습니다(`loadtest/results/.official.lock`). 중단(Ctrl+C)하면 이번 실행이 띄운 k6 컨테이너만 멈춥니다.
- 시험 실행: `ALLOW_DIRTY=1 SKIP_BUILD=1 WARM_MIN=60 WARM_MAX=300 GAP=5 STAGES=50,100 DUR=10 S1_REPEAT=3 bash loadtest/official.sh --batch smoke-1` (약 20분, 정식 결과로 쓰지 않습니다)
- 중간에 멈출 때는 `official.sh` 프로세스에 신호를 보냅니다. 감싼 셸만 멈추면 `official.sh`는 계속 돕니다. k6, docker stats 기록, 잠금은 `official.sh`가 끝날 때 정리됩니다.

판정 기준 (`judge.py`)

| | S0 (측정 3회 중앙값) | S1 (속도 단계마다 반복 3회 중앙값) |
| --- | --- | --- |
| G1 합격 | 처리량 200건/초 이상, 평균 500ms 이하, 오류율 1% 미만 | 평균 500ms 이하, 오류율 1% 미만 |
| G1 목표 | 처리량 400건/초 이상, 평균 250ms 이하, p95 500ms 이하, 오류 0 | 평균 250ms 이하, p95 500ms 이하, 오류 0 |
| G2 합격 | 접수→확정 p95 15초 이하, 소진 시간 15초 이하 | 같음 |
| 무효 | 요청 수가 1,000건이 아닌 회차(2분 제한 안에 다 못 보냄)는 무효. 측정 3회가 모두 유효하지 않으면 판정하지 않음 | 밀린 반복(`dropped_iterations`)이 있는 회차는 무효. 무효 회차가 섞인 단계는 판정하지 않음. 3회 미만이면 참고값(결론에 쓰지 않음) |

- S1은 한계 지점(평균 500ms 또는 오류율 1%가 처음 깨지는 단계)으로 보므로 처리량을 기준에 넣지 않고, 목표 속도 대비 실제 전달률을 따로 보여줍니다.
- 소진 시간은 마지막 요청 접수 이후 마지막 확정까지(`MAX(confirmed_at) - MAX(received_at)`)입니다. 확정 수가 요청 수와 다르면(오류, 거절, 미처리가 섞이면) G2는 무효입니다.
- `판정.md`의 S1 한계 지점은 G1 또는 G2가 처음 깨지는 단계입니다.

S3, S4 (Synthetic Data) 판정 기준. 회차마다 유효성, 정합성, 성능을 따로 판정하고 하나로 합치지 않습니다.

| | 기준 | 깨지면 |
| --- | --- | --- |
| 기대값 | 행동 CSV를 `synthetic/validate.py`로 집계 (요청 수, 청약 행, 확정, 사유별 거절, 증거금). CSV가 규칙을 어기거나 manifest의 예상 요청 수와 다르면 판정 전체를 멈춤 | `judge.py` 실패 |
| 유효성 | 요청 수 = 기대 요청 수(1,000) | **무효** (시험이 잘못됨. G1, G2, 정합성 모두 판정하지 않음) |
| 정합성 | 청약 행, 확정, 사유별 거절, 증거금 수가 기대값과 같음 + 미처리(`PENDING`, `COMPENSATING`, `CANCEL_REQUESTED`) 0. S4는 예수금 부족 투자자의 예수금이 행동 CSV 입금액 그대로이고 청약증거금 0 | **불합격** (시스템 결함. 무효와 다르다). HTTP 오류가 있으면 접수되지 않은 요청이 섞여 **판정 불가** |
| 성능 | G1은 S0과 같음(처리량 포함, 부하 모양이 같다). G2는 확정 건만, 확정 수가 기대 확정 수와 다르면 G2 무효 | G1, G2 불합격 |

- 대표값은 measure 3회 중앙값입니다. 3회가 모두 유효해야 G1, G2를 판정하고, 정합성은 3회 모두 통과해야 통과입니다. 경로 예열 회차(`warmup`)도 정합성은 판정합니다 (정합성은 예열과 관계없이 맞아야 한다).
- 거절 지연(접수 → 거절, `updated_at - received_at`)은 참고 지표로만 보여 줍니다. 청약에 거절 시각 컬럼이 없어 대신 쓰는 값입니다.
- `판정.md`에 "S0 대비" 표가 붙습니다 (S3, S4 중앙값 - S0 중앙값). 합격선 없이 수치로만 봅니다.
- 정합성 SQL 16~19번(`verify.sql`)은 같은 조건을 행 단위로 보여 줍니다 (같은 requestId 2행, 증거금 2개, 회원당 유효 청약 2건, 돈이 있는데 잔액 부족 거절). judge는 건수로, SQL은 어느 행이 문제인지로 봅니다.

종료 코드: 0 정상, 2 워밍업이 최대 시간 안에 정상 상태에 닿지 않음 또는 판정 실패, 3 토큰, 4 예수금, 5 버전(dirty, jar 불일치), 6 소진 대기, 7 기동, 8 k6, 9 사전 점검, 10 부하는 끝났고 후처리 일부 실패, 11 공모주 생성, 12 잠금·결과 폴더, 13 도구 없음, 14 기준 상태 복원 실패

결과를 쓸 때 같이 적는 한계: k6와 서비스가 같은 PC, Prometheus 5초 수집, docker stats 5초 간격 순간값(조회 1번에 약 2~3초, 조회도 CPU를 씀), 토큰 재발급의 로그인 요청, S1은 공모가 `S1_PRICE`원 종목 (금액만 다르고 처리 경로는 같다고 보지만 확인하지 않았습니다)

### 대량 환불 측정 (g2_run.sh)

청약 마감 뒤 배정, 정산, 환불이 끝날 때까지의 시간을 잽니다. 3-4절의 확정 청약 스냅샷을 준 순서대로 한 회차씩 돌립니다.

```bash
export MSYS_NO_PATHCONV=1
bash loadtest/g2_run.sh --batch g2-1k-0110 --session S-1 g2-A-1k g2-B-1k g2-B-1k g2-A-1k g2-A-1k g2-B-1k
```

회차 하나 (사람 손 없이 돈다)

1. 스냅샷 복원 (`baseline.sh restore`. 서비스가 재시작되어 JVM이 차가운 상태) → healthy, Eureka 등록 → `SETTLE_S`초
2. 점검: M 확정 수, 처리 중 청약 0, 종료 시각이 지난 OPEN 공모주 0, DLT 수. 실패하면 INVALID로 적고 다음 회차
3. 워밍업: W마다 종료 시각을 1초 전으로 바꿔 마감 → 환불 완료까지 대기 → `GAP`초
4. 측정 전 기록 (테이블 행 수), docker stats 기록 시작
5. 측정: M 종료 시각 UPDATE → 스케줄러가 실제 조건(유예 20초, 랙 0, 처리 중 0)으로 마감 → 환불 완료(REFUNDED = 확정 수)까지 대기
6. 수집, 검증 (`verify.sql`을 M과 W 모두), 판정 → `manifest.csv`에 한 줄
7. (`--idempotency`일 때) 중복 수신 멱등성 검사: account-service를 멈추고 `account-allocation-result` 그룹의 `allocation.result` 오프셋을 처음으로 되돌린 뒤 다시 띄워, 이번 회차의 배정 결과(W, M)를 전부 다시 받게 한다. 다 받으면(랙 0) 계좌 쪽 상태(예수금, 청약증거금 합, 원장 수와 마지막 시각, 증거금 상태별 수, 정산 금액 합, outbox, 처리 기록, 마지막 수정 시각)가 전과 같고 DLT가 늘지 않아야 통과. 측정 시간에는 넣지 않는다

측정 정의

| 항목 | 정의 |
| --- | --- |
| 시작 T0 | 청약 outbox `ipo.closed`의 `created_at` (마감 UPDATE 시각부터 T0까지는 정책 시간 `policy_s`로 따로 기록) |
| 끝 T1 | M의 `margin.settled_at` 최댓값. `total_s` = T1 - T0, `thr` = 정산 수 / `total_s` |
| 보조 T2 | 마지막 `margin.settled` 처리 시각 (청약 REFUNDED 표시). `mark_s` = T2 - T1 |
| 구간 | `wait_s`(T0 → 배정 시작), `alloc_s`(배정 계산, 저장), `publish_s`(배정 끝 → 마지막 배정 결과 발행), `first_settle_s`(T0 → 첫 정산), `settle_s`(첫 정산 → 마지막 정산) |

판정 (회차마다)

| 열 | 값 | 조건 |
| --- | --- | --- |
| `status` | `VALID` | 아래 둘이 아님 |
| | `INVALID` (시험이 잘못됨, 판정하지 않음) | 정산 수 또는 REFUNDED 수 ≠ 확정 수, DLT 증가, 측정 끝 outbox 미발행, 측정 중 W, M이 아닌 공모주 마감, 시작 점검 실패 |
| | `UNMEASURABLE` (측정 불가) | 정산이 `STALL_S`초 동안 늘지 않음, `MAX_S`초 초과, 배정 FAILED, 컨테이너 재시작 또는 OOM, W 환불이 끝나지 않음 |
| `integrity` | `PASS` / `FAIL` (시스템 결함) | `verify.sql` 2, 3, 5, 6, 8, 11 ~ 15번 위반(W는 5, 6, 8, 11 ~ 15번), 0주 배정 수 ≠ `VOIDED` 수, `VOIDED` 수 ≠ `MARGIN_VOID` 원장 수, `POSTED` 수 ≠ `MARGIN_POST` 원장 수, 멱등성 FAIL. 측정 불가면 `-` |
| `idempotency` | `PASS` / `FAIL` / `ERROR` / `-` | 위 7번. `ERROR`는 검사를 끝내지 못한 것(그룹이 비지 않음, 오프셋 되돌리기 실패, `IDEM_MAX_S`초 안에 랙이 0이 안 됨). `-`는 `--idempotency` 없음, 측정 불가 |

결과: `loadtest/results/g2-<batch>/`

| 파일 | 내용 |
| --- | --- |
| `manifest.csv` | 회차마다 한 줄: 세션, 순번, 스냅샷, 케이스, 확정 수, 시작, 끝, status, integrity, total_s, throughput, idempotency, 이유. 같은 `--batch`로 다시 실행하면 순번을 이어 쓴다 |
| `env.txt`, `batch.log` | git 커밋, 커밋 안 한 파일, 설정, 컨테이너 자원 상한, 컨테이너 jar sha256 / 전체 로그 |
| `<순번>-<스냅샷>/result.txt` | W, M 측정값 한 줄씩 (key=value: 위 구간, `settled`, `refunded`, `posted`, `voided`, `zero`(0주 배정), 원장 수, `round`, `seed`(추첨 시드)) |
| `<순번>-<스냅샷>/checks.txt` | 판정 근거 (확정 수, DLT 전후, 미발행, 다른 마감, verify 위반, 이유) |
| `<순번>-<스냅샷>/verify-M.txt`, `verify-W<i>.txt` | `verify.sql` 원본 출력 |
| `<순번>-<스냅샷>/bucket-publish.csv`, `bucket-settle.csv` | 1초마다 배정 결과 발행 수, 정산 수 |
| `<순번>-<스냅샷>/docker-stats.raw` | M 마감부터 환불 완료까지 docker stats (`STATS_INTERVAL`초 간격, 4장과 같은 형식) |
| `<순번>-<스냅샷>/tables-before.txt`, `processed.txt`, `log.txt` | 측정 직전 테이블 행 수, 처리 기록, 회차 로그 |
| `<순번>-<스냅샷>/idempotency.txt` | (`--idempotency`) 결과, 다시 받은 메시지 수, 전후 계좌 쪽 상태, DLT 전후, 참고로 로그의 "중복 수신, 건너뜀" 수 |

- 측정 전에 직접 합니다 (실행기가 하지 않음): `compose.perf.yml`로 띄우기(3-3절), 다른 무거운 프로그램 끄기, WSL 시계 점검
- 순서는 인자 순서 그대로입니다. 같은 건수 안에서 A, B를 ABBA 꼴로 섞고, 세션마다 첫 케이스를 바꿉니다
- 회차 시간: 1천 건 케이스 B 한 회차가 약 5분 30초였습니다 (복원과 기동 약 3분, W 약 45초, 쉼 30초, M 약 40초, 검증). `--idempotency`는 약 1분 30초 더 (2,000건 다시 받기 73초)
- 멱등성 검사는 정식 1천 건 A, B 회차 하나씩에 붙입니다 (예: `bash loadtest/g2_run.sh --batch g2-idem --idempotency g2-A-1k g2-B-1k`). 검사는 그 회차의 측정이 끝난 뒤에 하고, 다음 회차는 복원으로 시작하므로 측정값에 영향이 없습니다
- 시작 전에 모든 스냅샷 폴더(덤프, meta의 `allow_subscriptions` 줄, `ipo-ids.txt`)를 검사하고, 하나라도 없으면 아무것도 하지 않습니다
- 복원, 기동, 조회가 실패하면 배치를 멈춥니다 (종료 코드 4). 그 회차의 로그는 회차 폴더에 남습니다

종료 코드: 0 모든 회차 VALID, PASS / 1 사용법, 스냅샷 폴더 / 2 INVALID 또는 UNMEASURABLE 회차 있음 / 3 정합성 FAIL 회차 있음 / 4 복원, 기동, 조회 실패로 중단 / 12 잠금(다른 `g2_run.sh` 실행 중), 결과 폴더

#### 212만 건 추정 (g2_estimate.py)

```bash
python loadtest/verify/g2_estimate.py loadtest/results/g2-<1천 배치> loadtest/results/g2-<1만 배치> loadtest/results/g2-<10만 배치> --out 추정.md
```

- 2,122,367건(LG에너지솔루션 공모 때 KB증권 청약 건수, DART)의 소요 시간을 케이스별로 추정하고 속도를 판정합니다. 모든 값은 추정입니다
- 대표값: 같은 케이스, 건수에서 VALID이고 정합성 PASS인 회차의 구간별 중앙값. 3회 미만이면 참고값으로 표시합니다
- 구간은 겹치지 않게 나눕니다: 배정 대기 / 배정 계산, 저장 / 배정 끝 → 첫 정산 / 정산. 합 = T1 - T0. 배정 결과 발행은 정산과 겹쳐서 따로 더하지 않고, 환불 완료 표시(T1 → T2)는 참고로 따로 보입니다
- 규칙 (측정 전에 고정): 1천 건은 참고값으로만. 구간마다 r = 처리량(10만) / 처리량(1만)
  - 0.9 ≤ r ≤ 1.1: 1만, 10만 두 점을 지나는 직선으로 외삽
  - r < 0.9: 범위. 낙관 = 10만 처리량 유지, 비관 = 처리량(10만) x r^k (k = log10(212만 / 10만) ≈ 1.33)
  - r > 1.1: 오르는 추세는 잇지 않고 10만 처리량 유지
  - 10만 건이 측정 불가면 그 케이스는 외삽하지 않습니다 ("측정 불가 + 병목")
- 판정: 합격선 3시간, 목표 1.5시간 (임의 기준, T1 기준). 범위면 비관(긴 쪽)이 선 안 → 합격(목표는 달성) / 낙관(짧은 쪽)만 안 → 조건부 / 둘 다 밖 → 불합격(목표는 미달). 대표 판정은 케이스 A입니다
- 구간 시간이 0(밀리초 반올림)이면 처리량을 정할 수 없어 그 케이스는 "계산 불가"로 나옵니다

## 5. 회차를 하나씩 실행

정식 측정이 아닌 개별 확인용입니다. 회차마다 새 공모주를 씁니다. 같은 공모주를 다시 쓰면 회원 전원이 이중청약으로 거절됩니다.

```bash
RUN=S0-r1-1102                               # run_id: 시나리오-회차-날짜
IPO=$(loadtest/seed/new_ipo.sh)              # 새 공모주 ID (S2는 loadtest/s2.sh로 실행, 6장)

loadtest/precheck.sh --run-id $RUN           # 1. 사전 점검 (실패하면 해결한 뒤 진행)
docker exec -i ipo-postgres psql -U postgres -d ipo -v reset=1 -f - < loadtest/sql/slow-queries.sql   # 2. 슬로우 쿼리 통계 초기화
loadtest/run-k6.sh s0 --ipo $IPO --run-id $RUN                                                        # 3. 실행
python loadtest/verify/check_requests.py loadtest/results/$RUN/requests.log --expect-requests 1000    # 4. 요청 로그 분석
loadtest/verify.sh --ipo $IPO --run-id $RUN                                                           # 5. 정합성 SQL
docker exec -i ipo-postgres psql -U postgres -d ipo -f - < loadtest/sql/slow-queries.sql              # 6. 슬로우 쿼리 확인
python loadtest/report.py --run-id $RUN                                                               # 7. 회차 보고서 생성
```

- 2, 6번은 `compose.perf.yml`로 띄웠을 때만 동작합니다 (`pg_stat_statements` 필요).
- 3번에서 시나리오(`s0`~`s4`)와 회원 CSV를 바꿉니다: `--users data/users-s4.csv`, 값 조정은 `-e KEY=VALUE` (예: `-e RATE=300 -e DURATION=60s`).
- 3번 종료 코드가 99이면 합격 기준을 어긴 것입니다 (7장).
- 5번의 정합성 SQL 대부분은 Consumer가 구현된 뒤에야 의미 있는 값이 나옵니다. 그 전에는 청약 행이 생기지 않습니다.
- 5번의 11~15번(배정, 정산 검사)은 배정 라운드가 `DONE`, 공모주가 `ALLOCATED`이고 정산 대기(`PENDING`) 증거금이 없을 때 다시 실행해야 의미가 있습니다. 
  - 배정 전에 나온 0행은 대상이 없어서 나온 것이라 통과로 보지 않습니다. 
  - 1~10번은 정산 전 기준이라 다시 실행할 때는 11~15번 결과만 봅니다.
- 5번 전에 Kafka Consumer lag이 0이고 `PENDING`이 0건인지 확인합니다 (Kafka-UI Consumers 탭 또는 `kafka-consumer-groups.sh --describe`).
- 7번은 `results/$RUN/report.md`를 만듭니다 (k6 요약, 결과 분류, 서비스별 서버 지표). Prometheus가 구간 끝 이후의 수집을 마칠 때까지 몇 초 기다립니다. 5, 6번까지 끝낸 뒤에 실행하면 `precheck.txt`, `sql_result.txt`도 원본 목록에 들어갑니다. 컨테이너에서 실행하면 `PROMETHEUS_URL=http://prometheus:9090`을 줍니다.
- 측정 후 Grafana "측정 대시보드"에서 측정 구간을 캡처합니다.

같은 조건으로 최소 3회 반복합니다. 서비스를 재시작한 직후 회차는 워밍업(JVM, 커넥션 풀)이라 버리지 않고 따로 기록하고, 판정은 그 뒤 회차로 합니다. 개선 전후 비교는 한 번에 하나만 바꿉니다.

## 6. 시나리오

| 시나리오 | 요청 구성 | 공모주 | 회원 CSV | HTTP 기대 |
| --- | --- | --- | --- | --- |
| S0 | 회원 1,000명이 각 1건 (100명 × 10회, 1초에 걸쳐 시작) | 새 공모주 | `users.csv` | 전부 202 |
| S1 | 도착률 고정 단계 부하 (`STAGES` 기본 100,200,300,433,600,800건/초, 단계마다 전환 `RAMP` 10초 + 유지 `HOLD` 90초, 앞에 `WARMUP` 60초). 정식 측정은 한 속도 고정(`RATE`, `DURATION`)을 단계마다 따로 실행 | 새 공모주 (`IPO_IDS`면 여러 개) | `users.csv` | 전부 202 |
| S2 | 마감 경계: 대상 공모주에 회원 1,000명이 1건씩, 마감 시각 앞뒤에 걸쳐 요청하고 배경 공모주로 전체 274건/초 유지 (아래 S2 절) | 대상 1개(측정 중 마감) + 배경 여러 개(`IPO_IDS`) | `users.csv` | 대상: 마감 전 도착 202, 마감 후 422 `SUBSCRIPTION_CLOSED`, 마감 처리 뒤 409 `INVALID_STATUS_TRANSITION`. 배경: 전부 202 |
| S3 | Synthetic Data `s3-behavior.csv`: 첫 청약 860 + 같은 requestId 재전송 80 + 다른 계좌 청약 60 (S0과 같은 100명 x 10회) | 새 공모주 | `users.csv` (S3 행동 CSV로 두 번째 계좌 시드) | 전부 202, 재전송 응답의 `subscriptionId`가 원래 요청과 같음. 이중청약 거절은 Consumer |
| S4 | Synthetic Data `s4-behavior.csv`: 투자자 1,000명 x 1건, 97명 예수금 부족 (S0과 같은 100명 x 10회) | 새 공모주 | `users-s4.csv` (S4 행동 CSV의 입금액으로 시드) | 전부 202 (잔액 부족 판정은 Consumer) |

- **S1의 조건(요청 속도, 유지 시간, 요청 구성)은 임시 값입니다.** 첫 측정 전에 확정하고, 측정을 시작한 뒤에는 바꾸지 않습니다.
- S0은 `VUS`, `ITERATIONS`로 규모를 줄일 수 있습니다 (예: 스모크는 `-e VUS=1 -e ITERATIONS=1`). S2는 `RATE`, `PRE`, `POST`로 조정합니다 (아래 S2 절). S3, S4는 요청 수가 행동 CSV 행 수(1,000)와 같아야 해서 줄이면 시작 전에 멈춥니다.

### S2 마감 경계 (`s2.sh`)

마감 직전 몰림 속에서 마감 시각 전에 도착한 요청은 모두 접수되고, 이후 요청은 모두 거절되며, 마감 처리 뒤에 생기는 청약(마감 때 집계에서 빠지는 청약)이 없는지 봅니다 (SA 기획서 S2, D-16, D-29). 한 회차는 `loadtest/s2.sh`가 처음부터 판정까지 실행합니다.

```bash
bash loadtest/s2.sh --run-id S2-n-r1                                   # ① 평소 부하
POST=0 bash loadtest/s2.sh --run-id S2-g1-r1 --grace PT1S              # ② 장애 상황 (마감 유예 1초)
```

- 요청 배치: 회원은 공모주 하나에 한 번씩만 청약할 수 있어서, 마감을 볼 **대상 공모주**(회원 1,000명이 1건씩)와 부하만 만드는 **배경 공모주**(마감 시각이 12시간 뒤)를 나눕니다. 시간 순서는 배경 `PRE`초 → 대상 구간(274건/초면 약 3.6초, 가운데가 마감 시각) → 배경 `POST`초이고, 전체 속도는 처음부터 끝까지 `RATE`입니다. 배경 공모주 수는 요청 수로 계산합니다. k6는 요청을 보내기 전에(`setup()`) 요청 순번 전체를 배분해 보고, 대상 요청 수가 회원 수와 다르거나 대상 회원, 배경 공모주와 회원 조합이 겹치면 멈춥니다.
- 기본값: `RATE` 274(SA 기획서 규모 산정 ⑤, 마감 직전 10분 피크), `PRE` 60초(SA "마감 몇 분 전 몰림"을 로컬에 맞춰 줄임), `POST` 40초(마감 유예 20초 + 마감 확인 주기 5초 + 여유). `TARGET_SEC`를 주면 대상 구간을 그 길이로 늘려 배경과 섞습니다.
- 시작 시각과 대상 공모주의 마감 시각은 컨테이너 시계(PostgreSQL)로 정하고, k6는 `setup()`에서 시작 시각까지 기다립니다. 판정은 DB의 `received_at`(게이트웨이 도착 시각)과 마감 시각으로 합니다.
- 시작할 때 `official.sh`와 같이 이 커밋으로 jar를 빌드해 배포하고(`./gradlew build -x test`, `compose up -d --build`), 서비스 5개의 로컬 jar와 컨테이너 안 jar의 sha256이 같은지 확인합니다. 다르면 시작하지 않습니다(종료 코드 5). `SKIP_BUILD=1`이면 빌드, 배포만 건너뛰고 비교는 그대로 합니다. 다만 로컬 jar와 컨테이너 jar가 둘 다 이전 커밋으로 빌드한 것이면 비교를 통과해 `env.txt`의 커밋과 실제 코드가 달라지므로, `SKIP_BUILD=1`은 이 커밋으로 빌드, 배포한 것을 따로 확인했을 때만 씁니다. 그래서 회차마다 서비스가 다시 뜨고 1회에 2~3분이 더 걸립니다.
- 회차마다 토큰을 다시 받고, 정식 측정과 같은 워밍업(`warmup.py`, 응답 시간 추세가 평평해질 때까지)을 합니다 (`WARM=0`이면 생략).
- `--grace`: 청약 서비스의 마감 유예(`ipo.close.grace-period`, 기본 `PT20S`)만 바꿔 다시 띄우고, 끝나면(오류로 멈춰도) 덮어쓰기 없이 다시 띄워 되돌립니다. 덮어쓰기 파일은 `loadtest/data/`(커밋하지 않음)에 둡니다.
- ②에서 `POST=0`인 이유: 접수 토픽 랙을 마감 조건으로 보는 구조에서는 다른 공모주 요청이 이어지면 랙이 0이 되지 않아 마감이 미뤄지고(① 평소 부하에서 마감 시각 약 46초 뒤), 그 사이 늦은 발행이 끝나 누락이 가려집니다. 마감 뒤 요청이 없어 랙이 바로 0이 되는 조건에서 "발행 지연 > 유예"를 봅니다.
- 판정 (`results/<run_id>/s2-judge.txt`)
  - 입력 오류 (종료 코드 1, 판정하지 않음): 읽은 로그 행 수가 k6 요청 수(`http_reqs.count`)와 다름(읽지 못한 행을 0건으로 넘기지 않음), 밀린 요청이 없는데 k6 요청 수가 계획한 전체 요청 수와 다름(일찍 끝난 실행. 밀린 요청이 있으면 요청 수가 적은 것이 정상이고 아래 회차 무효), `stage`가 `target`, `bg`가 아닌 로그 행, 요약에 밀린 요청 수나 응답 검사 실패 수가 없음, 마감된 대상 공모주인데 `verify.sql` 20~22번 결과가 없음
  - 회차 무효 (종료 코드 3, 다시 실행): 밀린 요청(`dropped_iterations`)이 있음(요청 순번으로 대상 구간을 정해서 밀리면 구간이 늦어짐), k6 응답 검사 실패(상태 코드나 오류 코드가 기대와 다름), 401(토큰 만료), 대상 요청에 202와 422가 모두 있지 않음(마감 시각이 대상 구간 밖), 배경 요청에 202가 아닌 응답(배경 공모주는 마감되지 않아야 함)
  - 측정값 (기대 0): `verify.sql` 20번(마감 처리 뒤 생긴 청약), 21번(마감 시각 이후 도착인데 접수된 청약), 22번(마감 때 확정 수와 지금 유효 청약 수 차이), 202를 받았는데 청약 행이 없는 요청(k6 로그 대조)
- 결과 파일: `results/<run_id>/`에 `s2-judge.txt`, `sql_result.txt`, `env.txt`(커밋, 유예, 설정값, 대상 공모주, 시작과 마감 시각, 서비스별 jar sha256), `warmup.csv`, `진행.log`와 k6 원본. 워밍업 k6 원본은 `results/<run_id>-W/`
- 한계: 대상 공모주에 요청이 몰리는 시간은 약 3.6초입니다 (회원 1,000명). 한 공모주 행에 잠금이 몰리는 부담은 실제보다 작고, 로컬 PC 안의 전후 비교로만 씁니다.

### S3, S4 Synthetic Data

S3, S4의 요청 구성은 LLM으로 만든 행동 CSV(`loadtest/synthetic/`)를 따릅니다 (발제 필수 항목 "가상 사용자 기반의 부하 테스트 시나리오 생성"). 부하 모양(100명 x 10회, 1초에 걸쳐 시작)과 청약 수량은 S0과 같고, 누가 어떤 행동을 하는지만 다릅니다. 프롬프트와 응답 원문, 규칙, 기대 결과는 `loadtest/synthetic/README.md`에 있습니다.

| 시나리오 | 요청 수 | 청약 행 | 확정 | 거절 | 증거금 |
| --- | --- | --- | --- | --- | --- |
| S3 | 1,000 | 920 | 860 | 60 `ALREADY_SUBSCRIBED` | 860 |
| S4 | 1,000 | 1,000 | 903 | 97 `INSUFFICIENT_BALANCE` | 903 |

- 행동 CSV의 i번째 행이 i번째 요청입니다 (VU `i / 10 + 1`의 `i % 10`번째). `vuser`는 회원 CSV의 행 번호입니다.
- S3: `RESEND`는 같은 투자자의 `NORMAL`과 같은 requestId(`RUN_ID`와 투자자 번호로 만든 값이라 회차끼리 겹치지 않음), `SECOND_ACCOUNT`는 두 번째 계좌(`accountId2`)와 다른 requestId로 보냅니다.
- k6는 요청을 보내기 전에 데이터를 확인하고 맞지 않으면 바로 멈춥니다 (종료 코드 107): 행 수와 요청 수, S3는 `SECOND_ACCOUNT` 투자자의 두 번째 계좌, S4는 회원 CSV가 잔액 부족 묶음(`lb`)인지. S4를 기본 묶음으로 돌리면 S0과 같은 요청이 나가 "거절 0"이 결함처럼 보이기 때문입니다.
- 행동 CSV를 바꾸면 `python loadtest/synthetic/validate.py s3`(또는 `s4`)로 검사하고, S3, S4 기준 스냅샷을 다시 만듭니다 (3-4절).

### S1 단계 부하와 단계별 표

S1은 도착률을 고정하는 `ramping-arrival-rate`라서 응답이 느려져도 요청 수가 줄지 않습니다. 요청마다 `stage` 태그(`warmup`, `ramp`, 단계 속도)가 붙고, 단계 값은 유지(`HOLD`) 구간의 요청만 셉니다. 단계는 경과 시간이 아니라 요청 순번 기준으로 나눕니다 (실제 실행이 설정보다 길어질 수 있어서).

- 단계별 평균, p95, 실패율은 `summary.json`의 `http_req_duration{stage:N}` 등에 있고, 단계별 표(목표 속도, 실제 처리량, 지연, 실패율)는 `python loadtest/stage_table.py --run-id $RUN`으로 만듭니다 (`--stages`, `--hold`, `--ramp`, `--warmup`을 실행 때 쓴 값과 같게 줍니다).
- 단계마다 임계값을 따로 걸어서 처음 넘는 단계가 한계 지점입니다. 밀린 반복(`dropped_iterations`)이 1건이라도 있으면 종료 코드 99이고 그 회차는 무효입니다.
- `RATE`(+`DURATION`)를 주면 한 속도로만 일정하게 실행합니다 (`find-limit.sh`, `official.sh`가 사용).

### S1에서 접수→확정 시간(G2) 재기 (`IPO_IDS`)

회원 1,000명을 돌려 쓰면 1,000건 이후는 모두 이중청약 거절이라 확정 건이 생기지 않습니다. `-e IPO_IDS=<공모주 ID>,<공모주 ID>,...`를 주면 요청 순번 `idx`마다 회원은 `idx % 회원 수`, 공모주는 `idx / 회원 수`번째를 씁니다. 같은 회원도 다른 공모주에는 청약할 수 있어서 모든 요청이 확정 경로를 탑니다.

- 필요한 공모주 수 = 올림(전체 요청 수 / 회원 수). 모자라면 거절이 섞인 값으로 판정하지 않도록 테스트를 중단합니다.
- 회원당 확정 건마다 `QUANTITY x 공모가`가 인출되고 돌아오지 않습니다. 예수금이 모자라면 입금 API(`seed_users.py --run-id <새 이름> --amount <금액>`)로 넣습니다.
- `IPO_IDS`를 주지 않으면 처리량 한계용(이중청약 거절이 섞임)으로 동작합니다.

### S1 요청 속도 찾기 (한 속도 고정)

한계를 찾은 뒤 그 **50~70%**로 고정할 때 쓰는 방법입니다. 한계 탐색 스크립트가 속도를 올려 가며 단계마다 새 공모주로 짧게 실행합니다.

```bash
loadtest/find-limit.sh                                  # 기본 50, 100, 200, 400, 800건/초를 단계당 20초씩
loadtest/find-limit.sh --rates "100 200 400 800 1600" --duration 10s
```

- 한계 신호는 **평균 응답 500ms 초과, 실패율 1% 이상, 밀린 반복(`dropped_iterations`) 1건 이상** 중 하나입니다. 처음 한계에 닿으면 멈추고(`--all`이면 끝까지), 그 속도의 50~70%를 추천합니다.
- 결과는 `results/limit-{시각}/limit.md`, 단계별 원본은 `results/limit-{시각}-r{속도}/`에 남습니다.
- 서비스를 새로 띄운 직후 첫 단계는 워밍업(JVM, 커넥션 풀)의 영향으로 낮게 나올 수 있어서, 한계에 닿은 속도를 한 번 더 확인합니다.
- 고른 속도는 `-e STAGES=...`로 넘기거나 `loadtest/k6/s1.js`의 기본값으로 정하고, 측정 전에 확정합니다.

## 7. 결과 파일

`loadtest/results/{run_id}/`에 저장됩니다. 정식 측정은 `loadtest/results/{batch}/`에 묶음 파일이 따로 생기고, 실행마다 `{batch}-W`(워밍업), `{batch}-S0-r1`, `{batch}-S1-{속도}-r{반복}` 폴더가 생깁니다.

| 정식 측정 묶음 파일 | 내용 |
| --- | --- |
| `진행.log` | 진행 기록 (재시작 시각, 토큰 재발급 시점, 회차별 처리량, 소진 대기 시간) |
| `env.txt` | 커밋, dirty 여부, 서비스별 jar sha256, OS, bash, Python, Docker, compose 버전, 설정값 전부 |
| `manifest.csv` | 실행 목록 (워밍업은 시나리오 `W`, 구간 `warmup`으로 판정에 쓰지 않음. run_id, 시나리오, 구간, 반복 번호, 목표 속도, 유지 시간, 예상 요청 수, 공모주 ID, k6 시작·종료 시각, 소진 대기, 소진 확인 시각 `drain_end`). 후처리와 판정은 열 이름으로 읽는다 |
| `warmup.csv` | 워밍업 구간마다 끝 시각, 경과 초, 요청 수, 평균 응답 ms, 분당 기울기 %, 분당 기울기 ms, 연속 통과 수, 판정 |
| `db-after.dump` | 측정이 끝난 DB 덤프 (`pg_dump -Fc --create`. `pg_restore --create -d postgres`로 되살린다) |
| `s1-plan.txt` | S1 단계·반복별 공모주 ID (`속도 반복번호 공모주,공모주,...`) |
| `판정.md` | G1, G2 판정 (실행별 표, S0 중앙값, S1 단계별, 한계 지점). S3, S4가 있으면 회차별 정합성 표, 시나리오별 중앙값, S0 대비 표 |
| `docker-stats.raw` | docker stats 원본 (`<epoch초>\t<JSON>` 한 줄씩, 조회 실패는 `ERROR` 줄) |
| `docker-stats.csv` | 위 원본을 숫자로 바꾼 표 (컨테이너별 CPU %(100% = 1코어), 메모리 사용·상한 바이트, PIDs, 네트워크, 디스크) |
| `slow-queries-before.txt` | 측정 시작 전 `pg_stat_statements` 누적 (비우기 전, 기록용) |
| `autovacuum-before.csv`, `autovacuum.md` | 첫 실행 직전 테이블별 autovacuum, autoanalyze 누적 횟수 / 실행별로 그 사이에 돈 테이블과 횟수 (4장) |
| `자원.md`, `자원.err` | 실행마다 k6 구간의 컨테이너별 CPU 최대·평균, CPU 상한 대비, 메모리 최대. k6는 `k6-<run_id>` 컨테이너 |

| 실행별 파일 | 내용 |
| --- | --- |
| `summary.json` | k6 요약 (처리량, 평균, p90, p95, p99, 오류율, 임계값 판정) |
| `requests.log` | 요청마다 requestId, subscriptionId, 상태 코드, stage |
| `precheck.txt` | 사전 점검 결과 (DLT 메시지 수 포함) |
| `sql_result.txt` | 정합성 SQL 결과 (정식 측정은 공모주마다 `verify-{번호}/sql_result.txt`. 전체 계좌 기준인 2, 3번은 `verify-1`에만 결과가 있고 나머지는 "건너뜀"으로 남는다. 실행 결과 메시지는 `verify.out`) |
| `check_requests.txt` | 요청 분류 결과 (정식 측정) |
| `report.md` | 회차 보고서 (k6 요약, 결과 분류, 서비스별 서버 지표) |
| `prometheus.json` | 보고서의 서버 지표 질의와 응답 원본 |
| `slow-queries.txt` | 이 실행 동안의 쿼리 통계 (총 실행 시간, 평균 실행 시간 상위 10개. 정식 측정) |
| `autovacuum.csv` | 이 실행이 끝나고 소진 확인 뒤 테이블별 행 수, 죽은 행 수, autovacuum, autoanalyze 누적 횟수와 마지막 시각(epoch) (정식 측정) |

- 합격 기준은 처리량 200건/초 이상, 평균 500ms 이하, 오류율 1% 미만이고, 목표 기준은 400건/초 이상, 평균 250ms 이하, p95 500ms 이하, 오류 0입니다. 기본은 합격 기준이고 `-e THRESHOLD=goal`로 목표 기준을 씁니다. 기준은 측정 전에 정하고 측정 후에는 바꾸지 않습니다.
- 기준을 어기면 종료 코드 99입니다. **`summary.json`의 `thresholds`는 `true`가 위반, `false`가 통과**입니다.
- 오류율은 시스템 오류(5xx, 타임아웃, 연결 실패)만 셉니다. 업무 거절(이중청약, 잔액 부족, 마감)은 오류율에서 제외하고 `check_requests.py`의 결과 분류로 봅니다.
- 한계 탐색 결과는 `loadtest/results/limit-{시각}/`에 따로 남습니다.
- `loadtest/results/`는 회차마다 생기는 산출물이라 `.gitignore` 대상이고 커밋하지 않습니다. 보관할 결과는 팀 문서 쪽에 따로 저장합니다.

## 8. 환경변수

| 변수 | 기본값 | 쓰는 곳 |
| --- | --- | --- |
| `WARM_RATE`, `WARM_MIN`, `WARM_MAX` | `200`, `180`, `900` | `official.sh` (워밍업 도착률 건/초, 최소, 최대 초) |
| `WARM_BUCKET`, `WARM_WINDOW`, `WARM_SLOPE_PCT`, `WARM_SLOPE_MS`, `WARM_CONSEC` | `15`, `8`, `5`, `1`, `2` | `official.sh` → `warmup.py` (구간 초, 추세 창 구간 수, 분당 기울기 허용 %, 분당 기울기 허용 ms(0이면 보지 않음), 연속 통과 수) |
| `RESTORE`, `DB_DUMP` | `1`, `1` | `official.sh` (시작할 때 기준 스냅샷 복원, 끝난 뒤 DB 덤프. `RESTORE=0`은 `env.txt`에 남고 정식 결과로 쓰지 않음) |
| `BASELINE_DIR` | `loadtest/data/baseline` | `baseline.sh`, `official.sh` (기준 스냅샷 위치) |
| `ALLOW_SUBSCRIPTIONS` | 없음 | `baseline.sh save` (`1`이면 확정 청약이 있는 DB도 저장. 3-4절) |
| `GAP` | `30` | `official.sh` (실행 사이 대기 초, 4장), `g2_run.sh` (W 환불 완료 뒤 M 마감까지 대기 초) |
| `STALL_S`, `MAX_S`, `W_MAX_S`, `SETTLE_S` | `600`, `7200`, `3600`, `20` | `g2_run.sh` (진행 정지로 볼 초, M 대기 상한 초, W 대기 상한 초, 복원 뒤 healthy에서 점검까지 대기 초) |
| `IDEM_MAX_S` | `600` | `g2_run.sh --idempotency` (account-service 시작부터 다시 받기를 다 끝낼(랙 0) 때까지 기다리는 상한 초) |
| `BASELINE_ROOT` | `loadtest/data` | `g2_run.sh` (스냅샷 이름을 찾는 폴더) |
| `SCENARIOS` | `S0,S1` | `official.sh` (실행할 시나리오. S0 필수, S0, S1, S3, S4 중에서. 4장) |
| `STAGES`, `DUR` | `100,200,300,433,600`, `90` | `official.sh` (S1 속도 단계, 단계 유지 초. 433은 G1 목표의 근거인 오픈 직후 피크, 600은 목표를 넘는 부하 확인) |
| `S1_REPEAT` | `3` | `official.sh` (S1 단계마다 반복 측정 횟수. 팀 규칙은 최소 3회, 3 미만이면 판정이 참고값) |
| `S0_PRICE`, `S1_PRICE`, `QUANTITY` | `15000`, `1000`, `10` | `official.sh` (공모가, 청약 수량) |
| `PRE_VUS`, `MAX_VUS` | `500`, `2000` | `official.sh` (S1 k6 가상 사용자) |
| `P95_LIMIT`, `DRAIN_LIMIT` | `15`, `15` | `official.sh` → `judge.py` (G2 합격 기준 초) |
| `DRAIN_TIMEOUT` | `2400` | `official.sh` (소진 대기 상한 초) |
| `REQUIRED_GROUPS` | `subscription-requested subscription-margin-reserved subscription-margin-reserve-failed account-margin-reserve` | `official.sh`, `s2.sh` (lag을 확인할 필수 Consumer 그룹) |
| `STATS_INTERVAL` | `5` | `official.sh`, `g2_run.sh` (docker stats 기록 간격 초, 조회 시간 포함. `0`이면 기록하지 않음. 개선 전후는 같은 값) |
| `SKIP_BUILD`, `ALLOW_DIRTY` | `0`, `0` | `official.sh`, `s2.sh` (빌드·배포 건너뛰기, 커밋 안 한 변경 허용. 둘 다 `env.txt`에 남고 `ALLOW_DIRTY=1` 결과는 정식 결과로 쓰지 않음) |
| `IPO_IDS` | 없음 | `s1.js` (`-e IPO_IDS=...`, 6장) |
| `BEHAVIOR_CSV` | `../synthetic/<시나리오>-behavior.csv` | `s3.js`, `s4.js` (`-e BEHAVIOR_CSV=...`, 행동 CSV 경로. k6 스크립트 기준 상대 경로) |
| `S4_PREFIX` | `lb` | `official.sh` → `s4.js`, `judge.py --s4-prefix` (잔액 부족 묶음의 loginId 접두사. 회원 CSV가 이 묶음이 아니면 시작 전에 멈춤) |
| `PG_CONTAINER` | `ipo-postgres` | `new_ipo.sh`, `seed_users_sql.sh`, `verify.sh`, `precheck.sh`, `official.sh`(하위 스크립트와 `judge.py --container`에 같은 값을 넘긴다) |
| `KAFKA_CONTAINER` | `ipo-kafka` | `precheck.sh`, `baseline.sh` |
| `EUREKA_URL` | `http://localhost:8761` | `precheck.sh` |
| `K6_MODE` | `auto` | `run-k6.sh` (`auto`, `local`, `container`) |
| `K6_IMAGE` | `grafana/k6:1.3.0` | `run-k6.sh` (컨테이너 실행) |
| `K6_NETWORK` | `ipo-subscription_default` | `run-k6.sh` (컨테이너 실행, compose 네트워크 이름. `official.sh`는 게이트웨이 컨테이너의 네트워크를 읽어 넘긴다) |
| `K6_CPUS`, `K6_MEMORY` | 없음 | `run-k6.sh` (컨테이너 실행, 예: `1.0`, `512m`. `official.sh`는 `2`, `1g`) |
| `K6_LABEL` | 없음 | `run-k6.sh` (컨테이너 실행 시 `loadtest.batch` 라벨. `official.sh`가 중단될 때 자기가 띄운 k6만 멈추는 데 쓴다) |
| `K6_NAME` | 없음 | `run-k6.sh` (컨테이너 실행 시 컨테이너 이름. `official.sh`는 `k6-<run_id>`로 줘서 docker stats에서 실행별 k6를 구분한다) |
| `BASE_URL` | 로컬 `http://localhost:8080`(`seed_users.py`는 `http://127.0.0.1:8080`), 컨테이너 `http://api-gateway:8080` | `run-k6.sh`, `seed_users.py` (`seed_users.py`는 요청마다 새로 연결해서 `localhost`면 Windows에서 요청마다 약 2초가 더 걸린다. `PROMETHEUS_URL`과 같은 이유) |
| `PG_DB` | `ipo` | `seed_users_sql.sh` (회원을 넣을 데이터베이스) |
| `LOADTEST_PASSWORD` | `loadtest1234` | `seed_users.py`, `seed_users_sql.sh` (시드 회원의 비밀번호) |
| `PROMETHEUS_URL` | `http://127.0.0.1:9090` | `report.py`, `official.sh`(워밍업 판정) (`--prometheus`로도 지정. Windows에서 `localhost`는 IPv6를 먼저 시도해 연결마다 약 2초가 더 걸려서 `127.0.0.1`을 쓴다) |

## 9. 스크립트 테스트

`official.sh`, `g2_run.sh`, `baseline.sh`, `seed_users_sql.sh`, `g2_ipo.sh`, `new_ipo.sh`, `k6/g2-quantity.js`, `g2_estimate.py`, `judge.py`, `warmup.py`, `docker_stats.py`, `consumer_stats.py`, `autovacuum.py`, `synthetic/`, `s2.sh`, `s2_judge.py`를 고치면 측정 전에 테스트를 돌립니다. Docker, DB 없이 돌고 1분 안에 끝납니다.

```bash
bash loadtest/tests/test_g2_run.sh        # 대량 환불 회차 실행기 (인자, ipo-ids.txt, 스냅샷 검사, 환불 대기의 정지·상한·실패, verify.sql 출력 읽기(실제 출력 고정 데이터 포함), 판정, manifest, 멱등성 검사의 Kafka 출력 읽기와 실패 처리)
bash loadtest/tests/test_official.sh      # 셸 함수 (Kafka lag, DLT, git, 소진 대기, 토큰, 워밍업 실행 인자). 조회 실패를 0으로 넘기지 않는지 확인
bash loadtest/tests/test_baseline.sh      # 기준 스냅샷 (저장 거부 조건, ALLOW_SUBSCRIPTIONS 저장 조건, sha256, 복원 순서, 복원 뒤 상태 비교, Kafka 볼륨 고르기)
bash loadtest/tests/test_seed_users_sql.sh   # SQL 회원 시드 (옵션 검사, loginId 규칙, BCrypt 비용 확인, 넣기 실패 시 CSV 없음)
bash loadtest/tests/test_g2_ipo.sh        # 대량 환불 공모주 (일반청약 물량 계산과 짝수, 옵션 검사, W와 M 물량, 실패 시 종료 코드, new_ipo.sh --far)
node loadtest/tests/test_g2_quantity.mjs  # 대량 환불 청약 수량 (케이스 A 172주, 케이스 B 분포와 평균, 잘못된 입력). Node가 필요하다
python loadtest/tests/test_warmup.py      # 워밍업 판정 (기울기 계산, 최소 시간, 연속 통과, 최대 시간, 조회 실패)
python loadtest/tests/test_g2_estimate.py   # 212만 건 추정 (규칙 경계 r = 0.9, 1.1, 결정 문서 계산 예시, 중앙값, 제외 회차, 측정 불가, 입력 부족, 판정 경계)
python loadtest/tests/test_judge.py       # 판정 (G1, G2 경계값, 무효 회차, S1 한계 지점, 실제 조회 경로의 인자, S3, S4 정합성과 S0 대비)
bash loadtest/tests/test_s2.sh            # S2 셸 함수 (Kafka lag, 필수 Consumer 그룹 없음, 소진 대기, 마감 대기, 커밋 안 된 변경 거부, 마감 유예 확인, 공모주 생성, 요청 계획 반올림, jar 비교, 판정에 계획 요청 수 전달). 조회 실패를 0이나 마감으로 넘기지 않는지 확인
python loadtest/tests/test_s2_judge.py    # S2 판정 (요청 수가 계획과 다르거나 stage 없는 로그 행은 입력 오류, 회차 무효 조건(배경은 202만, k6 응답 검사 실패), 마감되지 않은 공모주, sql_result.txt 20~22번 읽기와 입력 오류, 202인데 행 없음)
python loadtest/tests/test_docker_stats.py   # docker stats 정리 (단위 변환, 실행 구간, 다른 실행의 k6 섞임, 빠진 구간은 실패)
python loadtest/tests/test_consumer_stats.py # 인출 Consumer 정리 (랙 증가 추세 + 처리량 정체만 병목 의심, 조회 실패와 수집 누락은 실패)
python loadtest/tests/test_autovacuum.py  # 실행별 autovacuum 차이 (새 테이블, 기록 없는 실행, 통계 초기화, 형식 오류)
python loadtest/tests/test_synthetic.py   # 행동 CSV 규칙과 기대 결과, 생성 코드 재실행 결과가 커밋한 CSV와 같은지, 생성 코드가 응답 원문과 같은지
```

- `loadtest/tests/fixtures/consumer-groups.txt`는 `kafka-consumer-groups.sh --describe --all-groups`의 실제 출력, `docker-stats.raw`는 `docker stats --no-stream --format '{{json .}}'`의 실제 출력, `g2-verify-M.txt`, `g2-verify-W.txt`는 대량 환불 1천 건 회차의 `verify.sql` 실제 출력입니다. 출력 형식이 바뀌면(verify.sql 절 추가 포함) 새로 받아 바꿉니다.
- 테스트를 통과해도 실제 환경 경로(빌드, 재시작, k6, 후처리)는 확인되지 않습니다. 고친 뒤에는 4장의 시험 실행을 한 번 돌립니다.

## 10. 주의

- 측정 중에는 다른 무거운 프로그램(브라우저, 빌드, IDE 인덱싱)을 끕니다. IntelliJ를 켜 두었다면 서비스는 Docker에서만 띄우고, 노트북은 전원을 연결합니다.
- 토큰은 발급 후 1시간 안에 모든 회차를 끝냅니다. 넘으면 401 `TOKEN_EXPIRED`가 섞입니다 (`precheck.sh`가 50분을 넘으면 알려 줍니다).
- 보고서의 서버 지표는 구간 안의 모든 요청을 셉니다 (앞뒤 여유 10초 포함). 서버 요청 수가 k6 요청 수와 다르면 시드나 이전 회차의 요청이 섞인 것이니, 시드나 이전 회차 직후에는 바로 측정하지 않습니다.
- 한 요청은 한 회원입니다. 같은 회원이 같은 공모주에 여러 번 요청하면 이중청약 거절이 섞입니다. S1에서 확정 건을 계속 만들려면 `IPO_IDS`로 공모주를 돌려 씁니다 (6장).
- 결과를 쓸 때는 "로컬 단일 머신, Docker, 인스턴스 1개" 같은 환경 한계를 같이 씁니다. 측정하지 않은 값은 "측정하지 않음"이라고 씁니다.
