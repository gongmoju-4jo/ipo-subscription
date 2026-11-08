#!/usr/bin/env bash
# 회원, 계좌, 예수금 입금을 API 대신 SQL 한 번으로 넣는다 (seed_users.py와 같은 모양. 10만 명 API 가입이 몇 시간 걸려서).
# 넣는 것 (회원 1명마다): users 1행, account 1행, 입금 원장(DEPOSIT) --deposits 개수만큼. 청약은 넣지 않는다 (청약은 k6 API로)
#   - loginId는 seed_users.py와 같다: <접두사><번호 4자리> (1만 이상이면 자리수 그대로). seed_users.py --refresh-tokens로 토큰을 받을 수 있다
#   - 비밀번호 해시는 BCrypt 하나를 만들어 모든 회원에 쓴다 (API는 회원마다 솔트가 다르지만 로그인과 측정에는 차이가 없다)
#     비용 기본 10은 API 가입과 같다. --bcrypt-cost 4로 낮추면 로그인(토큰 발급)이 빨라진다 (10만 명 토큰 발급용. 해시 길이는 같다)
#   - created_by: users는 SYSTEM:account-service, account와 원장은 회원 ID (API가 X-User-Id로 남기는 값과 같게)
#   - 한 트랜잭션이다. 같은 loginId가 이미 있으면 아무것도 넣지 않고 실패한다
# 사용: bash seed/seed_users_sql.sh [--users 1000] [--start 0] [--prefix lt] [--deposits 30000000,70000000] [--bcrypt-cost 10] [--out data/users.csv]
#   --start S --users N: 번호 S부터 N-1까지 넣는다 (예: 기존 1,000명 뒤에 9,000명을 더하려면 --start 1000 --users 10000)
#   --out: 이 접두사의 회원 전체(DB 기준)를 loginId,userId,accountId,accountId2,token CSV로 쓴다 (token은 비어 있음. 다음에 seed_users.py --refresh-tokens)
# 환경변수: PG_CONTAINER(ipo-postgres), PG_DB(ipo), LOADTEST_PASSWORD(기본 loadtest1234, seed_users.py와 같음)
# 종료 코드: 0 정상 / 1 사용법 / 4 DB 실패(넣은 것 없음)
set -uo pipefail
export MSYS_NO_PATHCONV=1
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_CONTAINER="${PG_CONTAINER:-ipo-postgres}"; PG_DB="${PG_DB:-ipo}"
USERS=1000; START=0; PREFIX=lt; DEPOSITS="30000000,70000000"; COST=10; OUT="$DIR/data/users.csv"

say() { printf '%s %s\n' "$(date '+%F %T')" "$*" >&2; }
die() { local c="$1"; shift; say "!! $*"; exit "$c"; }
psql_in() { docker exec -i "$PG_CONTAINER" psql -U postgres -d "${1:-$PG_DB}" -v ON_ERROR_STOP=1 -qtA "${@:2}"; }

parse_args() {
  while [ $# -gt 0 ]; do
    [ $# -ge 2 ] || die 1 "옵션 값이 없다: $1"   # 값이 없으면 shift 2가 실패해 같은 자리를 계속 돈다
    case "$1" in
      --users) USERS="${2:-}"; shift 2 ;;
      --start) START="${2:-}"; shift 2 ;;
      --prefix) PREFIX="${2:-}"; shift 2 ;;
      --deposits) DEPOSITS="${2:-}"; shift 2 ;;
      --bcrypt-cost) COST="${2:-}"; shift 2 ;;
      --out) OUT="${2:-}"; shift 2 ;;
      *) die 1 "알 수 없는 옵션: $1" ;;
    esac
  done
  case "$USERS" in ''|*[!0-9]*) die 1 "--users는 숫자: $USERS" ;; esac
  case "$START" in ''|*[!0-9]*) die 1 "--start는 숫자: $START" ;; esac
  [ "$START" -lt "$USERS" ] || die 1 "--start($START)는 --users($USERS)보다 작아야 한다"
  case "$PREFIX" in ''|*[!a-z0-9]*) die 1 "--prefix는 영문 소문자, 숫자: $PREFIX" ;; esac
  # loginId 규칙 4~20자 (가장 긴 번호 기준)
  local longest; longest="$(login_id "$PREFIX" $((USERS - 1)))"
  [ "${#longest}" -le 20 ] || die 1 "loginId가 20자를 넘는다: $longest"
  case "$DEPOSITS" in ''|,*|*,|*,,*|*[!0-9,]*) die 1 "--deposits는 쉼표로 나눈 양의 정수: $DEPOSITS" ;; esac
  local d; for d in ${DEPOSITS//,/ }; do [ "$d" -gt 0 ] || die 1 "입금액은 1 이상: $d"; done
  [ -n "$OUT" ] || die 1 "--out이 비어 있다"
  case "$COST" in 4|5|6|7|8|9|10|11|12) ;; *) die 1 "--bcrypt-cost는 4 ~ 12: $COST" ;; esac
}

# seed_users.py의 f"{prefix}{idx:04d}"와 같다
login_id() { printf '%s%04d' "$1" "$2"; }

