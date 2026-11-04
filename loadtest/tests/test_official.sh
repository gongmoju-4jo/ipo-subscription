#!/usr/bin/env bash
# official.sh 함수 단위 테스트 (Docker, DB 없이 돈다. 외부 명령은 가짜 함수로 바꾼다)
# 사용: bash loadtest/tests/test_official.sh   (인자를 주면 official.sh 경로, consumer-groups 출력 파일 순서)
#   fixtures/consumer-groups.txt는 kafka-consumer-groups.sh --describe --all-groups의 실제 출력이다
# shellcheck disable=SC2034,SC2329,SC2015
#   SC2034: LOG, GAP, TOKEN_* 등은 불러온 official.sh 함수가 쓰는 전역 변수라 여기서 값을 바꾼다
#   SC2329: docker, pg_int 등 가짜 함수는 official.sh 함수가 간접 호출한다
#   SC2015: '조건 && ok || bad'에서 ok는 항상 0을 돌려준다
set -u
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1090,SC1091
source "${1:-$HERE/../official.sh}"
REAL_CG="${2:-$HERE/fixtures/consumer-groups.txt}"
LOG="$(mktemp)"; PASS=0; FAIL=0
ok()   { PASS=$((PASS + 1)); printf 'ok   %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf 'FAIL %s (%s)\n' "$1" "$2"; }
expect_out() { # 이름, 기대 rc, 기대 출력, 명령...
  local name="$1" erc="$2" eout="$3" out rc; shift 3
  out="$("$@" 2>/dev/null)"; rc=$?
  if [ "$rc" = "$erc" ] && [ "$out" = "$eout" ]; then ok "$name"; else bad "$name" "rc=$rc out='$out' 기대 rc=$erc out='$eout'"; fi
}
REQ="subscription-requested subscription-margin-reserved subscription-margin-reserve-failed account-margin-reserve"
lag_of() { parse_lag "$REQ" < "$1"; }

# ---- parse_lag ----
expect_out "실제 출력 (모두 0, '-' 포함) → 0" 0 0 lag_of "$REAL_CG"
T="$(mktemp)"
sed 's/^\(account-margin-reserve margin.reserve-requested 2 *\)19198 /\119190 /' "$REAL_CG" > "$T"
expect_out "CURRENT < LOG-END 이면 차이만큼 lag (8)" 0 8 lag_of "$T"
cat > "$T" <<'EOF'
GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
subscription-requested subscription.requested 0 - 5 - c1 /h c1
subscription-margin-reserved margin.reserved 0 3 3 0 c2 /h c2
subscription-margin-reserve-failed margin.reserve-failed 0 - 0 - c3 /h c3
account-margin-reserve margin.reserve-requested 0 7 7 0 c4 /h c4
EOF
expect_out "커밋 오프셋 '-'인데 메시지 있음 → 끝 오프셋 전체가 대기 (5)" 0 5 lag_of "$T"
grep -v '^account-margin-reserve' "$T" > "$T.2"
expect_out "필수 그룹 누락 → 실패" 1 "" lag_of "$T.2"
: > "$T.3"
expect_out "빈 출력 → 실패" 1 "" lag_of "$T.3"
printf 'Error: Executing consumer group command failed due to org.apache.kafka.common.errors.TimeoutException\n' > "$T.4"
expect_out "오류 메시지만 → 실패" 1 "" lag_of "$T.4"
sed 's/^subscription-margin-reserved margin.reserved 0 3 3 0/subscription-margin-reserved margin.reserved 0 3 - -/' "$T" > "$T.5"
expect_out "LOG-END-OFFSET이 숫자가 아님 → 실패" 1 "" lag_of "$T.5"
{ echo "Consumer group 'subscription-requested' has no active members."; echo; cat "$T"; } > "$T.6"
expect_out "'no active members' 안내 줄이 있어도 데이터 행은 센다 (5)" 0 5 lag_of "$T.6"

# ---- kafka_lag: docker 실패 ----
docker() { echo "Error response from daemon: container is not running" >&2; return 1; }
expect_out "kafka_lag: docker exec 실패 → 실패 (0을 돌려주지 않음)" 1 "" kafka_lag
docker() { cat "$REAL_CG" | sed 's/$/\r/'; }
expect_out "kafka_lag: CRLF 출력도 처리 → 0" 0 0 kafka_lag
unset -f docker

# ---- parse_offsets, kafka_dlt_total ----
printf 'subscription.requested.DLT:0:0\nsubscription.requested.DLT:1:4\nsubscription.requested.DLT:2:1\n' > "$T.7"
expect_out "parse_offsets 합계 5" 0 5 parse_offsets subscription.requested.DLT < "$T.7"
expect_out "parse_offsets 다른 토픽 → 실패" 1 "" parse_offsets margin.voided.DLT < "$T.7"
expect_out "parse_offsets 빈 출력 → 실패" 1 "" parse_offsets subscription.requested.DLT < "$T.3"
topics_n="$(kafka_topics | wc -l | tr -d ' ')"
[ "$topics_n" = 10 ] && ok "kafka_topics: create-topics.sh에서 토픽 10개" || bad "kafka_topics" "${topics_n}개"
# kafka_dlt_total은 아래 "DLT 토픽을 한 번에 조회"에서 확인한다

# ---- sql_in, is_uuid, ceil_div ----
U1=6f1c3b0e-1111-4a2b-8c3d-000000000001; U2=6f1c3b0e-1111-4a2b-8c3d-000000000002
expect_out "sql_in 두 개" 0 "'$U1','$U2'" sql_in "$U1,$U2"
expect_out "sql_in 주입 시도 → 실패" 1 "" sql_in "$U1,x');drop table t;--"
expect_out "sql_in 빈 값 → 실패" 1 "" sql_in ""
expect_out "ceil_div 38970/1000 = 39" 0 39 ceil_div 38970 1000
expect_out "ceil_div 9000/1000 = 9" 0 9 ceil_div 9000 1000

# ---- drain (sleep, pg_int, kafka_lag, outbox_unpublished를 가짜로) ----
sleep() { :; }
# 가짜 함수는 $(...) 서브셸에서 불리므로 호출 횟수를 파일에 센다
CNT="$(mktemp)"; bump() { local n; n=$(( $(cat "$CNT" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$CNT"; echo "$n"; }
: > "$CNT"; pg_int() { [ "$(bump)" -ge 3 ] && echo 0 || echo 12; }; kafka_lag() { echo 0; }; outbox_unpublished() { echo 0; }
out="$(drain "$U1" 60)"; rc=$?
[ "$rc" = 0 ] && is_uint "$out" && ok "drain: PENDING이 0이 되면 성공" || bad "drain 성공" "rc=$rc out=$out"
pg_int() { echo 0; }; kafka_lag() { return 1; }
expect_out "drain: lag 조회가 연속 5번 실패 → 2 (0으로 넘기지 않음)" 2 "" drain "$U1" 60
pg_int() { echo 0; }; kafka_lag() { echo 3; }
expect_out "drain: lag이 남아 있으면 시간 초과 → 1" 1 "" drain "$U1" -1
expect_out "drain: 잘못된 공모주 ID → 3" 3 "" drain "nope" 60
: > "$CNT"; pg_int() { echo 0; }; kafka_lag() { if [ "$(bump)" -le 4 ]; then return 1; fi; echo 0; }
out="$(drain "$U1" 60)"; rc=$?
[ "$rc" = 0 ] && ok "drain: 실패 4번 뒤 회복하면 계속 진행" || bad "drain 회복" "rc=$rc"
unset -f sleep pg_int kafka_lag outbox_unpublished

# ---- main: 워밍업 인자 범위를 시작하자마자 막는다 (빌드, 복원 전에. 결과 폴더도 만들지 않음) ----
for bad_env in "WARM_SLOPE_PCT=0" "WARM_SLOPE_PCT=." "WARM_SLOPE_PCT=-1" "WARM_BUCKET=1" "WARM_WINDOW=2" "WARM_CONSEC=0" "WARM_MIN=500 WARM_MAX=100"; do
  out="$( ( WARM_SLOPE_PCT=5 WARM_BUCKET=15 WARM_WINDOW=8 WARM_CONSEC=2 WARM_MIN=180 WARM_MAX=900
            for kv in $bad_env; do eval "$kv"; done
            DIR="$(mktemp -d)"; main --batch zz-args ) 2>&1 )"; rc=$?
  [ "$rc" = 1 ] && [ -n "$out" ] && ok "main: $bad_env → 시작 전에 종료 코드 1" || bad "main 인자 $bad_env" "rc=$rc out=$out"
done
out="$( ( WARM_SLOPE_PCT=2.5; DIR="$(mktemp -d)"; mkdir() { echo STOP; exit 0; }; main --batch zz-args ) 2>&1 )"; rc=$?
[ "$rc" = 0 ] && [[ "$out" == *STOP* ]] && ok "main: WARM_SLOPE_PCT=2.5는 통과해 결과 폴더 만들기까지 간다" || bad "main 2.5" "rc=$rc out=$out"

# ---- pick_python, pypath (워밍업 판정은 verify/warmup.py, test_warmup.py) ----
pick_python && ok "pick_python: $PY" || bad "pick_python" "없음"
p="$(pypath "$REAL_CG")"; [ -n "$p" ] && "$PY" -c "import os,sys; sys.exit(0 if os.path.exists(sys.argv[1]) else 1)" "$p" && ok "pypath: Python이 읽을 수 있는 경로 ($p)" || bad "pypath" "$p"

# ---- ensure_token ----
GAP=0; TOKEN_MAX_S=3300; CALLED=0
refresh_tokens() { CALLED=$((CALLED + 1)); TOKEN_AT="$(date +%s)"; return 0; }
sleep() { :; }
TOKEN_AT="$(date +%s)"; ensure_token 300; [ "$CALLED" = 0 ] && ok "ensure_token: 여유 있으면 재발급 안 함" || bad "ensure_token 1" "$CALLED"
TOKEN_AT=$(( $(date +%s) - 3100 )); ensure_token 300; [ "$CALLED" = 1 ] && ok "ensure_token: 만료가 가까우면 재발급" || bad "ensure_token 2" "$CALLED"
refresh_tokens() { return 1; }
TOKEN_AT=$(( $(date +%s) - 3100 ))
( ensure_token 300 ) 2>/dev/null; rc=$?
[ "$rc" = 3 ] && ok "ensure_token: 재발급 실패 → 종료 코드 3" || bad "ensure_token 3" "rc=$rc"
unset -f sleep   # 아래 docker stats 테스트는 진짜 sleep이 필요하다

# ---- git (스모크에서 드러난 결함: -C 경로를 못 읽어 dirty가 0으로 통과) ----
n="$(dirty_count)"; rc=$?
[ "$rc" = 0 ] && is_uint "$n" && ok "dirty_count: 실제 저장소에서 숫자 ($n)" || bad "dirty_count 실제" "rc=$rc n=$n"
h="$(git_repo rev-parse HEAD)"; [ "${#h}" = 40 ] && ok "git_repo: MSYS_NO_PATHCONV=1에서도 HEAD 조회" || bad "git_repo" "$h"
git() { echo "fatal: not a git repository" >&2; return 128; }
expect_out "dirty_count: git 실패 → 실패 (0으로 넘기지 않음)" 1 "" dirty_count
unset -f git
REPO_SAVE="$REPO"; REPO=/nonexistent/repo
expect_out "dirty_count: 저장소 경로가 없음 → 실패" 1 "" dirty_count
REPO="$REPO_SAVE"

# ---- manifest_runs (열을 추가하자 후처리가 위치로 읽어 공모주 자리에 유지 시간이 들어간 결함) ----
M="$(mktemp)"
printf 'run_id,scenario,phase,target_rate,duration_s,expected_requests,ipo_ids,k6_start,k6_end,drain_s\nb-S0-r1,S0,measure,,,1000,%s,1,2,3\nb-S1-100,S1,measure,100,90,9000,%s;%s,1,2,3\n' "$U1" "$U1" "$U2" > "$M"
expect_out "manifest_runs: 열 이름으로 공모주, 예상 요청 수를 읽는다" 0 "b-S0-r1 S0 $U1 1000
b-S1-100 S1 $U1;$U2 9000" manifest_runs "$M"
printf 'ipo_ids,drain_s,run_id,scenario\n%s,3,b-S0-r1,S0\n' "$U1" > "$M"
expect_out "manifest_runs: 열 순서가 바뀌어도 같고, 없는 열은 '-'" 0 "b-S0-r1 S0 $U1 -" manifest_runs "$M"
printf 'run_id,scenario,expected_requests,ipo_ids\nb-S0-r1,S0,,%s\n' "$U1" > "$M"
expect_out "manifest_runs: 빈 값은 '-'라서 다음 필드가 당겨지지 않는다" 0 "b-S0-r1 S0 $U1 -" manifest_runs "$M"
printf 'run_id,scenario,phase\nb,S0,measure\n' > "$M"
expect_out "manifest_runs: ipo_ids 열이 없으면 실패" 1 "" manifest_runs "$M"
# official.sh가 쓰는 머리줄과 judge.py 테스트의 머리줄이 같은지 (두 곳이 어긋나면 판정이 열을 못 찾는다)
H_SH="$(grep -o '"run_id,scenario[^"]*"' "$DIR/official.sh" | head -1 | tr -d '"')"
H_PY="$(grep -o 'MANIFEST_HEADER = "run_id,scenario[^"\\]*' "$HERE/test_judge.py" | sed 's/^MANIFEST_HEADER = "//')"
[ -n "$H_SH" ] && [ "$H_SH" = "$H_PY" ] && ok "manifest 머리줄: official.sh와 test_judge.py가 같다" || bad "manifest 머리줄" "sh='$H_SH' py='$H_PY'"

# ---- plan_ipos (S1 반복마다 다른 공모주 묶음) ----
P="$(mktemp)"; printf '100 1 %s,%s\n100 2 %s\n200 1 %s\n' "$U1" "$U2" "$U2" "$U1" > "$P"
expect_out "plan_ipos: 단계, 반복 번호로 묶음을 찾는다" 0 "$U2" plan_ipos "$P" 100 2
expect_out "plan_ipos: 여러 공모주 묶음" 0 "$U1,$U2" plan_ipos "$P" 100 1
expect_out "plan_ipos: 없는 반복 → 실패" 1 "" plan_ipos "$P" 100 3
expect_out "plan_ipos: 반복 번호 10이 1에 걸리지 않는다" 1 "" plan_ipos "$P" 100 10
printf '100 1 %s\n100 1 %s\n' "$U1" "$U2" > "$P"
expect_out "plan_ipos: 같은 단계·반복이 두 줄이면 실패" 1 "" plan_ipos "$P" 100 1

# ---- post_process: 실제 함수를 가짜 명령으로 돌려 하위 명령에 넘어가는 인자를 본다 ----
# (CodeRabbit 지적: judge.py에 --container를 넘기지 않아 PG_CONTAINER를 바꾸면 G2를 다른 DB에서 조회)
PP="$(mktemp -d)"; CALLS="$PP/calls.log"; : > "$CALLS"
DIR_SAVE="$DIR"; OUT_SAVE="${OUT:-}"; MANIFEST_SAVE="${MANIFEST:-}"; PG_SAVE="$PG_CONTAINER"
DIR="$PP"; OUT="$PP/batch"; MANIFEST="$OUT/manifest.csv"; PG_CONTAINER=custom-pg; mkdir -p "$OUT" "$PP/results/b-S0-r1" "$PP/results/b-S1-100"
printf 'run_id,scenario,phase,target_rate,duration_s,expected_requests,ipo_ids,k6_start,k6_end,drain_s\nb-S0-r1,S0,measure,,,1000,%s,1,2,3\nb-S1-100,S1,measure,100,90,9000,%s;%s,1,2,3\n' "$U1" "$U1" "$U2" > "$MANIFEST"
py()   { echo "py $*" >> "$CALLS"; return 0; }
bash() { echo "bash $*" >> "$CALLS"; return 0; }
( post_process ) 2>/dev/null; rc=$?
unset -f py bash
[ "$rc" = 0 ] && ok "post_process: 가짜 명령으로 끝까지 돈다" || bad "post_process rc" "$rc"
grep -q -- "judge.py.*--container custom-pg" "$CALLS" && ok "post_process: judge.py에 --container를 넘긴다" || bad "post_process --container" "$(grep judge "$CALLS")"
[ "$(grep -c "verify.sh --ipo" "$CALLS")" = 2 ] && ok "post_process: 실행마다 정합성 SQL을 한 번 (실행 2개 = 2번)" || bad "post_process verify" "$(grep -c verify.sh "$CALLS")"
grep "verify.sh" "$CALLS" | grep -q -- "--ipo $U1 --ipo $U2 --run-id b-S1-100 --each-dir" && ok "post_process: 공모주 여러 개를 한 번에 넘기고 --each-dir" || bad "post_process verify 인자" "$(grep verify.sh "$CALLS")"
grep "check_requests.py" "$CALLS" | grep "b-S0-r1" | grep -q -- "--expect-requests 1000" && ok "post_process: S0 요청 분류에 예상 요청 수를 넘긴다" || bad "post_process S0 expect" "$(grep check_requests "$CALLS")"
grep "check_requests.py" "$CALLS" | grep "b-S1-100" | grep -q -- "--expect-requests" && bad "post_process S1 expect" "S1에도 넘김" || ok "post_process: S1 요청 분류에는 예상 요청 수를 넘기지 않는다"
grep "docker_stats.py" "$CALLS" | grep -q -- "--manifest" && ok "post_process: STATS_INTERVAL>0이면 docker_stats.py로 자원 표를 만든다" || bad "post_process docker_stats" "$(grep docker_stats "$CALLS")"
: > "$CALLS"; STATS_SAVE="$STATS_INTERVAL"; STATS_INTERVAL=0
py()   { echo "py $*" >> "$CALLS"; return 0; }
bash() { echo "bash $*" >> "$CALLS"; return 0; }
( post_process ) 2>/dev/null
unset -f py bash
grep -q "docker_stats.py" "$CALLS" && bad "post_process STATS_INTERVAL=0" "docker_stats.py를 불렀다" || ok "post_process: STATS_INTERVAL=0이면 docker_stats.py를 부르지 않는다"
py()   { [[ "$*" == *docker_stats.py* ]] && return 1; return 0; }
bash() { return 0; }
STATS_INTERVAL=5; ( post_process ) 2>/dev/null; rc=$?
unset -f py bash
[ "$rc" = 1 ] && ok "post_process: docker_stats.py 실패는 후처리 실패로 센다 (rc 1)" || bad "post_process docker_stats 실패" "rc=$rc"
STATS_INTERVAL="$STATS_SAVE"
DIR="$DIR_SAVE"; OUT="$OUT_SAVE"; MANIFEST="$MANIFEST_SAVE"; PG_CONTAINER="$PG_SAVE"

# ---- docker stats 기록 (stats_loop, stats_start, stats_stop) ----
ST="$(mktemp -d)"; OUT_SAVE="${OUT:-}"; OUT="$ST"
docker() { [ "$1" = stats ] && printf '{"Name":"ipo-kafka","CPUPerc":"12.5%%"}\r\n{"Name":"k6-b-S1-100-r1","CPUPerc":"80%%"}\n'; }
STATS_INTERVAL=1; stats_start 2>/dev/null; sleep 3; stats_stop 2>/dev/null
n="$(grep -c . "$ST/docker-stats.raw" 2>/dev/null || echo 0)"
[ "$n" -ge 4 ] && ok "stats: 1초 간격 3초 동안 샘플 2번 이상 (컨테이너 2개 x 2 이상 = ${n}줄)" || bad "stats 줄 수" "$n"
awk -F'\t' '$1 !~ /^[0-9]+$/ || $2 !~ /^\{/ { bad = 1 } END { exit bad }' "$ST/docker-stats.raw" && ok "stats: 모든 줄이 '<epoch>\\t<json>' 형식" || bad "stats 형식" "$(head -2 "$ST/docker-stats.raw")"
grep -q $'\r' "$ST/docker-stats.raw" && bad "stats CR" "CR이 남음" || ok "stats: docker 출력의 CR을 지운다 (Windows)"
[ -z "$STATS_PID" ] && ok "stats_stop: 기록 프로세스를 멈추고 PID를 비운다" || bad "stats_stop PID" "$STATS_PID"
m1="$(grep -c . "$ST/docker-stats.raw")"; sleep 2; m2="$(grep -c . "$ST/docker-stats.raw")"
[ "$m1" = "$m2" ] && ok "stats_stop: 멈춘 뒤에는 더 쓰지 않는다" || bad "stats_stop 뒤 기록" "$m1 → $m2"
: > "$ST/docker-stats.raw"
docker() { echo "Cannot connect to the Docker daemon" >&2; return 1; }
stats_start 2>/dev/null; sleep 2; stats_stop 2>/dev/null
grep -q $'\tERROR\tCannot connect' "$ST/docker-stats.raw" && ok "stats: docker 조회 실패는 ERROR 줄로 남기고 계속한다" || bad "stats ERROR" "$(cat "$ST/docker-stats.raw")"
rm -f "$ST/docker-stats.raw"; STATS_INTERVAL=0; stats_start 2>/dev/null
[ -z "$STATS_PID" ] && [ ! -e "$ST/docker-stats.raw" ] && ok "stats: STATS_INTERVAL=0이면 기록하지 않는다" || bad "stats 0" "pid=$STATS_PID"
STATS_INTERVAL=1; stats_start 2>/dev/null; P_ST="$STATS_PID"; cleanup 2>/dev/null
kill -0 "$P_ST" 2>/dev/null && { bad "cleanup stats" "기록 프로세스가 살아 있음"; kill "$P_ST"; } || ok "cleanup: 중단될 때 docker stats 기록도 멈춘다"
unset -f docker

# ---- record_limits ----
: > "$ST/env.txt"
docker() {
  case "$*" in
    *NanoCpus*ipo-kafka) echo "1000000000 1073741824" ;;
    *NanoCpus*ipo-postgres) return 1 ;;
    *NanoCpus*) echo "500000000 536870912" ;;
    *Config.Env*ipo-kafka) printf 'PATH=/x\nKAFKA_HEAP_OPTS=-Xms512m -Xmx512m\n' ;;
    *Config.Env*) printf 'JAVA_TOOL_OPTIONS=-Xmx512m\n' ;;
  esac
}
record_limits
unset -f docker
grep -q '^자원 ipo-kafka cpu=1 mem=1073741824 KAFKA_HEAP_OPTS=-Xms512m -Xmx512m' "$ST/env.txt" && ok "record_limits: CPU 코어, 메모리 바이트, 힙 옵션을 남긴다" || bad "record_limits kafka" "$(grep kafka "$ST/env.txt")"
grep -q '^자원 ipo-eureka-server cpu=0.5 ' "$ST/env.txt" && ok "record_limits: 0.5코어" || bad "record_limits 0.5" "$(grep eureka "$ST/env.txt")"
grep -q '^자원 ipo-postgres 조회 실패' "$ST/env.txt" && ok "record_limits: 조회 실패를 숨기지 않는다" || bad "record_limits 실패" "$(grep postgres "$ST/env.txt")"
grep -q '^자원 k6 cpu=2 mem=1g' "$ST/env.txt" && ok "record_limits: k6 상한도 남긴다" || bad "record_limits k6" "$(grep k6 "$ST/env.txt")"

