#!/usr/bin/env bash
# 시나리오(s0~s4, g2)를 실행하고 원본을 results/<run_id>/에 저장한다 (summary.json, requests.log).
# 실행 위치(K6_MODE): auto(기본)는 k6가 설치돼 있으면 설치형, 없으면 grafana/k6 컨테이너. local은 설치형만, container는 컨테이너만 쓴다.
# 실행 위치는 측정 환경 기록에 적는다 (서비스와 같은 머신이면 k6도 CPU를 쓴다).
# 종료 코드 99는 임계값 위반이다. summary.json의 thresholds는 true가 위반(crossed), false가 통과다.
# 사용: ./run-k6.sh s0 --ipo <공모주 ID> --run-id <run_id> [--users data/users.csv] [-e KEY=VALUE]...
# 환경변수: K6_IMAGE(grafana/k6:1.3.0), K6_NETWORK(컨테이너 실행 시 compose 네트워크, 기본 ipo-subscription_default),
#          K6_MODE(auto, local, container), BASE_URL(설치형 기본 http://localhost:8080, 컨테이너 기본 http://api-gateway:8080),
#          K6_CPUS, K6_MEMORY(컨테이너 실행 시 k6 컨테이너의 CPU, 메모리 상한. 예: 1.0, 512m. 서비스와 같은 머신이면 k6도 CPU를 쓴다)
#          K6_LABEL(컨테이너 실행 시 loadtest.batch 라벨. official.sh가 중단될 때 자기가 띄운 k6만 골라 멈추는 데 쓴다)
#          K6_NAME(컨테이너 실행 시 컨테이너 이름. official.sh는 k6-<run_id>로 줘서 docker stats에서 실행별 k6를 구분한다)
set -euo pipefail
export MSYS_NO_PATHCONV=1
DIR="$(cd "$(dirname "$0")" && pwd)"
SCEN="${1:-}"; shift || true
[ -n "$SCEN" ] && [ -f "$DIR/k6/$SCEN.js" ] || { echo "사용: $0 <s0|s1|s2|s3|s4|g2> --ipo <ID> --run-id <run_id>" >&2; exit 1; }
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

MODE="${K6_MODE:-auto}"
case "$MODE" in auto|local|container) ;; *) echo "K6_MODE는 auto, local, container 중 하나여야 합니다: $MODE" >&2; exit 1 ;; esac
if [ "$MODE" = local ] && ! command -v k6 >/dev/null 2>&1; then
  echo "K6_MODE=local인데 k6가 설치돼 있지 않습니다" >&2; exit 1
fi

if [ "$MODE" = local ] || { [ "$MODE" = auto ] && command -v k6 >/dev/null 2>&1; }; then
  echo "k6 설치형으로 실행: $(k6 version | head -1)"
  ENVS=(-e "BASE_URL=${BASE_URL:-http://localhost:8080}" -e "IPO_ID=$IPO" -e "RUN_ID=$RUN_ID" -e "USERS_CSV=$DIR/$USERS")
  for e in ${EXTRA[@]+"${EXTRA[@]}"}; do ENVS+=(-e "$e"); done
  k6 run "${ENVS[@]}" --summary-export="$OUT/summary.json" --console-output="$OUT/requests.log" "$DIR/k6/$SCEN.js"
else
  echo "컨테이너로 실행: $K6_IMAGE"
  HOSTDIR="$(cd "$DIR" && (pwd -W 2>/dev/null || pwd))"
  ENVS=(-e "BASE_URL=${BASE_URL:-http://api-gateway:8080}" -e "IPO_ID=$IPO" -e "RUN_ID=$RUN_ID" -e "USERS_CSV=/loadtest/$USERS")
  for e in ${EXTRA[@]+"${EXTRA[@]}"}; do ENVS+=(-e "$e"); done
  LIMITS=()
  [ -n "${K6_CPUS:-}" ] && LIMITS+=(--cpus "$K6_CPUS")
  [ -n "${K6_MEMORY:-}" ] && LIMITS+=(--memory "$K6_MEMORY")
  [ -n "${K6_LABEL:-}" ] && LIMITS+=(--label "loadtest.batch=$K6_LABEL")
  [ -n "${K6_NAME:-}" ] && LIMITS+=(--name "$K6_NAME")
  docker run --rm --network "${K6_NETWORK:-ipo-subscription_default}" -v "$HOSTDIR:/loadtest" ${LIMITS[@]+"${LIMITS[@]}"} "${ENVS[@]}" "$K6_IMAGE" run \
    --summary-export="/loadtest/results/$RUN_ID/summary.json" --console-output="/loadtest/results/$RUN_ID/requests.log" "/loadtest/k6/$SCEN.js"
fi
echo "→ $OUT/summary.json, $OUT/requests.log"
