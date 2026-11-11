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
# main 인자 검사용 임시 loadtest 폴더 (기준 스냅샷 파일이 있어야 빌드 전 확인을 통과한다)
fake_dir() { local d; d="$(mktemp -d)"; mkdir -p "$d/data/baseline"; : > "$d/data/baseline/ipo-baseline.dump"; echo "$d"; }

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
[ "$topics_n" = 9 ] && ok "kafka_topics: create-topics.sh에서 토픽 9개" || bad "kafka_topics" "${topics_n}개"
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
for bad_env in "WARM_SLOPE_PCT=0" "WARM_SLOPE_PCT=." "WARM_SLOPE_PCT=-1" "WARM_SLOPE_MS=-1" "WARM_SLOPE_MS=." "WARM_SLOPE_MS=abc" "WARM_BUCKET=1" "WARM_WINDOW=2" "WARM_CONSEC=0" "WARM_MIN=500 WARM_MAX=100"; do
  out="$( ( WARM_SLOPE_PCT=5 WARM_SLOPE_MS=1 WARM_BUCKET=15 WARM_WINDOW=8 WARM_CONSEC=2 WARM_MIN=180 WARM_MAX=900
            for kv in $bad_env; do eval "$kv"; done
            DIR="$(mktemp -d)"; main --batch zz-args ) 2>&1 )"; rc=$?
  [ "$rc" = 1 ] && [ -n "$out" ] && ok "main: $bad_env → 시작 전에 종료 코드 1" || bad "main 인자 $bad_env" "rc=$rc out=$out"
done
out="$( ( WARM_SLOPE_PCT=2.5; DIR="$(fake_dir)"; mkdir() { echo STOP; exit 0; }; main --batch zz-args ) 2>&1 )"; rc=$?
[ "$rc" = 0 ] && [[ "$out" == *STOP* ]] && ok "main: WARM_SLOPE_PCT=2.5는 통과해 결과 폴더 만들기까지 간다" || bad "main 2.5" "rc=$rc out=$out"
out="$( ( WARM_SLOPE_MS=0; DIR="$(fake_dir)"; mkdir() { echo STOP; exit 0; }; main --batch zz-args ) 2>&1 )"; rc=$?
[ "$rc" = 0 ] && [[ "$out" == *STOP* ]] && ok "main: WARM_SLOPE_MS=0(절대 기울기 끔)은 통과해 결과 폴더 만들기까지 간다" || bad "main slope-ms 0" "rc=$rc out=$out"

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
[ "$(grep -o 'kafka-get-offsets' "$ST/dlt-args" | wc -l | tr -d ' ')" = 1 ] && grep -q 'margin\\\.reserve-requested' "$ST/dlt-args" && ok "kafka_dlt_total: 한 번 호출, 점은 정규식에서 이스케이프" || bad "kafka_dlt_total 인자" "$(cat "$ST/dlt-args")"
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

# ---- SCENARIOS (S3, S4 Synthetic Data) ----
# 위 테스트들이 가짜 py를 만들고 unset -f py로 지워서 official.sh의 py 함수도 없어진다.
# Windows에서는 같은 이름의 Python 실행기(py.exe)가 대신 불려 가려지고 macOS, Linux에서는 command not found가 된다. 원래 정의로 되돌린다
py() { "$PY" -X utf8 "$@"; }
sc() { parse_scenarios "$1" 2>/dev/null && echo "$HAS_S1$HAS_S3$HAS_S4"; }
expect_out "parse_scenarios: 기본 S0,S1" 0 100 sc "S0,S1"
expect_out "parse_scenarios: S0,S3,S4" 0 011 sc "S0,S3,S4"
expect_out "parse_scenarios: 순서가 달라도 같다 (S4,S0)" 0 001 sc "S4,S0"
expect_out "parse_scenarios: S0만" 0 000 sc "S0"
for v in "S1" "S3,S4" "" "S0,S2" "S0,S0" "s0" "S0,S3,S3"; do
  expect_out "parse_scenarios: '$v' → 실패" 1 "" sc "$v"
