// 부하 테스트 공통 코드. 시나리오 스크립트(s0.js ~ s4.js)가 가져다 쓴다.
// 환경변수: BASE_URL(기본 http://localhost:8080), IPO_ID(필수), RUN_ID, USERS_CSV, THRESHOLD(pass 또는 goal)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const RUN_ID = __ENV.RUN_ID || 'dev';
export const IPO_ID = __ENV.IPO_ID;
export const QUANTITY = Number(__ENV.QUANTITY || 10);

// 회원 CSV(loginId,userId,accountId,accountId2,token)를 모든 VU가 함께 읽는다
export function loadUsers() {
  const path = __ENV.USERS_CSV || '../data/users.csv';
  return new SharedArray('users', () => {
    const lines = open(path).split(/\r?\n/).filter((l) => l.trim() !== '');
    const head = lines[0].split(',');
    return lines.slice(1).map((l) => {
      const cols = l.split(',');
      const row = {};
      head.forEach((h, i) => { row[h] = cols[i]; });
      return row;
    });
  });
}

export function uuid4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

// 같은 seed면 항상 같은 UUID 모양 값 (재전송 요청이 원래 요청과 같은 requestId를 쓰도록)
export function uuidFromSeed(seed) {
  let hex = '';
  for (let k = 0; k < 4; k++) {
    let h = 0x811c9dc5 ^ (k * 0x9e3779b1);
    for (let i = 0; i < seed.length; i++) {
      h ^= seed.charCodeAt(i);
      h = Math.imul(h, 0x01000193) >>> 0;
    }
    hex += h.toString(16).padStart(8, '0');
  }
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

// 합격, 목표 기준은 측정 전에 정한 값이고 측정 후에는 바꾸지 않는다.
//   합격: 200건/초 이상, 평균 500ms 이하, 오류율 1% 미만 / 목표: 400건/초 이상, 평균 250ms 이하, p95 500ms 이하, 오류 0
const THRESHOLDS = {
  pass: { http_reqs: ['rate>=200'], http_req_duration: ['avg<=500'], http_req_failed: ['rate<0.01'] },
  goal: { http_reqs: ['rate>=400'], http_req_duration: ['avg<=250', 'p(95)<=500'], http_req_failed: ['rate==0'] },
};

// extraThresholds: 시나리오가 추가로 요구하는 임계값 (예: S2는 check가 전부 통과해야 한다)
export function makeOptions(scenarioName, scenarios, extraThresholds = {}) {
  return {
    scenarios,
    thresholds: { ...THRESHOLDS[__ENV.THRESHOLD || 'pass'], ...extraThresholds },
    tags: { scenario: scenarioName, run: RUN_ID },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  };
}

// 청약 신청 1건. 요청마다 requestId, subscriptionId, 상태 코드를 한 줄(JSON)로 남긴다 (--console-output 파일)
export function subscribe({ scenario, idx, user, accountId, requestId, expected }) {
  if (!IPO_ID) throw new Error('IPO_ID 환경변수가 필요합니다');
  const body = JSON.stringify({ requestId, ipoId: IPO_ID, accountId, requestedQuantity: QUANTITY });
  const res = http.post(`${BASE_URL}/api/v1/subscriptions`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user.token}` },
    responseCallback: http.expectedStatuses(...expected),
  });
  let data = {};
  let code = '';
  try { const j = res.json(); data = j.data || {}; code = (j.error && j.error.code) || ''; } catch (e) { /* 본문이 JSON이 아님 */ }
  console.log(JSON.stringify({ s: scenario, run: RUN_ID, idx, requestId, status: res.status, subscriptionId: data.subscriptionId || '', code }));
  return { res, data, code };
}

export function check202(res, data) {
  return check(res, {
    '202 접수': (r) => r.status === 202,
    'subscriptionId 있음': () => !!data.subscriptionId,
    'status == ACCEPTED': () => data.status === 'ACCEPTED',
  });
}

// 100명 x 10회 (S0, S4): 한 요청 = 한 회원이고, 100명을 1초에 걸쳐 시작한다
export function normal(scenario) {
  const vus = Number(__ENV.VUS || 100);
  const iters = Number(__ENV.ITERATIONS || 10);
  const users = loadUsers();
  return {
    options: makeOptions(scenario, { [scenario]: { executor: 'per-vu-iterations', vus, iterations: iters, maxDuration: '2m' } }),
    run() {
      if (__ITER === 0) sleepRamp(vus);
      const idx = (__VU - 1) * iters + __ITER;
      const u = users[idx];
      const { res, data } = subscribe({ scenario, idx, user: u, accountId: u.accountId, requestId: uuid4(), expected: [202] });
      check202(res, data);
    },
  };
}

export function sleepRamp(vus) { sleep((__VU - 1) / vus); }
