#!/usr/bin/env bash
# g2_run.sh 단위 테스트 (Docker, DB 없이 돈다. DB 조회, 시계, sleep은 가짜 함수로 바꾼다)
# 사용: bash loadtest/tests/test_g2_run.sh
# shellcheck disable=SC2034,SC2329,SC2015
set -u
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/../g2_run.sh"
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); printf 'ok   %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf 'FAIL %s (%s)\n' "$1" "$2"; }
rc_of() { ( "$@" ) > /dev/null 2>&1; echo $?; }
T="$(mktemp -d)"; LOG=""
U1="11111111-1111-4111-8111-111111111111"; U2="22222222-2222-4222-8222-222222222222"; U3="33333333-3333-4333-8333-333333333333"

# ---- parse_args ----
for a in "" "--batch" "--batch a/b x" "--session 한글 x" "--bogus x"; do
  # shellcheck disable=SC2086
  [ "$(rc_of parse_args $a)" = 1 ] && ok "parse_args: '$a' → 1" || bad "parse_args $a" "$(rc_of parse_args $a)"
done
[ "$( (GAP=x; parse_args s) > /dev/null 2>&1; echo $?)" = 1 ] && ok "parse_args: GAP이 정수가 아니면 1" || bad "parse_args GAP" "통과함"
( SNAPS=(); parse_args --batch b1 --session S-1 g2-A-1k g2-B-1k g2-B-1k; [ "${SNAPS[*]}" = "g2-A-1k g2-B-1k g2-B-1k" ] && [ "$BATCH" = b1 ] && [ "$SESSION" = S-1 ] ) \
  && ok "parse_args: 스냅샷을 준 순서 그대로, 같은 폴더 반복 가능" || bad "parse_args 순서" "다름"
[ "$(BASELINE_ROOT=/r snap_path g2-A-1k)" = /r/g2-A-1k ] && [ "$(snap_path ./x/y)" = ./x/y ] && ok "snap_path: 이름이면 BASELINE_ROOT 아래, /가 있으면 경로 그대로" || bad "snap_path" "$(snap_path g2-A-1k)"

# ---- read_ids ----
mk_ids() { mkdir -p "$T/$1"; printf "$2" > "$T/$1/ipo-ids.txt"; }
mk_ids ok1 "CASE B\nW1 $U1\nW2 $U2\nM $U3\n"
( read_ids "$T/ok1" > /dev/null && [ "$CASE_" = B ] && [ "$W_IDS" = "$U1 $U2" ] && [ "$M_ID" = "$U3" ] ) && ok "read_ids: CASE, W 여러 개(순서대로), M" || bad "read_ids 정상" "$(read_ids "$T/ok1")"
mk_ids crlf "CASE A\r\nM $U3\r\n"
( read_ids "$T/crlf" > /dev/null && [ "$CASE_" = A ] && [ -z "$W_IDS" ] && [ "$M_ID" = "$U3" ] ) && ok "read_ids: CRLF 줄끝, W 없이 M만" || bad "read_ids CRLF" "$(read_ids "$T/crlf")"
mk_ids nocase "W1 $U1\nM $U3\n"; mk_ids badcase "CASE C\nM $U3\n"; mk_ids nom "CASE A\nW1 $U1\n"; mk_ids twom "CASE A\nM $U1\nM $U3\n"
mk_ids baduuid "CASE A\nM not-a-uuid\n"; mk_ids unknown "CASE A\nX $U1\nM $U3\n"
for d in nocase badcase nom twom baduuid unknown missing; do
  [ "$(rc_of read_ids "$T/$d")" = 1 ] && ok "read_ids: $d → 실패" || bad "read_ids $d" "통과함"
done

