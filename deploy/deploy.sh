#!/bin/bash
# 5주차② 배포. EC2 의 /opt/league 에서 실행한다: ./deploy.sh ghcr.io/jeffkm/mock-invest-league-api:<커밋 sha>
# 새 이미지를 띄우고 헬스 체크가 UP 이 될 때까지 기다린다. 제한 시간 안에 UP 이 안 되면 직전 이미지로 되돌린다.
# 7주차⑤: 푸시 서버 이미지는 같은 커밋 태그의 <저장소>-push 다(CI 가 함께 올린다). 둘 다 healthy 여야 성공이다.
set -euo pipefail
cd "$(dirname "$0")"

NEW_IMAGE="${1:?사용법: ./deploy.sh <이미지:태그>}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"   # 초. JVM 시작 + Flyway 마이그레이션 시간
STATE_FILE=.deployed-image                # 마지막으로 성공한 이미지(되돌릴 곳)
PUSH_STATE_FILE=.deployed-push-image      # 푸시 서버는 따로 적는다: 푸시가 생기기 전 커밋으로 되돌릴 때는 그 커밋의 푸시 이미지가 없다

push_image_of() { echo "${1%:*}-push:${1##*:}"; }   # ghcr.io/x/y:sha → ghcr.io/x/y-push:sha
NEW_PUSH="$(push_image_of "$NEW_IMAGE")"
PREV_IMAGE="$(cat "$STATE_FILE" 2>/dev/null || true)"
PREV_PUSH="$(cat "$PUSH_STATE_FILE" 2>/dev/null || echo "$NEW_PUSH")"

up() {
  # --force-recreate: 같은 이미지라도 다시 띄운다. .env 에서 줄을 지웠을 때 compose 가 바뀐 줄 모르고 그대로 둔 일이 있었다(26단계)
  IMAGE="$1" PUSH_IMAGE="$2" docker compose up -d --remove-orphans --force-recreate
}

wait_healthy() {
  local waited=0 status push
  while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
    status="$(docker inspect --format '{{.State.Health.Status}}' league-app 2>/dev/null || echo missing)"
    push="$(docker inspect --format '{{.State.Health.Status}}' league-push 2>/dev/null || echo missing)"
    if [ "$status" = "healthy" ] && [ "$push" = "healthy" ]; then
      return 0
    fi
    sleep 5
    waited=$((waited + 5))
  done
  echo "헬스 체크 실패: ${HEALTH_TIMEOUT}초 동안 healthy 가 아니었습니다(마지막 상태: 앱 $status, 푸시 $push)" >&2
  return 1
}

# 이미지를 먼저 받아 둔다. 받기에 실패하면 지금 도는 앱은 건드리지 않고 끝난다
if [ "${SKIP_PULL:-0}" != "1" ]; then
  IMAGE="$NEW_IMAGE" PUSH_IMAGE="$NEW_PUSH" docker compose pull
fi

echo "배포: ${PREV_IMAGE:-(처음)} → $NEW_IMAGE (푸시 $NEW_PUSH)"
up "$NEW_IMAGE" "$NEW_PUSH"
if wait_healthy; then
  echo "$NEW_IMAGE" > "$STATE_FILE"
  echo "$NEW_PUSH" > "$PUSH_STATE_FILE"
  echo "배포 성공"
  exit 0
fi

docker logs --tail 50 league-app >&2 || true
docker logs --tail 20 league-push >&2 || true
if [ -n "$PREV_IMAGE" ]; then
  echo "직전 이미지로 되돌립니다: $PREV_IMAGE" >&2
  up "$PREV_IMAGE" "$PREV_PUSH"
  wait_healthy && echo "되돌리기 성공" >&2
else
  # 처음 배포라 되돌릴 곳이 없다. 죽었다 살아나기를 반복하지 않게 멈춰 둔다
  echo "되돌릴 이미지가 없어 앱을 멈춥니다" >&2
  IMAGE="$NEW_IMAGE" PUSH_IMAGE="$NEW_PUSH" docker compose stop
fi
exit 1
