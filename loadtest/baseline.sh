#!/usr/bin/env bash
# 부하 테스트 기준 상태(회원, 계좌, 예수금만 있고 청약이 없는 DB)를 스냅샷으로 저장하고 되돌린다.
# 측정마다 같은 DB 크기, 같은 데이터에서 시작하게 해서, 측정이 쌓일수록 DB가 커져 결과가 달라지는 것을 막는다.
#
# 사용:
#   bash loadtest/baseline.sh save            지금 DB를 기준 스냅샷으로 저장 (청약, 증거금이 0건일 때만)
#   bash loadtest/baseline.sh restore         기준 스냅샷으로 DB를 되돌리고 Kafka를 비운다 (서비스를 멈췄다가 다시 띄운다)
#   bash loadtest/baseline.sh dump <파일>     지금 DB를 덤프한다 (측정 결과 백업용)
#   bash loadtest/baseline.sh check           지금 DB가 기준 스냅샷과 같은 상태인지 확인 (회원, 계좌, 예수금 합계, 청약 0)
#
# 기준 스냅샷: loadtest/data/baseline/ipo-baseline.dump 와 ipo-baseline.meta (BASELINE_DIR로 바꿀 수 있다. loadtest/data/는 커밋하지 않는다)
#   만드는 법: PostgreSQL, Kafka 볼륨을 비우고 띄운 뒤 seed/seed_users.py로 회원을 만들고(가입, 계좌, 입금 모두 API) save
# restore는 데이터베이스 ipo를 지우고 스냅샷으로 다시 만든다. 지금 DB의 데이터는 사라지므로 필요하면 먼저 dump로 백업한다.
# 환경변수: PG_CONTAINER(ipo-postgres), KAFKA_CONTAINER(ipo-kafka), BASELINE_DIR
# 종료 코드: 0 정상 / 1 사용법, 도구 / 2 save 조건 불만족(청약이 있음) / 3 스냅샷 없음, 손상 / 4 복원, 덤프 실패 / 5 복원 뒤 상태가 기준과 다름 / 6 Kafka 초기화 실패
set -uo pipefail
export MSYS_NO_PATHCONV=1

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$DIR/.." && pwd)"
PG_CONTAINER="${PG_CONTAINER:-ipo-postgres}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-ipo-kafka}"
BASELINE_DIR="${BASELINE_DIR:-$DIR/data/baseline}"
DUMP="$BASELINE_DIR/ipo-baseline.dump"; META="$BASELINE_DIR/ipo-baseline.meta"
COMPOSE_FILES=(-f docker-compose.yml -f compose.perf.yml)
APP_SERVICES="account-service subscription-service allocation-service"
COMPOSE=()

say() { printf '%s %s\n' "$(date '+%F %T')" "$*" >&2; }
die() { local c="$1"; shift; say "!! $*"; exit "$c"; }
pick_compose() {
  if docker compose version >/dev/null 2>&1; then COMPOSE=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then COMPOSE=(docker-compose)
  else return 1; fi
}
compose() { (cd "$REPO" && "${COMPOSE[@]}" "${COMPOSE_FILES[@]}" "$@"); }
psql_q() { docker exec -i "$PG_CONTAINER" psql -U postgres -d "${2:-ipo}" -v ON_ERROR_STOP=1 -qtA -c "$1"; }
sha256_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }

# 기준 상태를 나타내는 값 한 줄 (save 때 meta에 남기고, restore, check 때 같은지 본다)
state_line() {
  psql_q "select 'users=' || (select count(*) from account.users)
               || ' accounts=' || (select count(*) from account.account)
               || ' cash_sum=' || (select coalesce(sum(cash_balance), 0) from account.account)
               || ' margin_sum=' || (select coalesce(sum(margin_balance), 0) from account.account)
               || ' ledger=' || (select count(*) from account.ledger_entry)
               || ' ipos=' || (select count(*) from subscription.ipo)
               || ' subscriptions=' || (select count(*) from subscription.subscription)
               || ' margins=' || (select count(*) from account.margin)" | tr -d '\r'
}

