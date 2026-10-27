#!/bin/bash
# Kafka 토픽을 만든다. 이미 있으면 건너뛰므로 여러 번 실행해도 된다 (--if-not-exists)
# 토픽 이름은 이벤트 명세 기준. 토픽마다 {토픽}.DLT도 함께 만든다
set -euo pipefail

BOOTSTRAP_SERVER="kafka:29092"
# 파티션 수는 여기 한 곳에서 관리한다 (환경변수 KAFKA_TOPIC_PARTITIONS가 있으면 그 값)
PARTITIONS="${KAFKA_TOPIC_PARTITIONS:-3}"
# 단일 브로커라 복제본은 1
REPLICATION_FACTOR=1

TOPICS=(
  subscription.requested
  margin.reserve-requested
  margin.reserved
  margin.reserve-failed
  margin.void-requested
  margin.voided
  ipo.closed
  allocation.result
  margin.settled
  allocation.finished
)

for topic in "${TOPICS[@]}"; do
  for name in "${topic}" "${topic}.DLT"; do
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVER}" \
      --create --if-not-exists \
      --topic "${name}" \
      --partitions "${PARTITIONS}" \
      --replication-factor "${REPLICATION_FACTOR}"
  done
done

echo "토픽 생성 완료 (파티션 ${PARTITIONS}개)"
