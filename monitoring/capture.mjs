// Grafana 패널을 PNG 로 저장한다(보고서용). 설치할 것 없음: Node 22+ 의 기본 WebSocket 으로 Chrome 을 직접 조종한다.
// 그래프가 다 그려질 때까지 기다렸다가 찍는다(Chrome --screenshot 은 "Loading" 화면을 찍어 버린다).
// 사용: node monitoring/capture.mjs <이름> <시작 epoch초> <끝 epoch초> [패널 id ...]
//   예) node monitoring/capture.mjs hot-x1 1794582438 1794582588
// 결과: docs/img/<이름>-p<패널>.png. 패널 id 를 안 주면 보고서에 쓰는 기본 6개
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync, openSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [name, from, to, ...ids] = process.argv.slice(2);
if (!name || !from || !to) {
  console.error('사용법: node monitoring/capture.mjs <이름> <시작초> <끝초> [패널 id...]');
  process.exit(1);
}
// 5 p95(API별) · 4 응답 상태 · 8 커넥션 사용/대기 · 10 커넥션 쥔 시간 · 14 CPU · 6 p50/p99
const PANELS = ids.length ? ids : ['5', '4', '8', '10', '14', '6'];
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9333;
const profile = mkdtempSync(join(tmpdir(), 'grafana-capture-'));
const chromeLog = join(profile, 'chrome.log');   // 못 뜨면 이유를 보여 주려고 남긴다
const logFd = openSync(chromeLog, 'w');
const chrome = spawn(CHROME, ['--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
  '--no-default-browser-check', `--user-data-dir=${profile}`, `--remote-debugging-port=${PORT}`, 'about:blank'],
{ stdio: ['ignore', logFd, logFd] });

// 워치독: 전체가 2분을 넘기면 Chrome 을 죽이고 끝낸다(좀비 방지)
const watchdog = setTimeout(() => { console.error('시간 초과'); cleanup(1); }, 120_000);
function cleanup(code) {
  clearTimeout(watchdog);
  try { chrome.kill('SIGKILL'); } catch { /* 이미 끝남 */ }
  // 본체만 죽이면 Chrome 의 도우미 프로세스(렌더러 등)가 남는다. 이번 실행 전용 프로필 경로로 찾아 모두 끝낸다
  spawnSync('pkill', ['-9', '-f', profile]);
  rmSync(profile, { recursive: true, force: true });
  process.exit(code);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function debuggerUrl() {
  for (let i = 0; i < 150; i++) {   // 최대 30초(맥이 바쁘면 Chrome 이 늦게 뜬다)
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' });
      return (await res.json()).webSocketDebuggerUrl;
    } catch { await sleep(200); }
  }
  let tail = '';
  try { tail = readFileSync(chromeLog, 'utf8').split('\n').slice(-8).join('\n'); } catch { /* 없음 */ }
  throw new Error(`Chrome 디버깅 포트에 붙지 못했습니다(30초). Chrome 로그 끝:\n${tail}`);
}

try {
  const ws = new WebSocket(await debuggerUrl());
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let seq = 0;
  const pending = new Map();
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
  };
  const send = (method, params = {}) => new Promise((r) => {
    const id = ++seq; pending.set(id, r); ws.send(JSON.stringify({ id, method, params }));
  });

  await send('Emulation.setDeviceMetricsOverride', { width: 1000, height: 560, deviceScaleFactor: 2, mobile: false });
  mkdirSync('docs/img', { recursive: true });
  for (const id of PANELS) {
    const url = `http://localhost:3000/d-solo/league?orgId=1&panelId=${id}&from=${from}000&to=${to}000`
      + '&theme=light&tz=Asia%2FSeoul';
    await send('Page.enable');
    await send('Page.navigate', { url });
    // 그래프 캔버스(uPlot)가 생기고 "Loading" 글자가 사라질 때까지 기다린다(최대 20초)
    let ready = false;
    for (let i = 0; i < 100 && !ready; i++) {
      await sleep(200);
      const r = await send('Runtime.evaluate', { returnByValue: true,
        expression: "!!document.querySelector('.u-over') && !document.body.innerText.includes('Loading')" });
      ready = r.result?.result?.value === true;
    }
    await sleep(800);   // 마지막 선 그리기(애니메이션) 여유
    const shot = await send('Page.captureScreenshot', { format: 'png' });
    const out = `docs/img/${name}-p${id}.png`;
    writeFileSync(out, Buffer.from(shot.result.data, 'base64'));
    console.log(ready ? out : `${out} (경고: 그래프가 다 그려졌는지 확인 못 함)`);
  }
  ws.close();
  cleanup(0);
} catch (e) {
  console.error(e.message);
  cleanup(1);
}
