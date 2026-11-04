#!/usr/bin/env bash
# 배포 서버(EC2)에서 실행하는 배포 스크립트. 배포 워크플로가 SSM Run Command로 root 권한으로 실행한다
#   bash /opt/ipo/deploy/deploy.sh {커밋 SHA}
# 수동 롤백도 같은 명령에 이전 SHA를 넣는다 (직전 배포 SHA는 .last-tag, 그 전 SHA는 .prev-tag에 남는다)
#
# 하는 일
#   1. 레포를 배포할 커밋으로 맞춘다 (compose 파일, docker/ 아래 설정 파일이 이미지와 같은 커밋이 되도록)
#   2. Parameter Store(PARAM_PATH)의 비밀값으로 .env를 만든다 (권한 600)
#   3. ECR에 로그인해 이미지를 받고 docker compose up -d
#   4. 서비스 5개가 healthy가 될 때까지 기다린다. 실패하면 상태와 로그를 출력하고 종료 코드 1
#
# down -v는 쓰지 않는다 (DB, Kafka 데이터가 지워진다)
# 레포 checkout으로 이 파일 자신이 바뀔 수 있어서, bash가 파일 전체를 읽은 뒤 실행되도록 main 함수로 감쌌다

main() {
  set -euo pipefail

  local tag="${1:-}"
  if [[ ! "$tag" =~ ^[0-9a-f]{7,40}$ ]]; then
    echo "사용법: bash deploy/deploy.sh {커밋 SHA}" >&2
    exit 2
  fi

  local app_dir="${APP_DIR:-/opt/ipo}"
  local region="${AWS_REGION:-ap-northeast-2}"
  local param_path="${PARAM_PATH:-/ipo/prod/}"
  local health_timeout="${HEALTH_TIMEOUT:-300}"
  local services=(eureka-server api-gateway account-service subscription-service allocation-service)
  local required=(DB_PASSWORD ACCOUNT_DB_PASSWORD SUBSCRIPTION_DB_PASSWORD ALLOCATION_DB_PASSWORD JWT_SECRET GRAFANA_ADMIN_PASSWORD)

  cd "$app_dir"

  echo "== 1. 레포를 ${tag}로 맞춤"
  git fetch --quiet origin
  git checkout --quiet --detach "$tag"
  local full_sha
  full_sha="$(git rev-parse HEAD)"

  echo "== 2. Parameter Store(${param_path})로 .env 생성"
  local registry
  registry="${ECR_REGISTRY:-$(aws sts get-caller-identity --query Account --output text).dkr.ecr.${region}.amazonaws.com}"

  local tmp
  tmp="$(mktemp "${app_dir}/.env.XXXXXX")"
  chmod 600 "$tmp"
  # 값은 화면에 출력하지 않는다
  # Compose는 따옴표 없는 .env 값의 $를 변수로 바꾼다. 작은따옴표로 감싸 값을 그대로 넘기고, 작은따옴표가 든 값은 받지 않는다
  local params name value invalid=()
  params="$(aws ssm get-parameters-by-path --region "$region" --path "$param_path" --with-decryption \
    --query 'Parameters[].[Name,Value]' --output text)"
  while IFS=$'\t' read -r name value; do
    [[ -z "$name" ]] && continue
    if [[ "$value" == *"'"* ]]; then
      invalid+=("${name##*/}")
      continue
    fi
    printf "%s='%s'\n" "${name##*/}" "$value"
  done <<< "$params" > "$tmp"
  if (( ${#invalid[@]} > 0 )); then
    rm -f "$tmp"
    echo "작은따옴표(')가 든 값은 쓸 수 없습니다: ${invalid[*]} (경로 ${param_path})" >&2
    exit 1
  fi

  local key missing=()
  for key in "${required[@]}"; do
    grep -q "^${key}='[^']" "$tmp" || missing+=("$key")
  done
  if (( ${#missing[@]} > 0 )); then
    rm -f "$tmp"
    echo "Parameter Store에 값이 없습니다: ${missing[*]} (경로 ${param_path})" >&2
    exit 1
  fi

  {
    echo "ALERT_ENV='prod'"
    echo "GF_SERVER_ROOT_URL='http://localhost:3000'"
    # docker compose ps, logs를 손으로 칠 때도 같은 이미지를 가리키도록 남긴다
    echo "ECR_REGISTRY='${registry}'"
    echo "IMAGE_TAG='${tag}'"
  } >> "$tmp"
  mv -f "$tmp" "${app_dir}/.env"

  echo "== 3. ECR 로그인, 이미지 받기, 실행 (태그 ${tag})"
  aws ecr get-login-password --region "$region" \
    | docker login --username AWS --password-stdin "$registry" > /dev/null
  local compose=(docker compose -f docker-compose.yml -f compose.perf.yml -f compose.prod.yml)
  "${compose[@]}" pull --quiet
  "${compose[@]}" up -d

  echo "== 4. 서비스 healthy 대기 (최대 ${health_timeout}초)"
  local deadline=$((SECONDS + health_timeout)) svc status pending
  while :; do
    pending=()
    for svc in "${services[@]}"; do
      status="$(docker inspect -f '{{.State.Health.Status}}' "ipo-${svc}" 2>/dev/null || echo missing)"
      [[ "$status" == "healthy" ]] || pending+=("${svc}=${status}")
    done
    (( ${#pending[@]} == 0 )) && break
    if (( SECONDS >= deadline )); then
      echo "시간 안에 healthy가 되지 않았습니다: ${pending[*]}" >&2
      "${compose[@]}" ps >&2
      for svc in "${services[@]}"; do
        echo "--- ${svc} 마지막 로그" >&2
        "${compose[@]}" logs --tail 30 "$svc" >&2 || true
      done
      echo "롤백: bash ${app_dir}/deploy/deploy.sh \$(cat ${app_dir}/.last-tag)" >&2
      exit 1
    fi
    sleep 5
  done

  if [[ -f "${app_dir}/.last-tag" ]]; then
    cp -f "${app_dir}/.last-tag" "${app_dir}/.prev-tag"
  fi
  echo "$tag" > "${app_dir}/.last-tag"

  # 쓰지 않는 이전 이미지를 지워 디스크를 비운다 (롤백할 때는 ECR에서 다시 받는다)
  docker image prune -af > /dev/null

  echo "== 배포 완료: ${full_sha}"
  "${compose[@]}" ps --format 'table {{.Name}}\t{{.Status}}'
}

main "$@"
