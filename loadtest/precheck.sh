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
  # Kafka 명령이 실패하면 빈 결과나 0으로 보이지 않도록 점검 실패로 처리한다
  TOPICS=$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list 2>&1); RC=$?
  if [ "$RC" -ne 0 ]; then
    echo "❌ Kafka 토픽 목록을 읽지 못했습니다: $TOPICS"; FAIL=1
  else
    # 오프셋 도구는 일부 파티션을 읽지 못해도 종료 코드 0으로 끝나고 빠진 파티션은 말없이 합계에서 빠진다.
    # 그래서 토픽의 파티션 수와 읽힌 오프셋 줄 수가 같은지 확인한다.
    DESC=$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --describe 2>&1); RC=$?
    if [ "$RC" -ne 0 ]; then
      echo "❌ Kafka 토픽 정보를 읽지 못했습니다: $DESC"; FAIL=1
    fi
    for t in $(printf '%s\n' "$TOPICS" | grep '\.DLT$' | sort); do
      # --topic은 정규식(전체 일치)이라 토픽 이름의 .이 아무 글자로 읽히지 않게 이스케이프한다
      TPAT=$(printf '%s' "$t" | sed 's/[.]/\./g')
      OFFS=$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:29092 --topic "$TPAT" 2>&1); RC=$?
      if [ "$RC" -ne 0 ]; then
        echo "❌ $t 오프셋을 읽지 못했습니다: $OFFS"; FAIL=1; continue
      fi
      EXPECT=$(printf '%s\n' "$DESC" | grep -F -- "Topic: $t"$'\t' | sed -n 's/.*PartitionCount: \([0-9][0-9]*\).*/\1/p' | head -1)
      GOT=$(printf '%s\n' "$OFFS" | grep -cE '^[^:]+:[0-9]+:[0-9]+$')
      if [ -z "$EXPECT" ] || [ "$GOT" != "$EXPECT" ]; then
        echo "❌ $t 오프셋이 일부만 조회됐습니다 (파티션 ${EXPECT:-?}개 중 ${GOT}개)"; FAIL=1; continue
      fi
      echo "$t $(printf '%s\n' "$OFFS" | awk -F: '{s+=$3} END {print s+0}')"
    done
  fi

  echo; echo "== Consumer 그룹 lag (모두 0이어야 한다. 그룹이 없으면 아무것도 나오지 않는다)"
  LAGS=$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --all-groups 2>&1); RC=$?
  if [ "$RC" -ne 0 ]; then
    echo "❌ Consumer 그룹 lag을 읽지 못했습니다: $LAGS"; FAIL=1
  else
    printf '%s\n' "$LAGS" | grep -v '^$' || true
    # 이 도구는 조회 중 오류가 나도(그룹 없음, 브로커 접속 불가 등) Error: 를 출력하고 종료 코드 0으로 끝난다.
    # 그룹이 하나도 없어서 출력이 비는 것은 정상이다.
    if printf '%s\n' "$LAGS" | grep -q '^Error:'; then
      echo "❌ Consumer 그룹 lag 조회 중 오류가 발생했습니다 (위 출력 참고)"; FAIL=1
    fi
  fi

  echo; [ "$FAIL" = 0 ] && echo "✅ 점검 통과" || echo "❌ 점검 실패: 위 항목을 해결한 뒤 측정하세요"
} 2>&1 | tee "$OUT/precheck.txt"
echo "→ $OUT/precheck.txt"
grep -q "✅ 점검 통과" "$OUT/precheck.txt"
