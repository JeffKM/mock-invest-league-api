#!/usr/bin/env python3
"""7주차④ syn-capture.sh 가 잡은 패킷을 연결·SYN 단위로 센다. 이미 잡은 결과를 다시 볼 때도 쓴다.

사용(서버 /opt/league):  python3 syn-analyze.py <결과 폴더> <노트북 공인 IP> [dmesg table full 줄 수]

연결은 (노트북 포트, SYN 순번)으로 구분한다. 맥의 임시 포트(약 1만 6천 개)는 실행 중에 다시 쓰이지만,
재전송한 SYN 은 순번이 같고 새 연결은 다르다.
SYN-ACK 을 못 받은 연결을 어디서 끊겼는지로 나눈다. 잡는 곳은 NIC → 브리지 장치 → 컨테이너 안(eth0) 세 군데.
  ① SYN 이 브리지로 한 번도 안 넘어감               → 들어오는 길(NIC 와 브리지 사이, 연결 추적)에서 버림
  ② 브리지로 넘어갔는데 컨테이너 안에 안 닿음
  ③ 컨테이너가 받았는데 SYN-ACK 을 안 보냄           → 앱 쪽 커널(접속 대기열)
  ④ 컨테이너가 SYN-ACK 을 보냈는데 브리지 장치에서 안 보임 → 돌아오는 길의 브리지 방화벽(br_netfilter)에서 버림
  ⑤ 브리지 장치에서 보였는데 NIC 로 안 나감
처음 잡은 결과(app.txt 없음)는 ③·④를 가릴 수 없어 "브리지에서 SYN-ACK 이 안 보임"으로 합쳐 센다.
"""
import collections
import os
import re
import sys

out, client = sys.argv[1], sys.argv[2]
full = sys.argv[3] if len(sys.argv) > 3 else None
line = re.compile(r'^(\d+\.\d+) IP (\S+)\.(\d+) > (\S+)\.(\d+): Flags \[(\S+)\], seq (\d+)(?:, ack (\d+))?')


def read(path):
    for raw in open(path):
        m = line.match(raw)
        if m:
            yield float(m[1]), m[2], m[3], m[4], m[5], m[6], m[7], m[8]


syn = collections.defaultdict(list)   # 연결 → NIC 에 온 SYN 시각들
nic_ack = collections.Counter()       # 연결 → NIC 로 나간 SYN-ACK 수
fwd = collections.Counter()           # 연결 → 브리지로 넘어간 SYN 수
br_ack = collections.Counter()        # 연결 → 브리지 장치에서 보인 앱의 SYN-ACK 수
in_app = collections.Counter()        # 연결 → 컨테이너 안에 닿은 SYN 수
app_ack = collections.Counter()       # 연결 → 컨테이너가 보낸 SYN-ACK 수
for t, src, sport, dst, dport, flags, seq, ack in read(f'{out}/nic.txt'):
    if src == client and flags == 'S':
        syn[(sport, seq)].append(t)
    elif dst == client and flags == 'S.' and ack:
        nic_ack[(dport, str(int(ack) - 1))] += 1
for t, src, sport, dst, dport, flags, seq, ack in read(f'{out}/bridge.txt'):
    if src == client and flags == 'S':
        fwd[(sport, seq)] += 1
    elif dst == client and flags == 'S.' and ack:
        br_ack[(dport, str(int(ack) - 1))] += 1
has_app = os.path.exists(f'{out}/app.txt')
if has_app:
    for t, src, sport, dst, dport, flags, seq, ack in read(f'{out}/app.txt'):
        if src == client and flags == 'S':
            in_app[(sport, seq)] += 1
        elif dst == client and flags == 'S.' and ack:
            app_ack[(dport, str(int(ack) - 1))] += 1

conns = len(syn)
syns = sum(len(v) for v in syn.values())
retried = {c: ts for c, ts in syn.items() if len(ts) > 1}
gaps = collections.Counter()
for ts in retried.values():
    for a, b in zip(ts, ts[1:]):
        gaps[round(b - a, 1)] += 1   # 0.1초 단위. 정수 초로 반올림하자 맥의 0.42초 간격이 "0초"로 보였다
no_ack = [c for c in syn if nic_ack[c] == 0]
cut_in = sum(1 for c in no_ack if fwd[c] == 0)
cut_out = sum(1 for c in no_ack if br_ack[c] > 0)
if has_app:
    cut_br = sum(1 for c in no_ack if fwd[c] > 0 and in_app[c] == 0)
    cut_app = sum(1 for c in no_ack if in_app[c] > 0 and app_ack[c] == 0)
    cut_back = sum(1 for c in no_ack if app_ack[c] > 0 and br_ack[c] == 0)
else:
    cut_mid = sum(1 for c in no_ack if fwd[c] > 0 and br_ack[c] == 0)
waited = sorted(ts[-1] - ts[0] for c, ts in syn.items() if nic_ack[c] > 0)

print(f'연결 시도: {conns}, NIC 에 온 SYN 총 {syns}개')
print(f'SYN 을 다시 보낸 연결: {len(retried)} ({100 * len(retried) / max(conns, 1):.1f}%)')
print(f'재전송 간격(초) 분포: {dict(sorted(gaps.items()))}   ← TCP 재시도는 1·2·4·8초…')
if has_app:
    print(f'SYN 단위: NIC 에 옴 {syns} → 브리지로 {sum(fwd.values())} → 컨테이너 안에 {sum(in_app.values())} '
          f'→ 컨테이너가 보낸 SYN-ACK {sum(app_ack.values())} → 브리지에서 보임 {sum(br_ack.values())} → NIC 로 나감 {sum(nic_ack.values())}')
    print(f'SYN-ACK 을 한 번도 못 받은 연결: {len(no_ack)} = ① 들어오는 길 {cut_in} + ② 브리지→컨테이너 {cut_br} '
          f'+ ③ 앱 쪽 커널이 답 안 함 {cut_app} + ④ 돌아오는 길의 브리지 방화벽 {cut_back} + ⑤ 브리지→NIC {cut_out}')
else:
    print(f'SYN 단위: NIC 에 옴 {syns} → 브리지로 {sum(fwd.values())} '
          f'→ 브리지에서 보인 앱의 SYN-ACK {sum(br_ack.values())} → NIC 로 나감 {sum(nic_ack.values())}')
    print(f'SYN-ACK 을 한 번도 못 받은 연결: {len(no_ack)} = ① 들어오는 길 {cut_in} '
          f'+ ②~④ 브리지에서 SYN-ACK 이 안 보임(앱이 안 보냈는지, 돌아오다 버려졌는지 못 가림) {cut_mid} + ⑤ 브리지→NIC {cut_out}')
if waited:
    print(f'받아 준 연결의 첫 SYN → 마지막 SYN: 최대 {waited[-1]:.1f}초, '
          f'99% {waited[min(len(waited) - 1, int(len(waited) * 0.99))]:.1f}초')
if full is not None:
    print(f'dmesg "table full" 새로 찍힌 줄: {full}')
# tcpdump 가 따라가지 못해 버린 패킷이 있으면 위 숫자가 틀린다
for name in ('nic', 'bridge', 'app'):
    try:
        dropped = [x.strip() for x in open(f'{out}/{name}.err') if 'dropped by' in x]
        print(f'tcpdump({name}): {"; ".join(dropped) or "기록 없음"}')
    except FileNotFoundError:
        pass
