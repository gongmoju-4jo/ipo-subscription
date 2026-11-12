#!/usr/bin/env bash
# 정식 측정: S0, S1 각각에서 G1(처리량, 응답, 오류)과 G2(접수→확정 p95, 소진 시간)를 판정할 수 있게 실행하고,
# 부하가 모두 끝난 뒤 후처리(보고서, 요청 분류, 정합성 SQL)와 판정(verify/judge.py)까지 한다.
# SCENARIOS로 S3, S4(Synthetic Data, loadtest/synthetic/)를 S0과 같은 세션에서 번갈아 실행해 비교할 수 있다.
#
# 사용: bash loadtest/official.sh --batch <이름>
#       SCENARIOS=S0,S3,S4 BASELINE_DIR=loadtest/data/baseline-s34 bash loadtest/official.sh --batch <이름>
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
#   워밍업 (측정 앞의 독립 단계. 시간 기준으로 응답 시간 추세가 평평해질 때까지 일정한 부하를 건다. verify/warmup.py)
#   WARM_RATE=200     워밍업 도착률(건/초). 확정 대기가 쌓이지 않는 속도로 두고, 모든 요청이 확정되게 공모주를 돌린다
#   WARM_BUCKET=15    구간 길이(초). 구간마다 게이트웨이 평균 응답 시간을 Prometheus에서 읽는다
#   WARM_WINDOW=8     추세를 볼 구간 수 (15초 x 8 = 최근 2분)
#   WARM_SLOPE_PCT=5  기울기 허용 범위: 최근 창에 맞춘 직선의 기울기가 분당 창 평균의 +-5% 이내면 통과
#   WARM_SLOPE_MS=1   또는 기울기가 분당 +-1ms 이내면 통과 (응답이 수 ms일 때 %로 본 허용 폭이 너무 좁아지는 것을 막는다. 0이면 보지 않음)
#   WARM_CONSEC=2     연속 통과 횟수 (한 번 우연히 평평하게 나온 것으로 끝내지 않는다)
#   WARM_MIN=180 WARM_MAX=900  최소, 최대 시간(초). 최대까지 정상 상태에 닿지 않으면 중단 (종료 코드 2)
#   SCENARIOS=S0,S1  실행할 시나리오 (S0은 필수). 순서: 워밍업 → [S3, S4 경로 예열 각 1회] → S0, S3, S4를 번갈아 3회씩 → [S1 단계]
#                   S3, S4는 S0과 같은 100명 x 10회이고 요청 구성은 행동 CSV를 따른다. 경로 예열은 워밍업(모두 확정 경로)이 데우지 못한
#                   거절 경로(이중청약, 잔액 부족)를 첫 측정 전에 한 번 지나가게 하는 것이고, 판정은 참고(정합성은 판정)
#                   S3, S4는 두 번째 계좌와 잔액 부족 묶음이 있는 기준 스냅샷이 필요하다 (BASELINE_DIR, docs/LOADTEST.md 3-4절)
#   MEASURE_ORDER=  측정 3회 안에서 S0, S3, S4를 도는 순서 (예: S3,S4,S0). 비우면 S0, S3, S4. 실행하는 시나리오를 한 번씩 정확히 적는다
#                   워밍업 직후 첫 측정은 늘 같은 시나리오가 맡아 그 시나리오만 불리할 수 있어서, 세션마다 순서를 돌려 쓴다
#                   (세 세션: S0,S3,S4 / S3,S4,S0 / S4,S0,S3). 경로 예열(S3, S4)은 순서와 관계없이 먼저 한다
#   S4_PREFIX=lb   S4 잔액 부족 묶음의 loginId 접두사 (seed_users.py --prefix). 회원 CSV는 loadtest/data/users-s4.csv
#   GAP=30          실행 사이 대기(초). 앞 실행의 처리 대기는 소진 확인으로 없애고, 이 대기는 보고서의 서버 지표 구간
#                   (요청 구간 앞뒤 10초 + Prometheus 5초 수집)이 앞뒤 실행과 섞이지 않게 하는 용도다. GC, DB 체크포인트는 대기로 피할 수 없다
#                   개선 전후를 비교할 때는 같은 값을 쓴다
#   STAGES=100,200,300,433,600  S1 속도 단계(건/초). 433은 G1 목표의 근거(오픈 직후 피크), 600은 목표를 넘는 부하 확인
#   DUR=90          S1 단계 유지(초)
#   S1_REPEAT=3     S1 단계마다 같은 조건으로 몇 번 측정할지 (팀 규칙: 최소 3회, 중앙값). 3 미만이면 판정이 "참고값"이 된다
#   S0_PRICE=15000  S1_PRICE=1000  QUANTITY=10     공모가(원), 청약 수량(주)
#   PRE_VUS=500 MAX_VUS=2000   S1 k6 가상 사용자
#   P95_LIMIT=15 DRAIN_LIMIT=15  G2 합격 기준(초)
#   DRAIN_TIMEOUT=2400  소진 대기 상한(초)
#   REQUIRED_GROUPS="subscription-margin-reserved subscription-margin-reserve-failed account-margin-reserve"   소진 대기에서 lag을 확인할 필수 Consumer 그룹 (s2.sh와 같은 기본값)
#   IGNORE_GROUPS="subscription-requested"   lag 합에서 뺄 Consumer 그룹 (s2.sh와 같은 기본값. 읽는 Consumer가 없어진 접수 그룹에 남은 메시지가 소진 대기를 막지 않게. 빈 값이면 모두 셈)
#   SKIP_BUILD=1    빌드, 배포를 건너뛴다 (jar sha256 비교는 그대로 한다)
#   ALLOW_DIRTY=1   커밋하지 않은 변경이 있어도 실행 (env.txt에 dirty로 남는다. 정식 결과에 쓰지 않는다)
#   RESTORE=1       시작할 때 기준 스냅샷으로 DB와 Kafka를 되돌린다 (baseline.sh restore). 측정마다 같은 DB 크기, 같은 데이터에서 시작한다.
#                   0이면 되돌리지 않는다 (env.txt에 남고 정식 결과로 쓰지 않는다). 스냅샷은 baseline.sh save로 만든다
#   DB_DUMP=1       후처리 뒤 측정이 끝난 DB를 db-after.dump로 남긴다 (원본 행 보관. 0이면 남기지 않는다)
#   STATS_INTERVAL=5  docker stats 기록 간격(초). 0이면 기록하지 않는다. 조회 1번에 약 2~3초 걸려서 간격은 조회 시간을 포함한다
#                   PostgreSQL, Kafka, k6를 포함한 모든 컨테이너의 CPU, 메모리를 docker-stats.raw에 남기고, 후처리에서
#                   docker-stats.csv와 자원.md(실행마다 k6 구간의 컨테이너별 최대)로 바꾼다. 조회도 CPU를 쓰므로 개선 전후는 같은 값으로 비교한다
# 쿼리 통계: 실행마다 소진 확인 뒤 pg_stat_statements(loadtest/sql/slow-queries.sql)를 results/<run_id>/slow-queries.txt에 남기고 비운다
# autovacuum: 첫 실행 직전과 실행마다 소진 확인 뒤 테이블별 autovacuum, autoanalyze 누적 횟수를 남기고(autovacuum-before.csv,
#   results/<run_id>/autovacuum.csv), 후처리에서 실행별 차이를 autovacuum.md로 정리한다 (verify/autovacuum.py)
#   (그 실행의 요청과 소진까지의 Consumer 쿼리. 회차 사이 토큰 재발급이 끼면 그 로그인 쿼리도 다음 실행에 섞인다)
#
# 종료 코드: 0 정상 / 2 안정 판정 미도달 / 3 토큰 / 4 예수금 / 5 버전(dirty, jar 불일치) / 6 소진 대기 / 7 기동 / 8 k6 / 9 사전 점검
#           / 10 부하는 끝났고 후처리 일부 실패(DB 덤프 포함) / 11 공모주 생성 / 12 잠금, 결과 폴더 / 13 도구 / 14 기준 상태 복원 / 1 그 밖
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

