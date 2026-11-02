#!/bin/sh
# 관리자 비밀번호가 비어 있으면 기본 계정(admin/admin)으로 뜨지 않도록 시작 전에 막는다
if [ -z "$GF_SECURITY_ADMIN_PASSWORD" ]; then
  echo "GRAFANA_ADMIN_PASSWORD가 비어 있습니다. .env에 직접 정한 값을 넣고 다시 실행하세요." >&2
  exit 1
fi
exec /run.sh "$@"
