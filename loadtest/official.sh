#!/usr/bin/env bash
# 정식 측정: S0, S1 각각에서 G1(처리량, 응답, 오류)과 G2(접수→확정 p95, 소진 시간)를 판정할 수 있게 실행한다.
# 판정은 부하가 끝난 뒤 verify/judge.py가 한다 (부하 중에는 k6와 Prometheus 수집 외에 아무것도 돌리지 않는다).
#
# 외부 요인을 줄이는 방법
#  - 모든 부하 실행(회차, 단계) 뒤에 이번 공모주의 PENDING 0 + 전체 Consumer lag 0 + outbox 미발행 0을 확인하고 GAP초 쉰 뒤 다음 실행을 한다
#    (앞 실행의 처리 대기가 다음 실행의 G2에 섞이지 않게)
#  - S1은 속도 단계마다 독립 k6 실행이다. 단계 전환(ramp)과 앞 단계 backlog가 없다
#  - S1은 단계마다 공모주를 돌려 모든 요청이 확정 경로를 타게 한다 (IPO_IDS). 이중청약 거절이 섞이지 않는다
#  - 시작 전 gateway, subscription, account를 재시작하고(콜드 상태), 워밍업 회차도 기록한다
#  - 커밋하지 않은 변경이 있으면 실행하지 않는다 (어떤 버전으로 쟀는지 추적하기 위해). 커밋, 이미지 생성 시각을 env.txt에 남긴다
# 불가피한 것(한계): k6와 서비스가 같은 PC, Prometheus 수집, 토큰 재발급 중 로그인 요청(S0 끝~S1 시작 사이 대기 중에만), S1 공모가 1,000원 종목(금액만 다르고 경로는 같다고 보지만 확인하지 못함)
#
# 사용: bash official.sh [--batch 이름]    환경변수: WCAP(20) GAP(120) STAGES(100,200,300,433) DUR(90) S1_PRICE(1000) P95_LIMIT(10) DRAIN_LIMIT(10) ALLOW_DIRTY(0)
set -uo pipefail
export MSYS_NO_PATHCONV=1 PYTHONUTF8=1
DIR="$(cd "$(dirname "$0")" && pwd)"; REPO="$(dirname "$DIR")"; cd "$DIR" || exit 1
BATCH="official-$(date +%m%d-%H%M)"
while [ $# -gt 0 ]; do case "$1" in --batch) BATCH="$2"; shift 2 ;; *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;; esac; done
WCAP="${WCAP:-20}"; GAP="${GAP:-120}"; STAGES="${STAGES:-100,200,300,433}"; DUR="${DUR:-90}"; S1_PRICE="${S1_PRICE:-1000}"
P95_LIMIT="${P95_LIMIT:-10}"; DRAIN_LIMIT="${DRAIN_LIMIT:-10}"; TOKEN_MAX_MIN=55
OUT="$DIR/results/$BATCH"; mkdir -p "$OUT"; LOG="$OUT/진행.log"; MANIFEST="$OUT/manifest.csv"
say() { echo "$(date '+%F %T') $*" | tee -a "$LOG"; }
PSQL() { docker exec ipo-postgres psql -U postgres -d ipo -tA -F ' ' "$@"; }
SVCS="ipo-api-gateway ipo-subscription-service ipo-account-service"
lag_total() { docker exec ipo-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --all-groups 2>/dev/null | awk 'NF>=6 && $1!="GROUP" && $6 ~ /^[0-9]+$/ {s+=$6} END{print s+0}'; }
dlt_total() { t=0; for x in subscription.requested margin.reserve-requested margin.reserved margin.reserve-failed margin.void-requested margin.voided ipo.closed allocation.result margin.settled allocation.finished; do
    n=$(docker exec ipo-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:29092 --topic "$x.DLT" 2>/dev/null | awk -F: '{a+=$3}END{print a+0}'); t=$((t+n)); done; echo $t; }
metric() { python -X utf8 -c "import json;m=json.load(open('$(cygpath -m "$DIR/results/$1/summary.json")',encoding='utf-8'))['metrics'];print(m['$2']['$3'])"; }
sqlin() { local s=""; for i in ${1//,/ }; do s="$s'$i',"; done; echo "${s%,}"; }
drain_ipos() { # 인자: 쉼표로 구분한 공모주 ID. 걸린 초를 출력, 실패하면 -1
  local t1=$(date +%s) in; in=$(sqlin "$1"); for i in $(seq 1 1800); do
    p=$(PSQL -c "select count(*) from subscription.subscription where status in ('PENDING','COMPENSATING') and ipo_id in ($in)")
    l=$(lag_total); o=$(PSQL -c "select (select count(*) from subscription.outbox where published_at is null)+(select count(*) from account.outbox where published_at is null)")
    [ "${p:-1}" = 0 ] && [ "${l:-1}" = 0 ] && [ "${o:-1}" = 0 ] && echo $(( $(date +%s) - t1 )) && return; sleep 2; done; echo -1; }
token_guard() { [ $(( ($(date +%s) - TOKEN_AT)/60 )) -ge $TOKEN_MAX_MIN ] && say "!! 토큰 발급 후 ${TOKEN_MAX_MIN}분 경과. 중단" && exit 7; }

# ---------- 0. 버전 기록 ----------
cd "$REPO" || exit 1
DIRTY=$(git status --short | wc -l | tr -d ' ')
if [ "$DIRTY" != 0 ] && [ "${ALLOW_DIRTY:-0}" != 1 ]; then echo "커밋하지 않은 변경 ${DIRTY}건. 커밋한 뒤 실행하세요 (버전 추적). 무시하려면 ALLOW_DIRTY=1 (env.txt에 dirty로 기록됨)" >&2; git status --short >&2; exit 5; fi
{ echo "git=$(git rev-parse HEAD) short=$(git rev-parse --short HEAD) branch=$(git rev-parse --abbrev-ref HEAD) commit_time=$(git show -s --format=%cI HEAD) 변경파일=$DIRTY"
  for c in $SVCS ipo-allocation-service; do echo "$c image_created=$(docker inspect -f '{{.Created}}' "$(docker inspect -f '{{.Image}}' $c)") started=$(docker inspect -f '{{.State.StartedAt}}' $c)"; done
  echo "설정 WCAP=$WCAP GAP=$GAP STAGES=$STAGES DUR=$DUR S1_PRICE=$S1_PRICE P95_LIMIT=$P95_LIMIT DRAIN_LIMIT=$DRAIN_LIMIT"; } | tee "$OUT/env.txt" >> "$LOG"
python -X utf8 - "$OUT/env.txt" <<'PY' | tee -a "$LOG"
import re,sys,datetime as d
t=open(sys.argv[1],encoding='utf-8').read(); ct=d.datetime.fromisoformat(re.search(r'commit_time=(\S+)',t).group(1))
for m in re.finditer(r'(ipo-\S+) image_created=(\S+)',t):
    try: ic=d.datetime.fromisoformat(m.group(2).split('.')[0]+'+00:00')
    except Exception: continue
    if ic<ct: print(f"⚠️ {m.group(1)} 이미지({ic:%F %T} UTC)가 HEAD 커밋({ct:%F %T})보다 오래됐다. 이 커밋으로 다시 빌드했는지 확인할 것 (jar 빌드 → docker-compose up -d --build)")
PY
cd "$DIR" || exit 1

# ---------- 1. 예수금 확인 ----------
IFS=',' read -ra ST <<<"$STAGES"
S1_REQ=0; for R in "${ST[@]}"; do S1_REQ=$((S1_REQ + R*DUR)); done
NEED=$(( 150000*(WCAP+3) + (S1_REQ/1000+${#ST[@]}+1) * S1_PRICE*10 ))
MINBAL=$(PSQL -c "select min(a.cash_balance) from account.account a join account.users u on u.user_id=a.user_id where u.login_id like 'lt%'")
say "예수금 최소 ${MINBAL}원, 필요 약 ${NEED}원 (S0 최대 $((WCAP+3))회차 x 150,000원 + S1)"
[ "${MINBAL:-0}" -lt "$NEED" ] && say "!! 예수금 부족. seed_users.py --run-id deposit-xx --amount N 으로 입금 후 다시 실행 (입금 API로만)" && exit 4

# ---------- 2. 준비: 토큰, 재시작, 점검, 공모주 생성 ----------
CSV_AGE=$(( $(date +%s) - $(stat -c %Y "$DIR/data/users.csv") ))
if [ $CSV_AGE -lt 1500 ]; then say "토큰 재발급 생략 (발급 후 $((CSV_AGE/60))분)"; else say "토큰 재발급"; (cd "$REPO" && python -X utf8 loadtest/seed/seed_users.py --run-id "tok-$BATCH-0" --refresh-tokens 2>&1 | tail -1 | tee -a "$LOG"); CSV_AGE=0; fi
TOKEN_AT=$(( $(date +%s) - CSV_AGE ))
say "gateway, subscription, account 재시작"
(cd "$REPO" && docker-compose -f docker-compose.yml -f compose.perf.yml restart api-gateway subscription-service account-service >/dev/null 2>&1); RST=$(date +%s)
for c in $SVCS; do say "시작 시각 $c $(docker inspect -f '{{.State.StartedAt}}' $c)"; done
for i in $(seq 1 60); do ok=1; for c in $SVCS; do [ "$(docker inspect -f '{{.State.Health.Status}}' $c)" = healthy ] || ok=0; done
  up=$(curl -s -H 'Accept: application/json' localhost:8761/eureka/apps | grep -o '"status":"UP"' | wc -l | tr -d ' '); [ "$ok" = 1 ] && [ "$up" -ge 4 ] && break; sleep 5; done
say "healthy, Eureka UP=$up ($(( $(date +%s) - RST ))초). 45초 대기 (요청 없음)"; sleep 45
bash precheck.sh --run-id "$BATCH-precheck" >/dev/null 2>&1; PRC=$?; say "사전 점검 rc=$PRC (results/$BATCH-precheck/precheck.txt)"
[ $PRC -ne 0 ] && say "!! 사전 점검 실패. 중단" && exit 9
DLT0=$(dlt_total); say "DLT 합계(시작)=$DLT0"
S0IPOS=(); for k in $(seq 1 $((WCAP+3))); do S0IPOS+=("$(bash seed/new_ipo.sh --name "$BATCH-S0-$k" | tr -d '\r\n ')"); done
declare -A S1IPOS; for R in "${ST[@]}"; do n=$(( R*DUR/1000 + 2 )); l=""; for k in $(seq 1 $n); do l="$l,$(bash seed/new_ipo.sh --name "$BATCH-S1-$R-$k" --price "$S1_PRICE" | tr -d '\r\n ')"; done; S1IPOS[$R]="${l#,}"; done
say "공모주 미리 생성: S0 $((WCAP+3))개(공모가 15,000), S1 단계별 ${#ST[@]}묶음(공모가 $S1_PRICE)"
echo "run_id,구분,목표속도,공모주,t0,t1,소진대기_s" > "$MANIFEST"

# ---------- 3. S0: 회차마다 소진 확인 + 대기 ----------
K=0; AVGS=(); STABLE_AT=0
run_s0() { # $1=run_id $2=구분(워밍업|측정)
  token_guard; local ipo="${S0IPOS[$K]}"; K=$((K+1)); mkdir -p "$DIR/results/$1"
  local t0=$(date +%s); K6_CPUS=2 K6_MEMORY=1g bash run-k6.sh s0 --ipo "$ipo" --run-id "$1" > "$DIR/results/$1/k6-console.out" 2>&1; local rc=$?; local t1=$(date +%s)
  [ $rc -ne 0 ] && [ $rc -ne 99 ] && say "!! k6 비정상 종료 rc=$rc. 중단" && exit 8
  local d; d=$(drain_ipos "$ipo"); [ "$d" = -1 ] && say "!! 소진 대기 시간 초과. 중단" && exit 6
  echo "$1,$2,,$ipo,$t0,$t1,$d" >> "$MANIFEST"; LAST_AVG=$(metric "$1" http_req_duration avg)
  say "$1($2) 처리량=$(metric "$1" http_reqs rate) avg=${LAST_AVG}ms 소진대기=${d}s. ${GAP}초 대기 (요청 없음)"; sleep "$GAP"; }
stable_check() { python -X utf8 -c "import sys;a=[float(x) for x in sys.argv[1:]];w=a[-3:];m=sum(w)/3;sys.exit(0 if len(a)>=3 and all(abs(x-m)/m<=0.20 for x in w) else 1)" "${AVGS[@]}"; }
n=0; while [ $n -lt $WCAP ]; do n=$((n+1)); run_s0 "$BATCH-S0-n$n" 워밍업; AVGS+=("$LAST_AVG")
  stable_check && { STABLE_AT=$((n-2)); say "안정 판정: n${STABLE_AT}~n$n (평균 응답 연속 3회차가 각각 3회차 평균의 +-20% 이내)"; break; }; done
[ $STABLE_AT -eq 0 ] && say "!! 워밍업 ${WCAP}회차 내 안정 기준 미도달. 중단" && exit 2
for r in 1 2 3; do run_s0 "$BATCH-S0-r$r" 측정; done

# ---------- 4. S1: 속도 단계마다 독립 실행 (토큰은 S0 뒤 대기 중 재발급: 로그인 요청이 끼는 한계) ----------
CSV_AGE=$(( $(date +%s) - $(stat -c %Y "$DIR/data/users.csv") ))
if [ $(( ($(date +%s) - TOKEN_AT)/60 )) -ge 25 ]; then say "토큰 재발급 (S0~S1 사이, 요청 없는 구간)"; (cd "$REPO" && python -X utf8 loadtest/seed/seed_users.py --run-id "tok-$BATCH-1" --refresh-tokens 2>&1 | tail -1 | tee -a "$LOG"); TOKEN_AT=$(date +%s); say "재발급 뒤 ${GAP}초 대기"; sleep "$GAP"; fi
for R in "${ST[@]}"; do
  token_guard; run="$BATCH-S1-$R"; ipos="${S1IPOS[$R]}"; first="${ipos%%,*}"; mkdir -p "$DIR/results/$run"
  say "$run 시작 ${R}건/초 x ${DUR}초, 공모주 $(echo "$ipos" | tr ',' '\n' | wc -l)개"
  t0=$(date +%s); K6_CPUS=2 K6_MEMORY=1g bash run-k6.sh s1 --ipo "$first" --run-id "$run" -e "IPO_IDS=$ipos" -e "RATE=$R" -e "DURATION=${DUR}s" -e PRE_VUS=500 -e MAX_VUS=2000 > "$DIR/results/$run/k6-console.out" 2>&1; rc=$?; t1=$(date +%s)
  [ $rc -ne 0 ] && [ $rc -ne 99 ] && say "!! k6 비정상 종료 rc=$rc. 중단" && exit 8
  d=$(drain_ipos "$ipos"); [ "$d" = -1 ] && say "!! 소진 대기 시간 초과. 중단" && exit 6
  echo "$run,S1,$R,$(echo "$ipos" | tr ',' ';'),$t0,$t1,$d" >> "$MANIFEST"
  say "$run 처리량=$(metric "$run" http_reqs rate) avg=$(metric "$run" http_req_duration avg)ms 소진대기=${d}s. ${GAP}초 대기 (요청 없음)"; sleep "$GAP"
done
say "DLT 합계(종료)=$(dlt_total) (시작 $DLT0)"
say "== 부하 종료. 판정: python -X utf8 verify/judge.py results/$BATCH/manifest.csv --p95-limit $P95_LIMIT --drain-limit $DRAIN_LIMIT"
