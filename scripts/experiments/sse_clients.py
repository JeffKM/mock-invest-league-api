#!/usr/bin/env python3
"""7주차⑤ SSE 방송 실험의 클라이언트. 서버와 다른 프로세스로 돌려 서버 힙 측정에 섞이지 않게 한다.

사용: python3 -I sse_clients.py <포트> <정상 연결 수> <느린 연결 수> <결과 JSON 경로>
  정상 연결: 계속 읽으며, 방송마다 "서버가 방송을 시작한 시각 → 내가 받은 시각" 지연을 잰다.
  느린 연결: 수신 버퍼를 4KB 로 작게 잡고 아무것도 읽지 않는다(신호가 약한 휴대폰, 멈춘 앱).
표준 입력이 닫히면(서버 쪽 테스트가 끝나면) 결과를 쓰고 끝난다.
"""
import asyncio
import json
import resource
import socket
import sys
import time

port, normal, slow, out = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
resource.setrlimit(resource.RLIMIT_NOFILE, (min(hard, 65536), hard))

# HTTP/1.0: 응답이 청크로 쪼개지지 않게(1.1 에서는 Spring 이 "data:"·본문·줄바꿈을 따로 써 줄이 청크 크기 줄과 섞였다)
REQUEST = f'GET /sse HTTP/1.0\r\nHost: localhost:{port}\r\nAccept: text/event-stream\r\n\r\n'.encode()
latency = {}        # 방송 번호 → [받은 지연(ms)...]
connected = [0, 0]  # 정상, 느림
failed = [0]
holders = []        # 느린 연결 소켓(가비지 컬렉션으로 닫히지 않게 쥐고 있는다)


async def normal_client():
    try:
        reader, writer = await asyncio.open_connection('127.0.0.1', port, limit=1 << 20)
        writer.write(REQUEST)
        await writer.drain()
        connected[0] += 1
        while True:
            line = await reader.readline()
            if not line:
                return
            if line.startswith(b'data:'):
                now = time.time() * 1000
                msg = json.loads(line[5:])
                latency.setdefault(msg['id'], []).append(now - msg['sentAt'])
    except OSError:
        failed[0] += 1


def slow_client():
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)   # 연결 전에 잡아야 창 크기에 반영된다
    s.connect(('127.0.0.1', port))
    s.sendall(REQUEST)
    holders.append(s)
    connected[1] += 1


async def main():
    tasks = []
    for i in range(normal):
        tasks.append(asyncio.create_task(normal_client()))
        if i % 200 == 199:
            await asyncio.sleep(0.05)   # 한꺼번에 열면 서버 접속 대기열이 넘친다(32단계)
    for _ in range(slow):
        slow_client()
    print(f'열림: 정상 {connected[0]}, 느림 {connected[1]}, 실패 {failed[0]}', flush=True)
    await asyncio.get_running_loop().run_in_executor(None, sys.stdin.read)   # 서버가 끝내라고 할 때까지
    with open(out, 'w') as f:
        json.dump({'connected': connected, 'failed': failed[0],
                   'latency': {k: v for k, v in latency.items()}}, f)
    for t in tasks:
        t.cancel()


asyncio.run(main())