# compose의 kafka-data 볼륨 이름. 컨테이너에는 이미지가 만든 익명 볼륨도 붙어 있어서 마운트가 아니라 compose 라벨로 찾는다.
# 프로젝트 이름은 PostgreSQL 컨테이너의 compose 라벨에서 읽는다 (폴더 이름에 따라 바뀐다). 정확히 하나가 아니면 실패
kafka_volume() {
  local proj v n
  proj="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$PG_CONTAINER" 2>/dev/null)"
  [ -n "$proj" ] || return 1
  v="$(docker volume ls -q --filter "label=com.docker.compose.project=$proj" --filter "label=com.docker.compose.volume=kafka-data" | tr -d '\r')"
  n="$(printf '%s\n' "$v" | grep -c .)"
  [ "$n" -eq 1 ] || { printf '%s' "$v" | tr '\n' ' '; return 1; }
  printf '%s' "$v"
}

wait_kafka() { # Kafka healthy, 토픽 생성(kafka-init) 종료 코드 0까지
  local st ex
  for _ in $(seq 1 60); do
    st="$(docker inspect -f '{{.State.Health.Status}}' "$KAFKA_CONTAINER" 2>/dev/null)"
    ex="$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' ipo-kafka-init 2>/dev/null)"
    [ "$st" = healthy ] && [ "$ex" = "exited 0" ] && return 0
    case "$ex" in "exited "[1-9]*) say "kafka-init 실패: $ex"; return 1 ;; esac
    sleep 5
  done
  return 1
}

cmd_save() {
  local st n
  st="$(state_line)" || die 4 "DB 조회 실패"
  n="$(printf '%s' "$st" | sed -n 's/.*subscriptions=\([0-9]*\).*/\1/p')"
  case "$st" in *" subscriptions=0 margins=0"*) ;; *) die 2 "청약 또는 증거금이 있는 DB는 기준으로 저장하지 않는다 ($st)" ;; esac
  [ -n "$n" ] || die 4 "상태 값 읽기 실패 ($st)"
  mkdir -p "$BASELINE_DIR"
  docker exec "$PG_CONTAINER" pg_dump -U postgres -d ipo -Fc --create > "$DUMP.tmp" || { rm -f "$DUMP.tmp"; die 4 "pg_dump 실패"; }
  docker exec -i "$PG_CONTAINER" pg_restore -l < "$DUMP.tmp" > /dev/null || { rm -f "$DUMP.tmp"; die 4 "만든 덤프를 읽을 수 없음"; }
  mv "$DUMP.tmp" "$DUMP"
  {
    echo "state $st"
    echo "sha256 $(sha256_of "$DUMP")"
    echo "created_at $(date '+%F %T')"
    echo "git $(cd "$REPO" && git rev-parse HEAD 2>/dev/null)"
  } > "$META"
  say "기준 스냅샷 저장: $DUMP ($st)"
}

check_snapshot() { # 스냅샷 파일과 meta가 있고 sha256이 같아야 한다
  [ -f "$DUMP" ] && [ -f "$META" ] || die 3 "기준 스냅샷이 없음 ($DUMP). baseline.sh save로 만든다"
  local want got
  want="$(awk '$1 == "sha256" {print $2}' "$META")"
  got="$(sha256_of "$DUMP")"
  [ -n "$want" ] && [ "$want" = "$got" ] || die 3 "기준 스냅샷이 meta와 다름 (sha256 $got, meta $want)"
}

cmd_check() {
  check_snapshot
  local want got
  want="$(awk '$1 == "state" {sub(/^state /, ""); print}' "$META")"
  got="$(state_line)" || die 4 "DB 조회 실패"
  [ "$want" = "$got" ] || die 5 "DB가 기준 상태와 다름 (지금: $got / 기준: $want)"
  say "기준 상태와 같음 ($got)"
}

