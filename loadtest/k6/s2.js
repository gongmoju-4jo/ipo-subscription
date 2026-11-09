// S2: 마감 직전 몰림 + 마감 경계 (SA 기획서 S2: 마감 전 274건/초 유지, 마감 시각을 넘겨서도 계속 요청).
// 공모주 하나에는 회원마다 한 번씩만 청약할 수 있어서(회원 수 = 최대 요청 수) 요청을 두 종류로 나눈다.
//   대상 공모주(IPO_ID): 측정 중에 실제로 마감되는 공모주. 회원 전원이 한 번씩 요청하고, 그 구간 가운데에 마감 시각이 온다
//   배경 공모주(IPO_IDS): 마감되지 않는 공모주들. 대상 구간 앞뒤로 같은 속도를 이어 가서 마감 순간에도 몰림 상태를 만든다
// 시간 순서 (도착률 고정이라 요청 순번 ÷ RATE = 시작 뒤 초):
//   [배경 PRE초] [대상 구간 (TARGET_SEC, 0이면 회원 수 ÷ RATE초 동안 전부 대상)] [배경 POST초]
// 실행은 loadtest/s2.sh가 한다 (대상 공모주 마감 시각 설정, START_AT 계산, 판정). 직접 실행할 때도 같은 환경변수를 준다.
// 환경변수: IPO_ID(대상), IPO_IDS(배경, 쉼표), START_AT(부하 시작 epoch ms, 대상 마감 시각 계산의 기준),
//          RATE(274), PRE(60초), POST(40초), TARGET_SEC(0), PRE_VUS(100), MAX_VUS(500)
// 요청 로그(requests.log)의 stage: target(대상), bg(배경). 대상은 202(마감 전 도착), 422(마감 후 도착), 409(마감 처리 뒤 도착)를 기대한다.
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { makeOptions, loadUsers, subscribe, uuid4, check202, IPO_ID } from './lib.js';

const users = loadUsers();
const U = users.length;
const RATE = Number(__ENV.RATE || 274);
const PRE = Number(__ENV.PRE || 60);
const POST = Number(__ENV.POST || 40);
const TARGET_SEC = Number(__ENV.TARGET_SEC || 0);
const START_AT = Number(__ENV.START_AT || 0);
const BG = (__ENV.IPO_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);

// 대상 구간: 요청 순번 [WS, WS + WL). 그 안에서 회원 수(U)만큼을 고르게 대상으로 보낸다 (TARGET_SEC = 0이면 구간 전부가 대상)
const WIN_SEC = TARGET_SEC > 0 ? TARGET_SEC : U / RATE;
const WS = Math.round(RATE * PRE);
const WL = Math.max(U, Math.round(RATE * WIN_SEC));
const TOTAL = Math.round(RATE * (PRE + WIN_SEC + POST));
const BG_NEED = Math.ceil((TOTAL - U) / U);

if (!IPO_ID) throw new Error('IPO_ID(대상 공모주)가 필요합니다');
if (!START_AT) throw new Error('START_AT(부하 시작 epoch ms)이 필요합니다. loadtest/s2.sh로 실행한다');
if (TARGET_SEC > 0 && RATE * TARGET_SEC < U) throw new Error(`TARGET_SEC가 짧습니다: ${TARGET_SEC}초 x ${RATE}건/초 < 회원 ${U}명`);
// 모자라면 같은 회원이 같은 공모주에 다시 청약해 이중청약(REJECTED, 응답은 202)이 섞인다. 요청 전에 멈춘다
if (BG.length < BG_NEED) throw new Error(`배경 공모주가 모자랍니다: 필요 ${BG_NEED}개, 받은 ${BG.length}개`);

export const options = {
  ...makeOptions('S2', {
    S2: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s',
      duration: `${Math.ceil((TOTAL / RATE) * 1000)}ms`,
      preAllocatedVUs: Number(__ENV.PRE_VUS || 100), maxVUs: Number(__ENV.MAX_VUS || 500),
    },
  // http_reqs의 초당 값은 setup의 시작 대기까지 나눠서 실제 속도보다 낮게 나온다. S2는 처리량(G1)이 아니라 마감 경계를 보므로 기록만 한다
  }, { checks: ['rate==1'], dropped_iterations: ['count==0'], http_reqs: ['count>=0'] }),
  setupTimeout: '120s',
};

// 대상 공모주의 마감 시각은 s2.sh가 START_AT 기준으로 정해 둔다. 그 시각에 맞춰 부하를 시작한다
export function setup() {
  const wait = START_AT - Date.now();
  if (wait > 0) sleep(wait / 1000);
  return { lateMs: Math.max(0, -wait) };
}

// 요청 순번 idx → 대상인지, 몇 번째 회원인지, 배경이면 몇 번째 공모주인지.
// idx(iterationInTest)는 실제로 보낸 요청만 세서, 밀린 요청(dropped_iterations)이 있으면 대상 구간이 그만큼 늦어진다. 그래서 밀림 0을 임계값으로 건다

function route(idx) {
  if (idx >= WS && idx < WS + WL) {
    const j = idx - WS;
    const before = Math.floor((j * U) / WL);      // 이 요청 앞의 대상 요청 수
    if (Math.floor(((j + 1) * U) / WL) > before) return { target: true, user: before };
    return { target: false, b: idx - before };
  }
  return { target: false, b: idx < WS ? idx : idx - U };
}

export default function () {
  const idx = exec.scenario.iterationInTest;
  if (idx >= TOTAL) return;
  const r = route(idx);
  if (r.target) {
    const u = users[r.user];
    const { res, code } = subscribe({ scenario: 'S2', idx, user: u, accountId: u.accountId, requestId: uuid4(),
      expected: [202, 409, 422], tags: { stage: 'target' } });
    check(res, {
      '대상: 202, 422(마감 후), 409(마감 처리 뒤)': (x) => x.status === 202
        || (x.status === 422 && code === 'SUBSCRIPTION_CLOSED')
        || (x.status === 409 && code === 'INVALID_STATUS_TRANSITION'),
    });
    return;
  }
  const k = Math.floor(r.b / U);
  if (k >= BG.length) exec.test.abort(`배경 공모주가 모자랍니다: 요청 ${idx}번에 ${k + 1}번째가 필요한데 ${BG.length}개뿐`);
  const u = users[r.b % U];
  const { res, data } = subscribe({ scenario: 'S2', idx, user: u, accountId: u.accountId, requestId: uuid4(),
    expected: [202], tags: { stage: 'bg' }, ipoId: BG[k] });
  check202(res, data);
}
