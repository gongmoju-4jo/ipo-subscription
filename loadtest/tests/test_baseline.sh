#!/usr/bin/env bash
# baseline.sh 함수 단위 테스트 (Docker, DB 없이 돈다. docker, compose는 가짜 함수로 바꾼다)
# 사용: bash loadtest/tests/test_baseline.sh
# shellcheck disable=SC2034,SC2329,SC2015
#   SC2034: BASELINE_DIR 등은 불러온 baseline.sh 함수가 쓰는 전역 변수라 여기서 값을 바꾼다
#   SC2329: docker, compose 등 가짜 함수는 baseline.sh 함수가 간접 호출한다
#   SC2015: '조건 && ok || bad'에서 ok는 항상 0을 돌려준다
set -u
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/../baseline.sh"
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); printf 'ok   %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf 'FAIL %s (%s)\n' "$1" "$2"; }
rc_of() { ( "$@" ) > /dev/null 2>&1; echo $?; }

T="$(mktemp -d)"; BASELINE_DIR="$T/bl"; DUMP="$BASELINE_DIR/ipo-baseline.dump"; META="$BASELINE_DIR/ipo-baseline.meta"
STATE_OK="users=1000 accounts=1000 cash_sum=100 margin_sum=0 ledger=2000 ipos=20 subscriptions=0 margins=0"

# ---- save: 청약이나 증거금이 있으면 저장하지 않는다 ----
state_line() { echo "users=1000 accounts=1000 cash_sum=100 margin_sum=5 ledger=2001 ipos=21 subscriptions=1 margins=1"; }
[ "$(rc_of cmd_save)" = 2 ] && [ ! -e "$DUMP" ] && ok "save: 청약이 있으면 종료 코드 2, 스냅샷을 만들지 않는다" || bad "save 거부" "rc=$(rc_of cmd_save)"
state_line() { return 1; }
[ "$(rc_of cmd_save)" = 4 ] && ok "save: DB 조회 실패 → 종료 코드 4" || bad "save 조회 실패" "rc=$(rc_of cmd_save)"

# ---- save 성공: 덤프와 meta(state, sha256) ----
state_line() { echo "$STATE_OK"; }
docker() {
  case "$1 $2" in
    "exec ipo-postgres") echo "PGDMP-fake-dump" ;;    # pg_dump
    "exec -i") cat > /dev/null; echo "; archive" ;;    # pg_restore -l
    *) return 1 ;;
  esac
}
( cmd_save ) > /dev/null 2>&1; rc=$?
[ "$rc" = 0 ] && [ -f "$DUMP" ] && grep -q "^state $STATE_OK$" "$META" && grep -q "^sha256 $(sha256_of "$DUMP")$" "$META" \
  && ok "save: 덤프와 meta(상태, sha256)를 남긴다" || bad "save 성공" "rc=$rc $(cat "$META" 2>/dev/null)"

# ---- check_snapshot: 스냅샷이 없거나 sha256이 다르면 실패 ----
[ "$(rc_of check_snapshot)" = 0 ] && ok "check_snapshot: 덤프와 meta가 맞으면 통과" || bad "check_snapshot 통과" "$(rc_of check_snapshot)"
cp "$DUMP" "$T/keep"; echo "tampered" >> "$DUMP"
[ "$(rc_of check_snapshot)" = 3 ] && ok "check_snapshot: 덤프가 바뀌면(sha256 다름) 종료 코드 3" || bad "check_snapshot sha" "$(rc_of check_snapshot)"
cp "$T/keep" "$DUMP"; mv "$META" "$META.bak"
[ "$(rc_of check_snapshot)" = 3 ] && ok "check_snapshot: meta가 없으면 종료 코드 3" || bad "check_snapshot meta" "$(rc_of check_snapshot)"
mv "$META.bak" "$META"

# ---- check: 지금 DB 상태를 기준과 비교 ----
state_line() { echo "$STATE_OK"; }
[ "$(rc_of cmd_check)" = 0 ] && ok "check: 기준과 같으면 0" || bad "check 같음" "$(rc_of cmd_check)"
state_line() { echo "${STATE_OK/subscriptions=0/subscriptions=5}"; }
[ "$(rc_of cmd_check)" = 5 ] && ok "check: 청약이 남아 있으면 종료 코드 5" || bad "check 다름" "$(rc_of cmd_check)"