done
BATCH=b
HAS_S1=0; HAS_S3=0; HAS_S4=0
expect_out "plan_runs: S0만이면 지금과 같은 S0 3회" 0 "S0 b-S0-r1 measure
S0 b-S0-r2 measure
S0 b-S0-r3 measure" plan_runs
HAS_S3=1; HAS_S4=1
expect_out "plan_runs: S3, S4 경로 예열 뒤 S0, S3, S4를 번갈아 3회" 0 "S3 b-S3-W warmup
S4 b-S4-W warmup
S0 b-S0-r1 measure
S3 b-S3-r1 measure
S4 b-S4-r1 measure
S0 b-S0-r2 measure
S3 b-S3-r2 measure
S4 b-S4-r2 measure
S0 b-S0-r3 measure
S3 b-S3-r3 measure
S4 b-S4-r3 measure" plan_runs
HAS_S3=0
expect_out "plan_runs: S4만 추가" 0 "S4 b-S4-W warmup
S0 b-S0-r1 measure
S4 b-S4-r1 measure
S0 b-S0-r2 measure
S4 b-S4-r2 measure
S0 b-S0-r3 measure
S4 b-S4-r3 measure" plan_runs

# MEASURE_ORDER: 번갈아 도는 순서를 세션마다 돌려 쓴다 (워밍업 직후 첫 측정이 늘 같은 시나리오가 되지 않게)
po() { parse_order "$1" 2>/dev/null && echo "$ORDER_LIST"; }
plan_ord() { MEASURE_ORDER="$1" plan_runs; }
HAS_S3=1; HAS_S4=1
expect_out "parse_order: 비우면 S0 S3 S4" 0 "S0 S3 S4" po ""
expect_out "parse_order: S3,S4,S0" 0 "S3 S4 S0" po "S3,S4,S0"
expect_out "parse_order: S4,S0,S3" 0 "S4 S0 S3" po "S4,S0,S3"
for v in "S0,S3" "S0,S3,S4,S0" "S0,S3,S3,S4" "S0,S3,S4,S1" "S0,S3,S5" "s0,s3,s4"; do
  expect_out "parse_order: '$v' → 실패 (실행하는 시나리오를 한 번씩 정확히)" 1 "" po "$v"
done
HAS_S3=0
expect_out "parse_order: S4만 있으면 S4,S0" 0 "S4 S0" po "S4,S0"
expect_out "parse_order: 실행하지 않는 S3는 쓸 수 없다" 1 "" po "S0,S3,S4"
HAS_S3=1; BATCH=b
expect_out "plan_runs: MEASURE_ORDER=S3,S4,S0 → 경로 예열은 그대로, 측정은 S3, S4, S0 순서로 3회" 0 "S3 b-S3-W warmup
S4 b-S4-W warmup
S3 b-S3-r1 measure
S4 b-S4-r1 measure
S0 b-S0-r1 measure
S3 b-S3-r2 measure
S4 b-S4-r2 measure
S0 b-S0-r2 measure
S3 b-S3-r3 measure
S4 b-S4-r3 measure
S0 b-S0-r3 measure" plan_ord "S3,S4,S0"
HAS_S4=1
first_measure() { MEASURE_ORDER="$1" plan_runs | sed -n 3p; }
expect_out "plan_runs: MEASURE_ORDER=S4,S0,S3의 첫 측정은 S4" 0 "S4 b-S4-r1 measure" first_measure "S4,S0,S3"
expect_out "plan_runs: MEASURE_ORDER를 비우면 첫 측정은 S0" 0 "S0 b-S0-r1 measure" first_measure ""
HAS_S3=0; HAS_S4=0

