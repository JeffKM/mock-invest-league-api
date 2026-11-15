#!/bin/bash
# 6주차⑦ P6 동시 영향: 평소 부하(k6 steady) 중에 10만 명 스냅샷을 찍으면 다른 요청이 얼마나 늦어지나.
# 서버 /opt/league 에서 단계별로 실행한다(시드는 그 자체가 무거운 쓰기라 부하 전에 끝낸다).
#   ./p6-concurrent.sh seed            10만 명 대회 만들기(부하 걸기 전에)
#   ./p6-concurrent.sh snap [횟수=4] [간격초=40] [기준선초=60]   k6 와 함께(아무 때나) 시작한다.
#     끔 → 켬 → 끔 → 켬 으로 번갈아 찍는다(?tuning=). 첫 시도(work_mem·synchronous_commit)는 되돌려서
#     지금 앱은 이 값을 무시한다(28단계). 다음 시도의 전후 비교에 같은 틀을 다시 쓴다.
#     k6 준비(사용자 만들기) 시간은 매번 달랐다(1분 30초~2분 30초). 그래서 시각을 정해 두지 않고
#     앱의 초당 요청 수가 80 을 넘을 때(= steady 본 부하 시작)까지 기다린 뒤 기준선만큼 더 기다려 첫 장을 찍는다.
#     서버 시계가 고정이라 틱이 같으면 같은 스냅샷은 다시 만들지 않는다(on conflict do nothing, 3ms 만에 끝남).
#     그래서 찍은 스냅샷을 지워 다음 장이 진짜로 계산되게 한다. 지우기(10만 줄)도 DB 일이라
#     스냅샷과 같은 10초 창에 섞이지 않게 20초 뒤에 지운다.
#     첫 장 직전부터 150초 동안 1초마다 DB 세션 상태(activity.sql)를 같이 기록한다 → activity-<시각>.txt
#   ./p6-concurrent.sh clean           p6 대회 지우기(부하 끝난 뒤)
# 시각(UTC)을 찍어 두면 Grafana 에서 그 구간을 찾는다. psql.sh 호출에는 </dev/null (28단계)
set -euo pipefail
cd "$(dirname "$0")"
A="localhost/api/admin/contests"

# 그 대회의 스냅샷을 모두 지운다(다음 스냅샷이 같은 틱이어도 새로 계산되게)
drop_snapshots() {
  ./psql.sh -qc "delete from ranking_entries where snapshot_id in (select id from ranking_snapshots where contest_id = $1);
    delete from ranking_snapshots where contest_id = $1" </dev/null
}

contest() {
  ./psql.sh -tAc "select max(id) from contests where name like 'p6-scale-%'" </dev/null | tr -d ' \r'
}

# 앱이 지금까지 받은 요청 수(액추에이터 제외). /actuator/prometheus 의 http_server_requests 개수를 더한다
requests_so_far() {
  curl -s localhost/actuator/prometheus | grep '^http_server_requests_seconds_count' | grep -v 'uri="/actuator' \
    | awk '{s += $2} END {printf "%d", s}'
}

# 5초 동안 초당 80건을 넘으면 돌아온다(steady 는 초당 120건, k6 준비 단계는 20건 안팎). 최대 10분
wait_for_load() {
  local before after
  for _ in $(seq 1 120); do
    before="$(requests_so_far)"; sleep 5; after="$(requests_so_far)"
    if [ $(( (after - before) / 5 )) -ge 80 ]; then return 0; fi
  done
  echo "10분 동안 부하가 안 왔습니다" >&2
  exit 1
}

case "${1:-}" in
  seed)
    ./psql.sh -v n="${2:-100000}" -f /sql/p6-seed.sql </dev/null > /dev/null
    echo "대회=$(contest) 준비 끝 $(date -u +%H:%M:%S)"
    ;;
  snap)
    COUNT="${2:-4}"
    GAP="${3:-40}"
    BASE="${4:-60}"
    T="$(grep '^ADMIN_TOKEN=' .env | cut -d= -f2-)"
    C="$(contest)"
    [ -n "$C" ] || { echo "p6 대회가 없습니다. 먼저 seed" >&2; exit 1; }
    drop_snapshots "$C"   # 지난 실행이 남긴 스냅샷이 있으면 첫 장이 3ms 만에 끝난다
    echo "부하 기다리는 중 $(date -u +%H:%M:%S)"
    wait_for_load
    echo "부하 시작 확인 $(date -u +%H:%M:%S), 기준선 ${BASE}초"
    sleep "$((BASE - 5))"
    LOG="activity-$(date -u +%H%M%S).txt"
    ./psql.sh -tA -F ' ' -f /sql/activity.sql </dev/null > "$LOG" 2>&1 &
    act=$!
    sleep 5
    for i in $(seq 1 "$COUNT"); do
      if [ $((i % 2)) -eq 1 ]; then tuning=false; else tuning=true; fi
      start="$(date -u +%H:%M:%S)"
      took="$(curl -s -o /dev/null -w '%{http_code} %{time_total}s' -X POST -H "X-Admin-Token: $T" \
        "$A/$C/ranking-snapshots?tuning=$tuning")"
      echo "스냅샷 $i 설정=$tuning 대회=$C 시작=$start 결과=$took"
      sleep 20
      drop_snapshots "$C"
      echo "  지움 끝 $(date -u +%H:%M:%S)"
      if [ "$i" -lt "$COUNT" ]; then sleep "$((GAP - 25))"; fi
    done
    wait "$act" || true
    echo "끝 $(date -u +%H:%M:%S). DB 세션 기록(시각은 한국 시각): $LOG"
    echo "열: 시각 | 앱 활성 세션 | 행 잠금 대기 | 디스크 대기 | 내부 잠금 대기 | autovacuum | RDS 관리 | 대기 종류"
    awk '$2 >= 2 || $3 > 0 || $4 > 0 || $5 > 0' "$LOG"   # 바쁜 초만 보여 준다
    ;;
  clean)
    ./psql.sh -f /sql/p6-cleanup.sql </dev/null | tail -3
    ;;
  *)
    echo "사용: $0 seed | snap [횟수] [간격초] [기준선초] | clean" >&2
    exit 1
    ;;
esac