# ---- k6_run: 실행마다 k6 컨테이너 이름 k6-<run_id> ----
DIR_SAVE="$DIR"; DIR="$ST"; mkdir -p "$ST/results/b-S1-100-r1"
( # 가짜 함수가 밖으로 새지 않게 서브셸에서
  bash() { echo "K6_NAME=${K6_NAME:-} K6_CPUS=${K6_CPUS:-} K6_MEMORY=${K6_MEMORY:-}" > "$ST/k6env"; echo '{}' > "$ST/results/b-S1-100-r1/summary.json"; return 0; }
  json_metric() { echo 5; }
  k6_run s1 x b-S1-100-r1
) 2>/dev/null
grep -q '^K6_NAME=k6-b-S1-100-r1 K6_CPUS=2 K6_MEMORY=1g$' "$ST/k6env" && ok "k6_run: K6_NAME=k6-<run_id>, 상한 CPU 2, 메모리 1g를 넘긴다" || bad "k6_run 환경" "$(cat "$ST/k6env" 2>/dev/null)"
# ---- run_warmup: run-k6.sh에 --ipo로 첫 공모주를 넘기고, k6가 바로 죽으면 기다리지 않고 멈춘다 ----
OUT="$ST/wbatch"; mkdir -p "$OUT"; BATCH=b; WARM_IPOS="$U1,$U2"; MANIFEST="$OUT/manifest.csv"
( bash() { echo "$*" > "$ST/warm-args"; return 1; }
  sleep() { command sleep 1; }; ensure_token() { :; }; py() { return 3; }   # 1초 뒤 확인할 때 가짜 k6는 이미 끝나 있다
  run_warmup ) > /dev/null 2>&1; rc=$?
