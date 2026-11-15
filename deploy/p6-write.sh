#!/bin/bash
# 6주차⑦ P6 쓰기 절반: 외래 키가 있을 때와 없을 때(트랜잭션 안에서 잠시 빼고 되돌림)를 번갈아 잰다.
# 서버 /opt/league 에서 실행: ./p6-write.sh [반복 수=3] [참가자 수=100000]
# 10만 명 대회를 만들고, 재고, 마지막에 p6-cleanup.sql 로 지운다. 아무것도 영구히 바꾸지 않는다.
# psql.sh 는 docker run -i 라 터미널 입력을 가져간다 → 모든 호출에 </dev/null (28단계)
set -euo pipefail
cd "$(dirname "$0")"
ROUNDS="${1:-3}"
N="${2:-100000}"

./psql.sh -v n="$N" -f /sql/p6-seed.sql </dev/null > /dev/null
C="$(./psql.sh -tAc "select max(id) from contests where name like 'p6-scale-%'" </dev/null | tr -d ' \r')"
echo "대회=$C 참가자=$N"

measure() {
  ./psql.sh -v c="$C" "$@" -f /sql/p6-write.sql </dev/null \
    | grep -E "^ Insert on|WindowAgg|Trigger for|Execution Time" \
    | sed -E 's/\(cost=[^)]*\) //; s/^ +/    /'
}

for i in $(seq 1 "$ROUNDS"); do
  echo "외래 키 있음 $i회"
  measure
  echo "외래 키 없음 $i회"
  measure -v nofk=1
done

./psql.sh -f /sql/p6-cleanup.sql </dev/null | tail -3
