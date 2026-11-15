#!/usr/bin/env bash
# S7(경쟁률 조회 부하)용 공모주 1개와 확정 청약 N건을 SQL로 넣고 공모주 ID를 출력한다.
# 목록을 조회할 때마다 확정 청약 수량을 합산하는 비용이 드러나게, 확정 청약이 많이 쌓인 공모주를 목록 첫 페이지 맨 위에 둔다.
#
# 측정 전용 합성 데이터다 (결과에 같이 적는다)
#   - 청약 서비스 subscription 표에만 넣는다. 계좌 쪽(증거금, 원장)에 대응하는 데이터가 없어 정합성이 맞지 않는다
#   - 그래서 마감되면 안 된다: 마감 시각을 10년 뒤로 둔다 (마감되면 배정이 계좌에 없는 청약을 처리하려 한다)
#   - 목록은 등록 시각 최신순이라, 측정 때 새로 만드는 청약용 공모주에 밀리지 않게 등록 시각을 2099년으로 둔다
#   - 한 번에 넣어 이 공모주의 행이 디스크에 몰려 저장된다. 여러 공모주 청약이 섞여 쌓이는 실제보다 합산이 빠를 수 있다 (DB 합산에 유리)
#   - 회원 ID, 계좌 ID는 행마다 무작위 값이다 (같은 회원의 유효 청약은 공모주당 1건이라는 제약을 피함. 회원, 계좌 표에는 없음)
#
# 사용: ./s7_ipo.sh [--count 100000] [--quantity 10] [--retail 14494] [--price 1000] [--name s7-confirmed-100k]
#   기본값: 확정 청약 10만 건 x 10주 = 신청 100만 주, 물량 14,494주 → 경쟁률 약 69:1 (S5 대량 환불과 같은 경쟁률)
#   물량은 짝수여야 한다 (균등 물량 = 물량 / 2, DB 제약 균등 x 2 >= 물량)
# 스냅샷: 기준 스냅샷을 복원한 뒤 이 스크립트를 실행하고 ALLOW_SUBSCRIPTIONS=1 BASELINE_DIR=loadtest/data/baseline-s7 bash loadtest/baseline.sh save
# 환경변수: PG_CONTAINER(기본 ipo-postgres), PG_USER(기본 postgres), PG_DB(기본 ipo)
set -euo pipefail
export MSYS_NO_PATHCONV=1

PG_CONTAINER="${PG_CONTAINER:-ipo-postgres}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-ipo}"
COUNT=100000
QUANTITY=10
RETAIL=14494
PRICE=1000
NAME="s7-confirmed-100k"

while [ $# -gt 0 ]; do
  case "$1" in
    --count) COUNT="$2"; shift 2 ;;
    --quantity) QUANTITY="$2"; shift 2 ;;
    --retail) RETAIL="$2"; shift 2 ;;
    --price) PRICE="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done

for v in "$COUNT" "$QUANTITY" "$RETAIL" "$PRICE"; do
  [[ "$v" =~ ^[1-9][0-9]*$ ]] || { echo "--count, --quantity, --retail, --price는 1 이상 정수: $v" >&2; exit 1; }
done
[ $((RETAIL % 2)) -eq 0 ] || { echo "--retail은 짝수여야 한다 (균등 물량 = 물량 / 2): $RETAIL" >&2; exit 1; }
[ "${#NAME}" -le 30 ] || { echo "--name은 30자 이하 (ipo.name VARCHAR(30)): $NAME" >&2; exit 1; }

docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -qtA \
  -v name="$NAME" -v count="$COUNT" -v qty="$QUANTITY" -v retail="$RETAIL" -v price="$PRICE" -f - <<'SQL'
BEGIN;
CREATE TEMP TABLE s7_new (ipo_id uuid) ON COMMIT DROP;
WITH ins AS (
  INSERT INTO subscription.ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                created_at, created_by, updated_at, updated_by)
  VALUES (gen_random_uuid(), :'name', :price, :retail, :retail / 2, 1, 10000,
          1.00, now() - interval '1 hour', now() + interval '10 years',
          ((now() + interval '10 years') AT TIME ZONE 'Asia/Seoul')::date + 3, 'OPEN', 0,
          timestamptz '2099-01-01 00:00:00+09', 'SYSTEM:loadtest', now(), 'SYSTEM:loadtest')
  RETURNING ipo_id
)
INSERT INTO s7_new SELECT ipo_id FROM ins;

INSERT INTO subscription.subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity, margin_amount,
                                       status, attempts, last_requested_at, received_at, confirmed_at, version,
                                       created_at, created_by, updated_at, updated_by)
SELECT gen_random_uuid(), gen_random_uuid(), n.ipo_id, gen_random_uuid(), gen_random_uuid(), :qty, :qty * :price,
       'CONFIRMED', 1, now(), now(), now(), 0,
       now(), 'SYSTEM:loadtest', now(), 'SYSTEM:loadtest'
FROM s7_new n, generate_series(1, :count);

SELECT ipo_id FROM s7_new;
COMMIT;
ANALYZE subscription.subscription;
SQL
