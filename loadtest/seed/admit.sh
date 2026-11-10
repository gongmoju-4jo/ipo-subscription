#!/usr/bin/env bash
# 부하 테스트용 입장 허가를 Redis에 한꺼번에 넣고(기본), 끝나면 지운다(--revoke). 회원 CSV의 회원 전원 x 공모주마다 허가 키 하나.
#
# 게이트웨이는 입장 허가가 있는 청약 신청만 서비스로 보낸다 (AdmissionCheckFilter). 부하 테스트는 서비스 처리량을 재므로
# 대기열을 거치지 않고 허가를 미리 만들어 둔다. 측정 중에도 청약마다 허가 확인(Redis 조회 1회)은 실제와 같이 일어난다.
# 대기열 자체의 효과(진입, 순서 조회, 입장 처리)는 이 방식으로 재지 않는다.
#
# run-k6.sh가 k6 실행 전에 넣고, k6가 끝나면(실패, 임계값 위반, Ctrl+C 포함) --revoke로 지운다.
# 지우는 이유: compose.perf.yml의 Redis는 --maxmemory 200mb(noeviction)이고 허가 키는 1개에 약 180바이트다 (redis:7에서 10만 개 약 18MB).
#   정식 측정 한 번(워밍업 + S1 단계 5개 x 3회)에 키가 수십만 개라 지우지 않으면 같은 날 다음 측정에서 Redis 쓰기가 실패한다.
#   스크립트가 강제 종료돼 지우지 못한 키는 유효 시간(ADMIT_TTL_MS)이 지나면 사라진다.
#
# 사용: ./admit.sh --users <회원 CSV> --ipos <공모주 ID[,공모주 ID...]> [--revoke] [--print]
#   --users: 머리줄에 userId 열이 있는 CSV (loadtest/data/users.csv 형식. 값에 쉼표, 따옴표가 없는 CSV만)
#   --ipos: 쉼표로 구분한 공모주 ID. 공백, 빈 항목은 건너뛴다
#   --revoke: 같은 회원 x 공모주의 허가 키를 지운다 (없는 키여도 성공)
#   --print: Redis에 보내지 않고 보낼 명령(RESP)을 출력만 한다 (테스트용)
# 환경변수: REDIS_CONTAINER(기본 ipo-redis)
#          ADMIT_TTL_MS(허가 유효 시간 ms, 기본 21600000 = 6시간. k6 시나리오 중 가장 긴 g2.js 상한(maxDuration 6h)과 같다)
# 키 형식은 게이트웨이 QueueRedisRepository.admittedKey와 같다: queue:{ipoId}:admitted:{userId} (UUID 소문자). 값은 넣은 시각(epoch ms)
# 종료 코드: 0 성공 / 1 입력 오류, Redis 오류(응답 수가 회원 수 x 공모주 수와 다름 포함)
set -euo pipefail
export MSYS_NO_PATHCONV=1

REDIS_CONTAINER="${REDIS_CONTAINER:-ipo-redis}"
ADMIT_TTL_MS="${ADMIT_TTL_MS:-21600000}"
USERS=""; IPOS=""; PRINT=0; REVOKE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --users) [ $# -ge 2 ] || { echo "--users에 값이 필요합니다" >&2; exit 1; }; USERS="$2"; shift 2 ;;
    --ipos) [ $# -ge 2 ] || { echo "--ipos에 값이 필요합니다" >&2; exit 1; }; IPOS="$2"; shift 2 ;;
    --revoke) REVOKE=1; shift ;;
    --print) PRINT=1; shift ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done
if [ -z "$USERS" ] || [ ! -f "$USERS" ]; then
  echo "--users 회원 CSV가 없습니다: ${USERS}" >&2
  exit 1
fi
case "$ADMIT_TTL_MS" in ''|*[!0-9]*|0*) echo "ADMIT_TTL_MS는 1 이상 정수(앞자리 0 없이): ${ADMIT_TTL_MS}" >&2; exit 1 ;; esac

