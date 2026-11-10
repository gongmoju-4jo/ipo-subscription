#!/usr/bin/env bash
# s2.sh 함수 단위 테스트 (Docker, DB 없이 돈다. 외부 명령은 가짜 함수로 바꾼다)
# 사용: bash loadtest/tests/test_s2.sh   (인자를 주면 s2.sh 경로, consumer-groups 출력 파일 순서)
#   fixtures/consumer-groups.txt는 kafka-consumer-groups.sh --describe --all-groups의 실제 출력이다
# shellcheck disable=SC2034,SC2329,SC2015
#   SC2034: POLL, ALLOW_DIRTY 등은 불러온 s2.sh 함수가 쓰는 전역 변수라 여기서 값을 바꾼다
#   SC2329: docker, pg 등 가짜 함수는 s2.sh 함수가 간접 호출한다
#   SC2015: '조건 && ok || bad'에서 ok는 항상 0을 돌려준다
set -u
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1090,SC1091
source "${1:-$HERE/../s2.sh}"
REAL_CG="${2:-$HERE/fixtures/consumer-groups.txt}"
PASS=0; FAIL=0; POLL=0.1
ok()   { PASS=$((PASS + 1)); printf 'ok   %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf 'FAIL %s (%s)\n' "$1" "$2"; }
expect_out() { # 이름, 기대 rc, 기대 출력, 명령...
  local name="$1" erc="$2" eout="$3" out rc; shift 3
  out="$("$@" 2>/dev/null)"; rc=$?
  if [ "$rc" = "$erc" ] && [ "$out" = "$eout" ]; then ok "$name"; else bad "$name" "rc=$rc out='$out' (기대 rc=$erc out='$eout')"; fi
}

# ---------- kafka_lag: 모든 그룹 랙 합, 조회 실패를 0으로 넘기지 않음 ----------
docker() { sed 's/$/\r/' "$REAL_CG"; }   # Windows Docker 출력처럼 CRLF
expect_out "kafka_lag: 실제 출력(모두 처리됨, 커밋 오프셋 '-'이고 메시지 없는 파티션 포함)은 0" 0 0 kafka_lag
CG_LAG="$(mktemp)"
cat > "$CG_LAG" <<'EOF'
GROUP                  TOPIC                  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID
subscription-requested subscription.requested 0          10              15              5    c-1          /h    c-1
subscription-requested subscription.requested 1          -               3               -    c-2          /h    c-2

Consumer group 'account-margin-reserve' has no active members.

GROUP                  TOPIC                    PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID
account-margin-reserve margin.reserve-requested 0          7               7               0    -            -     -
EOF
docker() { cat "$CG_LAG"; }
REQ_ALL="$REQUIRED_GROUPS"; REQUIRED_GROUPS="subscription-requested account-margin-reserve"   # 이 가짜 출력에 있는 그룹만
expect_out "kafka_lag: 밀린 메시지 5 + 커밋 전 메시지 3 = 8 (멤버 없는 그룹도 셈)" 0 8 kafka_lag
REQUIRED_GROUPS="$REQ_ALL"
docker() { grep -v '^subscription-margin-reserved ' "$REAL_CG"; }
expect_out "kafka_lag: 필수 그룹(subscription-margin-reserved)이 없으면 다른 그룹 랙이 0이어도 실패" 1 "" kafka_lag
out="$(kafka_lag 2>&1 >/dev/null)"
[[ "$out" == *"필수 Consumer 그룹 없음: subscription-margin-reserved"* ]] && ok "kafka_lag: 없는 필수 그룹 이름을 남김" || bad "kafka_lag: 없는 그룹 이름" "stderr='$out'"
docker() { echo "Error response from daemon: container is not running" >&2; return 1; }
expect_out "kafka_lag: docker 실패면 0이 아니라 실패" 1 "" kafka_lag
docker() { echo "GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG"; }
expect_out "kafka_lag: 읽을 행이 없으면 실패" 1 "" kafka_lag

# ---------- drain: 조회 실패나 대기가 남으면 제한 시간 뒤 실패 ----------
PG_P=0; PG_O=0; PG_FAIL=""
pg() {
  [ -n "$PG_FAIL" ] && return 1
  case "$1" in
    *"status in ('PENDING','COMPENSATING')"*) echo "$PG_P" ;;
    *outbox*) echo "$PG_O" ;;
  esac
}
REQUIRED_GROUPS="subscription-requested account-margin-reserve"   # 아래 가짜 출력에 있는 그룹만 (필수 그룹 확인은 위 kafka_lag 테스트)
docker() { cat "$CG_LAG" | sed 's/ 15 / 10 /; s/ 3  / 0  /'; }   # 랙 0으로 바꾼 출력
# 걸린 초를 출력한다. 실행 중 초가 바뀌면 0이 아니라 1일 수 있어 값은 숫자인지만 본다
out="$(drain "a,b" 1 2>/dev/null)"; rc=$?
[ "$rc" = 0 ] && [[ "$out" =~ ^[0-9]+$ ]] && ok "drain: 진행 중 0, 미발행 0, 랙 0이면 성공 (걸린 초 출력)" || bad "drain: 성공" "rc=$rc out='$out'"
PG_P=2; expect_out "drain: 진행 중 청약이 남으면 제한 시간 뒤 실패" 1 "" drain "a" 0
PG_P=0; PG_O=1; expect_out "drain: 미발행 Outbox가 남으면 실패" 1 "" drain "a" 0
PG_O=0; PG_FAIL=1; expect_out "drain: DB 조회 실패를 0으로 보지 않음" 1 "" drain "a" 0
PG_FAIL=""; docker() { return 1; }
expect_out "drain: 랙 조회 실패를 0으로 보지 않음" 1 "" drain "a" 0
docker() { cat "$CG_LAG"; }
expect_out "drain: 랙이 남으면 실패" 1 "" drain "a" 0

