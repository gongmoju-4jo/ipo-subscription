#!/usr/bin/env bash
# 시연 전용. 이미 처리된 margin.reserve-requested 메시지 1건을 같은 key, value로 다시 발행한다 (Kafka 중복 전달 재현)
#   bash frontend/ops-server/demo-dup.sh                    토픽에서 가장 최근 메시지를 다시 발행
#   bash frontend/ops-server/demo-dup.sh <subscriptionId>   그 청약의 메시지를 다시 발행
set -euo pipefail
TOPIC=margin.reserve-requested
BIN=/opt/kafka/bin
SUB="${1:-}"

# key|타임스탬프|value 로 전부 읽고 (5초 동안 새 메시지가 없으면 끝), 조건에 맞는 가장 최근 1건을 고른다
LINE=$(docker exec ipo-kafka $BIN/kafka-console-consumer.sh --bootstrap-server kafka:29092 --topic "$TOPIC" \
  --from-beginning --timeout-ms 5000 --formatter-property print.key=true --formatter-property print.timestamp=true \
  --formatter-property key.separator='|' 2>/dev/null | grep '^CreateTime:' \
  | { if [ -n "$SUB" ]; then grep -F "$SUB"; else cat; fi; } \
  | sed 's/^CreateTime://' | sort -t'|' -k1,1n | tail -1) || true   # 못 찾으면 grep이 1로 끝나도 아래 안내로 간다
[ -n "$LINE" ] || { echo "다시 보낼 메시지를 찾지 못했어요" >&2; exit 1; }

MSG=${LINE#*|}   # 타임스탬프 떼고 key|value
echo "다시 발행: key=${MSG%%|*}"
echo "value=${MSG#*|}"
printf '%s\n' "$MSG" | docker exec -i ipo-kafka $BIN/kafka-console-producer.sh --bootstrap-server kafka:29092 --topic "$TOPIC" \
  --reader-property parse.key=true --reader-property key.separator='|'
echo "보냈어요. 확인: docker logs ipo-account-service --since 30s | grep '중복 수신'"
