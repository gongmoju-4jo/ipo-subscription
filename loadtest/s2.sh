#!/usr/bin/env bash
# S2 마감 경계 한 회차: 빌드, 배포(jar 확인) → 워밍업 → 대상, 배경 공모주 준비 → 대상 공모주 마감 시각 설정 → k6 s2 → 마감, 소진 대기 → 판정
# 사용: bash loadtest/s2.sh --run-id <run_id> [--grace PT1S]
#   --grace: 청약 서비스의 마감 유예(ipo.close.grace-period, 기본 PT20S)만 바꿔 다시 띄우고, 끝나면(오류로 멈춰도) 원래대로 되돌린다
# 결과: results/<run_id>/ (k6 원본, s2-judge.txt, sql_result.txt), results/<run_id>-W/ (워밍업)
# 판정 (s2-judge.txt):
#   입력 확인: 읽은 로그 행 수가 k6 요청 수와 같고, 밀린 요청이 없으면 k6 요청 수가 계획한 전체 요청 수와 같아야 판정한다 (다르면 종료 코드 1)
#   회차 유효성: 대상 요청에 202(마감 전 도착)와 422(마감 후 도착)가 모두 있어야 한다 (마감 시각이 대상 구간 안), 401이 없어야 한다
#   측정값: verify.sql 20~22번(마감 처리 뒤 생긴 청약, 마감 시각 이후 도착인데 접수된 청약, 마감 때 확정 수 차이)과 202를 받았는데 행이 없는 요청(k6 로그 대조)
# 환경변수: RATE(274), PRE(60), POST(40), TARGET_SEC(0: 회원 수 ÷ RATE초 동안 전부 대상), PRICE(1000), QUANTITY(10),
#          LEAD(25, 시작까지 여유 초), WARM(1, 0이면 워밍업 생략), WARM_*(official.sh와 같은 기본값),
#          CLOSE_TIMEOUT(300), DRAIN_TIMEOUT(600), K6_IMAGE, K6_CPUS(2), K6_MEMORY(1g),
#          PRE_VUS(500), MAX_VUS(2000)(k6 가상 사용자, official.sh S1과 같음. 202가 DB 커밋을 기다려서 DB가 잠깐 멈추면 응답이 늦어지고,
#                    가상 사용자가 모자라면 요청이 밀려 회차가 무효가 된다. 요청 속도는 RATE로 고정이라 서버 부하는 바뀌지 않는다),
#          REQUIRED_GROUPS(소진 대기에서 모두 조회돼야 하는 Consumer 그룹. official.sh와 같은 기본값),
#          IGNORE_GROUPS(subscription-requested. 랙 합에서 뺄 Consumer 그룹, official.sh와 같은 기본값. 빈 값이면 모두 셈),
#          ALLOW_DIRTY(0, 1이면 커밋하지 않은 변경이 있어도 실행. env.txt에 남고 결과로 쓰지 않는다),
#          SKIP_BUILD(0, 1이면 빌드, 배포를 건너뛴다. 서비스 5개의 로컬 jar와 컨테이너 jar sha256 비교는 그대로 하고 env.txt에 남는다.
#                     둘 다 이전 커밋의 jar여도 비교를 통과하므로, 이 커밋으로 빌드, 배포한 것을 따로 확인했을 때만 쓴다)
# 종료 코드: 0 유효 회차, 3 회차 무효(판정 파일 참고), 5 커밋하지 않은 변경 또는 컨테이너 jar가 로컬 jar와 다름, 그 밖은 준비, 실행 실패
# source로 불러오면(tests/test_s2.sh) 함수만 정의하고 실행하지 않는다
set -uo pipefail
export MSYS_NO_PATHCONV=1
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$DIR/.." && pwd)"
COMPOSE_FILES=(-f docker-compose.yml -f compose.perf.yml)
PG_CONTAINER="${PG_CONTAINER:-ipo-postgres}"; KAFKA_CONTAINER="${KAFKA_CONTAINER:-ipo-kafka}"; SUB_CONTAINER=ipo-subscription-service
RATE="${RATE:-274}"; PRE="${PRE:-60}"; POST="${POST:-40}"; TARGET_SEC="${TARGET_SEC:-0}"
PRICE="${PRICE:-1000}"; QUANTITY="${QUANTITY:-10}"; LEAD="${LEAD:-25}"
WARM="${WARM:-1}"; WARM_RATE="${WARM_RATE:-200}"; WARM_BUCKET="${WARM_BUCKET:-15}"; WARM_WINDOW="${WARM_WINDOW:-8}"
WARM_SLOPE_PCT="${WARM_SLOPE_PCT:-5}"; WARM_SLOPE_MS="${WARM_SLOPE_MS:-1}"; WARM_CONSEC="${WARM_CONSEC:-2}"; WARM_MIN="${WARM_MIN:-180}"; WARM_MAX="${WARM_MAX:-900}"
CLOSE_TIMEOUT="${CLOSE_TIMEOUT:-300}"; DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-600}"; POLL="${POLL:-2}"
REQUIRED_GROUPS="${REQUIRED_GROUPS:-subscription-margin-reserved subscription-margin-reserve-failed account-margin-reserve}"
IGNORE_GROUPS="${IGNORE_GROUPS-subscription-requested}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.3.0}"; K6_CPUS="${K6_CPUS:-2}"; K6_MEMORY="${K6_MEMORY:-1g}"
PRE_VUS="${PRE_VUS:-500}"; MAX_VUS="${MAX_VUS:-2000}"
GRACE_FILE="loadtest/data/compose.s2-grace.yml"
# 컨테이너:모듈 (jar sha256 비교 대상, official.sh와 같은 목록)
JAR_TARGETS="ipo-api-gateway:api-gateway ipo-account-service:account-service ipo-subscription-service:subscription-service ipo-allocation-service:allocation-service ipo-eureka-server:eureka-server"
ALL_CONTAINERS="ipo-postgres ipo-kafka ipo-eureka-server ipo-api-gateway ipo-account-service ipo-subscription-service ipo-allocation-service"
LOG="${LOG:-/dev/null}"; PY=""; COMPOSE=(); RUN_ID=""; WRUN=""; GRACE=""; DIRTY_N=0

