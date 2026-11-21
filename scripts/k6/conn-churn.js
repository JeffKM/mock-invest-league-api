// 7주차④ 새 연결 몰림. 요청마다 새 TCP 연결을 맺어(noConnectionReuse) 서버의 연결 추적 표를 채운다.
// 32단계에서는 덜어내기 503 이 연결을 닫아 재연결이 몰렸다. 여기서는 그 "새 연결 수"만 떼어 낸다(앱·DB 는 거의 일하지 않는 /actuator/info).
// 서버에서 deploy/syn-capture.sh 를 먼저 띄운 뒤:  k6 run -e BASE_URL=http://<서버 IP> [-e RATE=400] [-e DURATION=60s] scripts/k6/conn-churn.js
// 필요한 칸 ≈ 초당 연결 × TIME_WAIT(서버 30초). 400 × 30 = 12,000 > 7,680(32단계 이전 표 크기)
import http from 'k6/http';
import { Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 400);
const DURATION = __ENV.DURATION || '60s';

const slowConnect = new Counter('connect_over_900ms');   // SYN 을 한 번 이상 다시 보내야 했던 연결(첫 재시도가 1초 뒤)
const connectMs = new Trend('connect_ms', true);

export const options = {
  noConnectionReuse: true,
  scenarios: {
    churn: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 200, maxVUs: 3000,
    },
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE}/actuator/info`, { timeout: '40s' });
  connectMs.add(res.timings.connecting);
  if (res.timings.connecting > 900) {
    slowConnect.add(1);
  }
}
