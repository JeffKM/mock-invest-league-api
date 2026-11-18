#!/bin/bash
# 배포한 서버에 k6 를 보낸다(로컬 앱을 띄우는 load-test.sh 와 달리 서버는 건드리지 않는다).
# 사용: ADMIN_TOKEN 을 환경 변수로 준 뒤  scripts/load-test-remote.sh <BASE_URL> [MULT] [DURATION] [LABEL]
#   예) read -s ADMIN_TOKEN; export ADMIN_TOKEN; scripts/load-test-remote.sh http://3.107.29.43 1 2m laptop
# 노트북(macOS)과 AWS CloudShell(Linux) 양쪽에서 돈다. 결과는 build/k6/remote-<LABEL>-<시각>/
# SCENARIO=steady|rush|tick|hot|journey 를 주면 realistic.js 의 그 시나리오를 돌린다(비우면 league.js, 예전 기준)
# realistic.js 시나리오의 길이는 DURATION 이 아니라 SCALE(단계 길이 배율)로 정한다. 예) steady 2분 × SCALE=3 = 6분
# 그래프: 노트북에서 monitoring/up.sh <서버 IP> 를 띄워 두면 Grafana(localhost:3000)에 서버 지표가 그려진다
#   (서버 .env 의 ACTUATOR_EXPOSE 에 prometheus 가 있어야 한다)
set -euo pipefail
BASE="${1:?사용법: scripts/load-test-remote.sh <BASE_URL> [MULT] [DURATION] [LABEL]}"
MULT="${2:-1}"
DURATION="${3:-2m}"
LABEL="${4:-$(hostname -s)}"
: "${ADMIN_TOKEN:?ADMIN_TOKEN 환경 변수가 없습니다(read -s ADMIN_TOKEN; export ADMIN_TOKEN)}"
K6="${K6:-$(command -v k6 || echo "$HOME/.local/bin/k6")}"
WATCHDOG="${WATCHDOG:-1200}"   # k6 가 이 초 안에 안 끝나면 강제 종료(좀비 방지)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCENARIO="${SCENARIO:-}"
SCRIPT="$ROOT/scripts/k6/league.js"; [ -n "$SCENARIO" ] && SCRIPT="$ROOT/scripts/k6/realistic.js"

# 운영 서버는 실제 시계로 돈다. 장 시간(한국 평일 09:00~15:30)이 아니면 주문이 전부 거절되므로 먼저 막는다.
# 장 밖에 재려면 서버 .env 에 LEAGUE_CLOCK_FIXED=<날짜>T10:00:00 을 넣고 다시 띄운 뒤, 같은 날짜를 SERVER_DATE 로 준다
if [ -n "${SERVER_DATE:-}" ]; then
  TODAY="$SERVER_DATE"
  echo "서버 시계를 $TODAY 로 고정했다고 보고 장 시간 확인을 건너뜁니다"
else
  NOW_KST=$(TZ=Asia/Seoul date +%H%M); DOW=$(TZ=Asia/Seoul date +%u)
  if [ "$DOW" -ge 6 ] || [ "$NOW_KST" -lt 0905 ] || [ "$NOW_KST" -gt 1500 ]; then
    echo "지금은 한국 시각 $(TZ=Asia/Seoul date '+%a %H:%M') 입니다. 평일 09:05~15:00 에 돌리거나, 서버 시계를 고정하고 SERVER_DATE 를 주세요." >&2
    exit 1
  fi
  TODAY=$(TZ=Asia/Seoul date +%F)
fi
if date -v+1d >/dev/null 2>&1; then
  ENDS=$(date -j -v+30d -f %F "$TODAY" +%F)
else
  ENDS=$(date -d "$TODAY +30 days" +%F)
fi

OUT="$ROOT/build/k6/remote-$LABEL${SCENARIO:+-$SCENARIO}-x$MULT-$(TZ=Asia/Seoul date +%m%d-%H%M)"
mkdir -p "$OUT"
echo "결과: $OUT"

# 1) 네트워크 왕복 시간: 응답 시간에서 빼고 읽기 위해 먼저 잰다(5번, 연결·첫 바이트)
for _ in 1 2 3 4 5; do
  curl -s -o /dev/null -w '%{time_connect} %{time_starttransfer}\n' "$BASE/actuator/health"