# ---------- 공통 ----------
say() { printf '%s %s\n' "$(date '+%F %T')" "$*" | tee -a "$LOG" >&2; }
die() { local code="$1"; shift; say "!! $*"; exit "$code"; }
pick_python() {
  local c
  for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then PY="$c"; return 0; fi
  done
  return 1
}
pypath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
py() { "$PY" -X utf8 "$@"; }
pick_compose() { if docker compose version >/dev/null 2>&1; then COMPOSE=(docker compose); else COMPOSE=(docker-compose); fi; }
compose() { (cd "$REPO" && "${COMPOSE[@]}" "$@"); }
git_repo() { (cd "$REPO" && git "$@"); }
pg() { docker exec -i "$PG_CONTAINER" psql -U postgres -d ipo -v ON_ERROR_STOP=1 -qtA -F ' ' -c "$1"; }
new_ipo_cmd() { (cd "$DIR" && bash seed/new_ipo.sh "$@"); }

# 커밋하지 않은 변경이 있으면 결과가 어느 코드로 잰 것인지 알 수 없어서 시작하지 않는다 (official.sh와 같은 규칙. 시험 실행만 ALLOW_DIRTY=1)
# DIRTY_N에 변경 수를 남긴다. 0 통과, 5 변경 있음 또는 git 확인 실패
check_dirty() {
  local st
  st="$(git_repo status --porcelain)" || { echo "git 상태를 확인하지 못했습니다" >&2; return 5; }
  DIRTY_N="$(printf '%s' "$st" | grep -c .)"
  if [ "$DIRTY_N" != 0 ] && [ "${ALLOW_DIRTY:-0}" != 1 ]; then
    printf '%s\n' "$st" >&2
    echo "커밋하지 않은 변경 ${DIRTY_N}건. 커밋한 뒤 실행한다 (결과의 버전 추적). 시험 실행만 ALLOW_DIRTY=1" >&2
    return 5
  fi
  return 0
}

