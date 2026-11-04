#!/usr/bin/env bash
# 배포 후 스모크: 게이트웨이를 거쳐 가입(처음 한 번) → 로그인 → 계좌(없으면 개설) → 입금을 실행한다
#   SMOKE_LOGIN_ID=... SMOKE_PASSWORD=... bash deploy/smoke.sh http://127.0.0.1:80
# 배포 서버에서 deploy.sh가 실행한다 (계정은 Parameter Store). 비밀번호가 HTTP로 인터넷에 나가지 않도록 서버 안의 게이트웨이로 보낸다
# 스모크 전용 계정 하나를 계속 쓴다. 회원과 계좌는 처음 한 번만 생기고 입금만 쌓인다
# 비밀번호는 출력하지 않고 프로세스 인자에도 넣지 않는다 (요청 본문은 표준 입력으로 curl에 넘긴다)
# 서비스가 Eureka에 등록되기 전에는 게이트웨이가 503을 줄 수 있어 첫 요청은 최대 SMOKE_WAIT초 동안 다시 시도한다
set -euo pipefail

if [[ -z "${1:-}" ]]; then
  echo "사용법: SMOKE_LOGIN_ID=... SMOKE_PASSWORD=... bash deploy/smoke.sh 기본주소 (예: http://1.2.3.4)" >&2
  exit 2
fi
base="${1%/}"
wait_sec="${SMOKE_WAIT:-180}"
login_id="${SMOKE_LOGIN_ID:-}"
password="${SMOKE_PASSWORD:-}"
# 회원가입 규칙과 같게 검사한다 (아이디 영문 소문자, 숫자 4~20자 / 비밀번호 8~20자)
if [[ ! "$login_id" =~ ^[a-z0-9]{4,20}$ ]]; then
  echo "SMOKE_LOGIN_ID가 비어 있거나 형식이 틀립니다 (영문 소문자, 숫자 4~20자)" >&2
  exit 2
fi
if (( ${#password} < 8 || ${#password} > 20 )); then
  echo "SMOKE_PASSWORD가 비어 있거나 길이가 틀립니다 (8~20자)" >&2
  exit 2
fi

# Windows(Git Bash)에서는 python3가 스토어 바로 가기일 수 있어 python으로 대신한다
py=python3
"$py" -c '' 2>/dev/null || py=python
uuid() { "$py" -c 'import uuid; print(uuid.uuid4())'; }
# JSON 문자열 값으로 안전하게 넣는다 (따옴표, 역슬래시가 든 비밀번호). 값은 표준 입력으로 넘긴다 (printf는 셸 내장이라 프로세스 인자에 남지 않는다)
jstr() { printf '%s' "$1" | "$py" -c 'import json,sys; print(json.dumps(sys.stdin.read()))'; }

body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT

# $1 메서드, $2 경로, $3 JSON 본문(GET은 빈 값), $4 토큰(선택). 응답 본문은 body_file, 상태 코드는 표준 출력
call() {
  local opts=(-sS -o "$body_file" -w '%{http_code}' --max-time 15 -X "$1" "${base}$2")
  [[ -n "${4:-}" ]] && opts+=(-H "Authorization: Bearer $4")
  [[ -n "${3:-}" ]] && opts+=(-H 'Content-Type: application/json' --data-binary @-)
  printf '%s' "${3:-}" | curl "${opts[@]}" || true   # 연결 실패면 curl이 000을 출력한다
}

expect() { # $1 단계 이름, $2 실제 상태, $3.. 허용 상태
  local name="$1" got="$2"; shift 2
  local ok
  for ok in "$@"; do [[ "$got" == "$ok" ]] && { echo "  ${name}: ${got}"; return 0; }; done
  echo "  ${name} 실패: HTTP ${got}" >&2
  head -c 500 "$body_file" >&2 || true
  echo >&2
  exit 1
}

echo "== 스모크 (${base}, 계정 ${login_id})"
creds="{\"loginId\":$(jstr "$login_id"),\"password\":$(jstr "$password")}"

# 가입: 처음이면 201, 이미 있으면 409(같은 아이디). 둘 다 다음 단계로 간다
deadline=$((SECONDS + wait_sec))
while :; do
  status="$(call POST /api/v1/users "{\"loginId\":$(jstr "$login_id"),\"password\":$(jstr "$password"),\"name\":\"smoke\"}")"
  [[ "$status" == 201 || "$status" == 409 ]] && break
  if [[ "$status" != 000 && "$status" != 502 && "$status" != 503 && "$status" != 504 ]] || (( SECONDS >= deadline )); then
    expect "가입" "$status" 201 409
  fi
  sleep 5
done
echo "  가입: ${status} ($([[ "$status" == 201 ]] && echo 새 계정 || echo 기존 계정))"

status="$(call POST /api/v1/auth/login "$creds")"
expect "로그인" "$status" 200
token="$("$py" -c 'import json,sys; print(json.load(sys.stdin)["data"]["accessToken"])' < "$body_file")"

status="$(call GET "/api/v1/accounts?size=1" "" "$token")"
expect "계좌 조회" "$status" 200
account_id="$("$py" -c 'import json,sys; i=json.load(sys.stdin)["data"]["items"]; print(i[0]["accountId"] if i else "")' < "$body_file")"
if [[ -z "$account_id" ]]; then
  status="$(call POST /api/v1/accounts "{\"requestId\":\"$(uuid)\"}" "$token")"
  expect "계좌 개설" "$status" 200 201
  account_id="$("$py" -c 'import json,sys; d=json.load(sys.stdin)["data"]; print(d.get("accountId") or d.get("id"))' < "$body_file")"
fi

status="$(call POST "/api/v1/accounts/${account_id}/deposits" "{\"requestId\":\"$(uuid)\",\"amount\":1000}" "$token")"
expect "입금" "$status" 200 201

echo "== 스모크 통과"
