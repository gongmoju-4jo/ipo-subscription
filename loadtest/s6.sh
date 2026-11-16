#!/usr/bin/env bash
# S6 장애 주입 한 회차: 빌드, 배포(jar 확인) → 기준 상태 복원 → 토큰 → 부하 시작 → 부하 도중 계좌 서비스 강제 종료(docker kill) → 일정 시간 뒤 재시작
#   → UP 확인 → 소진 대기 → 정합성 SQL(verify.sql, verify-s6.sql) → 판정
# 사용: bash loadtest/s6.sh --run-id <run_id>
#   run_id는 s2.sh와 같은 "시나리오-변형-r회차" 형식이다 (S2-n-r1, S2-g1-r1처럼)
#   예) S6-a 짧은 장애: WARM=1 OUTAGE=10 bash loadtest/s6.sh --run-id S6-a-r1
#       S6-b 긴 장애:   WARM=1 OUTAGE=80 bash loadtest/s6.sh --run-id S6-b-r1   (1,000건 기준. 아래 OUTAGE 설명)
#   여러 회차(3회)는 verify/s6_summary.py로 한 표(중앙값, 최소~최대)로 본다
#   스모크(본 측정 전 한 번, 부하 테스트 상세 문서 3-6): ALLOW_DIRTY=1 RATE=2 DURATION=6 KILL_AT=1 OUTAGE=10 bash loadtest/s6.sh --run-id S6-smoke
#     (요청 12건으로 같은 흐름을 한 번 돌려 본다. docker kill이 약 1.7초 걸려 실제 종료는 부하 시작 약 2.7초 뒤라서 DURATION을 3보다 길게 둔다. 종료 코드 0과 s6-judge.txt의 "위반 없음"을 확인한다. 결과 폴더는 측정 결과로 쓰지 않는다)
# 결과: results/<run_id>/ (k6 원본, env.txt, precheck.txt, dlt-start.txt, dlt-end.txt, events.txt, lag.csv, sql_result.txt, sql_result_s6.txt,
#       account-service.log, subscription-service.log, s6-judge.txt)
# 시각: events.txt의 모든 시각은 컨테이너 시계(PostgreSQL 컨테이너의 date, epoch ms)다. s2.sh와 같이 시작 시각, 종료 시각을 이 시계로 정한다
#   k6_start_ms 부하 시작 / kill_ms 종료 직후 / restart_ms 재시작 명령 직후 / up_ms 컨테이너 healthy 확인 (헬스체크 간격만큼 늦게 잡힌다)
# 환경변수: RATE(100건/초), DURATION(10초. RATE x DURATION은 회원 수 이하), KILL_AT(3, 부하 시작 뒤 종료까지 초. DURATION보다 작아야 함),
#          OUTAGE(10, 종료 뒤 재시작 명령까지 초. 서비스가 실제로 돌아오는 UP까지는 여기에 약 23초가 더 걸린다 (OUTAGE 10 → 종료→UP 33초, 80 → 103초).
#                 시간 초과 보상은 스케줄러가 100건씩 5.3초마다 재요청하고 오래된 순으로 보상해서, 요청이 많으면 늦게 시작한다 (1,000건은 종료 기준 약 95~106초).
#                 1,000건 시험 실행: OUTAGE 70 → TIMEOUT 0건, 80 → 97건(재시작 뒤 확정 604건), 100 → 645건(재시작 뒤 확정 0건), 140 → 726건),
#          WARM(0, 1이면 부하 전에 워밍업. s2.sh와 같은 방식. WARM_RATE 200, WARM_MIN 180, WARM_MAX 900 등 WARM_*는 s2.sh와 같은 기본값), LEAD(25, 시작까지 여유 초), LAG_INTERVAL(2, 랙 기록 사이 쉬는 초. 조회 시간이 더해져 실제 간격은 더 길다),
#          UP_TIMEOUT(300, 재시작 뒤 healthy까지 제한 초), DRAIN_TIMEOUT(600), PRE_VUS, MAX_VUS, K6_IMAGE, K6_CPUS, K6_MEMORY,
#          REQUIRED_GROUPS, IGNORE_GROUPS (s2.sh와 같은 기본값), ALLOW_DIRTY(0), SKIP_BUILD(0, s2.sh와 같은 의미)
#   WARM=0(기본)이면 워밍업을 하지 않는다 (S6는 성능이 아니라 정합성 시나리오). 기준 상태 복원 뒤 서비스가 healthy가 되고 45초 기다린 상태에서 시작한다
#   WARM=1이면 복원 뒤 s2.sh와 같은 워밍업(응답 시간 추세가 평평해질 때까지)을 하고 소진, 30초 대기 뒤 시작한다. 결과는 results/<run_id>/warmup.csv, results/<run_id>-W/
# 종료 코드: 0 회차 유효, 위반 없음 / 4 회차 유효, 정합성 위반 있음(s6-judge.txt) / 3 회차 무효 / 5 커밋하지 않은 변경 또는 컨테이너 jar가 로컬 jar와 다름
#          9 사전 점검 실패(precheck.txt) / 14 기준 상태 복원 실패 / 그 밖은 준비, 실행 실패
# s2.sh를 source해서 함수를 재사용한다 (source로 불러오면 함수만 정의하고 실행하지 않는다. tests/test_s6.sh)
set -uo pipefail
export MSYS_NO_PATHCONV=1
# s2.sh가 같은 이름의 변수 기본값(RATE=274 등)을 덮어쓰므로, S6 기본값은 source 전에 받아 둔다
S6_RATE="${RATE:-100}"; S6_DURATION="${DURATION:-10}"; S6_WARM="${WARM:-0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/s2.sh"
RATE="$S6_RATE"; DURATION="$S6_DURATION"; WARM="$S6_WARM"
KILL_AT="${KILL_AT:-3}"; OUTAGE="${OUTAGE:-10}"; LAG_INTERVAL="${LAG_INTERVAL:-2}"; UP_TIMEOUT="${UP_TIMEOUT:-300}"
ACCOUNT_CONTAINER="${ACCOUNT_CONTAINER:-ipo-account-service}"
RESERVE_TOPIC="${RESERVE_TOPIC:-margin.reserve-requested}"; RESERVE_GROUP="${RESERVE_GROUP:-account-margin-reserve}"
# 게이트웨이가 Redis(입장 허가)에 의존하므로 s2.sh의 컨테이너 대기 목록에 Redis를 더한다
ALL_CONTAINERS="$ALL_CONTAINERS ipo-redis"
LAG_PID=""; WRUN=""; WARM_SEC=0

