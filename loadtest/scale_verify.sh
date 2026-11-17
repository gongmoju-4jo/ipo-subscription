#!/usr/bin/env bash
# 수평 확장 검증 (인스턴스 2개, 락 적용 전후). 저장소 루트에서 실행한다. 집계, 판정은 scale_report.py가 한다 (손 계산 없음).
#
# 사용
#   bash loadtest/scale_verify.sh v1 before|after          부하 없는 60초 동안 쿼리 실행 횟수 (스케줄러 실행 횟수)
#   bash loadtest/scale_verify.sh v2                        적용 후, 락 표를 120초 동안 1초마다 조회 (동시 실행 판정)
#   bash loadtest/scale_verify.sh v3 before|after <회차>     S0 1,000건 → 중복 발행, 정합성 (회차는 1, 2, 3)
#   python3 loadtest/scale_report.py                        요약.md 생성
#
# 환경변수: OUT_ROOT(결과 폴더, 기본 loadtest/results/scale-verify), V1_SECONDS(60), V2_SECONDS(120), DRAIN_TIMEOUT(600),
#          COMPOSE(기본 "docker-compose -f docker-compose.yml -f compose.perf.yml -f compose.scale.yml")
# 전제: compose.perf.yml 포함 실행 (pg_stat_statements), 회원 시드와 토큰(loadtest/data/users.csv) 준비 (LOADTEST 3-2)
set -euo pipefail

OUT_ROOT="${OUT_ROOT:-loadtest/results/scale-verify}"
COMPOSE="${COMPOSE:-docker-compose -f docker-compose.yml -f compose.perf.yml -f compose.scale.yml}"
TOPICS="margin.reserve-requested margin.reserved margin.reserve-failed margin.void-requested margin.voided"

die() { echo "오류: $*" >&2; exit 1; }
[ -f compose.scale.yml ] || die "저장소 루트에서 실행한다"

# 슈퍼유저로 조회. 결과는 구분자 | , 머리글 없음
q() { docker exec -i ipo-postgres psql -U postgres -d ipo -v ON_ERROR_STOP=1 -AtF'|' -c "$1"; }

phase_dir() {
  case "$1" in before|after) ;; *) die "단계는 before 또는 after: $1" ;; esac
  local d="$OUT_ROOT/$1"; mkdir -p "$d"; echo "$d"
}

record_env() {
  local d="$1"
  {
    echo "commit=$(git rev-parse HEAD)"
    echo "date=$(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "cpu=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo | cut -d: -f2)"
    echo "host_cpus=$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
    echo "host_mem_bytes=$(sysctl -n hw.memsize 2>/dev/null || free -b | awk '/Mem/ {print $2}')"
    echo "docker=$(docker info --format 'cpus={{.NCPU}} mem_bytes={{.MemTotal}}')"
    echo "ledger_cron=$(grep -E '^LEDGER_RECONCILIATION_CRON=' .env 2>/dev/null | cut -d= -f2- || true)"
    echo "== 컨테이너"
    $COMPOSE ps --format '{{.Name}} {{.Status}}' 2>/dev/null || $COMPOSE ps
  } > "$d/env.txt"
}

v1() {
  local d; d="$(phase_dir "$1")"
  record_env "$d"
  local secs="${V1_SECONDS:-60}"
  # 확장은 DB마다 한 번 만들어야 한다 (compose.perf.yml은 라이브러리만 올림. slow-queries.sql과 같은 방식)
  q "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;" > /dev/null
  q "SELECT pg_stat_statements_reset();" > /dev/null
  echo "V1: ${secs}초 동안 부하 없이 대기 (스케줄러 쿼리만 집계)"
  sleep "$secs"
  # 계정, 실행 횟수, 쿼리(한 줄). 판정은 scale_report.py가 쿼리 글자로 스케줄러를 구분해서 한다
  q "SELECT r.rolname, s.calls, regexp_replace(s.query, '\s+', ' ', 'g')
     FROM pg_stat_statements s JOIN pg_roles r ON r.oid = s.userid
     WHERE r.rolname IN ('account_app', 'subscription_app', 'allocation_app')
     ORDER BY r.rolname, s.calls DESC;" > "$d/v1-raw.txt"
  echo "$secs" > "$d/v1-seconds.txt"
  echo "V1 완료: $d/v1-raw.txt ($(wc -l < "$d/v1-raw.txt")개 쿼리)"
}