# run_plan: 반복 안에서 표준입력을 읽는 명령(docker exec -i)이 있어도 실행이 빠지지 않는다
RP="$(mktemp)"
( HAS_S3=1; HAS_S4=1   # 가짜 run_one이 밖으로 새지 않게 서브셸에서
  run_one() { echo "$*" >> "$RP"; cat > /dev/null; }
  run_plan <<< "표준입력에 남은 줄
또 한 줄" )
[ "$(grep -c . "$RP")" = 11 ] && [ "$(head -1 "$RP")" = "S3 b-S3-W warmup" ] && [ "$(tail -1 "$RP")" = "S4 b-S4-r3 measure" ] \
  && ok "run_plan: 표준입력을 먹는 명령이 있어도 11개 실행 모두 (fd 3으로 순서를 읽음)" || bad "run_plan" "$(tr '\n' '|' < "$RP")"
HAS_S1=0; HAS_S3=0; HAS_S4=0

# main: 잘못된 SCENARIOS, S4_PREFIX, S4 청약 금액은 빌드, 복원 전에 막는다 (결과 폴더도 만들지 않음)
for bad_env in "SCENARIOS=S1" "SCENARIOS=S0,S9" "S4_PREFIX=LB" "S4_PREFIX=" "SCENARIOS=S0,S4 S0_PRICE=10000" "SCENARIOS=S0,S3,S4 MEASURE_ORDER=S0,S3" "SCENARIOS=S0,S3,S4 MEASURE_ORDER=S3,S4,S0,S1" "SCENARIOS=S0,S3 MEASURE_ORDER=S4,S0,S3"; do
  out="$( ( for kv in $bad_env; do eval "$kv"; done
            DIR="$(fake_dir)"; mkdir() { echo STOP; exit 0; }; main --batch zz-args ) 2>&1 )"; rc=$?
  [ "$rc" = 1 ] && [[ "$out" != *STOP* ]] && ok "main: $bad_env → 시작 전에 종료 코드 1" || bad "main $bad_env" "rc=$rc out=$out"
done
out="$( ( SCENARIOS=S0,S3,S4; DIR="$(fake_dir)"; mkdir() { echo STOP; exit 0; }; main --batch zz-args ) 2>&1 )"; rc=$?
[ "$rc" = 0 ] && [[ "$out" == *STOP* ]] && ok "main: SCENARIOS=S0,S3,S4는 통과해 결과 폴더 만들기까지 간다" || bad "main S0,S3,S4" "rc=$rc out=$out"
out="$( ( SCENARIOS=S0,S3 S0_PRICE=10000; DIR="$(fake_dir)"; mkdir() { echo STOP; exit 0; }; main --batch zz-args ) 2>&1 )"; rc=$?
[ "$rc" = 0 ] && [[ "$out" == *STOP* ]] && ok "main: S3만이면 청약 금액 제한이 없다 (S4 입금액만 금액에 묶임)" || bad "main S3 금액" "rc=$rc out=$out"
# BASELINE_DIR: 실행한 위치 기준 상대 경로를 절대 경로로 바꾼다 (복원은 loadtest 폴더로 이동해서 부르므로 상대 경로면 못 찾는다)
BL="$(mktemp -d)"; mkdir -p "$BL/snap/s34"; : > "$BL/snap/s34/ipo-baseline.dump"
out="$( ( cd "$BL" && BASELINE_DIR=snap/s34 && DIR="$(fake_dir)" && mkdir() { echo "STOP $BASELINE_DIR"; exit 0; } && main --batch zz-args ) 2>&1 )"; rc=$?
[ "$rc" = 0 ] && [ "$out" = "STOP $BL/snap/s34" ] && ok "main: 상대 경로 BASELINE_DIR을 실행 위치 기준 절대 경로로 바꾼다" || bad "main BASELINE_DIR 상대 경로" "rc=$rc out=$out"
out="$( ( cd "$BL" && BASELINE_DIR=snap/none && DIR="$(fake_dir)" && mkdir() { echo STOP; exit 0; } && main --batch zz-args ) 2>&1 )"; rc=$?
[ "$rc" = 1 ] && [[ "$out" == *"기준 스냅샷이 없음: $BL/snap/none/ipo-baseline.dump"* ]] && ok "main: 기준 스냅샷이 없으면 빌드 전에 종료 코드 1" || bad "main 스냅샷 없음" "rc=$rc out=$out"
out="$( ( RESTORE=0; DIR="$(mktemp -d)"; mkdir() { echo STOP; exit 0; }; main --batch zz-args ) 2>&1 )"; rc=$?
[ "$rc" = 0 ] && [[ "$out" == *STOP* ]] && ok "main: RESTORE=0이면 스냅샷이 없어도 시작한다" || bad "main RESTORE=0" "rc=$rc out=$out"