is_uint() { case "${1:-}" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }

# 인자: 회원 수. RATE, DURATION, KILL_AT, OUTAGE를 확인한다. 틀리면 이유를 출력하고 실패
validate_params() {
  local u="$1" v
  for v in RATE DURATION KILL_AT OUTAGE; do
    is_uint "${!v}" && [ "${!v}" -gt 0 ] || { echo "$v는 0보다 큰 정수여야 합니다: '${!v}'" >&2; return 1; }
  done
  [ "$WARM" = 0 ] || [ "$WARM" = 1 ] || { echo "WARM은 0 또는 1이어야 합니다: '$WARM'" >&2; return 1; }
  [ "$KILL_AT" -lt "$DURATION" ] || { echo "KILL_AT($KILL_AT)는 DURATION($DURATION)보다 작아야 합니다 (종료가 부하 도중이어야 함)" >&2; return 1; }
  [ $(( RATE * DURATION )) -le "$u" ] || { echo "요청 수 $(( RATE * DURATION ))건(RATE $RATE x DURATION $DURATION)이 회원 수 ${u}명보다 많습니다" >&2; return 1; }
  return 0
}

# 컨테이너 시계(ms)
pg_now() { docker exec "$PG_CONTAINER" date +%s%3N | tr -d '\r'; }
# 인자: 목표 시각(epoch ms). 컨테이너 시계가 그 시각이 될 때까지 기다린다
wait_until_ms() {
  local target="$1" now
  while :; do
    now="$(pg_now)" && is_uint "$now" || return 1
    [ "$now" -ge "$target" ] && return 0
    sleep 0.2
  done
}

