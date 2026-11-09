// G2 대량 환불 측정용 청약 시드: 회원 CSV의 앞에서부터 COUNT명이 각각 1건씩 청약한다 (기본: CSV 전체). 기대: 전부 202
// 실행: ./run-k6.sh g2 --ipo <공모주 ID> --run-id <run_id> --users data/<회원 CSV> -e CASE=A [-e COUNT=1000] [-e VUS=50]
//   CASE: A(모든 청약 172주) 또는 B(회원 순번으로 고정한 수량 분포, g2-quantity.js)
//   COUNT: 청약 건수 (워밍업 공모주 W는 1000). 회원 CSV 행 수보다 많으면 요청 없이 멈춘다
//   VUS: 동시 가상 사용자 수. 시간 상한 없이 COUNT건을 모두 보낸다 (S0처럼 2분 상한이 없다)
// 측정이 아니라 시드라서 G1 처리량, 응답 기준은 걸지 않고, 202가 아닌 응답이 하나라도 있으면 실패한다 (종료 코드 99)
import exec from 'k6/execution';
import { loadUsers, subscribe, check202, uuid4, RUN_ID } from './lib.js';
import { quantityFor } from './g2-quantity.js';

const CASE = __ENV.CASE || '';
quantityFor(CASE, 0); // CASE가 A, B가 아니면 init에서 멈춘다
const users = loadUsers();
const COUNT = Number(__ENV.COUNT || users.length);
const VUS = Number(__ENV.VUS || 50);
if (!Number.isInteger(COUNT) || COUNT < 1 || COUNT > users.length) {
  throw new Error(`COUNT는 1 이상, 회원 CSV 행 수(${users.length}) 이하: ${__ENV.COUNT}`);
}

export const options = {
  scenarios: { G2: { executor: 'shared-iterations', vus: Math.min(VUS, COUNT), iterations: COUNT, maxDuration: '6h' } },
  thresholds: { checks: ['rate==1'] },
  tags: { scenario: 'G2', run: RUN_ID, case: CASE },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  const idx = exec.scenario.iterationInTest; // 0 ~ COUNT-1, 각 순번은 한 번만
  const user = users[idx];
  const { res, data } = subscribe({
    scenario: 'G2', idx, user, accountId: user.accountId, requestId: uuid4(), expected: [202],
    quantity: quantityFor(CASE, idx),
  });
  check202(res, data);
}
