#!/bin/bash
# 6주차⑬ P10 트래픽 중 배포: 평소 부하가 걸린 채로 deploy.sh 를 돌리면 몇 초 동안 끊기고, 그 시간은 어디서 오나.
# 서버 /opt/league 에서 k6(steady)와 같이 돌린다:  ./p10-deploy.sh [기준선초=60] [이미지=지금 것]
# 부하가 오면(초당 80건 넘음) 기준선만큼 기다린 뒤 같은 이미지로 deploy.sh 를 돌린다. 그동안 두 가지를 남긴다.
#   0.2초마다 /actuator/health 상태 코드(000 = 연결 거절)  → 바깥에서 본 끊긴 구간
#   docker events(컨테이너 kill·die·start·health_status)  → 끊긴 구간이 어느 단계인지
set -uo pipefail
cd "$(dirname "$0")"
BASE="${1:-60}"
IMAGE="${2:-$(cat .deployed-image)}"
TS="$(date -u +%H%M%S)"
H="p10-health-$TS.txt"; E="p10-events-$TS.txt"; D="p10-deploy-$TS.txt"

requests_so_far() {
  curl -s localhost/actuator/prometheus | grep '^http_server_requests_seconds_count' | grep -v 'uri="/actuator' \
    | awk '{s += $2} END {printf "%d", s}'
}
echo "부하 기다리는 중 $(date -u +%H:%M:%S)"
for _ in $(seq 1 120); do
  b="$(requests_so_far)"; sleep 5; a="$(requests_so_far)"
  [ $(( (a - b) / 5 )) -ge 80 ] && break
done
echo "부하 확인 $(date -u +%H:%M:%S), ${BASE}초 뒤 배포"
sleep "$BASE"

# 상태 코드 기록(배포 시작 5초 전 ~ 끝나고 30초 뒤)
( while :; do
    printf '%s %s\n' "$(date -u +%H:%M:%S.%2N)" "$(curl -s -o /dev/null -m 1 -w '%{http_code}' localhost/actuator/health)"
    sleep 0.2
  done ) > "$H" &
hp=$!
docker events --filter container=league-app --format '{{.TimeNano}} {{.Action}}' > "$E" 2>&1 &
ep=$!
sleep 5

t0="$(date +%s.%N)"
echo "배포 시작 $(date -u +%H:%M:%S.%2N)"
SKIP_PULL=1 ./deploy.sh "$IMAGE" > "$D" 2>&1
rc=$?
echo "deploy.sh 끝 $(date -u +%H:%M:%S.%2N) 종료 코드 $rc (걸림 $(awk -v a="$t0" -v b="$(date +%s.%N)" 'BEGIN {printf "%.1f", b - a}')초)"
sleep 30
kill "$hp" "$ep" 2>/dev/null; wait 2>/dev/null

echo "== 컨테이너 단계(UTC, 배포 시작 기준 초)"
awk -v t0="$t0" '{split($0, a, " "); s = a[1] / 1e9; act = substr($0, length(a[1]) + 2);
  if (act ~ /^(kill|die|stop|start|create|destroy|health_status)/) printf "%+7.2f초 %s\n", s - t0, act}' "$E"
echo "== 바깥에서 본 /actuator/health(같은 코드가 이어지는 구간)"
awk '{c = $2; if (c != prev) {if (NR > 1) printf "%s ~ %s  %s (%d번)\n", start, last, prev, n; start = $1; n = 0}
  prev = c; last = $1; n++} END {printf "%s ~ %s  %s (%d번)\n", start, last, prev, n}' "$H"
echo "원본: $PWD/$H $PWD/$E $PWD/$D"
