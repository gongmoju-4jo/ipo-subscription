// S1: 일정 속도로 1~3분 유지하는 시나리오. 처리량 한계를 말할 때의 근거로 쓴다 (S0은 수 초 만에 끝나서 rate가 흔들린다).
// ⚠️ 임시 값: VU, 유지 시간, 요청 속도가 아직 정해지지 않았다. 첫 측정 전에 확정해서 고친다 (측정 후에는 바꾸지 않는다).
//   RATE(초당 요청 수), DURATION(유지 시간)을 환경변수로 바꿀 수 있다. 기본 200건/초, 60초.
// 회원 1,000명을 돌려 쓰므로 한 바퀴가 지나면 같은 회원의 요청이 이중청약 거절(Consumer가 판정, HTTP는 202)이 된다.
import exec from 'k6/execution';
import { makeOptions, loadUsers, subscribe, check202, uuid4 } from './lib.js';

const users = loadUsers();
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '60s';

export const options = makeOptions('S1', {
  S1: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 100, maxVUs: 500 },
});

export default function () {
  const idx = exec.scenario.iterationInTest;
  const u = users[idx % users.length];
  const { res, data } = subscribe({ scenario: 'S1', idx, user: u, accountId: u.accountId, requestId: uuid4(), expected: [202] });
  check202(res, data);
}
