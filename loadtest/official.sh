#!/usr/bin/env bash
# 정식 측정: S0, S1 각각에서 G1(처리량, 응답, 오류)과 G2(접수→확정 p95, 소진 시간)를 판정할 수 있게 실행하고,
# 부하가 모두 끝난 뒤 후처리(보고서, 요청 분류, 정합성 SQL)와 판정(verify/judge.py)까지 한다.
#
# 사용: bash loadtest/official.sh --batch <이름>
#   결과: loadtest/results/<이름>/ (진행.log, env.txt, manifest.csv, s1-plan.txt, 판정.md) + 실행마다 loadtest/results/<이름>-.../
#
# 측정에 끼는 요인을 줄이는 방법
#   - 모든 부하 실행(S0 회차, S1 속도 단계) 뒤에 이번 공모주의 PENDING/COMPENSATING 0, 필수 Consumer 그룹 lag 0, outbox 미발행 0을
#     확인하고 GAP초 쉰 뒤 다음 실행을 한다 (앞 실행의 처리 대기가 다음 실행의 G2에 섞이지 않게)
#   - S1은 속도 단계마다 독립 k6 실행이고, 단계마다 공모주를 돌려 모든 요청이 확정 경로를 탄다 (이중청약 거절이 섞이지 않음)
#   - 시작할 때 이 커밋으로 jar를 빌드해 배포하고, 컨테이너 안 jar와 빌드한 jar의 sha256이 같은지 확인한다
#   - 그 뒤 gateway, subscription, account를 재시작해 콜드 상태에서 시작하고, 워밍업 회차도 기록한다
# 불가피한 것(결과를 쓸 때 한계로 적는다)
#   - k6와 서비스가 같은 PC, Prometheus 5초 수집
#   - 토큰 재발급(로그인 요청): 시작 전 1번, S1 직전 1번, 만료가 가까우면 회차 사이(요청 없는 구간)에 추가. 재발급 뒤 GAP초 쉰다. 시점은 진행.log에 남는다
#   - S1은 공모가 S1_PRICE원 종목 (예수금 때문에. 금액만 다르고 처리 경로는 같다고 보지만 확인하지 않았다)
#
# 환경변수 (모두 env.txt에 기록된다)
#   WCAP=20         S0 워밍업 상한 회차 (넘으면 중단)
#   STABLE_PCT=20   S0 안정 판정: 회차 평균 응답이 연속 3회차 각각 그 3회차 평균의 +-STABLE_PCT% 이내
#   GAP=30          실행 사이 대기(초). 앞 실행의 처리 대기는 소진 확인으로 없애고, 이 대기는 보고서의 서버 지표 구간
#                   (요청 구간 앞뒤 10초 + Prometheus 5초 수집)이 앞뒤 실행과 섞이지 않게 하는 용도다. GC, DB 체크포인트는 대기로 피할 수 없다
#                   개선 전후를 비교할 때는 같은 값을 쓴다
#   STAGES=100,200,300,433  S1 속도 단계(건/초)       DUR=90  S1 단계 유지(초)
#   S0_PRICE=15000  S1_PRICE=1000  QUANTITY=10     공모가(원), 청약 수량(주)
#   PRE_VUS=500 MAX_VUS=2000   S1 k6 가상 사용자
#   P95_LIMIT=15 DRAIN_LIMIT=15  G2 합격 기준(초)
#   DRAIN_TIMEOUT=2400  소진 대기 상한(초)
#   REQUIRED_GROUPS="subscription-requested subscription-margin-reserved subscription-margin-reserve-failed account-margin-reserve"
#   SKIP_BUILD=1    빌드, 배포를 건너뛴다 (jar sha256 비교는 그대로 한다)
#   ALLOW_DIRTY=1   커밋하지 않은 변경이 있어도 실행 (env.txt에 dirty로 남는다. 정식 결과에 쓰지 않는다)
#
# 종료 코드: 0 정상 / 2 안정 판정 미도달 / 3 토큰 / 4 예수금 / 5 버전(dirty, jar 불일치) / 6 소진 대기 / 7 기동 / 8 k6 / 9 사전 점검
#           / 10 부하는 끝났고 후처리 일부 실패 / 11 공모주 생성 / 12 잠금, 결과 폴더 / 13 도구 / 1 그 밖
set -uo pipefail
export MSYS_NO_PATHCONV=1 PYTHONUTF8=1

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$DIR/.." && pwd)"
COMPOSE_FILES=(-f docker-compose.yml -f compose.perf.yml)
PG_CONTAINER="${PG_CONTAINER:-ipo-postgres}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-ipo-kafka}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:29092}"
EUREKA_URL="${EUREKA_URL:-http://localhost:8761}"
# 하위 스크립트(precheck.sh, verify.sh, new_ipo.sh)도 같은 컨테이너, 주소를 쓰게 한다
export PG_CONTAINER KAFKA_CONTAINER EUREKA_URL
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.3.0}"
USERS_CSV="$DIR/data/users.csv"
RESTART_SERVICES="api-gateway subscription-service account-service"
COLD_CONTAINERS="ipo-api-gateway ipo-subscription-service ipo-account-service"
# 컨테이너:모듈 (jar sha256 비교 대상)
JAR_TARGETS="ipo-api-gateway:api-gateway ipo-account-service:account-service ipo-subscription-service:subscription-service ipo-allocation-service:allocation-service ipo-eureka-server:eureka-server"
ALL_CONTAINERS="ipo-postgres ipo-kafka ipo-eureka-server ipo-api-gateway ipo-account-service ipo-subscription-service ipo-allocation-service"

