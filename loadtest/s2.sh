#!/usr/bin/env bash
# S2 마감 경계 한 회차: 워밍업 → 대상, 배경 공모주 준비 → 대상 공모주 마감 시각 설정 → k6 s2 → 마감, 소진 대기 → 판정
# 사용: bash loadtest/s2.sh --run-id <run_id> [--grace PT1S]
#   --grace: 청약 서비스의 마감 유예(ipo.close.grace-period, 기본 PT20S)만 바꿔 다시 띄우고, 끝나면(오류로 멈춰도) 원래대로 되돌린다
# 결과: results/<run_id>/ (k6 원본, s2-judge.txt, sql_result.txt), results/<run_id>-W/ (워밍업)
# 판정 (s2-judge.txt):
#   회차 유효성: 대상 요청에 202(마감 전 도착)와 422(마감 후 도착)가 모두 있어야 한다 (마감 시각이 대상 구간 안), 401이 없어야 한다
#   측정값: verify.sql 20~22번(마감 처리 뒤 생긴 청약, 마감 시각 이후 도착인데 접수된 청약, 마감 때 확정 수 차이)과 202를 받았는데 행이 없는 요청(k6 로그 대조)
# 환경변수: RATE(274), PRE(60), POST(40), TARGET_SEC(0: 회원 수 ÷ RATE초 동안 전부 대상), PRICE(1000), QUANTITY(10),
#          LEAD(25, 시작까지 여유 초), WARM(1, 0이면 워밍업 생략), WARM_*(official.sh와 같은 기본값),
#          CLOSE_TIMEOUT(300), DRAIN_TIMEOUT(600), K6_IMAGE, K6_CPUS(2), K6_MEMORY(1g)
# 종료 코드: 0 유효 회차, 3 회차 무효(판정 파일 참고), 그 밖은 준비, 실행 실패
set -uo pipefail
export MSYS_NO_PATHCONV=1
DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$DIR/.." && pwd)"
COMPOSE_FILES=(-f docker-compose.yml -f compose.perf.yml)
PG_CONTAINER="${PG_CONTAINER:-ipo-postgres}"; KAFKA_CONTAINER="${KAFKA_CONTAINER:-ipo-kafka}"; SUB_CONTAINER=ipo-subscription-service
RATE="${RATE:-274}"; PRE="${PRE:-60}"; POST="${POST:-40}"; TARGET_SEC="${TARGET_SEC:-0}"
PRICE="${PRICE:-1000}"; QUANTITY="${QUANTITY:-10}"; LEAD="${LEAD:-25}"
WARM="${WARM:-1}"; WARM_RATE="${WARM_RATE:-200}"; WARM_BUCKET="${WARM_BUCKET:-15}"; WARM_WINDOW="${WARM_WINDOW:-8}"
WARM_SLOPE_PCT="${WARM_SLOPE_PCT:-5}"; WARM_SLOPE_MS="${WARM_SLOPE_MS:-1}"; WARM_CONSEC="${WARM_CONSEC:-2}"; WARM_MIN="${WARM_MIN:-180}"; WARM_MAX="${WARM_MAX:-900}"
CLOSE_TIMEOUT="${CLOSE_TIMEOUT:-300}"; DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-600}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.3.0}"; K6_CPUS="${K6_CPUS:-2}"; K6_MEMORY="${K6_MEMORY:-1g}"
GRACE_FILE="loadtest/data/compose.s2-grace.yml"

RUN_ID=""; GRACE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --run-id) RUN_ID="$2"; shift 2 ;;
    --grace) GRACE="$2"; shift 2 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done
[ -n "$RUN_ID" ] || { echo "사용: $0 --run-id <run_id> [--grace PT1S]" >&2; exit 1; }
OUT="$DIR/results/$RUN_ID"; WRUN="$RUN_ID-W"
[ -e "$OUT" ] && { echo "이미 있는 run_id입니다: $OUT" >&2; exit 1; }
mkdir -p "$OUT" "$DIR/results/$WRUN"
LOG="$OUT/진행.log"

say() { printf '%s %s\n' "$(date '+%F %T')" "$*" | tee -a "$LOG" >&2; }
die() { local code="$1"; shift; say "!! $*"; exit "$code"; }
PY=""
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then PY="$c"; break; fi
done
[ -n "$PY" ] || die 1 "Python 3.8 이상이 필요합니다"
pypath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
py() { "$PY" -X utf8 "$@"; }
if docker compose version >/dev/null 2>&1; then COMPOSE=(docker compose); else COMPOSE=(docker-compose); fi
compose() { (cd "$REPO" && "${COMPOSE[@]}" "$@"); }
pg() { docker exec -i "$PG_CONTAINER" psql -U postgres -d ipo -v ON_ERROR_STOP=1 -qtA -F ' ' -c "$1"; }

