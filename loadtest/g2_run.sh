#!/usr/bin/env bash
# G2 대량 환불(마감 → 배정 → 정산 → 환불) 회차 실행기. 스냅샷 폴더를 받은 순서대로 한 회차씩 돌린다.
#
# 회차 하나: 스냅샷 복원 → 점검 → 워밍업 공모주 W 마감, 환불 완료 대기 → GAP초 → 측정 전 기록
#            → 측정 공모주 M 마감(종료 시각 UPDATE), 환불 완료 대기 (그동안 docker stats 기록) → 수집 → 검증 → 판정
#
# 사용: bash loadtest/g2_run.sh [--batch 이름] [--session 이름] <스냅샷 폴더> [<스냅샷 폴더> ...]
#   스냅샷 폴더: loadtest/data/ 아래 이름(g2-A-1k) 또는 경로. 안에 ipo-baseline.dump, ipo-baseline.meta(baseline.sh save),
#                ipo-ids.txt(seed/g2_ipo.sh --out: "CASE A|B", "W1 <id>", ..., "M <id>")가 있어야 한다
#   순서(ABBA 등)는 인자 순서 그대로다. 같은 폴더를 여러 번 줘도 된다 (회차마다 복원)
#   --batch: 결과 폴더 이름 (loadtest/results/g2-<batch>/). 기본 시각. 같은 batch로 다시 실행하면 manifest.csv에 이어 쓴다
#   --session: manifest.csv의 세션 열 (예: S-1). 기본 1
#   --idempotency: 회차마다 판정 뒤에 중복 수신 멱등성 검사를 한다 (계좌 서비스가 배정 결과를 전부 다시 받음. 회차당 약 1 ~ 2분 더)
# 측정 전에 할 것 (자동으로 하지 않음): WSL 시계 점프 점검, 다른 무거운 프로그램 끄기, compose.perf.yml로 띄우기
#
# 결과: loadtest/results/g2-<batch>/
#   manifest.csv  회차마다 한 줄 (session,seq,snapshot,case,n,started,ended,status,integrity,total_s,throughput,idempotency,reasons)
#   env.txt       git, 컨테이너 jar sha256, 자원 상한, 설정 / batch.log 전체 로그
#   <seq>-<스냅샷 이름>/  log.txt, result.txt(W, M 측정값 key=value), checks.txt(판정 근거), verify-M.txt, verify-W<i>.txt,
#                         bucket-publish.csv, bucket-settle.csv(1초 버킷), docker-stats.raw(M 처리 중), tables-before.txt, processed.txt,
#                         idempotency.txt(--idempotency)
# 판정 (회차마다)
#   status: VALID / INVALID(시험이 잘못됨: 정산 수, REFUNDED 수 ≠ 확정 수, DLT 증가, 측정 끝 미발행, 측정 중 다른 공모주 마감,
#           처리 중 청약이 남은 스냅샷) / UNMEASURABLE(진행 정지 STALL_S초, 상한 MAX_S초, 컨테이너 재시작, OOM, W가 끝나지 않음)
#   integrity: PASS / FAIL(verify.sql 위반, 케이스 B 0주 경로 불일치, 멱등성 위반. W 포함) / -(측정 불가로 검증 못 함)
#   idempotency: PASS / FAIL / ERROR(검사를 끝내지 못함) / -(--idempotency 없음, 측정 불가)
# 환경변수: GAP(30), STALL_S(600), MAX_S(7200), W_MAX_S(3600), SETTLE_S(20, healthy 뒤 대기), STATS_INTERVAL(5), BASELINE_ROOT(loadtest/data), IDEM_MAX_S(600, 다시 받기 대기 상한)
# 종료 코드: 0 모든 회차 VALID, PASS / 1 사용법 / 2 INVALID 또는 UNMEASURABLE 회차 있음 / 3 정합성 FAIL 회차 있음
#           / 4 복원, 기동, 조회 실패로 배치 중단 / 12 잠금, 결과 폴더
set -uo pipefail
export MSYS_NO_PATHCONV=1 PYTHONUTF8=1

G2_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 공통 함수 (say, die, pg, pg_int, kafka_dlt_total, outbox_unpublished, wait_healthy, stats_start/stop, git_repo, sha256_file)
# shellcheck disable=SC1091
source "$G2_DIR/official.sh"

GAP="${GAP:-30}"; STALL_S="${STALL_S:-600}"; MAX_S="${MAX_S:-7200}"; W_MAX_S="${W_MAX_S:-3600}"; SETTLE_S="${SETTLE_S:-20}"
BASELINE_ROOT="${BASELINE_ROOT:-$G2_DIR/data}"
SERVICE_CONTAINERS="ipo-api-gateway ipo-account-service ipo-subscription-service ipo-allocation-service"
WATCH_CONTAINERS="ipo-postgres ipo-kafka ipo-eureka-server $SERVICE_CONTAINERS"
G2_LOCK="$G2_DIR/results/.g2.lock"; G2_LOCK_OWNED=""
SESSION=1; BATCH_DIR=""; SNAPS=(); IDEM=0
IDEM_GROUP="account-allocation-result"; IDEM_TOPIC="allocation.result"; IDEM_MAX_S="${IDEM_MAX_S:-600}"

