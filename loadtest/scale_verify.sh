#!/usr/bin/env bash
# 수평 확장 검증 (인스턴스 2개, 락 적용 전후). 저장소 루트에서 실행한다. 집계, 판정은 scale_report.py가 한다 (손 계산 없음).
#
# 사용
#   bash loadtest/scale_verify.sh v1 before|after          부하 없는 60초 동안 쿼리 실행 횟수 (스케줄러 실행 횟수)
#   bash loadtest/scale_verify.sh v2                        적용 후, 락 표를 120초 동안 1초마다 조회 (동시 실행 판정)
#   bash loadtest/scale_verify.sh v3 <단계> <회차>          S0 1,000건 → 중복 발행, 정합성, 성능 (회차 0은 워밍업, 1-9는 측정)
#   python3 loadtest/scale_report.py                        요약.md 생성
#
# 단계: before(락 적용 전, 2개), after(적용 후, 2개), single(적용 후 같은 커밋, 1개), rescale(적용 후 같은 커밋, 다시 2개)
#   성능 비교는 같은 커밋에서 after → single → rescale 순서로 측정한다 (2개 → 1개 → 2개, 순서 효과 확인)
#   compose 구성을 변경한 직후에는 회차 0(워밍업)을 먼저 실행한다. 보고서는 회차 0을 집계하지 않는다
#
# 환경변수: OUT_ROOT(결과 폴더, 기본 loadtest/results/scale-verify), V1_SECONDS(60), V2_SECONDS(120), DRAIN_TIMEOUT(600),
#          COMPOSE(기본: single은 "docker-compose -f docker-compose.yml -f compose.perf.yml", 그 외는 compose.scale.yml 추가)
# 전제: compose.perf.yml 포함 실행 (pg_stat_statements), 회원 시드와 토큰(loadtest/data/users.csv) 준비 (LOADTEST 3-2)
set -euo pipefail

OUT_ROOT="${OUT_ROOT:-loadtest/results/scale-verify}"
COMPOSE_SET="${COMPOSE:-}"
TOPICS="margin.reserve-requested margin.reserved margin.reserve-failed margin.void-requested margin.voided"

die() { echo "오류: $*" >&2; exit 1; }
[ -f compose.scale.yml ] || die "저장소 루트에서 실행한다"

# 슈퍼유저로 조회. 결과는 구분자 | , 머리글 없음
q() { docker exec -i ipo-postgres psql -U postgres -d ipo -v ON_ERROR_STOP=1 -AtF'|' -c "$1"; }

phase_dir() {
  case "$1" in before|after|single|rescale) ;; *) die "단계는 before, after, single, rescale: $1" ;; esac
  local d="$OUT_ROOT/$1"; mkdir -p "$d"; echo "$d"
}

# 단계에 해당하는 compose 명령을 COMPOSE에 설정한다 (환경변수로 지정하면 그 값 사용)
set_compose() {
  if [ -n "$COMPOSE_SET" ]; then COMPOSE="$COMPOSE_SET"
  elif [ "$1" = "single" ]; then COMPOSE="docker-compose -f docker-compose.yml -f compose.perf.yml"
  else COMPOSE="docker-compose -f docker-compose.yml -f compose.perf.yml -f compose.scale.yml"; fi
}

# 실행 중인 계좌 서비스 인스턴스 수가 단계와 일치하는지 확인한다 (다른 구성으로 측정해 재측정하는 것을 방지)
check_instances() {
  local want=2 got
  [ "$1" = "single" ] && want=1
  got="$(docker ps --format '{{.Names}}' | grep -cE '(^ipo-account-service$|^ipo-subscription-account-service-[0-9]+$)' || true)"
  [ "$got" = "$want" ] || die "계좌 서비스 인스턴스 ${got}개 실행 중, 단계 $1은 ${want}개 필요"
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
    echo "ledger_lock_at_least_for=$(grep -E '^LEDGER_RECONCILIATION_LOCK_AT_LEAST_FOR=' .env 2>/dev/null | cut -d= -f2- || true)"
    echo "== 컨테이너"
    $COMPOSE ps --format '{{.Name}} {{.Status}}' 2>/dev/null || $COMPOSE ps
  } > "$d/env.txt"
}

