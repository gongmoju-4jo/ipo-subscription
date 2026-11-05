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
| `loadtest/verify/judge.py` | 정식 측정의 G1, G2 판정, S3, S4 정합성 판정과 S0 대비 (`판정.md`) |
| `loadtest/baseline.sh` | 기준 상태 스냅샷 저장(`save`), 복원(`restore`, DB와 Kafka), 확인(`check`), 덤프(`dump`). 3-4절 |
| `loadtest/verify/warmup.py` | 정식 측정 워밍업 판정 (시간 구간별 응답 시간 추세의 기울기) |
| `loadtest/verify/docker_stats.py` | 정식 측정 중 기록한 docker stats를 CSV와 실행별 컨테이너 CPU, 메모리 표(`자원.md`)로 정리 |
| `loadtest/verify/consumer_stats.py` | 정식 측정이 끝난 뒤 따로 실행. S1의 증거금 인출 Consumer 처리량, 랙 추이, 오류율, 병목 의심 단계 (`인출.md`, `인출.csv`, `인출-prometheus.json`). `python loadtest/verify/consumer_stats.py --batch <이름>` |
| `loadtest/tests/` | `official.sh`, `baseline.sh`, `judge.py`, `warmup.py`, `docker_stats.py`, `consumer_stats.py`, `synthetic/validate.py` 테스트 (Docker, DB 없이 실행). 9장 |
| `loadtest/synthetic/` | S3, S4 Synthetic Data: LLM 프롬프트와 응답 원문, 행동 CSV, 생성 코드, 검증(`validate.py`). 6장, `loadtest/synthetic/README.md` |
| `loadtest/seed/seed_users.py` | 회원, 계좌, 예수금, 로그인 토큰 CSV 생성 (`--behavior`로 S3 두 번째 계좌, S4 입금액을 행동 CSV에서 읽음) |
| `loadtest/seed/new_ipo.sh` | 회차용 새 공모주 삽입 (`--closed`는 마감된 공모주, `--count N`은 N개를 SQL 한 번으로) |
| `loadtest/precheck.sh` | 회차 시작 전 점검 (Eureka, 토큰 나이, Outbox, DLT, Consumer lag) |
| `loadtest/run-k6.sh` | 시나리오 실행, 원본 저장 |
| `loadtest/k6/` | 시나리오 스크립트 `s0.js` ~ `s4.js`, 공통 코드 `lib.js` |
| `loadtest/verify/check_requests.py` | 요청 로그 분석 (결과 분류, 재전송 응답 일치) |
| `loadtest/verify.sh`, `loadtest/sql/verify.sql` | 정합성 SQL 19종 실행 (11~15는 배정, 정산 뒤 검사, 16~19는 재전송, 이중청약, 잔액 부족 검사. `--each-dir`는 공모주 여러 개를 psql 한 번으로 검사하고, 전체 계좌 기준 검사 2개는 첫 공모주만) |
| `loadtest/report.py` | 회차 보고서 생성 (k6 요약, 요청 로그, 같은 시간대의 Prometheus 지표를 `report.md`로) |
| `loadtest/stage_table.py` | S1 단계별 표 생성 (목표 속도, 실제 처리량, 지연, 실패율) |
| `loadtest/find-limit.sh` | S1 요청 속도를 정하기 위한 한계 탐색 (속도를 올려 가며 짧게 실행) |
| `loadtest/sql/slow-queries.sql` | 슬로우 쿼리 확인 |

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

## 5. 회차를 하나씩 실행

정식 측정이 아닌 개별 확인용입니다. 회차마다 새 공모주를 씁니다. 같은 공모주를 다시 쓰면 회원 전원이 이중청약으로 거절됩니다.