# synthetic_check: 커밋한 행동 CSV + 가짜 회원 CSV
# (앞 테스트들이 DIR을 임시 폴더로 바꿔 두므로 테스트 파일 위치 기준의 실제 loadtest 폴더를 쓴다)
SY="$(mktemp -d)"; DIR_SAVE="$DIR"; DIR="$(cd "$HERE/.." && pwd)"
"$PY" -X utf8 - "$(pypath "$DIR/synthetic/s3-behavior.csv")" "$(pypath "$SY")" <<'PYEOF'
import csv, os, sys
second = {int(r["vuser"]) for r in csv.DictReader(open(sys.argv[1], encoding="utf-8")) if r["action"] == "SECOND_ACCOUNT"}
def write(name, prefix, acc2):
    with open(os.path.join(sys.argv[2], name), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f); w.writerow(["loginId", "userId", "accountId", "accountId2", "token"])
        for i in range(1000):
            w.writerow([f"{prefix}{i:04d}", f"u{i}", f"a{i}", f"b{i}" if acc2(i) else "", "t"])
write("lt.csv", "lt", lambda i: i in second)
write("lt-no2.csv", "lt", lambda i: i in second and i != min(second))
write("lb.csv", "lb", lambda i: False)
PYEOF
[ -s "$SY/lt.csv" ] && [ -s "$SY/lb.csv" ] && ok "synthetic_check 준비: 가짜 회원 CSV 생성" || bad "synthetic_check 준비" "가짜 회원 CSV가 없다 (아래 실패 케이스가 엉뚱한 이유로 통과할 수 있음)"
USERS_CSV_SAVE="$USERS_CSV"; S4_CSV_SAVE="$S4_CSV"
syc() { USERS_CSV="$SY/$1"; S4_CSV="$SY/$2"; HAS_S3="$3"; HAS_S4="$4"; synthetic_check 2>"$SY/err"; }
# 실패 케이스는 종료 코드와 함께 실패 이유(메시지)까지 본다 (다른 이유로 실패해도 통과로 보이지 않게)
syc_fails() { # 이름, 메시지에 있어야 할 말, syc 인자...
  local name="$1" want="$2" out rc; shift 2
  out="$(syc "$@")"; rc=$?
  if [ "$rc" = 1 ] && [ -z "$out" ] && grep -q -- "$want" "$SY/err"; then ok "$name"; else bad "$name" "rc=$rc out='$out' err=$(tail -1 "$SY/err")"; fi
}
expect_out "synthetic_check: S3, S4 모두 맞으면 S4 예수금 부족 수(97)" 0 97 syc lt.csv lb.csv 1 1
expect_out "synthetic_check: S3만이면 0" 0 0 syc lt.csv lb.csv 1 0
syc_fails "synthetic_check: 두 번째 계좌가 없는 S3 투자자 → 실패" "두 번째 계좌(accountId2)가 없는 S3 투자자 1명" lt-no2.csv lb.csv 1 0
syc_fails "synthetic_check: S4 회원 CSV가 기본 묶음 → 실패" "잔액 부족 묶음(lb)이 아니다" lt.csv lt.csv 0 1
syc_fails "synthetic_check: S4 회원 CSV가 없음 → 실패" "회원 CSV가 없다" lt.csv none.csv 0 1
S0_SAVE="$S0_PRICE"; S0_PRICE=50000   # 청약 500,000원이면 입금 1,000,000원 회원도 4회 중간에 부족해진다
syc_fails "synthetic_check: 청약 금액이 달라 회차마다 결과가 바뀌는 입금액 → 실패" "회차마다 결과가 달라짐" lt.csv lb.csv 0 1
S0_PRICE="$S0_SAVE"; USERS_CSV="$USERS_CSV_SAVE"; S4_CSV="$S4_CSV_SAVE"; HAS_S3=0; HAS_S4=0; DIR="$DIR_SAVE"