# ---------- wait_closed: CLOSED, ALLOCATED만 마감. 조회 실패(빈 값)를 마감으로 보지 않음 ----------
PG_ST=""
pg() { [ "$PG_ST" = FAIL ] && return 1; echo "$PG_ST"; }
PG_ST=CLOSED;    expect_out "wait_closed: CLOSED면 성공" 0 CLOSED wait_closed ipo 0
PG_ST=ALLOCATED; expect_out "wait_closed: ALLOCATED도 마감으로 봄" 0 ALLOCATED wait_closed ipo 0
PG_ST=OPEN;      expect_out "wait_closed: OPEN이 계속되면 제한 시간 뒤 실패" 1 OPEN wait_closed ipo 0
PG_ST=FAIL;      expect_out "wait_closed: 조회 실패를 마감으로 보지 않음" 1 "조회 실패" wait_closed ipo 0
PG_ST="";        expect_out "wait_closed: 빈 값을 마감으로 보지 않음" 1 "조회 실패" wait_closed ipo 0

# ---------- check_dirty: 커밋하지 않은 변경이 있으면 시작하지 않음 ----------
GIT_OUT=""; GIT_FAIL=""
git_repo() { [ -n "$GIT_FAIL" ] && return 128; printf '%s' "$GIT_OUT"; }
check_dirty 2>/dev/null; rc=$?
[ "$rc" = 0 ] && [ "$DIRTY_N" = 0 ] && ok "check_dirty: 변경이 없으면 통과" || bad "check_dirty: 변경 없음" "rc=$rc DIRTY_N=$DIRTY_N"
GIT_OUT=" M loadtest/s2.sh
?? loadtest/new.sql"
check_dirty 2>/dev/null; rc=$?
[ "$rc" = 5 ] && [ "$DIRTY_N" = 2 ] && ok "check_dirty: 변경 2건이면 종료 코드 5" || bad "check_dirty: 변경 있음" "rc=$rc DIRTY_N=$DIRTY_N"
ALLOW_DIRTY=1 check_dirty 2>/dev/null; rc=$?
[ "$rc" = 0 ] && [ "$DIRTY_N" = 2 ] && ok "check_dirty: ALLOW_DIRTY=1이면 통과하고 변경 수는 남김" || bad "check_dirty: ALLOW_DIRTY" "rc=$rc DIRTY_N=$DIRTY_N"
GIT_FAIL=1; check_dirty 2>/dev/null; rc=$?
[ "$rc" = 5 ] && ok "check_dirty: git 확인 실패도 시작하지 않음" || bad "check_dirty: git 실패" "rc=$rc"

# ---------- grace_env: 청약 서비스 컨테이너의 마감 유예 환경변수 ----------
docker() { printf 'PATH=/usr/bin\r\nIPO_CLOSE_GRACEPERIOD=PT1S\r\nJAVA_TOOL_OPTIONS=-Xmx512m\r\n'; }
expect_out "grace_env: 덮어쓴 값 PT1S" 0 PT1S grace_env
docker() { printf 'PATH=/usr/bin\nJAVA_TOOL_OPTIONS=-Xmx512m\n'; }
expect_out "grace_env: 덮어쓰기가 없으면 빈 값" 1 "" grace_env

# ---------- new_ipos: 공모주 ID를 쉼표로 잇고, 개수가 다르면 실패 ----------
new_ipo_cmd() { printf 'id-1\r\nid-2\r\nid-3\r\n'; }
expect_out "new_ipos: 3개를 쉼표로 이음 (CRLF 제거)" 0 "id-1,id-2,id-3" new_ipos run 3
expect_out "new_ipos: 요청한 개수와 다르면 실패" 1 "" new_ipos run 4
new_ipo_cmd() { echo "psql: error" >&2; return 1; }
expect_out "new_ipos: 생성 실패" 1 "" new_ipos run 1

