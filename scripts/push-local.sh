#!/usr/bin/env bash
# 7주차⑤ 본 API(Tomcat) + 푸시 서버(Netty)를 실제 프로세스로 띄워, 스냅샷을 찍으면 SSE 클라이언트에 시세가 도착하는지 본다.
# 사용: ./scripts/push-local.sh   (전제: docker compose up -d postgres redis, ./gradlew bootJar)
# 끝나면 두 프로세스를 끈다. 결과는 build/push-local/
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/push-local
API=18086
PUSH=18087
mkdir -p "$OUT"
rm -f "$OUT"/*

pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2> /dev/null || true; done; wait 2> /dev/null || true; }
trap cleanup EXIT

# 본 API: 로컬 설정(5433 Postgres, 6380 Redis) + 푸시 켬 + 장중 시각 고정 + 스케줄러 끔(스냅샷은 관리자 API 로 직접)
java -jar build/libs/mock-invest-league-api-0.0.1-SNAPSHOT.jar --server.port=$API --league.push.enabled=true \
  --league.clock.fixed=2026-12-01T10:00:00 --league.scheduling.enabled=false > "$OUT/api.log" 2>&1 &
pids+=($!)
java -jar push/build/libs/push-0.0.1-SNAPSHOT.jar --server.port=$PUSH --spring.data.redis.port=6380 > "$OUT/push.log" 2>&1 &
pids+=($!)

for port in $API $PUSH; do
  for _ in $(seq 1 60); do curl -sf "localhost:$port/actuator/health" > /dev/null && break; sleep 1; done
  curl -sf "localhost:$port/actuator/health" > /dev/null || { echo "$port 가 뜨지 않았습니다" >&2; tail -30 "$OUT"/*.log; exit 1; }
done
grep -h "started on port" "$OUT"/*.log | sed 's/.*: //'

json() { python3 -c "import json,sys; print(json.load(sys.stdin)['data']['id'])"; }
CONTEST=$(curl -sf -X POST "localhost:$API/api/admin/contests" -H 'Content-Type: application/json' \
  -d "{\"name\":\"push-$(date +%s)\",\"startsOn\":\"2026-12-01\",\"endsOn\":\"2026-12-31\",\"seed\":7}" | json)
curl -sf -X POST "localhost:$API/api/admin/contests/$CONTEST/start" > /dev/null
USER_ID=$(curl -sf -X POST "localhost:$API/api/users" -H 'Content-Type: application/json' -d "{\"nickname\":\"push-$CONTEST\"}" | json)
curl -sf -X POST "localhost:$API/api/contests/$CONTEST/participants" -H "X-User-Id: $USER_ID" > /dev/null

# SSE 클라이언트(15초 뒤 끊음). 붙은 뒤 스냅샷을 찍는다
curl -sN --max-time 15 "localhost:$PUSH/contests/$CONTEST/stream" > "$OUT/sse.txt" &
CURL=$!
sleep 2
T0=$(python3 -c 'import time; print(int(time.time()*1000))')
curl -sf -X POST "localhost:$API/api/admin/contests/$CONTEST/ranking-snapshots" > "$OUT/snapshot.json"
for _ in $(seq 1 50); do grep -q '^event:tick' "$OUT/sse.txt" && break; sleep 0.1; done
T1=$(python3 -c 'import time; print(int(time.time()*1000))')
kill $CURL 2> /dev/null || true
wait $CURL 2> /dev/null || true

echo "대회 $CONTEST, 스냅샷 응답: $(cat "$OUT/snapshot.json")"
if grep -q '^event:tick' "$OUT/sse.txt"; then
  echo "SSE 도착(스냅샷 요청부터 약 $((T1 - T0)) ms. 스냅샷 계산 시간이 포함된 값이다):"
  grep -A1 '^event:tick' "$OUT/sse.txt" | grep '^data:' | head -1 | cut -c1-300
else
  echo "SSE 에 tick 이 오지 않았습니다" >&2; cat "$OUT/sse.txt"; exit 1
fi
