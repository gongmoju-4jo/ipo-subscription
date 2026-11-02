-- 슬로우 쿼리 확인 (compose.perf.yml로 띄웠을 때만 동작: pg_stat_statements를 미리 읽어 들이도록 설정돼 있어야 한다).
-- 슈퍼유저로 접속해서 실행한다.
--   docker exec -i ipo-postgres psql -U postgres -d ipo -f - < loadtest/sql/slow-queries.sql
-- 측정 직전에 통계를 비우려면 reset을 준다:
--   docker exec -i ipo-postgres psql -U postgres -d ipo -v reset=1 -f - < loadtest/sql/slow-queries.sql
-- 100ms 이상 걸린 개별 쿼리는 PostgreSQL 로그에 남는다 (docker logs ipo-postgres).
\pset pager off
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
\if :{?reset}
SELECT pg_stat_statements_reset();
\echo '통계를 비웠습니다. 측정 후 reset 없이 다시 실행해서 결과를 확인하세요.'
\else
\echo '== 총 실행 시간 상위 10개'
SELECT calls, round(total_exec_time::numeric, 1) AS total_ms, round(mean_exec_time::numeric, 2) AS mean_ms,
       round(max_exec_time::numeric, 1) AS max_ms, rows, left(regexp_replace(query, '\s+', ' ', 'g'), 100) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY total_exec_time DESC LIMIT 10;

\echo '== 평균 실행 시간 상위 10개 (5회 이상 호출)'
SELECT calls, round(mean_exec_time::numeric, 2) AS mean_ms, round(max_exec_time::numeric, 1) AS max_ms,
       left(regexp_replace(query, '\s+', ' ', 'g'), 100) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) AND calls >= 5
ORDER BY mean_exec_time DESC LIMIT 10;
\endif