cmd_restore() {
  check_snapshot
  pick_compose || die 1 "docker compose를 찾지 못함"
  local vol
  say "서비스 중지: $APP_SERVICES"
  # shellcheck disable=SC2086  # 서비스 목록을 나눠서 넘긴다
  compose stop $APP_SERVICES >&2 || die 4 "서비스 중지 실패"
  say "DB 되돌리기 (ipo를 지우고 기준 스냅샷으로 다시 만든다)"
  psql_q "DROP DATABASE IF EXISTS ipo WITH (FORCE)" postgres > /dev/null || die 4 "ipo 삭제 실패"
  docker exec -i "$PG_CONTAINER" pg_restore -U postgres -d postgres --create --exit-on-error < "$DUMP" || die 4 "pg_restore 실패"
  psql_q "ANALYZE" > /dev/null || die 4 "ANALYZE 실패"
  psql_q "CHECKPOINT" > /dev/null || die 4 "CHECKPOINT 실패"
  say "Kafka 비우기 (볼륨을 지우고 토픽을 다시 만든다)"
  vol="$(kafka_volume)" || die 6 "Kafka 데이터 볼륨(kafka-data)을 정확히 하나 찾지 못함: ${vol:-없음}"
  compose rm -sf kafka kafka-init >&2 || die 6 "Kafka 컨테이너 제거 실패"
  if docker volume inspect "$vol" > /dev/null 2>&1; then
    docker volume rm "$vol" > /dev/null || die 6 "Kafka 볼륨 삭제 실패 ($vol)"
  fi
  compose up -d kafka kafka-init >&2 || die 6 "Kafka 기동 실패"
  wait_kafka || die 6 "Kafka healthy 또는 토픽 생성 확인 실패"
  say "서비스 기동"
  # shellcheck disable=SC2086
  compose up -d $APP_SERVICES >&2 || die 4 "서비스 기동 실패"
  # 서비스가 뜨면서 data.sql로 개발용 공모주를 다시 넣을 수 있어서, 상태 비교는 서비스 기동 전 값으로 한다
  local want got
  want="$(awk '$1 == "state" {sub(/^state /, ""); print}' "$META")"
  got="$(state_line)" || die 4 "DB 조회 실패"
  # 공모주 수는 data.sql 때문에 기동 시점에 따라 같을 수도 다를 수도 있어 비교에서 뺀다 (청약 0은 확인한다)
  [ "$(printf '%s' "$want" | sed 's/ ipos=[0-9]*//')" = "$(printf '%s' "$got" | sed 's/ ipos=[0-9]*//')" ] \
    || die 5 "복원 뒤 상태가 기준과 다름 (지금: $got / 기준: $want)"
  say "복원 완료 ($got)"
}

cmd_dump() {
  local out="${1:-}"
  [ -n "$out" ] || die 1 "사용: baseline.sh dump <파일>"
  mkdir -p "$(dirname "$out")"
  docker exec "$PG_CONTAINER" pg_dump -U postgres -d ipo -Fc --create > "$out.tmp" || { rm -f "$out.tmp"; die 4 "pg_dump 실패"; }
  docker exec -i "$PG_CONTAINER" pg_restore -l < "$out.tmp" > /dev/null || { rm -f "$out.tmp"; die 4 "만든 덤프를 읽을 수 없음"; }
  mv "$out.tmp" "$out"
  say "덤프: $out ($(wc -c < "$out" | tr -d ' ')바이트)"
}

main() {
  case "${1:-}" in
    save) cmd_save ;;
    restore) cmd_restore ;;
    check) cmd_check ;;
    dump) shift; cmd_dump "${1:-}" ;;
    *) echo "사용: $0 save | restore | check | dump <파일>" >&2; exit 1 ;;
  esac
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
