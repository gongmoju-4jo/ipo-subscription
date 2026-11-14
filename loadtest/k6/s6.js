// S6: 장애 주입. 부하 중에 계좌 서비스를 강제 종료했다가 다시 띄워, 청약과 증거금이 어떻게 되는지 본다 (S6-a 짧은 장애, S6-b 긴 장애).
// S0는 1,000건이 1~2초에 끝나 "부하 시작 N초 뒤 종료"를 맞출 수 없어서, 같은 건수를 일정한 도착률로 여러 초에 걸쳐 보낸다.
// 회원마다 한 번씩만 청약하므로(회원 수 = 최대 요청 수) 요청 수는 RATE x DURATION이고 회원 수를 넘을 수 없다.
//   요청 순번 idx = 회원 순번. 한 공모주(IPO_ID)에 전원이 한 번씩 청약한다 (이중청약 없음, 기대 응답은 전부 202)
// 실행은 loadtest/s6.sh가 한다 (종료, 재시작 시각 기록, 소진 대기, 판정). 직접 실행할 때도 같은 환경변수를 준다.
// 환경변수: IPO_ID(필수), START_AT(부하 시작 epoch ms. 필수. s6.sh가 종료 시각을 이 값 기준으로 정한다),
//          RATE(100건/초), DURATION(10초), PRE_VUS(500), MAX_VUS(2000)
// 판정 임계값: S6는 처리량(G1)을 재는 시나리오가 아니라서 http_reqs(200건/초 이상)는 기록만 한다.
//   모든 check 통과, 밀린 요청(dropped_iterations) 0, 오류율 1% 미만, 평균 응답 500ms 이하
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { makeOptions, loadUsers, subscribe, uuid4, check202, IPO_ID } from './lib.js';

const users = loadUsers();
const U = users.length;
const RATE = Number(__ENV.RATE || 100);
const DURATION = Number(__ENV.DURATION || 10);
const START_AT = Number(__ENV.START_AT || 0);
const TOTAL = Math.round(RATE * DURATION);

if (!IPO_ID) throw new Error('IPO_ID(공모주)가 필요합니다');
if (!START_AT) throw new Error('START_AT(부하 시작 epoch ms)이 필요합니다. loadtest/s6.sh로 실행한다');
if (!(RATE > 0) || !(DURATION > 0)) throw new Error(`RATE, DURATION은 0보다 커야 합니다: ${RATE}, ${DURATION}`);
// 모자라면 같은 회원이 같은 공모주에 다시 청약해 이중청약(REJECTED, 응답은 202)이 섞인다. 요청 전에 멈춘다
if (TOTAL > U) throw new Error(`요청 수 ${TOTAL}건(RATE ${RATE} x DURATION ${DURATION})이 회원 수 ${U}명보다 많습니다`);

export const options = {
  ...makeOptions('S6', {
    S6: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s',
      duration: `${Math.ceil((TOTAL / RATE) * 1000)}ms`,
      preAllocatedVUs: Number(__ENV.PRE_VUS || 500), maxVUs: Number(__ENV.MAX_VUS || 2000),
    },
  // http_reqs의 초당 값은 setup의 시작 대기까지 나눠서 실제 속도보다 낮게 나오고, S6는 처리량을 재지 않으므로 기록만 한다
  }, { checks: ['rate==1'], dropped_iterations: ['count==0'], http_reqs: ['count>=0'] }),
  setupTimeout: '120s',
};

// 계획한 요청 수와 회원 수를 시작 전에 확인하고, 부하는 START_AT에 맞춰 시작한다 (s6.sh가 종료 시각을 START_AT 기준으로 정함)
export function setup() {
  console.log(`S6 계획: ${RATE}건/초 x ${DURATION}초 = ${TOTAL}건, 회원 ${U}명`);
  const wait = START_AT - Date.now();
  if (wait > 0) sleep(wait / 1000);
  return { lateMs: Math.max(0, -wait) };
}

// idx(iterationInTest)는 실제로 보낸 요청만 세서, 밀린 요청(dropped_iterations)이 있으면 그 뒤 요청의 시각이 늦어진다. 그래서 밀림 0을 임계값으로 건다
export default function () {
  const idx = exec.scenario.iterationInTest;
  if (idx >= TOTAL) return;
  const u = users[idx];
  const { res, data } = subscribe({ scenario: 'S6', idx, user: u, accountId: u.accountId, requestId: uuid4(),
    expected: [202], tags: { stage: 's6' } });
  check202(res, data);
}
