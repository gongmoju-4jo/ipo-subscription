#!/usr/bin/env bash
# 해피 시나리오: 가입 → 로그인 → 계좌 개설 → 입금 → 청약 202 → CONFIRMED, 잔액 부족 → REJECTED
set -euo pipefail
GW=${GW:-http://localhost:8080}
IPO=8b2d0000-0000-4000-8000-000000000001   # 테스트 공모주 A, 공모가 15,000, 최소 10주
LOGIN=happy$(date +%s | tail -c 7)
uuid() { uuidgen | tr A-Z a-z; }
j() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval(sys.argv[1]))" "$1"; }
step() { echo; echo "### $*"; }

step "1. 회원가입"
curl -s -X POST $GW/api/v1/users -H 'Content-Type: application/json' \
  -d "{\"loginId\":\"$LOGIN\",\"password\":\"password123\",\"name\":\"해피\"}"; echo

step "2. 로그인 (토큰은 출력하지 않음)"
TOKEN=$(curl -s -X POST $GW/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"loginId\":\"$LOGIN\",\"password\":\"password123\"}" | j 'd["data"]["accessToken"]')
echo "accessToken 길이=${#TOKEN}"
AUTH="Authorization: Bearer $TOKEN"

step "3. 계좌 개설"
ACC_RES=$(curl -s -X POST $GW/api/v1/accounts -H "$AUTH" -H 'Content-Type: application/json' -d "{\"requestId\":\"$(uuid)\"}")
echo "$ACC_RES"
ACC=$(echo "$ACC_RES" | j 'd["data"]["accountId"]')

step "4. 입금 200,000원"
curl -s -X POST $GW/api/v1/accounts/$ACC/deposits -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"$(uuid)\",\"amount\":200000}"; echo

step "5. 청약 신청 10주 (150,000원) → 202"
SUB_RES=$(curl -s -w '\nHTTP %{http_code}' -X POST $GW/api/v1/subscriptions -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"$(uuid)\",\"ipoId\":\"$IPO\",\"accountId\":\"$ACC\",\"requestedQuantity\":10}")
echo "$SUB_RES"
SUB=$(echo "$SUB_RES" | head -1 | j 'd["data"]["subscriptionId"]')

step "6. 청약 상태 (CONFIRMED가 될 때까지 최대 30초)"
for i in $(seq 1 30); do
  R=$(curl -s $GW/api/v1/subscriptions/$SUB -H "$AUTH")
  S=$(echo "$R" | j 'd.get("data",{}).get("status","-")' 2>/dev/null || echo -)
  [ "$S" = CONFIRMED ] || [ "$S" = REJECTED ] && break; sleep 1
done
echo "$R"; echo "→ ${i}초 후 status=$S"

step "7. 계좌 잔고 (예수금 50,000 / 청약증거금 150,000 예상)"
curl -s $GW/api/v1/accounts/$ACC -H "$AUTH"; echo

step "8. 잔액 부족 청약 10주 (예수금 50,000 < 150,000) → REJECTED 예상"
IPO2=8b2d0000-0000-4000-8000-000000000002   # 공모주 B (같은 종목 이중청약을 피하려고 다른 종목)
SUB2_RES=$(curl -s -w '\nHTTP %{http_code}' -X POST $GW/api/v1/subscriptions -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"$(uuid)\",\"ipoId\":\"$IPO2\",\"accountId\":\"$ACC\",\"requestedQuantity\":${Q2:-50000}}")
echo "$SUB2_RES"
SUB2=$(echo "$SUB2_RES" | head -1 | j 'd["data"]["subscriptionId"]')
for i in $(seq 1 30); do
  R=$(curl -s $GW/api/v1/subscriptions/$SUB2 -H "$AUTH")
  S=$(echo "$R" | j 'd.get("data",{}).get("status","-")' 2>/dev/null || echo -)
  [ "$S" = CONFIRMED ] || [ "$S" = REJECTED ] && break; sleep 1
done
echo "$R"; echo "→ ${i}초 후 status=$S"

echo; echo "ACC=$ACC SUB=$SUB SUB2=$SUB2"