# run_one: 시나리오마다 스크립트, 회원 CSV, 공모주를 고르고 manifest에 같은 형식으로 남긴다
RO="$(mktemp -d)"; : > "$RO/manifest.csv"
( MANIFEST="$RO/manifest.csv"   # 가짜 함수가 밖으로 새지 않게 서브셸에서
  ensure_token() { :; }; slowq_run() { :; }; sleep() { :; }; json_metric() { echo 5; }; drain() { echo 2; }
  k6_run() { echo "$*" >> "$RO/k6"; }
  S0_IPOS=("$U1"); S0_NEXT=0; S4_IPOS=("$U2" "$U1"); S4_NEXT=0; S3_IPOS=(); S3_NEXT=0
  run_one S4 b-S4-W warmup; run_one S0 b-S0-r1 measure; run_one S4 b-S4-r1 measure
  ( run_one S3 b-S3-r1 measure ); echo "$?" > "$RO/rc-s3"
  ( run_one S9 b-x measure ); echo "$?" > "$RO/rc-s9" ) 2>/dev/null
grep -q "^s4 $U2 b-S4-W --users data/users-s4.csv -e S4_PREFIX=lb -e VUS=100 -e ITERATIONS=10 " "$RO/k6" && ok "run_one: S4는 s4.js와 잔액 부족 묶음 회원 CSV" || bad "run_one S4 k6" "$(head -1 "$RO/k6")"
grep -q "^s0 $U1 b-S0-r1 -e VUS=100" "$RO/k6" && ok "run_one: S0은 기본 회원 CSV (--users 없음)" || bad "run_one S0 k6" "$(sed -n 2p "$RO/k6")"
grep -q "^s4 $U1 b-S4-r1 " "$RO/k6" && ok "run_one: S4 회차마다 다음 공모주" || bad "run_one S4 다음 공모주" "$(sed -n 3p "$RO/k6")"
grep -q "^b-S4-W,S4,warmup,,,,1000,$U2,[0-9]*,[0-9]*,2,[0-9]*$" "$RO/manifest.csv" && grep -q "^b-S0-r1,S0,measure,,,,1000,$U1," "$RO/manifest.csv" \
  && ok "run_one: manifest 행 (시나리오, 구간, 예상 요청 1000, 공모주, 소진, drain_end)" || bad "run_one manifest" "$(tr '\n' '|' < "$RO/manifest.csv")"
[ "$(cat "$RO/rc-s3")" = 11 ] && ok "run_one: 공모주가 모자라면 종료 코드 11" || bad "run_one 공모주 부족" "rc=$(cat "$RO/rc-s3")"
[ "$(cat "$RO/rc-s9")" = 1 ] && ok "run_one: 모르는 시나리오 → 종료 코드 1" || bad "run_one 모르는 시나리오" "rc=$(cat "$RO/rc-s9")"

# post_process: S3, S4도 요청 수를 확인하고, judge.py에 잔액 부족 묶음 접두사를 넘긴다
PP2="$(mktemp -d)"; CALLS="$PP2/calls.log"; : > "$CALLS"
DIR_SAVE="$DIR"; OUT_SAVE="${OUT:-}"; MANIFEST_SAVE="${MANIFEST:-}"
DIR="$PP2"; OUT="$PP2/batch"; MANIFEST="$OUT/manifest.csv"; mkdir -p "$OUT" "$PP2/results/b-S3-r1" "$PP2/results/b-S4-r1"
printf 'run_id,scenario,phase,repeat,target_rate,duration_s,expected_requests,ipo_ids,k6_start,k6_end,drain_s,drain_end\nb-S3-r1,S3,measure,,,,1000,%s,1,2,3,6\nb-S4-r1,S4,measure,,,,1000,%s,1,2,3,6\n' "$U1" "$U2" > "$MANIFEST"
py()   { echo "py $*" >> "$CALLS"; return 0; }
bash() { echo "bash $*" >> "$CALLS"; return 0; }
S4_PREFIX=zz; ( post_process ) 2>/dev/null; rc=$?; S4_PREFIX=lb
unset -f py bash
[ "$rc" = 0 ] && ok "post_process: S3, S4 실행으로 끝까지 돈다" || bad "post_process S3 rc" "$rc"
[ "$(grep check_requests.py "$CALLS" | grep -c -- "--expect-requests 1000")" = 2 ] && ok "post_process: S3, S4 요청 분류에도 예상 요청 수(1000)를 넘긴다" || bad "post_process S3 expect" "$(grep check_requests "$CALLS")"
grep -q -- "judge.py.*--s4-prefix zz" "$CALLS" && ok "post_process: judge.py에 S4_PREFIX를 넘긴다" || bad "post_process s4-prefix" "$(grep judge "$CALLS")"
grep "autovacuum.py" "$CALLS" | grep -q -- "manifest.csv --results .*results --before .*autovacuum-before.csv" \
  && ok "post_process: autovacuum.py로 실행별 autovacuum을 정리한다 (manifest, results, 첫 실행 직전 기록)" || bad "post_process autovacuum" "$(grep autovacuum "$CALLS")"
