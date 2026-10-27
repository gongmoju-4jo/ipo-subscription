#!/bin/bash
# 서비스별 스키마 분리 (발제: 서비스별 DB 분리). database ipo 하나에 서비스마다 스키마 하나
# 컨테이너를 처음 만들 때(볼륨이 비어 있을 때) 한 번만 실행된다. 바꾸면 docker-compose down -v 로 볼륨을 지운 뒤 다시 올린다
# SQL 파일은 환경변수(서비스별 비밀번호)를 읽을 수 없어서 셸 스크립트로 둔다

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
CREATE SCHEMA IF NOT EXISTS account;
CREATE SCHEMA IF NOT EXISTS subscription;
CREATE SCHEMA IF NOT EXISTS allocation;
EOSQL