WCAP="${WCAP:-20}"; STABLE_PCT="${STABLE_PCT:-20}"; GAP="${GAP:-30}"
STAGES="${STAGES:-100,200,300,433}"; DUR="${DUR:-90}"
S0_PRICE="${S0_PRICE:-15000}"; S1_PRICE="${S1_PRICE:-1000}"; QUANTITY="${QUANTITY:-10}"
PRE_VUS="${PRE_VUS:-500}"; MAX_VUS="${MAX_VUS:-2000}"
S0_VUS=100; S0_ITERS=10    # S0 = 100명 x 10회 = 1,000건 (발제 조건, 바꾸지 않는다). 판정은 요청 수가 이 값과 같아야 유효로 본다
P95_LIMIT="${P95_LIMIT:-15}"; DRAIN_LIMIT="${DRAIN_LIMIT:-15}"; DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-2400}"
REQUIRED_GROUPS="${REQUIRED_GROUPS:-subscription-requested subscription-margin-reserved subscription-margin-reserve-failed account-margin-reserve}"
TOKEN_MAX_S=$((55 * 60))   # 토큰 만료 1시간보다 5분 일찍 끊는다

BATCH=""; OUT=""; LOG=""; MANIFEST=""; PLAN=""; LOCK="$DIR/results/.official.lock"; LOCK_OWNED=""
PY=""; COMPOSE=(); K6_NET=""; TOKEN_AT=0; TOKEN_N=0; S0_IPOS=(); S0_NEXT=0; LAST_AVG=""; USERS=0

# ---------- 공통 ----------
say() { local line; line="$(date '+%F %T') $*"; printf '%s\n' "$line" >&2; [ -n "$LOG" ] && printf '%s\n' "$line" >> "$LOG"; return 0; }
die() { local code="$1"; shift; say "!! $*"; exit "$code"; }
is_uint() { case "${1:-}" in ''|*[!0-9]*) return 1 ;; esac; return 0; }
is_uuid() { case "${1:-}" in ''|*[!0-9a-fA-F-]*) return 1 ;; esac; [ "${#1}" -eq 36 ]; }
ceil_div() { echo $(( ($1 + $2 - 1) / $2 )); }

# Windows에서는 python3가 Microsoft Store 연결용 가짜 실행 파일일 수 있어서, 이름이 아니라 실제 실행 결과로 고른다
pick_python() {
  local c
  for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
      PY="$c"; return 0
    fi
  done
  return 1
}
# Windows용 Python에는 C:/... 경로를 넘긴다 (Git Bash에서만 cygpath가 있다)
pypath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
py() { "$PY" -X utf8 "$@"; }
pick_compose() {
  if docker compose version >/dev/null 2>&1; then COMPOSE=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then COMPOSE=(docker-compose)
  else return 1; fi
}
compose() { (cd "$REPO" && "${COMPOSE[@]}" "${COMPOSE_FILES[@]}" "$@"); }
# git은 저장소 폴더로 이동해서 실행한다. Windows용 git은 MSYS_NO_PATHCONV=1에서 /c/... 경로를 -C 인자로 읽지 못한다
git_repo() { (cd "$REPO" && git "$@"); }
# 커밋하지 않은 변경 수. git이 실패하면 0이 아니라 실패를 돌려준다
dirty_count() {
  local st
  st="$(git_repo status --porcelain)" || return 1
  printf '%s\n' "$st" | grep -c . || true
}

file_mtime() { py - "$(pypath "$1")" <<'PYEOF'
import os, sys
print(int(os.path.getmtime(sys.argv[1])))
PYEOF
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
# summary.json의 지표 하나 (없으면 실패)
json_metric() { py - "$(pypath "$1")" "$2" "$3" <<'PYEOF'
import json, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))["metrics"]
print(m[sys.argv[2]][sys.argv[3]])
PYEOF
}

