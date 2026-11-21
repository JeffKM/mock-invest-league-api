#!/usr/bin/env bash
# 7주차③ CI 성능 스모크(성능 회귀 게이트). 앱 이미지를 운영 설정(prod)으로 띄우고 k6 로 짧게 친 뒤 세 가지를 본다.
#   1) 실패율·불변식 위반(k6 기준)     2) 지연 p95(느슨한 k6 기준)
#   3) 요청당 DB 문장 수(pg_stat_statements) ≤ 기준값 × (1 + 허용폭)  ← 엄격한 기준은 이것
# 3)을 쓰는 이유: 공유 러너의 지연은 같은 코드에서도 흔들려서 기준을 조이면 아무 일 없이 깨지고, 풀면 회귀를 놓친다.
# 쿼리를 더 부르는 회귀(캐시 빠짐, N+1)는 데이터가 작은 CI 에서 지연으로는 잘 안 보이지만, 문장 수는 기계 속도와 상관없이 그대로 늘어난다.
# 사용: ./scripts/perf-smoke.sh [이미지=mock-invest-league-api]
# 환경 변수: CACHE_TYPE(기본 caffeine. none 으로 회귀를 흉내 낸다) TOLERANCE(기본 0.05) UPDATE_BASELINE=1(기준값을 이번 측정으로 바꾼다)
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE="${1:-mock-invest-league-api}"
NET=league-smoke
DB=league-smoke-db
APP=league-smoke-app
PORT=18082
OUT=build/k6/smoke
BASELINE=scripts/perf-baseline.json
TOLERANCE="${TOLERANCE:-0.05}"   # 같은 코드 3회: 4.710·4.710·4.711(흔들림 0.02%). 캐시를 빼면 +15.7%
K6_IMAGE=grafana/k6:2.3.0   # 로컬 k6 와 같은 판. 러너에 k6 가 없으면 이 이미지로 돈다
mkdir -p "$OUT"
rm -f "$OUT"/*

cleanup() {
  docker logs "$APP" > "$OUT/app.log" 2>&1 || true
  docker rm -f "$APP" "$DB" > /dev/null 2>&1 || true
  docker network rm "$NET" > /dev/null 2>&1 || true
}
trap cleanup EXIT
docker rm -f "$APP" "$DB" > /dev/null 2>&1 || true   # 지난번에 남은 것(로그는 덮어쓰지 않는다)
docker network rm "$NET" > /dev/null 2>&1 || true

psql() { docker exec "$DB" psql -U league -d league -qtAX "$@"; }

docker network create "$NET" > /dev/null
# pg_stat_statements: 문장 종류별 호출 수를 센다. track_utility=off 로 BEGIN·COMMIT·SET 은 빼고 실제 SQL 만 센다
docker run -d --name "$DB" --network "$NET" -e POSTGRES_DB=league -e POSTGRES_USER=league -e POSTGRES_PASSWORD=league \
  postgres:16-alpine -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track_utility=off > /dev/null
for _ in $(seq 1 30); do docker exec "$DB" pg_isready -U league -d league > /dev/null 2>&1 && break; sleep 1; done

TOKEN="$(openssl rand -hex 16)"
# 장중 시각으로 시계를 고정해야 언제 돌려도 주문이 받아진다
# 스케줄러는 끈다. 실제 시계로 매분 도는 대기 주문 처리가 측정 구간에 끼었다 안 끼었다 하며 문장 수를 ±2% 흔들었다(요청 경로만 잰다)
docker run -d --name "$APP" --network "$NET" -p "$PORT:8080" --cpus 2 -m 700m \
  -e SPRING_PROFILES_ACTIVE=prod -e DB_URL="jdbc:postgresql://$DB:5432/league" -e DB_USERNAME=league -e DB_PASSWORD=league \
  -e ADMIN_TOKEN="$TOKEN" -e LEAGUE_CLOCK_FIXED=2026-12-01T10:00:00 -e CACHE_TYPE="${CACHE_TYPE:-caffeine}" -e LEAGUE_SCHEDULING_ENABLED=false \
  "$IMAGE" > /dev/null
for _ in $(seq 1 36); do
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$APP")" = healthy ] && break
  sleep 5
done
[ "$(docker inspect --format '{{.State.Health.Status}}' "$APP")" = healthy ] || { echo "앱이 뜨지 않았습니다" >&2; tail -40 "$OUT/app.log" 2>/dev/null; docker logs "$APP" 2>&1 | tail -40; exit 1; }
psql -c "create extension if not exists pg_stat_statements" > /dev/null

# k6: 로컬 바이너리가 있으면 그것, 없으면(러너) 도커 이미지. 토큰은 명령줄(-e)이 아니라 환경 변수로 넘긴다(ps 에 보이지 않게)
k6run() {
  local K6BIN
  K6BIN="${K6:-$(command -v k6 || { [ -x "$HOME/.local/bin/k6" ] && echo "$HOME/.local/bin/k6"; } || true)}"
  if [ -n "$K6BIN" ]; then
    ADMIN_TOKEN="$TOKEN" "$K6BIN" run --quiet "$@"
  else
    docker run --rm --network host -u "$(id -u)" -v "$PWD:$PWD" -w "$PWD" -e ADMIN_TOKEN="$TOKEN" "$K6_IMAGE" run --quiet "$@"
  fi
}
# 워치독: k6 가 멈추면 10분에 끊는다
# 워치독의 sleep 은 출력을 /dev/null 로 돌린다. 안 그러면 sleep 이 tee 로 가는 파이프를 물고 있어 k6 가 끝나도 10분을 기다린다
watch_k6() {
  k6run "$@" & local pid=$!
  ( sleep 600 && kill -9 "$pid" ) > /dev/null 2>&1 & local wd=$!
  wait "$pid"; local code=$?
  pkill -P "$wd" 2>/dev/null; kill "$wd" 2>/dev/null
  return "$code"
}
COMMON=(-e BASE_URL="http://localhost:$PORT" -e PREPARED="$PWD/$OUT/prepared.json" -e SUMMARY="$PWD/$OUT/summary.json")   # open() 은 스크립트 폴더 기준이라 절대 경로로 준다

watch_k6 -e PHASE=prepare "${COMMON[@]}" scripts/k6/smoke.js > "$OUT/prepare.log" 2>&1 || { cat "$OUT/prepare.log"; exit 1; }

psql -c "select pg_stat_statements_reset()" > /dev/null   # 준비 단계의 문장은 빼고 센다
set +e
watch_k6 -e PHASE=load "${COMMON[@]}" scripts/k6/smoke.js 2>&1 | tee "$OUT/k6.log"
K6_EXIT=${PIPESTATUS[0]}
set -e

# 요청당 DB 문장 수 = 부하 구간의 SQL 호출 합 / 부하 구간의 HTTP 요청 수(워밍업·불변식 검사 포함, 매번 같은 수)
STATEMENTS="$(psql -c "select coalesce(sum(calls), 0) from pg_stat_statements s join pg_database d on d.oid = s.dbid where d.datname = 'league' and query not ilike '%pg_stat_statements%'")"
psql -c "select calls, left(regexp_replace(query, '\s+', ' ', 'g'), 110) from pg_stat_statements s join pg_database d on d.oid = s.dbid where d.datname = 'league' order by calls desc limit 40" > "$OUT/top-statements.txt"
REQUESTS="$(python3 -c "import json; print(int(json.load(open('$OUT/summary.json'))['metrics']['http_reqs']['values']['count']))")"

GATE_EXIT=0
python3 - "$STATEMENTS" "$REQUESTS" "$BASELINE" "$TOLERANCE" "${UPDATE_BASELINE:-0}" > "$OUT/gate.md" <<'PY' || GATE_EXIT=$?
import json, sys
statements, requests, path, tol, update = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], float(sys.argv[4]), sys.argv[5] == '1'
per = statements / requests
if update:
    json.dump({"statementsPerRequest": round(per, 3)}, open(path, "w"), indent=2)
    open(path, "a").write("\n")
base = json.load(open(path))["statementsPerRequest"]
limit = base * (1 + tol)
ok = per <= limit
print(f"요청당 DB 문장 수: {per:.3f} (SQL {statements}회 / 요청 {requests}건), 기준 {base} × {1 + tol:.2f} = {limit:.3f} 이하 → {'통과' if ok else '실패'}")
sys.exit(0 if ok else 3)
PY
cat "$OUT/gate.md"
echo "가장 많이 불린 문장(호출 수, SQL 앞부분):"
cat "$OUT/top-statements.txt"

# GitHub Actions 요약 화면에 결과를 남긴다
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo "### 성능 스모크"; echo '```'; cat "$OUT/summary.md" "$OUT/gate.md"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi
[ "$K6_EXIT" -eq 0 ] || { echo "k6 기준 실패(종료 코드 $K6_EXIT)" >&2; exit "$K6_EXIT"; }
[ "$GATE_EXIT" -eq 0 ] || { echo "DB 문장 수 기준 실패: 위 표에서 호출 수가 늘어난 문장을 찾는다" >&2; exit "$GATE_EXIT"; }