grep -q -- "run-k6.sh s1 --ipo $U1 --run-id b-W -e IPO_IDS=$U1,$U2 -e RATE=" "$ST/warm-args" && ok "run_warmup: run-k6.sh에 --ipo 첫 공모주, IPO_IDS 전체를 넘긴다" || bad "run_warmup 인자" "$(cat "$ST/warm-args" 2>/dev/null)"
[ "$rc" = 8 ] && ok "run_warmup: k6가 시작하자마자 끝나면 종료 코드 8 (판정을 기다리지 않음)" || bad "run_warmup k6 즉시 종료" "rc=$rc"
DIR="$DIR_SAVE"; OUT="$OUT_SAVE"

# ---- slowq_run: 실행마다 쿼리 통계를 남기고 비운다. 실패는 숨기지 않고 센다 ----
DIR_SAVE="$DIR"; DIR="$ST"; mkdir -p "$ST/results/b-S0-r1" "$ST/sql"; echo "-- sql" > "$ST/sql/slow-queries.sql"; : > "$ST/calls"
docker() { echo "docker $*" >> "$ST/calls"; cat > /dev/null; echo "== 총 실행 시간 상위 10개"; return 0; }
SLOWQ_FAILS=0; slowq_run b-S0-r1
grep -q '총 실행 시간' "$ST/results/b-S0-r1/slow-queries.txt" && ok "slowq_run: 통계를 slow-queries.txt에 남긴다" || bad "slowq_run 파일" "$(ls "$ST/results/b-S0-r1")"
[ "$(grep -c 'psql' "$ST/calls")" = 2 ] && grep -q -- '-v reset=1' "$ST/calls" && ok "slowq_run: 조회한 뒤 reset=1로 비운다 (2번 호출)" || bad "slowq_run 호출" "$(cat "$ST/calls")"
[ "$(sed -n 1p "$ST/calls" | grep -c reset)" = 0 ] && ok "slowq_run: 비우기 전에 먼저 조회한다" || bad "slowq_run 순서" "$(cat "$ST/calls")"
[ ! -e "$ST/results/b-S0-r1/.slow-queries-reset.out" ] && [ "$SLOWQ_FAILS" = 0 ] && ok "slowq_run: 성공하면 실패 수 0, 임시 파일 정리" || bad "slowq_run 성공" "fails=$SLOWQ_FAILS"
docker() { cat > /dev/null; echo 'ERROR:  relation "pg_stat_statements" does not exist'; return 3; }
slowq_run b-S0-r1 2>/dev/null; rc=$?
[ "$rc" = 0 ] && [ "$SLOWQ_FAILS" = 1 ] && ok "slowq_run: 실패하면 측정은 이어 가고(rc 0) 실패 수를 센다" || bad "slowq_run 실패" "rc=$rc fails=$SLOWQ_FAILS"
unset -f docker
# ---- kafka_dlt_total: DLT 토픽을 한 번에 조회 ----
TOPS="$(kafka_topics)"; NT="$(printf '%s\n' "$TOPS" | grep -c .)"
docker() { echo "$*" > "$ST/dlt-args"; printf '%s\n' "$TOPS" | awk '{ print $1 ".DLT:0:1"; print $1 ".DLT:1:2\r" }'; }
expect_out "kafka_dlt_total: 토픽 ${NT}개 x (1+2) = $((NT * 3))" 0 "$((NT * 3))" kafka_dlt_total
[ "$(grep -o 'kafka-get-offsets' "$ST/dlt-args" | wc -l | tr -d ' ')" = 1 ] && grep -q 'subscription\\\.requested' "$ST/dlt-args" && ok "kafka_dlt_total: 한 번 호출, 점은 정규식에서 이스케이프" || bad "kafka_dlt_total 인자" "$(cat "$ST/dlt-args")"
docker() { printf '%s\n' "$TOPS" | sed 1d | awk '{ print $1 ".DLT:0:1" }'; }
expect_out "kafka_dlt_total: 한 토픽의 행이 없으면 실패 (0으로 넘기지 않음)" 1 "" kafka_dlt_total
docker() { echo "Error: timeout" >&2; return 1; }
expect_out "kafka_dlt_total: 조회 실패 → 실패" 1 "" kafka_dlt_total
unset -f docker

