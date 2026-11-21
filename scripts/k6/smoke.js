// 7주차③ CI 성능 스모크. scripts/perf-smoke.sh 가 두 번 부른다.
//   PHASE=prepare  대회 하나·사용자 USERS 명을 만들고 결과(대회 id, 사용자 id)를 PREPARED 파일로 남긴다
//   PHASE=load     그 대회에 정해진 수의 요청을 보내고(도착 속도 고정 → 요청 수가 매번 같다), 끝에 불변식을 검사한다
// 두 단계로 나눈 이유: 준비(가입·참가)의 DB 문장은 빼고 "부하 구간의 요청당 DB 문장 수"만 재기 위해서다. 러너가 사이에서 통계를 비운다.
// 지연 기준은 느슨하게 둔다. GitHub 러너는 다른 작업과 CPU 를 나눠 써서 같은 코드도 지연이 흔들린다. 엄격한 기준은 러너가 DB 문장 수로 건다.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { JSON_HEADERS, TICKERS, prepare, summaryText, auditInvariants } from './lib.js';

const PHASE = __ENV.PHASE || 'load';
const BASE = __ENV.BASE_URL || 'http://localhost:18082';
const ADMIN = __ENV.ADMIN_TOKEN || '';
const USERS = Number(__ENV.USERS || 100);
const PREPARED = __ENV.PREPARED || 'build/k6/smoke/prepared.json';
const SUMMARY = __ENV.SUMMARY || 'build/k6/smoke/summary.json';
const ORDER_TPS = 10;   // 사용자 100명이면 1인당 초당 0.1건. 주문 속도 제한(초당 5건)에 닿지 않는다
const READ_TPS = 30;
const DURATION = '40s';

// 409(현금 부족 등)·429(속도 제한)는 업무상 거절이라 서버 실패로 세지 않는다(realistic.js 와 같은 기준)
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 429));

const loaded = PHASE === 'load' ? JSON.parse(open(PREPARED)) : null;

const NOOP = new Set(['max>=0', 'rate>=0', 'count>=0']);
export const options = PHASE === 'prepare'
  ? { setupTimeout: '5m', vus: 1, iterations: 1 }
  : {
    scenarios: {
      // 막 뜬 JVM 의 첫 요청(JIT·커넥션·캐시)이 지연 기준을 흔들지 않게 따로 뺀다. DB 문장 수에는 들어간다
      warmup: {
        executor: 'constant-arrival-rate', exec: 'mixed', rate: 20, timeUnit: '1s', duration: '10s',
        preAllocatedVUs: 20, maxVUs: 100,
      },
      orders: {
        executor: 'constant-arrival-rate', exec: 'order', startTime: '10s', rate: ORDER_TPS, timeUnit: '1s',
        duration: DURATION, preAllocatedVUs: 20, maxVUs: 100,
      },
      reads: {
        executor: 'constant-arrival-rate', exec: 'read', startTime: '10s', rate: READ_TPS, timeUnit: '1s',
        duration: DURATION, preAllocatedVUs: 20, maxVUs: 200,
      },
    },
    thresholds: {
      'http_req_failed{kind:order}': ['rate<0.01'],
      'http_req_failed{kind:read}': ['rate<0.01'],
      'http_req_duration{kind:order}': ['p(95)<1000'],
      'http_req_duration{kind:read}': ['p(95)<500'],
      invariant_violations: ['count==0'],
      'http_reqs{kind:order}': ['count>=0'],
      'http_reqs{kind:read}': ['count>=0'],
    },
    summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
  };

export function setup() {
  if (PHASE === 'prepare') {
    return prepare({ base: BASE, admin: ADMIN, users: USERS, startsOn: '2026-12-01', endsOn: '2026-12-31' });
  }
  return loaded;
}

export default function () {}   // prepare 단계: setup 만 쓴다

// 요청 종류·사용자·종목을 난수 대신 시나리오 안의 반복 번호로 고른다. 매번 같은 섞음이 되어야 DB 문장 수를 좁은 허용폭으로 비교할 수 있다
// (처음엔 Math.random 이었다. 같은 코드에서 요청당 문장 수가 ±3% 흔들려 허용폭을 20% 로 둘 수밖에 없었고, 캐시가 빠진 회귀(+17%)를 놓쳤다)
const nth = () => exec.scenario.iterationInTest;
const at = (arr, i) => arr[i % arr.length];

export function order(data, kind = 'order') {
  const i = nth();
  const limit = i % 5 === 0;   // 지정가 20%
  const body = limit
    ? { ticker: at(TICKERS, i * 7), side: 'BUY', quantity: 1, type: 'LIMIT', limitPrice: 1000 }
    : { ticker: at(TICKERS, i * 7), side: 'BUY', quantity: 1 + (i % 3) };
  const res = http.post(`${BASE}/api/contests/${data.contestId}/orders`, JSON.stringify(body), {
    headers: { ...JSON_HEADERS, 'X-User-Id': String(at(data.users, i * 13)), 'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}` },
    tags: { kind, name: limit ? 'POST orders(limit)' : 'POST orders(market)' },
  });
  check(res, { '주문 200': (r) => r.status === 200 });
}

export function read(data, kind = 'read') {
  const c = data.contestId;
  const i = nth();
  const p = i % 10;   // 내 계좌 40%, 내 주문 30%, 순위표 20%, 공시 10%
  const [url, name] = p < 4 ? [`/api/contests/${c}/me`, 'GET me']
    : p < 7 ? [`/api/contests/${c}/orders`, 'GET orders']
      : p < 9 ? [`/api/contests/${c}/rankings?limit=20`, 'GET rankings']
        : [`/api/contests/${c}/disclosures?limit=20`, 'GET disclosures'];
  const res = http.get(BASE + url, { headers: { 'X-User-Id': String(at(data.users, i * 13)) }, tags: { kind, name } });
  check(res, { '조회 200': (r) => r.status === 200 });
}

export function mixed(data) {
  if (nth() % 4 === 0) {
    order(data, 'warmup');
  } else {
    read(data, 'warmup');
  }
}

export function teardown(data) {
  if (PHASE === 'load') {
    auditInvariants({ base: BASE, admin: ADMIN, contestId: data.contestId });
  }
}

export function handleSummary(data) {
  if (PHASE === 'prepare') {
    return { [PREPARED]: JSON.stringify(data.setup_data) };
  }
  const text = summaryText(data, [`CI 스모크: 주문 ${ORDER_TPS} TPS + 조회 ${READ_TPS} TPS, ${DURATION}, 사용자 ${USERS}명`], NOOP);
  return { stdout: text, [SUMMARY]: JSON.stringify(data, null, 2), [SUMMARY.replace(/\.json$/, '.md')]: text };
}
