#!/bin/bash
# 7주차④ 32단계의 "연결 맺기 최대 29.7초, 버린 곳이 앱 앞(연결 추적 표가 가득 참)"을 패킷으로 확인한다.
# 32단계의 근거는 간접 증거였다(k6 연결 시간이 TCP 재시도 간격과 맞음, dmesg "table full", 앱 쪽 커널 카운터 0).
# 여기서는 연결마다 SYN 이 ① 서버 NIC 에 도착했는지 ② Docker 브리지(앱 컨테이너 쪽)로 넘어갔는지 ③ SYN-ACK 이 나갔는지를 본다.
# NIC 에서 잡는 패킷은 커널 방화벽(netfilter)보다 앞에서 잡히므로, 표가 차서 버린 SYN 도 ①에는 보이고 ②에는 없다.
#
# 사용(서버 /opt/league):  sudo ./syn-capture.sh <노트북 공인 IP> [표 크기=지금 값] [초=90]
#   예) sudo ./syn-capture.sh 203.0.113.7 7680     ← 32단계 이전 값으로 재현
#       sudo ./syn-capture.sh 203.0.113.7          ← 지금 값(65,536) 그대로 비교
# 노트북에서 같은 때에 scripts/k6/conn-churn.js 를 돌린다. 표 크기는 끝나면(중단·SSH 끊김 포함) 원래 값으로 되돌린다.
# 패킷은 헤더 96바이트만, 노트북 IP 와 오간 SYN·SYN-ACK 만 저장한다(다른 사용자의 패킷·본문은 담지 않는다).
set -uo pipefail
cd "$(dirname "$0")"
CLIENT="${1:?사용법: sudo ./syn-capture.sh <노트북 공인 IP> [표 크기] [초=90]}"
MAX_ARG="${2:-}"
DUR="${3:-90}"
[ "$(id -u)" = 0 ] || { echo "sudo 로 실행하세요(tcpdump·sysctl)" >&2; exit 1; }
command -v tcpdump > /dev/null || { echo "tcpdump 가 없습니다: sudo dnf install -y tcpdump" >&2; exit 1; }

TS="$(date -u +%H%M%S)"
OUT="syn-$TS${MAX_ARG:+-max$MAX_ARG}"
mkdir -p "$OUT"
NIC="$(ip route show default | awk '{print $5; exit}')"
APP_IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' league-app)"
BRIDGE="$(ip route get "$APP_IP" | awk '{for (i = 1; i < NF; i++) if ($i == "dev") {print $(i + 1); exit}}')"
ORIG_MAX="$(sysctl -n net.netfilter.nf_conntrack_max)"
echo "NIC=$NIC 브리지=$BRIDGE 앱=$APP_IP:8080 표 크기 지금=$ORIG_MAX TIME_WAIT=$(sysctl -n net.netfilter.nf_conntrack_tcp_timeout_time_wait)초"

pids=()
restore() {
  for p in "${pids[@]}"; do kill "$p" 2> /dev/null; done
  wait 2> /dev/null
  if [ "$(sysctl -n net.netfilter.nf_conntrack_max)" != "$ORIG_MAX" ]; then
    sysctl -qw net.netfilter.nf_conntrack_max="$ORIG_MAX" && echo "표 크기를 $ORIG_MAX 로 되돌렸다"
  fi
}
trap restore EXIT
trap 'exit 130' INT TERM HUP   # SSH 가 끊겨도 EXIT 로 가서 되돌린다