# 모든 Consumer 그룹의 랙 합. 커밋 오프셋이 '-'인데 메시지가 있으면 끝 오프셋 전체를 대기로 센다 (official.sh와 같은 방식)
kafka_lag() {
  docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --all-groups 2>/dev/null \
    | awk '$3 ~ /^[0-9]+$/ && $5 ~ /^[0-9]+$/ { lag += ($4 ~ /^[0-9]+$/) ? $5 - $4 : $5; n++ } END { if (n == 0) exit 1; print lag + 0 }'
}
outbox_unpublished() {
  pg "select (select count(*) from subscription.outbox where published_at is null) + (select count(*) from account.outbox where published_at is null) + (select count(*) from allocation.outbox where published_at is null)"
}
# 인자: 쉼표로 구분한 공모주 ID, 제한 초. 진행 중 청약 0, 미발행 Outbox 0, 랙 0이 될 때까지 기다리고 걸린 초를 출력
drain() {
  local in start p o l
  in="'$(printf '%s' "$1" | sed "s/,/','/g")'"; start="$(date +%s)"
  while [ $(( $(date +%s) - start )) -le "$2" ]; do
    if p="$(pg "select count(*) from subscription.subscription where status in ('PENDING','COMPENSATING') and ipo_id in ($in)")" \
       && o="$(outbox_unpublished)" && l="$(kafka_lag)" && [ "$p" = 0 ] && [ "$o" = 0 ] && [ "$l" = 0 ]; then
      echo $(( $(date +%s) - start )); return 0
    fi
    sleep 2
  done
  return 1
}
# 인자: 이름, 개수. 공모주 ID를 쉼표로 이어 출력 (new_ipo.sh가 SQL 한 번으로 만든다)
new_ipos() {
  local ids n
  ids="$(cd "$DIR" && bash seed/new_ipo.sh --name "$1" --count "$2" --price "$PRICE" | tr -d '\r ')" || return 1
  n="$(printf '%s\n' "$ids" | grep -c .)"
  [ "$n" -eq "$2" ] || return 1
  printf '%s' "$ids" | paste -sd, -
}
wait_healthy() {
  local i
  for i in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$SUB_CONTAINER" 2>/dev/null)" = healthy ] && { sleep 45; return 0; }
    sleep 5
  done
  return 1
}
grace_env() { docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$SUB_CONTAINER" | grep '^IPO_CLOSE_GRACEPERIOD=' | cut -d= -f2; }

# --grace: 유예만 바꿔 청약 서비스를 다시 띄운다. 끝나면 덮어쓰기 없이 다시 띄워 원래 값으로 되돌린다
restore_grace() {
  say "마감 유예 되돌림: 덮어쓰기 없이 청약 서비스를 다시 띄움"
  rm -f "$REPO/$GRACE_FILE"
  compose "${COMPOSE_FILES[@]}" up -d subscription-service >> "$LOG" 2>&1 || say "!! 청약 서비스 재기동 실패. 직접 확인 필요"
  wait_healthy || say "!! 되돌린 뒤 healthy 대기 시간 초과. 직접 확인 필요"
  [ -z "$(grace_env)" ] && say "마감 유예 되돌림 확인 (기본값)" || say "!! 마감 유예 환경변수가 남아 있음. 직접 확인 필요"
}
docker stop -t 30 "k6-$RUN_ID" "k6-$WRUN" >/dev/null 2>&1 || true
if [ -n "$GRACE" ]; then
  printf 'services:\n  subscription-service:\n    environment:\n      IPO_CLOSE_GRACEPERIOD: "%s"\n' "$GRACE" > "$REPO/$GRACE_FILE"
  trap 'docker stop -t 30 "k6-$RUN_ID" "k6-$WRUN" >/dev/null 2>&1; restore_grace' EXIT
  say "마감 유예 $GRACE로 청약 서비스를 다시 띄움"
  compose "${COMPOSE_FILES[@]}" -f "$GRACE_FILE" up -d subscription-service >> "$LOG" 2>&1 || die 1 "청약 서비스 재기동 실패"
  wait_healthy || die 1 "청약 서비스 healthy 대기 시간 초과"
  [ "$(grace_env)" = "$GRACE" ] || die 1 "컨테이너의 마감 유예가 $GRACE가 아님: '$(grace_env)'"
else
  trap 'docker stop -t 30 "k6-$RUN_ID" "k6-$WRUN" >/dev/null 2>&1' EXIT
  [ -z "$(grace_env)" ] || die 1 "청약 서비스에 마감 유예 덮어쓰기가 남아 있음: $(grace_env). --grace 없이 재야 하면 덮어쓰기 없이 다시 띄운다"
fi
GRACE_ACTUAL="${GRACE:-PT20S(기본)}"

# 토큰 (1시간 만료): 워밍업(최대 WARM_MAX초)과 본 측정 전에 다시 받는다
say "토큰 재발급"
(cd "$REPO" && py loadtest/seed/seed_users.py --run-id "tok-$RUN_ID" --refresh-tokens) >> "$LOG" 2>&1 || die 1 "토큰 재발급 실패"
U="$(($(grep -c . "$DIR/data/users.csv") - 1))"
[ "$U" -ge 1 ] || die 1 "회원 CSV가 비어 있음"

# 워밍업: official.sh와 같은 방식 (WARM_RATE 일정 부하, warmup.py가 응답 시간 추세가 평평해졌다고 판정하면 멈춤)
if [ "$WARM" = 1 ]; then
  WIPOS="$(new_ipos "$WRUN" $(( ((WARM_MAX + 60) * WARM_RATE + U - 1) / U )))" || die 1 "워밍업 공모주 생성 실패"
  say "워밍업 시작: ${WARM_RATE}건/초, 최소 ${WARM_MIN}초, 최대 ${WARM_MAX}초"
  t0="$(date +%s)"
  (cd "$DIR" && K6_MODE=container K6_IMAGE="$K6_IMAGE" K6_CPUS="$K6_CPUS" K6_MEMORY="$K6_MEMORY" K6_NAME="k6-$WRUN" \
    bash run-k6.sh s1 --ipo "${WIPOS%%,*}" --run-id "$WRUN" -e "IPO_IDS=$WIPOS" -e "RATE=$WARM_RATE" -e "DURATION=$((WARM_MAX + 60))s" -e "QUANTITY=$QUANTITY") \
    > "$DIR/results/$WRUN/k6-console.out" 2>&1 &
  kpid=$!
  sleep 12
  kill -0 "$kpid" 2>/dev/null || die 1 "워밍업 k6가 시작하자마자 끝남 (results/$WRUN/k6-console.out)"
  py "$(pypath "$DIR/verify/warmup.py")" --since $((t0 + 10)) --csv "$(pypath "$OUT/warmup.csv")" --prometheus "${PROMETHEUS_URL:-http://127.0.0.1:9090}" \
    --bucket "$WARM_BUCKET" --window "$WARM_WINDOW" --slope-pct "$WARM_SLOPE_PCT" --slope-ms "$WARM_SLOPE_MS" --consecutive "$WARM_CONSEC" \
    --min "$WARM_MIN" --max "$WARM_MAX" >> "$LOG" 2>&1; wrc=$?
  docker stop -t 30 "k6-$WRUN" >/dev/null 2>&1; wait "$kpid"
  [ "$wrc" -eq 0 ] || die 1 "워밍업이 정상 상태에 닿지 않음 (warmup.py 종료 코드 $wrc, warmup.csv)"
  d="$(drain "$WIPOS" "$DRAIN_TIMEOUT")" || die 1 "워밍업 뒤 소진 대기 시간 초과"
  say "워밍업 끝: $(( $(date +%s) - t0 ))초, 소진 ${d}초. 30초 대기"
  sleep 30
fi

# 대상 1개 + 배경 (필요 수 = 올림((전체 요청 - 회원 수) / 회원 수), s2.js와 같은 계산)
read -r WIN_MS TOTAL BG_N <<< "$(py -c "
import math
U, RATE, PRE, POST, TS = $U, $RATE, $PRE, $POST, $TARGET_SEC
win = TS if TS > 0 else U / RATE
total = round(RATE * (PRE + win + POST))
print(round(win * 1000), total, math.ceil((total - U) / U))")"
T="$(new_ipos "$RUN_ID-T" 1)" || die 1 "대상 공모주 생성 실패"
BG="$(new_ipos "$RUN_ID-BG" "$BG_N")" || die 1 "배경 공모주 생성 실패"

# 시작 시각과 마감 시각은 컨테이너 시계(PostgreSQL, 게이트웨이, k6가 같은 Docker 시계)로 정한다
NOW_MS="$(docker exec "$PG_CONTAINER" date +%s%3N)" || die 1 "컨테이너 시계 조회 실패"
START_AT=$(( NOW_MS + LEAD * 1000 ))
END_MS=$(( START_AT + PRE * 1000 + WIN_MS / 2 ))
pg "update subscription.ipo set subscription_end_at = to_timestamp($END_MS / 1000.0) where ipo_id = '$T'" > /dev/null || die 1 "대상 공모주 마감 시각 설정 실패"
{
  echo "run_id=$RUN_ID git=$(cd "$REPO" && git rev-parse --short HEAD) branch=$(cd "$REPO" && git rev-parse --abbrev-ref HEAD) dirty=$(cd "$REPO" && git status --porcelain | grep -c .)"
  echo "grace=$GRACE_ACTUAL rate=$RATE pre=$PRE post=$POST target_sec=$TARGET_SEC target_window_ms=$WIN_MS total=$TOTAL users=$U bg_ipos=$BG_N price=$PRICE quantity=$QUANTITY"
  echo "target_ipo=$T start_at_ms=$START_AT end_at_ms=$END_MS"
  echo "k6_image=$K6_IMAGE k6_cpus=$K6_CPUS k6_memory=$K6_MEMORY"
} > "$OUT/env.txt"
say "대상 $T 마감 시각 = 시작 + $((PRE * 1000 + WIN_MS / 2))ms. 부하 ${RATE}건/초 x 약 $(( TOTAL / RATE ))초 (배경 공모주 ${BG_N}개)"

(cd "$DIR" && K6_MODE=container K6_IMAGE="$K6_IMAGE" K6_CPUS="$K6_CPUS" K6_MEMORY="$K6_MEMORY" K6_NAME="k6-$RUN_ID" \
  bash run-k6.sh s2 --ipo "$T" --run-id "$RUN_ID" -e "IPO_IDS=$BG" -e "START_AT=$START_AT" -e "RATE=$RATE" -e "PRE=$PRE" \
  -e "POST=$POST" -e "TARGET_SEC=$TARGET_SEC" -e "QUANTITY=$QUANTITY") > "$OUT/k6-console.out" 2>&1; krc=$?
say "k6 종료 코드 $krc (0 통과, 99 임계값 위반)"
case "$krc" in 0|99) ;; *) die 1 "k6 실행 실패 (k6-console.out)" ;; esac

