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
[ "$topics_n" = 10 ] && ok "kafka_topics: create-topics.sh에서 토픽 10개" || bad "kafka_topics" "$topics_n개"
topic_arg() { local a="$*"; a="${a##*--topic }"; printf '%s' "${a%% *}"; }
docker() { local t; t="$(topic_arg "$@")"; printf '%s:0:1\r\n%s:1:0\r\n' "$t" "$t"; }
expect_out "kafka_dlt_total: 10개 토픽 x 1 = 10" 0 10 kafka_dlt_total
docker() { local t; t="$(topic_arg "$@")"; if [ "$t" = margin.voided.DLT ]; then echo "Error: topic not found"; return 1; fi; printf '%s:0:1\n' "$t"; }
expect_out "kafka_dlt_total: 10개 중 margin.voided.DLT만 조회 실패 → 실패" 1 "" kafka_dlt_total
docker() { local t; t="$(topic_arg "$@")"; [ "$t" = margin.voided.DLT ] && t=wrong.topic; printf '%s:0:1\n' "$t"; }
expect_out "kafka_dlt_total: 명령은 성공했는데 그 토픽 행이 없음 → 실패" 1 "" kafka_dlt_total
unset -f docker

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

# ---- stable_check, pick_python, pypath ----
pick_python && ok "pick_python: $PY" || bad "pick_python" "없음"
stable_check 20 100 100 100 && ok "stable_check 같은 값 → 안정" || bad "stable_check 1" ""
stable_check 20 400 100 200 100 && bad "stable_check 2" "안정으로 판정" || ok "stable_check 흔들림 → 불안정"
stable_check 20 100 100 && bad "stable_check 3" "2개로 판정" || ok "stable_check 2회차 → 불안정"
stable_check 20 300 101 100 99 && ok "stable_check 마지막 3개만 본다" || bad "stable_check 4" ""
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
[ "$(grep -c "verify.sh --ipo" "$CALLS")" = 3 ] && ok "post_process: 공모주마다 정합성 SQL (1 + 2 = 3번)" || bad "post_process verify" "$(grep -c verify.sh "$CALLS")"
grep "check_requests.py" "$CALLS" | grep "b-S0-r1" | grep -q -- "--expect-requests 1000" && ok "post_process: S0 요청 분류에 예상 요청 수를 넘긴다" || bad "post_process S0 expect" "$(grep check_requests "$CALLS")"
grep "check_requests.py" "$CALLS" | grep "b-S1-100" | grep -q -- "--expect-requests" && bad "post_process S1 expect" "S1에도 넘김" || ok "post_process: S1 요청 분류에는 예상 요청 수를 넘기지 않는다"
DIR="$DIR_SAVE"; OUT="$OUT_SAVE"; MANIFEST="$MANIFEST_SAVE"; PG_CONTAINER="$PG_SAVE"

echo; echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" -eq 0 ]