FULL_BEFORE="$(dmesg | grep -c 'nf_conntrack: table full')"
APP_PID="$(docker inspect -f '{{.State.Pid}}' league-app)"
# 커널 카운터 앞뒤 값: 연결 추적(CPU 별 합, 16진수)과 앱 컨테이너 안의 접속 대기열 넘침
ct_stats() { awk 'NR == 1 {for (i = 1; i <= NF; i++) n[i] = $i; next} {for (i = 1; i <= NF; i++) s[n[i]] += strtonum("0x" $i)}
  END {printf "drop=%d early_drop=%d insert_failed=%d invalid=%d", s["drop"], s["early_drop"], s["insert_failed"], s["invalid"]}' /proc/net/stat/nf_conntrack; }
app_stats() { nsenter -t "$APP_PID" -n nstat -az TcpExtListenOverflows TcpExtListenDrops TcpExtSyncookiesSent | awk 'NR > 1 {printf "%s=%s ", $1, $2}'; }
CT_BEFORE="$(ct_stats)"; APP_BEFORE="$(app_stats)"
# SYN 과 SYN-ACK 만(SYN 비트가 선 패킷)
tcpdump -i "$NIC" -nn -s 96 -U -w "$OUT/nic.pcap" "host $CLIENT and tcp port 80 and tcp[tcpflags] & tcp-syn != 0" 2> "$OUT/nic.err" &
pids+=($!)
tcpdump -i "$BRIDGE" -nn -s 96 -U -w "$OUT/bridge.pcap" "host $CLIENT and tcp port 8080 and tcp[tcpflags] & tcp-syn != 0" 2> "$OUT/bridge.err" &
pids+=($!)
# 컨테이너 안(eth0): 앱이 SYN 을 받았는지, SYN-ACK 을 보냈는지. 브리지 장치에서는 앱의 SYN-ACK 이 브리지 방화벽(br_netfilter)을
# 지난 뒤에만 보인다. 표가 차서 그 연결의 칸이 지워지면(early_drop) 앱의 답이 그 앞에서 버려져, 브리지만 보면 "앱이 답 안 함"처럼 보인다
nsenter -t "$APP_PID" -n tcpdump -i eth0 -nn -s 96 -U -w "$OUT/app.pcap" "host $CLIENT and tcp port 8080 and tcp[tcpflags] & tcp-syn != 0" 2> "$OUT/app.err" &
pids+=($!)
sleep 2
[ -n "$MAX_ARG" ] && sysctl -qw net.netfilter.nf_conntrack_max="$MAX_ARG" && echo "표 크기를 $MAX_ARG 로 낮췄다(재현용)"

echo "지금 노트북에서 conn-churn.js 를 시작하세요. ${DUR}초 동안 잡습니다."
# 1초마다 표에 든 칸 수와 커널이 버린 수(/proc/net/stat/nf_conntrack 의 drop 열, CPU 별 합)를 적는다
end=$(( $(date +%s) + DUR ))
while [ "$(date +%s)" -lt "$end" ]; do
  printf '%s %s %s\n' "$(date -u +%H:%M:%S)" "$(cat /proc/sys/net/netfilter/nf_conntrack_count)" \
    "$(awk 'NR == 1 {for (i = 1; i <= NF; i++) if ($i == "drop") c = i; next} {s += strtonum("0x" $c)} END {print s}' /proc/net/stat/nf_conntrack)" >> "$OUT/conntrack.txt"
  sleep 1
done
restore
trap - EXIT
FULL_AFTER="$(dmesg | grep -c 'nf_conntrack: table full')"
{ echo "연결 추적 전: $CT_BEFORE"; echo "연결 추적 후: $(ct_stats)"; echo "앱 접속 대기열 전: $APP_BEFORE"; echo "앱 접속 대기열 후: $(app_stats)"; } > "$OUT/counters.txt"

tcpdump -r "$OUT/nic.pcap" -nn -tt 2> /dev/null > "$OUT/nic.txt"
tcpdump -r "$OUT/bridge.pcap" -nn -tt 2> /dev/null > "$OUT/bridge.txt"
tcpdump -r "$OUT/app.pcap" -nn -tt 2> /dev/null > "$OUT/app.txt"
python3 syn-analyze.py "$OUT" "$CLIENT" "$(( FULL_AFTER - FULL_BEFORE ))" | tee "$OUT/summary.txt"   # 분석만 다시: python3 syn-analyze.py <폴더> <IP>
awk 'NR == 1 {d0 = $3} {if ($2 > m) m = $2; d = $3} END {print "표에 든 칸 최대: " m ", 커널 drop 증가: " d - d0}' "$OUT/conntrack.txt" | tee -a "$OUT/summary.txt"
cat "$OUT/counters.txt" | tee -a "$OUT/summary.txt"
echo "결과: $(pwd)/$OUT"
