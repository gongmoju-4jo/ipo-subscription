#!/usr/bin/env bash
# 회차 시작 전 점검. 결과를 results/<run_id>/precheck.txt에 저장한다.
# 사용: ./precheck.sh --run-id <run_id>
# 확인: Eureka의 서비스 4개 UP, 회원 CSV 토큰 나이(1시간 만료), Outbox 미발행 0건, DLT 토픽 메시지 수(기록), Consumer 그룹 lag
# 환경변수: PG_CONTAINER(ipo-postgres), KAFKA_CONTAINER(ipo-kafka), EUREKA_URL(http://localhost:8761), USERS_CSV(data/users.csv)
set -uo pipefail
export MSYS_NO_PATHCONV=1
DIR="$(cd "$(dirname "$0")" && pwd)"
PG_CONTAINER="${PG_CONTAINER:-ipo-postgres}"; PG_USER="${PG_USER:-postgres}"; PG_DB="${PG_DB:-ipo}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-ipo-kafka}"; EUREKA_URL="${EUREKA_URL:-http://localhost:8761}"
USERS_CSV="${USERS_CSV:-$DIR/data/users.csv}"
RUN_ID=""
while [ $# -gt 0 ]; do
  case "$1" in --run-id) RUN_ID="$2"; shift 2 ;; *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;; esac
done
[ -n "$RUN_ID" ] || { echo "사용: $0 --run-id <run_id>" >&2; exit 1; }
OUT="$DIR/results/$RUN_ID"; mkdir -p "$OUT"
FAIL=0

{
  echo "# 사전 점검 run_id=$RUN_ID $(date '+%Y-%m-%d %H:%M:%S')"

  echo; echo "== Eureka 등록 서비스 (4개 모두 UP이어야 한다)"
  APPS=$(curl -s -H "Accept: application/json" "$EUREKA_URL/eureka/apps" || true)
  UP=$(printf '%s' "$APPS" | grep -o '"status":"UP"' | wc -l | tr -d ' ')
  printf '%s' "$APPS" | grep -o '"app":"[A-Z-]*"' | sort -u | tr '\n' ' '; echo
  echo "UP 인스턴스 수: $UP"
  [ "$UP" -ge 4 ] || { echo "❌ UP 인스턴스가 4개 미만입니다"; FAIL=1; }

  echo; echo "== 회원 CSV 토큰 나이 (발급 후 1시간 안에 모든 회차를 끝내야 한다)"
  if [ -f "$USERS_CSV" ]; then
    AGE=$(( ($(date +%s) - $(date -r "$USERS_CSV" +%s)) / 60 ))
    echo "$USERS_CSV: 수정 후 ${AGE}분 경과"
    [ "$AGE" -lt 50 ] || { echo "❌ 50분이 지났습니다. python seed/seed_users.py --refresh-tokens 로 토큰을 다시 발급하세요"; FAIL=1; }
  else
    echo "❌ 회원 CSV가 없습니다: $USERS_CSV"; FAIL=1
  fi

  echo; echo "== Outbox 미발행 (모두 0이어야 한다)"
  OB=$(docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -qtA -F ' ' -c \
    "SELECT 'account', count(*) FROM account.outbox WHERE published_at IS NULL UNION ALL SELECT 'subscription', count(*) FROM subscription.outbox WHERE published_at IS NULL UNION ALL SELECT 'allocation', count(*) FROM allocation.outbox WHERE published_at IS NULL;" 2>&1)
  echo "$OB"
  printf '%s\n' "$OB" | awk '$2 != 0 {bad=1} END {exit bad}' || { echo "❌ 미발행 Outbox가 남아 있습니다"; FAIL=1; }

  echo; echo "== DLT 토픽 메시지 수 (측정 후 증가분만 이번 회차 것. 기록용)"
  for t in $(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list 2>/dev/null | grep '\.DLT$' | sort); do
    N=$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:29092 --topic "$t" 2>/dev/null | awk -F: '{s+=$3} END {print s+0}')
    echo "$t $N"
  done

  echo; echo "== Consumer 그룹 lag (모두 0이어야 한다. 그룹이 없으면 아무것도 나오지 않는다)"
  docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --all-groups 2>&1 | grep -v '^$' || true

  echo; [ "$FAIL" = 0 ] && echo "✅ 점검 통과" || echo "❌ 점검 실패: 위 항목을 해결한 뒤 측정하세요"
} 2>&1 | tee "$OUT/precheck.txt"
echo "→ $OUT/precheck.txt"
grep -q "✅ 점검 통과" "$OUT/precheck.txt"
