#!/usr/bin/env bash
# s6.sh 함수 단위 테스트 (Docker, DB 없이 돈다. 외부 명령은 가짜 함수로 바꾼다)
# 사용: bash loadtest/tests/test_s6.sh   (인자를 주면 s6.sh 경로)
# shellcheck disable=SC2034,SC2329,SC2015
#   SC2034: OUT, RUN_ID 등은 불러온 s6.sh 함수가 쓰는 전역 변수라 여기서 값을 바꾼다
#   SC2329: docker, pg_now 등 가짜 함수는 s6.sh 함수가 간접 호출한다
#   SC2015: '조건 && ok || bad'에서 ok는 항상 0을 돌려준다
set -u
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1090,SC1091
source "${1:-$HERE/../s6.sh}"
PASS=0; FAIL=0
ok()   { PASS=$((PASS + 1)); printf 'ok   %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf 'FAIL %s (%s)\n' "$1" "$2"; }
expect_out() { # 이름, 기대 rc, 기대 출력, 명령...
  local name="$1" erc="$2" eout="$3" out rc; shift 3
  out="$("$@" 2>/dev/null)"; rc=$?
  if [ "$rc" = "$erc" ] && [ "$out" = "$eout" ]; then ok "$name"; else bad "$name" "rc=$rc out='$out' (기대 rc=$erc out='$eout')"; fi
}

# ---------- 기본값: s2.sh가 덮어쓰는 RATE, 컨테이너 목록 ----------
[ "$RATE" = 100 ] && ok "기본 RATE는 s2.sh의 274가 아니라 100" || bad "기본 RATE" "RATE=$RATE"
[ "$DURATION" = 10 ] && [ "$KILL_AT" = 3 ] && [ "$OUTAGE" = 10 ] && ok "기본 DURATION 10, KILL_AT 3, OUTAGE 10" || bad "기본값" "$DURATION $KILL_AT $OUTAGE"
[ "$WARM" = 0 ] && ok "기본 WARM은 s2.sh의 1이 아니라 0 (워밍업 없음)" || bad "기본 WARM" "WARM=$WARM"
[[ " $ALL_CONTAINERS " == *" ipo-redis "* ]] && ok "컨테이너 대기 목록에 ipo-redis가 있음" || bad "ipo-redis" "$ALL_CONTAINERS"
[[ " $ALL_CONTAINERS " == *" ipo-account-service "* ]] && ok "기존 컨테이너(계좌 서비스)도 그대로 있음" || bad "기존 목록" "$ALL_CONTAINERS"
[[ "$REQUIRED_GROUPS" != *subscription-requested* ]] && [ "$IGNORE_GROUPS" = "subscription-requested" ] && ok "Consumer 그룹 기본값은 s2.sh와 같음" || bad "그룹 기본값" "$REQUIRED_GROUPS / $IGNORE_GROUPS"

# ---------- validate_params ----------
vp() { RATE="$1"; DURATION="$2"; KILL_AT="$3"; OUTAGE="$4"; validate_params "$5"; }
expect_out "validate_params: 기본값, 회원 1,000명이면 통과" 0 "" vp 100 10 3 10 1000
expect_out "validate_params: 요청 수(1,000)가 회원 수(999)보다 많으면 실패" 1 "" vp 100 10 3 10 999
expect_out "validate_params: KILL_AT이 DURATION과 같으면 실패 (종료가 부하 도중이어야 함)" 1 "" vp 100 10 10 10 1000
expect_out "validate_params: RATE가 숫자가 아니면 실패" 1 "" vp abc 10 3 10 1000
expect_out "validate_params: OUTAGE 0이면 실패" 1 "" vp 100 10 3 0 1000
expect_out "validate_params: 연습 실행 값(20, 10, 3, 10)은 통과" 0 "" vp 20 10 3 10 1000
WARM=1; expect_out "validate_params: WARM=1이면 통과" 0 "" vp 100 10 3 10 1000
WARM=2; expect_out "validate_params: WARM이 0, 1이 아니면 실패" 1 "" vp 100 10 3 10 1000
WARM=0
out="$(vp 100 10 10 10 1000 2>&1)"
[[ "$out" == *"KILL_AT(10)는 DURATION(10)보다 작아야"* ]] && ok "validate_params: 이유를 출력" || bad "validate_params 이유" "$out"
RATE=100; DURATION=10; KILL_AT=3; OUTAGE=10

# ---------- save_logs: 이번 회차 시작 이후 로그만 저장 (이전 회차 로그가 따라오지 않게 --since) ----------
OUT="$(mktemp -d)"
docker() { if [ "$1" = logs ]; then echo "args: $*"; fi; }
save_logs 1794000000
grep -q -- "--since 1794000000 ipo-account-service" "$OUT/account-service.log" && grep -q -- "--timestamps" "$OUT/account-service.log" && ok "save_logs: 계좌 서비스 로그에 --since를 넘김" || bad "save_logs 계좌" "$(cat "$OUT/account-service.log")"
grep -q -- "--since 1794000000 ipo-subscription-service" "$OUT/subscription-service.log" && ok "save_logs: 청약 서비스 로그에 --since를 넘김" || bad "save_logs 청약" "$(cat "$OUT/subscription-service.log")"
unset -f docker

# ---------- saga_timeout_conf: application.yml의 시간 초과 스케줄러 설정 ----------
YML="$(mktemp)"
cat > "$YML" <<'EOT'
outbox:
  publisher:
    interval: PT1S
    batch-size: 100
saga:
  timeout:  # 주석
    enabled: true
    interval: PT5S           # 쉬는 시간
    response-timeout: PT30S  # 마지막 요청 뒤
    max-attempts: 3          # 총 횟수
    batch-size: 70           # 한 번에
    alert-interval: PT5M
eureka:
  client:
    interval: PT9S
EOT
expect_out "saga_timeout_conf: saga.timeout의 4개 값만 읽음 (다른 블록의 같은 키는 무시)" 0 "interval=PT5S response-timeout=PT30S max-attempts=3 batch-size=70" saga_timeout_conf "$YML"
expect_out "saga_timeout_conf: 파일이 없으면 빈 줄" 0 "" saga_timeout_conf /nonexistent/application.yml

# ---------- dlt_snapshot: DLT 토픽별 끝 오프셋 합, 조회 실패를 0으로 넘기지 않음 ----------
# kafka_topics는 가짜로 바꾸지 않고 s6.sh의 것을 쓴다 (임시 create-topics.sh를 읽게 REPO를 바꾼다. CRLF 줄바꿈도 섞는다)
REPO_SAVE="$REPO"; REPO="$(mktemp -d)"; mkdir -p "$REPO/docker/kafka"
printf 'set -e\r\nTOPICS=(\r\n  subscription.requested\r\n  margin.reserve-requested\r\n)\r\nfor t in x; do :; done\r\n' > "$REPO/docker/kafka/create-topics.sh"
expect_out "kafka_topics: create-topics.sh의 TOPICS 목록 (CRLF 포함)" 0 $'subscription.requested\nmargin.reserve-requested' kafka_topics
KAFKA_CONTAINER=ipo-kafka
OFFS="$(mktemp)"
cat > "$OFFS" <<'EOT'
subscription.requested.DLT:0:2
subscription.requested.DLT:1:0
subscription.requested.DLT:2:1
margin.reserve-requested.DLT:0:5
EOT
docker() { sed 's/$/\r/' "$OFFS"; }   # Windows Docker 출력처럼 CRLF
expect_out "dlt_snapshot: 토픽별 파티션 합 (2+0+1 = 3, 5)" 0 $'subscription.requested.DLT 3\nmargin.reserve-requested.DLT 5' dlt_snapshot
printf 'subscription.requested.DLT:0:2\n' > "$OFFS"
expect_out "dlt_snapshot: 토픽 하나의 오프셋 행이 없으면 실패 (그 토픽을 0으로 넘기지 않음. 호출하는 쪽이 실패하면 멈춤)" 1 "subscription.requested.DLT 2" dlt_snapshot
docker() { return 1; }
expect_out "dlt_snapshot: Kafka 조회가 실패하면 실패" 1 "" dlt_snapshot
: > "$REPO/docker/kafka/create-topics.sh"   # 토픽 목록이 비면 실패해야 한다
docker() { sed 's/$/\r/' "$OFFS"; }
expect_out "dlt_snapshot: 토픽 목록을 못 읽으면 실패" 1 "" dlt_snapshot
unset -f docker
REPO="$REPO_SAVE"

# ---------- record_env: 환경 기록 (조회 실패는 "조회 실패"로) ----------
REPO="$(mktemp -d)"; mkdir -p "$REPO/svc-a/src/main/resources"
cat > "$REPO/svc-a/src/main/resources/application.yml" <<'EOT'
spring:
  datasource:
    hikari:
      maximum-pool-size: 30   # 풀
  kafka:
    producer:
      acks: all      # 모든 복제본
      # properties: acks 설명만 있는 주석 줄
logging:
  level:
    root: INFO
EOT
ALL_CONTAINERS="ipo-a ipo-b"; K6_CPUS=2; K6_MEMORY=2g; ACCOUNT_CONTAINER=ipo-account-service
docker() {
  case "$1" in
    info) echo "NCPU=8 MemTotal=17179869184 Server=27.0 OS=Docker Desktop" ;;
    exec) printf 'Picked up JAVA_TOOL_OPTIONS: -Xmx512m\nopenjdk version "21.0.2"\nOpenJDK Runtime Environment\nOpenJDK 64-Bit Server VM\n' ;;   # 실제 출력: 옵션 안내 줄 + 3줄
    inspect) if [[ "$*" == *ipo-b* ]]; then return 1; fi; echo "2000000000 1073741824" ;;
  esac
}
ENV_OUT="$(record_env)"
[[ "$ENV_OUT" == *"docker: NCPU=8 MemTotal=17179869184"* ]] && ok "record_env: Docker CPU 수, 메모리" || bad "record_env docker" "$ENV_OUT"
[[ "$ENV_OUT" == *'java: openjdk version "21.0.2" (JAVA_TOOL_OPTIONS: -Xmx512m)'* && "$ENV_OUT" != *"OpenJDK Runtime"* ]] && ok "record_env: java 버전 줄과 JVM 옵션 (\"Picked up\" 줄은 옵션으로)" || bad "record_env java" "$ENV_OUT"
[[ "$ENV_OUT" == *"자원 ipo-a cpu=2 mem=1073741824"* ]] && ok "record_env: 컨테이너 CPU(코어), 메모리 상한" || bad "record_env 자원" "$ENV_OUT"
[[ "$ENV_OUT" == *"자원 ipo-b 조회 실패"* ]] && ok "record_env: 조회에 실패한 컨테이너는 '조회 실패'" || bad "record_env 실패 표기" "$ENV_OUT"
[[ "$ENV_OUT" == *"설정 svc-a: maximum-pool-size: 30"* && "$ENV_OUT" == *"설정 svc-a: acks: all"* && "$ENV_OUT" == *"설정 svc-a(로그):     root: INFO"* ]] && ok "record_env: application.yml의 풀 크기, acks, 로그 블록 (주석은 뺌)" || bad "record_env 설정" "$ENV_OUT"
[[ "$ENV_OUT" != *"설정 svc-a: "$'\n'* && "$ENV_OUT" != *"설정 svc-a: acks 설명"* ]] && ok "record_env: 주석만 있던 줄은 빈 설정 줄로 남기지 않음" || bad "record_env 빈 줄" "$ENV_OUT"
[[ "$ENV_OUT" != *"# 풀"* ]] && ok "record_env: 설정 줄에서 주석 제거" || bad "record_env 주석" "$ENV_OUT"
[[ "$ENV_OUT" == *"한계: 로컬 단일 머신, Docker, 인스턴스 1개"* ]] && ok "record_env: 환경 한계 문구" || bad "record_env 한계" "$ENV_OUT"
docker() { return 1; }
ENV_OUT="$(record_env)"
[[ "$ENV_OUT" == *"docker: 조회 실패"* && "$ENV_OUT" == *"java: 조회 실패"* ]] && ok "record_env: docker, java 조회가 실패해도 멈추지 않고 '조회 실패'" || bad "record_env 전체 실패" "$ENV_OUT"
unset -f docker