# ---------- 인자 ----------
parse_args() {
  BATCH="$(date +%m%d-%H%M)"
  while [ $# -gt 0 ]; do
    case "$1" in
      --batch) [ $# -ge 2 ] || die 1 "--batch 값이 없다"; BATCH="$2"; shift 2 ;;
      --session) [ $# -ge 2 ] || die 1 "--session 값이 없다"; SESSION="$2"; shift 2 ;;
      --idempotency) IDEM=1; shift ;;
      -*) die 1 "알 수 없는 옵션: $1" ;;
      *) SNAPS+=("$1"); shift ;;
    esac
  done
  [ "${#SNAPS[@]}" -gt 0 ] || die 1 "스냅샷 폴더를 하나 이상 준다"
  case "$BATCH" in ''|*[!A-Za-z0-9._-]*) die 1 "--batch는 영문, 숫자, . _ -: $BATCH" ;; esac
  case "$SESSION" in ''|*[!A-Za-z0-9._-]*) die 1 "--session은 영문, 숫자, . _ -: $SESSION" ;; esac
  local v; for v in GAP STALL_S MAX_S W_MAX_S SETTLE_S IDEM_MAX_S; do is_uint "${!v}" || die 1 "${v}는 0 이상의 정수: ${!v}"; done
}

snap_path() { case "$1" in */*) echo "$1" ;; *) echo "$BASELINE_ROOT/$1" ;; esac; }

# ipo-ids.txt → 전역 CASE_, W_IDS(공백 구분), M_ID. 형식이 틀리면 실패
read_ids() {
  local f="$1/ipo-ids.txt" k v
  CASE_=""; W_IDS=""; M_ID=""
  [ -f "$f" ] || { echo "ipo-ids.txt 없음: $f"; return 1; }
  while read -r k v _; do
    k="${k%$'\r'}"; v="${v%$'\r'}"
    case "$k" in
      '') ;;
      CASE) CASE_="$v" ;;
      W[1-9]) is_uuid "$v" || { echo "$k ID 형식: $v"; return 1; }; W_IDS="${W_IDS:+$W_IDS }$v" ;;
      M) is_uuid "$v" || { echo "M ID 형식: $v"; return 1; }; [ -z "$M_ID" ] || { echo "M이 두 줄"; return 1; }; M_ID="$v" ;;
      *) echo "모르는 줄: $k"; return 1 ;;
    esac
  done < "$f"
  case "$CASE_" in A|B) ;; *) echo "CASE는 A 또는 B: ${CASE_:-없음}"; return 1 ;; esac
  [ -n "$M_ID" ] || { echo "M 줄이 없다"; return 1; }
}

check_snaps() { # 시작 전에 모든 스냅샷 폴더를 검사한다 (회차 중간에 멈추지 않게)
  local s p err=0 msg
  for s in "${SNAPS[@]}"; do
    p="$(snap_path "$s")"
    [ -f "$p/ipo-baseline.dump" ] && [ -f "$p/ipo-baseline.meta" ] || { say "!! 스냅샷 없음: $p"; err=1; continue; }
    grep -q '^allow_subscriptions ' "$p/ipo-baseline.meta" || { say "!! 확정 청약 스냅샷이 아님 (ALLOW_SUBSCRIPTIONS=1 save로 만든다): $p"; err=1; }
    msg="$(read_ids "$p")" || { say "!! $p: $msg"; err=1; }
  done
  return "$err"
}

# ---------- DB 조회 ----------
q() { pg "$1" | tr -d '\r'; }

# 처리 중 청약, outbox 미발행, 종료 시각이 지난 OPEN 공모주 (baseline.sh held_line과 같은 조건)
held_counts() {
  q "select (select count(*) from subscription.subscription where status in ('PENDING','COMPENSATING','CANCEL_REQUESTED'))
       || ' ' || ((select count(*) from account.outbox where published_at is null) + (select count(*) from subscription.outbox where published_at is null) + (select count(*) from allocation.outbox where published_at is null))
       || ' ' || (select count(*) from subscription.ipo where status = 'OPEN' and subscription_end_at <= now())"
}

confirmed_count() { pg_int "select count(*) from subscription.subscription where ipo_id = '$1' and status = 'CONFIRMED'"; }

close_ipo() { # 종료 시각을 1초 전으로. 바꾼 시각(t_trigger)을 출력. OPEN이 아니면 실패
  local t
  t="$(q "update subscription.ipo set subscription_end_at = now() - interval '1 second' where ipo_id = '$1' and status = 'OPEN' returning to_char(clock_timestamp(), 'YYYY-MM-DD HH24:MI:SS.MS')")" || return 1
  [ -n "$t" ] || return 1
  echo "$t"
}

# 진행: "<settled> <refunded> <round 상태>"
progress() {
  q "select (select count(*) from account.margin where ipo_id = '$1' and settled_at is not null) || ' ' ||
            (select count(*) from subscription.subscription where ipo_id = '$1' and status = 'REFUNDED') || ' ' ||
            coalesce((select status from allocation.allocation_round where ipo_id = '$1'), '-')"
}

# 환불 완료(REFUNDED = 기대 수)까지 대기. 반환: 0 완료 / 1 진행 정지 / 2 상한 / 3 조회 연속 실패 / 4 배정 FAILED
# 진행 = 정산 수 + REFUNDED 수가 늘어나는 것. 배정 대기 중(아직 0)도 정지 시간에 들어간다
wait_refunded() { # 인자: ipo, 기대 수, 정지 초, 상한 초
  local ipo="$1" n="$2" stall="$3" max="$4" t0 now last=-1 lastchg p s r st fails=0
  t0="$(date +%s)"; lastchg="$t0"
  while :; do
    now="$(date +%s)"
    if p="$(progress "$ipo")" && [ -n "$p" ]; then
      fails=0
      read -r s r st <<< "$p"
      [ "$r" = "$n" ] && { say "  환불 완료 settled=$s refunded=$r round=$st ($((now - t0))초)"; return 0; }
      [ "$st" = FAILED ] && { say "  배정 FAILED settled=$s refunded=$r"; return 4; }
      [ $((s + r)) != "$last" ] && { last=$((s + r)); lastchg="$now"; }
    else
      fails=$((fails + 1)); [ "$fails" -ge 5 ] && { say "  진행 조회 연속 5번 실패"; return 3; }
    fi
    [ $((now - lastchg)) -ge "$stall" ] && { say "  진행 정지 ${stall}초 ($p)"; return 1; }
    [ $((now - t0)) -ge "$max" ] && { say "  상한 ${max}초 초과 ($p)"; return 2; }
    sleep 2
  done
}

# 측정값 한 줄 (key=value). 인자: ipo, t_trigger
collect() {
  q "with p as (select '$1'::uuid ipo),
  t0 as (select min(created_at) v from subscription.outbox, p where topic = 'ipo.closed' and aggregate_id = p.ipo),
  r as (select started_at, finished_at, lottery_seed, status from allocation.allocation_round, p where ipo_id = p.ipo),
  pub as (select max(published_at) v, count(*) n from allocation.outbox, p where topic = 'allocation.result' and payload->>'ipoId' = p.ipo::text),
  m as (select min(settled_at) s0, max(settled_at) s1, count(settled_at) n, count(*) filter (where status = 'VOIDED') voided, count(*) filter (where status = 'POSTED') posted from account.margin, p where ipo_id = p.ipo),
  t2 as (select max(processed_at) v, count(*) n from subscription.processed_event where topic = 'margin.settled' and processed_at >= (select v from t0)),
  s as (select count(*) filter (where status = 'REFUNDED') refunded, count(*) filter (where allocated_quantity = 0) zero, coalesce(sum(allocated_quantity), 0) qty from subscription.subscription, p where ipo_id = p.ipo),
  l as (select count(*) filter (where type = 'MARGIN_POST') post, count(*) filter (where type = 'REFUND') refund, count(*) filter (where type = 'MARGIN_VOID') void from account.ledger_entry e join account.margin mm using (margin_id), p where mm.ipo_id = p.ipo)
  select 'trigger=' || replace('$2', ' ', 'T') || ' T0=' || coalesce(to_char((select v from t0), 'YYYY-MM-DD\"T\"HH24:MI:SS.MS'), '-')
   || ' wait_s=' || coalesce(round(extract(epoch from (select started_at from r) - (select v from t0))::numeric, 3)::text, '-')
   || ' alloc_s=' || coalesce(round(extract(epoch from (select finished_at from r) - (select started_at from r))::numeric, 3)::text, '-')
   || ' publish_s=' || coalesce(round(extract(epoch from (select v from pub) - (select finished_at from r))::numeric, 3)::text, '-')
   || ' first_settle_s=' || coalesce(round(extract(epoch from (select s0 from m) - (select v from t0))::numeric, 3)::text, '-')
   || ' settle_s=' || coalesce(round(extract(epoch from (select s1 from m) - (select s0 from m))::numeric, 3)::text, '-')
   || ' mark_s=' || coalesce(round(extract(epoch from (select v from t2) - (select s1 from m))::numeric, 3)::text, '-')
   || ' total_s=' || coalesce(round(extract(epoch from (select s1 from m) - (select v from t0))::numeric, 3)::text, '-')
   || ' thr=' || coalesce(round(((select n from m) / nullif(extract(epoch from (select s1 from m) - (select v from t0)), 0))::numeric, 1)::text, '-')
   || ' policy_s=' || coalesce(round(extract(epoch from (select v from t0) - '$2'::timestamptz)::numeric, 3)::text, '-')
   || ' pub_n=' || (select n from pub) || ' settled=' || (select n from m) || ' refunded=' || (select refunded from s) || ' marked=' || (select n from t2)
   || ' posted=' || (select posted from m) || ' voided=' || (select voided from m) || ' zero=' || (select zero from s) || ' qty=' || (select qty from s)
   || ' l_post=' || (select post from l) || ' l_refund=' || (select refund from l) || ' l_void=' || (select void from l)
   || ' round=' || coalesce((select status from r), '-') || ' seed=' || coalesce((select lottery_seed::text from r), '-')"
}

kv() { printf '%s\n' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p" | head -1; } # key=value 줄에서 값 하나

# 측정 시작(첫 W 마감) 뒤에 W, M이 아닌 공모주가 마감된 수 (ipo.closed 발행 기준)
other_closed() { # 인자: 시작 시각, 제외할 ID들(공백)
  local ex="" id
  for id in $2; do ex="${ex:+$ex,}'$id'"; done
  pg_int "select count(*) from subscription.outbox where topic = 'ipo.closed' and created_at >= '$1'::timestamptz and aggregate_id not in ($ex)"
}

buckets() { # 1초 버킷 CSV: 인자 ipo, 출력 폴더
  { echo "second,published"
    q "select to_char(date_trunc('second', published_at), 'YYYY-MM-DD\"T\"HH24:MI:SS') || ',' || count(*) from allocation.outbox where topic = 'allocation.result' and payload->>'ipoId' = '$1' and published_at is not null group by date_trunc('second', published_at) order by 1"
  } > "$2/bucket-publish.csv" || return 1
  { echo "second,settled"
    q "select to_char(date_trunc('second', settled_at), 'YYYY-MM-DD\"T\"HH24:MI:SS') || ',' || count(*) from account.margin where ipo_id = '$1' and settled_at is not null group by date_trunc('second', settled_at) order by 1"
  } > "$2/bucket-settle.csv"
}

# 컨테이너 재시작 수, OOM: "<이름>=<RestartCount>:<OOMKilled>:<StartedAt>" 공백 구분
container_marks() {
  local c out=""
  for c in $WATCH_CONTAINERS; do out="$out $c=$(docker inspect -f '{{.RestartCount}}:{{.State.OOMKilled}}:{{.State.StartedAt}}' "$c" 2>/dev/null | tr -d '\r' || echo '?')"; done
  echo "${out# }"
}
# 두 표시를 비교해 바뀐 컨테이너를 출력 (없으면 빈 출력)
marks_diff() {
  local a b name
  for a in $1; do
    name="${a%%=*}"
    for b in $2; do [ "${b%%=*}" = "$name" ] && [ "$a" != "$b" ] && printf '%s ' "$name(${a#*=} -> ${b#*=})"; done
    case " $2 " in *" $name="*) ;; *) printf '%s ' "$name(사라짐)" ;; esac
  done
}

# verify.sql 출력(정렬 표 형식)에서 위반을 찾는다. 인자: 0행이어야 하는 절 번호들. 위반을 "13:2행" 꼴로 출력
# 반환: 0 검사함(위반은 출력으로) / 1 형식 이상(절이 없음, 행 수 줄이 없음)
verify_violations() {
  awk -v need="$1" '
    { sub(/\r$/, "") }
    /^== / { sec = $2; sub(/\.$/, "", sec); seen[sec] = 1; if ($0 ~ /건너뜀/) skipped[sec] = 1; next }
    /^\([0-9]+ rows?\)$/ { n = $0; gsub(/[^0-9]/, "", n); rows[sec] = n; next }
    sec == "8" && /^ *(acc|sub) *\| *[0-9]+ *$/ { split($0, a, "|"); v = a[2]; gsub(/ /, "", v); k = a[1]; gsub(/ /, "", k); if (v != "0") out = out " 8:" k "=" v; seen8 = 1 }
    END {
      if (seen["11~15"]) out = out " 11~15:건너뜀(배정 미완료)"
      m = split(need, w, " ")
      for (i = 1; i <= m; i++) {
        s = w[i]
        if (!(s in seen)) { bad = 1; continue }
        if (s in skipped) continue
        if (!(s in rows)) { bad = 1; continue }
        if (rows[s] != 0) out = out " " s ":" rows[s] "행"
      }
      if (!seen8) bad = 1
      sub(/^ /, "", out); print out
      exit bad
    }'
}

run_verify() { # 인자: ipo, 출력 파일, 1이면 전체 계좌 검사(2, 3번) 건너뜀
  local extra=()
  [ "$3" = 1 ] && extra=(-v skip_global=1)
  docker exec -i "$PG_CONTAINER" psql -U postgres -d ipo -v ON_ERROR_STOP=1 -v ipo="'$1'" ${extra[@]+"${extra[@]}"} -f - < "$DIR/sql/verify.sql" > "$2" 2>&1
}

# ---------- 중복 수신 멱등성 (--idempotency) ----------
# 배정 결과를 계좌 서비스가 다시 받아도 돈이 한 번만 움직이는지 본다. 회차 판정 뒤, 다음 복원 전에 그 DB에서 한다 (측정 시간에는 넣지 않는다)
# 계좌 서비스를 멈추고 account-allocation-result 그룹의 allocation.result 오프셋을 처음으로 되돌린 뒤 다시 띄운다
# → 이번 회차의 배정 결과(W, M)가 전부 다시 들어온다 (복원 때 Kafka를 비우므로 토픽에는 이번 회차 메시지만 있다)
kgroups() { docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --group "$IDEM_GROUP" "$@" 2>&1 | tr -d '\r'; }
# 표준입력: --describe --state 출력. 그룹 상태(Stable, Empty 등). 그룹 줄이 없으면 실패
parse_group_state() { awk -v g="$IDEM_GROUP" '$1 == g { print $(NF - 1); f = 1 } END { exit !f }'; }
# 표준입력: --reset-offsets --execute 출력. 이 토픽의 파티션이 하나 이상이고 NEW-OFFSET이 모두 0이면 파티션 수를 출력
parse_reset() { awk -v g="$IDEM_GROUP" -v t="$IDEM_TOPIC" '$1 == g && $2 == t && $3 ~ /^[0-9]+$/ { n++; if ($4 != "0") bad = 1 } END { if (n == 0 || bad) exit 1; print n }'; }
group_state() { kgroups --describe --state | parse_group_state; }
group_lag() { kgroups --describe | parse_lag "$IDEM_GROUP"; }
topic_messages() {
  docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$IDEM_TOPIC" 2>&1 | tr -d '\r' | parse_offsets "$IDEM_TOPIC"
}
# 계좌 쪽 돈과 기록 전체. 다시 받은 뒤 한 글자도 바뀌면 안 된다
idem_state() {
  q "select 'cash=' || (select coalesce(sum(cash_balance), 0) from account.account) || ' margin=' || (select coalesce(sum(margin_balance), 0) from account.account)
       || ' ledger=' || (select count(*) from account.ledger_entry) || ' ledger_max=' || coalesce((select max(created_at)::text from account.ledger_entry), '-')
       || ' margins=' || coalesce((select string_agg(status || ':' || n, ',' order by status) from (select status, count(*) n from account.margin group by 1) x), '-')
       || ' posted_sum=' || (select coalesce(sum(posted_amount), 0) from account.margin)
       || ' outbox=' || (select count(*) from account.outbox) || ' settled_out=' || (select count(*) from account.outbox where topic = 'margin.settled')
       || ' processed=' || (select count(*) from account.processed_event where consumer_name = '$IDEM_GROUP')
       || ' account_upd=' || coalesce((select max(updated_at)::text from account.account), '-') || ' margin_upd=' || coalesce((select max(updated_at)::text from account.margin), '-')"
}
dup_logs() { docker logs ipo-account-service 2>&1 | grep -c '\[증거금 정산\] 중복 수신, 건너뜀' || true; }

# 반환: 0 통과 / 1 위반(계좌 쪽이 바뀜 또는 DLT 증가) / 2 실행 실패(검사 못 함). 결과는 <회차>/idempotency.txt
run_idempotency() { # 인자: 회차 폴더
  local rd="$1" f="$1/idempotency.txt" before after msgs parts st="" lag="" d0 d1 l0 l1 t0 i out rc
  before="$(idem_state)" && [ -n "$before" ] || { say "!! 멱등성: 기준 상태 조회 실패"; return 2; }
  msgs="$(topic_messages)" || { say "!! 멱등성: $IDEM_TOPIC 메시지 수 조회 실패"; return 2; }
  d0="$(kafka_dlt_total)" || { say "!! 멱등성: DLT 조회 실패"; return 2; }
  l0="$(dup_logs)"
  say "멱등성: account-service 멈춤 → $IDEM_GROUP 오프셋을 처음으로 → 다시 띄움 ($IDEM_TOPIC ${msgs}건을 다시 받음)"
  compose stop account-service >> "$rd/log.txt" 2>&1 || { say "!! 멱등성: account-service 멈추기 실패"; return 2; }
  for i in $(seq 1 30); do st="$(group_state)" && [ "$st" = Empty ] && break; sleep 2; done
  if [ "$st" != Empty ]; then
    say "!! 멱등성: 그룹이 비지 않음 (${st:-조회 실패})"; compose start account-service >> "$rd/log.txt" 2>&1; return 2
  fi
  out="$(kgroups --topic "$IDEM_TOPIC" --reset-offsets --to-earliest --execute)"; printf '%s\n' "$out" >> "$rd/log.txt"
  if ! parts="$(printf '%s\n' "$out" | parse_reset)"; then
    say "!! 멱등성: 오프셋 되돌리기 실패"; compose start account-service >> "$rd/log.txt" 2>&1; return 2
  fi
  t0="$(date +%s)"
  compose start account-service >> "$rd/log.txt" 2>&1 || { say "!! 멱등성: account-service 시작 실패"; return 2; }
  wait_healthy 600 ipo-account-service || { say "!! 멱등성: account-service healthy 대기 실패"; return 2; }
  while :; do
    lag="$(group_lag)" && [ "$lag" = 0 ] && break
    [ $(( $(date +%s) - t0 )) -ge "$IDEM_MAX_S" ] && { say "!! 멱등성: ${IDEM_MAX_S}초 안에 랙이 0이 되지 않음 (${lag:-조회 실패})"; return 2; }
    sleep 3
  done
  sleep 5
  after="$(idem_state)" || { say "!! 멱등성: 결과 상태 조회 실패"; return 2; }
  d1="$(kafka_dlt_total)" || d1="?"
  l1="$(dup_logs)"
  rc=0; [ "$before" = "$after" ] && [ "$d1" = "$d0" ] || rc=1
  { echo "result=$([ "$rc" = 0 ] && echo PASS || echo FAIL) topic=$IDEM_TOPIC group=$IDEM_GROUP messages=$msgs partitions=$parts replay_s=$(( $(date +%s) - t0 ))"
    echo "before $before"
    echo "after  $after"
    echo "dlt_before=$d0 dlt_after=$d1"
    echo "참고: 로그의 '중복 수신, 건너뜀' $((l1 - l0))건 (다시 받은 메시지 ${msgs}건. 로그 문구에 기대는 값이라 판정에는 쓰지 않음)"
  } > "$f"
  say "멱등성: $(head -1 "$f")"
  return "$rc"
}

# ---------- 판정 ----------
# 인자: 기대 수, M 측정값 줄, DLT 증가, 측정 끝 미발행, 다른 공모주 마감 수, 케이스, M verify 위반, W verify 위반
# 출력 첫 줄 "<status> <integrity>", 다음 줄부터 이유. 측정 불가는 호출하는 쪽이 따로 정한다
judge() {
  local n="$1" line="$2" dlt="$3" unpub="$4" other="$5" case_="$6" vm="$7" vw="$8" status=VALID integ=PASS reasons=()
  local settled refunded zero voided lvoid posted lpost
  settled="$(kv "$line" settled)"; refunded="$(kv "$line" refunded)"; zero="$(kv "$line" zero)"
  voided="$(kv "$line" voided)"; lvoid="$(kv "$line" l_void)"; posted="$(kv "$line" posted)"; lpost="$(kv "$line" l_post)"
  [ "$settled" = "$n" ] || { status=INVALID; reasons+=("정산 수 ${settled:-?} ≠ 확정 수 $n"); }
  [ "$refunded" = "$n" ] || { status=INVALID; reasons+=("REFUNDED 수 ${refunded:-?} ≠ 확정 수 $n"); }
  [ "$dlt" = 0 ] || { status=INVALID; reasons+=("DLT 증가 $dlt"); }
  [ "$unpub" = 0 ] || { status=INVALID; reasons+=("측정 끝 outbox 미발행 $unpub"); }
  [ "$other" = 0 ] || { status=INVALID; reasons+=("측정 중 다른 공모주 마감 $other"); }
  [ -z "$vm" ] || { integ=FAIL; reasons+=("M verify.sql 위반: $vm"); }
  [ -z "$vw" ] || { integ=FAIL; reasons+=("W verify.sql 위반: $vw"); }
  [ "$voided" = "$lvoid" ] || { integ=FAIL; reasons+=("VOIDED ${voided:-?} ≠ MARGIN_VOID 원장 ${lvoid:-?}"); }
  [ "$posted" = "$lpost" ] || { integ=FAIL; reasons+=("POSTED ${posted:-?} ≠ MARGIN_POST 원장 ${lpost:-?}"); }
  [ "$zero" = "$voided" ] || { integ=FAIL; reasons+=("0주 배정 ${zero:-?} ≠ VOIDED ${voided:-?}"); }
  if [ "$case_" = A ] && [ "$zero" != 0 ]; then reasons+=("참고: 케이스 A인데 0주 ${zero}건 (판정에는 쓰지 않음)"); fi
  echo "$status $integ"
  [ "${#reasons[@]}" -eq 0 ] || printf '%s\n' "${reasons[@]}"
}

# ---------- 결과 파일 ----------
manifest_row() { # 인자: seq snapshot case n started ended status integrity total thr idempotency reasons
  local r="${12//,/;}"; r="${r//$'\n'/ | }"
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$SESSION" "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$8" "${9:--}" "${10:--}" "${11:--}" "$r" >> "$MANIFEST"
}

record_env() {
  local c line sha
  {
    echo "batch=$BATCH session=$SESSION started=$(date '+%F %T')"
    echo "git=$(git_repo rev-parse HEAD 2>/dev/null) branch=$(git_repo rev-parse --abbrev-ref HEAD 2>/dev/null)"
    git_repo status --porcelain 2>/dev/null | sed 's/^/dirty: /'
    echo "설정 GAP=$GAP STALL_S=$STALL_S MAX_S=$MAX_S W_MAX_S=$W_MAX_S SETTLE_S=$SETTLE_S STATS_INTERVAL=$STATS_INTERVAL IDEMPOTENCY=$IDEM IDEM_MAX_S=$IDEM_MAX_S"
    echo "순서 ${SNAPS[*]}"
    for c in $WATCH_CONTAINERS; do
      line="$(docker inspect -f '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}' "$c" 2>/dev/null)" || { echo "자원 $c 조회 실패"; continue; }
      echo "자원 $c cpu=$(awk -v n="${line%% *}" 'BEGIN { printf "%g", n / 1e9 }') mem=${line##* } $(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$c" 2>/dev/null | grep -E '^(JAVA_TOOL_OPTIONS|KAFKA_HEAP_OPTS)=' | tr '\n' ' ')"
    done
    for c in $SERVICE_CONTAINERS ipo-eureka-server; do
      sha="$(docker exec "$c" sha256sum /app/app.jar 2>/dev/null | awk '{print $1}')"
      echo "jar $c sha256=${sha:-조회 실패}"
    done
  } >> "$BATCH_DIR/env.txt"
}

# ---------- 회차 ----------
# 인자: 순번, 스냅샷 경로. 반환: 0 회차 끝(판정은 manifest) / 4 배치를 멈춰야 하는 실패(복원, 기동)
run_round() {
  local seq="$1" snap="$2" name rd started n wid i tw t_start held unf unpub due d0 d1 m0 m1 changed tm rc line wl
  local status integ reasons vm vw vwi verdict j other idem
  name="$(basename "$snap")"; rd="$BATCH_DIR/$(printf '%02d' "$seq")-$name"
  mkdir -p "$rd"; LOG="$rd/log.txt"; OUT="$rd"
  started="$(date '+%F %T')"
  read_ids "$snap" > /dev/null || { say "!! ipo-ids.txt 읽기 실패"; return 4; }
  say "[$seq] $name 케이스 $CASE_ 복원 시작"
  BASELINE_DIR="$snap" bash "$DIR/baseline.sh" restore >> "$rd/log.txt" 2>&1 || { say "!! 복원 실패 ($rd/log.txt)"; return 4; }
  wait_healthy 600 $SERVICE_CONTAINERS || { say "!! healthy, Eureka 대기 실패"; return 4; }
  sleep "$SETTLE_S"

  n="$(confirmed_count "$M_ID")" || { say "!! 확정 수 조회 실패"; return 4; }
  held="$(held_counts)" || { say "!! 점검 조회 실패"; return 4; }
  read -r unf unpub due <<< "$held"
  d0="$(kafka_dlt_total)" || { say "!! DLT 조회 실패"; return 4; }
  m0="$(container_marks)"
  say "점검: M 확정 ${n}건, 처리 중 청약 $unf, 미발행 $unpub, 지난 OPEN $due, DLT $d0"
  if [ "$n" = 0 ] || [ "$unf" != 0 ] || [ "$due" != 0 ]; then
    manifest_row "$seq" "$name" "$CASE_" "$n" "$started" "$(date '+%F %T')" INVALID - - - - "점검 실패: 확정 $n, 처리 중 $unf, 지난 OPEN $due"
    say "!! 점검 실패 → INVALID"; return 0
  fi

  t_start="$(q "select to_char(now(), 'YYYY-MM-DD HH24:MI:SS.MS')")"
  i=0
  for wid in $W_IDS; do
    i=$((i + 1))
    j="$(confirmed_count "$wid")" || return 4
    tw="$(close_ipo "$wid")" || { say "!! W$i 마감 UPDATE 실패"; return 4; }
    say "W$i 마감 trigger=$tw (확정 ${j}건)"
    wait_refunded "$wid" "$j" "$STALL_S" "$W_MAX_S"; rc=$?
    if [ "$rc" != 0 ]; then
      manifest_row "$seq" "$name" "$CASE_" "$n" "$started" "$(date '+%F %T')" UNMEASURABLE - - - - "W$i 환불이 끝나지 않음 (wait_refunded $rc)"
      return 0
    fi
    echo "W$i $(collect "$wid" "$tw")" >> "$rd/result.txt"
    say "W$i 대기 ${GAP}초"; sleep "$GAP"
  done

  q "select schemaname || '.' || relname || ' live=' || n_live_tup || ' dead=' || n_dead_tup from pg_stat_user_tables where schemaname in ('account','subscription','allocation') order by 1" > "$rd/tables-before.txt"
  stats_start
  tm="$(close_ipo "$M_ID")" || { stats_stop; say "!! M 마감 UPDATE 실패"; return 4; }
  say "M 마감 trigger=$tm (확정 ${n}건, 케이스 $CASE_)"
  wait_refunded "$M_ID" "$n" "$STALL_S" "$MAX_S"; rc=$?
  stats_stop
  m1="$(container_marks)"; changed="$(marks_diff "$m0" "$m1")"
  line="$(collect "$M_ID" "$tm")"; echo "M $line" >> "$rd/result.txt"
  if [ "$rc" != 0 ] || [ -n "$changed" ]; then
    reasons="wait_refunded $rc"; [ -n "$changed" ] && reasons="$reasons, 컨테이너 변화: $changed"
    manifest_row "$seq" "$name" "$CASE_" "$n" "$started" "$(date '+%F %T')" UNMEASURABLE - "$(kv "$line" total_s)" - - "$reasons"
    say "!! 측정 불가: $reasons"; return 0
  fi

  sleep 5
  d1="$(kafka_dlt_total)" || d1="?"
  unpub="$(outbox_unpublished)" || unpub="?"
  other="$(other_closed "$t_start" "$W_IDS $M_ID")" || other="?"
  buckets "$M_ID" "$rd" || say "!! 1초 버킷 조회 실패"
  q "select topic || ' ' || count(*) || ' ' || min(processed_at) || ' ' || max(processed_at) from subscription.processed_event where processed_at >= '$t_start'::timestamptz group by topic order by 1" > "$rd/processed.txt"

  if run_verify "$M_ID" "$rd/verify-M.txt" 0 && vm="$(verify_violations "2 3 5 6 11 12 13 14 15" < "$rd/verify-M.txt")"; then :; else vm="verify-M.txt 실행 또는 형식 오류"; fi
  vw=""; i=0
  for wid in $W_IDS; do
    i=$((i + 1))
    if run_verify "$wid" "$rd/verify-W$i.txt" 1 && vwi="$(verify_violations "5 6 11 12 13 14 15" < "$rd/verify-W$i.txt")"; then :; else vwi="verify-W$i.txt 실행 또는 형식 오류"; fi
    [ -z "$vwi" ] || vw="${vw:+$vw; }W$i $vwi"
  done

  verdict="$(judge "$n" "$line" "$([ "$d1" = "?" ] && echo "?" || echo $((d1 - d0)))" "$unpub" "$other" "$CASE_" "$vm" "$vw")"
  read -r status integ <<< "$(printf '%s\n' "$verdict" | head -1)"
  reasons="$(printf '%s\n' "$verdict" | sed 1d)"
  idem=-
  if [ "$IDEM" = 1 ]; then
    run_idempotency "$rd"
    case $? in
      0) idem=PASS ;;
      1) idem=FAIL; integ=FAIL; reasons="${reasons:+$reasons | }멱등성 위반: 배정 결과를 다시 받은 뒤 계좌 쪽 돈이나 기록이 바뀜 (idempotency.txt)" ;;
      *) idem=ERROR; reasons="${reasons:+$reasons | }멱등성 검사를 끝내지 못함 (log.txt)" ;;
    esac
  fi
  { echo "expected=$n dlt_before=$d0 dlt_after=$d1 unpublished_end=$unpub other_closed=$other"
    echo "verify_M=${vm:-0행} verify_W=${vw:-0행}"
    echo "status=$status integrity=$integ idempotency=$idem"
    [ -n "$reasons" ] && printf '%s\n' "$reasons"
  } > "$rd/checks.txt"
  manifest_row "$seq" "$name" "$CASE_" "$n" "$started" "$(date '+%F %T')" "$status" "$integ" "$(kv "$line" total_s)" "$(kv "$line" thr)" "$idem" "$reasons"
  say "[$seq] $name 끝: $status $integ 멱등성 $idem total_s=$(kv "$line" total_s) thr=$(kv "$line" thr) ${reasons:+($reasons)}"
  return 0
}

g2_cleanup() {
  stats_stop
  [ -n "$G2_LOCK_OWNED" ] && rmdir "$G2_LOCK" 2>/dev/null
  return 0
}

main() {
  parse_args "$@"
  check_snaps || die 1 "스냅샷 폴더 검사 실패"
  [ "$IDEM" = 0 ] || pick_compose || die 1 "docker compose가 없다 (--idempotency는 account-service를 멈췄다 띄운다)"
  mkdir -p "$DIR/results" || die 12 "results 폴더를 만들 수 없음"
  mkdir "$G2_LOCK" 2>/dev/null || die 12 "다른 g2_run.sh가 실행 중 ($G2_LOCK. 아니면 지운다)"
  G2_LOCK_OWNED=1; trap g2_cleanup EXIT; trap 'exit 130' INT TERM
  BATCH_DIR="$DIR/results/g2-$BATCH"; mkdir -p "$BATCH_DIR" || die 12 "결과 폴더: $BATCH_DIR"
  MANIFEST="$BATCH_DIR/manifest.csv"
  [ -f "$MANIFEST" ] || echo "session,seq,snapshot,case,n,started,ended,status,integrity,total_s,throughput,idempotency,reasons" > "$MANIFEST"
  LOG="$BATCH_DIR/batch.log"
  record_env
  local seq start s rc=0 base
  base="$(($(grep -c . "$MANIFEST") - 1))"   # 같은 batch로 이어 쓰면 순번을 이어 간다
  seq="$base"
  for s in "${SNAPS[@]}"; do
    seq=$((seq + 1))
    start="$(snap_path "$s")"; start="$(cd "$start" && pwd)"
    run_round "$seq" "$start" || { LOG="$BATCH_DIR/batch.log"; say "!! [$seq] 배치 중단"; exit 4; }
    LOG="$BATCH_DIR/batch.log"
  done
  say "끝: $MANIFEST"
  awk -F, 'NR > 1 && $1 == s { print "  " $2 " " $3 " " $8 " " $9 " total_s=" $10 }' s="$SESSION" "$MANIFEST" >&2
  awk -F, -v b="$base" 'NR > 1 + b { if ($9 == "FAIL") f = 1; if ($8 != "VALID") v = 1 } END { exit f ? 3 : (v ? 2 : 0) }' "$MANIFEST"; rc=$?
  exit "$rc"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