# ---- new_ipos: 공모주 여러 개를 한 번에 ----
DIR_SAVE="$DIR"; DIR="$ST"
bash() { echo "$*" > "$ST/ipo-args"; printf '%s\r\n%s\r\n' "$U1" "$U2"; }
expect_out "new_ipos: UUID 2개 → 쉼표로 이음" 0 "$U1,$U2" new_ipos b-S1-100-r1 2 1000
grep -q -- "--name b-S1-100-r1 --count 2 --price 1000" "$ST/ipo-args" && ok "new_ipos: 이름 접두사, 개수, 공모가를 넘긴다" || bad "new_ipos 인자" "$(cat "$ST/ipo-args")"
expect_out "new_ipos: 개수가 다르면 실패" 1 "" new_ipos b 3 1000
bash() { printf '%s\nINSERT 0 1\n' "$U1"; }
expect_out "new_ipos: UUID가 아닌 줄이 있으면 실패" 1 "" new_ipos b 2 1000
bash() { return 1; }
expect_out "new_ipos: 스크립트 실패 → 실패" 1 "" new_ipos b 1 1000
unset -f bash
DIR="$DIR_SAVE"

# ---- verify.sh --each-dir: psql 출력을 공모주별 파일로 나눈다 (가짜 docker 실행 파일) ----
VB="$(mktemp -d)"; mkdir -p "$VB/bin" "$VB/lt/sql" "$VB/lt/results"
cp "$HERE/../verify.sh" "$VB/lt/"; cp "$HERE/../sql/verify.sql" "$VB/lt/sql/"
cat > "$VB/bin/docker" <<'EOF'
#!/usr/bin/env bash
# 표준입력의 "\echo @@VERIFY ..." 줄과 skip_global 설정을 그대로 흉내 낸다
skip=0
while IFS= read -r l; do
  case "$l" in
    '\echo @@VERIFY '*) echo "${l#\\echo }" ;;
    '\set skip_global 1') skip=1 ;;
    '\unset skip_global') skip=0 ;;
    "\\echo '== 2."*) echo "== 2. 전체"; [ "$skip" = 1 ] && echo "(건너뜀)" || echo "(0 rows)" ;;
  esac
