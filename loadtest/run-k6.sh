#!/usr/bin/env bash
# 시나리오(s0~s4)를 실행하고 원본을 results/<run_id>/에 저장한다 (summary.json, requests.log).
# k6가 설치돼 있으면 그것을, 없으면 grafana/k6 컨테이너를 쓴다 (실행 위치는 환경 기록표에 적는다).
# 종료 코드 99는 임계값 위반이다. summary.json의 thresholds는 true가 위반(crossed), false가 통과다.
# 사용: ./run-k6.sh s0 --ipo <공모주 ID> --run-id <run_id> [--users data/users.csv] [-e KEY=VALUE]...
# 환경변수: K6_IMAGE(grafana/k6:1.3.0), K6_NETWORK(컨테이너 실행 시 compose 네트워크, 기본 ipo-subscription_default),
#          BASE_URL(설치형 기본 http://localhost:8080, 컨테이너 기본 http://api-gateway:8080)
set -euo pipefail
export MSYS_NO_PATHCONV=1
DIR="$(cd "$(dirname "$0")" && pwd)"
SCEN="${1:-}"; shift || true
[ -n "$SCEN" ] && [ -f "$DIR/k6/$SCEN.js" ] || { echo "사용: $0 <s0|s1|s2|s3|s4> --ipo <ID> --run-id <run_id>" >&2; exit 1; }
IPO=""; RUN_ID=""; USERS="data/users.csv"; EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --ipo) IPO="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    --users) USERS="$2"; shift 2 ;;
    -e) EXTRA+=("$2"); shift 2 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done
[ -n "$IPO" ] && [ -n "$RUN_ID" ] || { echo "--ipo, --run-id가 필요합니다" >&2; exit 1; }
OUT="$DIR/results/$RUN_ID"; mkdir -p "$OUT"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.3.0}"

if command -v k6 >/dev/null 2>&1; then
  echo "k6 설치형으로 실행: $(k6 version | head -1)"
  ENVS=(-e "BASE_URL=${BASE_URL:-http://localhost:8080}" -e "IPO_ID=$IPO" -e "RUN_ID=$RUN_ID" -e "USERS_CSV=$DIR/$USERS")
  for e in ${EXTRA[@]+"${EXTRA[@]}"}; do ENVS+=(-e "$e"); done
  k6 run "${ENVS[@]}" --summary-export="$OUT/summary.json" --console-output="$OUT/requests.log" "$DIR/k6/$SCEN.js"
else
  echo "k6가 없어 컨테이너로 실행: $K6_IMAGE"
  HOSTDIR="$(cd "$DIR" && (pwd -W 2>/dev/null || pwd))"
  ENVS=(-e "BASE_URL=${BASE_URL:-http://api-gateway:8080}" -e "IPO_ID=$IPO" -e "RUN_ID=$RUN_ID" -e "USERS_CSV=/loadtest/$USERS")
  for e in ${EXTRA[@]+"${EXTRA[@]}"}; do ENVS+=(-e "$e"); done
  docker run --rm --network "${K6_NETWORK:-ipo-subscription_default}" -v "$HOSTDIR:/loadtest" "${ENVS[@]}" "$K6_IMAGE" run \
    --summary-export="/loadtest/results/$RUN_ID/summary.json" --console-output="/loadtest/results/$RUN_ID/requests.log" "/loadtest/k6/$SCEN.js"
fi
echo "→ $OUT/summary.json, $OUT/requests.log"