v1() {
  local d; d="$(phase_dir "$1")"
  set_compose "$1"; check_instances "$1"; check_no_close
  record_env "$d"
  local secs="${V1_SECONDS:-60}"
  # 확장은 DB마다 한 번 생성해야 한다 (compose.perf.yml은 라이브러리만 올림. slow-queries.sql과 같은 방식)
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
  set_compose after; check_instances after; check_no_close
  local secs="${V2_SECONDS:-120}" i sch
  # 배정은 #177 전에는 shedlock 표가 없을 수 있어, 표가 있는 스키마만 조회한다. 그 외 조회 오류는 회차를 중단한다
  local schemas="" raw="$d/v2-shedlock.raw"
  # 재측정이면 이전 완료 결과를 먼저 삭제한다. 조회 실패로 중단돼도 이전 결과가 새 측정처럼 집계되지 않는다 (v2-seconds.txt가 완료 표시)
  rm -f "$d/v2-shedlock.txt" "$d/v2-seconds.txt"
  for sch in subscription account allocation; do
    if [ "$(q "SELECT to_regclass('$sch.shedlock') IS NOT NULL;")" = "t" ]; then schemas="$schemas $sch"; fi
  done
  [ -n "$schemas" ] || die "shedlock 표가 있는 스키마가 없음 (락 적용 후 커밋으로 실행했는지 확인)"
  echo "V2: ${secs}초 동안 1초마다 락 표 조회 (스키마:$schemas)"
  : > "$raw"
  for i in $(seq "$secs"); do
    for sch in $schemas; do
      q "SELECT '$sch', name, locked_by, locked_at, lock_until FROM $sch.shedlock" >> "$raw" || die "락 표 조회 실패: $sch"
    done
    sleep 1
  done
  sort -u "$raw" > "$d/v2-shedlock.txt" && rm -f "$raw"
  echo "$secs" > "$d/v2-seconds.txt"
  echo "V2 완료: $d/v2-shedlock.txt ($(wc -l < "$d/v2-shedlock.txt")행)"
}

# 토픽별 파티션 오프셋 합계. 조회 실패나 빈 결과를 0으로 기록하면 중복 발행이 틀리게 계산되므로 회차를 중단한다
offsets() {
  local t out
  for t in $TOPICS; do
    out="$(docker exec ipo-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:29092 --topic "$t")" || die "오프셋 조회 실패: $t"
    echo "$out" | grep -qE "^$t:[0-9]+:[0-9]+$" || die "오프셋 결과 형식이 다름: $t ($out)"
    echo "$t $(echo "$out" | awk -F: '{s+=$3} END {print s}')"
  done
}

# 전체 Consumer 그룹 랙 합계 (LAG 열, 오프셋이 없는 파티션은 '-'라 제외). 조회 실패는 회차 중단
consumer_lag() {
  local out
  out="$(docker exec ipo-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 --describe --all-groups 2>/dev/null)" || die "Consumer 그룹 조회 실패"
  echo "$out" | awk '$1=="GROUP" {for (i=1; i<=NF; i++) if ($i=="LAG") c=i; next} c && $c ~ /^[0-9]+$/ {s+=$c} END {print s+0}'
}

# 서비스 DB의 마지막 처리 시각 (UTC): 청약 갱신, Outbox 발행, 이벤트 처리 기록 중 최댓값. 이전 회차의 비동기 처리가 종료된 시각으로 사용
last_activity() {
  q "SELECT to_char(greatest(
       (SELECT max(updated_at) FROM subscription.subscription),
       (SELECT max(published_at) FROM subscription.outbox), (SELECT max(published_at) FROM account.outbox),
       (SELECT max(processed_at) FROM subscription.processed_event), (SELECT max(processed_at) FROM account.processed_event))
     AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"');"
}

