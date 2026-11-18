#!/bin/bash
# 6주차⑩ P7 가입 몰림: 같은 대회에 참가 요청이 동시에 몰리면 왜 커넥션 대기가 3초를 넘기나(30단계, 쉬는 서버에서 50건 동시 → 503).
# 서버 /opt/league 에서 실행한다. 노트북(k6)을 거치지 않아 부하 발생기 쪽 문제(파일 핸들, 왕복 지연)가 섞이지 않는다.
#   ./p7-join.sh [동시 수 목록="1 10 20 50"]
# 동시 수마다 새 대회를 만들고 두 단계를 잰다.
#   가: 사용자 N명을 동시에 만든다(대조군: 서로 다른 행만 쓴다. 함께 가리키는 행이 없다)
#   나: 그 N명이 같은 대회에 동시에 참가한다(모든 참가 행이 같은 contests 행을 외래 키로 가리킨다)
# 두 단계 모두 요청마다 상태 코드와 걸린 시간을 남기고, 나 단계 동안 DB 세션이 무엇을 기다리는지 0.1초마다 기록한다(p7-waits.sql).
# 덜어내기(동시 20건)가 켜져 있으면 20을 넘는 요청은 바로 503 이 된다. 그래서 20 이하에서 원인을 보고, 50 은 지금 운영 모습을 본다.
set -euo pipefail
cd "$(dirname "$0")"
LEVELS="${1:-1 10 20 50}"
T="$(grep '^ADMIN_TOKEN=' .env | cut -d= -f2-)"
B=localhost
OUT="p7-$(date -u +%H%M%S)"
mkdir -p "$OUT"

# 요청 시간 목록(초)을 받아 건수·중앙값·최대를 한 줄로
stats() {
  sort -n | awk '{a[NR] = $1} END {if (NR == 0) {print "0건"; exit}
    printf "%d건 중앙값 %.3fs 최대 %.3fs\n", NR, a[int((NR + 1) / 2)], a[NR]}'
}

# 요청 N건을 정말로 한꺼번에 보낸다. curl 하나가 연결 N개를 바로 연다(-Z --parallel-immediate).
# 처음엔 xargs -P 로 curl 을 N개 띄웠는데, 프로세스를 띄우는 사이 앞 요청(20~50ms)이 끝나 버려 동시에 4건도 안 겹쳤다.
# 또 여러 curl 이 같은 파일에 쓰다 줄이 섞여 id 몇 개를 잃었다. 지금은 응답을 요청마다 따로 받는다
# 설정 파일은 요청마다 "next" 로 끝나게 만든다. 마지막 next 뒤에 URL 이 없으면 curl 이 전체를 거부하므로 지우고 보낸다
fire() {
  sed -i '$d' "$2"
  curl -s -Z --parallel-immediate --parallel-max "$1" -K "$2"
}

# 상태 코드별 건수와 시간. 입력: "코드 시간" 줄
report() {
  local f="$1"
  awk '{print $1}' "$f" | sort | uniq -c | awk '{printf "  상태 %s: %d건\n", $2, $1}'
  echo "  시간: $(awk '{print $2}' "$f" | stats)"
  echo "  2xx 만: $(awk '$1 ~ /^2/ {print $2}' "$f" | stats)"
}

for N in $LEVELS; do
  echo "=== 동시 $N $(date -u +%H:%M:%S)"
  run="p7-$(date +%s)-$N"
  C="$(curl -s -X POST -H "X-Admin-Token: $T" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$run\",\"startsOn\":\"2026-11-16\",\"endsOn\":\"2026-12-16\",\"seed\":7}" \
    "$B/api/admin/contests" | sed -n 's/.*"data":{"id":\([0-9]*\).*/\1/p')"
  [ -n "$C" ] || { echo "대회를 못 만들었습니다" >&2; exit 1; }
  curl -s -o /dev/null -X POST -H "X-Admin-Token: $T" "$B/api/admin/contests/$C/start"
  sleep 2

  # 가: 사용자 N명 동시 생성
  : > "$OUT/users-$N.cfg"
  for i in $(seq 1 "$N"); do
    printf 'url = "http://%s/api/users"\nrequest = "POST"\nheader = "Content-Type: application/json"\ndata = "{\\"nickname\\":\\"%s-%s\\"}"\noutput = "%s/u-%s-%s.json"\nwrite-out = "%%{http_code} %%{time_total}\\n"\nnext\n' \
      "$B" "$run" "$i" "$OUT" "$N" "$i" >> "$OUT/users-$N.cfg"
  done
  fire "$N" "$OUT/users-$N.cfg" > "$OUT/users-$N.txt"
  cat "$OUT"/u-"$N"-*.json | grep -o '"data":{"id":[0-9]*' | grep -o '[0-9]*$' > "$OUT/ids-$N.txt"
  echo "가) 사용자 만들기 (대회 $C, id $(wc -l < "$OUT/ids-$N.txt")개 받음)"
  report "$OUT/users-$N.txt"
  sleep 2

  # 나: 같은 대회에 동시 참가. 대기 기록(psql 컨테이너 띄우기에 1~2초)을 먼저 켠다
  ./psql.sh -tA -F ' ' -v cnt=80 -f /sql/p7-waits.sql </dev/null > "$OUT/waits-$N.txt" 2>&1 &
  w=$!
  sleep 3
  since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  start="$(date -u +%H:%M:%S.%3N)"
  : > "$OUT/join-$N.cfg"
  while read -r id; do
    printf 'url = "http://%s/api/contests/%s/participants"\nrequest = "POST"\nheader = "X-User-Id: %s"\noutput = "/dev/null"\nwrite-out = "%%{http_code} %%{time_total}\\n"\nnext\n' \
      "$B" "$C" "$id" >> "$OUT/join-$N.cfg"
  done < "$OUT/ids-$N.txt"
  fire "$N" "$OUT/join-$N.cfg" > "$OUT/join-$N.txt"
  echo "나) 같은 대회 동시 참가 (시작 $start UTC)"
  report "$OUT/join-$N.txt"
  echo "  커넥션 못 얻음(로그): $(docker logs league-app --since "$since" 2>&1 | grep -c 'DB 커넥션 부족' || true)건"
  wait "$w" || true
  echo "  DB 대기(0.1초마다, 세션이 하나라도 있었던 줄만, 한국 시각): 시각 | 쿼리 중 | 트랜잭션 쉼 | 행 잠금 | 내부 잠금 | 대기 종류"
  awk '$2 + $3 > 0' "$OUT/waits-$N.txt" | head -40
  sleep 3
done
echo "끝 $(date -u +%H:%M:%S). 원본: $PWD/$OUT/  (지우기: ./p7-join.sh 가 만든 대회는 이름이 p7- 로 시작)"