# ---------- DB, Kafka 조회 (실패하면 0을 돌려주지 않고 실패를 돌려준다) ----------
pg() { docker exec -i "$PG_CONTAINER" psql -U postgres -d ipo -v ON_ERROR_STOP=1 -qtA -F ' ' -c "$1"; }
pg_int() {
  local out
  out="$(pg "$1")" || return 1
  out="$(printf '%s' "$out" | tr -d '\r\n ')"
  is_uint "$out" || return 1
  printf '%s' "$out"
}
sql_in() { # 쉼표로 구분한 공모주 ID → 'a','b' (UUID가 아니면 실패)
  local s="" i
  for i in ${1//,/ }; do is_uuid "$i" || return 1; s="$s'$i',"; done
  [ -n "$s" ] || return 1
  printf '%s' "${s%,}"
}
kafka_topics() { # 토픽 목록은 docker/kafka/create-topics.sh 한 곳에서 관리한다
  local t
  t="$(awk '/^TOPICS=\(/{f=1; next} f && /^\)/{exit} f {gsub(/[[:space:]]/, ""); if ($0 != "") print}' "$REPO/docker/kafka/create-topics.sh")" || return 1
  [ -n "$t" ] || return 1
  printf '%s\n' "$t"
}
# 표준입력: kafka-consumer-groups.sh --describe --all-groups 출력 / 인자: 필수 그룹 목록
# lag = LOG-END-OFFSET - CURRENT-OFFSET (커밋 오프셋이 '-'이면 0으로 보고 끝 오프셋 전체를 대기로 센다).
# 형식이 이상한 행, 데이터 행 0개, 필수 그룹 누락이면 실패
parse_lag() {
  awk -v req="$1" '
    BEGIN { n = split(req, R, " "); bad = 0; s = 0; rows = 0 }
    $1 == "GROUP" && $2 == "TOPIC" { next }
    NF >= 5 && $3 ~ /^[0-9]+$/ {
      leo = $5; cur = $4
      if (leo !~ /^[0-9]+$/) { bad = 1; next }
      if (cur == "-") cur = 0
      else if (cur !~ /^[0-9]+$/) { bad = 1; next }
      seen[$1] = 1; s += leo - cur; rows++
    }
    END {
      for (i = 1; i <= n; i++) if (!(R[i] in seen)) { print "필수 Consumer 그룹 없음: " R[i] > "/dev/stderr"; bad = 1 }
      if (rows == 0 || bad) exit 1
      print s
    }'
}
kafka_lag() {
  local out
  out="$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --describe --all-groups 2>&1)" || return 1
  printf '%s\n' "$out" | tr -d '\r' | parse_lag "$REQUIRED_GROUPS"
}
# 표준입력: kafka-get-offsets.sh 출력(토픽:파티션:끝오프셋) / 인자: 토픽 이름. 해당 토픽 행이 없으면 실패
parse_offsets() {
  awk -F: -v t="$1" '$1 == t && $3 ~ /^[0-9]+$/ { s += $3; c++ } END { if (c == 0) exit 1; print s }'
}
kafka_dlt_total() {
  local topics t out n total=0
  topics="$(kafka_topics)" || return 1
  for t in $topics; do
    out="$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$t.DLT" 2>&1)" || return 1
    n="$(printf '%s\n' "$out" | tr -d '\r' | parse_offsets "$t.DLT")" || return 1
    total=$((total + n))
  done
  echo "$total"
}
outbox_unpublished() {
  pg_int "select (select count(*) from subscription.outbox where published_at is null) + (select count(*) from account.outbox where published_at is null) + (select count(*) from allocation.outbox where published_at is null)"
}

# 소진 대기: 이번 공모주 PENDING/COMPENSATING 0 + 필수 그룹 lag 0 + outbox 미발행 0. 성공하면 걸린 초를 출력
# 반환: 0 성공 / 1 시간 초과 / 2 조회가 연속 5번 실패 / 3 인자 오류
drain() {
  local in start now p l o fails=0
  in="$(sql_in "$1")" || return 3
  start="$(date +%s)"
  while :; do
    now="$(date +%s)"
    [ $((now - start)) -gt "$2" ] && return 1
    if p="$(pg_int "select count(*) from subscription.subscription where status in ('PENDING','COMPENSATING') and ipo_id in ($in)")" \
       && l="$(kafka_lag)" && o="$(outbox_unpublished)"; then
      fails=0
      if [ "$p" = 0 ] && [ "$l" = 0 ] && [ "$o" = 0 ]; then echo $(( $(date +%s) - start )); return 0; fi
    else
      fails=$((fails + 1))
      [ "$fails" -ge 5 ] && return 2
    fi
    sleep 2
  done
}

eureka_up_count() {
  local out
  out="$(curl -sf -H 'Accept: application/json' "$EUREKA_URL/eureka/apps")" || { echo 0; return 0; }
  printf '%s' "$out" | grep -o '"status":"UP"' | wc -l | tr -d ' '
}
wait_healthy() { # 인자: 제한 초, 컨테이너 목록. Eureka UP 4개 이상까지 확인
  local limit="$1" start c ok; shift
  start="$(date +%s)"
  while [ $(( $(date +%s) - start )) -le "$limit" ]; do
    ok=1
    for c in "$@"; do [ "$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null)" = healthy ] || ok=0; done
    if [ "$ok" = 1 ] && [ "$(eureka_up_count)" -ge 4 ]; then return 0; fi
    sleep 5
  done
  return 1
}