WARM_RATE="${WARM_RATE:-200}"; WARM_BUCKET="${WARM_BUCKET:-15}"; WARM_WINDOW="${WARM_WINDOW:-8}"; WARM_SLOPE_PCT="${WARM_SLOPE_PCT:-5}"; WARM_SLOPE_MS="${WARM_SLOPE_MS:-1}"
WARM_CONSEC="${WARM_CONSEC:-2}"; WARM_MIN="${WARM_MIN:-180}"; WARM_MAX="${WARM_MAX:-900}"; GAP="${GAP:-30}"
STAGES="${STAGES:-100,200,300,433,600}"; DUR="${DUR:-90}"; S1_REPEAT="${S1_REPEAT:-3}"
S0_PRICE="${S0_PRICE:-15000}"; S1_PRICE="${S1_PRICE:-1000}"; QUANTITY="${QUANTITY:-10}"
PRE_VUS="${PRE_VUS:-500}"; MAX_VUS="${MAX_VUS:-2000}"
S0_VUS=100; S0_ITERS=10    # S0 = 100명 x 10회 = 1,000건 (발제 조건, 바꾸지 않는다). 판정은 요청 수가 이 값과 같아야 유효로 본다
P95_LIMIT="${P95_LIMIT:-15}"; DRAIN_LIMIT="${DRAIN_LIMIT:-15}"; DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-2400}"
REQUIRED_GROUPS="${REQUIRED_GROUPS:-subscription-margin-reserved subscription-margin-reserve-failed account-margin-reserve}"
IGNORE_GROUPS="${IGNORE_GROUPS-subscription-requested}"
TOKEN_MAX_S=$((55 * 60))   # 토큰 만료 1시간보다 5분 일찍 끊는다
STATS_INTERVAL="${STATS_INTERVAL:-5}"
RESTORE="${RESTORE:-1}"; DB_DUMP="${DB_DUMP:-1}"
K6_CPUS=2; K6_MEMORY=1g    # k6 컨테이너 상한 (모든 실행 같음. env.txt에 기록)
SCENARIOS="${SCENARIOS:-S0,S1}"; S4_PREFIX="${S4_PREFIX:-lb}"; MEASURE_ORDER="${MEASURE_ORDER:-}"; ORDER_LIST=""
S4_CSV="$DIR/data/users-s4.csv"
SYN_RUNS=4                 # S3, S4 회원이 청약하는 횟수 = 경로 예열 1 + 측정 3 (S4 행동 CSV의 입금액 조건, synthetic/validate.py --runs)
SYN_AMOUNT=150000          # S4 행동 CSV를 만든 청약 금액 (공모가 15,000원 x 10주. judge.py도 이 금액으로 기대값을 계산한다)

BATCH=""; OUT=""; LOG=""; MANIFEST=""; PLAN=""; LOCK="$DIR/results/.official.lock"; LOCK_OWNED=""
PY=""; COMPOSE=(); K6_NET=""; TOKEN_AT=0; TOKEN_N=0; S0_IPOS=(); S0_NEXT=0; WARM_IPOS=""; LAST_AVG=""; USERS=0; STATS_PID=""; SLOWQ_FAILS=0; AUTOVAC_FAILS=0
HAS_S1=0; HAS_S3=0; HAS_S4=0; S3_IPOS=(); S3_NEXT=0; S4_IPOS=(); S4_NEXT=0; S4_USERS=0

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
# 표준입력: kafka-consumer-groups.sh --describe --all-groups 출력 / 인자: 필수 그룹 목록, lag 합에서 뺄 그룹 목록(없으면 모두 셈)
# lag = LOG-END-OFFSET - CURRENT-OFFSET (커밋 오프셋이 '-'이면 0으로 보고 끝 오프셋 전체를 대기로 센다).
# 뺄 그룹도 행 형식 검사와 필수 그룹 확인에는 들어간다. 형식이 이상한 행, 데이터 행 0개, 필수 그룹 누락이면 실패
parse_lag() {
  awk -v req="$1" -v ign="${2:-}" '
    BEGIN { n = split(req, R, " "); m = split(ign, I, " "); for (i = 1; i <= m; i++) skip[I[i]] = 1; bad = 0; s = 0; rows = 0 }
    $1 == "GROUP" && $2 == "TOPIC" { next }
    NF >= 5 && $3 ~ /^[0-9]+$/ {
      leo = $5; cur = $4
      if (leo !~ /^[0-9]+$/) { bad = 1; next }
      if (cur == "-") cur = 0
      else if (cur !~ /^[0-9]+$/) { bad = 1; next }
      seen[$1] = 1; rows++
      if (!($1 in skip)) s += leo - cur
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
  printf '%s\n' "$out" | tr -d '\r' | parse_lag "$REQUIRED_GROUPS" "$IGNORE_GROUPS"
}
# 표준입력: kafka-get-offsets.sh 출력(토픽:파티션:끝오프셋) / 인자: 토픽 이름. 해당 토픽 행이 없으면 실패
parse_offsets() {
  awk -F: -v t="$1" '$1 == t && $3 ~ /^[0-9]+$/ { s += $3; c++ } END { if (c == 0) exit 1; print s }'
}
# DLT 토픽 전체를 한 번에 조회한다 (토픽 이름 정규식). 토픽마다 행이 있어야 하고, 하나라도 없으면 실패
kafka_dlt_total() {
  local topics t out n total=0 re
  topics="$(kafka_topics)" || return 1
  re="($(printf '%s\n' "$topics" | sed 's/\./\\./g' | paste -sd '|' -))\\.DLT"
  out="$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$re" 2>&1)" || return 1
  out="$(printf '%s\n' "$out" | tr -d '\r')"
  for t in $topics; do
    n="$(printf '%s\n' "$out" | parse_offsets "$t.DLT")" || return 1
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

# 회원 CSV가 t0 이후 갱신됐고 모든 행에 토큰이 있으면 회원 수를 출력한다
token_csv_rows() { # 인자: CSV 경로, 재발급 시작 시각
  py - "$(pypath "$1")" "$2" <<'PYEOF'
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
}

# 토큰 재발급. 성공하면 TOKEN_AT을 재발급 "시작" 시각으로 둔다 (만료는 발급 시각 기준이라 늦게 잡으면 위험)
# 기본 묶음(users.csv)과, S4가 있으면 잔액 부족 묶음(users-s4.csv)도 다시 발급한다
refresh_tokens() {
  local t0 out rc
  TOKEN_N=$((TOKEN_N + 1)); t0="$(date +%s)"
  say "토큰 재발급 #$TOKEN_N 시작 (로그인 요청 발생)"
  out="$(cd "$REPO" && py loadtest/seed/seed_users.py --run-id "tok-$BATCH-$TOKEN_N" --refresh-tokens 2>&1)"; rc=$?
  printf '%s\n' "$out" | tail -3 >> "$LOG"
  [ "$rc" -eq 0 ] || { say "토큰 재발급 실패 rc=$rc"; return 1; }
  USERS="$(token_csv_rows "$USERS_CSV" "$t0")" || { say "토큰 CSV 확인 실패 ($USERS_CSV)"; return 1; }
  is_uint "$USERS" && [ "$USERS" -ge 1000 ] || { say "회원 수가 1,000명 미만: $USERS"; return 1; }
  if [ "$HAS_S4" = 1 ]; then
    out="$(cd "$REPO" && py loadtest/seed/seed_users.py --run-id "tok-$BATCH-$TOKEN_N" --refresh-tokens --prefix "$S4_PREFIX" --out "$(pypath "$S4_CSV")" 2>&1)"; rc=$?
    printf '%s\n' "$out" | tail -3 >> "$LOG"
    [ "$rc" -eq 0 ] || { say "잔액 부족 묶음 토큰 재발급 실패 rc=$rc"; return 1; }
    S4_USERS="$(token_csv_rows "$S4_CSV" "$t0")" || { say "토큰 CSV 확인 실패 ($S4_CSV)"; return 1; }
    is_uint "$S4_USERS" && [ "$S4_USERS" -ge 1000 ] || { say "잔액 부족 묶음 회원 수가 1,000명 미만: $S4_USERS"; return 1; }
  fi
  TOKEN_AT="$t0"
  say "토큰 재발급 #$TOKEN_N 완료 ($(( $(date +%s) - t0 ))초, 회원 ${USERS}명$([ "$HAS_S4" = 1 ] && printf ', 잔액 부족 묶음 %s명' "$S4_USERS"))"
}
# 다음 실행(need초) 동안 토큰이 유효한지 보고, 모자라면 요청 없는 지금 재발급하고 GAP초 쉰다
ensure_token() {
  if [ $(( $(date +%s) - TOKEN_AT + $1 )) -ge "$TOKEN_MAX_S" ]; then
    refresh_tokens || die 3 "토큰 재발급 실패"
    say "재발급 뒤 ${GAP}초 대기 (요청 없음)"; sleep "$GAP"
  fi
}

# 공모주 여러 개를 SQL 한 번으로 만든다 (이름은 <접두사>-1 ~ <접두사>-N). 정확히 N개의 UUID가 아니면 실패
new_ipos() { # 인자: 이름 접두사, 개수, 공모가. 공모주 ID를 쉼표로 이어 출력
  local out n id
  out="$(cd "$DIR" && bash seed/new_ipo.sh --name "$1" --count "$2" --price "$3" 2>>"$LOG" | tr -d '\r ')" || return 1
  n=0
  while IFS= read -r id; do [ -z "$id" ] && continue; is_uuid "$id" || return 1; n=$((n + 1)); done <<< "$out"
  [ "$n" -eq "$2" ] || return 1
  printf '%s\n' "$out" | awk 'NF' | paste -sd ',' -
}

# k6 실행. 종료 코드 0(통과), 99(임계값 위반)만 정상으로 보고, summary.json에 요청이 있어야 한다
k6_run() { # 인자: 시나리오, 첫 공모주, run_id, -e 인자들...
  local scen="$1" ipo="$2" run="$3" rc n; shift 3
  mkdir -p "$DIR/results/$run"
  (cd "$DIR" && K6_MODE=container K6_IMAGE="$K6_IMAGE" K6_CPUS="$K6_CPUS" K6_MEMORY="$K6_MEMORY" K6_LABEL="$BATCH" K6_NAME="k6-$run" K6_NETWORK="$K6_NET" \
     bash run-k6.sh "$scen" --ipo "$ipo" --run-id "$run" "$@") > "$DIR/results/$run/k6-console.out" 2>&1
  rc=$?
  case "$rc" in 0|99) ;; *) say "$run k6 종료 코드 $rc (k6-console.out 확인)"; return "$rc" ;; esac
  [ -f "$DIR/results/$run/summary.json" ] || { say "$run summary.json 없음"; return 1; }
  n="$(json_metric "$DIR/results/$run/summary.json" http_reqs count)" || { say "$run summary.json에 http_reqs 없음"; return 1; }
  [ "${n%.*}" -gt 0 ] 2>/dev/null || { say "$run 요청 0건"; return 1; }
  return 0
}