# ---- kafka_volume: compose 라벨로 정확히 하나 ----
docker() {
  case "$1" in
    inspect) echo "ipo-subscription" ;;
    volume) printf '%s\r\n' "${FAKE_VOLS[@]}" ;;
  esac
}
FAKE_VOLS=(ipo-subscription_kafka-data)
[ "$(kafka_volume)" = ipo-subscription_kafka-data ] && ok "kafka_volume: 하나면 그 이름 (CR 제거)" || bad "kafka_volume 하나" "$(kafka_volume)"
FAKE_VOLS=()
[ "$(rc_of kafka_volume)" = 1 ] && ok "kafka_volume: 없으면 실패" || bad "kafka_volume 없음" "$(rc_of kafka_volume)"
FAKE_VOLS=(a_kafka-data b_kafka-data)
[ "$(rc_of kafka_volume)" = 1 ] && ok "kafka_volume: 두 개면 실패 (아무거나 지우지 않음)" || bad "kafka_volume 둘" "$(rc_of kafka_volume)"
docker() { [ "$1" = inspect ] && return 1; }
[ "$(rc_of kafka_volume)" = 1 ] && ok "kafka_volume: 프로젝트 이름을 못 읽으면 실패" || bad "kafka_volume 프로젝트" "$(rc_of kafka_volume)"

# ---- restore: 단계 순서와 복원 뒤 상태 비교 ----
CALLS="$T/calls"
compose() { echo "compose $*" >> "$CALLS"; return 0; }
pick_compose() { COMPOSE=(docker compose); }
psql_q() { echo "psql $1" >> "$CALLS"; return 0; }
kafka_volume() { echo "vol-x"; }
wait_kafka() { echo "wait_kafka" >> "$CALLS"; return 0; }
docker() { echo "docker $*" >> "$CALLS"; [ "$1" = exec ] && cat > /dev/null; return 0; }
: > "$CALLS"; state_line() { echo "${STATE_OK/ipos=20/ipos=21}"; }
( cmd_restore ) > /dev/null 2>&1; rc=$?
[ "$rc" = 0 ] && ok "restore: 공모주 수(data.sql)만 다르면 통과" || bad "restore 통과" "rc=$rc"
order="$(awk '{print $1, $2}' "$CALLS" | tr '\n' '|')"
case "$order" in
  "compose stop|psql DROP|docker exec|psql ANALYZE|psql CHECKPOINT|compose rm|docker volume|docker volume|compose up|wait_kafka |compose up|")
    ok "restore: 서비스 중지 → DB 삭제 → 복원 → ANALYZE → CHECKPOINT → Kafka 비우기 → 서비스 기동 순서" ;;
  *) bad "restore 순서" "$order" ;;
esac
grep -q 'pg_restore -U postgres -d postgres --create --exit-on-error' "$CALLS" && ok "restore: pg_restore --create --exit-on-error" || bad "restore 옵션" "$(grep pg_restore "$CALLS")"
state_line() { echo "${STATE_OK/cash_sum=100/cash_sum=90}"; }
[ "$(rc_of cmd_restore)" = 5 ] && ok "restore: 복원 뒤 예수금 합계가 다르면 종료 코드 5" || bad "restore 상태 다름" "$(rc_of cmd_restore)"
psql_q() { case "$1" in DROP*) return 1 ;; esac; return 0; }
[ "$(rc_of cmd_restore)" = 4 ] && ok "restore: DB 삭제 실패 → 종료 코드 4" || bad "restore DROP 실패" "$(rc_of cmd_restore)"
psql_q() { return 0; }; wait_kafka() { return 1; }
[ "$(rc_of cmd_restore)" = 6 ] && ok "restore: Kafka 토픽 생성 확인 실패 → 종료 코드 6" || bad "restore kafka 실패" "$(rc_of cmd_restore)"

# ---- dump: 실패하면 반쯤 쓴 파일을 남기지 않는다 ----
docker() { [ "$1 $2" = "exec ipo-postgres" ] && { echo partial; return 1; }; cat > /dev/null; return 0; }
( cmd_dump "$T/out/db.dump" ) > /dev/null 2>&1; rc=$?
[ "$rc" = 4 ] && [ ! -e "$T/out/db.dump" ] && [ ! -e "$T/out/db.dump.tmp" ] && ok "dump: pg_dump 실패 → 종료 코드 4, 파일을 남기지 않음" || bad "dump 실패" "rc=$rc $(ls "$T/out" 2>&1)"
[ "$(rc_of cmd_dump "")" = 1 ] && ok "dump: 파일 인자가 없으면 종료 코드 1" || bad "dump 인자" "$(rc_of cmd_dump "")"