# 토큰 재발급. 성공하면 TOKEN_AT을 재발급 "시작" 시각으로 둔다 (만료는 발급 시각 기준이라 늦게 잡으면 위험)
refresh_tokens() {
  local t0 out rc
  TOKEN_N=$((TOKEN_N + 1)); t0="$(date +%s)"
  say "토큰 재발급 #$TOKEN_N 시작 (로그인 요청 발생)"
  out="$(cd "$REPO" && py loadtest/seed/seed_users.py --run-id "tok-$BATCH-$TOKEN_N" --refresh-tokens 2>&1)"; rc=$?
  printf '%s\n' "$out" | tail -3 >> "$LOG"
  [ "$rc" -eq 0 ] || { say "토큰 재발급 실패 rc=$rc"; return 1; }
  USERS="$(py - "$(pypath "$USERS_CSV")" "$t0" <<'PYEOF'
import csv, os, sys
path, t0 = sys.argv[1], int(sys.argv[2])
if os.path.getmtime(path) < t0 - 1:
    sys.exit("CSV가 갱신되지 않았다")
rows = list(csv.DictReader(open(path, encoding="utf-8")))
if not rows or "token" not in rows[0]:
    sys.exit("CSV에 token 열이 없다")
if any(not r["token"] for r in rows):
    sys.exit("빈 토큰이 있다")
print(len(rows))
PYEOF
)" || { say "토큰 CSV 확인 실패"; return 1; }
  is_uint "$USERS" && [ "$USERS" -ge 1000 ] || { say "회원 수가 1,000명 미만: $USERS"; return 1; }
  TOKEN_AT="$t0"
  say "토큰 재발급 #$TOKEN_N 완료 ($(( $(date +%s) - t0 ))초, 회원 $USERS명)"
}
# 다음 실행(need초) 동안 토큰이 유효한지 보고, 모자라면 요청 없는 지금 재발급하고 GAP초 쉰다
ensure_token() {
  if [ $(( $(date +%s) - TOKEN_AT + $1 )) -ge "$TOKEN_MAX_S" ]; then
    refresh_tokens || die 3 "토큰 재발급 실패"
    say "재발급 뒤 ${GAP}초 대기 (요청 없음)"; sleep "$GAP"
  fi
}

new_ipo() { # 인자: 이름, 공모가. 공모주 ID 출력
  local id
  id="$(cd "$DIR" && bash seed/new_ipo.sh --name "$1" --price "$2" 2>>"$LOG" | tr -d '\r\n ')" || return 1
  is_uuid "$id" || return 1
  printf '%s' "$id"
}

# k6 실행. 종료 코드 0(통과), 99(임계값 위반)만 정상으로 보고, summary.json에 요청이 있어야 한다
k6_run() { # 인자: 시나리오, 첫 공모주, run_id, -e 인자들...
  local scen="$1" ipo="$2" run="$3" rc n; shift 3
  mkdir -p "$DIR/results/$run"
  (cd "$DIR" && K6_MODE=container K6_IMAGE="$K6_IMAGE" K6_CPUS=2 K6_MEMORY=1g K6_LABEL="$BATCH" K6_NETWORK="$K6_NET" \
     bash run-k6.sh "$scen" --ipo "$ipo" --run-id "$run" "$@") > "$DIR/results/$run/k6-console.out" 2>&1
  rc=$?
  case "$rc" in 0|99) ;; *) say "$run k6 종료 코드 $rc (k6-console.out 확인)"; return "$rc" ;; esac
  [ -f "$DIR/results/$run/summary.json" ] || { say "$run summary.json 없음"; return 1; }
  n="$(json_metric "$DIR/results/$run/summary.json" http_reqs count)" || { say "$run summary.json에 http_reqs 없음"; return 1; }
  [ "${n%.*}" -gt 0 ] 2>/dev/null || { say "$run 요청 0건"; return 1; }
  return 0
}

stable_check() { # 인자: 기준 %, 평균 응답 목록. 마지막 3개가 각각 그 평균의 +-기준% 이내면 0
  py - "$@" <<'PYEOF'
import sys
pct = float(sys.argv[1]); a = [float(x) for x in sys.argv[2:]]
w = a[-3:]
m = sum(w) / 3 if len(w) == 3 else 0
sys.exit(0 if len(a) >= 3 and m > 0 and all(abs(x - m) / m <= pct / 100 for x in w) else 1)
PYEOF
}

cleanup() {
  local ids
  ids="$(docker ps -q --filter "label=loadtest.batch=${BATCH:-none}" 2>/dev/null)"
  # shellcheck disable=SC2086  # 컨테이너 ID 목록을 나눠서 넘긴다
  [ -n "$ids" ] && docker stop $ids >/dev/null 2>&1 && say "남은 k6 컨테이너를 멈춤"
  [ -n "$LOCK_OWNED" ] && rmdir "$LOCK" 2>/dev/null
  return 0
}

