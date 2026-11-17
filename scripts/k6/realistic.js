// 6주차③ 실제 사람의 사용 패턴을 흉내 낸 k6 시나리오. 하나씩 고른다: -e SCENARIO=steady|rush|tick|hot|journey|settle
//   steady  기준선: 평소 속도(주문 1 : 조회 3)를 일정하게. 나머지 시나리오를 이것과 비교한다
//   rush    장 시작 몰림: 평소 → 4배로 30초 → 평소. 몰림이 끝난 뒤 바로 회복하는지
//   tick    틱 경계 몰림: 10초 폭주 + 50초 한산을 3번. 5분 주기를 60초로 줄였다
//           서버를 5배속 시계(LEAGUE_CLOCK_SPEED=5)로 띄우면 틱이 실제 매분 30초에 바뀐다. 그래서 폭주를 매분 30초에
//           맞춰 시작하고, 폭주 1초 뒤 순위 스냅샷을 찍는다(스케줄러 대신) → 가격·순위 캐시 키가 폭주 중에 바뀐다(6주차⑤)
//           TICK_ALIGN=0 이면 기다리지 않고 바로 시작한다(배포 직후 첫 몰림을 재는 콜드 스타트 측정용, 6주차⑥)
//   hot     같은 계좌 연타·재전송: 계좌 몇 개에 주문이 몰린다. 동시 두 번 누르기와 늦은 재전송이 같은 주문을 돌려주는지
//   journey 사용 흐름: 계좌 → 공시 → 매수 → 주문 확인 → (지정가 걸고 취소 | 매도) → 순위, 사이사이 생각하는 시간
//   settle  정산 폭주(P4)·취소 경쟁(P5, 6주차⑧): 준비 때 "지금 가격이면 체결될" 매수 지정가를 사용자마다 LIMITS 개 걸어 둔다.
//           평소 부하(steady 와 같은 섞음) 중 40초에 관리자 정산 API 를 한 번 불러 수천 건을 한꺼번에 체결시킨다.
//           그동안 같은 사용자들이 시장가 주문(같은 계좌 잠금을 두고 배치와 경합)과 대기 주문 취소(배치와 같은 주문을 두고 경합)를 한다
// league.js(일정한 속도, 매수·조회만)는 비교 기준으로 그대로 둔다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { JSON_HEADERS, TICKERS, pick, must, prepare, errorCode, summaryText, auditInvariants } from './lib.js';

const SCENARIO = __ENV.SCENARIO || 'journey';
const BASE = __ENV.BASE_URL || 'http://localhost:18081';
const ADMIN = __ENV.ADMIN_TOKEN || '';
const MULT = Number(__ENV.MULT || 1);
const USERS = Number(__ENV.USERS || 1000);
const STARTS_ON = __ENV.STARTS_ON || '2026-12-01';
const ENDS_ON = __ENV.ENDS_ON || '2026-12-31';
const SUMMARY = __ENV.SUMMARY || `build/k6/${SCENARIO}.json`;
const SCALE = Number(__ENV.SCALE || 1);     // 단계 길이 배율. 로컬에서 시나리오가 도는지만 볼 때 0.2 등
const RTT_MS = Number(__ENV.RTT_MS || 0);   // 부하 발생기 ↔ 서버 왕복. 지연 기준에 더한다(러너가 재서 넘긴다)
const HOT_USERS = Number(__ENV.HOT_USERS || 5);
const LIMITS = Number(__ENV.LIMITS || 3);         // settle: 사용자마다 걸어 둘 지정가 수
const SETTLE_AT = 40;                              // settle: 정산을 부르는 시각(초, SCALE 적용 전)

// docs/01 5절 가정(주문 30 TPS, 조회 90 TPS)의 MULT 배가 "평소"다
const ORDER_TPS = 30 * MULT;
const READ_TPS = 90 * MULT;
const NORMAL = ORDER_TPS + READ_TPS;
const sec = (n) => `${Math.max(1, Math.round(n * SCALE))}s`;
const ms = (n) => n + RTT_MS;