# ---- ALLOW_SUBSCRIPTIONS=1 save: 확정 청약이 있는 DB (G2 대량 환불용) ----
BASELINE_DIR="$T/g2"; DUMP="$BASELINE_DIR/ipo-baseline.dump"; META="$BASELINE_DIR/ipo-baseline.meta"
STATE_SUB="users=1000 accounts=1000 cash_sum=90 margin_sum=10 ledger=3000 ipos=22 subscriptions=2000 margins=2000"
HELD_OK="unfinished=0 unpublished=0 open_due=0"
docker() {
  case "$1 $2" in
    "exec ipo-postgres") echo "PGDMP-fake-dump" ;;
    "exec -i") cat > /dev/null; echo "; archive" ;;
    *) return 1 ;;
  esac
}
state_line() { echo "$STATE_SUB"; }
held_line() { echo "$HELD_OK"; }
[ "$(rc_of cmd_save)" = 2 ] && [ ! -e "$DUMP" ] && ok "save: 옵션이 없으면 확정 청약이 있어도 지금처럼 거부" || bad "save 옵션 없음" "rc=$(rc_of cmd_save)"
ALLOW_SUBSCRIPTIONS=yes; [ "$(rc_of cmd_save)" = 2 ] && [ ! -e "$DUMP" ] && ok "save: ALLOW_SUBSCRIPTIONS가 1이 아니면 꺼진 것으로 본다" || bad "save 옵션 값" "rc=$(rc_of cmd_save)"
ALLOW_SUBSCRIPTIONS=1
( cmd_save ) > /dev/null 2>&1; rc=$?
[ "$rc" = 0 ] && grep -q "^state $STATE_SUB$" "$META" && grep -q "^allow_subscriptions $HELD_OK$" "$META" && grep -q "^sha256 $(sha256_of "$DUMP")$" "$META" \
  && ok "save(ALLOW): 조건이 모두 0이면 저장, meta에 청약 수가 든 상태와 allow 줄" || bad "save ALLOW 성공" "rc=$rc $(cat "$META" 2>/dev/null)"
rm -rf "$BASELINE_DIR"
for h in "unfinished=3 unpublished=0 open_due=0" "unfinished=0 unpublished=1 open_due=0" "unfinished=0 unpublished=0 open_due=1" ""; do
  held_line() { echo "$h"; }
  [ "$(rc_of cmd_save)" = 2 ] && [ ! -e "$DUMP" ] && ok "save(ALLOW): '${h:-빈 값}'이면 종료 코드 2, 저장하지 않음" || bad "save ALLOW 거부 $h" "rc=$(rc_of cmd_save)"
done
held_line() { return 1; }
[ "$(rc_of cmd_save)" = 4 ] && [ ! -e "$DUMP" ] && ok "save(ALLOW): 조건 조회 실패 → 종료 코드 4 (0으로 넘기지 않음)" || bad "save ALLOW 조회 실패" "rc=$(rc_of cmd_save)"
unset ALLOW_SUBSCRIPTIONS
# 옵션 없이 저장한 meta에는 allow 줄이 없다 (기존 스냅샷 meta 모양 그대로)
grep -q '^allow_subscriptions' "$T/bl/ipo-baseline.meta" && bad "기본 save meta" "allow 줄이 생김" || ok "save: 옵션 없이 저장한 meta는 예전 모양 그대로 (allow 줄 없음)"

# restore, check: 옵션 없이 meta의 청약 수와 비교한다
held_line() { echo "$HELD_OK"; }; state_line() { echo "$STATE_SUB"; }
ALLOW_SUBSCRIPTIONS=1; ( cmd_save ) > /dev/null 2>&1; unset ALLOW_SUBSCRIPTIONS
compose() { return 0; }; psql_q() { return 0; }; wait_kafka() { return 0; }; kafka_volume() { echo "vol-x"; }
docker() { [ "$1" = exec ] && cat > /dev/null; return 0; }
[ "$(rc_of cmd_restore)" = 0 ] && ok "restore: 확정 청약 스냅샷도 같은 절차로 복원, 청약 수가 같으면 통과" || bad "restore G2" "$(rc_of cmd_restore)"
state_line() { echo "${STATE_SUB/subscriptions=2000/subscriptions=1999}"; }
[ "$(rc_of cmd_restore)" = 5 ] && ok "restore: 복원 뒤 청약 수가 meta와 다르면 종료 코드 5" || bad "restore G2 청약 수" "$(rc_of cmd_restore)"
[ "$(rc_of cmd_check)" = 5 ] && ok "check: 청약 수가 meta와 다르면 종료 코드 5" || bad "check G2" "$(rc_of cmd_check)"

echo; echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" -eq 0 ]