record_versions() { # env.txt에 버전을 쓰고, jar 불일치 수를 돌려준다
  local mismatch=0 pair c mod jars local_sha cont_sha head branch ctime dirty
  if ! { head="$(git_repo rev-parse HEAD)" && branch="$(git_repo rev-parse --abbrev-ref HEAD)" \
         && ctime="$(git_repo show -s --format=%cI HEAD)" && dirty="$(dirty_count)"; }; then
    echo "git 정보 조회 실패" >> "$OUT/env.txt"; return 1
  fi
  {
    echo "git=$head branch=$branch commit_time=$ctime"
    echo "dirty_files=$dirty"
    git_repo status --porcelain | sed 's/^/dirty: /'
    echo "os=$(uname -srm) bash=${BASH_VERSION} python=$(py -c 'import platform; print(platform.python_version())') docker=$(docker version --format '{{.Server.Version}}' 2>/dev/null) compose=$("${COMPOSE[@]}" version --short 2>/dev/null) k6_image=$K6_IMAGE"
  } >> "$OUT/env.txt"
  for pair in $JAR_TARGETS; do
    c="${pair%%:*}"; mod="${pair#*:}"
    # shellcheck disable=SC2206  # glob으로 jar 파일 목록을 만든다
    jars=( "$REPO/$mod/build/libs/"*.jar )
    if [ "${#jars[@]}" -ne 1 ] || [ ! -f "${jars[0]}" ]; then echo "jar $mod: 로컬 jar가 1개가 아님" >> "$OUT/env.txt"; mismatch=$((mismatch + 1)); continue; fi
    local_sha="$(sha256_file "${jars[0]}")" || local_sha="?"
    cont_sha="$(docker exec "$c" sha256sum /app/app.jar 2>/dev/null | awk '{print $1}')"
    if [ -n "$cont_sha" ] && [ "$local_sha" = "$cont_sha" ]; then echo "jar $mod sha256=$local_sha (컨테이너와 같음)" >> "$OUT/env.txt"
    else echo "jar $mod 로컬=$local_sha 컨테이너=${cont_sha:-조회 실패} (다름)" >> "$OUT/env.txt"; mismatch=$((mismatch + 1)); fi
  done
  {
    echo "설정 WCAP=$WCAP STABLE_PCT=$STABLE_PCT GAP=$GAP STAGES=$STAGES DUR=$DUR S0_VUS=$S0_VUS S0_ITERS=$S0_ITERS S0_PRICE=$S0_PRICE S1_PRICE=$S1_PRICE QUANTITY=$QUANTITY PRE_VUS=$PRE_VUS MAX_VUS=$MAX_VUS"
    echo "설정 PG_CONTAINER=$PG_CONTAINER KAFKA_CONTAINER=$KAFKA_CONTAINER EUREKA_URL=$EUREKA_URL"
    echo "설정 P95_LIMIT=$P95_LIMIT DRAIN_LIMIT=$DRAIN_LIMIT DRAIN_TIMEOUT=$DRAIN_TIMEOUT SKIP_BUILD=${SKIP_BUILD:-0} ALLOW_DIRTY=${ALLOW_DIRTY:-0}"
    echo "설정 REQUIRED_GROUPS=$REQUIRED_GROUPS"
  } >> "$OUT/env.txt"
  return "$mismatch"
}

# ---------- 회차 ----------
run_s0() { # 인자: run_id, phase(warmup|measure)
  local run="$1" phase="$2" ipo t0 t1 d drc rate
  ensure_token 300
  [ "$S0_NEXT" -lt "${#S0_IPOS[@]}" ] || die 11 "S0 공모주가 모자람"
  ipo="${S0_IPOS[$S0_NEXT]}"; S0_NEXT=$((S0_NEXT + 1))
  t0="$(date +%s)"
  k6_run s0 "$ipo" "$run" -e "VUS=$S0_VUS" -e "ITERATIONS=$S0_ITERS" -e "QUANTITY=$QUANTITY" || die 8 "$run k6 실패"
  t1="$(date +%s)"
  d="$(drain "$ipo" "$DRAIN_TIMEOUT")"; drc=$?
  [ "$drc" -eq 0 ] || die 6 "$run 소진 대기 실패 (1 시간 초과, 2 조회 실패, 3 인자): $drc"
  echo "$run,S0,$phase,,,$((S0_VUS * S0_ITERS)),$ipo,$t0,$t1,$d" >> "$MANIFEST"
  LAST_AVG="$(json_metric "$DIR/results/$run/summary.json" http_req_duration avg)" || die 1 "$run 평균 응답을 읽지 못함"
  rate="$(json_metric "$DIR/results/$run/summary.json" http_reqs rate)" || rate="?"
  say "$run ($phase) 처리량=${rate}건/초 avg=${LAST_AVG}ms 소진 대기=${d}초. ${GAP}초 대기 (요청 없음)"
  sleep "$GAP"
}