# 인출 요청 토픽의 계좌 Consumer 그룹 랙 (파티션 합). 조회가 실패하거나 행이 없으면 0이 아니라 실패를 돌려준다
reserve_lag() {
  local out
  out="$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --group "$RESERVE_GROUP" 2>/dev/null)" || return 1
  printf '%s\n' "$out" | tr -d '\r' | awk -v topic="$RESERVE_TOPIC" '
    $2 == topic && $3 ~ /^[0-9]+$/ && $5 ~ /^[0-9]+$/ { lag += ($4 ~ /^[0-9]+$/) ? $5 - $4 : $5; n++ }
    END { if (n == 0) exit 1; print lag + 0 }'
}
# 백그라운드: LAG_INTERVAL마다 "시각 ms,랙"을 lag.csv에 덧붙인다. 조회에 실패한 샘플은 0으로 넣지 않고 건너뛴다
record_lag() {
  local ts lg
  echo "ts_ms,lag" > "$OUT/lag.csv"
  while :; do
    ts="$(pg_now)" || ts=""
    lg="$(reserve_lag)" || lg=""
    if is_uint "$ts" && is_uint "$lg"; then echo "$ts,$lg" >> "$OUT/lag.csv"; fi
    sleep "$LAG_INTERVAL"
  done
}
# 인자: 제한 초. 계좌 컨테이너가 healthy가 되면 그 시각(컨테이너 시계 ms)을 출력하고 성공, 제한 시간이 지나면 실패
wait_account_up() {
  local start
  start="$(date +%s)"
  while [ $(( $(date +%s) - start )) -le "$1" ]; do
    if [ "$(docker inspect -f '{{.State.Health.Status}}' "$ACCOUNT_CONTAINER" 2>/dev/null | tr -d '\r')" = healthy ]; then pg_now; return 0; fi
    sleep 1
  done
  return 1
}

# 워밍업 (WARM=1): s2.sh와 같은 방식. WARM_RATE 일정 부하를 걸고 warmup.py가 응답 시간 추세가 평평해졌다고 판정하면 멈춘 뒤, 소진, 30초 대기
# 인자: 회원 수. 끝나면 WARM_SEC에 걸린 초를 넣는다. 정상 상태에 닿지 않거나 실패하면 멈춘다
run_warmup() {
  local u="$1" wipos t0 kpid wrc d
  wipos="$(new_ipos "$WRUN" $(( ((WARM_MAX + 60) * WARM_RATE + u - 1) / u )))" || die 1 "워밍업 공모주 생성 실패"
  say "워밍업 시작: ${WARM_RATE}건/초, 최소 ${WARM_MIN}초, 최대 ${WARM_MAX}초"
  t0="$(date +%s)"
  (cd "$DIR" && K6_MODE=container K6_IMAGE="$K6_IMAGE" K6_CPUS="$K6_CPUS" K6_MEMORY="$K6_MEMORY" K6_NAME="k6-$WRUN" \
    bash run-k6.sh s1 --ipo "${wipos%%,*}" --run-id "$WRUN" -e "IPO_IDS=$wipos" -e "RATE=$WARM_RATE" -e "DURATION=$((WARM_MAX + 60))s" -e "QUANTITY=$QUANTITY") \
    > "$DIR/results/$WRUN/k6-console.out" 2>&1 &
  kpid=$!
  sleep 12
  kill -0 "$kpid" 2>/dev/null || die 1 "워밍업 k6가 시작하자마자 끝남 (results/$WRUN/k6-console.out)"
  py "$(pypath "$DIR/verify/warmup.py")" --since $((t0 + 10)) --csv "$(pypath "$OUT/warmup.csv")" --prometheus "${PROMETHEUS_URL:-http://127.0.0.1:9090}" \
    --bucket "$WARM_BUCKET" --window "$WARM_WINDOW" --slope-pct "$WARM_SLOPE_PCT" --slope-ms "$WARM_SLOPE_MS" --consecutive "$WARM_CONSEC" \
    --min "$WARM_MIN" --max "$WARM_MAX" >> "$LOG" 2>&1; wrc=$?
  docker stop -t 30 "k6-$WRUN" >/dev/null 2>&1; wait "$kpid"
  [ "$wrc" -eq 0 ] || die 1 "워밍업이 정상 상태에 닿지 않음 (warmup.py 종료 코드 $wrc, warmup.csv)"
  d="$(drain "$wipos" "$DRAIN_TIMEOUT")" || die 1 "워밍업 뒤 소진 대기 시간 초과"
  WARM_SEC=$(( $(date +%s) - t0 ))
  say "워밍업 끝: ${WARM_SEC}초, 소진 ${d}초. 30초 대기"
  sleep 30
}

