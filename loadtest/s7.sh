#!/usr/bin/env bash
# S7 경쟁률 조회 부하 한 묶음 (F-15): 지금 커밋으로 빌드, 배포(jar 확인) → S7 스냅샷 복원 → 토큰 → 워밍업 → 목록 조회 속도별로 REPS회 → 후처리
#   청약 신청(RATE)과 공모주 목록 조회(LIST_RATE, 경쟁률 포함)를 같은 시간 동안 각자 고정 속도로 보낸다 (k6/s7.js)
#   목록 첫 페이지에 확정 청약 10만 건 공모주가 있는 S7 스냅샷에서 시작한다 (seed/s7_ipo.sh로 만든 합성 데이터, 아래 "스냅샷")
# 사용: bash loadtest/s7.sh --batch <이름>   (기본값이면 19자 이하. 공모주 이름이 30자를 넘으면 시작 전에 멈춘다)
#   시험 실행: ALLOW_DIRTY=1 WARM=0 RATE=5 LIST_RATES=5 REPS=1 DURATION=10s GAP=5 bash loadtest/s7.sh --batch S7-smoke (결과를 측정값으로 쓰지 않음)
#   비교는 코드마다 같은 날 한 번씩 돌린다. 예) develop에서 EXPECT_RATE=0 bash loadtest/s7.sh --batch S7-dev, 기능 브랜치에서 bash loadtest/s7.sh --batch S7-db
#   경쟁률이 없는 코드(develop)는 EXPECT_RATE=0 (목록 항목의 competitionRate 검사를 끔)
# 결과: results/<이름>/ (진행.log, env.txt, manifest.csv, docker-stats.raw, docker-stats.csv, 자원.md, 요약.md, slow-queries-<run>.txt)
#       + 실행마다 results/<이름>-l<목록 속도>-r<회차>/ (summary.json, requests.log, report.md) + 워밍업 results/<이름>-W/
# 환경변수: RATE(청약, 433), LIST_RATES("433 866"), REPS(3), DURATION(90s), GAP(30, 실행 사이 쉬는 초), EXPECT_RATE(1),
#          BASELINE_DIR(loadtest/data/baseline-s7), STATS_INTERVAL(5, docker stats 기록 간격 초. 0이면 기록 안 함),
#          WARM(1, 0이면 워밍업 없이. 스크립트 시험용), WARM_RATE(200), WARM_LIST_RATE(216), WARM_* (s2.sh와 같은 기본값), DRAIN_TIMEOUT(600), PRE_VUS, MAX_VUS, K6_IMAGE, K6_CPUS, K6_MEMORY,
#          ALLOW_DIRTY(0), SKIP_BUILD(0, s2.sh와 같은 의미)
# 스냅샷: 기준 스냅샷 복원 → bash loadtest/seed/s7_ipo.sh → ALLOW_SUBSCRIPTIONS=1 BASELINE_DIR=loadtest/data/baseline-s7 bash loadtest/baseline.sh save
#   (확정 청약 10만 건은 SQL로 넣은 측정 전용 합성 데이터라 계좌 쪽 데이터가 없다. 마감시키지 않는다. 자세한 한계는 seed/s7_ipo.sh 머리말)
# 볼 것: 요청 종류별(kind: subscribe, list) 응답 시간과 밀린 요청(요약.md), 청약 서비스 Hikari(report.md), 컨테이너 CPU(자원.md, Redis 포함), 쿼리 통계
#   k6 시작 직후에는 run-k6.sh가 입장 허가를 Redis에 한꺼번에 넣어 Redis CPU가 상한(0.5코어)까지 오른다. Redis CPU는 그 구간을 빼고 본다
# 워밍업: s2.sh와 같은 기준(응답 시간 추세가 평평해질 때까지). 다만 무너진 상태(응답 수십 초)로 고정돼도 평평하다고 판정할 수 있어 마지막 구간 값을 진행.log에 남긴다
# 종료 코드: 0 끝 / 5 커밋하지 않은 변경 또는 컨테이너 jar가 로컬 jar와 다름 / 14 스냅샷 복원 실패 / 그 밖은 준비, 실행 실패. 실행별 k6 종료 코드(99 임계값)는 진행.log에 남기고 계속한다
set -uo pipefail
export MSYS_NO_PATHCONV=1
# s2.sh가 같은 이름의 변수 기본값(RATE=274 등)을 덮어쓰므로, S7 기본값은 source 전에 받아 둔다
S7_RATE="${RATE:-433}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/s2.sh"
RATE="$S7_RATE"
LIST_RATES="${LIST_RATES:-433 866}"; REPS="${REPS:-3}"; DURATION="${DURATION:-90s}"; GAP="${GAP:-30}"; EXPECT_RATE="${EXPECT_RATE:-1}"
BASELINE_DIR="${BASELINE_DIR:-$DIR/data/baseline-s7}"; STATS_INTERVAL="${STATS_INTERVAL:-5}"; WARM_LIST_RATE="${WARM_LIST_RATE:-216}"
ALL_CONTAINERS="$ALL_CONTAINERS ipo-redis"
BATCH=""; MANIFEST=""; STATS_PID=""; K6_NET=""