// 409(현금·수량 부족, 취소 불가 등)는 업무상 거절이라 "서버 실패"로 세지 않는다. 503 은 실패로 센다(사용자는 못 쓴 것)
// 429(사용자별 주문 속도 제한, 6주차③)도 의도한 거절이다. 사람 속도를 넘는 주문을 커넥션을 잡기 전에 돌려보낸 것
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 429));
const serverErrors = new Counter('server_errors_not_503');   // 500 등. 0 이어야 한다
const shed = new Counter('shed_503');                          // 과부하로 덜어낸 요청
const rejects = new Counter('business_rejects');               // 409·429 등, error.code 태그
const idemMismatch = new Counter('idempotency_mismatch');      // 같은 키인데 다른 주문 id. 0 이어야 한다

const SCENARIOS = {
  steady: {
    executor: 'constant-arrival-rate', exec: 'mixed', rate: NORMAL, timeUnit: '1s', duration: sec(120),
    preAllocatedVUs: 100, maxVUs: 1500,
  },
  rush: {
    executor: 'ramping-arrival-rate', exec: 'mixed', startRate: NORMAL, timeUnit: '1s',
    preAllocatedVUs: 100, maxVUs: 1500,
    stages: [
      { target: NORMAL, duration: sec(30) },       // 평소
      { target: NORMAL * 4, duration: sec(10) },   // 09:00 몰려듦
      { target: NORMAL * 4, duration: sec(30) },
      { target: NORMAL, duration: sec(10) },
      { target: NORMAL, duration: sec(40) },       // 회복 구간
    ],
  },
  tick: {
    executor: 'ramping-arrival-rate', exec: 'tickRead', startRate: Math.ceil(NORMAL / 2), timeUnit: '1s',
    preAllocatedVUs: 100, maxVUs: 1500,
    stages: [0, 1, 2].flatMap(() => [
      { target: NORMAL * 4, duration: sec(2) },          // 새 틱: 다들 계좌·순위를 연다
      { target: NORMAL * 4, duration: sec(8) },
      { target: Math.ceil(NORMAL / 2), duration: sec(2) },
      { target: Math.ceil(NORMAL / 2), duration: sec(48) },
    ]),
  },
  hot: {
    executor: 'constant-arrival-rate', exec: 'hot', rate: ORDER_TPS, timeUnit: '1s', duration: sec(120),
    preAllocatedVUs: 50, maxVUs: 400,
  },
  settle: {
    executor: 'constant-arrival-rate', exec: 'settleMixed', rate: NORMAL, timeUnit: '1s', duration: sec(120),
    preAllocatedVUs: 100, maxVUs: 1500,
  },
  journey: {
    executor: 'constant-arrival-rate', exec: 'journey', rate: Math.max(1, Math.round(10 * MULT)), timeUnit: '1s',
    duration: sec(120), preAllocatedVUs: 100, maxVUs: 1500,
  },
};
if (!SCENARIOS[SCENARIO]) {
  throw new Error(`SCENARIO 는 ${Object.keys(SCENARIOS).join('|')} 중 하나여야 합니다: ${SCENARIO}`);
}

// 통과 기준. 지연 기준에는 왕복 시간(RTT_MS)을 더한다
const THRESHOLDS = {
  steady: {
    'http_req_duration{kind:order}': [`p(95)<${ms(300)}`],
    'http_req_duration{kind:read}': [`p(95)<${ms(200)}`],
    'http_req_failed': ['rate<0.01'],
  },
  rush: {
    'http_req_duration{phase:after}': [`p(95)<${ms(300)}`],   // 몰림이 끝나면 평소 기준으로 돌아와야 한다
    'http_req_failed{phase:after}': ['rate<0.01'],
  },
  tick: {
    'http_req_duration{phase:burst}': [`p(95)<${ms(500)}`],
    'http_req_duration{phase:calm}': [`p(95)<${ms(200)}`],
    'http_req_failed': ['rate<0.01'],
  },
  hot: {
    'idempotency_mismatch': ['count==0'],
    'http_req_duration{kind:order}': [`p(95)<${ms(300)}`],
    'http_req_failed': ['rate<0.01'],
  },
  settle: {
    'http_req_duration{phase:before}': [`p(95)<${ms(300)}`],
    'http_req_failed': ['rate<0.01'],
  },
  journey: {
    'http_req_duration{kind:order}': [`p(95)<${ms(300)}`],
    'http_req_duration{kind:read}': [`p(95)<${ms(200)}`],
    'http_req_failed': ['rate<0.01'],
  },
};

