// S1: 도착률 고정(ramping-arrival-rate)으로 처리량 한계를 찾는다 (팀 문서 8-2). 초당 요청 수를 고정하므로 응답이 느려져도 요청이 줄지 않는다.
// 구성: 워밍업(첫 단계 속도로 WARMUP) → 단계마다 RAMP(속도 전환) + HOLD(유지). 요청마다 stage 태그를 달아서 단계별 지표가 summary.json에 나온다
//   (태그 값: warmup, ramp, 단계 속도, 예: http_req_duration{stage:300}). 단계 값은 HOLD 구간의 요청만 센다.
//   단계 구분은 시간이 아니라 요청 순번(iterationInTest) 기준이다. k6가 단계마다 보낼 요청 수는 속도의 적분(속도 x 시간)으로 정해지는데,
//   실제 실행 시간은 단계 설정보다 길어질 수 있어서(첫 실행에서 660초 설정이 694초 걸림) 경과 시간으로 나누면 단계가 어긋난다.
// 환경변수 (기본값은 값이 확정되기 전의 추천이고, 첫 측정 전에 확정한 뒤 측정 후에는 바꾸지 않는다):
//   STAGES   단계 속도(건/초), 기본 100,200,300,433,600,800
//   HOLD     단계 유지 시간, 기본 90s      RAMP  단계 사이 전환 시간, 기본 10s
//   WARMUP   측정에서 제외하는 워밍업 시간, 기본 60s (0s면 생략)
//   PRE_VUS, MAX_VUS  k6 가상 사용자 수, 기본 300, 800. MAX_VUS가 모자라 요청이 밀리면 dropped_iterations가 생기고 그 회차는 무효다
//   RATE(+DURATION)  한 속도만 일정하게 (find-limit.sh가 사용). 이때는 단계, 워밍업 없이 전체 구간이 기준이고, dropped_iterations 임계값만 단계 모드와 같이 건다
// 임계값: 단계마다 평균 응답, 실패율 기준(THRESHOLD=pass 500ms/1%, goal 250ms/p95 500ms/0%)을 따로 걸어서, 처음 넘는 단계가 한계 지점이다. dropped_iterations가 1건이라도 있으면 종료 코드 99
// IPO_IDS 없이 실행하면 회원 1,000명을 돌려 쓰므로 한 바퀴가 지나면 같은 회원의 요청이 이중청약 거절(Consumer가 판정, HTTP는 202)이 된다 (처리량 한계용).
//   G2(접수→확정)까지 재려면 IPO_IDS를 줘서 모든 요청이 확정되게 한다 (아래 default 함수 주석).
import exec from 'k6/execution';
import { makeOptions, loadUsers, subscribe, check202, uuid4 } from './lib.js';

const users = loadUsers();

function toSec(s) {
  const m = /^(\d+)(s|m)$/.exec(String(s));
  if (!m) throw new Error(`시간은 30s, 2m 형식이어야 합니다: ${s}`);
  return Number(m[1]) * (m[2] === 'm' ? 60 : 1);
}

const SINGLE = __ENV.RATE ? Number(__ENV.RATE) : null;
const bounds = []; // 요청 순번(0부터)이 end보다 작으면 tag 구간. 순번은 단계별 요청 수(속도의 적분)를 누적한 값
let scenario;
let extra = {};

if (SINGLE !== null) {
  scenario = {
    executor: 'ramping-arrival-rate', startRate: SINGLE, timeUnit: '1s',
    stages: [{ target: SINGLE, duration: __ENV.DURATION || '60s' }],
    preAllocatedVUs: Number(__ENV.PRE_VUS || 100), maxVUs: Number(__ENV.MAX_VUS || 500),
  };
  extra = { dropped_iterations: ['count==0'] }; // 직접 실행해도 밀린 반복이 있으면 종료 코드 99 (find-limit.sh는 종료 코드를 무시하고 summary를 직접 판정한다)
} else {
  const steps = (__ENV.STAGES || '100,200,300,433,600,800').split(',').map(Number);
  const hold = toSec(__ENV.HOLD || '90s');
  const ramp = toSec(__ENV.RAMP || '10s');
  const warm = toSec(__ENV.WARMUP || '60s');
  const stages = [];
  let n = 0; // 누적 요청 수
  let rate = steps[0]; // 현재 속도
  const add = (target, sec, tag) => {
    stages.push({ target, duration: `${sec}s` });
    n += Math.round(((rate + target) / 2) * sec); // 속도가 직선으로 바뀌므로 평균 속도 x 시간
    rate = target;
    bounds.push({ end: n, tag });
  };
  if (warm > 0) add(steps[0], warm, 'warmup');
  steps.forEach((s) => { if (ramp > 0) add(s, ramp, 'ramp'); add(s, hold, String(s)); });
  scenario = {
    executor: 'ramping-arrival-rate', startRate: steps[0], timeUnit: '1s', stages,
    preAllocatedVUs: Number(__ENV.PRE_VUS || 300), maxVUs: Number(__ENV.MAX_VUS || 800),
  };
  const goal = __ENV.THRESHOLD === 'goal';
  // 전체 구간에 걸리는 기본 임계값은 단계가 섞인 값이라 의미가 없어서 기록용으로만 두고, 단계별로 건다
  extra = { http_reqs: ['count>=0'], http_req_duration: ['avg>=0'], http_req_failed: ['rate>=0'], dropped_iterations: ['count==0'] };
  steps.forEach((s) => {
    extra[`http_req_duration{stage:${s}}`] = goal ? ['avg<=250', 'p(95)<=500'] : ['avg<=500'];
    extra[`http_req_failed{stage:${s}}`] = [goal ? 'rate==0' : 'rate<0.01'];
    extra[`http_reqs{stage:${s}}`] = ['count>=0'];
  });
}

export const options = makeOptions('S1', { S1: scenario }, extra);

function stageTag(idx) {
  if (bounds.length === 0) return 'single';
  for (const b of bounds) if (idx < b.end) return b.tag;
  return 'end';
}

// IPO_IDS(쉼표로 구분한 공모주 ID 목록)를 주면 회원이 한 바퀴 돌 때마다 다음 공모주로 넘어간다 (요청 idx → 회원 idx % N, 공모주 floor(idx / N)).
// 같은 회원도 다른 공모주에는 다시 청약할 수 있어서 모든 요청이 이중청약 거절이 아니라 확정 경로를 탄다. 그래야 단계마다 접수→확정 시간(G2)을 잴 수 있다.
// 필요한 공모주 수 = ceil(전체 요청 수 / 회원 수). 모자라면 테스트를 중단한다 (거절이 섞인 값으로 G2를 판정하지 않기 위해).
const IPO_LIST = (__ENV.IPO_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);

export default function () {
  const idx = exec.scenario.iterationInTest;
  const u = users[idx % users.length];
  let ipoId;
  if (IPO_LIST.length > 0) {
    const k = Math.floor(idx / users.length);
    if (k >= IPO_LIST.length) exec.test.abort(`공모주가 모자랍니다: 요청 ${idx}번에 ${k + 1}번째 공모주가 필요한데 ${IPO_LIST.length}개뿐`);
    ipoId = IPO_LIST[k];
  }
  const { res, data } = subscribe({ scenario: 'S1', idx, user: u, accountId: u.accountId, requestId: uuid4(), expected: [202], tags: { stage: stageTag(idx) }, ipoId });
  check202(res, data);
}