# ---- check_snaps ----
mkdir -p "$T/snapok" "$T/snapnoallow"
printf "CASE A\nW1 $U1\nM $U3\n" > "$T/snapok/ipo-ids.txt"; cp "$T/snapok/ipo-ids.txt" "$T/snapnoallow/"
for d in snapok snapnoallow; do : > "$T/$d/ipo-baseline.dump"; echo "state x" > "$T/$d/ipo-baseline.meta"; done
echo "allow_subscriptions unfinished=0 unpublished=0 open_due=0" >> "$T/snapok/ipo-baseline.meta"
( SNAPS=("$T/snapok" "$T/snapok"); check_snaps ) 2>/dev/null && ok "check_snaps: 덤프, allow 줄, ipo-ids.txt가 있으면 통과" || bad "check_snaps 정상" "실패"
( SNAPS=("$T/snapok" "$T/snapnoallow"); check_snaps ) 2>/dev/null && bad "check_snaps allow 없음" "통과함" || ok "check_snaps: allow_subscriptions 줄이 없는 스냅샷(청약 없는 기준 스냅샷)이 섞이면 실패"
( SNAPS=("$T/snapok" "$T/none"); check_snaps ) 2>/dev/null && bad "check_snaps 없음" "통과함" || ok "check_snaps: 없는 폴더가 섞이면 시작 전에 실패"

# ---- kv ----
L="trigger=2026-11-08T10:00:00.000 total_s=10.5 thr=95.2 settled=1000 refunded=1000 zero=0 seed=12"
[ "$(kv "$L" total_s)" = 10.5 ] && [ "$(kv "$L" settled)" = 1000 ] && [ -z "$(kv "$L" none)" ] && ok "kv: key=value 줄에서 값, 없으면 빈 값" || bad "kv" "$(kv "$L" total_s)"

# ---- wait_refunded: 가짜 시계와 진행 ----
echo 1000 > "$T/now"
date() { if [ "${1:-}" = +%s ]; then cat "$T/now"; else command date "$@"; fi; }
sleep() { echo $(( $(cat "$T/now") + ${1%.*} )) > "$T/now"; }
say() { :; }
seq_progress() { : > "$T/pcount"; printf '%s\n' "$@" > "$T/pseq"; }
progress() { echo x >> "$T/pcount"; local k; k="$(wc -l < "$T/pcount")"; sed -n "${k}p" "$T/pseq" | grep . || tail -1 "$T/pseq"; }
seq_progress "0 0 -" "0 0 RUNNING" "500 0 DONE" "1000 400 DONE" "1000 1000 DONE"
[ "$(rc_of wait_refunded "$U3" 1000 600 7200)" = 0 ] && ok "wait_refunded: REFUNDED = 기대 수면 0" || bad "wait 완료" "$(rc_of wait_refunded "$U3" 1000 600 7200)"
echo 1000 > "$T/now"; seq_progress "0 0 -"
[ "$(rc_of wait_refunded "$U3" 1000 10 7200)" = 1 ] && ok "wait_refunded: 진행이 정지 초 동안 없으면 1 (배정 대기 중 0건도 정지로 셈)" || bad "wait 정지" "$(rc_of wait_refunded "$U3" 1000 10 7200)"
# 계속 조금씩 늘지만 상한을 넘음: 매번 settled가 1씩 는다
echo 1000 > "$T/now"; seq_progress $(for i in $(seq 1 200); do echo "$i 0 DONE"; done | tr ' ' '_'); sed -i 's/_/ /g' "$T/pseq"
[ "$(rc_of wait_refunded "$U3" 1000 10 100)" = 2 ] && ok "wait_refunded: 계속 늘어도 상한 초를 넘으면 2" || bad "wait 상한" "$(rc_of wait_refunded "$U3" 1000 10 100)"
echo 1000 > "$T/now"; seq_progress "0 0 FAILED"
[ "$(rc_of wait_refunded "$U3" 1000 600 7200)" = 4 ] && ok "wait_refunded: 배정 라운드 FAILED면 4" || bad "wait FAILED" "$(rc_of wait_refunded "$U3" 1000 600 7200)"
echo 1000 > "$T/now"; progress() { return 1; }
[ "$(rc_of wait_refunded "$U3" 1000 600 7200)" = 3 ] && ok "wait_refunded: 조회가 연속 5번 실패하면 3 (0으로 넘기지 않음)" || bad "wait 조회 실패" "$(rc_of wait_refunded "$U3" 1000 600 7200)"
unset -f date sleep

