#!/usr/bin/env bash
# 사건 알림(DltMessageArrived, ErrorLogDetected) 식의 회귀 테스트
# rules.yml에서 두 규칙의 실제 식을 꺼내 event-alerts.test.yml에 넣고, Prometheus 이미지의 promtool로 실행한다
# 사용: bash docker/grafana/alert-tests/run.sh   (Docker 필요, 성공하면 SUCCESS)
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RULES="${DIR}/../provisioning/alerting/rules.yml"
TEMPLATE="${DIR}/event-alerts.test.yml"
# docker-compose.yml의 prometheus 이미지와 같은 버전
IMAGE="${PROMETHEUS_IMAGE:-prom/prometheus:v3.5.0}"

# uid의 규칙에서 "expr: >-" 아래 식을 한 줄로 꺼낸다 (instant: 줄 앞까지)
extract_expr() {
  awk -v uid="$1" '
    $0 ~ "uid: " uid "$" { in_rule = 1; next }
    in_rule && /uid: / { exit }
    in_rule && /expr: >-/ { in_expr = 1; next }
    in_expr && /instant:/ { exit }
    in_expr { sub(/^[ \t]+/, ""); printf "%s ", $0 }
  ' "${RULES}"
}

DLT_EXPR="$(extract_expr ipo-dlt-message-arrived)"
ERROR_EXPR="$(extract_expr ipo-error-log-detected)"
if [ -z "${DLT_EXPR}" ] || [ -z "${ERROR_EXPR}" ]; then
  echo "rules.yml에서 식을 찾지 못했습니다 (uid ipo-dlt-message-arrived, ipo-error-log-detected)" >&2
  exit 1
fi
# 테스트 파일의 expr는 작은따옴표 문자열이라 식에 작은따옴표가 있으면 안 된다
case "${DLT_EXPR}${ERROR_EXPR}" in
  *"'"*) echo "식에 작은따옴표가 있어 테스트 파일에 넣을 수 없습니다" >&2; exit 1 ;;
esac

# 식을 넣은 테스트 파일을 표준 입력으로 컨테이너에 넘긴다 (호스트 경로를 마운트하지 않아 OS와 관계없이 같게 동작)
awk -v dlt="${DLT_EXPR}" -v err="${ERROR_EXPR}" '
  { gsub(/__DLT_EXPR__/, dlt); gsub(/__ERROR_EXPR__/, err); print }
' "${TEMPLATE}" | docker run --rm -i --entrypoint sh "${IMAGE}" -c 'cat > /tmp/event-alerts.test.yml && promtool test rules /tmp/event-alerts.test.yml'
