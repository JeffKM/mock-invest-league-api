#!/usr/bin/env bash
# 5주차② k6 부하 테스트 러너. 앱을 운영 설정(prod) 그대로, CPU·메모리를 t3.micro 와 비슷하게 묶은 컨테이너로 띄우고 k6 로 친다.
# 사용: ./scripts/load-test.sh [배수=3] [시간=2m]     예) ./scripts/load-test.sh 1 1m
# 전제: docker compose up -d postgres, ./gradlew bootJar && docker build -t mock-invest-league-api ., k6 설치
# 환경 변수: CLOCK_SPEED(기본 0=시계 고정, 5=5배속. tick 시나리오에서 틱이 실제로 바뀌게) CPUS(기본 2) MEM(기본 700m) IMAGE(기본 mock-invest-league-api) USERS(기본 1000) POOL(기본 10)
#           JAVA_EXTRA(JVM 옵션 추가) LABEL(결과 폴더 이름 꼬리) JFR=1(프로파일 기록)
set -euo pipefail
cd "$(dirname "$0")/.."

MULT="${1:-3}"
DURATION="${2:-2m}"
CPUS="${CPUS:-2}"
MEM="${MEM:-700m}"
IMAGE="${IMAGE:-mock-invest-league-api}"
POOL="${POOL:-10}"
K6="${K6:-$(command -v k6 || echo "$HOME/.local/bin/k6")}"
SCENARIO="${SCENARIO:-}"   # 비우면 league.js(예전 기준), 채우면 realistic.js 의 그 시나리오(steady|rush|tick|hot|journey)
SCRIPT="scripts/k6/${SCENARIO:+realistic.js}"; [ -z "$SCENARIO" ] && SCRIPT=scripts/k6/league.js
OUT="build/k6/x${MULT}-cpu${CPUS}-pool${POOL}${SCENARIO:+-$SCENARIO}${LABEL:+-$LABEL}"   # LABEL: 같은 조건에서 설정만 바꾼 실험 이름
APP=league-load
DB=league_load
PORT=18081
mkdir -p "$OUT"

psql() { docker exec league-postgres psql -U league -d "${PGDB:-league}" -qtAX "$@"; }

cleanup() {
  [ -n "${SAMPLER:-}" ] && kill "$SAMPLER" 2>/dev/null || true
  docker stop -t 30 "$APP" > /dev/null 2>&1 || true   # SIGTERM → JVM 이 정상 종료하며 JFR 기록을 파일로 쓴다
  docker logs "$APP" > "$OUT/app.log" 2>&1 || true
  [ "${JFR:-0}" = 1 ] && docker cp "$APP:/tmp/app.jfr" "$OUT/app.jfr" > /dev/null 2>&1 || true
  docker rm -f "$APP" > /dev/null 2>&1 || true
}
trap cleanup EXIT

# 매번 빈 DB 에서 시작한다(앞선 실행의 주문이 쌓여 결과가 달라지지 않게)
psql -c "drop database if exists $DB" -c "create database $DB"
TOKEN="$(openssl rand -hex 16)"

# 장중 시각(12월 1일 화요일 10시)으로 시계를 고정해야 밤에도 주문이 받아진다. metrics·prometheus 는 결과 수집과 그래프용으로만 연다
# 그래프: 먼저 monitoring/up.sh local 을 띄워 두면 Grafana(localhost:3000)에 이 실행이 그려진다
# JFR=1 이면 JDK Flight Recorder 로 CPU 를 어디에 쓰는지 기록한다(분석: jfr print --events jdk.ExecutionSample)
JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"
JAVA_OPTS="$JAVA_OPTS ${JAVA_EXTRA:-}"   # 예: JAVA_EXTRA=-XX:+UseG1GC
[ "${JFR:-0}" = 1 ] && JAVA_OPTS="$JAVA_OPTS -XX:StartFlightRecording=filename=/tmp/app.jfr,settings=profile,dumponexit=true"
docker run -d --name "$APP" --cpus "$CPUS" -m "$MEM" -p "$PORT:8080" -e JAVA_TOOL_OPTIONS="$JAVA_OPTS" \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL="jdbc:postgresql://host.docker.internal:5433/$DB" -e DB_USERNAME=league -e DB_PASSWORD=league \
  -e DB_POOL_SIZE="$POOL" -e ADMIN_TOKEN="$TOKEN" \
  -e LEAGUE_CLOCK_FIXED=2026-12-01T10:00:00 -e LEAGUE_CLOCK_SPEED="${CLOCK_SPEED:-0}" \
  -e ACTUATOR_EXPOSE=health,info,metrics,prometheus \
  "$IMAGE" > /dev/null

for _ in $(seq 1 60); do
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$APP")" = healthy ] && break
  sleep 5
done
[ "$(docker inspect --format '{{.State.Health.Status}}' "$APP")" = healthy ] || { echo "앱이 뜨지 않았습니다" >&2; exit 1; }

