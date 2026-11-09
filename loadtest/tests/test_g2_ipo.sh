#!/usr/bin/env bash
# seed/g2_ipo.sh, seed/new_ipo.sh --far 단위 테스트 (Docker, DB 없이 돈다. docker, new_ipo는 가짜 함수로 바꾼다)
# 사용: bash loadtest/tests/test_g2_ipo.sh
# shellcheck disable=SC2034,SC2329,SC2015
set -u
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/../seed/g2_ipo.sh"
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); printf 'ok   %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf 'FAIL %s (%s)\n' "$1" "$2"; }
rc_of() { ( "$@" ) > /dev/null 2>&1; echo $?; }
T="$(mktemp -d)"

# ---- retail_for: round(n x 평균 / 69), 짝수 ----
[ "$(retail_for A 1000)" = 2494 ] && ok "retail_for: A 1,000건 → 2,494 (2,492.75 반올림 2,493, 홀수라 +1)" || bad "retail A 1000" "$(retail_for A 1000)"
[ "$(retail_for B 1000)" = 768 ] && ok "retail_for: B 1,000건 → 768 (768.1)" || bad "retail B 1000" "$(retail_for B 1000)"
[ "$(retail_for A 10000)" = 24928 ] && ok "retail_for: A 1만 → 24,928 (24,927.5 반올림 24,928)" || bad "retail A 10000" "$(retail_for A 10000)"
[ "$(retail_for A 100000)" = 249276 ] && ok "retail_for: A 10만 → 249,276 (249,275.4 → 249,275, 홀수라 +1)" || bad "retail A 100000" "$(retail_for A 100000)"
[ "$(retail_for B 100000)" = 76812 ] && ok "retail_for: B 10만 → 76,812 (76,811.6 → 76,812)" || bad "retail B 100000" "$(retail_for B 100000)"
all_even=1; for n in 1 2 3 50 69 100 999 1000 1001 9999 12345 100000; do for c in A B; do
  r="$(retail_for $c $n)"; [ $((r % 2)) = 0 ] && [ "$r" -ge 2 ] || all_even=0
  [ $((r / 2 * 2)) -ge "$r" ] || all_even=0   # DB 제약: 균등(물량 / 2) x 2 >= 물량
done; done
[ "$all_even" = 1 ] && ok "retail_for: 여러 건수에서 항상 짝수, 2 이상 (균등 x 2 >= 물량 제약 통과)" || bad "retail 짝수" "홀수 또는 2 미만"

# ---- parse_args ----
for a in "" "--case A" "--count 1000" "--case C --count 1000" "--case a --count 1000" "--case A --count 0" "--case A --count 010" "--case A --count x" \
         "--case B --count 1010" "--case A --count 1000 --warmups 10" "--case A --count 1000 --warmups -1" "--case A --count 1000 --warmup-count 0" \
         "--case A --count 1000 --bogus 1" "--case A --count"; do
  # shellcheck disable=SC2086
  [ "$(rc_of parse_args $a)" = 1 ] && ok "parse_args: '$a' → 1" || bad "parse_args $a" "$(rc_of parse_args $a)"
done
( parse_args --case B --count 1050 --warmups 2 --warmup-count 1000 --name t ) && ok "parse_args: B 50의 배수, W 2개 통과" || bad "parse_args 정상" "rc=$?"
( parse_args --case A --count 1001 ) && ok "parse_args: A는 50의 배수가 아니어도 됨" || bad "parse_args A 1001" "rc=$?"
( parse_args --case A --count 1000 --warmups 0 ) && ok "parse_args: --warmups 0 (W 없이 M만)" || bad "parse_args W 0" "rc=$?"
( parse_args --case A --count 1000; [ -n "$NAME" ] ) && ok "parse_args: 이름을 안 주면 g2-<케이스><건수>-<시각>" || bad "parse_args 이름" "빈 이름"