```bash
RUN=S0-r1-1102                               # run_id: 시나리오-회차-날짜
IPO=$(loadtest/seed/new_ipo.sh)              # 새 공모주 ID (S2는 --closed)

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
| S2 | S0과 같은 구성으로 마감된 공모주에 요청 | `new_ipo.sh --closed` | `users.csv` | 전부 422 `SUBSCRIPTION_CLOSED` |
| S3 | Synthetic Data `s3-behavior.csv`: 첫 청약 860 + 같은 requestId 재전송 80 + 다른 계좌 청약 60 (S0과 같은 100명 x 10회) | 새 공모주 | `users.csv` (S3 행동 CSV로 두 번째 계좌 시드) | 전부 202, 재전송 응답의 `subscriptionId`가 원래 요청과 같음. 이중청약 거절은 Consumer |
| S4 | Synthetic Data `s4-behavior.csv`: 투자자 1,000명 x 1건, 97명 예수금 부족 (S0과 같은 100명 x 10회) | 새 공모주 | `users-s4.csv` (S4 행동 CSV의 입금액으로 시드) | 전부 202 (잔액 부족 판정은 Consumer) |

- **S1과 S2의 조건(요청 속도, 유지 시간, 요청 구성)은 임시 값입니다.** 첫 측정 전에 확정하고, 측정을 시작한 뒤에는 바꾸지 않습니다.
- S0~S2는 `VUS`, `ITERATIONS`로 규모를 줄일 수 있습니다 (예: 스모크는 `-e VUS=1 -e ITERATIONS=1`). S3, S4는 요청 수가 행동 CSV 행 수(1,000)와 같아야 해서 줄이면 시작 전에 멈춥니다.

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
| `GAP` | `30` | `official.sh` (실행 사이 대기 초, 4장) |
| `SCENARIOS` | `S0,S1` | `official.sh` (실행할 시나리오. S0 필수, S0, S1, S3, S4 중에서. 4장) |
| `STAGES`, `DUR` | `100,200,300,433,600`, `90` | `official.sh` (S1 속도 단계, 단계 유지 초. 433은 G1 목표의 근거인 오픈 직후 피크, 600은 목표를 넘는 부하 확인) |
| `S1_REPEAT` | `3` | `official.sh` (S1 단계마다 반복 측정 횟수. 팀 규칙은 최소 3회, 3 미만이면 판정이 참고값) |
| `S0_PRICE`, `S1_PRICE`, `QUANTITY` | `15000`, `1000`, `10` | `official.sh` (공모가, 청약 수량) |
| `PRE_VUS`, `MAX_VUS` | `500`, `2000` | `official.sh` (S1 k6 가상 사용자) |
| `P95_LIMIT`, `DRAIN_LIMIT` | `15`, `15` | `official.sh` → `judge.py` (G2 합격 기준 초) |
| `DRAIN_TIMEOUT` | `2400` | `official.sh` (소진 대기 상한 초) |
| `REQUIRED_GROUPS` | `subscription-requested subscription-margin-reserved subscription-margin-reserve-failed account-margin-reserve` | `official.sh` (lag을 확인할 필수 Consumer 그룹) |
| `STATS_INTERVAL` | `5` | `official.sh` (docker stats 기록 간격 초, 조회 시간 포함. `0`이면 기록하지 않음. 개선 전후는 같은 값) |
| `SKIP_BUILD`, `ALLOW_DIRTY` | `0`, `0` | `official.sh` (빌드·배포 건너뛰기, 커밋 안 한 변경 허용. 둘 다 `env.txt`에 남고 `ALLOW_DIRTY=1` 결과는 정식 결과로 쓰지 않음) |
| `IPO_IDS` | 없음 | `s1.js` (`-e IPO_IDS=...`, 6장) |
| `BEHAVIOR_CSV` | `../synthetic/<시나리오>-behavior.csv` | `s3.js`, `s4.js` (`-e BEHAVIOR_CSV=...`, 행동 CSV 경로. k6 스크립트 기준 상대 경로) |
| `S4_PREFIX` | `lb` | `official.sh` → `s4.js`, `judge.py --s4-prefix` (잔액 부족 묶음의 loginId 접두사. 회원 CSV가 이 묶음이 아니면 시작 전에 멈춤) |
| `PG_CONTAINER` | `ipo-postgres` | `new_ipo.sh`, `verify.sh`, `precheck.sh`, `official.sh`(하위 스크립트와 `judge.py --container`에 같은 값을 넘긴다) |
| `KAFKA_CONTAINER` | `ipo-kafka` | `precheck.sh`, `baseline.sh` |
| `EUREKA_URL` | `http://localhost:8761` | `precheck.sh` |
| `K6_MODE` | `auto` | `run-k6.sh` (`auto`, `local`, `container`) |
| `K6_IMAGE` | `grafana/k6:1.3.0` | `run-k6.sh` (컨테이너 실행) |
| `K6_NETWORK` | `ipo-subscription_default` | `run-k6.sh` (컨테이너 실행, compose 네트워크 이름. `official.sh`는 게이트웨이 컨테이너의 네트워크를 읽어 넘긴다) |
| `K6_CPUS`, `K6_MEMORY` | 없음 | `run-k6.sh` (컨테이너 실행, 예: `1.0`, `512m`. `official.sh`는 `2`, `1g`) |
| `K6_LABEL` | 없음 | `run-k6.sh` (컨테이너 실행 시 `loadtest.batch` 라벨. `official.sh`가 중단될 때 자기가 띄운 k6만 멈추는 데 쓴다) |
| `K6_NAME` | 없음 | `run-k6.sh` (컨테이너 실행 시 컨테이너 이름. `official.sh`는 `k6-<run_id>`로 줘서 docker stats에서 실행별 k6를 구분한다) |
| `BASE_URL` | 로컬 `http://localhost:8080`, 컨테이너 `http://api-gateway:8080` | `run-k6.sh`, `seed_users.py` |
| `LOADTEST_PASSWORD` | `loadtest1234` | `seed_users.py` (시드 회원의 비밀번호) |
| `PROMETHEUS_URL` | `http://127.0.0.1:9090` | `report.py`, `official.sh`(워밍업 판정) (`--prometheus`로도 지정. Windows에서 `localhost`는 IPv6를 먼저 시도해 연결마다 약 2초가 더 걸려서 `127.0.0.1`을 쓴다) |

