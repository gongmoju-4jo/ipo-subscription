#!/usr/bin/env bash
# 시연 전용. 시연용 공모주 2개를 한 번에 만들고, 이름으로 마감시킨다 (ID 복사 없이)
#   bash frontend/ops-server/demo-ipo.sh new     시연-배정(물량 4주), 시연-실패(물량 100,000주) 생성
#   bash frontend/ops-server/demo-ipo.sh close   가장 최근 시연-배정을 지금 마감 (약 25초 뒤 배정 시작)
#   bash frontend/ops-server/demo-ipo.sh close 종목명|공모주ID   해당 종목을 지금 마감 (관리자 화면에서 등록한 종목 등). 종목명이 같으면 가장 최근 1건
#   bash frontend/ops-server/demo-ipo.sh list    시연 공모주 상태 보기
set -euo pipefail
cd "$(dirname "$0")/../.."
PSQL=(docker exec -i ipo-postgres psql -U postgres -d ipo -v ON_ERROR_STOP=1)

case "${1:-}" in
  new)
    # 물량 4주: 혼자 청약해도 4주만 배정돼 환불이 보이게. 짝수여야 함 (균등 = 물량/2 제약)
    A=$(bash loadtest/seed/new_ipo.sh --name 시연-배정 --retail 4)
    B=$(bash loadtest/seed/new_ipo.sh --name 시연-실패 --retail 100000)
    echo "시연-배정 $A"
    echo "시연-실패 $B"
    ;;
  close)
    # 대상: 인자가 UUID면 공모주 ID, 아니면 종목명(같은 이름이면 가장 최근 1건). 인자가 없으면 시연-배정
    # 값은 psql 변수(:'target')로 전달해 SQL 문자열에 직접 붙이지 않는다. 청약 중(OPEN)인 종목만 마감한다
    TARGET=${2:-시연-배정}
    OUT=$("${PSQL[@]}" -qtA -v target="$TARGET" <<'SQL'
update subscription.ipo set subscription_end_at = now()
where ipo_id = (
  select ipo_id from subscription.ipo
  where status = 'OPEN'
    and (ipo_id::text = :'target' or name = :'target')
  order by created_at desc limit 1)
returning '마감 처리: ' || name || ' ' || ipo_id;
SQL
)
    if [ -z "$OUT" ]; then
      echo "마감할 청약 중(OPEN) 종목이 없어요: $TARGET (list로 확인)" >&2
      exit 1
    fi
    echo "$OUT"
    echo "약 25초 뒤 배정이 시작돼요 (유예 20초 + 확인 간격 5초)"
    ;;
  list)
    "${PSQL[@]}" -c "select name, ipo_id, status, retail_quantity, to_char(subscription_end_at at time zone 'Asia/Seoul', 'MM-DD HH24:MI:SS') as end_kst
      from subscription.ipo where name like '시연-%' order by created_at desc limit 6"
    ;;
  *) echo "사용: bash frontend/ops-server/demo-ipo.sh new | close [종목명|공모주ID] | list" >&2; exit 1 ;;
esac