# ---------- 자원 기록 (docker stats) ----------
# 간격마다 모든 컨테이너를 한 번 조회해 "<epoch>\t<json>" 줄로 덧붙인다. 조회 실패는 "<epoch>\tERROR\t<메시지>"로 남기고 계속한다
stats_loop() { # 인자: 출력 파일, 간격(초)
  local f="$1" iv="$2" t0 out el
  while :; do
    t0="$(date +%s)"
    if out="$(docker stats --no-stream --format '{{json .}}' 2>&1)"; then
      printf '%s\n' "$out" | awk -v t="$t0" 'NF { sub(/\r$/, ""); print t "\t" $0 }' >> "$f"
    else
      printf '%s\tERROR\t%s\n' "$t0" "$(printf '%s' "$out" | head -1)" >> "$f"
    fi
    el=$(( $(date +%s) - t0 ))
    [ "$el" -lt "$iv" ] && sleep $(( iv - el ))
  done
}

stats_start() {
  [ "$STATS_INTERVAL" -gt 0 ] || { say "docker stats 기록 안 함 (STATS_INTERVAL=0)"; return 0; }
  stats_loop "$OUT/docker-stats.raw" "$STATS_INTERVAL" &
  STATS_PID=$!
  say "docker stats 기록 시작 (${STATS_INTERVAL}초 간격, docker-stats.raw)"
}

stats_stop() {
  [ -n "$STATS_PID" ] || return 0
  kill "$STATS_PID" 2>/dev/null; wait "$STATS_PID" 2>/dev/null
  STATS_PID=""
  say "docker stats 기록 종료 ($(grep -c . "$OUT/docker-stats.raw" 2>/dev/null || echo 0)줄)"
}

# 쿼리 통계 (pg_stat_statements, loadtest/sql/slow-queries.sql). compose.perf.yml이 미리 읽어 들이도록 설정한다
slowq() { # 인자: 출력 파일, 1이면 통계를 비운다
  if [ "${2:-0}" = 1 ]; then
    docker exec -i "$PG_CONTAINER" psql -U postgres -d ipo -v ON_ERROR_STOP=1 -v reset=1 -f - < "$DIR/sql/slow-queries.sql" > "$1" 2>&1
  else
    docker exec -i "$PG_CONTAINER" psql -U postgres -d ipo -v ON_ERROR_STOP=1 -f - < "$DIR/sql/slow-queries.sql" > "$1" 2>&1
  fi
}

# 실행 하나가 끝나고 소진까지 확인한 뒤: 이번 실행 동안의 쿼리 통계를 남기고 비운다 (요청이 없는 구간에서 부른다).
# 실패해도 측정은 이어 가고, 후처리에서 실패로 센다 (종료 코드 10)
slowq_run() { # 인자: run_id
  if slowq "$DIR/results/$1/slow-queries.txt" && slowq "$DIR/results/$1/.slow-queries-reset.out" 1; then
    rm -f "$DIR/results/$1/.slow-queries-reset.out"; return 0
  fi
  say "$1 쿼리 통계(pg_stat_statements) 조회 또는 초기화 실패 (results/$1/slow-queries.txt)"
  SLOWQ_FAILS=$((SLOWQ_FAILS + 1)); return 0
}

# autovacuum, autoanalyze 누적 횟수 (pg_stat_user_tables). PostgreSQL은 바꾸거나 지운 행의 옛 버전(죽은 행)을 남기고
# autovacuum이 백그라운드에서 치운다 (autoanalyze는 쿼리 계획용 통계 갱신). 측정 중 행 변경이 많아 도중에 자동으로 돌고,
# 돌 때 CPU, IO를 써서 그 실행이 느려질 수 있다. 누적값만 남는 통계라 실행마다 찍어 두고, 후처리에서 앞 기록과의 차이로
# 어느 실행과 겹쳤는지 정리한다 (verify/autovacuum.py → autovacuum.md)
autovac() { # 인자: 출력 파일 (autovacuum.csv). 조회 실패, 빈 결과면 실패
  local out
  out="$(docker exec -i "$PG_CONTAINER" psql -U postgres -d ipo -v ON_ERROR_STOP=1 -qtA -F ',' -c \
    "select schemaname || '.' || relname, n_live_tup, n_dead_tup, autovacuum_count, autoanalyze_count, coalesce(extract(epoch from last_autovacuum)::bigint::text, ''), coalesce(extract(epoch from last_autoanalyze)::bigint::text, '') from pg_stat_user_tables order by 1")" || return 1
  out="$(printf '%s\n' "$out" | tr -d '\r' | awk 'NF')"
  [ -n "$out" ] || return 1
  { echo "table,live_tup,dead_tup,autovacuum_count,autoanalyze_count,last_autovacuum,last_autoanalyze"; printf '%s\n' "$out"; } > "$1"
}
# 실행 하나가 끝나고 소진까지 확인한 뒤 부른다. 실패해도 측정은 이어 가고, 후처리에서 실패로 센다 (종료 코드 10)
autovac_run() { # 인자: run_id
  autovac "$DIR/results/$1/autovacuum.csv" && return 0
  say "$1 autovacuum 기록 실패 (results/$1/autovacuum.csv)"
  AUTOVAC_FAILS=$((AUTOVAC_FAILS + 1)); return 0
}

