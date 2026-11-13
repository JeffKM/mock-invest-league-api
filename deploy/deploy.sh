#!/bin/bash
# 5주차② 배포. EC2 의 /opt/league 에서 실행한다: ./deploy.sh ghcr.io/jeffkm/mock-invest-league-api:<커밋 sha>
# 새 이미지를 띄우고 헬스 체크가 UP 이 될 때까지 기다린다. 제한 시간 안에 UP 이 안 되면 직전 이미지로 되돌린다.
set -euo pipefail
cd "$(dirname "$0")"

NEW_IMAGE="${1:?사용법: ./deploy.sh <이미지:태그>}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"   # 초. JVM 시작 + Flyway 마이그레이션 시간
STATE_FILE=.deployed-image                # 마지막으로 성공한 이미지(되돌릴 곳)

PREV_IMAGE="$(cat "$STATE_FILE" 2>/dev/null || true)"

up() {
  IMAGE="$1" docker compose up -d --remove-orphans
}

wait_healthy() {
  local waited=0 status
  while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
    status="$(docker inspect --format '{{.State.Health.Status}}' league-app 2>/dev/null || echo missing)"
    if [ "$status" = "healthy" ]; then
      return 0
    fi
    sleep 5
    waited=$((waited + 5))
  done
  echo "헬스 체크 실패: ${HEALTH_TIMEOUT}초 동안 healthy 가 아니었습니다(마지막 상태: $status)" >&2
  return 1
}

# 이미지를 먼저 받아 둔다. 받기에 실패하면 지금 도는 앱은 건드리지 않고 끝난다
if [ "${SKIP_PULL:-0}" != "1" ]; then
  IMAGE="$NEW_IMAGE" docker compose pull
fi

echo "배포: ${PREV_IMAGE:-(처음)} → $NEW_IMAGE"
up "$NEW_IMAGE"
if wait_healthy; then
  echo "$NEW_IMAGE" > "$STATE_FILE"
  echo "배포 성공"
  exit 0
fi

docker logs --tail 50 league-app >&2 || true
if [ -n "$PREV_IMAGE" ]; then
  echo "직전 이미지로 되돌립니다: $PREV_IMAGE" >&2
  up "$PREV_IMAGE"
  wait_healthy && echo "되돌리기 성공" >&2
else
  # 처음 배포라 되돌릴 곳이 없다. 죽었다 살아나기를 반복하지 않게 멈춰 둔다
  echo "되돌릴 이미지가 없어 앱을 멈춥니다" >&2
  IMAGE="$NEW_IMAGE" docker compose stop
fi
exit 1
