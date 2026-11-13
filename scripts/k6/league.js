// 5주차② k6 부하 테스트. docs/01 5절 가정(주문 30 TPS, 조회 90 TPS)의 MULT 배를 "일정한 도착 속도"로 보낸다.
// constant-arrival-rate: 서버가 느려져도 요청을 덜 보내지 않는다(실제 사용자는 서버 사정을 봐주지 않는다).
// 실행은 scripts/load-test.sh 가 한다. 직접 돌릴 때: k6 run -e BASE_URL=... -e ADMIN_TOKEN=... scripts/k6/league.js
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:18081';
const ADMIN = __ENV.ADMIN_TOKEN || '';
const MULT = Number(__ENV.MULT || 3);
const DURATION = __ENV.DURATION || '2m';
const USERS = Number(__ENV.USERS || 1000);
const STARTS_ON = __ENV.STARTS_ON || '2026-12-01';   // 서버 시계(LEAGUE_CLOCK_FIXED)와 같은 날
const ENDS_ON = __ENV.ENDS_ON || '2026-12-31';
const SUMMARY = __ENV.SUMMARY || 'build/k6/summary.json';
// 측정 전 워밍업. 운영 서버는 이미 JIT 컴파일·커넥션·캐시가 데워진 상태라, 막 뜬 JVM 의 첫 30초는 따로 뺀다
const WARMUP = __ENV.WARMUP || '30s';

const ORDER_TPS = 30 * MULT;
const READ_TPS = 90 * MULT;

const TICKERS = ['SBSI', 'ARCL', 'MRSF', 'EHLG', 'PRCB', 'DAPH', 'SBDX', 'CRGN', 'BRGE', 'HORS', 'DDBT', 'GSGS',
  'GMFD', 'HRCF', 'MMLV', 'NMFN', 'DDBK', 'BBSC', 'IECP', 'MRIN', 'BJET', 'DKGM', 'ARST', 'SRMS'];