## 9. 스크립트 테스트

`official.sh`, `baseline.sh`, `judge.py`, `warmup.py`, `docker_stats.py`, `consumer_stats.py`, `synthetic/`를 고치면 정식 측정 전에 테스트를 돌립니다. Docker, DB 없이 돌고 1분 안에 끝납니다.

```bash
bash loadtest/tests/test_official.sh      # 셸 함수 (Kafka lag, DLT, git, 소진 대기, 토큰, 워밍업 실행 인자). 조회 실패를 0으로 넘기지 않는지 확인
bash loadtest/tests/test_baseline.sh      # 기준 스냅샷 (저장 거부 조건, sha256, 복원 순서, 복원 뒤 상태 비교, Kafka 볼륨 고르기)
python loadtest/tests/test_warmup.py      # 워밍업 판정 (기울기 계산, 최소 시간, 연속 통과, 최대 시간, 조회 실패)
python loadtest/tests/test_judge.py       # 판정 (G1, G2 경계값, 무효 회차, S1 한계 지점, 실제 조회 경로의 인자, S3, S4 정합성과 S0 대비)
python loadtest/tests/test_docker_stats.py   # docker stats 정리 (단위 변환, 실행 구간, 다른 실행의 k6 섞임, 빠진 구간은 실패)
python loadtest/tests/test_consumer_stats.py # 인출 Consumer 정리 (랙 증가 추세 + 처리량 정체만 병목 의심, 조회 실패와 수집 누락은 실패)
python loadtest/tests/test_synthetic.py   # 행동 CSV 규칙과 기대 결과, 생성 코드 재실행 결과가 커밋한 CSV와 같은지, 생성 코드가 응답 원문과 같은지
```

- `loadtest/tests/fixtures/consumer-groups.txt`는 `kafka-consumer-groups.sh --describe --all-groups`의 실제 출력, `docker-stats.raw`는 `docker stats --no-stream --format '{{json .}}'`의 실제 출력입니다. 출력 형식이 바뀌면 새로 받아 바꿉니다.
- 테스트를 통과해도 실제 환경 경로(빌드, 재시작, k6, 후처리)는 확인되지 않습니다. 고친 뒤에는 4장의 시험 실행을 한 번 돌립니다.

## 10. 주의

- 측정 중에는 다른 무거운 프로그램(브라우저, 빌드, IDE 인덱싱)을 끕니다. IntelliJ를 켜 두었다면 서비스는 Docker에서만 띄우고, 노트북은 전원을 연결합니다.
- 토큰은 발급 후 1시간 안에 모든 회차를 끝냅니다. 넘으면 401 `TOKEN_EXPIRED`가 섞입니다 (`precheck.sh`가 50분을 넘으면 알려 줍니다).
- 보고서의 서버 지표는 구간 안의 모든 요청을 셉니다 (앞뒤 여유 10초 포함). 서버 요청 수가 k6 요청 수와 다르면 시드나 이전 회차의 요청이 섞인 것이니, 시드나 이전 회차 직후에는 바로 측정하지 않습니다.
- 한 요청은 한 회원입니다. 같은 회원이 같은 공모주에 여러 번 요청하면 이중청약 거절이 섞입니다. S1에서 확정 건을 계속 만들려면 `IPO_IDS`로 공모주를 돌려 씁니다 (6장).
- 결과를 쓸 때는 "로컬 단일 머신, Docker, 인스턴스 1개" 같은 환경 한계를 같이 씁니다. 측정하지 않은 값은 "측정하지 않음"이라고 씁니다.