# ---- verify_violations: verify.sql 출력 (정렬 표) ----
mkv() { # 인자: 파일, 13번 행 수, 8번 sub 값, 11~15 건너뜀 여부
  { echo "Pager usage is off."
    echo "== 1. 상태별 건수"; printf ' status | reject_reason | count\n--------+---------------+-------\n REFUNDED |  | 1000\n(1 row)\n\n'
    for s in 2 3 5 6; do echo "== $s. 설명"; printf ' account_id\n------------\n(0 rows)\n\n'; done
    echo "== 8. Outbox 미발행 없음 (기대: 모두 0)"; printf ' ?column? | count\n----------+-------\n acc      |     0\n sub      |     %s\n(2 rows)\n\n' "$3"
    if [ "$4" = skip ]; then echo "== 11~15. (건너뜀: 배정 라운드가 DONE이 아니다)"
    else
      for s in 11 12 14 15; do echo "== $s. [계좌 단독] 설명"; printf ' subscription_id\n-----------------\n(0 rows)\n\n'; done
      echo "== 13. [서비스 간] 배정 결과와 정산 일치"; printf ' subscription_id\n-----------------\n'
      for i in $(seq 1 "$2"); do echo " $U1"; done
      if [ "$2" = 1 ]; then echo "(1 row)"; else echo "($2 rows)"; fi
    fi
  } > "$1"
}
mkv "$T/v-ok.txt" 0 0 no; mkv "$T/v-13.txt" 2 0 no; mkv "$T/v-8.txt" 0 3 no; mkv "$T/v-skip.txt" 0 0 no; mkv "$T/v-skip.txt" 0 0 skip
r="$(verify_violations "2 3 5 6 11 12 13 14 15" < "$T/v-ok.txt")"; rc=$?
[ "$rc" = 0 ] && [ -z "$r" ] && ok "verify_violations: 모든 절 0행, 미발행 0이면 빈 출력" || bad "verify 정상" "rc=$rc '$r'"
r="$(verify_violations "2 3 5 6 11 12 13 14 15" < "$T/v-13.txt")"
[ "$r" = "13:2행" ] && ok "verify_violations: 13번 2행 → '13:2행'" || bad "verify 13" "'$r'"
r="$(verify_violations "2 3 5 6 11 12 13 14 15" < "$T/v-8.txt")"
[ "$r" = "8:sub=3" ] && ok "verify_violations: 8번 미발행 sub 3 → '8:sub=3'" || bad "verify 8" "'$r'"
r="$(verify_violations "2 3 5 6 11 12 13 14 15" < "$T/v-skip.txt")"; rc=$?
case "$r" in *"11~15:건너뜀"*) ok "verify_violations: 11~15를 건너뛰었으면 위반 (배정 미완료를 통과로 보지 않음)" ;; *) bad "verify skip" "rc=$rc '$r'" ;; esac
head -c 300 "$T/v-ok.txt" > "$T/v-cut.txt"
[ "$(verify_violations "2 3 5 6 11 12 13 14 15" < "$T/v-cut.txt" > /dev/null; echo $?)" = 1 ] && ok "verify_violations: 출력이 잘려 절이 빠지면 반환 1 (형식 이상)" || bad "verify 잘림" "0"
sed 's/^(0 rows)$//' "$T/v-ok.txt" > "$T/v-norows.txt"
[ "$(verify_violations "2 3 5 6 11 12 13 14 15" < "$T/v-norows.txt" > /dev/null; echo $?)" = 1 ] && ok "verify_violations: 행 수 줄이 없으면 반환 1" || bad "verify 행 수 없음" "0"
# W용: 2, 3이 건너뜀(skip_global)으로 나와도 요구하지 않는다
{ echo "== 2. 설명"; echo "(건너뜀: 전체 계좌 기준)"; sed -n '/^== 5\./,$p' "$T/v-ok.txt"; } > "$T/v-w.txt"
r="$(verify_violations "5 6 11 12 13 14 15" < "$T/v-w.txt")"; rc=$?
[ "$rc" = 0 ] && [ -z "$r" ] && ok "verify_violations: W(2, 3 건너뜀)는 5 ~ 15만 요구" || bad "verify W" "rc=$rc '$r'"