# 인자: 출력 파일, 간격(초). official.sh와 같은 형식("<epoch>\t<json>")으로 모든 컨테이너를 기록한다
stats_loop() {
  local f="$1" iv="$2" t0 out el
  while :; do
    t0="$(date +%s)"
    if out="$(docker stats --no-stream --format '{{json .}}' 2>&1)"; then
      printf '%s\n' "$out" | awk -v t="$t0" 'NF { sub(/\r$/, ""); print t "\t" $0 }' >> "$f"
    else
      printf '%s\tERROR\t%s\n' "$t0" "$(printf '%s' "$out" | head -1)" >> "$f"
    fi
    el=$(( $(date +%s) - t0 ))
    [ "$el" -lt "$iv" ] && sleep $(( iv - el ))
  done
}
stats_stop() { [ -n "$STATS_PID" ] && { kill "$STATS_PID" 2>/dev/null; wait "$STATS_PID" 2>/dev/null; STATS_PID=""; }; return 0; }

# 인자: run_id, 공모주 목록(쉼표), 청약 속도, 목록 속도, 시간(예: 90s). k6 종료 코드를 출력한다
k6s7() {
  (cd "$DIR" && K6_MODE=container K6_IMAGE="$K6_IMAGE" K6_CPUS="$K6_CPUS" K6_MEMORY="$K6_MEMORY" K6_LABEL="$BATCH" K6_NAME="k6-$1" K6_NETWORK="$K6_NET" \
    bash run-k6.sh s7 --ipo "${2%%,*}" --run-id "$1" -e "IPO_IDS=$2" -e "RATE=$3" -e "LIST_RATE=$4" -e "DURATION=$5" -e "EXPECT_RATE=$EXPECT_RATE" \
    -e "PRE_VUS=$PRE_VUS" -e "MAX_VUS=$MAX_VUS" -e "QUANTITY=$QUANTITY") > "$DIR/results/$1/k6-console.out" 2>&1
  echo $?
}
# 인자: 청약 속도, 초. 회원이 한 바퀴 돌 때마다 공모주를 하나씩 쓰므로 필요한 공모주 수 (+1 여유)
need_ipos() { echo $(( ($1 * $2 + USERS - 1) / USERS + 1 )); }
secs() { echo "${1%s}"; }
# 만들 공모주 이름 중 가장 긴 것의 길이 ("<이름>-l<목록 속도>-r<회차>-<번호>", 워밍업 "<이름>-W-<번호>"). 공모주 이름은 VARCHAR(30)
max_ipo_name_len() {
  local max=0 len n L
  n="$(need_ipos "$RATE" "$(secs "$DURATION")")"
  for L in $LIST_RATES; do
    len=$(( ${#BATCH} + 2 + ${#L} + 2 + ${#REPS} + 1 + ${#n} ))
    [ "$len" -gt "$max" ] && max=$len
  done
  if [ "$WARM" = 1 ]; then
    n="$(need_ipos "$WARM_RATE" $((WARM_MAX + 60)))"
    len=$(( ${#BATCH} + 3 + ${#n} ))
    [ "$len" -gt "$max" ] && max=$len
  fi
  echo "$max"
}

record_env() {
  {
    echo "batch=$BATCH git=$(git_repo rev-parse --short HEAD) branch=$(git_repo rev-parse --abbrev-ref HEAD) dirty=$DIRTY_N allow_dirty=${ALLOW_DIRTY:-0}"
    echo "rate=$RATE list_rates=$LIST_RATES reps=$REPS duration=$DURATION gap=$GAP expect_rate=$EXPECT_RATE users=$USERS"
    echo "baseline_dir=$BASELINE_DIR stats_interval=$STATS_INTERVAL warm_rate=$WARM_RATE warm_list_rate=$WARM_LIST_RATE"
    echo "k6_image=$K6_IMAGE k6_cpus=$K6_CPUS k6_memory=$K6_MEMORY pre_vus=$PRE_VUS max_vus=$MAX_VUS skip_build=${SKIP_BUILD:-0}"
    local c line
    for c in $ALL_CONTAINERS; do
      line="$(docker inspect -f '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}' "$c" 2>/dev/null)" || { echo "자원 $c 조회 실패"; continue; }
      echo "자원 $c cpu=$(awk -v n="${line%% *}" 'BEGIN { printf "%g", n / 1e9 }') mem=${line##* }"
    done
    echo "자원 k6 cpu=$K6_CPUS mem=$K6_MEMORY (실행마다 k6-<run_id> 컨테이너)"
  } >> "$OUT/env.txt"
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --batch) BATCH="$2"; shift 2 ;;
      *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
    esac
  done
  [ -n "$BATCH" ] || { echo "사용: $0 --batch <이름>" >&2; exit 1; }
  [ "$WARM" = 0 ] || [ "$WARM" = 1 ] || { echo "WARM은 0 또는 1: $WARM" >&2; exit 1; }
  USERS="$(($(grep -c . "$DIR/data/users.csv") - 1))"
  # 복원, 워밍업 뒤에 공모주 만들기가 실패하지 않게 시작 전에 이름 길이를 확인한다
  local name_len; name_len="$(max_ipo_name_len)"
  [ "$name_len" -le 30 ] || { echo "공모주 이름이 ${name_len}자가 되어 30자 제한을 넘습니다. --batch를 $((name_len - 30))자 이상 줄입니다: $BATCH" >&2; exit 1; }
  check_dirty || exit $?
  OUT="$DIR/results/$BATCH"; MANIFEST="$OUT/manifest.csv"
  [ -e "$OUT" ] && { echo "이미 있는 이름입니다: $OUT" >&2; exit 1; }
  mkdir -p "$OUT"
  LOG="$OUT/진행.log"
  pick_python || die 1 "Python 3.8 이상이 필요합니다"
  pick_compose
  trap 'stats_stop; docker ps --format "{{.Names}}" | grep "^k6-$BATCH" | xargs -r docker stop -t 30 >/dev/null 2>&1' EXIT

  if [ "${SKIP_BUILD:-0}" != 1 ]; then
    say "jar 빌드 (./gradlew build -x test)"
    (cd "$REPO" && ./gradlew build -x test -q) >> "$LOG" 2>&1 || die 1 "gradle 빌드 실패 (진행.log)"
    say "컨테이너 배포 (compose up -d --build)"
    compose "${COMPOSE_FILES[@]}" up -d --build >> "$LOG" 2>&1 || die 1 "compose up 실패 (진행.log)"
  else
    say "빌드, 배포 건너뜀 (SKIP_BUILD=1). jar 비교는 한다"
  fi
  wait_all_healthy 600 || die 1 "컨테이너가 600초 안에 모두 healthy가 되지 않음"
  local jars_info
  jars_info="$(check_jars)" || { printf '%s\n' "$jars_info" | tee -a "$LOG" >&2; die 5 "컨테이너 안 jar가 로컬 jar와 다름. 이 커밋으로 다시 빌드, 배포한다"; }
  printf '%s\n' "$jars_info" >> "$OUT/env.txt"

  say "S7 스냅샷 복원 ($BASELINE_DIR)"
  BASELINE_DIR="$BASELINE_DIR" bash "$DIR/baseline.sh" restore >> "$LOG" 2>&1 || die 14 "스냅샷 복원 실패 (진행.log)"
  wait_all_healthy 600 || die 1 "복원 뒤 컨테이너가 600초 안에 모두 healthy가 되지 않음"
  # 쿼리 통계: 스냅샷 DB에는 확장이 없을 수 있어 만든다 (compose.perf.yml이 미리 읽어 들이도록 설정한다)
  pg "create extension if not exists pg_stat_statements" > /dev/null 2>&1 || say "pg_stat_statements 확장을 만들지 못함 (쿼리 통계 없이 진행)"
  K6_NET="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' ipo-api-gateway | awk '{print $1}')"
  record_env
  echo "run_id,scenario,phase,repeat,target_rate,duration_s,expected_requests,ipo_ids,k6_start,k6_end,drain_s,drain_end" > "$MANIFEST"
  if [ "$STATS_INTERVAL" -gt 0 ]; then stats_loop "$OUT/docker-stats.raw" "$STATS_INTERVAL" & STATS_PID=$!; say "docker stats 기록 시작 (${STATS_INTERVAL}초 간격)"; fi

  say "토큰 재발급"
  (cd "$REPO" && py loadtest/seed/seed_users.py --run-id "tok-$BATCH" --refresh-tokens) >> "$LOG" 2>&1 || die 1 "토큰 재발급 실패"

  # 워밍업: 복원 때 서비스가 다시 떠서 JVM이 식은 상태라 꼭 한다 (s2.sh와 같은 판정). WARM=0은 스크립트 시험용
  local wrun="$BATCH-W" wipos t0 t1 kpid wrc d
  if [ "$WARM" = 1 ]; then
  mkdir -p "$DIR/results/$wrun"
  wipos="$(new_ipos "$wrun" "$(need_ipos "$WARM_RATE" $((WARM_MAX + 60)))")" || die 1 "워밍업 공모주 생성 실패"
  say "워밍업 시작: 청약 ${WARM_RATE} + 목록 ${WARM_LIST_RATE}건/초, 최소 ${WARM_MIN}초, 최대 ${WARM_MAX}초"
  t0="$(date +%s)"
  k6s7 "$wrun" "$wipos" "$WARM_RATE" "$WARM_LIST_RATE" "$((WARM_MAX + 60))s" > /dev/null &
  kpid=$!
  sleep 12
  kill -0 "$kpid" 2>/dev/null || die 8 "워밍업 k6가 시작하자마자 끝남 (results/$wrun/k6-console.out)"
  py "$(pypath "$DIR/verify/warmup.py")" --since $((t0 + 10)) --csv "$(pypath "$OUT/warmup.csv")" --prometheus "${PROMETHEUS_URL:-http://127.0.0.1:9090}" \
    --bucket "$WARM_BUCKET" --window "$WARM_WINDOW" --slope-pct "$WARM_SLOPE_PCT" --slope-ms "$WARM_SLOPE_MS" --consecutive "$WARM_CONSEC" \
    --min "$WARM_MIN" --max "$WARM_MAX" >> "$LOG" 2>&1; wrc=$?
  docker stop -t 30 "k6-$wrun" > /dev/null 2>&1
  wait "$kpid"; t1="$(date +%s)"
  [ "$wrc" -eq 0 ] || die 1 "워밍업이 정상 상태에 닿지 않음 (warmup.py 종료 코드 $wrc, warmup.csv)"
  d="$(drain "$wipos" "$DRAIN_TIMEOUT")" || die 6 "워밍업 소진 대기 시간 초과"
  echo "$wrun,S7,warmup,,$WARM_RATE+$WARM_LIST_RATE,$((t1 - t0)),,$(printf '%s' "$wipos" | tr ',' ';'),$t0,$t1,$d,$(date +%s)" >> "$MANIFEST"
  say "워밍업 끝: $((t1 - t0))초, 마지막 구간 $(tail -1 "$OUT/warmup.csv" | cut -d, -f4)ms (수십 초로 고정됐다면 이미 무너진 상태). 소진 ${d}초. ${GAP}초 대기"
  sleep "$GAP"
  else
    say "워밍업 건너뜀 (WARM=0, 시험용. 결과를 측정값으로 쓰지 않는다)"
  fi

  local rep L run ipos rc
  for rep in $(seq 1 "$REPS"); do
    for L in $LIST_RATES; do
      run="$BATCH-l$L-r$rep"
      mkdir -p "$DIR/results/$run"
      (cd "$REPO" && py loadtest/seed/seed_users.py --run-id "tok-$BATCH" --refresh-tokens) >> "$LOG" 2>&1 || die 1 "토큰 재발급 실패"
      ipos="$(new_ipos "$run" "$(need_ipos "$RATE" "$(secs "$DURATION")")")" || die 1 "$run 공모주 생성 실패"
      pg "select pg_stat_statements_reset()" > /dev/null 2>&1 || true
      t0="$(date +%s)"; rc="$(k6s7 "$run" "$ipos" "$RATE" "$L" "$DURATION")"; t1="$(date +%s)"
      d="$(drain "$ipos" "$DRAIN_TIMEOUT")" || { say "!! $run 소진 대기 시간 초과"; d=timeout; }
      echo "$run,S7,measure,$rep,$RATE+$L,$((t1 - t0)),,$(printf '%s' "$ipos" | tr ',' ';'),$t0,$t1,$d,$(date +%s)" >> "$MANIFEST"
      pg "select calls, round(mean_exec_time::numeric,3) mean_ms, round(max_exec_time::numeric,1) max_ms, round(total_exec_time::numeric,1) total_ms, left(regexp_replace(query, '\s+', ' ', 'g'), 160) from pg_stat_statements order by total_exec_time desc limit 20" \
        > "$OUT/slow-queries-$run.txt" 2>&1 || true
      (cd "$DIR" && py report.py --run-id "$run") > "$DIR/results/$run/report.out" 2>&1 || say "  $run report.py 실패 (report.out)"
      say "$run k6=$rc 소진=${d}초"
      sleep "$GAP"
    done
  done
  stats_stop

  say "후처리: 자원.md, 요약.md"
  if [ "$STATS_INTERVAL" -gt 0 ]; then
    py "$(pypath "$DIR/verify/docker_stats.py")" "$(pypath "$OUT/docker-stats.raw")" --manifest "$(pypath "$MANIFEST")" --env "$(pypath "$OUT/env.txt")" \
      --csv "$(pypath "$OUT/docker-stats.csv")" --md "$(pypath "$OUT/자원.md")" 2> "$OUT/자원.err" || say "후처리: docker stats 정리 실패 (자원.err)"
  fi
  py - "$(pypath "$DIR/results")" "$BATCH" > "$OUT/요약.md" <<'PYEOF'
import glob, json, os, statistics, sys
root, batch = sys.argv[1], sys.argv[2]
rows = {}
for d in sorted(glob.glob(os.path.join(root, f"{batch}-l*-r*"))):
    f = os.path.join(d, "summary.json")
    if not os.path.exists(f):
        continue
    m = json.load(open(f, encoding="utf-8"))["metrics"]
    g = lambda k, fld: (m.get(k) or {}).get(fld)
    name = os.path.basename(d)
    L = name[len(batch) + 2:].split("-r")[0]
    rows.setdefault(L, []).append((name, g("dropped_iterations", "count") or 0,
        g("http_reqs{kind:subscribe}", "count"), g("http_reqs{kind:list}", "count"),
        g("http_req_duration{kind:subscribe}", "avg"), g("http_req_duration{kind:subscribe}", "p(95)"),
        g("http_req_duration{kind:list}", "avg"), g("http_req_duration{kind:list}", "p(95)")))
# 실행이 중간에 멈춰 요약에 값이 없으면 "-"로 적는다 (중앙값은 값이 있는 실행만으로)
num = lambda v: "-" if v is None else f"{v:g}"
ms = lambda v: "-" if v is None else f"{v:.1f}"
def med(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None
print(f"# S7 요약 ({batch})\n")
print("| 실행 | 밀린 요청 | 청약 요청 | 목록 요청 | 청약 avg / p95 (ms) | 목록 avg / p95 (ms) |\n| --- | --- | --- | --- | --- | --- |")
for L in sorted(rows, key=int):
    for r in rows[L]:
        print(f"| {r[0]} | {r[1]:g} | {num(r[2])} | {num(r[3])} | {ms(r[4])} / {ms(r[5])} | {ms(r[6])} / {ms(r[7])} |")
# 밀린 요청이 있는 회차도 중앙값에 넣는다. S7에서는 서버가 느려져 속도를 못 낸 것 자체가 결과라서 빼면 무너진 결과가 요약에서 사라진다.
# 대신 밀린 회차 수를 같이 적는다 (밀린 회차의 응답 시간은 정한 속도를 보내지 못한 상태의 값)
print("\n## 목록 속도별 중앙값\n")
print("밀린 회차도 포함한다. 밀린 회차의 응답 시간은 정한 속도를 보내지 못한 상태의 값이라 밀리지 않은 회차와 같은 조건이 아니다.\n")
print("| 목록 건/초 | 밀린 회차 | 밀린 요청 | 청약 avg / p95 (ms) | 목록 avg / p95 (ms) |\n| --- | --- | --- | --- | --- |")
for L in sorted(rows, key=int):
    rs = rows[L]
    dropped_runs = sum(1 for r in rs if r[1] > 0)
    print(f"| {L} | {dropped_runs}/{len(rs)} | {num(med([r[1] for r in rs]))} | {ms(med([r[4] for r in rs]))} / {ms(med([r[5] for r in rs]))} | {ms(med([r[6] for r in rs]))} / {ms(med([r[7] for r in rs]))} |")
PYEOF
  say "== 끝: $OUT (요약.md, 자원.md, 실행별 report.md)"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