# 컨테이너별 CPU, 메모리 상한과 JVM 옵션을 env.txt에 남긴다 ("자원 <이름> cpu=<코어> mem=<바이트> ..."). 0은 상한 없음
record_limits() {
  local c line
  for c in $ALL_CONTAINERS; do
    line="$(docker inspect -f '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}' "$c" 2>/dev/null)" || { echo "자원 $c 조회 실패" >> "$OUT/env.txt"; continue; }
    echo "자원 $c cpu=$(awk -v n="${line%% *}" 'BEGIN { printf "%g", n / 1e9 }') mem=${line##* } $(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$c" 2>/dev/null | grep -E '^(JAVA_TOOL_OPTIONS|KAFKA_HEAP_OPTS)=' | tr '\n' ' ')" >> "$OUT/env.txt"
  done
  echo "자원 k6 cpu=$K6_CPUS mem=$K6_MEMORY (실행마다 k6-<run_id> 컨테이너)" >> "$OUT/env.txt"
}

cleanup() {
  local ids
  stats_stop
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
    echo "설정 WARM_RATE=$WARM_RATE WARM_BUCKET=$WARM_BUCKET WARM_WINDOW=$WARM_WINDOW WARM_SLOPE_PCT=$WARM_SLOPE_PCT WARM_SLOPE_MS=$WARM_SLOPE_MS WARM_CONSEC=$WARM_CONSEC WARM_MIN=$WARM_MIN WARM_MAX=$WARM_MAX"
    echo "설정 GAP=$GAP STAGES=$STAGES DUR=$DUR S1_REPEAT=$S1_REPEAT S0_VUS=$S0_VUS S0_ITERS=$S0_ITERS S0_PRICE=$S0_PRICE S1_PRICE=$S1_PRICE QUANTITY=$QUANTITY PRE_VUS=$PRE_VUS MAX_VUS=$MAX_VUS"
    echo "설정 PG_CONTAINER=$PG_CONTAINER KAFKA_CONTAINER=$KAFKA_CONTAINER EUREKA_URL=$EUREKA_URL"
    echo "설정 P95_LIMIT=$P95_LIMIT DRAIN_LIMIT=$DRAIN_LIMIT DRAIN_TIMEOUT=$DRAIN_TIMEOUT SKIP_BUILD=${SKIP_BUILD:-0} ALLOW_DIRTY=${ALLOW_DIRTY:-0}"
    echo "설정 REQUIRED_GROUPS=$REQUIRED_GROUPS IGNORE_GROUPS=$IGNORE_GROUPS"
    echo "설정 STATS_INTERVAL=$STATS_INTERVAL K6_CPUS=$K6_CPUS K6_MEMORY=$K6_MEMORY RESTORE=$RESTORE DB_DUMP=$DB_DUMP"
    echo "설정 SCENARIOS=$SCENARIOS MEASURE_ORDER=${ORDER_LIST// /,} S4_PREFIX=$S4_PREFIX SYN_RUNS=$SYN_RUNS BASELINE_DIR=${BASELINE_DIR:-$DIR/data/baseline}"
    # 어떤 행동 데이터로 측정했는지 (Synthetic Data는 커밋한 CSV를 쓰지만, 같은 커밋인지와 별개로 파일 자체를 남긴다)
    [ "$HAS_S3" = 1 ] && echo "행동 CSV s3-behavior.csv sha256=$(sha256_file "$DIR/synthetic/s3-behavior.csv" 2>/dev/null || echo 조회실패)"
    [ "$HAS_S4" = 1 ] && echo "행동 CSV s4-behavior.csv sha256=$(sha256_file "$DIR/synthetic/s4-behavior.csv" 2>/dev/null || echo 조회실패)"
  } >> "$OUT/env.txt"
  return "$mismatch"
}

# ---------- 회차 ----------
# 워밍업: 측정 앞의 독립 단계. WARM_RATE로 일정한 부하를 걸고(모든 요청이 확정되게 공모주를 돌림),
# verify/warmup.py가 응답 시간 추세가 평평해졌다고 판정하면 k6를 멈춘다. 그 뒤 소진을 확인하고 쉰다
run_warmup() {
  local run="$BATCH-W" first t0 t1 d drain_end drc kpid wrc krc
  first="${WARM_IPOS%%,*}"
  ensure_token $((WARM_MAX + 300))
  mkdir -p "$DIR/results/$run"
  say "워밍업 시작: ${WARM_RATE}건/초 일정 부하, ${WARM_BUCKET}초 구간 ${WARM_WINDOW}개 창의 기울기가 분당 +-${WARM_SLOPE_PCT}% 또는 +-${WARM_SLOPE_MS}ms 이내 연속 ${WARM_CONSEC}번이면 끝 (최소 ${WARM_MIN}초, 최대 ${WARM_MAX}초)"
  t0="$(date +%s)"
  (cd "$DIR" && K6_MODE=container K6_IMAGE="$K6_IMAGE" K6_CPUS="$K6_CPUS" K6_MEMORY="$K6_MEMORY" K6_LABEL="$BATCH" K6_NAME="k6-$run" K6_NETWORK="$K6_NET"      bash run-k6.sh s1 --ipo "$first" --run-id "$run" -e "IPO_IDS=$WARM_IPOS" -e "RATE=$WARM_RATE" -e "DURATION=$((WARM_MAX + 60))s"        -e "PRE_VUS=$PRE_VUS" -e "MAX_VUS=$MAX_VUS" -e "QUANTITY=$QUANTITY") > "$DIR/results/$run/k6-console.out" 2>&1 &
  kpid=$!
  # k6가 인자 오류 등으로 바로 끝났으면 기다리지 않고 멈춘다
  sleep 12
  kill -0 "$kpid" 2>/dev/null || { wait "$kpid"; die 8 "$run k6가 시작하자마자 끝남 (종료 코드 $?, results/$run/k6-console.out)"; }
  # 가상 사용자 준비(약 8초)가 끝난 뒤부터 구간을 센다
  py "$(pypath "$DIR/verify/warmup.py")" --since $((t0 + 10)) --csv "$(pypath "$OUT/warmup.csv")" --prometheus "${PROMETHEUS_URL:-http://127.0.0.1:9090}"     --bucket "$WARM_BUCKET" --window "$WARM_WINDOW" --slope-pct "$WARM_SLOPE_PCT" --slope-ms "$WARM_SLOPE_MS" --consecutive "$WARM_CONSEC" --min "$WARM_MIN" --max "$WARM_MAX"     >> "$LOG" 2>&1; wrc=$?
  docker stop -t 30 "k6-$run" > /dev/null 2>&1
  wait "$kpid"; krc=$?
  t1="$(date +%s)"
  case "$wrc" in
    0) ;;
    2) die 2 "워밍업 ${WARM_MAX}초 안에 정상 상태에 닿지 않음 (warmup.csv)" ;;
    1) die 1 "warmup.py 인자 오류 (진행.log)" ;;
    *) die 2 "워밍업 판정 실패 (warmup.py 종료 코드 $wrc, 진행.log, warmup.csv)" ;;
  esac
  # 멈춘 k6는 종료 코드 105(terminated)로 끝나고 summary.json은 남는다. 그 밖의 종료 코드는 실패
  case "$krc" in 0|99|105) ;; *) die 8 "$run k6 종료 코드 $krc (results/$run/k6-console.out)" ;; esac
  [ -f "$DIR/results/$run/summary.json" ] || die 8 "$run summary.json 없음"
  d="$(drain "$WARM_IPOS" "$DRAIN_TIMEOUT")"; drc=$?
  [ "$drc" -eq 0 ] || die 6 "$run 소진 대기 실패 (1 시간 초과, 2 조회 실패, 3 인자): $drc"
  drain_end="$(date +%s)"
  echo "$run,W,warmup,,$WARM_RATE,$((t1 - t0)),,$(printf '%s' "$WARM_IPOS" | tr ',' ';'),$t0,$t1,$d,$drain_end" >> "$MANIFEST"
  slowq_run "$run"
  autovac_run "$run"
  say "워밍업 끝: $((t1 - t0))초, 요청 $(json_metric "$DIR/results/$run/summary.json" http_reqs count 2>/dev/null)건, $(tail -1 "$OUT/warmup.csv" | cut -d, -f4)ms (warmup.csv). 소진 대기=${d}초. ${GAP}초 대기 (요청 없음)"
  sleep "$GAP"
}