# 실제 출력 (1천 건 케이스 B 스모크의 verify.sql 결과, fixtures/g2-verify-*.txt)
FX="$HERE/fixtures"
r="$(verify_violations "2 3 5 6 11 12 13 14 15" < "$FX/g2-verify-M.txt")"; rc=$?
[ "$rc" = 0 ] && [ -z "$r" ] && ok "verify_violations: 실제 M 출력(통과 회차) → 위반 없음" || bad "verify 실제 M" "rc=$rc '$r'"
r="$(verify_violations "5 6 11 12 13 14 15" < "$FX/g2-verify-W.txt")"; rc=$?
[ "$rc" = 0 ] && [ -z "$r" ] && ok "verify_violations: 실제 W 출력(2, 3 건너뜀) → 위반 없음" || bad "verify 실제 W" "rc=$rc '$r'"
# 실제 출력의 13번에 위반 1행을 끼워 넣는다
awk -v u="$U1" '/^== 13\./ { s = 1 } s && /^\(0 rows\)$/ { print " " u; print "(1 row)"; s = 0; next } { print }' "$FX/g2-verify-M.txt" > "$T/real-13.txt"
r="$(verify_violations "2 3 5 6 11 12 13 14 15" < "$T/real-13.txt")"
[ "$r" = "13:1행" ] && ok "verify_violations: 실제 출력 형식에서 13번 1행 위반을 잡음" || bad "verify 실제 13" "'$r'"

# ---- judge ----
J="settled=1000 refunded=1000 zero=500 voided=500 l_void=500 posted=500 l_post=500"
out="$(judge 1000 "$J" 0 0 0 B "" "")"
[ "$out" = "VALID PASS" ] && ok "judge: 모두 맞으면 VALID PASS, 이유 없음" || bad "judge 정상" "$out"
out="$(judge 1000 "${J/settled=1000/settled=999}" 0 0 0 B "" "")"
[ "$(echo "$out" | head -1)" = "INVALID PASS" ] && echo "$out" | grep -q "정산 수 999 ≠ 확정 수 1000" && ok "judge: 정산 수 ≠ 확정 수 → INVALID" || bad "judge 정산" "$out"
out="$(judge 1000 "${J/refunded=1000/refunded=0}" 0 0 0 B "" "")"
[ "$(echo "$out" | head -1)" = "INVALID PASS" ] && ok "judge: REFUNDED 수 ≠ 확정 수 → INVALID" || bad "judge refunded" "$out"
for args in "1 0 0" "0 2 0" "0 0 1" "? 0 0"; do
  set -- $args
  out="$(judge 1000 "$J" "$1" "$2" "$3" B "" "")"
  [ "$(echo "$out" | head -1)" = "INVALID PASS" ] && ok "judge: DLT/미발행/다른 마감 = '$args' → INVALID (조회 실패 ?도 INVALID)" || bad "judge $args" "$out"
