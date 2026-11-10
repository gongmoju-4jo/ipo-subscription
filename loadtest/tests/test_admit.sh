#!/usr/bin/env bash
# seed/admit.sh, run-k6.sh의 입장 허가 넣기/지우기 단위 테스트 (Redis, k6 없이 돈다.
# --print로 만든 명령을 확인하고, Redis에 보내는 경로는 가짜 docker, k6 실행은 가짜 k6로 바꾼다)
# 사용: bash loadtest/tests/test_admit.sh
# shellcheck disable=SC2015
set -u
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADMIT="$HERE/../seed/admit.sh"
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); printf 'ok   %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf 'FAIL %s (%s)\n' "$1" "$2"; }
rc() { ( "$@" ) > /dev/null 2>&1; echo $?; }
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT

U1=1CA92C99-34F1-445F-A6C2-E7DD8830F4ED; U2=2ca92c99-34f1-445f-a6c2-e7dd8830f4ee
I1=8B2D0000-0000-4000-8000-000000000001; I2=8b2d0000-0000-4000-8000-000000000002
K11=queue:8b2d0000-0000-4000-8000-000000000001:admitted:1ca92c99-34f1-445f-a6c2-e7dd8830f4ed
K12=queue:8b2d0000-0000-4000-8000-000000000001:admitted:2ca92c99-34f1-445f-a6c2-e7dd8830f4ee
K21=queue:8b2d0000-0000-4000-8000-000000000002:admitted:1ca92c99-34f1-445f-a6c2-e7dd8830f4ed
K22=queue:8b2d0000-0000-4000-8000-000000000002:admitted:2ca92c99-34f1-445f-a6c2-e7dd8830f4ee
# CRLF, 끝의 빈 줄, 공백만 있는 줄
printf 'loginId,userId,accountId,accountId2,token\r\nlt0000,%s,a,b,t\r\nlt0001,%s,a,b,t\r\n  \r\n\r\n' "$U1" "$U2" > "$T/users.csv"

# RESP에서 키만 뽑는다 (SET, DEL 다음의 길이 줄 다음이 키)
keys() { tr -d '\r' | awk '(prev == "SET" || prev == "DEL") { getline; print } { prev = $0 }'; }

# ---- 키 형식: 게이트웨이 QueueRedisRepository.admittedKey와 같은 queue:{ipoId}:admitted:{userId}, UUID 소문자 ----
OUT="$(bash "$ADMIT" --users "$T/users.csv" --ipos "$I1, $I2,," --print)"
K="$(printf '%s' "$OUT" | keys)"
EXPECTED="$(printf '%s\n%s\n%s\n%s' "$K11" "$K12" "$K21" "$K22")"
[ "$K" = "$EXPECTED" ] && ok "회원 2명 x 공모주 2개 = 키 4개, 소문자 UUID, CRLF와 빈 줄, 공백 줄, 빈 공모주 항목 무시" || bad "키 형식" "$K"

# ---- RESP 형식: *5 SET key value PX ttl, 길이 값이 실제 길이와 같다 ----
LINES=()
while IFS= read -r line; do LINES+=("$line"); done <<< "$(printf '%s' "$OUT" | head -11 | tr -d '\r')"
[ "${LINES[0]}" = '*5' ] && [ "${LINES[2]}" = SET ] && [ "${LINES[8]}" = PX ] && [ "${LINES[10]}" = 21600000 ] \
  && ok "명령 한 개: *5 SET <키> <넣은 시각> PX 21600000 (기본 6시간)" || bad "RESP 형식" "${LINES[*]}"
len_ok=1
for i in 1 3 5 7 9; do
  [ "${LINES[$i]}" = "\$${#LINES[$((i + 1))]}" ] || len_ok=0
done
[ "$len_ok" = 1 ] && ok "RESP 길이 값이 실제 문자열 길이와 같다" || bad "RESP 길이" "${LINES[*]}"
case "${LINES[6]}" in
  *[!0-9]*|'') bad "넣은 시각" "${LINES[6]}" ;;
  *) [ "${#LINES[6]}" = 13 ] && ok "값은 epoch ms (13자리)" || bad "넣은 시각 자릿수" "${LINES[6]}" ;;
esac

TTL="$(ADMIT_TTL_MS=3600000 bash "$ADMIT" --users "$T/users.csv" --ipos "$I1" --print | tr -d '\r' | sed -n 11p)"
[ "$TTL" = 3600000 ] && ok "ADMIT_TTL_MS로 유효 시간을 바꾼다" || bad "ADMIT_TTL_MS" "$TTL"

