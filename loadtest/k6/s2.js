// S2: 마감된 공모주에 청약하는 시나리오. 기대: 마감 거절(HTTP 422 SUBSCRIPTION_CLOSED)은 실패로 세지 않는다.
// 마감된 공모주는 ./seed/new_ipo.sh --closed 로 만든다 (IPO_ID에 넣는다).
// ⚠️ 임시 값: 상세 계획에 S2의 요청 구성이 정해져 있지 않다. S0과 같은 100명 x 10회로 두었고, 첫 측정 전에 확정해서 고친다.
import { check } from 'k6';
import { makeOptions, loadUsers, subscribe, uuid4, sleepRamp } from './lib.js';

const vus = Number(__ENV.VUS || 100);
const iters = Number(__ENV.ITERATIONS || 10);
const users = loadUsers();

export const options = makeOptions('S2', {
  S2: { executor: 'per-vu-iterations', vus, iterations: iters, maxDuration: '2m' },
});

export default function () {
  if (__ITER === 0) sleepRamp(vus);
  const idx = (__VU - 1) * iters + __ITER;
  const u = users[idx];
  const { res, code } = subscribe({ scenario: 'S2', idx, user: u, accountId: u.accountId, requestId: uuid4(), expected: [202, 422] });
  check(res, { '마감 거절 422': (r) => r.status === 422, 'SUBSCRIPTION_CLOSED': () => code === 'SUBSCRIPTION_CLOSED' });
}
