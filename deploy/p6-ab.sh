#!/bin/bash
# 6주차⑦ P6 전후 비교. 서버 /opt/league 에서 실행: ./p6-ab.sh [반복 수=3] [참가자 수=100000]
# 옛 경로(SQL 로 만든 대회, 상장 통계 없음)와 새 경로(관리자 API 로 만들고 시작한 대회 = 시작할 때 ANALYZE)를
# 번갈아 만들고 10만 명 순위 스냅샷 시간을 잰다. 매 회 끝에 p6-cleanup.sql 로 지워 DB 크기를 같게 둔다.
# 관리자 토큰은 .env 에서 셸 변수로만 읽고 화면에 찍지 않는다.
set -euo pipefail
cd "$(dirname "$0")"
ROUNDS="${1:-3}"
N="${2:-100000}"
T="$(grep '^ADMIN_TOKEN=' .env | cut -d= -f2-)"
A="localhost/api/admin/contests"  # compose 가 호스트 80 → 컨테이너 8080 으로 잇는다

snap() {
  curl -s -o /dev/null -w "$1 대회=$2 %{http_code} %{time_total}s\n" \
    -X POST -H "X-Admin-Token: $T" "$A/$2/ranking-snapshots"
}

# 그 순간 플래너가 아는 상장 표 줄 수·마지막 ANALYZE 시각, 그리고 읽기 부분의 실행 계획 요약
diag() {
  ./psql.sh -tAc "select 'listings 통계: 줄='||c.reltuples::int||' 바뀐줄='||s.n_mod_since_analyze
    ||' 수동='||coalesce(to_char(s.last_analyze,'HH24:MI:SS'),'-')
    ||' 자동='||coalesce(to_char(s.last_autoanalyze,'HH24:MI:SS'),'-')
    from pg_class c join pg_stat_user_tables s on s.relid = c.oid where c.relname = 'contest_listings'"
  ./psql.sh -v c="$1" -f /sql/p6-explain.sql | grep -E "Nested Loop|Hash.*Join|Execution Time" | sed 's/^ */    /'
}

cleanup() {
  ./psql.sh -f /sql/p6-cleanup.sql > /dev/null
}

for i in $(seq 1 "$ROUNDS"); do
  # 옛 경로: 시드가 대회·상장까지 SQL 로 만든다
  ./psql.sh -v n="$N" -f /sql/p6-seed.sql > /dev/null
  C="$(./psql.sh -tAc "select max(id) from contests where name like 'p6-scale-%'" | tr -d ' \r')"
  snap "옛(SQL대회) $i회" "$C"
  diag "$C"
  cleanup

  # 새 경로: API 로 만들고 시작한 뒤, 시드는 참가자·보유만 채운다
  BODY="{\"name\":\"p6-scale-api-$i\",\"startsOn\":\"2026-11-16\",\"endsOn\":\"2026-12-31\"}"
  C="$(curl -s -X POST -H "X-Admin-Token: $T" -H 'Content-Type: application/json' -d "$BODY" "$A" \
    | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)"
  if [ -z "$C" ]; then
    echo "대회 생성 실패" >&2
    exit 1
  fi
  curl -s -o /dev/null -w "시작 %{http_code}\n" -X POST -H "X-Admin-Token: $T" "$A/$C/start"
  ./psql.sh -v n="$N" -v c="$C" -f /sql/p6-seed.sql > /dev/null
  snap "새(API대회) $i회" "$C"
  diag "$C"
  cleanup
done