export const options = {
  setupTimeout: '10m',
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate', exec: 'warmup',
      rate: Math.ceil((ORDER_TPS + READ_TPS) / 2), timeUnit: '1s', duration: WARMUP,
      preAllocatedVUs: 50, maxVUs: 400,
    },
    orders: {
      executor: 'constant-arrival-rate', exec: 'order', startTime: WARMUP,
      rate: ORDER_TPS, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 50, maxVUs: 400,
    },
    reads: {
      executor: 'constant-arrival-rate', exec: 'read', startTime: WARMUP,
      rate: READ_TPS, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 100, maxVUs: 800,
    },
  },
  // 통과 기준
  thresholds: {
    'http_req_failed{kind:order}': ['rate<0.01'],
    'http_req_failed{kind:read}': ['rate<0.01'],
    'http_req_duration{kind:order}': ['p(95)<300'],
    'http_req_duration{kind:read}': ['p(95)<200'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 준비(setup)·워밍업 요청은 kind 가 order/read 가 아니라 아래 기준에 들어가지 않는다
// k6 는 thresholds 에 적힌 태그 조합만 따로 집계한다. 요청 이름별 표를 만들려고 "항상 통과하는" 기준을 붙인다
const NAMES = ['kind:order,name:POST orders(market)', 'kind:order,name:POST orders(limit)', 'kind:read,name:GET me',
  'kind:read,name:GET orders', 'kind:read,name:GET rankings', 'kind:read,name:GET disclosures'];
for (const tag of [...NAMES, 'kind:order', 'kind:read']) {
  options.thresholds[`http_req_duration{${tag}}`] ??= ['max>=0'];
  options.thresholds[`http_req_failed{${tag}}`] ??= ['rate>=0'];
  options.thresholds[`http_reqs{${tag}}`] = ['count>=0'];
}
const NOOP = new Set(['max>=0', 'rate>=0', 'count>=0']);

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function must(res, what) {
  if (res.status >= 300) {
    fail(`${what} 실패: ${res.status} ${res.body}`);
  }
  return res;
}

/** 대회 하나, 사용자 USERS 명을 만들어 참가시킨다. 50개씩 동시에 보낸다(http.batch). */
export function setup() {
  const admin = { headers: { ...JSON_HEADERS, 'X-Admin-Token': ADMIN } };
  const run = Date.now() % 100000000;
  const contest = must(http.post(`${BASE}/api/admin/contests`,
    JSON.stringify({ name: `k6-${run}`, startsOn: STARTS_ON, endsOn: ENDS_ON, seed: 7 }), admin), '대회 만들기');
  const contestId = contest.json('data.id');
  must(http.post(`${BASE}/api/admin/contests/${contestId}/start`, null, admin), '대회 시작');

  const users = [];
  for (let i = 0; i < USERS; i += 50) {
    const batch = [];
    for (let j = i; j < Math.min(i + 50, USERS); j++) {
      batch.push(['POST', `${BASE}/api/users`, JSON.stringify({ nickname: `k6-${run}-${j}` }), { headers: JSON_HEADERS }]);
    }
    const ids = http.batch(batch).map((r) => must(r, '사용자 만들기').json('data.id'));
    http.batch(ids.map((id) => ['POST', `${BASE}/api/contests/${contestId}/participants`, null,
      { headers: { 'X-User-Id': String(id) } }])).forEach((r) => must(r, '참가'));
    users.push(...ids);
  }
  // 순위 조회가 빈 표가 아니라 실제 1,000줄 스냅샷을 읽게 한 장 찍어 둔다
  must(http.post(`${BASE}/api/admin/contests/${contestId}/ranking-snapshots`, null, admin), '스냅샷');
  return { contestId, users };
}

/** 주문: 시장가 매수 80%, 체결되지 않을 낮은 값의 지정가 매수 20%(대기 주문·묶인 현금이 섞이게). */
export function order(data, kind = 'order') {
  const user = pick(data.users);
  const limit = Math.random() < 0.2;
  const body = limit
    ? { ticker: pick(TICKERS), side: 'BUY', quantity: 1, type: 'LIMIT', limitPrice: 1000 }
    : { ticker: pick(TICKERS), side: 'BUY', quantity: 1 + Math.floor(Math.random() * 3) };
  const res = http.post(`${BASE}/api/contests/${data.contestId}/orders`, JSON.stringify(body), {
    headers: { ...JSON_HEADERS, 'X-User-Id': String(user), 'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}` },
    tags: { kind, name: limit ? 'POST orders(limit)' : 'POST orders(market)' },
  });
  check(res, { '주문 200': (r) => r.status === 200 });
}

/** 조회: 내 계좌 40%, 내 주문 30%, 순위표 20%, 공시 10%. */
export function read(data, kind = 'read') {
  const user = String(pick(data.users));
  const c = data.contestId;
  const p = Math.random();
  let url;
  let name;
  if (p < 0.4) {
    url = `${BASE}/api/contests/${c}/me`; name = 'GET me';
  } else if (p < 0.7) {
    url = `${BASE}/api/contests/${c}/orders`; name = 'GET orders';
  } else if (p < 0.9) {
    url = `${BASE}/api/contests/${c}/rankings?limit=20`; name = 'GET rankings';
  } else {
    url = `${BASE}/api/contests/${c}/disclosures?limit=20`; name = 'GET disclosures';
  }
  const res = http.get(url, { headers: { 'X-User-Id': user }, tags: { kind, name } });
  check(res, { '조회 200': (r) => r.status === 200 });
}

/** 워밍업: 주문 1 : 조회 3 비율로 섞는다. 결과 표에는 넣지 않는다. */
export function warmup(data) {
  if (Math.random() < 0.25) {
    order(data, 'warmup');
  } else {
    read(data, 'warmup');
  }
}

function fmt(m, stat) {
  return m && m.values[stat] !== undefined ? m.values[stat].toFixed(1) : '-';
}

/** 요약: 요청 이름별 지연·실패와 실제로 보낸 속도를 표로 남긴다. 원본 수치는 JSON 으로 저장한다. */
export function handleSummary(data) {
  const m = data.metrics;
  const lines = [];
  lines.push(`목표: 주문 ${ORDER_TPS} TPS + 조회 ${READ_TPS} TPS (가정의 ${MULT}배), ${DURATION}, 사용자 ${USERS}명`);
  lines.push(`실제 처리: ${fmt(m.http_reqs, 'rate')} req/s (준비 요청 포함), 못 보낸 요청(dropped): ${m.dropped_iterations ? m.dropped_iterations.values.count : 0}`);
  lines.push('');
  lines.push('| 구분 | 요청 수 | 실패율 | p50(ms) | p95(ms) | p99(ms) | 최대(ms) |');
  lines.push('|---|---|---|---|---|---|---|');
  const rows = Object.keys(m).filter((k) => k.startsWith('http_req_duration{')).sort();
  for (const k of rows) {
    const tag = k.slice('http_req_duration'.length);
    const failed = m[`http_req_failed${tag}`];
    const reqs = m[`http_reqs${tag}`];
    lines.push(`| ${tag} | ${reqs ? reqs.values.count : '-'} | ${failed ? (failed.values.rate * 100).toFixed(2) + '%' : '-'} | ${fmt(m[k], 'med')} | ${fmt(m[k], 'p(95)')} | ${fmt(m[k], 'p(99)')} | ${fmt(m[k], 'max')} |`);
  }
  lines.push('');
  for (const [name, t] of Object.entries(data.metrics)) {
    if (t.thresholds) {
      for (const [expr, r] of Object.entries(t.thresholds)) {
        if (NOOP.has(expr)) continue;
        lines.push(`${r.ok ? '통과' : '실패'}  ${name}  ${expr}`);
      }
    }
  }
  const text = lines.join('\n') + '\n';
  return { stdout: text, [SUMMARY]: JSON.stringify(data, null, 2), [SUMMARY.replace(/\.json$/, '.md')]: text };
}