// tick 은 "틱이 바뀌는 순간"을 만드는 두 번째 흐름을 함께 돌린다: 폭주가 시작되고 1초 뒤 순위 스냅샷
const EXTRA = SCENARIO === 'tick' && ADMIN ? {
  tickSnapshot: {
    executor: 'constant-arrival-rate', exec: 'takeSnapshot', rate: 1, timeUnit: sec(60), duration: sec(180),
    preAllocatedVUs: 1, maxVUs: 2,
  },
} : SCENARIO === 'settle' && ADMIN ? {
  settleBatch: {
    executor: 'shared-iterations', exec: 'runSettlement', iterations: 1, vus: 1, startTime: sec(SETTLE_AT),
    maxDuration: '10m',
  },
} : {};

export const options = {
  setupTimeout: '10m',
  scenarios: { [SCENARIO]: SCENARIOS[SCENARIO], ...EXTRA },
  // 모든 시나리오 공통: 500 류 0건, 끝난 뒤 불변식 검사(돈·주식이 장부와 맞는가) 위반 0건
  thresholds: { server_errors_not_503: ['count==0'], invariant_violations: ['count==0'], ...THRESHOLDS[SCENARIO] },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 결과 표에 줄로 나오게 할 태그 조합(k6 는 thresholds 에 적힌 조합만 따로 집계한다)
const ROWS = {
  steady: ['name:POST orders(market)', 'name:GET me', 'name:GET orders', 'name:GET rankings', 'name:GET disclosures',
    'kind:order', 'kind:read'],
  rush: ['phase:before', 'phase:peak', 'phase:after', 'kind:order', 'kind:read'],
  tick: ['phase:burst', 'phase:calm', 'kind:order', 'kind:read'],
  hot: ['name:POST orders(buy)', 'name:POST orders(sell)', 'name:POST orders(double)', 'name:POST orders(resend)'],
  settle: ['phase:before', 'phase:storm', 'phase:after', 'name:POST orders(market)', 'name:DELETE order(pending)',
    'name:POST limit-settlements', 'kind:order', 'kind:read'],
  journey: ['name:GET me', 'name:GET disclosures', 'name:POST orders(market)', 'name:GET orders', 'name:POST orders(limit)',
    'name:DELETE order', 'name:POST orders(sell)', 'name:GET rankings', 'kind:order', 'kind:read'],
};
const NOOP = new Set(['max>=0', 'rate>=0', 'count>=0']);
// 거절 사유별 개수도 따로 보이게
for (const code of ['INSUFFICIENT_CASH', 'INSUFFICIENT_QUANTITY', 'ORDER_NOT_CANCELABLE', 'DATA_CONFLICT',
  'IDEMPOTENCY_KEY_REUSED', 'NOT_FOUND', 'MARKET_CLOSED', 'RATE_LIMITED']) {
  options.thresholds[`business_rejects{code:${code}}`] = ['count>=0'];
}
options.thresholds['server_errors_not_503{code:INTERNAL_ERROR}'] = ['count>=0'];
for (const tag of ROWS[SCENARIO]) {
  options.thresholds[`http_req_duration{${tag}}`] ??= ['max>=0'];
  options.thresholds[`http_req_failed{${tag}}`] ??= ['rate>=0'];
  options.thresholds[`http_reqs{${tag}}`] = ['count>=0'];
}

export function setup() {
  const data = prepare({ base: BASE, admin: ADMIN, users: USERS, startsOn: STARTS_ON, endsOn: ENDS_ON });
  if (SCENARIO === 'tick' && __ENV.TICK_ALIGN !== '0') {
    // 매분 30초(5배속 서버의 틱 경계)까지 기다렸다가 시작한다. 0초 무렵 CPU steal 과 겹치지 않게 반 분 비켰다
    const wait = (90_000 - (Date.now() % 60_000)) % 60_000;
    console.log(`틱 경계(매분 30초)에 맞추려고 ${(wait / 1000).toFixed(1)}초 기다린다`);
    sleep(wait / 1000);
  }
  if (SCENARIO === 'settle') {
    data.limitOrders = placeLimitOrders(data);
  }
  return data;
}

/**
 * settle 준비: 종목마다 시장가 1주로 지금 가격을 알아낸 뒤, 사용자마다 LIMITS 개의 매수 지정가를 지금 가격 +1% 에 건다.
 * 지정가 주문은 접수 때 체결하지 않으므로(정산 배치가 가격 경로를 보고 체결) 모두 PENDING 으로 남는다.
 * 서버 시계가 고정이라 정산을 부르면 지금 틱 가격으로 전부 닿아 한꺼번에 체결된다.
 */
function placeLimitOrders(data) {
  const c = data.contestId;
  const opts = (user, key) => ({ headers: { ...JSON_HEADERS, 'X-User-Id': String(user), 'Idempotency-Key': key } });
  const price = {};
  TICKERS.forEach((t, i) => {
    // 한 사용자로 24번 연달아 보내면 주문 속도 제한(초당 5, 연속 10)에 걸린다 → 종목마다 다른 사용자
    const user = data.users[i % data.users.length];
    const res = http.post(`${BASE}/api/contests/${c}/orders`, JSON.stringify(market(t, 'BUY', 1)), opts(user, `price-${t}`));
    must(res, `가격 알아내기(${t})`);
    price[t] = res.json('data.filledPrice');
  });
  const wanted = [];
  for (let k = 0; k < LIMITS; k++) {
    for (const user of data.users) {
      const ticker = pick(TICKERS);
      wanted.push({ user, body: { ticker, side: 'BUY', quantity: 1, type: 'LIMIT', limitPrice: Math.ceil(price[ticker] * 1.01) } });
    }
  }
  const placed = [];
  for (let i = 0; i < wanted.length; i += 50) {
    const batch = wanted.slice(i, i + 50);
    const res = http.batch(batch.map((w, j) => ['POST', `${BASE}/api/contests/${c}/orders`, JSON.stringify(w.body),
      opts(w.user, `limit-${i + j}`)]));
    res.forEach((r, j) => {
      if (r.status === 200 && r.json('data.status') === 'PENDING') {
        placed.push([batch[j].user, r.json('data.orderId')]);
      }
    });
  }
  console.log(`지정가 대기 주문 ${placed.length}건 (원한 수 ${wanted.length})`);
  return placed;
}

/** settle: 40초에 한 번, 관리자 정산 API. 끝날 때까지 기다리고 결과를 남긴다. */
export function runSettlement(data) {
  const res = http.post(`${BASE}/api/admin/contests/${data.contestId}/limit-settlements`, null,
    { headers: { 'X-Admin-Token': ADMIN }, tags: { kind: 'admin', name: 'POST limit-settlements', phase: 'storm' },
      timeout: '600s' });
  console.log(`정산: ${res.status} ${res.status === 200 ? JSON.stringify(res.json('data')) : res.body}`);
}

/** tick: 스케줄러가 할 일(새 틱의 순위 스냅샷)을 폭주 1초 뒤에 한다. 서버 시계가 고정이면 같은 틱이라 새로 찍히지 않는다. */
export function takeSnapshot(data) {
  sleep(1);
  const res = http.post(`${BASE}/api/admin/contests/${data.contestId}/ranking-snapshots`, null,
    { headers: { 'X-Admin-Token': ADMIN }, tags: { kind: 'admin', name: 'POST ranking-snapshots' } });
  console.log(`순위 스냅샷: ${res.status} 새로 찍음=${res.status === 200 ? res.json('data.created') : '-'}`);
}

/** 시나리오 시작 뒤 몇 초째인지(SCALE 을 되돌려 원래 시간축으로). 구간 태그를 붙이는 데 쓴다. */
function elapsed() {
  return (Date.now() - exec.scenario.startTime) / 1000 / SCALE;
}

function phase() {
  if (SCENARIO === 'rush') {
    const t = elapsed();
    return t < 30 ? 'before' : t < 90 ? 'peak' : 'after';
  }
  if (SCENARIO === 'tick') {
    return elapsed() % 60 < 12 ? 'burst' : 'calm';
  }
  if (SCENARIO === 'settle') {
    // 정산이 몇 초 걸릴지 모르므로 넉넉히 30초를 폭주 구간으로 본다. 실제 끝 시각은 정산 응답의 elapsedMs 로 본다
    const t = elapsed();
    return t < SETTLE_AT ? 'before' : t < SETTLE_AT + 30 ? 'storm' : 'after';
  }
  return 'all';
}

/** 응답을 분류해 센다: 500 류(0 이어야 함), 503(덜어냄), 409 등 업무상 거절(코드별). */
function record(res) {
  if (res.status === 503) {
    shed.add(1);
  } else if (res.status >= 500) {
    serverErrors.add(1, { code: errorCode(res) });
  } else if (res.status >= 400) {
    rejects.add(1, { code: errorCode(res) });
  }
  return res;
}

function get(path, user, kind, name) {
  return record(http.get(`${BASE}${path}`, { headers: { 'X-User-Id': String(user) }, tags: { kind, name, phase: phase() } }));
}

function postOrder(c, user, body, name, key) {
  return record(http.post(`${BASE}/api/contests/${c}/orders`, JSON.stringify(body), {
    headers: { ...JSON_HEADERS, 'X-User-Id': String(user), 'Idempotency-Key': key || newKey() },
    tags: { kind: 'order', name, phase: phase() },
  }));
}

function newKey() {
  return `${exec.vu.idInTest}-${exec.scenario.iterationInTest}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function think(min = 0.5, max = 2) {
  sleep(min + Math.random() * (max - min));
}

const market = (ticker, side, quantity) => ({ ticker, side, quantity });

/** steady·rush: 주문 1 : 조회 3. */
export function mixed(data) {
  const user = pick(data.users);
  const c = data.contestId;
  const p = Math.random();
  if (p < 0.25) {
    postOrder(c, user, market(pick(TICKERS), 'BUY', 1 + Math.floor(Math.random() * 3)), 'POST orders(market)');
  } else if (p < 0.55) {
    get(`/api/contests/${c}/me`, user, 'read', 'GET me');
  } else if (p < 0.75) {
    get(`/api/contests/${c}/orders`, user, 'read', 'GET orders');
  } else if (p < 0.95) {
    get(`/api/contests/${c}/rankings?limit=20`, user, 'read', 'GET rankings');
  } else {
    get(`/api/contests/${c}/disclosures?limit=20`, user, 'read', 'GET disclosures');
  }
}

/** settle: steady 와 같은 섞음에 "대기 주문 취소" 10% 를 넣는다. 취소는 정산 전엔 성공, 정산과 겹치면 둘 중 하나만 이긴다. */
export function settleMixed(data) {
  if (Math.random() < 0.1 && data.limitOrders.length > 0) {
    const [user, orderId] = pick(data.limitOrders);
    record(http.del(`${BASE}/api/contests/${data.contestId}/orders/${orderId}`, null, {
      headers: { 'X-User-Id': String(user) }, tags: { kind: 'order', name: 'DELETE order(pending)', phase: phase() },
    }));
    return;
  }
  mixed(data);
}

/** tick: 새 틱이 뜨면 계좌·순위를 연다. 주문은 조금. */
export function tickRead(data) {
  const user = pick(data.users);
  const c = data.contestId;
  const p = Math.random();
  if (p < 0.45) {
    get(`/api/contests/${c}/me`, user, 'read', 'GET me');
  } else if (p < 0.8) {
    get(`/api/contests/${c}/rankings?limit=20`, user, 'read', 'GET rankings');
  } else if (p < 0.9) {
    get(`/api/contests/${c}/orders`, user, 'read', 'GET orders');
  } else {
    postOrder(c, user, market(pick(TICKERS), 'BUY', 1), 'POST orders(market)');
  }
}

// hot: VU 마다 마지막으로 보낸 주문을 기억해 두었다가 "늦은 재전송"에 쓴다
let lastSent = null;

/** hot: 계좌 HOT_USERS 개, 인기 종목 3개에 매수·매도. 15% 동시 두 번 누르기, 15% 늦은 재전송. */
export function hot(data) {
  const users = data.users.slice(0, HOT_USERS);
  const c = data.contestId;
  const p = Math.random();

  if (p < 0.15) {
    // 같은 키로 동시에 두 번(버튼 연타). 둘 다 성공했다면 같은 주문 id 여야 한다
    const user = pick(users);
    const key = newKey();
    const body = JSON.stringify(market(pick(TICKERS.slice(0, 3)), 'BUY', 1));
    const params = {
      headers: { ...JSON_HEADERS, 'X-User-Id': String(user), 'Idempotency-Key': key },
      tags: { kind: 'order', name: 'POST orders(double)', phase: 'all' },
    };
    const [a, b] = http.batch([['POST', `${BASE}/api/contests/${c}/orders`, body, params],
      ['POST', `${BASE}/api/contests/${c}/orders`, body, params]]).map(record);
    if (a.status === 200 && b.status === 200 && a.json('data.orderId') !== b.json('data.orderId')) {
      idemMismatch.add(1);
    }
    check(a, { '연타: 같은 주문': () => !(a.status === 200 && b.status === 200) || a.json('data.orderId') === b.json('data.orderId') });
    return;
  }

  if (p < 0.3 && lastSent) {
    // 같은 키·같은 내용으로 나중에 다시(네트워크 재시도). 처음과 같은 주문 id 여야 한다
    const res = postOrder(c, lastSent.user, lastSent.body, 'POST orders(resend)', lastSent.key);
    if (res.status === 200 && lastSent.orderId && res.json('data.orderId') !== lastSent.orderId) {
      idemMismatch.add(1);
    }
    return;
  }

  const user = pick(users);
  const side = Math.random() < 0.5 ? 'BUY' : 'SELL';
  const body = market(pick(TICKERS.slice(0, 3)), side, 1);
  const key = newKey();
  const res = postOrder(c, user, body, side === 'BUY' ? 'POST orders(buy)' : 'POST orders(sell)', key);
  lastSent = { user, body, key, orderId: res.status === 200 ? res.json('data.orderId') : null };
}

/** journey: 한 사람의 한 번 접속. 사이사이 생각하는 시간을 둔다. */
export function journey(data) {
  const user = pick(data.users);
  const c = data.contestId;
  const ticker = pick(TICKERS);

  get(`/api/contests/${c}/me`, user, 'read', 'GET me');
  think();
  get(`/api/contests/${c}/disclosures?limit=20`, user, 'read', 'GET disclosures');
  think();
  postOrder(c, user, market(ticker, 'BUY', 1 + Math.floor(Math.random() * 2)), 'POST orders(market)');
  get(`/api/contests/${c}/orders`, user, 'read', 'GET orders');
  think();

  const p = Math.random();
  if (p < 0.3) {
    // 체결되지 않을 낮은 값에 지정가를 걸었다가 마음을 바꿔 취소
    const res = postOrder(c, user, { ticker, side: 'BUY', quantity: 1, type: 'LIMIT', limitPrice: 1000 }, 'POST orders(limit)');
    if (res.status === 200) {
      think(0.3, 1);
      record(http.del(`${BASE}/api/contests/${c}/orders/${res.json('data.orderId')}`, null, {
        headers: { 'X-User-Id': String(user) }, tags: { kind: 'order', name: 'DELETE order', phase: 'all' },
      }));
    }
  } else if (p < 0.6) {
    postOrder(c, user, market(ticker, 'SELL', 1), 'POST orders(sell)');
  }
  think();
  get(`/api/contests/${c}/rankings?limit=20`, user, 'read', 'GET rankings');
}

/** 부하가 끝난 뒤 한 번: 이 대회의 돈과 주식이 장부와 맞는지 서버에 검사시킨다. */
export function teardown(data) {
  auditInvariants({ base: BASE, admin: ADMIN, contestId: data.contestId });
}

export function handleSummary(data) {
  const header = [
    `시나리오: ${SCENARIO}, 가정의 ${MULT}배(평소 주문 ${ORDER_TPS} + 조회 ${READ_TPS} TPS), 사용자 ${USERS}명, 단계 배율 ${SCALE}`,
    `왕복 보정: 지연 기준에 ${RTT_MS} ms 를 더함`,
    `실제 처리: ${data.metrics.http_reqs ? data.metrics.http_reqs.values.rate.toFixed(1) : '-'} req/s (준비 요청 포함)`,
  ];
  const text = summaryText(data, header, NOOP);
  return { stdout: text, [SUMMARY]: JSON.stringify(data, null, 2), [SUMMARY.replace(/\.json$/, '.md')]: text };
}