# autovacuum.py가 실패하거나(기록 없는 실행) 실행 중 기록이 실패했으면 후처리 실패로 센다
py()   { [[ "$*" == *autovacuum.py* ]] && return 1; return 0; }
bash() { return 0; }
( post_process ) 2>/dev/null; rc=$?
[ "$rc" = 1 ] && ok "post_process: autovacuum.py 실패는 후처리 실패 (rc 1)" || bad "post_process autovacuum 실패" "rc=$rc"
py()   { return 0; }
AUTOVAC_FAILS=2; ( post_process ) 2>/dev/null; rc=$?; AUTOVAC_FAILS=0
[ "$rc" = 2 ] && ok "post_process: 실행 중 autovacuum 기록 실패 수를 후처리 실패로 센다 (rc 2)" || bad "post_process AUTOVAC_FAILS" "rc=$rc"
unset -f py bash
py() { "$PY" -X utf8 "$@"; }   # unset -f py가 official.sh의 py도 지우므로 되돌린다
DIR="$DIR_SAVE"; OUT="$OUT_SAVE"; MANIFEST="$MANIFEST_SAVE"

# autovac: pg_stat_user_tables 누적 횟수 기록. 조회 실패, 빈 결과는 실패 (0으로 넘기지 않음), CRLF 출력은 정리
AV="$(mktemp -d)"
( docker() { printf 'account.margin,10,2,1,3,1793887501,\r\nsubscription.outbox,5,0,0,1,,1793887816\r\n'; }   # 표준입력을 읽지 않는다 (autovac은 넘기는 입력이 없다)
  autovac "$AV/ok.csv"; echo "$?" > "$AV/rc-ok"
  docker() { echo "connection refused" >&2; return 2; }
  autovac "$AV/fail.csv"; echo "$?" > "$AV/rc-fail"
  docker() { printf '\r\n'; }
  autovac "$AV/empty.csv"; echo "$?" > "$AV/rc-empty" ) 2>/dev/null
[ "$(cat "$AV/rc-ok")" = 0 ] && [ "$(head -1 "$AV/ok.csv")" = "table,live_tup,dead_tup,autovacuum_count,autoanalyze_count,last_autovacuum,last_autoanalyze" ] \
  && [ "$(sed -n 2p "$AV/ok.csv")" = "account.margin,10,2,1,3,1793887501," ] && [ "$(tr -dc '\r' < "$AV/ok.csv" | wc -c | tr -d ' ')" = 0 ] \
  && ok "autovac: 머리줄 + 테이블별 행, CRLF 정리" || bad "autovac 정상" "rc=$(cat "$AV/rc-ok") $(cat -A "$AV/ok.csv" 2>&1 | head -3)"
[ "$(cat "$AV/rc-fail")" = 1 ] && [ ! -e "$AV/fail.csv" ] && ok "autovac: docker 실패 → 실패, 파일 안 만듦" || bad "autovac 실패" "rc=$(cat "$AV/rc-fail")"
[ "$(cat "$AV/rc-empty")" = 1 ] && [ ! -e "$AV/empty.csv" ] && ok "autovac: 빈 결과 → 실패" || bad "autovac 빈 결과" "rc=$(cat "$AV/rc-empty")"
AF="$(mktemp -d)"
( DIR="$AF"; mkdir -p "$AF/results/b-S0-r1"; autovac() { return 1; }; autovac_run b-S0-r1; echo "$AUTOVAC_FAILS" > "$AF/n" ) 2>/dev/null
[ "$(cat "$AF/n")" = 1 ] && ok "autovac_run: 기록 실패는 측정을 멈추지 않고 실패 수만 센다" || bad "autovac_run" "$(cat "$AF/n")"