done
[ -n "${FAKE_DROP:-}" ] && exit 0
exit "${FAKE_RC:-0}"
EOF
chmod +x "$VB/bin/docker"
PATH="$VB/bin:$PATH" "$BASH" "$VB/lt/verify.sh" --ipo "$U1" --ipo "$U2" --run-id r1 --each-dir > /dev/null 2>&1; rc=$?
[ "$rc" = 0 ] && [ -f "$VB/lt/results/r1/verify-1/sql_result.txt" ] && [ -f "$VB/lt/results/r1/verify-2/sql_result.txt" ] && ok "verify.sh --each-dir: 공모주마다 verify-<번호>/sql_result.txt" || bad "verify.sh each" "rc=$rc $(ls -R "$VB/lt/results" 2>&1 | tr '\n' ' ')"
head -1 "$VB/lt/results/r1/verify-2/sql_result.txt" | grep -q "^# run_id=r1/verify-2 ipo=$U2 " && ok "verify.sh --each-dir: 머리줄에 run_id, 공모주 ID" || bad "verify.sh 머리줄" "$(head -1 "$VB/lt/results/r1/verify-2/sql_result.txt")"
grep -q '(0 rows)' "$VB/lt/results/r1/verify-1/sql_result.txt" && grep -q '(건너뜀)' "$VB/lt/results/r1/verify-2/sql_result.txt" && ok "verify.sh --each-dir: 전체 계좌 검사는 첫 공모주만, 나머지는 건너뜀" || bad "verify.sh skip" "$(cat "$VB/lt/results/r1/verify-2/sql_result.txt")"
PATH="$VB/bin:$PATH" FAKE_RC=3 "$BASH" "$VB/lt/verify.sh" --ipo "$U1" --ipo "$U2" --run-id r2 --each-dir > /dev/null 2>&1; rc=$?
[ "$rc" = 1 ] && ok "verify.sh --each-dir: psql 실패 → 종료 코드 1" || bad "verify.sh psql 실패" "rc=$rc"
[ -f "$VB/lt/results/r2/verify-1/sql_result.txt" ] && [ -f "$VB/lt/results/r2/.verify-all.out" ] && ok "verify.sh --each-dir: psql이 실패해도 그때까지의 결과는 나눠 남기고 전체 출력도 남긴다" || bad "verify.sh 부분 결과" "$(ls -a "$VB/lt/results/r2" 2>&1 | tr '\n' ' ')"
# ---- new_ipo.sh 인자 검사 (docker를 부르기 전에 거부) ----
printf '#!/usr/bin/env bash\necho called >> "%s/docker-called"\n' "$VB" > "$VB/bin/docker"
for bad_count in 0 abc -1; do
  PATH="$VB/bin:$PATH" "$BASH" "$HERE/../seed/new_ipo.sh" --name x --count "$bad_count" > /dev/null 2>&1; rc=$?
  [ "$rc" = 1 ] && ok "new_ipo.sh: --count $bad_count 거부" || bad "new_ipo.sh --count $bad_count" "rc=$rc"
