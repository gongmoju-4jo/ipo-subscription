#!/usr/bin/env bash
# G2 대량 환불 스냅샷용 공모주를 넣는다: 워밍업 공모주 W(케이스 A 모양)와 측정 공모주 M. ID를 "W1 <id>", "M <id>" 꼴로 출력한다.
#   - 일반청약 물량 = 청약 수 x 평균 청약 수량 / 69 (경쟁률 69:1, 주식 수 기준)를 반올림하고 홀수면 1을 더한다
#     (균등 물량 = 물량 / 2이고 DB 제약이 균등 x 2 >= 물량이라 짝수여야 한다). A 평균 172주, B 평균 53주 (k6/g2-quantity.js)
#     예: A 1,000건 → 2,494주, B 1,000건 → 768주
#   - 모두 종료 시각 10년 뒤 (new_ipo.sh --far). 측정할 때 종료 시각을 과거로 바꿔 마감시킨다
#   - 다른 OPEN 공모주(개발용 시드 등)도 종료 시각을 10년 뒤로 옮긴다. 스냅샷을 복원했을 때나 측정 중에 저절로 마감되지 않게
#     (baseline.sh의 ALLOW_SUBSCRIPTIONS=1 save가 종료 시각이 지난 OPEN 공모주를 거부한다)
#   - 공모가 15,000원, 최소 10주, 최대 10,000주 (new_ipo.sh 기본값)
# 사용: bash seed/g2_ipo.sh --case A|B --count N [--warmups 1] [--warmup-count 1000] [--name 이름] [--out 파일]
#   --count: M의 청약 수. 케이스 B는 50의 배수 (수량 분포가 50건 단위)
#   --warmups: W 개수 (연습 P1은 2). 0이면 W 없이 M만
#   --warmup-count: W 하나의 청약 수 (케이스 A 모양)
#   --out: 출력과 같은 내용("W1 <id>" 줄들, "M <id>")을 파일로도 쓴다
# 환경변수: PG_CONTAINER(ipo-postgres), PG_DB(ipo)
# 종료 코드: 0 정상 / 1 사용법 / 4 DB 실패
set -uo pipefail
export MSYS_NO_PATHCONV=1
SEED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PG_CONTAINER="${PG_CONTAINER:-ipo-postgres}"; PG_DB="${PG_DB:-ipo}"
CASE=""; COUNT=""; WARMUPS=1; WARMUP_COUNT=1000; NAME=""; OUT=""

say() { printf '%s %s\n' "$(date '+%F %T')" "$*" >&2; }
die() { local c="$1"; shift; say "!! $*"; exit "$c"; }

parse_args() {
  while [ $# -gt 0 ]; do
    [ $# -ge 2 ] || die 1 "옵션 값이 없다: $1"
    case "$1" in
      --case) CASE="$2"; shift 2 ;;
      --count) COUNT="$2"; shift 2 ;;
      --warmups) WARMUPS="$2"; shift 2 ;;
      --warmup-count) WARMUP_COUNT="$2"; shift 2 ;;
      --name) NAME="$2"; shift 2 ;;
      --out) OUT="$2"; shift 2 ;;
      *) die 1 "알 수 없는 옵션: $1" ;;
    esac
  done
  case "$CASE" in A|B) ;; *) die 1 "--case는 A 또는 B: $CASE" ;; esac
  case "$COUNT" in ''|*[!0-9]*|0*) die 1 "--count는 1 이상의 정수: $COUNT" ;; esac
  case "$WARMUPS" in [0-9]) ;; *) die 1 "--warmups는 0 ~ 9: $WARMUPS" ;; esac
  case "$WARMUP_COUNT" in ''|*[!0-9]*|0*) die 1 "--warmup-count는 1 이상의 정수: $WARMUP_COUNT" ;; esac
  [ "$CASE" = A ] || [ $((COUNT % 50)) = 0 ] || die 1 "케이스 B의 --count는 50의 배수 (수량 분포가 50건 단위): $COUNT"
  [ -n "$NAME" ] || NAME="g2-${CASE}${COUNT}-$(date +%m%d-%H%M%S)"
}

# 평균 청약 수량 (k6/g2-quantity.js와 같다)
avg_quantity() { case "$1" in A) echo 172 ;; B) echo 53 ;; esac; }

# 일반청약 물량: round(n x 평균 / 69), 홀수면 + 1
retail_for() {
  local q r; q="$(avg_quantity "$1")"
  r=$(( ($2 * q * 2 + 69) / 138 ))
  [ $((r % 2)) = 0 ] || r=$((r + 1))
  [ "$r" -ge 2 ] || r=2
  echo "$r"
}

new_ipo() { bash "$SEED_DIR/new_ipo.sh" --far --retail "$2" --name "$1" | tr -d '\r'; }

# W, M을 뺀 OPEN 공모주 중 종료 시각이 5년 안인 것을 10년 뒤로 옮긴다. 옮긴 수를 출력
push_open() {
  docker exec -i "$PG_CONTAINER" psql -U postgres -d "$PG_DB" -v ON_ERROR_STOP=1 -qtA <<'SQL' | tr -d '\r'
WITH u AS (
  UPDATE subscription.ipo
     SET subscription_end_at = now() + interval '10 years',
         refund_date = ((now() + interval '10 years') AT TIME ZONE 'Asia/Seoul')::date + 3,
         version = version + 1, updated_at = now(), updated_by = 'SYSTEM:loadtest'
   WHERE status = 'OPEN' AND subscription_end_at < now() + interval '5 years'
  RETURNING 1)
SELECT count(*) FROM u;
SQL
}

is_uuid() { case "$1" in ????????-????-????-????-????????????) return 0 ;; *) return 1 ;; esac; }

main() {
  parse_args "$@"
  local lines="" i id rw rmq moved
  rw="$(retail_for A "$WARMUP_COUNT")"; rmq="$(retail_for "$CASE" "$COUNT")"
  for i in $(seq 1 "$WARMUPS"); do
    id="$(new_ipo "$NAME-W$i" "$rw")" && is_uuid "$id" || die 4 "W$i 공모주 넣기 실패: $id"
    lines="${lines}W$i $id"$'\n'
    say "W$i $id (케이스 A, 청약 ${WARMUP_COUNT}건 기준, 일반청약 물량 ${rw}주)"
  done
  id="$(new_ipo "$NAME-M" "$rmq")" && is_uuid "$id" || die 4 "M 공모주 넣기 실패: $id"
  lines="${lines}M $id"$'\n'
  say "M $id (케이스 $CASE, 청약 ${COUNT}건 기준, 일반청약 물량 ${rmq}주)"
  moved="$(push_open)" || die 4 "다른 OPEN 공모주 종료 시각 옮기기 실패 (W, M은 넣었음)"
  say "다른 OPEN 공모주 ${moved}개의 종료 시각을 10년 뒤로 옮김"
  printf '%s' "$lines"
  if [ -n "$OUT" ]; then mkdir -p "$(dirname "$OUT")" && printf '%s' "$lines" > "$OUT" || die 4 "--out 쓰기 실패: $OUT"; fi
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