v2() {
  local d; d="$(phase_dir after)"
  local secs="${V2_SECONDS:-120}" i sch
  echo "V2: ${secs}초 동안 1초마다 락 표 조회"
  for i in $(seq "$secs"); do
    for sch in subscription account allocation; do
      # 배정은 #177 전에는 표가 없을 수 있어 실패를 무시한다
      q "SELECT '$sch', name, locked_by, locked_at, lock_until FROM $sch.shedlock" 2>/dev/null || true
    done
    sleep 1
  done | sort -u > "$d/v2-shedlock.txt"
  echo "$secs" > "$d/v2-seconds.txt"
  echo "V2 완료: $d/v2-shedlock.txt ($(wc -l < "$d/v2-shedlock.txt")행)"
}

offsets() {
  local t
  for t in $TOPICS; do
    echo "$t $(docker exec ipo-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:29092 --topic "$t" | awk -F: '{s+=$3} END {print s+0}')"
  done
}

unpublished() {
  q "SELECT (SELECT count(*) FROM account.outbox WHERE published_at IS NULL) + (SELECT count(*) FROM subscription.outbox WHERE published_at IS NULL);"
}

v3() {
  local d; d="$(phase_dir "$1")"
  local n="${2:-}"; [[ "$n" =~ ^[1-9]$ ]] || die "회차는 1-9: $n"
  local run="scale-$1-r$n" p="$d/v3-r$n"
  [ -f "$d/env.txt" ] || record_env "$d"

  # 1. 이전 회차의 미발행 행이 남아 있으면 이번 회차 집계에 섞이므로 0건까지 대기
  local w=0
  until [ "$(unpublished)" = "0" ]; do
    w=$((w + 1)); [ "$w" -le 60 ] || die "미발행 Outbox가 60초 넘게 남아 있음 (incident-response.md SQL 6 확인)"
    sleep 1
  done

  # 2. 새 공모주, 시작 시각 (DB 시각, UTC)
  local ipo t0
  ipo="$(bash loadtest/seed/new_ipo.sh | tail -1)"
  [[ "$ipo" =~ ^[0-9a-f-]{36}$ ]] || die "공모주 ID를 받지 못함: $ipo"
  t0="$(q "SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"');")"
  echo "ipo=$ipo" > "$p-meta.txt"; echo "t0=$t0" >> "$p-meta.txt"; echo "run=$run" >> "$p-meta.txt"

  # 3. 오프셋, 부하 (종료 코드 99는 임계값 위반이라 측정은 계속한다)
  offsets > "$p-offsets-before.txt"
  local code=0
  bash loadtest/run-k6.sh s0 --ipo "$ipo" --run-id "$run" || code=$?
  echo "k6_exit=$code" >> "$p-meta.txt"

  # 4. 처리 완료 대기: 처리 중 청약 0건, 미발행 Outbox 0건이 5초 간격으로 2번 연속
  local ok=0 waited=0 pend unpub
  while [ "$ok" -lt 2 ]; do
    pend="$(q "SELECT count(*) FROM subscription.subscription WHERE ipo_id = '$ipo' AND status IN ('PENDING', 'COMPENSATING', 'CANCEL_REQUESTED');")"
    unpub="$(unpublished)"
    if [ "$pend" = "0" ] && [ "$unpub" = "0" ]; then ok=$((ok + 1)); else ok=0; fi
    waited=$((waited + 5)); [ "$waited" -le "${DRAIN_TIMEOUT:-600}" ] || { echo "drain_timeout=1 pending=$pend unpublished=$unpub" >> "$p-meta.txt"; break; }
    sleep 5
  done
  # 다른 인스턴스가 같은 행을 늦게 보낸 중복까지 포함하려고 10초 더 대기
  sleep 10
  echo "drain_seconds=$waited" >> "$p-meta.txt"

  # 5. 오프셋, 같은 시간대 Outbox 행 수
  offsets > "$p-offsets-after.txt"
  q "SELECT topic, count(*) FROM (SELECT topic FROM account.outbox WHERE created_at >= '$t0'
     UNION ALL SELECT topic FROM subscription.outbox WHERE created_at >= '$t0') o GROUP BY topic ORDER BY topic;" > "$p-outbox.txt"

  # 6. 정합성: verify.sql 22종 원본 + 판정용 건수 (verify.sql과 같은 조건을 count로)
  (cd loadtest && bash verify.sh --ipo "$ipo" --run-id "$run") > /dev/null 2>&1 || echo "verify_sh_exit=$?" >> "$p-meta.txt"
  q "SELECT 'v02_margin_balance', count(*) FROM (SELECT a.account_id FROM account.account a LEFT JOIN account.margin m ON m.account_id = a.account_id AND m.status = 'PENDING' GROUP BY a.account_id, a.margin_balance HAVING a.margin_balance <> COALESCE(SUM(m.amount), 0)) x
     UNION ALL SELECT 'v03_last_ledger', count(*) FROM (WITH last AS (SELECT DISTINCT ON (account_id) account_id, cash_balance_after, margin_balance_after FROM account.ledger_entry ORDER BY account_id, created_at DESC, ledger_entry_id DESC) SELECT a.account_id FROM account.account a JOIN last l USING (account_id) WHERE a.cash_balance <> l.cash_balance_after OR a.margin_balance <> l.margin_balance_after) x
     UNION ALL SELECT 'v04_confirmed_minus_pending_margin', (SELECT count(*) FROM subscription.subscription WHERE ipo_id = '$ipo' AND status = 'CONFIRMED') - (SELECT count(*) FROM account.margin WHERE ipo_id = '$ipo' AND status = 'PENDING')
     UNION ALL SELECT 'v05_amount_margin_id', count(*) FROM subscription.subscription s JOIN account.margin m ON m.subscription_id = s.subscription_id WHERE s.ipo_id = '$ipo' AND (s.margin_amount <> m.amount OR (NOT (s.status = 'CANCELLED' AND m.status = 'VOIDED') AND s.margin_id IS DISTINCT FROM m.margin_id))
     UNION ALL SELECT 'v06_rejected_with_margin', count(*) FROM subscription.subscription s JOIN account.margin m ON m.subscription_id = s.subscription_id WHERE s.ipo_id = '$ipo' AND s.status = 'REJECTED'
     UNION ALL SELECT 'v11_pending_ledger_once', count(*) FROM (SELECT m.subscription_id FROM account.margin m LEFT JOIN account.ledger_entry l ON l.margin_id = m.margin_id AND l.type = 'MARGIN_PENDING' WHERE m.ipo_id = '$ipo' GROUP BY m.subscription_id, m.amount HAVING count(l.ledger_entry_id) <> 1 OR COALESCE(SUM(l.amount), 0) <> m.amount) x
     UNION ALL SELECT 'v16_dup_request_id', count(*) FROM (SELECT request_id FROM subscription.subscription WHERE ipo_id = '$ipo' GROUP BY request_id HAVING count(*) > 1) x
     UNION ALL SELECT 'v17_dup_margin', count(*) FROM (SELECT subscription_id FROM account.margin WHERE ipo_id = '$ipo' GROUP BY subscription_id HAVING count(*) > 1) x
     UNION ALL SELECT 'v18_dup_valid_subscription', count(*) FROM (SELECT user_id FROM subscription.subscription WHERE ipo_id = '$ipo' AND status NOT IN ('REJECTED', 'CANCELLED') GROUP BY user_id HAVING count(*) > 1) x
     UNION ALL SELECT 'unfinished', count(*) FROM subscription.subscription WHERE ipo_id = '$ipo' AND status IN ('PENDING', 'COMPENSATING', 'CANCEL_REQUESTED');" > "$p-integrity.txt"
  q "SELECT status, count(*) FROM subscription.subscription WHERE ipo_id = '$ipo' GROUP BY status ORDER BY status;" > "$p-status.txt"

  # 7. 계좌 서비스 중복 수신 로그 (두 인스턴스 합계)
  { $COMPOSE logs --no-color --since "$t0" account-service 2>/dev/null | grep -c "중복 수신, 건너뜀" || true; } > "$p-dup-log.txt"

  echo "V3 $1 r$n 완료: $p-*.txt (k6 원본 loadtest/results/$run/)"
}

case "${1:-}" in
  v1) v1 "${2:-}" ;;
  v2) v2 ;;
  v3) v3 "${2:-}" "${3:-}" ;;
  *) sed -n '2,13p' "$0"; exit 1 ;;
esac