done
out="$(judge 1000 "$J" 0 0 0 B "13:2행" "")"
[ "$(echo "$out" | head -1)" = "VALID FAIL" ] && echo "$out" | grep -q "M verify.sql 위반: 13:2행" && ok "judge: M verify 위반 → 정합성 FAIL (유효성과 따로)" || bad "judge vm" "$out"
out="$(judge 1000 "$J" 0 0 0 B "" "W1 12:1행")"
[ "$(echo "$out" | head -1)" = "VALID FAIL" ] && ok "judge: W verify 위반도 정합성 FAIL" || bad "judge vw" "$out"
out="$(judge 1000 "${J/l_void=500/l_void=499}" 0 0 0 B "" "")"
[ "$(echo "$out" | head -1)" = "VALID FAIL" ] && ok "judge: VOIDED ≠ MARGIN_VOID 원장 → FAIL" || bad "judge void" "$out"
out="$(judge 1000 "${J/zero=500/zero=501}" 0 0 0 B "" "")"
[ "$(echo "$out" | head -1)" = "VALID FAIL" ] && ok "judge: 0주 배정 수 ≠ VOIDED → FAIL" || bad "judge zero" "$out"
out="$(judge 1000 "${J/l_post=500/l_post=0}" 0 0 0 B "" "")"
[ "$(echo "$out" | head -1)" = "VALID FAIL" ] && ok "judge: POSTED ≠ MARGIN_POST 원장 → FAIL" || bad "judge post" "$out"
out="$(judge 1000 "$J" 0 0 0 A "" "")"
[ "$(echo "$out" | head -1)" = "VALID PASS" ] && echo "$out" | grep -q "참고: 케이스 A인데 0주 500건" && ok "judge: 케이스 A의 0주는 참고로만 적고 판정은 그대로" || bad "judge A zero" "$out"
out="$(judge 1000 "" 0 0 0 B "" "")"
[ "$(echo "$out" | head -1)" = "INVALID PASS" ] && ok "judge: 측정값이 비면(조회 실패) INVALID" || bad "judge 빈 값" "$out"

# ---- marks_diff ----
A="ipo-kafka=0:false:t1 ipo-account-service=0:false:t2"
[ -z "$(marks_diff "$A" "$A")" ] && ok "marks_diff: 같으면 빈 출력" || bad "marks 같음" "$(marks_diff "$A" "$A")"
r="$(marks_diff "$A" "ipo-kafka=0:false:t1 ipo-account-service=1:true:t9")"
case "$r" in *"ipo-account-service(0:false:t2 -> 1:true:t9)"*) ok "marks_diff: 재시작, OOM, 시작 시각이 바뀐 컨테이너를 보여 줌" ;; *) bad "marks 변화" "$r" ;; esac
r="$(marks_diff "$A" "ipo-kafka=0:false:t1")"
case "$r" in *"ipo-account-service(사라짐)"*) ok "marks_diff: 컨테이너가 사라지면 보여 줌" ;; *) bad "marks 사라짐" "$r" ;; esac

# ---- manifest_row ----
MANIFEST="$T/m.csv"; SESSION=S-1; : > "$MANIFEST"
manifest_row 3 g2-B-1k B 1000 "2026-11-08 20:00:00" "2026-11-08 20:05:00" VALID FAIL 10.5 95.2 "M verify.sql 위반: 13:2행, 12:1행"$'\n'"VOIDED 1 ≠ 2"
[ "$(awk -F, '{print NF}' "$MANIFEST")" = 12 ] && [ "$(wc -l < "$MANIFEST" | tr -d ' ')" = 1 ] && grep -q "13:2행; 12:1행 | VOIDED" "$MANIFEST" \
  && ok "manifest_row: 이유 안의 쉼표, 줄바꿈을 바꿔 한 줄 12열 유지" || bad "manifest_row" "$(cat "$MANIFEST")"
: > "$MANIFEST"; manifest_row 1 s A 0 a b INVALID - - - "점검 실패"
[ "$(cut -d, -f8-11 "$MANIFEST")" = "INVALID,-,-,-" ] && ok "manifest_row: 빈 측정값은 -" || bad "manifest_row 빈 값" "$(cat "$MANIFEST")"

rm -rf "$T"
echo; echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" -eq 0 ]