# 대상 공모주 마감(OPEN이 아님)과 전체 소진을 기다린다
start="$(date +%s)"
until [ "$(pg "select status from subscription.ipo where ipo_id = '$T'")" != OPEN ]; do
  [ $(( $(date +%s) - start )) -gt "$CLOSE_TIMEOUT" ] && die 1 "대상 공모주가 ${CLOSE_TIMEOUT}초 안에 마감되지 않음"
  sleep 2
done
d="$(drain "$T,$BG" "$DRAIN_TIMEOUT")" || die 1 "소진 대기 시간 초과"
say "마감 확인, 소진 ${d}초"

# 정합성 SQL (마감된 공모주 검사 20~22번 포함). 판정이 결과를 읽으므로 실패하면 멈춘다
(cd "$DIR" && bash verify.sh --ipo "$T" --run-id "$RUN_ID") >> "$LOG" 2>&1 || die 1 "verify.sh 실패 (sql_result.txt 확인)"

# 판정: k6 로그가 필요한 것(회차 유효성, 202인데 행 없음)만 직접 계산하고, DB만 보는 검사는 verify.sql 20~22번 결과를 읽는다
py - "$(pypath "$OUT/requests.log")" "$(pypath "$DIR/verify")" "$T" "$END_MS" "$PG_CONTAINER" "$(pypath "$OUT/summary.json")" "$(pypath "$OUT/sql_result.txt")" > "$OUT/s2-judge.txt" <<'PYEOF'
import collections, json, re, subprocess, sys
log, verify_dir, ipo, end_ms, pg, summary, sql_result = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5], sys.argv[6], sys.argv[7]
sys.path.insert(0, verify_dir)
from check_requests import load_rows