done
[ ! -e "$VB/docker-called" ] && ok "new_ipo.sh: 잘못된 --count면 DB를 건드리지 않는다" || bad "new_ipo.sh docker 호출" "불렸음"
PATH="$VB/bin:$PATH" "$BASH" "$VB/lt/verify.sh" --ipo "$U1" --ipo "$U2" --run-id r3 > /dev/null 2>&1; rc=$?
[ "$rc" = 1 ] && ok "verify.sh: 공모주 여러 개인데 --each-dir 없으면 거부" || bad "verify.sh 여러 개" "rc=$rc"
cat > "$VB/bin/docker" <<'EOF'
#!/usr/bin/env bash
while IFS= read -r l; do case "$l" in '\echo @@VERIFY 1 '*) echo "${l#\\echo }" ;; esac; done
EOF
PATH="$VB/bin:$PATH" "$BASH" "$VB/lt/verify.sh" --ipo "$U1" --ipo "$U2" --run-id r4 --each-dir > /dev/null 2>&1; rc=$?
[ "$rc" = 1 ] && ok "verify.sh --each-dir: 나눠진 결과 수가 공모주 수와 다르면 실패" || bad "verify.sh 개수" "rc=$rc"

DIR="$PP"; OUT="$PP/batch"; MANIFEST="$OUT/manifest.csv"; SLOWQ_FAILS=2
py()   { return 0; }
bash() { return 0; }
( post_process ) 2>/dev/null; rc=$?
unset -f py bash
[ "$rc" = 2 ] && ok "post_process: 쿼리 통계 실패 수를 후처리 실패로 센다 (rc 2)" || bad "post_process SLOWQ" "rc=$rc"
SLOWQ_FAILS=0; DIR="$DIR_SAVE"; OUT="$OUT_SAVE"; MANIFEST="$MANIFEST_SAVE"

echo; echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" -eq 0 ]