# ---------- reserve_lag: 인출 요청 토픽 랙 (파티션 합), 조회 실패를 0으로 넘기지 않음 ----------
CG="$(mktemp)"
cat > "$CG" <<'EOT'
GROUP                  TOPIC                    PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID
account-margin-reserve margin.reserve-requested 0          10              15              5    -            -     -
account-margin-reserve margin.reserve-requested 1          -               3               -    -            -     -
account-margin-reserve margin.reserve-requested 2          4               4               0    -            -     -
account-margin-reserve other.topic              0          1               99              98   -            -     -
EOT
docker() { sed 's/$/\r/' "$CG"; }   # Windows Docker 출력처럼 CRLF
expect_out "reserve_lag: 5 + 커밋 전 3 + 0 = 8 (다른 토픽은 뺌)" 0 8 reserve_lag
docker() { echo "GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG"; }
expect_out "reserve_lag: 행이 없으면 0이 아니라 실패" 1 "" reserve_lag
docker() { echo "Error response from daemon: container is not running" >&2; return 1; }
expect_out "reserve_lag: docker 실패면 실패" 1 "" reserve_lag
REAL="$HERE/fixtures/consumer-groups.txt"
if [ -f "$REAL" ]; then
  docker() { sed 's/$/\r/' "$REAL"; }
  expect_out "reserve_lag: 실제 출력(모두 처리됨)은 0" 0 0 reserve_lag