# SCENARIOS("S0,S3,S4" 등)를 읽어 HAS_S1, HAS_S3, HAS_S4를 정한다. S0이 없거나, 모르는 값, 같은 값이 두 번이면 실패
parse_scenarios() { # 인자: 쉼표로 구분한 시나리오
  local s seen=","
  HAS_S1=0; HAS_S3=0; HAS_S4=0
  for s in ${1//,/ }; do
    case "$seen" in *",$s,"*) echo "SCENARIOS에 ${s}가 두 번 있음: $1" >&2; return 1 ;; esac
    seen="$seen$s,"
    case "$s" in
      S0) ;;
      S1) HAS_S1=1 ;;
      S3) HAS_S3=1 ;;
      S4) HAS_S4=1 ;;
      *) echo "SCENARIOS 값은 S0, S1, S3, S4 중에서 고른다: $s" >&2; return 1 ;;
    esac
  done
  case "$seen" in *",S0,"*) ;; *) echo "SCENARIOS에 S0이 있어야 한다 (워밍업 뒤 기준값이자 S3, S4의 비교 대상): $1" >&2; return 1 ;; esac
  return 0
}

# MEASURE_ORDER("S3,S4,S0" 등)를 읽어 ORDER_LIST(공백으로 구분)를 정한다. 비우면 S0, S3, S4 (실행하는 것만)
# 지정하면 이번에 실행하는 시나리오(S0, 그리고 SCENARIOS에 있는 S3, S4)를 한 번씩 정확히 적어야 한다. HAS_S3, HAS_S4를 먼저 정해 둔다
parse_order() { # 인자: 쉼표로 구분한 순서 (비어 있을 수 있음)
  local want="S0" s seen="," got=""
  [ "$HAS_S3" = 1 ] && want="$want S3"
  [ "$HAS_S4" = 1 ] && want="$want S4"
  if [ -z "${1:-}" ]; then ORDER_LIST="$want"; return 0; fi
  for s in ${1//,/ }; do
    case " $want " in *" $s "*) ;; *) echo "MEASURE_ORDER에는 이번에 실행하는 시나리오만 쓴다 ($want): $s" >&2; return 1 ;; esac
    case "$seen" in *",$s,"*) echo "MEASURE_ORDER에 ${s}가 두 번 있음: $1" >&2; return 1 ;; esac
    seen="$seen$s,"; got="$got $s"
  done
  for s in $want; do
    case "$seen" in *",$s,"*) ;; *) echo "MEASURE_ORDER에 ${s}가 없음 (실행하는 시나리오: $want): $1" >&2; return 1 ;; esac
  done
  ORDER_LIST="${got# }"
  return 0
}

# 워밍업 뒤 S0, S3, S4의 실행 순서를 "시나리오 run_id 구간" 줄로 낸다 (S1은 그 뒤 따로)
# S3, S4는 경로 예열 1회 뒤 S0과 번갈아 3회씩 (몰아서 돌리면 세션 중 DB 증가, JVM 변화가 한 시나리오에 쏠린다)
# 번갈아 도는 순서는 MEASURE_ORDER (기본 S0, S3, S4). 세 번의 측정은 모두 같은 순서다
plan_runs() {
  local i s
  parse_order "${MEASURE_ORDER:-}" || return 1
  [ "$HAS_S3" = 1 ] && echo "S3 $BATCH-S3-W warmup"
  [ "$HAS_S4" = 1 ] && echo "S4 $BATCH-S4-W warmup"
  for i in 1 2 3; do
    for s in $ORDER_LIST; do
      echo "$s $BATCH-$s-r$i measure"
    done
  done
  return 0
}

# plan_runs 순서대로 run_one을 부른다. 순서는 fd 3으로 읽는다
# (표준입력으로 읽으면 반복 안의 docker exec -i(소진 확인 psql)가 남은 줄을 먹어 실행이 조용히 빠진다)
run_plan() {
  local scen run phase
  while read -r scen run phase <&3; do
    run_one "$scen" "$run" "$phase"
  done 3<<< "$(plan_runs)"
}

# S3, S4 행동 CSV를 이 측정의 요청 수, 청약 금액, 반복 횟수로 검사하고(synthetic/validate.py), 회원 CSV와 맞는지 본다
#   S3: 두 번째 계좌 청약(SECOND_ACCOUNT) 투자자가 모두 users.csv에 accountId2를 가졌는지
#   S4: users-s4.csv가 잔액 부족 묶음(S4_PREFIX)인지
# 통과하면 S4 예수금 부족 투자자 수를 출력한다 (S4가 없으면 0). 부하 전에 불러서, 워밍업 뒤에야 k6가 멈추는 일을 막는다
synthetic_check() {
  py - "$(pypath "$DIR/synthetic/validate.py")" "$(pypath "$USERS_CSV")" "$(pypath "$S4_CSV")" "$HAS_S3" "$HAS_S4" \
     $((S0_VUS * S0_ITERS)) $((S0_PRICE * QUANTITY)) "$SYN_RUNS" "$S4_PREFIX" <<'PYEOF'
import csv, importlib.util, os, sys
vpath, lt_csv, lb_csv, has3, has4, total, amount, runs, prefix = sys.argv[1:10]
total, amount, runs = int(total), int(amount), int(runs)
spec = importlib.util.spec_from_file_location("synthetic_validate", vpath)
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


def users(path):
    if not os.path.exists(path):
        sys.exit(f"회원 CSV가 없다: {path}")
    return list(csv.DictReader(open(path, encoding="utf-8")))


short = 0
if has3 == "1":
    lt = users(lt_csv)
    errors, exp = v.check("S3", total=total, users=len(lt))
    if errors:
        sys.exit(f"S3 행동 CSV 규칙 위반 {len(errors)}건: " + "; ".join(errors[:3]))
    missing = [x for x in exp["detail"]["second_account_vusers"] if not lt[x].get("accountId2")]
    if missing:
        sys.exit(f"회원 CSV에 두 번째 계좌(accountId2)가 없는 S3 투자자 {len(missing)}명 (예: {missing[:5]}). "
                 "S3, S4 기준 스냅샷을 만들 때의 회원 CSV를 쓴다 (docs/LOADTEST.md 3-4절)")
if has4 == "1":
    lb = users(lb_csv)
    errors, exp = v.check("S4", total=total, users=len(lb), amount=amount, runs=runs)
    if errors:
        sys.exit(f"S4 행동 CSV 규칙 위반 {len(errors)}건 (청약 금액 {amount}원, {runs}회 기준): " + "; ".join(errors[:3]))
    other = [r["loginId"] for r in lb if not r["loginId"].startswith(prefix)]
    if other:
        sys.exit(f"{lb_csv}가 잔액 부족 묶음({prefix})이 아니다 (예: {other[:3]})")
    short = exp["rejected"]["INSUFFICIENT_BALANCE"]
print(short)
PYEOF
}

