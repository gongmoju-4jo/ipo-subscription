#!/usr/bin/env bash
# 이번 회차용 새 공모주를 넣고 ipoId를 출력한다. 공모주 등록 API가 없어서 SQL로 넣는다 (슈퍼유저로 접속).
# 같은 종목을 다시 쓰면 회원 전원이 ALREADY_SUBSCRIBED가 되므로 회차마다 새 종목을 쓴다.
#
# 사용: ./new_ipo.sh [--name 이름] [--price 15000] [--min 10] [--max 10000] [--retail 100000] [--closed]
#   --closed: 청약 기간이 이미 끝난 종목 (S2 마감 거절용)
# 환경변수: PG_CONTAINER(기본 ipo-postgres), PG_USER(기본 postgres), PG_DB(기본 ipo)
set -euo pipefail
export MSYS_NO_PATHCONV=1

PG_CONTAINER="${PG_CONTAINER:-ipo-postgres}"; PG_USER="${PG_USER:-postgres}"; PG_DB="${PG_DB:-ipo}"
NAME="loadtest-$(date +%m%d-%H%M%S)"; PRICE=15000; MIN=10; MAX=10000; RETAIL=100000; CLOSED=0

while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="$2"; shift 2 ;;
    --price) PRICE="$2"; shift 2 ;;
    --min) MIN="$2"; shift 2 ;;
    --max) MAX="$2"; shift 2 ;;
    --retail) RETAIL="$2"; shift 2 ;;
    --closed) CLOSED=1; shift ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done
for v in "$PRICE" "$MIN" "$MAX" "$RETAIL"; do
  case "$v" in ''|*[!0-9]*) echo "숫자가 아닌 값: $v" >&2; exit 1 ;; esac
done

if [ "$CLOSED" = 1 ]; then START="now() - interval '2 days'"; END="now() - interval '1 hour'"
else START="now() - interval '1 hour'"; END="now() + interval '12 hours'"; fi

docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -qtA \
  -v name="$NAME" -v price="$PRICE" -v min="$MIN" -v max="$MAX" -v retail="$RETAIL" -f - <<SQL
INSERT INTO subscription.ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                              margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                              created_at, created_by, updated_at, updated_by)
VALUES (gen_random_uuid(), :'name', :price, :retail, :retail / 2, :min, :max,
        1.00, $START, $END, (now() AT TIME ZONE 'Asia/Seoul')::date + 3, 'OPEN', 0,
        now(), 'SYSTEM:loadtest', now(), 'SYSTEM:loadtest')
RETURNING ipo_id;
SQL
