#!/usr/bin/env bash
# seed_users_sql.sh 단위 테스트 (Docker, DB 없이 돈다. docker는 가짜 함수로 바꾼다)
# 사용: bash loadtest/tests/test_seed_users_sql.sh
# shellcheck disable=SC2034,SC2329,SC2015
set -u
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/../seed/seed_users_sql.sh"
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); printf 'ok   %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf 'FAIL %s (%s)\n' "$1" "$2"; }
rc_of() { ( "$@" ) > /dev/null 2>&1; echo $?; }
T="$(mktemp -d)"

# ---- login_id: seed_users.py의 f"{prefix}{idx:04d}"와 같다 ----
[ "$(login_id lt 0)" = lt0000 ] && [ "$(login_id lt 999)" = lt0999 ] && [ "$(login_id lt 12345)" = lt12345 ] \
  && ok "login_id: 4자리 0 채움, 1만 이상은 그대로" || bad "login_id" "$(login_id lt 0) $(login_id lt 12345)"

# ---- parse_args: 잘못된 입력은 종료 코드 1 ----
for a in "--users x" "--users 10 --start 10" "--start -1" "--prefix LT" "--prefix" "--deposits 0" "--deposits 1,,2" "--deposits ,1" "--deposits 1," "--deposits 1.5" "--bogus 1" "--prefix aaaaaaaaaaaaaaaaa --users 10" "--bcrypt-cost 3" "--bcrypt-cost 13" "--bcrypt-cost x" "--bcrypt-cost 04"; do
  # shellcheck disable=SC2086
  [ "$(rc_of parse_args $a)" = 1 ] && ok "parse_args: '$a' → 1" || bad "parse_args $a" "$(rc_of parse_args $a)"
done
( parse_args --users 10000 --start 1000 --prefix lt --deposits 30000000,70000000 --out "$T/u.csv" ) && ok "parse_args: 정상 입력 통과" || bad "parse_args 정상" "rc=$?"

# ---- build_sql: 들어가야 할 것 ----
sql="$(build_sql)"
for s in "BEGIN;" "COMMIT;" "generate_series(:start, :last)" "INSERT INTO account.users" "INSERT INTO account.account" "INSERT INTO account.ledger_entry" "'DEPOSIT'" ":'pwhash'" "user_id::text" "'SYSTEM:account-service'" "sum(amount) OVER (ORDER BY k)"; do
  case "$sql" in *"$s"*) ok "build_sql: $s" ;; *) bad "build_sql" "$s 없음" ;; esac
done

# ---- main: 해시 형식이 이상하거나 DB가 실패하면 종료 코드 4, CSV를 만들지 않는다 ----
make_hash() { echo "plain"; }
[ "$(rc_of main --users 2 --out "$T/a.csv")" = 4 ] && [ ! -e "$T/a.csv" ] && ok "main: 해시 형식이 BCrypt가 아니면 4" || bad "main 해시" "$(rc_of main --users 2 --out "$T/a.csv")"
make_hash() { printf '%s\n' '$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234'; }
psql_in() { cat > /dev/null; return 3; }
[ "$(rc_of main --users 2 --out "$T/b.csv")" = 4 ] && [ ! -e "$T/b.csv" ] && ok "main: 넣기 실패 → 4, CSV 없음" || bad "main 넣기 실패" "$(rc_of main --users 2 --out "$T/b.csv")"

# ---- main 성공: CSV 머리줄과 행 (CR 제거) ----
psql_in() {
  local sql; sql="$(cat)"
  case "$sql" in
    *COPY*) printf 'lt0000,u0,a0,,\r\nlt0001,u1,a1,,\r\n' ;;
    *) echo "inserted users=2 ledger=4" ;;
  esac
}
( main --users 2 --out "$T/c.csv" ) > /dev/null 2>&1; rc=$?
[ "$rc" = 0 ] && [ "$(head -1 "$T/c.csv")" = "loginId,userId,accountId,accountId2,token" ] && [ "$(sed -n 2p "$T/c.csv")" = "lt0000,u0,a0,," ] && ! grep -q $'\r' "$T/c.csv" \
  && ok "main: CSV 머리줄과 회원 행 (CR 없음)" || bad "main CSV" "rc=$rc $(cat "$T/c.csv" 2>&1)"

# ---- --bcrypt-cost: 해시의 비용이 옵션과 같아야 한다 ----
[ "$(rc_of main --users 2 --bcrypt-cost 4 --out "$T/d.csv")" = 4 ] && [ ! -e "$T/d.csv" ] && ok "main: --bcrypt-cost 4인데 해시 비용이 10이면 4" || bad "main 비용 불일치" "$(rc_of main --users 2 --bcrypt-cost 4 --out "$T/d.csv")"
make_hash() { printf '%s\n' "\$2a\$$(printf '%02d' "$COST")\$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"; }
[ "$(rc_of main --users 2 --bcrypt-cost 4 --out "$T/e.csv")" = 0 ] && [ -e "$T/e.csv" ] && ok "main: --bcrypt-cost 4 + 비용 04 해시면 통과" || bad "main 비용 4" "$(rc_of main --users 2 --bcrypt-cost 4 --out "$T/e.csv")"
# 실제 make_hash (위에서 가짜로 바꿨으므로 스크립트를 다시 불러온다): 비용을 psql 변수로, SQL은 gen_salt('bf', :cost)
got="$( ( source "$HERE/../seed/seed_users_sql.sh"; COST=4; LOADTEST_PASSWORD=pw
          psql_in() { printf 'ARGS %s\n' "$*" >&2; cat >&2; echo '$2a$04$x'; }   # 인자와 SQL을 stderr로 보여 준다
          make_hash ) 2>&1 )"
case "$got" in *"ARGS postgres -v pw=pw -v cost=4"*"gen_salt('bf', :cost)"*) ok "make_hash: 비용을 psql 변수로 넘기고 gen_salt에 쓴다" ;; *) bad "make_hash 비용" "$got" ;; esac

rm -rf "$T"
echo; echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" -eq 0 ]