# 넣는 SQL. psql 변수: prefix, start, last, deposits(쉼표 목록), pwhash
build_sql() {
  cat <<'SQL'
BEGIN;
CREATE TEMP TABLE seed ON COMMIT DROP AS
  SELECT g AS n,
         :'prefix' || CASE WHEN g < 10000 THEN lpad(g::text, 4, '0') ELSE g::text END AS login_id,
         gen_random_uuid() AS user_id, gen_random_uuid() AS account_id,
         -- 계좌번호 12자리: 앞 5자리 무작위 + 뒤 7자리 번호 (번호가 달라 이 시드 안에서는 겹치지 않는다)
         lpad((10000 + floor(random() * 90000))::int::text, 5, '0') || lpad(g::text, 7, '0') AS account_number
  FROM generate_series(:start, :last) AS g;
CREATE TEMP TABLE dep ON COMMIT DROP AS
  SELECT k, amount, sum(amount) OVER (ORDER BY k) AS cash_after
  FROM unnest(string_to_array(:'deposits', ',')::bigint[]) WITH ORDINALITY AS t(amount, k);
INSERT INTO account.users (user_id, login_id, name, password_hash, role, created_at, created_by, updated_at, updated_by)
  SELECT user_id, login_id, 'loadtest', :'pwhash', 'USER', clock_timestamp(), 'SYSTEM:account-service', clock_timestamp(), 'SYSTEM:account-service'
  FROM seed ORDER BY n;
INSERT INTO account.account (account_id, account_number, cash_balance, margin_balance, request_id, user_id, created_at, created_by, updated_at, updated_by)
  SELECT account_id, account_number, (SELECT sum(amount) FROM dep), 0, gen_random_uuid(), user_id,
         clock_timestamp(), user_id::text, clock_timestamp(), user_id::text
  FROM seed ORDER BY n;
INSERT INTO account.ledger_entry (ledger_entry_id, account_id, type, amount, cash_balance_after, margin_balance_after, request_id, created_at, created_by)
  SELECT gen_random_uuid(), s.account_id, 'DEPOSIT', d.amount, d.cash_after, 0, gen_random_uuid(), clock_timestamp(), s.user_id::text
  FROM seed s CROSS JOIN dep d ORDER BY s.n, d.k;
SELECT 'inserted users=' || (SELECT count(*) FROM seed) || ' ledger=' || (SELECT count(*) FROM seed) * (SELECT count(*) FROM dep);
COMMIT;
SQL
}

# BCrypt 해시 하나. ipo DB에 확장을 남기지 않으려고 관리용 DB(postgres)의 pgcrypto로 만든다
make_hash() {
  printf '%s\n' "CREATE EXTENSION IF NOT EXISTS pgcrypto;" "SELECT crypt(:'pw', gen_salt('bf', :cost));" \
    | psql_in postgres -v pw="${LOADTEST_PASSWORD:-loadtest1234}" -v cost="$COST" | tr -d '\r' | tail -1
}

write_csv() { # 이 접두사의 회원 전체 (회원마다 가장 먼저 연 계좌)
  local tmp="$OUT.tmp"
  mkdir -p "$(dirname "$OUT")"
  printf '%s\n' "COPY (SELECT u.login_id, u.user_id, a.account_id, '', '' FROM account.users u
          JOIN LATERAL (SELECT account_id FROM account.account WHERE user_id = u.user_id ORDER BY created_at LIMIT 1) a ON true
          WHERE u.login_id ~ ('^' || :'prefix' || '[0-9]{4,}\$') ORDER BY u.login_id) TO STDOUT WITH (FORMAT csv)" \
    | psql_in "$PG_DB" -v prefix="$PREFIX" > "$tmp.body" || { rm -f "$tmp.body"; return 1; }
  { echo "loginId,userId,accountId,accountId2,token"; tr -d '\r' < "$tmp.body"; } > "$tmp" && rm -f "$tmp.body" && mv "$tmp" "$OUT"
}

main() {
  parse_args "$@"
  local hash t0 res
  t0="$(date +%s)"
  hash="$(make_hash)" || die 4 "비밀번호 해시 생성 실패"
  case "$hash" in '$2'?'$'"$(printf '%02d' "$COST")"'$'*) ;; *) die 4 "비밀번호 해시 형식이 이상함 (BCrypt 비용 $COST이 아님)" ;; esac
  say "회원 넣기: $(login_id "$PREFIX" "$START") ~ $(login_id "$PREFIX" $((USERS - 1))), 입금 $DEPOSITS"
  res="$(build_sql | psql_in "$PG_DB" -v prefix="$PREFIX" -v start="$START" -v last=$((USERS - 1)) -v deposits="$DEPOSITS" -v pwhash="$hash" | tr -d '\r')" \
    || die 4 "넣기 실패 (한 트랜잭션이라 넣은 것 없음): $res"
  say "$res ($(( $(date +%s) - t0 ))초)"
  write_csv || die 4 "CSV 쓰기 실패 (DB에는 넣었음): $OUT"
  say "CSV: $OUT ($(( $(wc -l < "$OUT") - 1 ))명, 토큰 없음 → python seed/seed_users.py --run-id <id> --prefix $PREFIX --users $USERS --out $OUT --refresh-tokens)"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