# 모든 Consumer 그룹의 랙 합 (IGNORE_GROUPS는 빼되 필수 그룹 확인에는 넣는다). 커밋 오프셋이 '-'인데 메시지가 있으면 끝 오프셋 전체를 대기로 센다 (official.sh와 같은 방식)
# 조회가 실패하거나, 읽을 행이 없거나, 필수 그룹(REQUIRED_GROUPS)이 하나라도 없으면 0이 아니라 실패를 돌려준다
# (아직 조회되지 않은 그룹의 대기를 0으로 넘기지 않는다)
kafka_lag() {
  local out
  out="$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --all-groups 2>/dev/null)" || return 1
  printf '%s\n' "$out" | tr -d '\r' \
    | awk -v req="$REQUIRED_GROUPS" -v ign="$IGNORE_GROUPS" '
        BEGIN { m = split(ign, I, " "); for (i = 1; i <= m; i++) skip[I[i]] = 1 }
        $3 ~ /^[0-9]+$/ && $5 ~ /^[0-9]+$/ { if (!($1 in skip)) lag += ($4 ~ /^[0-9]+$/) ? $5 - $4 : $5; seen[$1] = 1; n++ }
        END {
          if (n == 0) exit 1
          k = split(req, R, " "); bad = 0
          for (i = 1; i <= k; i++) if (!(R[i] in seen)) { print "필수 Consumer 그룹 없음: " R[i] > "/dev/stderr"; bad = 1 }
          if (bad) exit 1
          print lag + 0
        }'
}
outbox_unpublished() {
  pg "select (select count(*) from subscription.outbox where published_at is null) + (select count(*) from account.outbox where published_at is null) + (select count(*) from allocation.outbox where published_at is null)"
}
# 인자: 쉼표로 구분한 공모주 ID, 제한 초. 진행 중 청약 0, 미발행 Outbox 0, 랙 0이 될 때까지 기다리고 걸린 초를 출력
# 조회가 실패하면 0으로 보지 않고 다시 확인하고, 제한 시간이 지나면 실패
drain() {
  local in start p o l
  in="'$(printf '%s' "$1" | sed "s/,/','/g")'"; start="$(date +%s)"
  while [ $(( $(date +%s) - start )) -le "$2" ]; do
    if p="$(pg "select count(*) from subscription.subscription where status in ('PENDING','COMPENSATING') and ipo_id in ($in)")" \
       && o="$(outbox_unpublished)" && l="$(kafka_lag)" && [ "$p" = 0 ] && [ "$o" = 0 ] && [ "$l" = 0 ]; then
      echo $(( $(date +%s) - start )); return 0
    fi
    sleep "$POLL"
  done
  return 1
}
# 인자: 공모주 ID, 제한 초. CLOSED, ALLOCATED가 나오면 그 상태를 출력하고 성공.
# 조회가 실패해 빈 값이 나오면 마감으로 보지 않고 다시 확인하고, 제한 시간이 지나면 마지막 조회 결과를 출력하고 실패
wait_closed() {
  local start st=""
  start="$(date +%s)"
  while :; do
    st="$(pg "select status from subscription.ipo where ipo_id = '$1'")" || st=""
    case "$st" in CLOSED|ALLOCATED) echo "$st"; return 0 ;; esac
    [ $(( $(date +%s) - start )) -gt "$2" ] && { echo "${st:-조회 실패}"; return 1; }
    sleep "$POLL"
  done
}
# 인자: 이름, 개수. 공모주 ID를 쉼표로 이어 출력 (new_ipo.sh가 SQL 한 번으로 만든다). 개수가 다르면 실패
new_ipos() {
  local ids n
  ids="$(new_ipo_cmd --name "$1" --count "$2" --price "$PRICE" | tr -d '\r ')" || return 1
  n="$(printf '%s\n' "$ids" | grep -c .)"
  [ "$n" -eq "$2" ] || return 1
  printf '%s' "$ids" | paste -sd, -
}
# 인자: 회원 수. 대상 구간 ms, 전체 요청 수, 배경 공모주 수(올림((전체 요청 - 회원 수) / 회원 수))를 출력한다 (s2.js와 같은 계산)
# 반올림은 JS Math.round와 같은 floor(x + 0.5)로 한다. Python round()는 .5를 짝수 쪽으로 보내 k6가 보내는 요청 수와 1 차이 날 수 있다
plan() {
  py -c "
import math
U, RATE, PRE, POST, TS = $1, $RATE, $PRE, $POST, $TARGET_SEC
js_round = lambda x: math.floor(x + 0.5)
win = TS if TS > 0 else U / RATE
total = js_round(RATE * (PRE + win + POST))
print(js_round(win * 1000), total, math.ceil((total - U) / U))"
}
wait_healthy() {
  local i
  for i in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$SUB_CONTAINER" 2>/dev/null)" = healthy ] && { sleep 45; return 0; }
    sleep 5
  done
  return 1
}
# 인자: 제한 초. 모든 컨테이너(ALL_CONTAINERS)가 healthy가 되면 게이트웨이의 서비스 목록 반영을 위해 45초 기다리고 성공
wait_all_healthy() {
  local start c ok
  start="$(date +%s)"
  while [ $(( $(date +%s) - start )) -le "$1" ]; do
    ok=1
    for c in $ALL_CONTAINERS; do [ "$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null)" = healthy ] || ok=0; done
    [ "$ok" = 1 ] && { sleep 45; return 0; }
    sleep 5
  done
  return 1
}
sha256_file() { py - "$(pypath "$1")" <<'PYEOF'
import hashlib, sys
h = hashlib.sha256()
with open(sys.argv[1], "rb") as f:
    for b in iter(lambda: f.read(1 << 20), b""):
        h.update(b)
print(h.hexdigest())
PYEOF
}
# 서비스마다 로컬 jar와 컨테이너 안 jar의 sha256을 한 줄씩 출력한다 (official.sh record_versions와 같은 비교)
# 하나라도 다르거나, 컨테이너 조회가 실패하거나, 로컬 jar가 1개가 아니면 실패 (측정한 코드가 HEAD인지 알 수 없다)
check_jars() {
  local pair c mod jars ls cs bad=0
  for pair in $JAR_TARGETS; do
    c="${pair%%:*}"; mod="${pair#*:}"
    jars=( "$REPO/$mod/build/libs/"*.jar )
    if [ "${#jars[@]}" -ne 1 ] || [ ! -f "${jars[0]}" ]; then echo "jar $mod: 로컬 jar가 1개가 아님"; bad=1; continue; fi
    ls="$(sha256_file "${jars[0]}")" || ls="?"
    cs="$(docker exec "$c" sha256sum /app/app.jar 2>/dev/null | tr -d '\r' | awk '{print $1}')"
    if [ -n "$cs" ] && [ "$ls" = "$cs" ]; then echo "jar $mod sha256=$ls (컨테이너와 같음)"
    else echo "jar $mod 로컬=$ls 컨테이너=${cs:-조회 실패} (다름)"; bad=1; fi
  done
  return "$bad"
}
# 청약 서비스 컨테이너에 들어간 마감 유예 환경변수 값 (없으면 빈 값)
grace_env() { docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$SUB_CONTAINER" | tr -d '\r' | grep '^IPO_CLOSE_GRACEPERIOD=' | cut -d= -f2; }

# --grace: 유예만 바꿔 청약 서비스를 다시 띄운다. 끝나면 덮어쓰기 없이 다시 띄워 원래 값으로 되돌린다
restore_grace() {
  say "마감 유예 되돌림: 덮어쓰기 없이 청약 서비스를 다시 띄움"
  rm -f "$REPO/$GRACE_FILE"
  compose "${COMPOSE_FILES[@]}" up -d subscription-service >> "$LOG" 2>&1 || say "!! 청약 서비스 재기동 실패. 직접 확인 필요"
  wait_healthy || say "!! 되돌린 뒤 healthy 대기 시간 초과. 직접 확인 필요"
  [ -z "$(grace_env)" ] && say "마감 유예 되돌림 확인 (기본값)" || say "!! 마감 유예 환경변수가 남아 있음. 직접 확인 필요"
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --run-id) RUN_ID="$2"; shift 2 ;;
      --grace) GRACE="$2"; shift 2 ;;
      *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
    esac
  done
  [ -n "$RUN_ID" ] || { echo "사용: $0 --run-id <run_id> [--grace PT1S]" >&2; exit 1; }
  check_dirty || exit $?
  OUT="$DIR/results/$RUN_ID"; WRUN="$RUN_ID-W"
  [ -e "$OUT" ] && { echo "이미 있는 run_id입니다: $OUT" >&2; exit 1; }
  mkdir -p "$OUT" "$DIR/results/$WRUN"
  LOG="$OUT/진행.log"
  pick_python || die 1 "Python 3.8 이상이 필요합니다"
  pick_compose

  docker stop -t 30 "k6-$RUN_ID" "k6-$WRUN" >/dev/null 2>&1 || true
  # 이 커밋으로 빌드, 배포하고 컨테이너 jar가 같은지 확인한다 (official.sh와 같은 방식. 다시 빌드하지 않은 채 재면 env.txt의 git과 실제 코드가 달라진다)
  if [ "${SKIP_BUILD:-0}" != 1 ]; then
    say "jar 빌드 (./gradlew build -x test)"
    (cd "$REPO" && ./gradlew build -x test -q) >> "$LOG" 2>&1 || die 1 "gradle 빌드 실패 (진행.log)"
    say "컨테이너 배포 (compose up -d --build)"
    compose "${COMPOSE_FILES[@]}" up -d --build >> "$LOG" 2>&1 || die 1 "compose up 실패 (진행.log)"
  else
    say "빌드, 배포 건너뜀 (SKIP_BUILD=1). jar 비교는 한다"
  fi
  wait_all_healthy 600 || die 1 "컨테이너가 600초 안에 모두 healthy가 되지 않음"
  local jars_info
  jars_info="$(check_jars)" || { printf '%s\n' "$jars_info" | tee -a "$LOG" >&2; die 5 "컨테이너 안 jar가 로컬 jar와 다름. 이 커밋으로 다시 빌드, 배포한다"; }
  if [ -n "$GRACE" ]; then
    printf 'services:\n  subscription-service:\n    environment:\n      IPO_CLOSE_GRACEPERIOD: "%s"\n' "$GRACE" > "$REPO/$GRACE_FILE"
    trap 'docker stop -t 30 "k6-$RUN_ID" "k6-$WRUN" >/dev/null 2>&1; restore_grace' EXIT
    say "마감 유예 ${GRACE}로 청약 서비스를 다시 띄움"
    compose "${COMPOSE_FILES[@]}" -f "$GRACE_FILE" up -d subscription-service >> "$LOG" 2>&1 || die 1 "청약 서비스 재기동 실패"
    wait_healthy || die 1 "청약 서비스 healthy 대기 시간 초과"
    [ "$(grace_env)" = "$GRACE" ] || die 1 "컨테이너의 마감 유예가 ${GRACE}가 아님: '$(grace_env)'"
  else
    trap 'docker stop -t 30 "k6-$RUN_ID" "k6-$WRUN" >/dev/null 2>&1' EXIT
    [ -z "$(grace_env)" ] || die 1 "청약 서비스에 마감 유예 덮어쓰기가 남아 있음: $(grace_env). --grace 없이 재야 하면 덮어쓰기 없이 다시 띄운다"
  fi
  local grace_actual="${GRACE:-PT20S(기본)}"

  # 토큰 (1시간 만료): 워밍업(최대 WARM_MAX초)과 본 측정 전에 다시 받는다
  say "토큰 재발급"
  (cd "$REPO" && py loadtest/seed/seed_users.py --run-id "tok-$RUN_ID" --refresh-tokens) >> "$LOG" 2>&1 || die 1 "토큰 재발급 실패"
  local u
  u="$(($(grep -c . "$DIR/data/users.csv") - 1))"
  [ "$u" -ge 1 ] || die 1 "회원 CSV가 비어 있음"

  # 워밍업: official.sh와 같은 방식 (WARM_RATE 일정 부하, warmup.py가 응답 시간 추세가 평평해졌다고 판정하면 멈춤)
  local wipos t0 kpid wrc d
  if [ "$WARM" = 1 ]; then
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
    say "워밍업 끝: $(( $(date +%s) - t0 ))초, 소진 ${d}초. 30초 대기"
    sleep 30
  fi

  # 대상 1개 + 배경
  local win_ms total bg_n t bg now_ms start_at end_ms krc st jrc
  read -r win_ms total bg_n <<< "$(plan "$u")"
  [[ "$win_ms $total $bg_n" =~ ^[0-9]+\ [0-9]+\ [0-9]+$ ]] || die 1 "요청 계획 계산 실패: '$win_ms $total $bg_n'"
  t="$(new_ipos "$RUN_ID-T" 1)" || die 1 "대상 공모주 생성 실패"
  bg="$(new_ipos "$RUN_ID-BG" "$bg_n")" || die 1 "배경 공모주 생성 실패"

  # 시작 시각과 마감 시각은 컨테이너 시계(PostgreSQL, 게이트웨이, k6가 같은 Docker 시계)로 정한다
  now_ms="$(docker exec "$PG_CONTAINER" date +%s%3N)" || die 1 "컨테이너 시계 조회 실패"
  start_at=$(( now_ms + LEAD * 1000 ))
  end_ms=$(( start_at + PRE * 1000 + win_ms / 2 ))
  pg "update subscription.ipo set subscription_end_at = to_timestamp($end_ms / 1000.0) where ipo_id = '$t'" > /dev/null || die 1 "대상 공모주 마감 시각 설정 실패"
  {
    echo "run_id=$RUN_ID git=$(git_repo rev-parse --short HEAD) branch=$(git_repo rev-parse --abbrev-ref HEAD) dirty=$DIRTY_N allow_dirty=${ALLOW_DIRTY:-0}"
    echo "grace=$grace_actual rate=$RATE pre=$PRE post=$POST target_sec=$TARGET_SEC target_window_ms=$win_ms total=$total users=$u bg_ipos=$bg_n price=$PRICE quantity=$QUANTITY"
    echo "target_ipo=$t start_at_ms=$start_at end_at_ms=$end_ms"
    echo "k6_image=$K6_IMAGE k6_cpus=$K6_CPUS k6_memory=$K6_MEMORY pre_vus=$PRE_VUS max_vus=$MAX_VUS skip_build=${SKIP_BUILD:-0}"
    printf '%s\n' "$jars_info"
  } > "$OUT/env.txt"
  say "대상 $t 마감 시각 = 시작 + $((PRE * 1000 + win_ms / 2))ms. 부하 ${RATE}건/초 x 약 $(( total / RATE ))초 (배경 공모주 ${bg_n}개)"

  (cd "$DIR" && K6_MODE=container K6_IMAGE="$K6_IMAGE" K6_CPUS="$K6_CPUS" K6_MEMORY="$K6_MEMORY" K6_NAME="k6-$RUN_ID" \
    bash run-k6.sh s2 --ipo "$t" --run-id "$RUN_ID" -e "IPO_IDS=$bg" -e "START_AT=$start_at" -e "RATE=$RATE" -e "PRE=$PRE" \
    -e "POST=$POST" -e "TARGET_SEC=$TARGET_SEC" -e "QUANTITY=$QUANTITY" -e "PRE_VUS=$PRE_VUS" -e "MAX_VUS=$MAX_VUS") > "$OUT/k6-console.out" 2>&1; krc=$?
  say "k6 종료 코드 $krc (0 통과, 99 임계값 위반)"
  case "$krc" in 0|99) ;; *) die 1 "k6 실행 실패 (k6-console.out)" ;; esac

  # 대상 공모주 마감(CLOSED 또는 ALLOCATED)과 전체 소진을 기다린다
  st="$(wait_closed "$t" "$CLOSE_TIMEOUT")" || die 1 "대상 공모주가 ${CLOSE_TIMEOUT}초 안에 마감되지 않음 (마지막 조회: '$st')"
  d="$(drain "$t,$bg" "$DRAIN_TIMEOUT")" || die 1 "소진 대기 시간 초과"
  say "마감 확인 ($st), 소진 ${d}초"

  # 정합성 SQL (마감된 공모주 검사 20~22번 포함). 판정이 결과를 읽으므로 실패하면 멈춘다
  (cd "$DIR" && bash verify.sh --ipo "$t" --run-id "$RUN_ID") >> "$LOG" 2>&1 || die 1 "verify.sh 실패 (sql_result.txt 확인)"

  # 판정 (verify/s2_judge.py): k6 로그가 필요한 것(회차 유효성, 202인데 행 없음)은 직접 계산하고, DB만 보는 검사는 verify.sql 20~22번 결과를 읽는다
  py "$(pypath "$DIR/verify/s2_judge.py")" --requests "$(pypath "$OUT/requests.log")" --summary "$(pypath "$OUT/summary.json")" \
    --sql-result "$(pypath "$OUT/sql_result.txt")" --ipo "$t" --end-ms "$end_ms" --expect-requests "$total" --container "$PG_CONTAINER" > "$OUT/s2-judge.txt"
  jrc=$?
  tee -a "$LOG" < "$OUT/s2-judge.txt"
  [ "$jrc" -eq 0 ] || { [ "$jrc" -eq 3 ] && exit 3; die 1 "판정 스크립트 실패 (종료 코드 $jrc)"; }
  say "끝: $OUT"
}

# source로 불러오면(테스트) main을 실행하지 않는다
if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
