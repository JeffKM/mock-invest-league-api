#!/bin/bash
# 6주차⑫ N3 장시간 부하에서 부하 시작 14~15분 뒤 DB 커넥션이 15~26초 통째로 멈췄다(앱 CPU 10%, RDS CPU 17%, 디스크 지연 정상).
# 멈추는 순간을 잡으려고 부하와 같이 돌린다(서버 /opt/league):  ./stall-watch.sh [초=2400]
# 1초마다 두 가지를 남긴다.
#   db-<시각>.txt   DB 세션 상태(activity.sql 과 같은 열) + 잠금을 기다리는 세션이 있으면 무엇을 누가 쥐고 있는지
#   net-<시각>.txt  앱 컨테이너의 TCP 재전송·시간 초과 누적값(EC2 ↔ RDS 사이 네트워크가 끊겼는지)
# 끝나면 바쁜 초와 재전송이 늘어난 초만 골라 보여 준다.
set -uo pipefail
cd "$(dirname "$0")"
DUR="${1:-2400}"
TS="$(date -u +%H%M%S)"
DB="db-$TS.txt"; NET="net-$TS.txt"
P="$(docker inspect -f '{{.State.Pid}}' league-app)"

./psql.sh -tA -F ' ' -v cnt="$DUR" -f /sql/stall.sql </dev/null > "$DB" 2>&1 &
db=$!

end=$(( $(date +%s) + DUR ))
while [ "$(date +%s)" -lt "$end" ]; do
  printf '%s %s\n' "$(date -u +%H:%M:%S)" \
    "$(sudo nsenter -t "$P" -n nstat -az TcpRetransSegs TcpExtTCPTimeouts TcpExtTCPLostRetransmit 2>/dev/null \
       | awk 'NR > 1 {printf "%s=%s ", $1, $2}')" >> "$NET"
  sleep 1
done
wait "$db" || true

echo "== DB: 앱 세션 5개 이상이거나 잠금·내부 잠금 대기가 있던 초(시각은 한국 시각)"
echo "열: 시각 | 앱 활성 | 행 잠금 대기 | 디스크 대기 | 내부 잠금 대기 | autovacuum | 대기 종류 | 잠금 쥔 쪽"
awk '$2 >= 5 || $3 > 0 || $5 > 0' "$DB" | head -60
echo "== 네트워크: 재전송이 늘어난 초(시각은 UTC)"
awk '{split($2, a, "="); r = a[2]; if (NR > 1 && r > prev) print; prev = r}' "$NET" | head -40
echo "원본: $PWD/$DB  $PWD/$NET"