# 토픽 목록은 docker/kafka/create-topics.sh 한 곳에서 관리한다 (official.sh의 kafka_topics와 같은 읽기. s2.sh에는 없어서 여기에 둔다)
kafka_topics() {
  local t
  t="$(awk '/^TOPICS=\(/{f=1; next} f && /^\)/{exit} f {gsub(/[[:space:]]/, ""); if ($0 != "") print}' "$REPO/docker/kafka/create-topics.sh")" || return 1
  [ -n "$t" ] || return 1
  printf '%s\n' "$t"
}
# DLT 토픽(<토픽>.DLT)별 끝 오프셋 합을 "토픽 건수" 줄로 출력한다 (부하 테스트 상세 문서 3-4, 6-2의 DLT 증가분용. official.sh의 kafka_dlt_total과 같은 방식).
# 토픽 목록을 못 읽거나, Kafka 조회가 실패하거나, 토픽마다 오프셋 행이 하나라도 없으면 0으로 넘기지 않고 실패한다
dlt_snapshot() {
  local topics t out n re
  topics="$(kafka_topics)" || return 1
  re="($(printf '%s\n' "$topics" | sed 's/\./\\./g' | paste -sd '|' -))\\.DLT"
  out="$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:29092 --topic "$re" 2>&1)" || return 1
  out="$(printf '%s\n' "$out" | tr -d '\r')"
  for t in $topics; do
    n="$(printf '%s\n' "$out" | awk -F: -v t="$t.DLT" '$1 == t && $3 ~ /^[0-9]+$/ { s += $3; c++ } END { if (c == 0) exit 1; print s }')" || return 1
    echo "$t.DLT $n"
  done
}

# 실행 환경을 stdout으로 출력한다 (부하 테스트 상세 문서 2장 환경 기록표). env.txt에 덧붙인다. 조회에 실패한 항목은 "조회 실패"로 적는다
# Docker 안에서는 호스트 CPU 모델을 알 수 없어 Docker가 쓰는 CPU 수, 메모리를 적는다. 설정 줄은 application.yml 원문의 일부이고 값이 없으면 기본값이다
record_env() {
  local out c line f svc
  out="$(docker info --format 'NCPU={{.NCPU}} MemTotal={{.MemTotal}} Server={{.ServerVersion}} OS={{.OperatingSystem}}' 2>/dev/null | tr -d '\r')" || out=""
  echo "docker: ${out:-조회 실패}"
  out="$(docker exec "$ACCOUNT_CONTAINER" java -version 2>&1)" || out=""
  out="$(printf '%s\n' "$out" | tr -d '\r')"
  # java -version 앞에 "Picked up JAVA_TOOL_OPTIONS: ..." 줄이 붙을 수 있다. 버전 줄과 JVM 옵션을 나눠 적는다
  line="$(printf '%s\n' "$out" | grep -m1 ' version ')"; f="$(printf '%s\n' "$out" | grep -m1 '^Picked up ' | sed 's/^Picked up //')"
  echo "java: ${line:-조회 실패}${f:+ ($f)}"
  for c in $ALL_CONTAINERS; do
    line="$(docker inspect -f '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}' "$c" 2>/dev/null | tr -d '\r')" || line=""
    if [ -z "$line" ]; then echo "자원 $c 조회 실패"; continue; fi
    echo "자원 $c cpu=$(awk -v n="${line%% *}" 'BEGIN { printf "%g", n / 1e9 }') mem=${line##* } (0은 상한 없음)"
  done
  echo "자원 k6 cpu=$K6_CPUS mem=$K6_MEMORY (실행마다 k6-<run_id> 컨테이너)"
  for f in "$REPO"/*/src/main/resources/application.yml; do
    [ -f "$f" ] || continue
    svc="${f#"$REPO"/}"; svc="${svc%%/*}"
    grep -E 'maximum-pool-size|concurrency|max-threads|threads:|acks|idempotence|delivery\.timeout|request\.timeout|max\.block' "$f" 2>/dev/null \
      | sed 's/^[[:space:]]*//; s/[[:space:]]*#.*//' | sed '/^$/d' | sed "s|^|설정 $svc: |" || true
    awk '/^logging:/ { f = 1; print; next } /^[^ #]/ { f = 0 } f' "$f" 2>/dev/null \
      | sed 's/[[:space:]]*#.*//' | sed '/^[[:space:]]*$/d' | sed "s|^|설정 $svc(로그): |" || true
  done
  echo "한계: 로컬 단일 머신, Docker, 인스턴스 1개 (k6도 같은 머신에서 실행. 실제 운영 수치가 아닌 시뮬레이션)"
}