run_s1() { # 인자: 속도, 마지막 단계면 1
  local rate="$1" last="$2" run ipos first t0 t1 d drc
  run="$BATCH-S1-$rate"
  ipos="$(awk -v r="$rate" '$1 == r { print $2 }' "$PLAN")"
  [ -n "$ipos" ] || die 11 "S1 ${rate}건/초 공모주 목록 없음"
  first="${ipos%%,*}"
  ensure_token $((DUR + 300))
  say "$run 시작: ${rate}건/초 x ${DUR}초, 공모주 $(printf '%s\n' "$ipos" | awk -F, '{print NF}')개"
  t0="$(date +%s)"
  k6_run s1 "$first" "$run" -e "IPO_IDS=$ipos" -e "RATE=$rate" -e "DURATION=${DUR}s" -e "PRE_VUS=$PRE_VUS" -e "MAX_VUS=$MAX_VUS" -e "QUANTITY=$QUANTITY" \
    || die 8 "$run k6 실패"
  t1="$(date +%s)"
  d="$(drain "$ipos" "$DRAIN_TIMEOUT")"; drc=$?
  [ "$drc" -eq 0 ] || die 6 "$run 소진 대기 실패 (1 시간 초과, 2 조회 실패, 3 인자): $drc"
  echo "$run,S1,measure,$rate,$DUR,$((rate * DUR)),$(printf '%s' "$ipos" | tr ',' ';'),$t0,$t1,$d" >> "$MANIFEST"
  say "$run 처리량=$(json_metric "$DIR/results/$run/summary.json" http_reqs rate 2>/dev/null)건/초 avg=$(json_metric "$DIR/results/$run/summary.json" http_req_duration avg 2>/dev/null)ms 소진 대기=${d}초"
  if [ "$last" != 1 ]; then say "${GAP}초 대기 (요청 없음)"; sleep "$GAP"; fi
}

# manifest.csv를 열 위치가 아니라 열 이름으로 읽어 "run_id 시나리오 공모주(;구분) 예상요청수"를 한 줄씩 낸다.
# 빈 값은 '-'로 낸다 (빈 칸이면 read가 다음 필드를 당겨 읽는다). run_id, scenario, ipo_ids 열이 없으면 실패
manifest_runs() {
  awk -F, '
    function v(name) { return (name in c && $c[name] != "") ? $c[name] : "-" }
    NR == 1 { for (i = 1; i <= NF; i++) c[$i] = i
              if (!("run_id" in c) || !("scenario" in c) || !("ipo_ids" in c)) exit 1
              next }
    NF > 0  { print v("run_id") " " v("scenario") " " v("ipo_ids") " " v("expected_requests") }' "$1"
}

