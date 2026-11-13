#!/bin/bash
# 지표 그래프 띄우기. 사용: monitoring/up.sh local | <EC2 IP 또는 호스트[:포트]>
#   local → load-test.sh 가 띄우는 로컬 앱(host.docker.internal:18081)
# 대상을 바꿔 다시 실행해도 된다(Prometheus 설정만 바꿔 다시 읽는다). 지난 데이터는 그대로 남는다
set -euo pipefail
cd "$(dirname "$0")"
TARGET="${1:?사용법: monitoring/up.sh local | <EC2 IP>}"
[ "$TARGET" = local ] && TARGET="host.docker.internal:18081"
case "$TARGET" in *:*) ;; *) TARGET="$TARGET:80" ;; esac
mkdir -p .generated
cat > .generated/prometheus.yml <<YML
global:
  scrape_interval: 5s      # 2분짜리 측정도 그래프가 촘촘하게 나오도록 5초마다 가져온다
  scrape_timeout: 4s
scrape_configs:
  - job_name: league
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["$TARGET"]
YML
WAS_RUNNING="$(docker inspect -f '{{.State.Running}}' league-prometheus 2>/dev/null || echo false)"
docker compose up -d
# 이미 떠 있었다면 바뀐 설정(대상)을 읽도록 다시 시작한다. 막 뜨는 중에 HUP 신호를 보내면 프로세스가 죽는다(실제로 겪음)
[ "$WAS_RUNNING" = true ] && docker restart league-prometheus > /dev/null
echo "수집 대상: $TARGET"
echo "그래프: http://localhost:3000   (Prometheus 원본: http://localhost:9090/targets 에서 대상이 UP 인지 확인)"
