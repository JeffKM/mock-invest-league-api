#!/usr/bin/env bash
# 조회 캐시 비교: 같은 데이터·같은 부하로 캐시 없음 / 서버 메모리(caffeine) / Redis 를 차례로 잰다.
# 준비: docker compose up -d && ./gradlew bootJar
# 사용: scripts/cache-bench.sh [요청 수=10000] [동시 연결=20]
# 결과: build/bench/summary.txt (각 방식의 초당 처리량, 응답 시간 50·95·99%, 요청당 DB 블록·트랜잭션)
set -u
cd "$(dirname "$0")/.."
N="${1:-10000}"; C="${2:-20}"; PORT=18080
JAR=$(ls build/libs/*.jar | grep -v plain | head -1)
OUT=build/bench; mkdir -p "$OUT"; : > "$OUT/summary.txt"
pg() { docker exec -i league-postgres psql -qtA -U league -d "$1"; }
dbstat() { echo "select blks_hit + blks_read, xact_commit from pg_stat_database where datname = 'league_perf'" | pg league; }

for TYPE in none caffeine redis; do
  echo "drop database if exists league_perf; create database league_perf;" | pg league
  docker exec league-redis redis-cli flushall >/dev/null
  java -jar "$JAR" --server.port=$PORT \
    --spring.datasource.url=jdbc:postgresql://localhost:5433/league_perf \
    --spring.cache.type=$TYPE --league.clock.fixed=2027-01-30T10:20:00 \
    --league.scheduling.enabled=false --logging.level.org.hibernate.SQL=warn > "$OUT/app-$TYPE.log" 2>&1 &
  APP=$!
  ( sleep 900; echo "워치독: 앱 강제 종료" >&2; kill -9 $APP 2>/dev/null ) & WD=$!
  for i in $(seq 1 120); do curl -sf localhost:$PORT/actuator/health >/dev/null && break; sleep 1; done
  pg league_perf < scripts/perf-seed.sql
  CONTEST=$(echo "select id from contests" | pg league_perf)

  for EP in rankings me; do
    URL="http://localhost:$PORT/api/contests/$CONTEST/$EP"
    [ "$EP" = rankings ] && URL="$URL?limit=50"
    ab -q -k -n 1000 -c "$C" -H "X-User-Id: 1" "$URL" >/dev/null 2>&1   # 데우기(JIT·연결 풀·캐시 채우기)
    read -r B0 T0 <<<"$(dbstat | tr '|' ' ')"
    ab -q -k -n "$N" -c "$C" -H "X-User-Id: 1" "$URL" > "$OUT/$TYPE-$EP.txt" 2>&1
    read -r B1 T1 <<<"$(dbstat | tr '|' ' ')"
    RPS=$(grep "Requests per second" "$OUT/$TYPE-$EP.txt" | awk '{print $4}')
    P=$(grep -E "^ +(50|95|99)%" "$OUT/$TYPE-$EP.txt" | awk '{printf "%s=%sms ", $1, $2}')
    FAIL=$(grep -E "^(Failed|Non-2xx)" "$OUT/$TYPE-$EP.txt" | awk -F: '{gsub(/ /,"",$2); printf "%s ", $2}')
    printf "%-8s %-9s %8s req/s  %s 블록/요청=%.1f 트랜잭션/요청=%.2f 실패=%s\n" "$TYPE" "$EP" "$RPS" "$P" \
      "$(echo "($B1-$B0)/$N" | bc -l)" "$(echo "($T1-$T0)/$N" | bc -l)" "${FAIL:-0}" | tee -a "$OUT/summary.txt"
  done
  kill $APP 2>/dev/null; wait $APP 2>/dev/null; pkill -P $WD 2>/dev/null; kill $WD 2>/dev/null
done
echo "drop database if exists league_perf;" | pg league