printf 'userId,loginId\n%s,lt0000\n' "$U1" > "$T/firstcol.csv"
K="$(bash "$ADMIT" --users "$T/firstcol.csv" --ipos "$I2" --print | keys)"
[ "$K" = "$K21" ] && ok "userId가 첫 열이어도 읽는다" || bad "userId 첫 열" "$K"

# ---- --revoke: 넣은 키와 같은 키를 DEL ----
RK="$(bash "$ADMIT" --users "$T/users.csv" --ipos "$I1" --revoke --print | tr -d '\r')"
[ "$(printf '%s\n' "$RK" | grep -c '^\*2$')" = 2 ] && [ "$(printf '%s\n' "$RK" | sed -n 3p)" = DEL ] \
  && [ "$(printf '%s\n' "$RK" | keys)" = "$(printf '%s\n%s' "$K11" "$K12")" ] \
  && ok "--revoke: 넣은 키와 같은 키를 DEL (*2 DEL <키>)" || bad "--revoke 형식" "$RK"

# ---- 잘못된 입력은 Redis에 아무것도 보내지 않고 멈춘다 ----
printf 'loginId,accountId\nlt0000,a\n' > "$T/nouser.csv"
printf 'loginId,userId\nlt0000,not-a-uuid\n' > "$T/baduser.csv"
printf 'loginId,userId\n' > "$T/empty.csv"
[ "$(rc bash "$ADMIT" --users "$T/users.csv" --ipos "not-a-uuid" --print)" != 0 ] && ok "UUID가 아닌 공모주 ID → 실패" || bad "공모주 검증" "통과함"
[ "$(rc bash "$ADMIT" --users "$T/users.csv" --ipos " , " --print)" != 0 ] && ok "공모주 ID가 비면 실패" || bad "빈 공모주" "통과함"
[ "$(rc bash "$ADMIT" --users "$T/nouser.csv" --ipos "$I1" --print)" != 0 ] && ok "userId 열이 없으면 실패" || bad "userId 열" "통과함"
[ "$(rc bash "$ADMIT" --users "$T/baduser.csv" --ipos "$I1" --print)" != 0 ] && ok "UUID가 아닌 userId → 실패" || bad "userId 검증" "통과함"
[ "$(rc bash "$ADMIT" --users "$T/empty.csv" --ipos "$I1" --print)" != 0 ] && ok "회원이 없으면 실패" || bad "빈 회원" "통과함"
[ "$(rc bash "$ADMIT" --users "$T/none.csv" --ipos "$I1" --print)" != 0 ] && ok "회원 CSV가 없으면 실패" || bad "없는 CSV" "통과함"
ttl_ok=1
[ "$(ADMIT_TTL_MS='' bash "$ADMIT" --users "$T/users.csv" --ipos "$I1" --print | tr -d '\r' | sed -n 11p)" = 21600000 ] || ttl_ok=0
for ttl in 0 0123 -1 1.5 abc; do
  [ "$(ADMIT_TTL_MS=$ttl rc bash "$ADMIT" --users "$T/users.csv" --ipos "$I1" --print)" != 0 ] || ttl_ok=0
done
[ "$ttl_ok" = 1 ] && ok "ADMIT_TTL_MS가 0, 앞자리 0, 음수, 소수, 문자면 실패 (빈 값은 기본값)" || bad "ADMIT_TTL_MS 검증" "통과한 값 있음"
[ "$(rc bash "$ADMIT" --users "$T/users.csv" --ipos)" != 0 ] && [ "$(rc bash "$ADMIT" --users)" != 0 ] && ok "옵션 값이 빠지면 실패" || bad "옵션 값 누락" "통과함"
[ "$(rc bash "$ADMIT" --users "$T/users.csv" --ipos "$I1" --bogus)" != 0 ] && ok "모르는 옵션 → 실패" || bad "모르는 옵션" "통과함"

# ---- Redis에 보내기: redis-cli --pipe 응답 수가 회원 x 공모주와 같아야 성공 ----
mkdir -p "$T/bin" "$T/nocontainer"
# 가짜 docker: 받은 명령 수를 세어 redis-cli --pipe처럼 응답한다. FAKE_ERRORS가 있으면 오류 수로 쓰고,
# FAKE_LOG가 있으면 받은 명령 종류(SET, DEL)를 남긴다
cat > "$T/bin/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
in="$(tr -d '\r')"
n="$(printf '%s\n' "$in" | grep -c '^\*[25]$')"
if [ -n "${FAKE_LOG:-}" ]; then printf '%s\n' "$in" | grep -m1 -E '^(SET|DEL)$' >> "$FAKE_LOG"; fi
echo "All data transferred. Waiting for the last reply..."
echo "Last reply received from server."
echo "errors: ${FAKE_ERRORS:-0}, replies: $n"
FAKE_DOCKER
printf '#!/usr/bin/env bash\necho "Error response from daemon: No such container: ipo-redis" >&2\nexit 1\n' > "$T/nocontainer/docker"
chmod +x "$T/bin/docker" "$T/nocontainer/docker"