# 부하가 모두 끝난 뒤: 보고서(report.py), 요청 분류(check_requests.py), 정합성 SQL(verify.sh), 판정(judge.py)
post_process() {
  local fails=0 run scen ipos expected i k expect runs
  runs="$(manifest_runs "$MANIFEST")" || { say "후처리: manifest.csv 형식 오류"; return 1; }
  while read -r run scen ipos expected; do
    [ -n "$run" ] && [ "$run" != - ] || continue
    [ "$ipos" = - ] && ipos=""
    (cd "$DIR" && py report.py --run-id "$run") > "$DIR/results/$run/report.out" 2>&1 || { say "후처리: $run report.py 실패"; fails=$((fails + 1)); }
    # S0은 요청 수가 정확히 맞아야 한다. S1은 k6가 목표 속도를 다 못 보낼 수 있어 판정(전달률, 밀린 반복)에서 본다
    expect=""; [ "$scen" = S0 ] && is_uint "$expected" && expect="--expect-requests $expected"
    # shellcheck disable=SC2086  # expect는 비어 있거나 옵션 2개
    py "$(pypath "$DIR/verify/check_requests.py")" "$(pypath "$DIR/results/$run/requests.log")" $expect > "$DIR/results/$run/check_requests.txt" 2>&1 \
      || { say "후처리: $run 요청 분류 이상 (check_requests.txt)"; fails=$((fails + 1)); }
    k=0
    for i in ${ipos//;/ }; do
      k=$((k + 1))
      (cd "$DIR" && bash verify.sh --ipo "$i" --run-id "$run/verify-$k") > /dev/null 2>&1 || { say "후처리: $run 정합성 SQL 실패 (공모주 $k)"; fails=$((fails + 1)); }
    done
    [ "$k" -gt 0 ] || { say "후처리: $run 공모주 ID 없음"; fails=$((fails + 1)); }
  done <<< "$runs"
  py "$(pypath "$DIR/verify/judge.py")" "$(pypath "$MANIFEST")" --p95-limit "$P95_LIMIT" --drain-limit "$DRAIN_LIMIT" --container "$PG_CONTAINER" \
    > "$OUT/판정.md" 2> "$OUT/판정.err" \
    || { say "후처리: judge.py 실패 (판정.err)"; fails=$((fails + 1)); }
  return "$fails"
}

main() {
  local dirty need minbal accounts s1_cost r n k l ids dlt0 dlt1 avgs stable_at i
  while [ $# -gt 0 ]; do
    case "$1" in --batch) BATCH="${2:-}"; shift 2 ;; *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;; esac
  done
  case "$BATCH" in ''|*[!A-Za-z0-9._-]*) echo "사용: $0 --batch <이름> (영문, 숫자, . _ - 만)" >&2; exit 1 ;; esac
  for v in WCAP STABLE_PCT GAP DUR S0_PRICE S1_PRICE QUANTITY PRE_VUS MAX_VUS DRAIN_TIMEOUT; do
    is_uint "${!v}" || { echo "$v가 0 이상의 정수가 아님: ${!v}" >&2; exit 1; }
  done
  IFS=',' read -ra ST <<< "$STAGES"
  for r in "${ST[@]}"; do is_uint "$r" && [ "$r" -gt 0 ] || { echo "STAGES 값이 올바르지 않음: $STAGES" >&2; exit 1; }; done

  # 잠금과 결과 폴더 (이전 결과를 덮어쓰지 않는다)
  mkdir -p "$DIR/results"
  OUT="$DIR/results/$BATCH"
  [ -e "$OUT" ] && { echo "이미 있는 결과 폴더: $OUT (다른 --batch 이름을 쓴다)" >&2; exit 12; }
  mkdir "$LOCK" 2>/dev/null || { echo "다른 official.sh가 실행 중이다 (멈춘 실행이 남긴 것이면 $LOCK 폴더를 지운다)" >&2; exit 12; }
  LOCK_OWNED=1
  trap cleanup EXIT
  trap 'exit 130' INT TERM
  mkdir -p "$OUT"; LOG="$OUT/진행.log"; MANIFEST="$OUT/manifest.csv"; PLAN="$OUT/s1-plan.txt"; : > "$OUT/env.txt"

  pick_python || die 13 "Python 3.8 이상을 찾지 못함 (python3, python)"
  pick_compose || die 13 "docker compose를 찾지 못함"
  for c in docker curl git awk; do command -v "$c" >/dev/null 2>&1 || die 13 "$c 명령이 없음"; done
  say "== 정식 측정 시작 batch=$BATCH (python=$PY, compose=${COMPOSE[*]})"

  # 0. 버전: 커밋하지 않은 변경 확인 → 빌드, 배포 → jar sha256 비교
  dirty="$(dirty_count)" || die 5 "git 상태 확인 실패 (저장소: $REPO)"
  if [ "$dirty" != 0 ] && [ "${ALLOW_DIRTY:-0}" != 1 ]; then
    git_repo status --porcelain >&2
    die 5 "커밋하지 않은 변경 ${dirty}건. 커밋한 뒤 실행한다 (정식 결과의 버전 추적). 시험 실행만 ALLOW_DIRTY=1"
  fi
  if [ "${SKIP_BUILD:-0}" != 1 ]; then
    say "jar 빌드 (./gradlew build -x test)"
    (cd "$REPO" && ./gradlew build -x test -q) >> "$LOG" 2>&1 || die 5 "gradle 빌드 실패 (진행.log)"
    say "컨테이너 배포 (compose up -d --build)"
    compose up -d --build >> "$LOG" 2>&1 || die 7 "compose up 실패 (진행.log)"
  fi
  # shellcheck disable=SC2086  # 컨테이너 목록을 나눠서 넘긴다
  wait_healthy 600 $ALL_CONTAINERS || die 7 "컨테이너가 healthy가 아니거나 Eureka UP이 4개 미만"
  record_versions || die 5 "컨테이너 안 jar가 빌드한 jar와 다름 (env.txt). 이 커밋으로 다시 빌드, 배포한다"
  # k6 컨테이너는 게이트웨이와 같은 네트워크에 붙인다 (compose 프로젝트 이름이 폴더 이름을 따라 바뀌므로 고정하지 않는다)
  K6_NET="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' ipo-api-gateway 2>/dev/null | awk '{print $1}')"
  [ -n "$K6_NET" ] || die 13 "게이트웨이 컨테이너의 네트워크를 찾지 못함"
  echo "k6_network=$K6_NET" >> "$OUT/env.txt"
  say "버전 기록: $(head -1 "$OUT/env.txt")"

  # 1. 토큰 (회원 수 확인 포함) → 예수금
  refresh_tokens || die 3 "토큰 재발급 실패"
  s1_cost=0
  for r in "${ST[@]}"; do s1_cost=$(( s1_cost + $(ceil_div $((r * DUR)) "$USERS") )); done
  need=$(( S0_PRICE * QUANTITY * (WCAP + 3) + S1_PRICE * QUANTITY * s1_cost ))
  minbal="$(pg_int "select coalesce(min(a.cash_balance), 0) from account.account a join account.users u on u.user_id = a.user_id where u.login_id like 'lt%'")" || die 4 "예수금 조회 실패"
  accounts="$(pg_int "select count(*) from account.account a join account.users u on u.user_id = a.user_id where u.login_id like 'lt%'")" || die 4 "계좌 수 조회 실패"
  say "회원 계좌 ${accounts}개, 회원당 예수금 최소 ${minbal}원, 최대로 필요한 금액 ${need}원 (S0 최대 $((WCAP + 3))회차 + S1 최대 ${s1_cost}건)"
  [ "$accounts" -ge "$USERS" ] || die 4 "계좌 수(${accounts})가 회원 수(${USERS})보다 적음"
  [ "$minbal" -ge "$need" ] || die 4 "예수금 부족. 입금 API로 넣는다: python loadtest/seed/seed_users.py --run-id deposit-<이름> --amount <금액>"

  # 2. 콜드 시작: 세 서비스 재시작 → healthy → 45초 대기 → 사전 점검
  say "재시작: $RESTART_SERVICES"
  # shellcheck disable=SC2086
  compose restart $RESTART_SERVICES >> "$LOG" 2>&1 || die 7 "재시작 실패"
  for c in $COLD_CONTAINERS; do say "시작 시각 $c $(docker inspect -f '{{.State.StartedAt}}' "$c")"; done
  # shellcheck disable=SC2086
  wait_healthy 600 $ALL_CONTAINERS || die 7 "재시작 뒤 healthy가 아님"
  say "healthy. 게이트웨이의 서비스 목록 반영을 위해 45초 대기 (요청 없음)"; sleep 45
  (cd "$DIR" && bash precheck.sh --run-id "$BATCH-precheck") > /dev/null 2>&1 || die 9 "사전 점검 실패 (results/$BATCH-precheck/precheck.txt)"
  say "사전 점검 통과 (results/$BATCH-precheck/precheck.txt)"
  kafka_lag > /dev/null || die 9 "Consumer 그룹 조회 실패 또는 필수 그룹 없음 ($REQUIRED_GROUPS)"
  dlt0="$(kafka_dlt_total)" || die 9 "DLT 조회 실패"
  say "DLT 합계(시작)=$dlt0"

  # 3. 공모주 미리 생성 (부하 중에는 만들지 않는다)
  for i in $(seq 1 $((WCAP + 3))); do
    k="$(new_ipo "$BATCH-S0-$i" "$S0_PRICE")" || die 11 "S0 공모주 생성 실패"
    S0_IPOS+=("$k")
  done
  : > "$PLAN"
  for r in "${ST[@]}"; do
    n=$(( $(ceil_div $((r * DUR)) "$USERS") + 1 )); l=""
    for i in $(seq 1 "$n"); do k="$(new_ipo "$BATCH-S1-$r-$i" "$S1_PRICE")" || die 11 "S1 공모주 생성 실패"; l="$l,$k"; done
    echo "$r ${l#,}" >> "$PLAN"
  done
  say "공모주 생성: S0 ${#S0_IPOS[@]}개(공모가 $S0_PRICE), S1 단계별 $(awk '{print $1 ":" gsub(/,/, ",") + 1 "개"}' "$PLAN" | tr '\n' ' ')(공모가 $S1_PRICE)"
  echo "run_id,scenario,phase,target_rate,duration_s,expected_requests,ipo_ids,k6_start,k6_end,drain_s" > "$MANIFEST"

  # 4. S0: 워밍업 → 안정 판정 → 측정 3회
  avgs=(); stable_at=0
  for i in $(seq 1 "$WCAP"); do
    run_s0 "$BATCH-S0-n$i" warmup
    avgs+=("$LAST_AVG")
    if stable_check "$STABLE_PCT" "${avgs[@]}"; then stable_at=$((i - 2)); say "안정 판정: n${stable_at}~n$i (평균 응답 연속 3회차가 각각 그 평균의 +-${STABLE_PCT}% 이내)"; break; fi
  done
  [ "$stable_at" -gt 0 ] || die 2 "워밍업 ${WCAP}회차 안에 안정 판정 기준에 닿지 않음"
  for i in 1 2 3; do run_s0 "$BATCH-S0-r$i" measure; done

  # 5. S1: 토큰을 새로 받고 쉰 뒤 속도 단계마다 독립 실행
  refresh_tokens || die 3 "S1 전 토큰 재발급 실패"
  say "재발급 뒤 ${GAP}초 대기 (요청 없음)"; sleep "$GAP"
  for i in $(seq 0 $((${#ST[@]} - 1))); do
    run_s1 "${ST[$i]}" "$([ "$i" -eq $((${#ST[@]} - 1)) ] && echo 1 || echo 0)"
  done
  if dlt1="$(kafka_dlt_total)"; then say "DLT 합계(종료)=$dlt1 (증가 $((dlt1 - dlt0)))"; else say "DLT 합계(종료) 조회 실패"; fi
  say "== 부하 종료. 후처리 시작"

  # 6. 후처리와 판정
  if post_process; then say "== 후처리 완료. 판정: $OUT/판정.md"; return 0; fi
  say "== 후처리 일부 실패 (위 로그). 부하 결과는 그대로 있다. 판정: $OUT/판정.md"
  return 10
}

# source로 불러오면(테스트) main을 실행하지 않는다
if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
