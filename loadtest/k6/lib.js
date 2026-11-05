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
  return loadCsv('users', __ENV.USERS_CSV || '../data/users.csv');
}

// Synthetic Data 행동 CSV (loadtest/synthetic/<시나리오>-behavior.csv). BEHAVIOR_CSV로 바꿀 수 있다
export function loadBehavior(scenario) {
  return loadCsv('behavior', __ENV.BEHAVIOR_CSV || `../synthetic/${scenario.toLowerCase()}-behavior.csv`);
}

// 머리줄이 있는 CSV를 모든 VU가 함께 읽는다 (값에 쉼표가 없는 CSV만)
export function loadCsv(name, path) {
  return new SharedArray(name, () => {
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
// ipoId를 따로 주면 그 공모주로 청약한다 (S1이 단계 부하에서 확정 건을 계속 만들려고 공모주를 돌려 쓴다). 안 주면 IPO_ID.
export function subscribe({ scenario, idx, user, accountId, requestId, expected, tags = {}, ipoId = IPO_ID }) {
  if (!ipoId) throw new Error('IPO_ID 환경변수가 필요합니다');
  const body = JSON.stringify({ requestId, ipoId, accountId, requestedQuantity: QUANTITY });
  const res = http.post(`${BASE_URL}/api/v1/subscriptions`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${user.token}` },
    responseCallback: http.expectedStatuses(...expected),
    tags,
  });
  let data = {};
  let code = '';
  try { const j = res.json(); data = j.data || {}; code = (j.error && j.error.code) || ''; } catch (e) { /* 본문이 JSON이 아님 */ }
  console.log(JSON.stringify({ s: scenario, run: RUN_ID, idx, requestId, status: res.status, subscriptionId: data.subscriptionId || '', code, stage: tags.stage || '' }));
  return { res, data, code };
}

export function check202(res, data) {
  return check(res, {
    '202 접수': (r) => r.status === 202,
    'subscriptionId 있음': () => !!data.subscriptionId,
    'status == ACCEPTED': () => data.status === 'ACCEPTED',
  });
}

// 100명 x 10회 (S0, S3, S4): 100명을 1초에 걸쳐 시작하고, 요청 순번 idx = (VU - 1) x 10 + 반복 번호
// pick(idx, users)는 요청 하나의 { user, accountId, requestId }를 돌려준다. 없으면 S0 방식 (한 요청 = 한 회원, requestId 무작위)
// verify(users, total)는 시작 전에 데이터를 검사하고 문제가 있으면 예외를 던진다 (init에서 던지면 k6가 요청 없이 끝난다)
export function normal(scenario, { pick, verify } = {}) {
  const vus = Number(__ENV.VUS || 100);
  const iters = Number(__ENV.ITERATIONS || 10);
  const users = loadUsers();
  if (verify) verify(users, vus * iters);
  const choose = pick || ((idx) => ({ user: users[idx], accountId: users[idx].accountId, requestId: uuid4() }));
  return {
    options: makeOptions(scenario, { [scenario]: { executor: 'per-vu-iterations', vus, iterations: iters, maxDuration: '2m' } }),
    run() {
      if (__ITER === 0) sleepRamp(vus);
      const idx = (__VU - 1) * iters + __ITER;
      const { user, accountId, requestId } = choose(idx, users);
      const { res, data } = subscribe({ scenario, idx, user, accountId, requestId, expected: [202] });
      check202(res, data);
    },
  };
}

export function sleepRamp(vus) { sleep((__VU - 1) / vus); }