MSG="$(PATH="$T/bin:$PATH" bash "$ADMIT" --users "$T/users.csv" --ipos "$I1,$I2" 2>&1)"; RC=$?
[ "$RC" = 0 ] && [ "$MSG" = "입장 허가 4개 (회원 2명 x 공모주 2개, 유효 21600초)" ] && ok "Redis 응답 4개 → 성공, 넣은 수를 출력" || bad "Redis 넣기" "rc=$RC $MSG"
RC="$(FAKE_ERRORS=1 PATH="$T/bin:$PATH" rc bash "$ADMIT" --users "$T/users.csv" --ipos "$I1")"
[ "$RC" != 0 ] && ok "Redis 오류가 1건이라도 있으면 실패" || bad "Redis 오류" "rc=$RC"
MSG="$(PATH="$T/bin:$PATH" bash "$ADMIT" --users "$T/users.csv" --ipos "$I1" --revoke 2>&1)"; RC=$?
[ "$RC" = 0 ] && [ "$MSG" = "입장 허가 지움 (회원 2명 x 공모주 1개)" ] && ok "--revoke: 응답 수가 맞으면 성공" || bad "--revoke 실행" "rc=$RC $MSG"
RC="$(PATH="$T/nocontainer:$PATH" rc bash "$ADMIT" --users "$T/users.csv" --ipos "$I1")"
[ "$RC" != 0 ] && ok "Redis 컨테이너가 없으면 실패" || bad "컨테이너 없음" "rc=$RC"

# ---- run-k6.sh: k6 전에 넣고, k6가 끝나면(실패 포함) 지우며, k6 종료 코드를 그대로 돌려준다 ----
LT="$T/lt"; mkdir -p "$LT/seed" "$LT/k6" "$LT/data"
cp "$HERE/../run-k6.sh" "$LT/"; cp "$ADMIT" "$LT/seed/"; : > "$LT/k6/s0.js"; cp "$T/users.csv" "$LT/data/users.csv"
# 가짜 k6: version과 run만. run은 K6를 남기고 FAKE_K6_RC로 끝난다
cat > "$T/bin/k6" <<'FAKE_K6'
#!/usr/bin/env bash
if [ "$1" = version ]; then echo "k6 v0.0.0-fake"; exit 0; fi
echo K6 >> "$FAKE_LOG"
exit "${FAKE_K6_RC:-0}"
FAKE_K6
chmod +x "$T/bin/k6"
for k6rc in 0 99; do
  : > "$T/calls"
  ( FAKE_LOG="$T/calls" FAKE_K6_RC=$k6rc K6_MODE=local PATH="$T/bin:$PATH" bash "$LT/run-k6.sh" s0 --ipo "$I1" --run-id "t-$k6rc" -e "IPO_IDS=$I2" ) > /dev/null 2>&1
  RC=$?
  ORDER="$(tr '\n' ' ' < "$T/calls")"
  [ "$RC" = "$k6rc" ] && [ "$ORDER" = "SET K6 DEL " ] && ok "run-k6.sh: 넣기 → k6 → 지우기, k6 종료 코드 $k6rc 유지" || bad "run-k6.sh k6 종료 코드 $k6rc" "rc=$RC 순서=$ORDER"
done
: > "$T/calls"
( FAKE_LOG="$T/calls" FAKE_ERRORS=1 K6_MODE=local PATH="$T/bin:$PATH" bash "$LT/run-k6.sh" s0 --ipo "$I1" --run-id t-e ) > /dev/null 2>&1; RC=$?
[ "$RC" != 0 ] && ! grep -q K6 "$T/calls" && [ "$(tr '\n' ' ' < "$T/calls")" = "SET DEL " ] \
  && ok "run-k6.sh: 허가를 넣지 못하면 k6를 실행하지 않고 실패, 일부 들어갔을 허가도 지움" || bad "run-k6.sh 넣기 실패" "rc=$RC 순서=$(tr '\n' ' ' < "$T/calls")"

echo
echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" = 0 ]