# 측정 중 공모주가 마감되면 배정, 정산, 환불 처리가 V1 쿼리 집계와 성능 구간에 포함되므로, 10분 안에 마감되는 공모주나 마감 처리 중 공모주가 있으면 중단한다
check_no_close() {
  local soon closing
  soon="$(q "SELECT count(*) FROM subscription.ipo WHERE status = 'OPEN' AND subscription_end_at < now() + interval '10 minutes';")"
  closing="$(q "SELECT count(*) FROM subscription.ipo WHERE status = 'CLOSED';")"
  [ "$soon" = "0" ] && [ "$closing" = "0" ] || die "10분 안에 마감되는 공모주 ${soon}개, 마감 처리 중 공모주 ${closing}개. 마감, 배정 완료 후 측정"
}

unpublished() {
  q "SELECT (SELECT count(*) FROM account.outbox WHERE published_at IS NULL) + (SELECT count(*) FROM subscription.outbox WHERE published_at IS NULL);"
}

v3() {
  local d; d="$(phase_dir "$1")"
  local n="${2:-}"; [[ "$n" =~ ^[0-9]$ ]] || die "회차는 0(워밍업), 1-9: $n"
  local run="scale-$1-r$n" p="$d/v3-r$n"
  set_compose "$1"; check_instances "$1"; check_no_close
  [ -f "$d/env.txt" ] || record_env "$d"
  # 재실행이면 이전 완료 기록을 먼저 삭제하고 진행 중 표시를 생성한다. 이후 단계가 실패해도 이전 회차 결과가 집계되지 않고 미완료로 표시된다
  local m="$p-meta.tmp"
  rm -f "$p-meta.txt"
  echo "started=1" > "$m"
  # 단계 env.txt는 처음 한 번만 기록되므로, 성능 비교의 같은 커밋 판정에는 회차별 커밋을 사용한다
  echo "commit=$(git rev-parse HEAD)" >> "$m"

  # 1. 이전 회차의 미발행 행이 남아 있으면 이번 회차 집계에 포함되므로 0건까지 대기
  local w=0
  until [ "$(unpublished)" = "0" ]; do
    w=$((w + 1)); [ "$w" -le 60 ] || die "미발행 Outbox가 60초 동안 0건이 되지 않음 (incident-response.md SQL 6 확인)"
    sleep 1
  done

  # 이전 회차의 비동기 처리(중복 메시지 소비 포함)가 남아 있으면 이번 성능 구간에 포함되므로 Consumer 랙 0까지 대기
  local lag; w=0
  until lag="$(consumer_lag)" && [ "$lag" = "0" ]; do
    w=$((w + 1)); [ "$w" -le 60 ] || die "Consumer 랙이 60초 동안 0이 되지 않음 (현재 ${lag})"
    sleep 1
  done
  echo "start_lag=$lag" >> "$m"; echo "prev_activity_end=$(last_activity)" >> "$m"

  # 2. 새 공모주, 시작 시각 (DB 시각, UTC)
  local ipo t0
  ipo="$(bash loadtest/seed/new_ipo.sh | tail -1)"
  [[ "$ipo" =~ ^[0-9a-f-]{36}$ ]] || die "공모주 ID를 받지 못함: $ipo"
  # 마이크로초까지 기록한다. 초 단위로 잘리면 시작 직전 1초 안에 생성된 Outbox 행도 집계되어 토픽 차이가 음수로 계산될 수 있다
  t0="$(q "SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"');")"
  # 진행 중 기록은 임시 파일에 두고, 모든 파일을 저장한 후에만 meta.txt로 공개한다 (중간 실패 회차가 집계에 포함되지 않게)
  echo "ipo=$ipo" >> "$m"; echo "t0=$t0" >> "$m"; echo "run=$run" >> "$m"

  # 3. 오프셋, 부하 (종료 코드 99는 임계값 위반이라 측정은 계속한다)
  offsets > "$p-offsets-before.txt"
  local code=0
  bash loadtest/run-k6.sh s0 --ipo "$ipo" --run-id "$run" || code=$?
  echo "k6_exit=$code" >> "$m"

  # 4. 처리 완료 대기: 처리 중 청약 0건, 미발행 Outbox 0건이 5초 간격으로 2번 연속
  local ok=0 waited=0 pend unpub
  while [ "$ok" -lt 2 ]; do
    pend="$(q "SELECT count(*) FROM subscription.subscription WHERE ipo_id = '$ipo' AND status IN ('PENDING', 'COMPENSATING', 'CANCEL_REQUESTED');")"
    unpub="$(unpublished)"
    if [ "$pend" = "0" ] && [ "$unpub" = "0" ]; then ok=$((ok + 1)); else ok=0; fi
    waited=$((waited + 5)); [ "$waited" -le "${DRAIN_TIMEOUT:-600}" ] || { echo "drain_timeout=1 pending=$pend unpublished=$unpub" >> "$m"; break; }
    sleep 5
  done
  # 다른 인스턴스가 같은 행을 늦게 보낸 중복까지 포함하려고 10초 더 대기
  sleep 10
  echo "drain_seconds=$waited" >> "$m"

  # 5. 오프셋, 같은 시간대 Outbox 행 수
  offsets > "$p-offsets-after.txt"
  q "SELECT topic, count(*) FROM (SELECT topic FROM account.outbox WHERE created_at >= '$t0'
     UNION ALL SELECT topic FROM subscription.outbox WHERE created_at >= '$t0') o GROUP BY topic ORDER BY topic;" > "$p-outbox.txt"

  # 접수 → 확정 시간 p95 (초, 확정 청약 기준. verify.sql 9번과 같은 계산)
  q "SELECT round(percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM confirmed_at - received_at))::numeric, 3)
     FROM subscription.subscription WHERE ipo_id = '$ipo' AND status = 'CONFIRMED';" > "$p-confirm-p95.txt"

  # 6. 정합성: verify.sql 22종 원본 + 판정용 건수 (verify.sql과 같은 조건을 count로)
  (cd loadtest && bash verify.sh --ipo "$ipo" --run-id "$run") > /dev/null 2>&1 || echo "verify_sh_exit=$?" >> "$m"
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

  # 7. 계좌 서비스 중복 수신 로그 (두 인스턴스 합계). 로그 조회 실패를 0건으로 기록하지 않게 조회와 집계를 분리한다
  local logs="$p-account.log" dup
  $COMPOSE logs --no-color --since "$t0" account-service > "$logs" 2>/dev/null || die "계좌 서비스 로그 조회 실패"
  # grep -c는 0건이면 종료 코드 1이라 0건과 오류(2)를 구분한다
  dup="$(grep -c "중복 수신, 건너뜀" "$logs")" || [ $? -eq 1 ] || die "중복 수신 로그 집계 실패"
  echo "$dup" > "$p-dup-log.txt" && rm -f "$logs"

  # 8. 회차 보고서 (k6 요약 + 같은 시간대 Prometheus 지표: Hikari 활성, 대기, CPU). 실패하면 성능 비교에서 제외한다
  python3 loadtest/report.py --run-id "$run" > /dev/null 2>&1 || echo "report_exit=$?" >> "$m"

  echo "complete=1" >> "$m"
  mv "$m" "$p-meta.txt"
  echo "V3 $1 r$n 완료: $p-*.txt (k6 원본 loadtest/results/$run/)"
}

case "${1:-}" in
  v1) v1 "${2:-}" ;;
  v2) v2 ;;
  v3) v3 "${2:-}" "${3:-}" ;;
  *) sed -n '2,16p' "$0"; exit 1 ;;
esac
