#!/bin/bash
# postgres 기본 진입점을 감싸서, DB를 처음 만들기 전에(데이터 디렉터리가 비어 있을 때만) 필수 비밀번호를 검사한다
# 초기화가 시작된 뒤에 실패하면 init.sh가 다시 실행되지 않으므로, 데이터 디렉터리를 만들기 전에 막는다

# $1 = 컨테이너 환경변수 이름, $2 = .env 에 적는 이름
require() {
  if [ -z "${!1}" ]; then
    echo "entrypoint.sh: ${2}이(가) 비어 있습니다. .env에 DB 비밀번호를 설정하고 다시 올리세요 (DB를 만들기 전이라 볼륨을 지울 필요는 없습니다)" >&2
    exit 1
  fi
}

if [ ! -s "${PGDATA:-/var/lib/postgresql/data}/PG_VERSION" ]; then
  require POSTGRES_PASSWORD DB_PASSWORD
  require ACCOUNT_DB_PASSWORD ACCOUNT_DB_PASSWORD
  require SUBSCRIPTION_DB_PASSWORD SUBSCRIPTION_DB_PASSWORD
  require ALLOCATION_DB_PASSWORD ALLOCATION_DB_PASSWORD
fi

exec docker-entrypoint.sh "$@"
