#!/bin/bash
# 서비스별 스키마 분리 + 서비스별 DB 계정 (발제: 서비스별 DB 분리)
# database ipo 하나에 서비스마다 스키마 하나, 계정 하나. 계정은 자기 스키마만 쓸 수 있다
# 컨테이너를 처음 만들 때(볼륨이 비어 있을 때) 한 번만 실행된다. 바꾸면 PostgreSQL 볼륨을 지운 뒤 다시 올린다 (docs/PROJECT_SETUP.md 10장)
# SQL 파일은 환경변수(서비스별 비밀번호)를 읽을 수 없어서 셸 스크립트로 둔다

for name in ACCOUNT_DB_PASSWORD SUBSCRIPTION_DB_PASSWORD ALLOCATION_DB_PASSWORD; do
  if [ -z "${!name}" ]; then
    echo "init.sh: ${name}이(가) 비어 있습니다. .env에 서비스별 DB 비밀번호를 설정한 뒤 PostgreSQL 볼륨을 지우고 다시 올리세요 (docs/PROJECT_SETUP.md 10장. 초기화는 한 번만 실행되어 실패해도 재실행되지 않습니다)" >&2
    exit 1
  fi
done

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v account_pw="$ACCOUNT_DB_PASSWORD" \
  -v subscription_pw="$SUBSCRIPTION_DB_PASSWORD" \
  -v allocation_pw="$ALLOCATION_DB_PASSWORD" <<'EOSQL'
-- public 스키마는 모두에게 열려 있으므로 기본 권한을 회수한다
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- 서비스별 계정 (슈퍼유저 postgres는 초기화, 관리용. 앱은 쓰지 않는다)
CREATE ROLE account_app LOGIN PASSWORD :'account_pw';
CREATE ROLE subscription_app LOGIN PASSWORD :'subscription_pw';
CREATE ROLE allocation_app LOGIN PASSWORD :'allocation_pw';

-- 계정이 자기 스키마의 소유자가 된다. 다른 서비스 스키마에는 권한이 없다
CREATE SCHEMA IF NOT EXISTS account AUTHORIZATION account_app;
CREATE SCHEMA IF NOT EXISTS subscription AUTHORIZATION subscription_app;
CREATE SCHEMA IF NOT EXISTS allocation AUTHORIZATION allocation_app;

-- 접속하면 자기 스키마가 기본이 되도록 고정한다
ALTER ROLE account_app SET search_path TO account;
ALTER ROLE subscription_app SET search_path TO subscription;
ALTER ROLE allocation_app SET search_path TO allocation;
EOSQL