# 5초마다 앱·DB 컨테이너의 CPU·메모리를 적어 둔다(어느 쪽이 먼저 한계에 닿는지 보기 위함)
( while true; do docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' "$APP" league-postgres \
    | sed "s/^/$(date +%s),/"; sleep 5; done > "$OUT/stats.csv" 2>/dev/null ) &
SAMPLER=$!

LOAD_BEFORE="$(uptime | sed 's/.*averages*: //')"   # 이 맥의 다른 작업이 결과를 흔드므로 같이 적는다
before="$(PGDB=$DB psql -c "select xact_commit, blks_read, blks_hit from pg_stat_database where datname = '$DB'")"

# k6 워치독: 정해진 시간 + 준비 10분을 넘기면 강제 종료
# k6 HTML 리포트(report.html): 사용자 쪽에서 본 요청 수·지연·실패를 시간 순 그래프로 남긴다
# 30단계: 맥의 셸은 열 수 있는 파일(소켓) 수가 256 으로 시작한다. 재전송 측정에서 VU 수천 개가 소켓을 열다
# "too many open files" 로 요청 수만 건이 서버에 가지도 못하고 실패했다(부하 발생기 쪽 한계를 서버 실패로 잘못 읽을 뻔함)
ulimit -n "$(sysctl -n kern.maxfilesperproc 2>/dev/null || echo 10240)" 2>/dev/null || ulimit -n 10240 2>/dev/null || true
echo "열 수 있는 파일 수(ulimit -n): $(ulimit -n)"
START_MS="$(date +%s)000"
set +e
( K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_PERIOD=2s K6_WEB_DASHBOARD_EXPORT="$OUT/report.html" "$K6" run --quiet -e BASE_URL="http://localhost:$PORT" -e ADMIN_TOKEN="$TOKEN" -e MULT="$MULT" -e DURATION="$DURATION" \
    -e USERS="${USERS:-1000}" -e SUMMARY="$OUT/summary.json" -e SCENARIO="$SCENARIO" -e SCALE="${SCALE:-1}" -e LIMITS="${LIMITS:-3}" -e SETTLE_AGAIN="${SETTLE_AGAIN:-0}" -e STORM="${STORM:-30}" -e PEAK="${PEAK:-5}" -e RUSH="${RUSH:-4}" -e RUSH_RAMP="${RUSH_RAMP:-10}" -e SOAK_MIN="${SOAK_MIN:-30}" -e TIMEOUT="${TIMEOUT:-2s}" -e RETRIES="${RETRIES:-0}" \
    "$SCRIPT" > "$OUT/k6.log" 2>&1 & pid=$!
  ( sleep 1200; kill -9 $pid 2>/dev/null ) & wd=$!
  wait $pid; code=$?; kill $wd 2>/dev/null; exit $code )
K6_EXIT=$?
set -e
END_MS="$(date +%s)000"

after="$(PGDB=$DB psql -c "select xact_commit, blks_read, blks_hit from pg_stat_database where datname = '$DB'")"
metric() { curl -s "localhost:$PORT/actuator/metrics/$1" | python3 -c "import json,sys; d=json.load(sys.stdin); print(' '.join(f\"{m['statistic']}={m['value']:.4g}\" for m in d.get('measurements',[])))"; }
{
  echo "호스트 load average(1·5·15분): 시작 $LOAD_BEFORE / 끝 $(uptime | sed 's/.*averages*: //')"
  echo "DB(xact_commit, blks_read, blks_hit) 전: $before / 후: $after"
  echo "커넥션 얻기 대기(hikaricp.connections.acquire): $(metric hikaricp.connections.acquire)"
  echo "커넥션 사용 시간(hikaricp.connections.usage): $(metric hikaricp.connections.usage)"
  echo "GC 멈춤(jvm.gc.pause): $(metric jvm.gc.pause)"
  echo "캐시(cache.gets): $(metric cache.gets)"
  echo "캐시 놓침 → DB 읽기 시간(league.cache.load): $(metric league.cache.load)"
  echo "같은 키 동시 로드(league.cache.concurrent_loads): $(metric league.cache.concurrent_loads)"
  echo "앱 CPU 최대: $(grep ",$APP," "$OUT/stats.csv" | cut -d, -f3 | tr -d % | sort -n | tail -1)%  DB CPU 최대: $(grep ',league-postgres,' "$OUT/stats.csv" | cut -d, -f3 | tr -d % | sort -n | tail -1)%"
} > "$OUT/server.txt"

cat "$OUT/summary.md" 2>/dev/null || tail -20 "$OUT/k6.log"
cat "$OUT/server.txt"
echo "k6 리포트: $OUT/report.html"
echo "Grafana(monitoring/up.sh local 을 띄웠다면): http://localhost:3000/d/league?from=$START_MS&to=$END_MS"
echo "결과: $OUT (k6 종료 코드 $K6_EXIT: 0 이면 통과 기준을 모두 만족)"
exit $K6_EXIT