UUID_RE='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
IPO_LIST="$(printf '%s' "$IPOS" | tr ',' '\n' | tr -d ' \r\t' | grep -v '^$' || true)"
[ -n "$IPO_LIST" ] || { echo "--ipos 공모주 ID가 없습니다" >&2; exit 1; }
BAD_IPO="$(printf '%s\n' "$IPO_LIST" | grep -Ev "$UUID_RE" || true)"
[ -z "$BAD_IPO" ] || { echo "UUID가 아닌 공모주 ID: ${BAD_IPO}" >&2; exit 1; }

# 회원 CSV x 공모주 → 'SET key now PX ttl' (--revoke면 'DEL key')을 RESP로 만든다 (redis-cli --pipe 입력 형식)
resp() {
  printf '%s\n' "$IPO_LIST" | awk -v users="$USERS" -v ttl="$ADMIT_TTL_MS" -v now="$(date +%s)000" -v re="$UUID_RE" -v revoke="$REVOKE" '
    function bulk(s) { return "$" length(s) "\r\n" s "\r\n" }
    BEGIN {
      col = 0; n = 0
      while ((getline line < users) > 0) {
        sub(/\r$/, "", line)
        if (line ~ /^[ \t]*$/) continue
        nf = split(line, f, ",")
        if (col == 0) {
          for (i = 1; i <= nf; i++) if (f[i] == "userId") col = i
          if (col == 0) { print "회원 CSV 머리줄에 userId 열이 없습니다" > "/dev/stderr"; exit 2 }
          continue
        }
        if (f[col] !~ re) { print "UUID가 아닌 userId: " f[col] > "/dev/stderr"; exit 2 }
        user[++n] = tolower(f[col])
      }
      if (n == 0) { print "회원 CSV에 회원이 없습니다" > "/dev/stderr"; exit 2 }
    }
    {
      ipo = tolower($0)
      for (i = 1; i <= n; i++) {
        key = "queue:" ipo ":admitted:" user[i]
        if (revoke == 1) printf "*2\r\n%s%s", bulk("DEL"), bulk(key)
        else printf "*5\r\n%s%s%s%s%s", bulk("SET"), bulk(key), bulk(now), bulk("PX"), bulk(ttl)
      }
    }'
}

# resp()와 같은 규칙(빈 줄, 공백만 있는 줄 제외, 머리줄 제외)으로 센 회원 수
count_users() {
  tr -d '\r' < "$USERS" | grep -cv '^[[:space:]]*$' | awk '{ print $1 - 1 }'
}

if [ "$PRINT" = 1 ]; then
  resp
  exit 0
fi

N_USERS="$(count_users)"
N_IPOS="$(printf '%s\n' "$IPO_LIST" | wc -l | tr -d ' ')"
EXPECTED=$((N_USERS * N_IPOS))
ACTION="넣기"; [ "$REVOKE" = 1 ] && ACTION="지우기"

# resp()가 입력 오류로 멈추면 pipefail로 여기서 실패한다 (Redis에는 아무것도 보내지 않은 상태)
OUT="$(resp | docker exec -i "$REDIS_CONTAINER" redis-cli --pipe 2>&1)" || { echo "입장 허가 ${ACTION} 실패: ${OUT}" >&2; exit 1; }
# redis-cli --pipe 마지막 요약 줄: "errors: <오류 수>, replies: <응답 수>"
SUMMARY="$(printf '%s\n' "$OUT" | grep '^errors:' | tail -1)"
if [ "$SUMMARY" != "errors: 0, replies: ${EXPECTED}" ]; then
  echo "입장 허가 ${ACTION} 중 오류 (기대 응답 ${EXPECTED}개): ${OUT}" >&2
  exit 1
fi
if [ "$REVOKE" = 1 ]; then
  echo "입장 허가 지움 (회원 ${N_USERS}명 x 공모주 ${N_IPOS}개)"
else
  echo "입장 허가 ${EXPECTED}개 (회원 ${N_USERS}명 x 공모주 ${N_IPOS}개, 유효 $((ADMIT_TTL_MS / 1000))초)"
fi