# ---- Mac 기본 bash 3.2: 변수 바로 뒤에 ASCII가 아닌 문자(한글 등)가 붙으면 이름의 일부로 읽어 set -u에서 멈춘다 → 항상 ${VAR} ----
na_scan() { # 인자: 저장소 루트. 변수 바로 뒤에 비ASCII 문자가 붙은 줄을 "파일:줄"로 출력
  "$PY" -X utf8 - "$(pypath "$1")" <<'PYEOF'
import glob, os, re, sys
root = sys.argv[1]
pat = re.compile(r"\$[A-Za-z_][A-Za-z0-9_]*(?=[^\x00-\x7f])")
hits = []
for pattern in ("loadtest/**/*.sh", "deploy/**/*.sh", "docker/**/*.sh"):
    for f in glob.glob(os.path.join(root, pattern), recursive=True):
        for n, line in enumerate(open(f, encoding="utf-8"), 1):
            if pat.search(line):
                hits.append(f"{os.path.relpath(f, root)}:{n}".replace(os.sep, "/"))
print(" ".join(sorted(hits)))
PYEOF
}
# 검사기가 실제로 잡는지 먼저 확인한다 (못 잡는 검사기는 "위반 0"으로 항상 통과한다)
NAT="$(mktemp -d)"; mkdir -p "$NAT/loadtest/sub" "$NAT/deploy"
D='$'   # 이 파일 자체가 검사에 걸리지 않게 '$'를 따로 붙여 위반 줄을 만든다
printf 'echo "회원 %sUSERS명"\necho "회원 %s{USERS}명"\n' "$D" "$D" > "$NAT/loadtest/sub/a.sh"
printf 'echo "%sv가 틀림" "%s{v}가 맞음" "%sv → 화살표" "%sv 공백"\n' "$D" "$D" "$D" "$D" > "$NAT/deploy/b.sh"
expect_out "bash 3.2 검사기: 변수 바로 뒤 한글, 화살표는 잡고 중괄호로 감싼 변수, 공백 뒤는 안 잡는다" 0 "deploy/b.sh:1 loadtest/sub/a.sh:1" na_scan "$NAT"
NA="$(na_scan "$REPO")"; rc=$?
[ "$rc" = 0 ] && [ -z "$NA" ] && ok "bash 3.2: 셸 스크립트에 변수 바로 뒤 비ASCII 문자 없음 (\${VAR}로 감쌈)" || bad "bash 3.2 변수 경계" "rc=$rc $NA"

# ---- 필수 Consumer 그룹 기본값: official.sh와 s2.sh가 같고, 접수 Outbox로 없어진 접수 그룹(subscription-requested)이 없다 ----
# (없는 그룹이 있으면 Kafka를 비우고 시작한 측정이 필수 그룹 확인에서 종료 코드 9로 멈춘다)
req_default() { grep -o 'REQUIRED_GROUPS="${REQUIRED_GROUPS:-[^}]*}' "$1" | head -1 | sed 's/.*:-//; s/}$//'; }
RG_OFF="$(req_default "$HERE/../official.sh")"; RG_S2="$(req_default "$HERE/../s2.sh")"
[ -n "$RG_OFF" ] && [ "$RG_OFF" = "$RG_S2" ] && ok "필수 그룹 기본값: official.sh와 s2.sh가 같음" || bad "필수 그룹 기본값" "official.sh='$RG_OFF' s2.sh='$RG_S2'"
case " $RG_OFF " in *" subscription-requested "*) bad "필수 그룹 기본값" "official.sh에 subscription-requested가 남아 있음" ;; *) ok "필수 그룹 기본값: subscription-requested 없음" ;; esac

echo; echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" -eq 0 ]