# 인자: application.yml 경로. saga.timeout(시간 초과 스케줄러)의 interval, response-timeout, max-attempts, batch-size를 "키=값 ..."으로 한 줄 출력한다
# 시간 초과 보상이 접수 몇 초 뒤에 나오는지는 이 값과 요청 수가 정한다 (한 번에 batch-size건씩만 재요청하므로 요청이 많으면 늦어진다). 못 읽으면 빈 줄
saga_timeout_conf() {
  awk '
    /^[^ #]/ { top = ($1 == "saga:"); in_t = 0 }
    top && /^  timeout:/ { in_t = 1; next }
    top && /^  [^ #]/ { in_t = 0 }
    in_t && /^    (interval|response-timeout|max-attempts|batch-size):/ { k = $1; sub(/:$/, "", k); printf "%s=%s ", k, $2 }
    END { printf "\n" }' "$1" 2>/dev/null | sed 's/ $//' || true
}

# 로그 보관: 이번 회차 부하 시작 5초 전부터만 저장한다. 컨테이너는 회차 사이에 다시 만들어지지 않아 docker logs에는 이전 회차(워밍업 포함) 로그가 쌓여 있다
# 인자: 시작 시각(epoch 초, 컨테이너 시계)
save_logs() {
  docker logs --timestamps --since "$1" "$ACCOUNT_CONTAINER" > "$OUT/account-service.log" 2>&1
  docker logs --timestamps --since "$1" "$SUB_CONTAINER" > "$OUT/subscription-service.log" 2>&1
}

# 어떤 이유로 끝나든 k6와 랙 기록을 멈추고, 계좌 서비스가 멈춘 채면 다시 띄운다 (이 실행이 죽여 놓은 서비스를 그대로 두지 않는다)
cleanup() {
  [ -n "$LAG_PID" ] && kill "$LAG_PID" 2>/dev/null
  docker stop -t 30 "k6-$RUN_ID" >/dev/null 2>&1
  [ -n "$WRUN" ] && docker stop -t 30 "k6-$WRUN" >/dev/null 2>&1
  if [ "$(docker inspect -f '{{.State.Running}}' "$ACCOUNT_CONTAINER" 2>/dev/null | tr -d '\r')" = false ]; then
    say "!! 계좌 서비스가 멈춘 채 끝남. 다시 띄움"
    docker start "$ACCOUNT_CONTAINER" >/dev/null 2>&1 || say "!! 계좌 서비스 재시작 실패. 직접 확인 필요"
  fi
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --run-id) RUN_ID="$2"; shift 2 ;;
      *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
    esac
  done
  [ -n "$RUN_ID" ] || { echo "사용: $0 --run-id <run_id>" >&2; exit 1; }
  check_dirty || exit $?
  OUT="$DIR/results/$RUN_ID"
  [ -e "$OUT" ] && { echo "이미 있는 run_id입니다: $OUT" >&2; exit 1; }
  mkdir -p "$OUT"
  LOG="$OUT/진행.log"
  pick_python || die 1 "Python 3.8 이상이 필요합니다"
  pick_compose
  trap cleanup EXIT

  docker stop -t 30 "k6-$RUN_ID" >/dev/null 2>&1 || true
  # 이 커밋으로 빌드, 배포하고 컨테이너 jar가 같은지 확인한다 (s2.sh와 같은 방식)
  if [ "${SKIP_BUILD:-0}" != 1 ]; then
    say "jar 빌드 (./gradlew build -x test)"
    (cd "$REPO" && ./gradlew build -x test -q) >> "$LOG" 2>&1 || die 1 "gradle 빌드 실패 (진행.log)"
    say "컨테이너 배포 (compose up -d --build)"
    compose "${COMPOSE_FILES[@]}" up -d --build >> "$LOG" 2>&1 || die 1 "compose up 실패 (진행.log)"
  else
    say "빌드, 배포 건너뜀 (SKIP_BUILD=1). jar 비교는 한다"
  fi
  # 기준 상태로 되돌린다: 같은 DB 크기, 같은 데이터(회원, 계좌, 예수금만), 빈 Kafka에서 시작한다. 서비스가 다시 뜬다
  say "기준 스냅샷으로 DB, Kafka 되돌리기 (baseline.sh restore)"
  (cd "$DIR" && bash baseline.sh restore) >> "$LOG" 2>&1 || die 14 "기준 상태 복원 실패 (진행.log)"
  wait_all_healthy 600 || die 1 "컨테이너가 600초 안에 모두 healthy가 되지 않음"
  local jars_info
  jars_info="$(check_jars)" || { printf '%s\n' "$jars_info" | tee -a "$LOG" >&2; die 5 "컨테이너 안 jar가 로컬 jar와 다름. 이 커밋으로 다시 빌드, 배포한다"; }
  [ -z "$(grace_env)" ] || die 1 "청약 서비스에 마감 유예 덮어쓰기가 남아 있음: $(grace_env). 덮어쓰기 없이 다시 띄운다"

  # 토큰 (1시간 만료)
  say "토큰 재발급"
  (cd "$REPO" && py loadtest/seed/seed_users.py --run-id "tok-$RUN_ID" --refresh-tokens) >> "$LOG" 2>&1 || die 1 "토큰 재발급 실패"
  local u
  u="$(($(grep -c . "$DIR/data/users.csv") - 1))"
  [ "$u" -ge 1 ] || die 1 "회원 CSV가 비어 있음"
  validate_params "$u" || die 1 "S6 설정이 올바르지 않음"
  local total=$(( RATE * DURATION ))
  if [ "$WARM" = 1 ]; then
    WRUN="$RUN_ID-W"
    [ -e "$DIR/results/$WRUN" ] && die 1 "이미 있는 워밍업 run_id입니다: $DIR/results/$WRUN"
    mkdir -p "$DIR/results/$WRUN"
    run_warmup "$u"
  fi

  # 사전 점검 (부하 테스트 상세 문서 3-4): Eureka 서비스 UP, 토큰 나이, Outbox 미발행 0, Consumer lag, DLT 메시지 수. 워밍업을 했으면 그 뒤에 한다
  say "사전 점검 (precheck.sh)"
  (cd "$DIR" && PG_CONTAINER="$PG_CONTAINER" KAFKA_CONTAINER="$KAFKA_CONTAINER" bash precheck.sh --run-id "$RUN_ID") > /dev/null 2>&1 \
    || die 9 "사전 점검 실패 (results/$RUN_ID/precheck.txt)"
  dlt_snapshot > "$OUT/dlt-start.txt" || die 1 "DLT 조회 실패 (시작)"
  local ipo
  ipo="$(new_ipos "$RUN_ID" 1)" || die 1 "공모주 생성 실패"
  # 시작 시각과 종료 시각은 컨테이너 시계로 정한다 (게이트웨이, k6가 같은 Docker 시계)
  local now_ms start_at kill_target restart_target
  now_ms="$(pg_now)" && is_uint "$now_ms" || die 1 "컨테이너 시계 조회 실패"
  start_at=$(( now_ms + LEAD * 1000 ))
  kill_target=$(( start_at + KILL_AT * 1000 ))
  {
    echo "run_id=$RUN_ID git=$(git_repo rev-parse --short HEAD) branch=$(git_repo rev-parse --abbrev-ref HEAD) dirty=$DIRTY_N allow_dirty=${ALLOW_DIRTY:-0}"
    echo "rate=$RATE duration=$DURATION total=$total users=$u kill_at=$KILL_AT outage=$OUTAGE lead=$LEAD"
    echo "saga_timeout: $(saga_timeout_conf "$REPO/subscription-service/src/main/resources/application.yml") (설정 파일 기준)"
    echo "warm=$WARM warm_seconds=$WARM_SEC warm_rate=$WARM_RATE warm_min=$WARM_MIN warm_max=$WARM_MAX"
    echo "ipo=$ipo start_at_ms=$start_at kill_target_ms=$kill_target"
    echo "k6_image=$K6_IMAGE k6_cpus=$K6_CPUS k6_memory=$K6_MEMORY pre_vus=$PRE_VUS max_vus=$MAX_VUS skip_build=${SKIP_BUILD:-0}"
    echo "required_groups=$REQUIRED_GROUPS ignore_groups=$IGNORE_GROUPS"
    printf '%s\n' "$jars_info"
    record_env
  } > "$OUT/env.txt"
  say "공모주 $ipo: ${RATE}건/초 x ${DURATION}초 = ${total}건, 부하 시작 뒤 ${KILL_AT}초에 계좌 서비스 종료, ${OUTAGE}초 뒤 재시작"

  record_lag & LAG_PID=$!
  (cd "$DIR" && K6_MODE=container K6_IMAGE="$K6_IMAGE" K6_CPUS="$K6_CPUS" K6_MEMORY="$K6_MEMORY" K6_NAME="k6-$RUN_ID" \
    bash run-k6.sh s6 --ipo "$ipo" --run-id "$RUN_ID" -e "START_AT=$start_at" -e "RATE=$RATE" -e "DURATION=$DURATION" \
    -e "PRE_VUS=$PRE_VUS" -e "MAX_VUS=$MAX_VUS") > "$OUT/k6-console.out" 2>&1 &
  local kpid=$!

  # 종료: 부하 시작 + KILL_AT초. 그때 k6가 아직 돌고 있어야 한다
  wait_until_ms "$kill_target" || die 1 "종료 시각을 기다리는 중 컨테이너 시계 조회 실패"
  kill -0 "$kpid" 2>/dev/null || die 1 "종료 시각에 k6가 이미 끝남 (k6-console.out)"
  docker kill "$ACCOUNT_CONTAINER" >> "$LOG" 2>&1 || die 1 "계좌 서비스 종료(docker kill) 실패"
  local kill_ms restart_ms up_ms
  kill_ms="$(pg_now)"
  say "계좌 서비스 종료: ${kill_ms} (부하 시작 + $(( (kill_ms - start_at) / 1000 ))초)"
  # 재시작: 종료 + OUTAGE초 (docker start. 이 컨테이너는 restart 정책이 없어 저절로 살아나지 않고, compose up은 컨테이너를 다시 만든다)
  restart_target=$(( kill_ms + OUTAGE * 1000 ))
  wait_until_ms "$restart_target" || die 1 "재시작 시각을 기다리는 중 컨테이너 시계 조회 실패"
  docker start "$ACCOUNT_CONTAINER" >> "$LOG" 2>&1 || die 1 "계좌 서비스 재시작(docker start) 실패"
  restart_ms="$(pg_now)"
  say "계좌 서비스 재시작 명령: ${restart_ms} (종료 + $(( (restart_ms - kill_ms) / 1000 ))초)"
  up_ms="$(wait_account_up "$UP_TIMEOUT")" || die 1 "계좌 서비스가 ${UP_TIMEOUT}초 안에 healthy가 되지 않음"
  say "계좌 서비스 UP(healthy): ${up_ms} (종료 + $(( (up_ms - kill_ms) / 1000 ))초)"
  {
    echo "k6_start_ms=$start_at"; echo "kill_ms=$kill_ms"; echo "restart_ms=$restart_ms"; echo "up_ms=$up_ms"
  } > "$OUT/events.txt"

  local krc
  wait "$kpid"; krc=$?
  say "k6 종료 코드 $krc (0이면 임계값 통과, 99면 임계값 위반)"
  case "$krc" in 0|99) ;; *) die 1 "k6 실행 실패 (k6-console.out)" ;; esac

  # 소진 대기: 시간 초과 보상(접수 90초 뒤)과 반환까지 끝나도록 제한 시간을 길게 둔다
  local d
  d="$(drain "$ipo" "$DRAIN_TIMEOUT")" || die 1 "소진 대기 시간 초과 (${DRAIN_TIMEOUT}초)"
  say "소진 ${d}초"
  kill "$LAG_PID" 2>/dev/null; LAG_PID=""

  # 로그 보관 (재시작 뒤 같은 eventId가 걸러졌는지, 재요청, 시간 초과 보상 로그 확인용)
  save_logs $(( start_at / 1000 - 5 ))
  # 끝 DLT: 실패해도 회차는 이어 가고, 판정에는 "기록하지 못함"으로 남긴다 (빈 파일)
  dlt_snapshot > "$OUT/dlt-end.txt" || { say "!! DLT 조회 실패 (끝). 판정에 \"기록하지 못함\"으로 남김"; : > "$OUT/dlt-end.txt"; }

  # 정합성 SQL: 기존 verify.sql(sql_result.txt), S6용 23~26번(sql_result_s6.txt). 판정이 결과를 읽으므로 실패하면 멈춘다
  (cd "$DIR" && bash verify.sh --ipo "$ipo" --run-id "$RUN_ID") >> "$LOG" 2>&1 || die 1 "verify.sh 실패 (sql_result.txt 확인)"
  {
    echo "# run_id=$RUN_ID ipo=$ipo $(date '+%Y-%m-%d %H:%M:%S')"
    docker exec -i "$PG_CONTAINER" psql -U postgres -d ipo -v ON_ERROR_STOP=1 -v ipo="'$ipo'" -f - < "$DIR/sql/verify-s6.sql"
  } > "$OUT/sql_result_s6.txt" 2>&1 || die 1 "verify-s6.sql 실패 (sql_result_s6.txt 확인)"

  py "$(pypath "$DIR/verify/s6_judge.py")" --requests "$(pypath "$OUT/requests.log")" --summary "$(pypath "$OUT/summary.json")" \
    --events "$(pypath "$OUT/events.txt")" --lag "$(pypath "$OUT/lag.csv")" --sql-s6 "$(pypath "$OUT/sql_result_s6.txt")" \
    --sql-base "$(pypath "$OUT/sql_result.txt")" --ipo "$ipo" --expect-requests "$total" --container "$PG_CONTAINER" \
    --dlt-start "$(pypath "$OUT/dlt-start.txt")" --dlt-end "$(pypath "$OUT/dlt-end.txt")" --account-log "$(pypath "$OUT/account-service.log")" \
    --warmup "$([ "$WARM" = 1 ] && echo "함 (${WARM_SEC}초, ${WARM_RATE}건/초)" || echo "안 함")" > "$OUT/s6-judge.txt"
  local jrc=$?
  tee -a "$LOG" < "$OUT/s6-judge.txt"
  case "$jrc" in
    0|3|4) say "끝: $OUT (종료 코드 $jrc)"; exit "$jrc" ;;
    *) die 1 "판정 스크립트 실패 (종료 코드 $jrc)" ;;
  esac
}

# source로 불러오면(테스트) main을 실행하지 않는다
if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