# 회차 하나 (S0, S3, S4. 100명 x 10회): 다음 공모주로 k6 → 소진 확인 → manifest → 쿼리 통계 → GAP초 대기
run_one() { # 인자: 시나리오, run_id, phase(warmup|measure)
  local scen="$1" run="$2" phase="$3" ipo t0 t1 d drain_end drc rate script extra=()
  ensure_token 300
  case "$scen" in
    S0) [ "$S0_NEXT" -lt "${#S0_IPOS[@]}" ] || die 11 "S0 공모주가 모자람"
        ipo="${S0_IPOS[$S0_NEXT]}"; S0_NEXT=$((S0_NEXT + 1)); script=s0 ;;
    S3) [ "$S3_NEXT" -lt "${#S3_IPOS[@]}" ] || die 11 "S3 공모주가 모자람"
        ipo="${S3_IPOS[$S3_NEXT]}"; S3_NEXT=$((S3_NEXT + 1)); script=s3 ;;
    S4) [ "$S4_NEXT" -lt "${#S4_IPOS[@]}" ] || die 11 "S4 공모주가 모자람"
        ipo="${S4_IPOS[$S4_NEXT]}"; S4_NEXT=$((S4_NEXT + 1)); script=s4
        extra=(--users data/users-s4.csv -e "S4_PREFIX=$S4_PREFIX") ;;
    *) die 1 "run_one: 모르는 시나리오 $scen" ;;
  esac
  t0="$(date +%s)"
  k6_run "$script" "$ipo" "$run" ${extra[@]+"${extra[@]}"} -e "VUS=$S0_VUS" -e "ITERATIONS=$S0_ITERS" -e "QUANTITY=$QUANTITY" || die 8 "$run k6 실패"
  t1="$(date +%s)"
  d="$(drain "$ipo" "$DRAIN_TIMEOUT")"; drc=$?
  [ "$drc" -eq 0 ] || die 6 "$run 소진 대기 실패 (1 시간 초과, 2 조회 실패, 3 인자): $drc"
  drain_end="$(date +%s)"
  echo "$run,$scen,$phase,,,,$((S0_VUS * S0_ITERS)),$ipo,$t0,$t1,$d,$drain_end" >> "$MANIFEST"
  slowq_run "$run"
  autovac_run "$run"
  LAST_AVG="$(json_metric "$DIR/results/$run/summary.json" http_req_duration avg)" || die 1 "$run 평균 응답을 읽지 못함"
  rate="$(json_metric "$DIR/results/$run/summary.json" http_reqs rate)" || rate="?"
  say "$run ($phase) 처리량=${rate}건/초 avg=${LAST_AVG}ms 소진 대기=${d}초. ${GAP}초 대기 (요청 없음)"
  sleep "$GAP"
}

# s1-plan.txt("속도 반복번호 공모주,공모주,...")에서 한 단계, 한 반복의 공모주 목록. 정확히 한 줄이 아니면 실패
plan_ipos() { # 인자: plan 파일, 속도, 반복 번호
  awk -v r="$2" -v k="$3" '$1 == r && $2 == k { n++; v = $3 } END { if (n != 1 || v == "") exit 1; print v }' "$1"
}

run_s1() { # 인자: 속도, 반복 번호, 마지막 실행이면 1
  local rate="$1" rep="$2" last="$3" run ipos first t0 t1 d drain_end drc
  run="$BATCH-S1-$rate-r$rep"
  ipos="$(plan_ipos "$PLAN" "$rate" "$rep")" || die 11 "S1 ${rate}건/초 ${rep}회차 공모주 목록 없음"
  first="${ipos%%,*}"
  ensure_token $((DUR + 300))
  say "$run 시작: ${rate}건/초 x ${DUR}초 (${rep}/${S1_REPEAT}회), 공모주 $(printf '%s\n' "$ipos" | awk -F, '{print NF}')개"
  t0="$(date +%s)"
  k6_run s1 "$first" "$run" -e "IPO_IDS=$ipos" -e "RATE=$rate" -e "DURATION=${DUR}s" -e "PRE_VUS=$PRE_VUS" -e "MAX_VUS=$MAX_VUS" -e "QUANTITY=$QUANTITY" \
    || die 8 "$run k6 실패"
  t1="$(date +%s)"
  d="$(drain "$ipos" "$DRAIN_TIMEOUT")"; drc=$?
  [ "$drc" -eq 0 ] || die 6 "$run 소진 대기 실패 (1 시간 초과, 2 조회 실패, 3 인자): $drc"
  drain_end="$(date +%s)"
  echo "$run,S1,measure,$rep,$rate,$DUR,$((rate * DUR)),$(printf '%s' "$ipos" | tr ',' ';'),$t0,$t1,$d,$drain_end" >> "$MANIFEST"
  slowq_run "$run"
  autovac_run "$run"
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
  local fails=0 run scen ipos expected i k expect runs vargs
  runs="$(manifest_runs "$MANIFEST")" || { say "후처리: manifest.csv 형식 오류"; return 1; }
  while read -r run scen ipos expected; do
    [ -n "$run" ] && [ "$run" != - ] || continue
    [ "$ipos" = - ] && ipos=""
    (cd "$DIR" && py report.py --run-id "$run") > "$DIR/results/$run/report.out" 2>&1 || { say "후처리: $run report.py 실패"; fails=$((fails + 1)); }
    # S0, S3, S4(100명 x 10회)는 요청 수가 정확히 맞아야 한다. S1은 k6가 목표 속도를 다 못 보낼 수 있어 판정(전달률, 밀린 반복)에서 본다
    # S3는 같은 requestId 재전송의 응답 subscriptionId가 같은지도 check_requests.py가 본다
    expect=""
    case "$scen" in S0|S3|S4) is_uint "$expected" && expect="--expect-requests $expected" ;; esac
    # shellcheck disable=SC2086  # expect는 비어 있거나 옵션 2개
    py "$(pypath "$DIR/verify/check_requests.py")" "$(pypath "$DIR/results/$run/requests.log")" $expect > "$DIR/results/$run/check_requests.txt" 2>&1 \
      || { say "후처리: $run 요청 분류 이상 (check_requests.txt)"; fails=$((fails + 1)); }
    # 실행 하나의 공모주를 psql 한 번으로 검사한다 (results/<run>/verify-<번호>/sql_result.txt)
    k=0; vargs=()
    for i in ${ipos//;/ }; do k=$((k + 1)); vargs+=(--ipo "$i"); done
    if [ "$k" -gt 0 ]; then
      (cd "$DIR" && bash verify.sh "${vargs[@]}" --run-id "$run" --each-dir) > "$DIR/results/$run/verify.out" 2>&1 \
        || { say "후처리: $run 정합성 SQL 실패 (verify.out)"; fails=$((fails + 1)); }
    else
      say "후처리: $run 공모주 ID 없음"; fails=$((fails + 1))
    fi
  done <<< "$runs"
  py "$(pypath "$DIR/verify/judge.py")" "$(pypath "$MANIFEST")" --p95-limit "$P95_LIMIT" --drain-limit "$DRAIN_LIMIT" --container "$PG_CONTAINER" \
     --s4-prefix "$S4_PREFIX" > "$OUT/판정.md" 2> "$OUT/판정.err" \
    || { say "후처리: judge.py 실패 (판정.err)"; fails=$((fails + 1)); }
  [ "$SLOWQ_FAILS" -eq 0 ] || { say "후처리: 쿼리 통계 조회 실패 ${SLOWQ_FAILS}번 (진행.log)"; fails=$((fails + SLOWQ_FAILS)); }
  # 실행별 autovacuum, autoanalyze (앞 기록과의 차이). 기록이 없는 실행이 있으면 실패로 센다
  py "$(pypath "$DIR/verify/autovacuum.py")" "$(pypath "$MANIFEST")" --results "$(pypath "$DIR/results")" --before "$(pypath "$OUT/autovacuum-before.csv")" \
    > "$OUT/autovacuum.md" 2> "$OUT/autovacuum.err" \
    || { say "후처리: autovacuum 정리 실패 (autovacuum.err)"; fails=$((fails + 1)); }
  [ "$AUTOVAC_FAILS" -eq 0 ] || { say "후처리: autovacuum 기록 실패 ${AUTOVAC_FAILS}번 (진행.log)"; fails=$((fails + AUTOVAC_FAILS)); }
  if [ "$STATS_INTERVAL" -gt 0 ]; then
    py "$(pypath "$DIR/verify/docker_stats.py")" "$(pypath "$OUT/docker-stats.raw")" --manifest "$(pypath "$MANIFEST")" --env "$(pypath "$OUT/env.txt")" \
      --csv "$(pypath "$OUT/docker-stats.csv")" --md "$(pypath "$OUT/자원.md")" 2> "$OUT/자원.err" \
      || { say "후처리: docker stats 정리 실패 또는 빠진 구간 있음 (자원.err)"; fails=$((fails + 1)); }
  fi
  return "$fails"
}

