#!/bin/bash
# 6주차⑭ P9 DB 장애: 평소 부하 중 RDS 를 재부팅하면 앱은 몇 초 동안 무엇을 돌려주고, DB 가 돌아온 뒤 스스로 회복하나.
# 서버 /opt/league 에서 k6(steady)와 같이 돌린다:  ./p9-watch.sh [초=600]
# 재부팅은 CloudShell 에서 직접 한다(aws rds reboot-db-instance). 이 스크립트는 그동안 0.5초마다 남긴다.
#   /actuator/health 상태 코드와 걸린 시간(DB 상태가 들어 있다. DB 가 죽으면 503). 사용자 API 쪽은 k6 가 본다
# 끝나면 같은 코드가 이어진 구간과, 그 동안 앱 로그에 찍힌 오류 종류별 개수를 보여 준다.
set -uo pipefail
cd "$(dirname "$0")"
DUR="${1:-600}"
TS="$(date -u +%H%M%S)"
OUT="p9-$TS.txt"
SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "기록 시작 $(date -u +%H:%M:%S), ${DUR}초"

end=$(( $(date +%s) + DUR ))
while [ "$(date +%s)" -lt "$end" ]; do
  printf '%s health=%s\n' "$(date -u +%H:%M:%S.%1N)" \
    "$(curl -s -o /dev/null -m 5 -w '%{http_code} %{time_total}' localhost/actuator/health)" >> "$OUT"
  sleep 0.5
done

echo "== health 상태가 이어진 구간(UTC). 괄호 안은 그 구간에서 가장 오래 걸린 응답(초)"
awk '{key = $2; t = $3 + 0
  if (key != prev) {if (NR > 1) printf "%s ~ %s  %s (%d번, 최대 %.2f초)\n", start, last, prev, n, mx; start = $1; n = 0; mx = 0}
  prev = key; last = $1; n++; if (t > mx) mx = t}
  END {printf "%s ~ %s  %s (%d번, 최대 %.2f초)\n", start, last, prev, n, mx}' "$OUT"
echo "== 그동안 앱 로그의 경고·오류(종류별)"
docker logs league-app --since "$SINCE" 2>&1 | grep -E ' (WARN|ERROR) ' \
  | sed -E 's/^[^ ]+ +(WARN|ERROR) [0-9]+ --- \[[^]]*\] \[[^]]*\] ([^ ]+) *: (.{0,90}).*/\1 \2: \3/' \
  | sed -E 's/[0-9]+ms/Nms/g; s/[0-9]{2,}/N/g' | sort | uniq -c | sort -rn | head -15
echo "원본: $PWD/$OUT"
