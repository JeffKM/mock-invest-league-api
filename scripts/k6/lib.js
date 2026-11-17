// k6 시나리오들이 함께 쓰는 준비·도구. league.js(일정한 속도)는 예전 그대로 두고, realistic.js 가 이 파일을 쓴다.
import http from 'k6/http';
import { fail } from 'k6';
import { Counter } from 'k6/metrics';

export const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const TICKERS = ['SBSI', 'ARCL', 'MRSF', 'EHLG', 'PRCB', 'DAPH', 'SBDX', 'CRGN', 'BRGE', 'HORS', 'DDBT', 'GSGS',
  'GMFD', 'HRCF', 'MMLV', 'NMFN', 'DDBK', 'BBSC', 'IECP', 'MRIN', 'BJET', 'DKGM', 'ARST', 'SRMS'];

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export function must(res, what) {
  if (res.status >= 300) {
    fail(`${what} 실패: ${res.status} ${res.body}`);
  }
  return res;
}

/** 오류 응답 본문의 error.code(예: INSUFFICIENT_CASH). 본문이 JSON 이 아니면 HTTP 상태를 쓴다. */
export function errorCode(res) {
  try {
    return res.json('error.code') || String(res.status);
  } catch (e) {
    return String(res.status);
  }
}

/**
 * 측정용 대회 하나를 만들어 시작하고, 사용자 users 명을 참가시킨다(10명씩 동시에).
 * 마지막에 순위 스냅샷을 한 장 찍어 순위 조회가 빈 표가 아니라 실제 users 줄을 읽게 한다.
 */
export function prepare({ base, admin, users, startsOn, endsOn }) {
  const adminHeaders = { headers: { ...JSON_HEADERS, 'X-Admin-Token': admin } };
  const run = Date.now() % 100000000;
  const contest = must(http.post(`${base}/api/admin/contests`,
    JSON.stringify({ name: `k6-${run}`, startsOn, endsOn, seed: 7 }), adminHeaders), '대회 만들기');
  const contestId = contest.json('data.id');
  must(http.post(`${base}/api/admin/contests/${contestId}/start`, null, adminHeaders), '대회 시작');

  // 10명씩: 같은 대회에 참가 50건을 동시에 보내자 커넥션 3초 대기를 넘겨 503 이 났다(30단계, 쉬는 서버에서도).
  // 그전에는 k6 가 서버당 6개씩만 보내서 드러나지 않았다. 원인은 P7(가입 몰림)에서 따로 본다
  const ids = [];
  for (let i = 0; i < users; i += 10) {
    const batch = [];
    for (let j = i; j < Math.min(i + 10, users); j++) {
      batch.push(['POST', `${base}/api/users`, JSON.stringify({ nickname: `k6-${run}-${j}` }), { headers: JSON_HEADERS }]);
    }
    const made = http.batch(batch).map((r) => must(r, '사용자 만들기').json('data.id'));
    http.batch(made.map((id) => ['POST', `${base}/api/contests/${contestId}/participants`, null,
      { headers: { 'X-User-Id': String(id) } }])).forEach((r) => must(r, '참가'));
    ids.push(...made);
  }
  must(http.post(`${base}/api/admin/contests/${contestId}/ranking-snapshots`, null, adminHeaders), '스냅샷');
  return { contestId, users: ids };
}

// 불변식 위반 건수(검사별 code 태그). 통과 기준 count==0
const invariantViolations = new Counter('invariant_violations');

/**
 * 서버의 불변식 검사 API 를 불러 위반 건수를 센다(6주차③). 응답 코드가 모두 정상이어도
 * 같은 돈을 두 번 쓰거나 묶인 돈이 안 풀린 일은 장부를 대조해야 보이기 때문이다.
 */
export function auditInvariants({ base, admin, contestId }) {
  const res = http.get(`${base}/api/admin/contests/${contestId}/invariants`,
    { headers: { 'X-Admin-Token': admin }, tags: { kind: 'audit', name: 'GET invariants' }, timeout: '120s' });
  if (res.status !== 200) {
    console.error(`불변식 검사를 못 했습니다: ${res.status} ${res.body}`);
    invariantViolations.add(1, { code: 'AUDIT_FAILED' });
    return;
  }
  let total = 0;
  for (const c of res.json('data.checks')) {
    total += c.violations;
    if (c.violations > 0) {
      console.error(`불변식 위반 ${c.code}: ${c.violations}건 (${c.rule}) 예: ${c.sample.join(', ')}`);
      invariantViolations.add(c.violations, { code: c.code });
    }
  }
  if (total === 0) {
    invariantViolations.add(0);   // 0 이어도 지표가 생겨야 결과 표에 "통과"로 나온다
  }
}

function fmt(m, stat) {
  return m && m.values[stat] !== undefined ? m.values[stat].toFixed(1) : '-';
}

/** 태그 조합별 표(요청 수, 실패율, 지연)와 통과 기준 결과, 사용자 지표(카운터)를 마크다운으로 만든다. */
export function summaryText(data, header, noop) {
  const m = data.metrics;
  const lines = [...header, ''];
  lines.push('| 구분 | 요청 수 | 실패율 | p50(ms) | p95(ms) | p99(ms) | 최대(ms) |');
  lines.push('|---|---|---|---|---|---|---|');
  for (const k of Object.keys(m).filter((x) => x.startsWith('http_req_duration{')).sort()) {
    const tag = k.slice('http_req_duration'.length);
    const failed = m[`http_req_failed${tag}`];
    const reqs = m[`http_reqs${tag}`];
    lines.push(`| ${tag} | ${reqs ? reqs.values.count : '-'} | ${failed ? (failed.values.rate * 100).toFixed(2) + '%' : '-'} | ${fmt(m[k], 'med')} | ${fmt(m[k], 'p(95)')} | ${fmt(m[k], 'p(99)')} | ${fmt(m[k], 'max')} |`);
  }
  lines.push('');
  const counters = Object.keys(m).filter((k) => m[k].type === 'counter' && !k.startsWith('http_') && !k.startsWith('data_')
    && !k.startsWith('iterations') && !k.startsWith('dropped'));
  for (const k of counters.sort()) {
    lines.push(`${k}: ${m[k].values.count}`);
  }
  lines.push(`못 보낸 요청(dropped): ${m.dropped_iterations ? m.dropped_iterations.values.count : 0}`);
  lines.push('');
  for (const [name, t] of Object.entries(m)) {
    if (!t.thresholds) continue;
    for (const [expr, r] of Object.entries(t.thresholds)) {
      if (noop.has(expr)) continue;
      lines.push(`${r.ok ? '통과' : '실패'}  ${name}  ${expr}`);
    }
  }
  return lines.join('\n') + '\n';
}