main() {
  local dirty need minbal accounts s1_cost warm_cost syn_cost syn_short lb_short lb_mid lb_n r n k l ids dlt0 dlt1 i rep last pp
  while [ $# -gt 0 ]; do
    case "$1" in --batch) BATCH="${2:-}"; shift 2 ;; *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;; esac
  done
  case "$BATCH" in ''|*[!A-Za-z0-9._-]*) echo "사용: $0 --batch <이름> (영문, 숫자, . _ - 만)" >&2; exit 1 ;; esac
  for v in WARM_RATE WARM_BUCKET WARM_WINDOW WARM_CONSEC WARM_MIN WARM_MAX GAP DUR S1_REPEAT S0_PRICE S1_PRICE QUANTITY PRE_VUS MAX_VUS DRAIN_TIMEOUT STATS_INTERVAL; do
    is_uint "${!v}" || { echo "${v}가 0 이상의 정수가 아님: ${!v}" >&2; exit 1; }
  done
  [ "$S1_REPEAT" -ge 1 ] || { echo "S1_REPEAT는 1 이상 (팀 규칙은 3)" >&2; exit 1; }
  # 워밍업 인자는 warmup.py와 같은 범위로 여기서 먼저 막는다 (빌드, 복원, 토큰 발급 뒤에야 드러나지 않게)
  case "$WARM_SLOPE_PCT" in ""|*[!0-9.]*|*.*.*) echo "WARM_SLOPE_PCT는 0보다 큰 숫자: $WARM_SLOPE_PCT" >&2; exit 1 ;; esac
  awk -v s="$WARM_SLOPE_PCT" 'BEGIN { exit !(s + 0 > 0) }' || { echo "WARM_SLOPE_PCT는 0보다 큰 숫자: $WARM_SLOPE_PCT" >&2; exit 1; }
  case "$WARM_SLOPE_MS" in ""|*[!0-9.]*|*.*.*|.) echo "WARM_SLOPE_MS는 0 이상의 숫자: $WARM_SLOPE_MS" >&2; exit 1 ;; esac
  [ "$WARM_BUCKET" -ge 5 ] && [ "$WARM_WINDOW" -ge 3 ] && [ "$WARM_CONSEC" -ge 1 ] && [ "$WARM_MAX" -ge "$WARM_MIN" ] \
    || { echo "워밍업 인자 범위: WARM_BUCKET>=5, WARM_WINDOW>=3, WARM_CONSEC>=1, WARM_MAX>=WARM_MIN (지금 $WARM_BUCKET, $WARM_WINDOW, $WARM_CONSEC, $WARM_MIN~$WARM_MAX)" >&2; exit 1; }
  case "$RESTORE$DB_DUMP" in 00|01|10|11) ;; *) echo "RESTORE, DB_DUMP는 0 또는 1: RESTORE=$RESTORE DB_DUMP=$DB_DUMP" >&2; exit 1 ;; esac
  parse_scenarios "$SCENARIOS" || exit 1
  parse_order "$MEASURE_ORDER" || exit 1
  case "$S4_PREFIX" in ''|*[!a-z0-9]*) echo "S4_PREFIX는 영문 소문자, 숫자: $S4_PREFIX" >&2; exit 1 ;; esac
  if [ "$HAS_S4" = 1 ] && [ $((S0_PRICE * QUANTITY)) -ne "$SYN_AMOUNT" ]; then
    echo "S4 행동 CSV는 청약 금액 ${SYN_AMOUNT}원 기준이다 (S0_PRICE x QUANTITY = $((S0_PRICE * QUANTITY))원). 금액을 바꾸려면 행동 CSV와 judge.py 기대값도 같이 바꾼다" >&2; exit 1
  fi
  # BASELINE_DIR은 실행한 위치 기준 경로로 받는다. 복원은 loadtest 폴더로 이동해서 부르므로 여기서 절대 경로로 바꾸고,
  # 스냅샷이 없으면 빌드, 배포 전에 멈춘다
  if [ -n "${BASELINE_DIR:-}" ]; then
    case "$BASELINE_DIR" in /*|[A-Za-z]:*) ;; *) BASELINE_DIR="$(pwd)/$BASELINE_DIR" ;; esac
    export BASELINE_DIR
  fi
  if [ "$RESTORE" = 1 ] && [ ! -f "${BASELINE_DIR:-$DIR/data/baseline}/ipo-baseline.dump" ]; then
    echo "기준 스냅샷이 없음: ${BASELINE_DIR:-$DIR/data/baseline}/ipo-baseline.dump (BASELINE_DIR은 실행한 위치 기준 경로. docs/LOADTEST.md 3-4절)" >&2; exit 1
  fi
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
  # 기준 상태로 되돌린다: 측정마다 같은 DB 크기, 같은 데이터(회원, 계좌, 예수금만), 빈 Kafka에서 시작한다
  if [ "$RESTORE" = 1 ]; then
    say "기준 스냅샷으로 DB, Kafka 되돌리기 (baseline.sh restore)"
    (cd "$DIR" && bash baseline.sh restore) >> "$LOG" 2>&1 || die 14 "기준 상태 복원 실패 (진행.log)"
    awk '$1 == "sha256" { print "기준 스냅샷 sha256=" $2 } $1 == "state" { sub(/^state /, ""); print "기준 스냅샷 상태 " $0 } $1 == "created_at" { print "기준 스냅샷 생성 " $2 " " $3 }'       "${BASELINE_DIR:-$DIR/data/baseline}/ipo-baseline.meta" >> "$OUT/env.txt"
  else
    say "기준 상태로 되돌리지 않음 (RESTORE=0, 정식 결과로 쓰지 않는다)"
  fi
  # shellcheck disable=SC2086  # 컨테이너 목록을 나눠서 넘긴다
  wait_healthy 600 $ALL_CONTAINERS || die 7 "컨테이너가 healthy가 아니거나 Eureka UP이 4개 미만"
  record_versions || die 5 "컨테이너 안 jar가 빌드한 jar와 다름 (env.txt). 이 커밋으로 다시 빌드, 배포한다"
  # k6 컨테이너는 게이트웨이와 같은 네트워크에 붙인다 (compose 프로젝트 이름이 폴더 이름을 따라 바뀌므로 고정하지 않는다)
  K6_NET="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' ipo-api-gateway 2>/dev/null | awk '{print $1}')"
  [ -n "$K6_NET" ] || die 13 "게이트웨이 컨테이너의 네트워크를 찾지 못함"
  echo "k6_network=$K6_NET" >> "$OUT/env.txt"
  record_limits
  say "버전 기록: $(grep -m1 "^git=" "$OUT/env.txt")"
  stats_start

  # 1. 토큰 (회원 수 확인 포함) → S3, S4 데이터 확인 → 예수금
  refresh_tokens || die 3 "토큰 재발급 실패"
  syn_short=0
  if [ "$HAS_S3" = 1 ] || [ "$HAS_S4" = 1 ]; then
    syn_short="$(synthetic_check 2>>"$LOG")" || die 4 "S3, S4 행동 CSV 또는 회원 CSV 확인 실패 (진행.log)"
    is_uint "$syn_short" || die 4 "S3, S4 확인 결과가 숫자가 아님: $syn_short"
    say "S3, S4 행동 CSV 확인 통과 (S4 예수금 부족 투자자 ${syn_short}명)"
  fi
  s1_cost=0
  if [ "$HAS_S1" = 1 ]; then
    for r in "${ST[@]}"; do s1_cost=$(( s1_cost + $(ceil_div $((r * DUR)) "$USERS") * S1_REPEAT )); done
  fi
  syn_cost=0; [ "$HAS_S3" = 1 ] && syn_cost="$SYN_RUNS"   # S3는 기본 묶음 회원이 경로 예열 1 + 측정 3번 청약 (두 번째 계좌로 확정되면 그 계좌에서 빠진다)
  warm_cost=$(( $(ceil_div $((WARM_RATE * (WARM_MAX + 60))) "$USERS") + 1 ))   # 워밍업 최대 시간 동안 회원 1명이 보내는 요청 수
  need=$(( S0_PRICE * QUANTITY * (3 + syn_cost) + S1_PRICE * QUANTITY * (s1_cost + warm_cost) ))
  minbal="$(pg_int "select coalesce(min(a.cash_balance), 0) from account.account a join account.users u on u.user_id = a.user_id where u.login_id like 'lt%'")" || die 4 "예수금 조회 실패"
  accounts="$(pg_int "select count(*) from account.account a join account.users u on u.user_id = a.user_id where u.login_id like 'lt%'")" || die 4 "계좌 수 조회 실패"
  say "회원 계좌 ${accounts}개, 계좌당 예수금 최소 ${minbal}원, 최대로 필요한 금액 ${need}원 (워밍업 최대 ${warm_cost}건 + S0 3회차 + S3 ${syn_cost}회차 + S1 최대 ${s1_cost}건)"
  [ "$accounts" -ge "$USERS" ] || die 4 "계좌 수(${accounts})가 회원 수(${USERS})보다 적음"
  [ "$minbal" -ge "$need" ] || die 4 "예수금 부족. 입금 API로 넣는다: python loadtest/seed/seed_users.py --run-id deposit-<이름> --amount <금액>"
  # S4: DB의 잔액 부족 묶음 예수금이 행동 CSV대로인지 (부족 수가 같고, 회차마다 결과가 바뀌는 애매한 금액이 없음)
  if [ "$HAS_S4" = 1 ]; then
    l="$(pg "select count(*) filter (where c < $SYN_AMOUNT), count(*) filter (where c >= $SYN_AMOUNT and c < $((SYN_AMOUNT * SYN_RUNS))), count(*) from (select sum(a.cash_balance) as c from account.users u join account.account a on a.user_id = u.user_id where u.login_id ~ '^${S4_PREFIX}[0-9]{4}\$' group by u.login_id) t")" \
      || die 4 "잔액 부족 묶음 예수금 조회 실패"
    read -r lb_short lb_mid lb_n <<< "$l"
    say "잔액 부족 묶음: 회원 ${lb_n}명, 예수금 ${SYN_AMOUNT}원 미만 ${lb_short}명 (행동 CSV ${syn_short}명), 애매한 금액 ${lb_mid}명"
    is_uint "$lb_n" && [ "$lb_n" -ge "$S4_USERS" ] || die 4 "잔액 부족 묶음 계좌가 회원 CSV(${S4_USERS}명)보다 적음: $lb_n. S3, S4 기준 스냅샷인지 확인한다 (BASELINE_DIR)"
    [ "$lb_short" = "$syn_short" ] && [ "$lb_mid" = 0 ] \
      || die 4 "잔액 부족 묶음 예수금이 행동 CSV와 다름. S4 행동 CSV로 시드한 스냅샷인지 확인한다 (seed_users.py --behavior)"
  fi

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
  slowq "$OUT/slow-queries-before.txt" || die 9 "쿼리 통계(pg_stat_statements) 조회 실패. compose.perf.yml로 띄웠는지 확인 (slow-queries-before.txt)"

  # 3. 공모주 미리 생성 (부하 중에는 만들지 않는다)
  WARM_IPOS="$(new_ipos "$BATCH-W" "$warm_cost" "$S1_PRICE")" || die 11 "워밍업 공모주 생성 실패"
  l="$(new_ipos "$BATCH-S0" 3 "$S0_PRICE")" || die 11 "S0 공모주 생성 실패"
  IFS=',' read -ra S0_IPOS <<< "$l"
  if [ "$HAS_S3" = 1 ]; then   # 경로 예열 1 + 측정 3. 회차마다 새 공모주 (같은 공모주면 이중청약 거절)
    l="$(new_ipos "$BATCH-S3" "$SYN_RUNS" "$S0_PRICE")" || die 11 "S3 공모주 생성 실패"
    IFS=',' read -ra S3_IPOS <<< "$l"
  fi
  if [ "$HAS_S4" = 1 ]; then
    l="$(new_ipos "$BATCH-S4" "$SYN_RUNS" "$S0_PRICE")" || die 11 "S4 공모주 생성 실패"
    IFS=',' read -ra S4_IPOS <<< "$l"
  fi
  : > "$PLAN"
  if [ "$HAS_S1" = 1 ]; then
    for r in "${ST[@]}"; do
      n=$(( $(ceil_div $((r * DUR)) "$USERS") + 1 ))
      for rep in $(seq 1 "$S1_REPEAT"); do   # 반복마다 새 공모주 (회원은 앞 반복의 공모주에 이미 청약했다)
        l="$(new_ipos "$BATCH-S1-$r-r$rep" "$n" "$S1_PRICE")" || die 11 "S1 공모주 생성 실패"
        echo "$r $rep $l" >> "$PLAN"
      done
    done
  fi
  say "공모주 생성: 워밍업 ${warm_cost}개(공모가 $S1_PRICE), S0 ${#S0_IPOS[@]}개, S3 ${#S3_IPOS[@]}개, S4 ${#S4_IPOS[@]}개(공모가 $S0_PRICE), S1 $(wc -l < "$PLAN" | tr -d ' ')묶음(단계 ${#ST[@]}개 x ${S1_REPEAT}회, 공모가 $S1_PRICE)"
  echo "run_id,scenario,phase,repeat,target_rate,duration_s,expected_requests,ipo_ids,k6_start,k6_end,drain_s,drain_end" > "$MANIFEST"
  # 첫 실행의 쿼리 통계에 준비 단계(공모주 생성 등)가 섞이지 않게 비운다. 이후 실행마다 slowq_run이 남기고 비운다
  slowq "$OUT/.slow-queries-reset.out" 1 || die 9 "쿼리 통계 초기화 실패 (.slow-queries-reset.out)"
  autovac "$OUT/autovacuum-before.csv" || die 9 "autovacuum 기록 실패 (pg_stat_user_tables, autovacuum-before.csv)"

  # 4. 워밍업(독립 단계, 시간 기준) → [S3, S4 경로 예열] → S0(, S3, S4 번갈아) 측정 3회
  run_warmup
  run_plan

  # 5. S1: 토큰을 새로 받고 쉰 뒤 속도 단계마다 독립 실행
  if [ "$HAS_S1" = 1 ]; then
    refresh_tokens || die 3 "S1 전 토큰 재발급 실패"
    # 재발급(로그인) 쿼리가 S1 첫 실행의 쿼리 통계에 섞이지 않게 비운다
    slowq "$OUT/.slow-queries-reset.out" 1 || { say "쿼리 통계 초기화 실패 (S1 전)"; SLOWQ_FAILS=$((SLOWQ_FAILS + 1)); }
    say "재발급 뒤 ${GAP}초 대기 (요청 없음)"; sleep "$GAP"
    # 단계마다 S1_REPEAT회를 연달아 실행한다 (단계를 섞으면 앞 단계의 영향이 섞인다). 팀 규칙: 같은 조건 최소 3회, 중앙값
    for i in $(seq 0 $((${#ST[@]} - 1))); do
      for rep in $(seq 1 "$S1_REPEAT"); do
        last=0; [ "$i" -eq $((${#ST[@]} - 1)) ] && [ "$rep" -eq "$S1_REPEAT" ] && last=1
        run_s1 "${ST[$i]}" "$rep" "$last"
      done
    done
  fi
  if dlt1="$(kafka_dlt_total)"; then say "DLT 합계(종료)=$dlt1 (증가 $((dlt1 - dlt0)))"; else say "DLT 합계(종료) 조회 실패"; fi
  stats_stop
  say "== 부하 종료. 후처리 시작"

  # 6. 후처리와 판정
  pp=0; post_process || pp=1
  # 측정이 끝난 DB를 남긴다 (다음 측정은 기준 스냅샷으로 되돌리므로 원본 행은 여기에만 남는다)
  if [ "$DB_DUMP" = 1 ]; then
    say "측정 뒤 DB 덤프 (db-after.dump)"
    (cd "$DIR" && bash baseline.sh dump "$OUT/db-after.dump") >> "$LOG" 2>&1 || { say "후처리: DB 덤프 실패 (진행.log)"; pp=1; }
  fi
  if [ "$pp" = 0 ]; then say "== 후처리 완료. 판정: $OUT/판정.md"; return 0; fi
  say "== 후처리 일부 실패 (위 로그). 부하 결과는 그대로 있다. 판정: $OUT/판정.md"
  return 10
}

# source로 불러오면(테스트) main을 실행하지 않는다
if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