# ---- main: new_ipo, push_open을 가짜로 ----
U1="11111111-1111-4111-8111-111111111111"; U2="22222222-2222-4222-8222-222222222222"; U3="33333333-3333-4333-8333-333333333333"
reset_fakes() {
  : > "$T/calls"
  # $(...) 안에서 불리므로 호출 순서는 파일 줄 수로 센다
  new_ipo() { echo "$1 $2" >> "$T/calls"; case $(wc -l < "$T/calls") in 1) echo "$U1" ;; 2) echo "$U2" ;; *) echo "$U3" ;; esac; }
  push_open() { echo 5; }
}
reset_fakes
out="$( ( main --case B --count 1000 --warmups 2 --name t --out "$T/ids.txt" ) 2>/dev/null )"; rc=$?
exp="CASE B"$'\n'"W1 $U1"$'\n'"W2 $U2"$'\n'"M $U3"
[ "$rc" = 0 ] && [ "$out" = "$exp" ] && [ "$(cat "$T/ids.txt")" = "$exp" ] && ok "main: 'CASE B', 'W1 id', 'W2 id', 'M id' 출력, --out에도 같은 내용" || bad "main 출력" "rc=$rc out=$out"
reset_fakes; ( main --case B --count 1000 --warmups 2 --name t ) > /dev/null 2>&1
[ "$(cat "$T/calls")" = "t-W1 2494"$'\n'"t-W2 2494"$'\n'"t-M 768" ] && ok "main: W는 케이스 A 1,000건 물량(2,494), M은 케이스 B 물량(768)" || bad "main 물량" "$(cat "$T/calls")"
reset_fakes; out="$( ( main --case A --count 1000 --warmups 0 --name t ) 2>/dev/null )"
[ "$out" = "CASE A"$'\n'"M $U1" ] && [ "$(cat "$T/calls")" = "t-M 2494" ] && ok "main: --warmups 0이면 M만" || bad "main W 0" "$out / $(cat "$T/calls")"
reset_fakes; new_ipo() { echo "ERROR: insert failed"; return 0; }
[ "$(rc_of main --case A --count 1000 --name t)" = 4 ] && ok "main: new_ipo가 UUID가 아닌 값을 내면 종료 코드 4" || bad "main uuid 아님" "$(rc_of main --case A --count 1000 --name t)"
reset_fakes; new_ipo() { return 1; }
[ "$(rc_of main --case A --count 1000 --name t)" = 4 ] && ok "main: new_ipo 실패 → 종료 코드 4" || bad "main new_ipo 실패" "$(rc_of main --case A --count 1000 --name t)"
reset_fakes; push_open() { return 1; }
[ "$(rc_of main --case A --count 1000 --name t --out "$T/x.txt")" = 4 ] && [ ! -e "$T/x.txt" ] && ok "main: 다른 OPEN 공모주 옮기기 실패 → 종료 코드 4, --out 안 씀" || bad "main push_open 실패" "rc=$(rc_of main --case A --count 1000 --name t)"

# ---- push_open: SQL 모양 (docker를 가짜로. 위에서 바꾼 함수를 원래대로 되돌리려고 다시 읽는다) ----
# shellcheck disable=SC1091
source "$HERE/../seed/g2_ipo.sh"
docker() { cat > "$T/push.sql"; echo 3; }
[ "$(push_open)" = 3 ] && grep -q "status = 'OPEN' AND subscription_end_at < now() + interval '5 years'" "$T/push.sql" \
  && grep -q "refund_date = ((now() + interval '10 years') AT TIME ZONE 'Asia/Seoul')::date + 3" "$T/push.sql" && grep -q "version = version + 1" "$T/push.sql" \
  && ok "push_open: OPEN이고 5년 안에 끝나는 것만, 종료 시각과 환불일(종료일 + 3)을 같이 옮기고 version 증가" || bad "push_open SQL" "$(cat "$T/push.sql")"
unset -f docker

# ---- new_ipo.sh --far: SQL 모양 (docker를 PATH의 가짜 실행 파일로) ----
mkdir -p "$T/bin"
cat > "$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
cat > "$FAKE_SQL"; echo "44444444-4444-4444-8444-444444444444"
EOF
chmod +x "$T/bin/docker"
NI="$HERE/../seed/new_ipo.sh"
FAKE_SQL="$T/far.sql" PATH="$T/bin:$PATH" bash "$NI" --far --retail 2494 --name t > /dev/null 2>&1; rc=$?
[ "$rc" = 0 ] && grep -q "now() + interval '10 years', ((now() + interval '10 years') AT TIME ZONE 'Asia/Seoul')::date + 3, 'OPEN'" "$T/far.sql" \
  && ok "new_ipo.sh --far: 종료 시각 10년 뒤, 환불일은 그 종료일 + 3 (환불일 > 종료일 제약)" || bad "new_ipo --far" "rc=$rc $(grep -o "now() +[^,]*,[^,]*," "$T/far.sql")"
FAKE_SQL="$T/def.sql" PATH="$T/bin:$PATH" bash "$NI" --retail 100 --name t > /dev/null 2>&1
grep -q "now() + interval '12 hours', (now() AT TIME ZONE 'Asia/Seoul')::date + 3, 'OPEN'" "$T/def.sql" \
  && ok "new_ipo.sh: 옵션 없으면 지금처럼 12시간 뒤 종료, 환불일 오늘 + 3" || bad "new_ipo 기본" "$(grep -o "now() +[^,]*,[^,]*," "$T/def.sql")"
FAKE_SQL="$T/x.sql" PATH="$T/bin:$PATH" bash "$NI" --far --closed > /dev/null 2>&1; rc=$?
[ "$rc" = 1 ] && [ ! -e "$T/x.sql" ] && ok "new_ipo.sh: --far와 --closed를 같이 주면 종료 코드 1, DB에 보내지 않음" || bad "new_ipo far+closed" "rc=$rc"

rm -rf "$T"
echo; echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" -eq 0 ]
