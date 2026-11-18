#!/bin/bash
# 6주차⑩ P7 에서 나온 의심: 과부하 뒤 앱 메모리가 스왑으로 밀려나 Full GC 가 17~27초 멈췄다(CPU 는 거의 놀면서).
# 부하를 거는 동안 서버 /opt/league 에서 같이 돌려, 앱 프로세스와 컨테이너의 메모리·스왑을 5초마다 남긴다.
#   ./mem-watch.sh [초=600]      → 화면과 mem-<시각>.txt 에 같이 쓴다
# 열: 시각(UTC) | 앱 RSS(MB) | 앱 스왑(MB) | 스레드 | 컨테이너 메모리(MB) | 컨테이너 스왑(MB) | 서버 전체 스왑 사용(MB) | 서버 여유 메모리(MB)
set -uo pipefail
cd "$(dirname "$0")"
DUR="${1:-600}"
LOG="mem-$(date -u +%H%M%S).txt"
P="$(docker inspect -f '{{.State.Pid}}' league-app)"
CG="/sys/fs/cgroup/system.slice/docker-$(docker inspect -f '{{.Id}}' league-app).scope"
mb() { awk -v k="$1" '$1 == k":" {printf "%d", $2 / 1024}' "/proc/$P/status"; }
cg() { [ -r "$CG/$1" ] && awk '{printf "%d", $1 / 1048576}' "$CG/$1" || printf -; }
echo "시각 RSS 스왑 스레드 컨테이너 컨테이너스왑 서버스왑 서버여유 (MB)" | tee "$LOG"
end=$(( $(date +%s) + DUR ))
while [ "$(date +%s)" -lt "$end" ]; do
  [ -r "/proc/$P/status" ] || { echo "앱 프로세스가 바뀌었습니다(재시작?)" | tee -a "$LOG"; break; }
  printf '%s %s %s %s %s %s %s %s\n' "$(date -u +%H:%M:%S)" "$(mb VmRSS)" "$(mb VmSwap)" \
    "$(awk '$1 == "Threads:" {print $2}' "/proc/$P/status")" "$(cg memory.current)" "$(cg memory.swap.current)" \
    "$(free -m | awk '$1 == "Swap:" {print $3}')" "$(free -m | awk '$1 == "Mem:" {print $7}')" | tee -a "$LOG"
  sleep 5
done
