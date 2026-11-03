#!/usr/bin/env bash
# 정합성 검증 SQL(sql/verify.sql)을 실행하고 결과를 results/<run_id>/sql_result.txt에 저장한다.
# 사용: ./verify.sh --ipo <공모주 ID> --run-id <run_id>
#       ./verify.sh --ipo <ID1> --ipo <ID2> ... --run-id <run_id> --each-dir
#         공모주 여러 개를 psql 한 번으로 검사하고 results/<run_id>/verify-<번호>/sql_result.txt에 하나씩 나눠 저장한다.
#         전체 계좌 기준인 2, 3번은 첫 공모주(verify-1)에서만 실행한다 (공모주마다 결과가 같다).
#         psql이 실패하거나 나눠진 결과 수가 공모주 수와 다르면 종료 코드 1
# 환경변수: PG_CONTAINER(기본 ipo-postgres), PG_USER(기본 postgres), PG_DB(기본 ipo)
set -euo pipefail
export MSYS_NO_PATHCONV=1
DIR="$(cd "$(dirname "$0")" && pwd)"
PG_CONTAINER="${PG_CONTAINER:-ipo-postgres}"; PG_USER="${PG_USER:-postgres}"; PG_DB="${PG_DB:-ipo}"
IPOS=(); RUN_ID=""; EACH=0
while [ $# -gt 0 ]; do
  case "$1" in
    --ipo) IPOS+=("$2"); shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    --each-dir) EACH=1; shift ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done
[ "${#IPOS[@]}" -gt 0 ] && [ -n "$RUN_ID" ] || { echo "사용: $0 --ipo <공모주 ID> --run-id <run_id> [--each-dir]" >&2; exit 1; }
for IPO in "${IPOS[@]}"; do
  case "$IPO" in ''|*[!0-9a-fA-F-]*) echo "공모주 ID 형식이 올바르지 않습니다: $IPO" >&2; exit 1 ;; esac
done
[ "$EACH" = 1 ] || [ "${#IPOS[@]}" -eq 1 ] || { echo "공모주 여러 개는 --each-dir로 실행한다" >&2; exit 1; }

OUT="$DIR/results/$RUN_ID"; mkdir -p "$OUT"
if [ "$EACH" = 0 ]; then
  IPO="${IPOS[0]}"
  {
    echo "# run_id=$RUN_ID ipo=$IPO $(date '+%Y-%m-%d %H:%M:%S')"
    docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -v ipo="'$IPO'" -f - < "$DIR/sql/verify.sql"
  } 2>&1 | tee "$OUT/sql_result.txt"
  echo "→ $OUT/sql_result.txt"
  exit 0
fi

# 공모주마다 표시 줄(@@VERIFY 번호 ID) → 변수 설정 → verify.sql 본문을 이어 붙여 psql 한 번으로 실행한다
ALL="$OUT/.verify-all.out"
k=0
{
  for IPO in "${IPOS[@]}"; do
    k=$((k + 1))
    printf '\\echo @@VERIFY %s %s\n' "$k" "$IPO"
    printf "\\\\set ipo '''%s'''\n" "$IPO"
    if [ "$k" -eq 1 ]; then printf '\\unset skip_global\n'; else printf '\\set skip_global 1\n'; fi
    cat "$DIR/sql/verify.sql"; printf '\n'
  done
} | docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -f - > "$ALL" 2>&1 && rc=0 || rc=$?   # set -e로 바로 끝나지 않게
# psql이 중간에 실패해도 그때까지의 결과는 공모주별 파일로 나눠 남기고, 실패는 그대로 실패로 보고한다
NOW="$(date '+%Y-%m-%d %H:%M:%S')"
n="$(awk -v out="$OUT" -v run="$RUN_ID" -v now="$NOW" '
  { sub(/\r$/, "") }
  /^@@VERIFY [0-9]+ / { if (f) close(f); d = out "/verify-" $2; system("mkdir -p \"" d "\""); f = d "/sql_result.txt"
                        print "# run_id=" run "/verify-" $2 " ipo=" $3 " " now > f; c++; next }
  f { print > f }
  END { print c + 0 }' "$ALL")"
[ "$rc" -eq 0 ] || { echo "psql 실패 (종료 코드 $rc, ${n}번째 공모주까지 나눠 저장, 전체 출력 $ALL)" >&2; exit 1; }
[ "$n" -eq "${#IPOS[@]}" ] || { echo "결과가 ${n}개로 공모주 수(${#IPOS[@]})와 다름 ($ALL)" >&2; exit 1; }
rm -f "$ALL"
echo "→ $OUT/verify-1 ~ verify-$n"