done > "$OUT/rtt.txt"
awk '{c+=$1; t+=$2} END {printf "왕복(연결) 평균 %.0f ms, health 첫 바이트 평균 %.0f ms\n", c/NR*1000, t/NR*1000}' "$OUT/rtt.txt" | tee "$OUT/rtt-summary.txt"
RTT_MS=$(awk '{c+=$1} END {printf "%d", c/NR*1000}' "$OUT/rtt.txt")   # 지연 기준에 더할 왕복 시간

# 2) 서버 쪽 지표(ACTUATOR_EXPOSE 에 metrics 가 있을 때만). 전후를 남겨 차이를 본다
snap() {
  for m in http.server.requests hikaricp.connections.pending hikaricp.connections.timeout jvm.gc.pause process.cpu.usage; do
    printf '%s ' "$m"; curl -s -m 5 "$BASE/actuator/metrics/$m" || true; echo
  done > "$OUT/metrics-$1.txt"
}
snap before

# 3) k6 (워치독으로 시간 제한)
# k6 HTML 리포트(report.html): 사용자 쪽에서 본 요청 수·지연·실패를 시간 순 그래프로 남긴다
# 30단계: 맥의 셸은 열 수 있는 파일(소켓) 수가 256 으로 시작한다. 재전송 측정에서 VU 수천 개가 소켓을 열다
# "too many open files" 로 요청 수만 건이 서버에 가지도 못하고 실패했다(부하 발생기 쪽 한계를 서버 실패로 잘못 읽을 뻔함)
ulimit -n "$(sysctl -n kern.maxfilesperproc 2>/dev/null || echo 10240)" 2>/dev/null || ulimit -n 10240 2>/dev/null || true
echo "열 수 있는 파일 수(ulimit -n): $(ulimit -n)"
START_MS="$(date +%s)000"
set +e
(
  K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_PERIOD=2s K6_WEB_DASHBOARD_EXPORT="$OUT/report.html" "$K6" run -e BASE_URL="$BASE" -e ADMIN_TOKEN="$ADMIN_TOKEN" -e MULT="$MULT" -e DURATION="$DURATION" \
    -e STARTS_ON="$TODAY" -e ENDS_ON="$ENDS" -e SUMMARY="$OUT/summary.json" -e SCENARIO="$SCENARIO" -e TICK_ALIGN="${TICK_ALIGN:-1}" -e RTT_MS="$RTT_MS" -e SCALE="${SCALE:-1}" -e LIMITS="${LIMITS:-3}" -e SETTLE_AGAIN="${SETTLE_AGAIN:-0}" -e STORM="${STORM:-30}" -e PEAK="${PEAK:-5}" -e RUSH="${RUSH:-4}" -e RUSH_RAMP="${RUSH_RAMP:-10}" -e TIMEOUT="${TIMEOUT:-2s}" -e RETRIES="${RETRIES:-0}" \
    "$SCRIPT" > "$OUT/k6.log" 2>&1 &
  pid=$!
  ( sleep "$WATCHDOG"; kill -9 "$pid" 2>/dev/null ) & wd=$!
  wait "$pid"; code=$?
  kill "$wd" 2>/dev/null
  exit "$code"
)
K6_EXIT=$?
set -e
END_MS="$(date +%s)000"

snap after
curl -s -m 5 "$BASE/actuator/health" > "$OUT/health-after.txt" || true
echo "k6 종료 코드 $K6_EXIT (99 = 통과 기준 미달, 그 밖의 0 아닌 값 = 실행 실패)" | tee -a "$OUT/rtt-summary.txt"
[ -f "$OUT/summary.md" ] && cat "$OUT/summary.md" || tail -40 "$OUT/k6.log"
# 부하 발생기 쪽 실패(서버에 닿지도 않은 요청)를 따로 센다. 0 이 아니면 결과표의 실패율을 서버 탓으로 읽으면 안 된다
LOCAL_FAIL="$(grep -c 'too many open files' "$OUT/k6.log" || true)"
echo "부하 발생기 쪽 실패(too many open files): ${LOCAL_FAIL:-0}건"
echo "k6 리포트: $OUT/report.html"
echo "Grafana 시간 범위(노트북에서 monitoring/up.sh 를 띄웠다면): http://localhost:3000/d/league?from=$START_MS&to=$END_MS"
