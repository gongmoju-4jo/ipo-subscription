#!/usr/bin/env bash
# 정합성 검증 SQL(sql/verify.sql)을 실행하고 결과를 results/<run_id>/sql_result.txt에 저장한다.
# 사용: ./verify.sh --ipo <공모주 ID> --run-id <run_id>
# 환경변수: PG_CONTAINER(기본 ipo-postgres), PG_USER(기본 postgres), PG_DB(기본 ipo)
set -euo pipefail
export MSYS_NO_PATHCONV=1
DIR="$(cd "$(dirname "$0")" && pwd)"
PG_CONTAINER="${PG_CONTAINER:-ipo-postgres}"; PG_USER="${PG_USER:-postgres}"; PG_DB="${PG_DB:-ipo}"
IPO=""; RUN_ID=""
while [ $# -gt 0 ]; do
  case "$1" in
    --ipo) IPO="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done
[ -n "$IPO" ] && [ -n "$RUN_ID" ] || { echo "사용: $0 --ipo <공모주 ID> --run-id <run_id>" >&2; exit 1; }
case "$IPO" in *[!0-9a-fA-F-]*) echo "공모주 ID 형식이 올바르지 않습니다: $IPO" >&2; exit 1 ;; esac

OUT="$DIR/results/$RUN_ID"; mkdir -p "$OUT"
{
  echo "# run_id=$RUN_ID ipo=$IPO $(date '+%Y-%m-%d %H:%M:%S')"
  docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -v ipo="'$IPO'" -f - < "$DIR/sql/verify.sql"
} 2>&1 | tee "$OUT/sql_result.txt"
echo "→ $OUT/sql_result.txt"
