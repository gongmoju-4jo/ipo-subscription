#!/bin/sh
# 관리자 비밀번호가 비어 있으면 기본 계정(admin/admin)으로 뜨지 않도록 시작 전에 막는다
if [ -z "$GF_SECURITY_ADMIN_PASSWORD" ]; then
  echo "GRAFANA_ADMIN_PASSWORD가 비어 있습니다. .env에 직접 정한 값을 넣고 다시 실행하세요." >&2
  exit 1
fi
# Slack Webhook URL은 선택값이다. 비어 있으면 Grafana가 연락처 검증에 실패해 종료되므로,
# 어디에도 닿지 않는 로컬 주소를 대신 넣어 시작은 하고 알림만 나가지 않게 한다
if [ -z "$SLACK_WEBHOOK_URL" ]; then
  echo "SLACK_WEBHOOK_URL이 비어 있어 Slack 장애 알림이 나가지 않습니다." >&2
  export SLACK_WEBHOOK_URL="http://127.0.0.1:1/slack-webhook-disabled"
fi
exec /run.sh "$@"
