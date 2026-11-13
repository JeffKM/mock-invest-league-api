#!/bin/bash
# 배포한 서버에 k6 를 보낸다(로컬 앱을 띄우는 load-test.sh 와 달리 서버는 건드리지 않는다).
# 사용: ADMIN_TOKEN 을 환경 변수로 준 뒤  scripts/load-test-remote.sh <BASE_URL> [MULT] [DURATION] [LABEL]
#   예) read -s ADMIN_TOKEN; export ADMIN_TOKEN; scripts/load-test-remote.sh http://3.107.29.43 1 2m laptop
# 노트북(macOS)과 AWS CloudShell(Linux) 양쪽에서 돈다. 결과는 build/k6/remote-<LABEL>-<시각>/
set -euo pipefail
BASE="${1:?사용법: scripts/load-test-remote.sh <BASE_URL> [MULT] [DURATION] [LABEL]}"
MULT="${2:-1}"
DURATION="${3:-2m}"
LABEL="${4:-$(hostname -s)}"
: "${ADMIN_TOKEN:?ADMIN_TOKEN 환경 변수가 없습니다(read -s ADMIN_TOKEN; export ADMIN_TOKEN)}"
K6="${K6:-$(command -v k6 || echo "$HOME/.local/bin/k6")}"
WATCHDOG="${WATCHDOG:-1200}"   # k6 가 이 초 안에 안 끝나면 강제 종료(좀비 방지)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 운영 서버는 실제 시계로 돈다. 장 시간(한국 평일 09:00~15:30)이 아니면 주문이 전부 거절되므로 먼저 막는다
NOW_KST=$(TZ=Asia/Seoul date +%H%M); DOW=$(TZ=Asia/Seoul date +%u)
if [ "$DOW" -ge 6 ] || [ "$NOW_KST" -lt 0905 ] || [ "$NOW_KST" -gt 1500 ]; then
  echo "지금은 한국 시각 $(TZ=Asia/Seoul date '+%a %H:%M') 입니다. 평일 09:05~15:00 에 돌리세요(측정 중 장이 닫히지 않게 여유를 둠)." >&2
  exit 1
fi
TODAY=$(TZ=Asia/Seoul date +%F)
if date -v+1d >/dev/null 2>&1; then ENDS=$(TZ=Asia/Seoul date -v+30d +%F); else ENDS=$(TZ=Asia/Seoul date -d '+30 days' +%F); fi

OUT="$ROOT/build/k6/remote-$LABEL-$(TZ=Asia/Seoul date +%m%d-%H%M)"
mkdir -p "$OUT"
echo "결과: $OUT"

# 1) 네트워크 왕복 시간: 응답 시간에서 빼고 읽기 위해 먼저 잰다(5번, 연결·첫 바이트)
for _ in 1 2 3 4 5; do
  curl -s -o /dev/null -w '%{time_connect} %{time_starttransfer}\n' "$BASE/actuator/health"
done > "$OUT/rtt.txt"
awk '{c+=$1; t+=$2} END {printf "왕복(연결) 평균 %.0f ms, health 첫 바이트 평균 %.0f ms\n", c/NR*1000, t/NR*1000}' "$OUT/rtt.txt" | tee "$OUT/rtt-summary.txt"

# 2) 서버 쪽 지표(ACTUATOR_EXPOSE 에 metrics 가 있을 때만). 전후를 남겨 차이를 본다
snap() {
  for m in http.server.requests hikaricp.connections.pending hikaricp.connections.timeout jvm.gc.pause process.cpu.usage; do
    printf '%s ' "$m"; curl -s -m 5 "$BASE/actuator/metrics/$m" || true; echo
  done > "$OUT/metrics-$1.txt"
}
snap before

# 3) k6 (워치독으로 시간 제한)
set +e
(
  "$K6" run -e BASE_URL="$BASE" -e ADMIN_TOKEN="$ADMIN_TOKEN" -e MULT="$MULT" -e DURATION="$DURATION" \
    -e STARTS_ON="$TODAY" -e ENDS_ON="$ENDS" -e SUMMARY="$OUT/summary.json" \
    "$ROOT/scripts/k6/league.js" > "$OUT/k6.log" 2>&1 &
  pid=$!
  ( sleep "$WATCHDOG"; kill -9 "$pid" 2>/dev/null ) & wd=$!
  wait "$pid"; code=$?
  kill "$wd" 2>/dev/null
  exit "$code"
)
K6_EXIT=$?
set -e

snap after
curl -s -m 5 "$BASE/actuator/health" > "$OUT/health-after.txt" || true
echo "k6 종료 코드 $K6_EXIT (99 = 통과 기준 미달, 그 밖의 0 아닌 값 = 실행 실패)" | tee -a "$OUT/rtt-summary.txt"
[ -f "$OUT/summary.md" ] && cat "$OUT/summary.md" || tail -40 "$OUT/k6.log"