# ---------- plan: 대상 구간 ms, 전체 요청 수, 배경 공모주 수. 반올림은 s2.js(Math.round)와 같아야 한다 ----------
pick_python || { echo "Python 3.8 이상이 필요합니다" >&2; exit 1; }
RATE=274 PRE=60 POST=40 TARGET_SEC=0; expect_out "plan: 기본 ① 274건/초 (지난 측정 env.txt와 같음)" 0 "3650 28400 28" plan 1000
POST=0; expect_out "plan: 기본 ② POST=0 (지난 측정 env.txt와 같음)" 0 "3650 17440 17" plan 1000
# 275 x 103.5 = 28462.5: Math.round는 28463, Python round()는 28462 (짝수 쪽)
RATE=275 POST=40 TARGET_SEC=3.5; expect_out "plan: .5는 Math.round처럼 올림 (28462.5 → 28463)" 0 "3500 28463 28" plan 1000
RATE=274 PRE=60 POST=40 TARGET_SEC=0

# ---------- check_jars: 서비스마다 로컬 jar와 컨테이너 jar sha256이 같아야 통과 ----------
REPO_REAL="$REPO"; REPO="$(mktemp -d)"
for m in api-gateway account-service subscription-service allocation-service eureka-server; do
  mkdir -p "$REPO/$m/build/libs"; printf 'jar-%s' "$m" > "$REPO/$m/build/libs/$m-0.0.1-SNAPSHOT.jar"
done
SHA_SUB="$(sha256_file "$REPO/subscription-service/build/libs/subscription-service-0.0.1-SNAPSHOT.jar")"
JAR_DIFF=""   # 이 컨테이너만 다른 sha를 돌려준다
docker() { # docker exec <컨테이너> sha256sum /app/app.jar → 같은 이름 모듈의 로컬 jar sha (CRLF)
  local c="$2" m
  [ "$c" = "$JAR_DIFF" ] && { printf 'deadbeef  /app/app.jar\r\n'; return 0; }
  m="${c#ipo-}"; printf '%s  /app/app.jar\r\n' "$(sha256_file "$REPO/$m/build/libs/$m-0.0.1-SNAPSHOT.jar")"
}
out="$(check_jars)"; rc=$?
[ "$rc" = 0 ] && [ "$(printf '%s\n' "$out" | grep -c '(컨테이너와 같음)')" = 5 ] && [[ "$out" == *"jar subscription-service sha256=$SHA_SUB"* ]] \
  && ok "check_jars: 5개 모두 같으면 통과하고 sha256을 줄마다 남김" || bad "check_jars: 같음" "rc=$rc out='$out'"
JAR_DIFF=ipo-subscription-service
out="$(check_jars)"; rc=$?
[ "$rc" = 1 ] && [[ "$out" == *"jar subscription-service 로컬=$SHA_SUB 컨테이너=deadbeef (다름)"* ]] \
  && ok "check_jars: 하나라도 다르면 실패" || bad "check_jars: 다름" "rc=$rc out='$out'"
JAR_DIFF=""; docker() { [ "$2" = ipo-allocation-service ] && return 1; printf 'x  /app/app.jar\n'; }
out="$(check_jars)"; rc=$?
[ "$rc" = 1 ] && [[ "$out" == *"jar allocation-service 로컬="*"컨테이너=조회 실패 (다름)"* ]] \
  && ok "check_jars: 컨테이너 조회 실패를 같음으로 보지 않음" || bad "check_jars: 조회 실패" "rc=$rc out='$out'"
printf 'plain' > "$REPO/api-gateway/build/libs/api-gateway-0.0.1-SNAPSHOT-plain.jar"
rm -f "$REPO/eureka-server/build/libs/"*.jar
out="$(check_jars)"; rc=$?
[ "$rc" = 1 ] && [[ "$out" == *"jar api-gateway: 로컬 jar가 1개가 아님"* ]] && [[ "$out" == *"jar eureka-server: 로컬 jar가 1개가 아님"* ]] \
  && ok "check_jars: 로컬 jar가 2개이거나 없으면 실패" || bad "check_jars: 로컬 jar 개수" "rc=$rc out='$out'"
rm -rf "$REPO"; REPO="$REPO_REAL"

# ---------- main: 판정에 계획한 전체 요청 수를 넘김 (main은 Docker가 필요해 호출 줄만 본다) ----------
S2_SRC="${1:-$HERE/../s2.sh}"
grep -q 'verify/s2_judge.py.*' "$S2_SRC" && grep -A2 'verify/s2_judge.py' "$S2_SRC" | grep -q -- '--expect-requests "$total"' \
  && ok "main: 판정에 --expect-requests \"\$total\"을 넘김" || bad "main: 판정 인자" "s2_judge.py 호출에 --expect-requests \"\$total\"이 없음"
grep -A2 'bash run-k6.sh s2 ' "$S2_SRC" | grep -q -- '-e "PRE_VUS=$PRE_VUS" -e "MAX_VUS=$MAX_VUS"' \
  && ok "main: k6 s2에 PRE_VUS, MAX_VUS를 넘김" || bad "main: k6 가상 사용자" "run-k6.sh s2 호출에 PRE_VUS, MAX_VUS가 없음"

rm -f "$CG_LAG"
echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" = 0 ]
