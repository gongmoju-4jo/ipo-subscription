#!/usr/bin/env bash
# 배포 후 스모크: 게이트웨이(외부 주소)를 거쳐 가입 → 로그인 → 계좌 개설 → 입금을 한 번 실행한다
#   bash deploy/smoke.sh http://{고정 IP}
# 실행마다 새 테스트 회원(smk로 시작)을 만든다. 비밀번호는 실행마다 새로 만들고 출력하지 않는다
# 서비스가 Eureka에 등록되기 전에는 게이트웨이가 503을 줄 수 있어 가입은 최대 SMOKE_WAIT초 동안 다시 시도한다
set -euo pipefail

if [[ -z "${1:-}" ]]; then
  echo "사용법: bash deploy/smoke.sh 기본주소 (예: http://1.2.3.4)" >&2
  exit 2
fi
base="$1"
base="${base%/}"
wait_sec="${SMOKE_WAIT:-180}"

# Windows(Git Bash)에서는 python3가 스토어 바로 가기일 수 있어 python으로 대신한다
py=python3
"$py" -c '' 2>/dev/null || py=python
uuid() { "$py" -c 'import uuid; print(uuid.uuid4())'; }

body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT

# $1 메서드, $2 경로, $3 JSON 본문, $4 토큰(선택). 응답 본문은 body_file, 상태 코드는 표준 출력
call() {
  local auth=()
  [[ -n "${4:-}" ]] && auth=(-H "Authorization: Bearer $4")
  curl -sS -o "$body_file" -w '%{http_code}' --max-time 15 -X "$1" "${base}$2" \
    -H 'Content-Type: application/json' "${auth[@]}" -d "$3" || true   # 연결 실패면 curl이 000을 출력한다
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

login_id="smk$(date +%s)$((RANDOM % 1000))"
password="$("$py" -c 'import secrets; print(secrets.token_hex(8))')"
echo "== 스모크 (${base}, 회원 ${login_id})"

deadline=$((SECONDS + wait_sec))
while :; do
  status="$(call POST /api/v1/users "{\"loginId\":\"${login_id}\",\"password\":\"${password}\",\"name\":\"smoke\"}")"
  [[ "$status" == 201 ]] && break
  if [[ "$status" != 000 && "$status" != 502 && "$status" != 503 && "$status" != 504 ]] || (( SECONDS >= deadline )); then
    expect "가입" "$status" 201
  fi
  sleep 5
done
echo "  가입: 201"

status="$(call POST /api/v1/auth/login "{\"loginId\":\"${login_id}\",\"password\":\"${password}\"}")"
expect "로그인" "$status" 200
token="$("$py" -c 'import json,sys; print(json.load(sys.stdin)["data"]["accessToken"])' < "$body_file")"

status="$(call POST /api/v1/accounts "{\"requestId\":\"$(uuid)\"}" "$token")"
expect "계좌 개설" "$status" 200 201
account_id="$("$py" -c 'import json,sys; d=json.load(sys.stdin)["data"]; print(d.get("accountId") or d.get("id"))' < "$body_file")"

status="$(call POST "/api/v1/accounts/${account_id}/deposits" "{\"requestId\":\"$(uuid)\",\"amount\":10000}" "$token")"
expect "입금" "$status" 200 201

echo "== 스모크 통과"
