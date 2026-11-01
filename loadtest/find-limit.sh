#!/usr/bin/env bash
# S1 요청 속도를 정하기 위해 한계를 찾는다. 속도를 올려 가며 짧게 돌리고, 처음 한계에 닿는 속도와 추천 범위를 알려 준다.
# 한계 신호 (하나라도 해당하면 한계): 평균 응답 500ms 초과, 실패율 1% 이상, 밀린 반복(dropped_iterations) 1건 이상
# 한계에 닿은 첫 속도의 50~70%를 S1의 요청 속도로 쓴다.
#
# 사용: ./find-limit.sh [--rates "50 100 200 400 800"] [--duration 20s] [--users data/users.csv] [--all]
#   --all: 한계에 닿아도 나머지 속도까지 계속 돌린다
# 단계마다 새 공모주를 만든다. 결과는 results/limit-<시각>/limit.md, 단계별 원본은 results/limit-<시각>-r<속도>/에 남는다.
# 서비스를 새로 띄운 직후 첫 단계는 워밍업(JVM, 커넥션 풀)의 영향으로 낮게 나올 수 있다. 한계에 닿으면 그 속도를 한 번 더 확인한다.
# 환경변수: run-k6.sh, new_ipo.sh와 같다 (PG_CONTAINER, K6_MODE, K6_NETWORK, K6_CPUS, K6_MEMORY 등)
set -uo pipefail
export MSYS_NO_PATHCONV=1
DIR="$(cd "$(dirname "$0")" && pwd)"
# Windows의 Python은 /c/Users/... 형식 경로를 못 열어서 Python에 넘길 경로는 Windows 형식(pwd -W)으로 만든다
PYDIR="$(cd "$DIR" && (pwd -W 2>/dev/null || pwd))"
RATES="50 100 200 400 800"; DURATION="20s"; USERS="data/users.csv"; ALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --rates) RATES="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --users) USERS="$2"; shift 2 ;;
    --all) ALL=1; shift ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done
for r in $RATES; do case "$r" in ''|*[!0-9]*) echo "속도는 숫자여야 합니다: $r" >&2; exit 1 ;; esac; done

BASE="limit-$(date +%m%d-%H%M%S)"
OUT="$DIR/results/$BASE"; mkdir -p "$OUT"
{
  echo "# S1 한계 탐색 $BASE"
  echo
  echo "한계 신호: 평균 응답 500ms 초과, 실패율 1% 이상, 밀린 반복 1건 이상. 단계마다 새 공모주, 유지 시간 $DURATION."
  echo
  echo "| 목표 속도 (건/초) | 실제 처리량 | 평균 (ms) | p95 (ms) | 실패율 (%) | 밀린 반복 | 판정 | 원본 |"
  echo "|---|---|---|---|---|---|---|---|"
} > "$OUT/limit.md"
echo "단계별 결과 (한계 신호: 평균 500ms 초과, 실패율 1% 이상, 밀린 반복 1건 이상)"

LIMIT=""; LAST_OK=""
for RATE in $RATES; do
  RUN="$BASE-r$RATE"
  IPO=$("$DIR/seed/new_ipo.sh" --name "$RUN") || { echo "공모주 생성 실패" >&2; exit 1; }
  # 임계값을 어기면 종료 코드가 99지만 여기서는 직접 판정하므로 무시한다
  "$DIR/run-k6.sh" s1 --ipo "$IPO" --run-id "$RUN" --users "$USERS" -e "RATE=$RATE" -e "DURATION=$DURATION" > "$OUT/k6-r$RATE.out" 2>&1 || true
  ROW=$(python - "$PYDIR/results/$RUN/summary.json" "$RATE" "$RUN" <<'PY'
import json, sys
path, rate, run = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    m = json.load(open(path, encoding="utf-8"))["metrics"]
except Exception:
    print(f"| {rate} | - | - | - | - | - | 실행 실패 | `{run}` ||FAIL"); sys.exit()
thr = m["http_reqs"]["rate"]; avg = m["http_req_duration"]["avg"]; p95 = m["http_req_duration"]["p(95)"]
fail = m["http_req_failed"]["value"] * 100; drop = int(m.get("dropped_iterations", {}).get("count", 0))
limit = avg > 500 or fail >= 1 or drop > 0
why = [n for n, c in (("평균", avg > 500), ("실패", fail >= 1), ("밀림", drop > 0)) if c]
verdict = "한계 (" + ", ".join(why) + ")" if limit else "여유"
print(f"| {rate} | {thr:.1f} | {avg:.1f} | {p95:.1f} | {fail:.2f} | {drop} | {verdict} | `{run}` |" + ("|LIMIT" if limit else "|OK"))
PY
)
  STATUS="${ROW##*|}"; LINE="${ROW%|*}"
  echo "$LINE" >> "$OUT/limit.md"; echo "$LINE"
  if [ "$STATUS" = LIMIT ] || [ "$STATUS" = FAIL ]; then
    [ -z "$LIMIT" ] && LIMIT="$RATE"
    [ "$ALL" = 1 ] || break
  else
    LAST_OK="$RATE"
  fi
done

{
  echo
  if [ -n "$LIMIT" ]; then
    LO=$(( (LIMIT * 5 + 9) / 10 )); HI=$(( LIMIT * 7 / 10 ))
    echo "**한계에 닿은 첫 속도: ${LIMIT}건/초** (직전에 여유 있던 속도: ${LAST_OK:-없음})"
    echo
    echo "**S1 요청 속도 추천: 한계의 50~70% = ${LO}~${HI}건/초.** 한 값을 골라 S1의 \`RATE\`로 고정하고, 같은 시나리오의 모든 회차를 그 값으로 측정합니다."
  else
    echo "**한계에 닿지 않았습니다.** \`--rates\`에 더 큰 값을 주고 다시 실행하세요 (가장 큰 속도: ${LAST_OK:-없음})."
  fi
} >> "$OUT/limit.md"
echo; tail -4 "$OUT/limit.md"; echo "→ $OUT/limit.md"