fi

# ---------- wait_until_ms: 컨테이너 시계가 목표 시각이 될 때까지 ----------
CNT="$(mktemp)"; echo 1000 > "$CNT"
pg_now() { local v; v="$(cat "$CNT")"; echo $((v + 100)) > "$CNT"; echo "$v"; }
start=$(date +%s)
wait_until_ms 1500 && ok "wait_until_ms: 목표 시각이 되면 성공" || bad "wait_until_ms" "실패"
[ "$(cat "$CNT")" -ge 1600 ] && ok "wait_until_ms: 목표 시각 전에는 계속 기다림" || bad "wait_until_ms 대기" "$(cat "$CNT")"
pg_now() { return 1; }
expect_out "wait_until_ms: 시계 조회 실패면 실패" 1 "" wait_until_ms 1500
pg_now() { echo "abc"; }
expect_out "wait_until_ms: 시각이 숫자가 아니면 실패" 1 "" wait_until_ms 1500

# ---------- wait_account_up: healthy가 되면 그 시각을 출력 ----------
HC="$(mktemp)"; printf 'starting\nstarting\nhealthy\n' > "$HC"
IDX="$(mktemp)"; echo 0 > "$IDX"
docker() {
  local i; i="$(cat "$IDX")"; echo $((i + 1)) > "$IDX"
  sed -n "$((i + 1))p" "$HC" | sed 's/$/\r/'
}
pg_now() { echo 5555; }
expect_out "wait_account_up: starting 뒤 healthy가 되면 그 시각 출력" 0 5555 wait_account_up 10
docker() { echo "starting"; }
expect_out "wait_account_up: 제한 시간 안에 healthy가 아니면 실패" 1 "" wait_account_up 1