# sql_result.txt에서 검사 번호마다 결과 행 수를 읽는다 (건너뛴 검사는 "skip")
def sql_counts(path):
    res, cur = {}, None
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.match(r"== (\d+)\.", line)
        if m:
            cur = int(m.group(1))
            res[cur] = "skip" if "건너뜀" in line else None
            continue
        m = re.match(r"\((\d+) rows?\)", line.strip())
        if m and cur is not None and res.get(cur) is None:
            res[cur] = int(m.group(1))
    return res

def q(sql):
    out = subprocess.run(["docker", "exec", "-i", pg, "psql", "-U", "postgres", "-d", "ipo", "-v", "ON_ERROR_STOP=1",
                          "-qtA", "-F", "|", "-c", sql], capture_output=True, text=True, encoding="utf-8", check=True).stdout
    return [line.split("|") for line in out.splitlines() if line.strip()]

rows = load_rows(log)
tgt = [r for r in rows if r.get("stage") == "target"]
bg = [r for r in rows if r.get("stage") == "bg"]
ts = collections.Counter(r["status"] for r in tgt)
bs = collections.Counter(r["status"] for r in bg)
unauth = sum(1 for r in rows if r["status"] == 401)
ok202 = {r["requestId"] for r in tgt if r["status"] == 202}

