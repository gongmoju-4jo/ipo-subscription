// S2: 마감된 공모주에 청약하는 시나리오. 기대: 마감 거절(HTTP 422 SUBSCRIPTION_CLOSED)은 실패로 세지 않는다.
// 마감된 공모주는 ./seed/new_ipo.sh --closed 로 만든다 (IPO_ID에 넣는다).
// ⚠️ 임시 값: S2의 요청 구성이 아직 정해지지 않았다. S0과 같은 100명 x 10회로 두었고, 첫 측정 전에 확정해서 고친다.
import { check } from 'k6';
import { makeOptions, loadUsers, subscribe, uuid4, sleepRamp } from './lib.js';

const vus = Number(__ENV.VUS || 100);
const iters = Number(__ENV.ITERATIONS || 10);
const users = loadUsers();

// 202도 기대 상태에 들어 있어서 http_req_failed에는 잡히지 않는다. 마감된 공모주가 202로 접수되는 것을 놓치지 않도록
// check(422, SUBSCRIPTION_CLOSED)가 하나라도 실패하면 임계값 위반(종료 코드 99)으로 처리한다.
export const options = makeOptions('S2', {
  S2: { executor: 'per-vu-iterations', vus, iterations: iters, maxDuration: '2m' },
}, { checks: ['rate==1'] });

export default function () {
  if (__ITER === 0) sleepRamp(vus);
  const idx = (__VU - 1) * iters + __ITER;
  const u = users[idx];
  const { res, code } = subscribe({ scenario: 'S2', idx, user: u, accountId: u.accountId, requestId: uuid4(), expected: [202, 422] });
  check(res, { '마감 거절 422': (r) => r.status === 422, 'SUBSCRIPTION_CLOSED': () => code === 'SUBSCRIPTION_CLOSED' });
}