# ---------- cleanup: 계좌 서비스가 멈춘 채면 다시 띄움 ----------
CALLS="$(mktemp)"; : > "$CALLS"
RUN_ID="t"; LAG_PID=""; LOG=/dev/null
docker() {
  echo "$*" >> "$CALLS"
  case "$1" in inspect) echo "false" ;; esac
  return 0
}
cleanup
grep -q "^start $ACCOUNT_CONTAINER" "$CALLS" && ok "cleanup: 계좌 서비스가 멈춰 있으면 docker start" || bad "cleanup 재시작" "$(cat "$CALLS")"
grep -q "^stop -t 30 k6-t" "$CALLS" && ok "cleanup: k6 컨테이너를 멈춤" || bad "cleanup k6" "$(cat "$CALLS")"
: > "$CALLS"
docker() { echo "$*" >> "$CALLS"; case "$1" in inspect) echo "true" ;; esac; return 0; }
cleanup
grep -q "^start " "$CALLS" && bad "cleanup: 실행 중이면 다시 띄우지 않음" "$(cat "$CALLS")" || ok "cleanup: 계좌 서비스가 실행 중이면 다시 띄우지 않음"

printf '\n%s passed, %s failed\n' "$PASS" "$FAIL"
[ "$FAIL" = 0 ]