end_epoch = end_ms / 1000.0
closed = q(f"select extract(epoch from created_at), payload::json->>'confirmedCount' from subscription.outbox "
           f"where topic = 'ipo.closed' and aggregate_id = '{ipo}' order by created_at limit 1")
close_epoch = float(closed[0][0]) if closed else None
confirmed_at_close = int(closed[0][1]) if closed else None
subs = {s[0] for s in q(f"select request_id from subscription.subscription where ipo_id = '{ipo}'")}
missing = [r for r in ok202 if r not in subs]
sc = sql_counts(sql_result)
for n in (20, 21, 22):
    if not isinstance(sc.get(n), int):
        sys.exit(f"sql_result.txt {n}번 결과를 읽지 못함: {sc.get(n)} (마감된 대상 공모주라 건너뛰면 안 된다)")

reasons = []
# 요청 순번으로 대상 구간을 정하므로(k6 iterationInTest는 보낸 요청만 센다), 밀린 요청이 있으면 대상 구간이 그만큼 뒤로 밀린다
dropped = int(json.load(open(summary, encoding="utf-8"))["metrics"].get("dropped_iterations", {}).get("count", 0))
if dropped:
    reasons.append(f"밀린 요청 {dropped}건 (대상 구간이 계획보다 늦어짐)")
if ts.get(202, 0) == 0 or ts.get(422, 0) == 0:
    reasons.append("대상 요청에 202와 422가 모두 있지 않음 (마감 시각이 대상 구간 밖)")
if unauth:
    reasons.append(f"401 {unauth}건 (토큰 만료)")
others = {s: n for s, n in (ts + bs).items() if s not in (202, 409, 422)}
if others:
    reasons.append(f"기대하지 않은 상태 코드 {dict(others)}")
if close_epoch is None:
    reasons.append("ipo.closed Outbox 없음")

print("# S2 마감 경계 판정")
print(f"회차: {'유효' if not reasons else '무효'}" + ("" if not reasons else " (" + "; ".join(reasons) + ")"))
print()
print(f"대상 요청: {dict(sorted(ts.items()))} (202 마감 전 도착, 422 마감 후 도착, 409 마감 처리 뒤 도착)")
print(f"배경 요청: {dict(sorted(bs.items()))}")
print(f"마감 시각(대상 공모주 subscription_end_at): {end_epoch:.3f}")
if close_epoch is not None:
    print(f"마감 처리(ipo.closed Outbox 생성): {close_epoch:.3f} (마감 시각 + {close_epoch - end_epoch:.3f}초), 마감 때 확정 수 {confirmed_at_close}")
print(f"대상 청약 행: {len(subs)}건")
print()
print("## 측정값 (기대: 모두 0)")
print(f"마감 처리 뒤에 생긴 대상 청약 행 (verify.sql 20번, 202를 받고 마감 집계에서 빠진 청약): {sc[20]}")
print(f"마감 시각 이후 도착인데 접수된 행 (21번): {sc[21]}")
print(f"마감 때 확정 수와 지금 유효 청약 수가 다름 (22번, 다르면 1): {sc[22]}")
print(f"202를 받았는데 청약 행이 없음 (k6 로그 대조): {len(missing)}")
sys.exit(0 if not reasons else 3)
PYEOF
jrc=$?
cat "$OUT/s2-judge.txt" | tee -a "$LOG"
[ "$jrc" -eq 0 ] || { [ "$jrc" -eq 3 ] && exit 3; die 1 "판정 스크립트 실패 (종료 코드 $jrc)"; }
say "끝: $OUT"
