// S7 경쟁률 조회 부하 (F-15): 청약 신청과 공모주 목록 조회(경쟁률 포함)를 같은 시간 동안 각자 고정 속도로 보낸다.
// 청약이 몰리는 동안 경쟁률을 보려는 조회도 같이 몰리는 상황이다. 목록 조회는 대기열을 거치지 않는다.
// 결과는 요청 종류 태그(kind: subscribe, list)로 나눠 본다 (http_req_duration{kind:list} 등). 태그는 시나리오에 붙여 check 결과(checks)에도 붙는다.
// 임계값(종료 코드 99): 밀린 요청 0, 청약 평균 500ms 이하(G1 합격선), 요청 종류별 실패율 1% 미만, 요청 종류별 검사 전부 통과.
// 공통 합격선(lib.js)은 청약과 목록을 합친 값이라 기록용으로 덮는다 (목록이 느리기만 해도 99가 되어 청약 기준을 넘은 것처럼 보이지 않게).
//
// 환경변수
//   RATE        청약 신청 건/초 (기본 433)
//   LIST_RATE   목록 조회 건/초 (기본 433)
//   DURATION    부하 시간 (기본 90s, 두 요청 종류가 같은 시간)
//   IPO_IDS     청약할 공모주 목록 (쉼표 구분). 회원이 한 바퀴 돌 때마다 다음 공모주로 넘어간다 (s1.js와 같은 방식, 모든 청약이 확정 경로)
//   LIST_SIZE   목록 한 페이지 크기 (기본 20, 화면 기본값)
//   EXPECT_RATE 1이면 목록 항목에 competitionRate가 있는지 검사 (기본 1). 경쟁률이 없는 코드(develop)와 비교할 때는 0
//   PRE_VUS, MAX_VUS            청약 가상 사용자 (기본 500, 2000. S1 정식 측정과 같음)
//   LIST_PRE_VUS, LIST_MAX_VUS  목록 조회 가상 사용자 (기본 200, 1000)
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { BASE_URL, makeOptions, loadUsers, subscribe, check202, uuid4 } from './lib.js';

const users = loadUsers();
const RATE = Number(__ENV.RATE || 433);
const LIST_RATE = Number(__ENV.LIST_RATE || 433);
const DURATION = __ENV.DURATION || '90s';
const LIST_SIZE = Number(__ENV.LIST_SIZE || 20);
const EXPECT_RATE = (__ENV.EXPECT_RATE || '1') === '1';
const IPO_LIST = (__ENV.IPO_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);

const constant = { executor: 'constant-arrival-rate', timeUnit: '1s', duration: DURATION };

export const options = makeOptions('S7', {
  subscribe: {
    ...constant, rate: RATE, exec: 'subscribeOne', tags: { kind: 'subscribe' },
    preAllocatedVUs: Number(__ENV.PRE_VUS || 500), maxVUs: Number(__ENV.MAX_VUS || 2000),
  },
  list: {
    ...constant, rate: LIST_RATE, exec: 'listOne', tags: { kind: 'list' },
    preAllocatedVUs: Number(__ENV.LIST_PRE_VUS || 200), maxVUs: Number(__ENV.LIST_MAX_VUS || 1000),
  },
}, {
  // 밀린 요청이 있으면 정한 속도를 보내지 못한 것이라 회차를 쓰지 않는다 (종료 코드 99)
  dropped_iterations: ['count==0'],
  // 청약과 목록을 합친 공통 합격선은 기록용으로 덮는다 (s1.js와 같은 방식)
  http_reqs: ['count>=0'],
  http_req_duration: ['avg>=0'],
  http_req_failed: ['rate>=0'],
  // 요청 종류별 값을 요약(summary.json)에 남기려고 건다. 청약은 G1 합격선과 같고, 목록 응답 시간은 기준이 없어 값만 남긴다
  'http_reqs{kind:subscribe}': ['count>=0'],
  'http_req_duration{kind:subscribe}': ['avg<=500'],
  'http_req_failed{kind:subscribe}': ['rate<0.01'],
  'http_reqs{kind:list}': ['count>=0'],
  'http_req_duration{kind:list}': ['avg>=0'],
  'http_req_failed{kind:list}': ['rate<0.01'],
  // 응답 검사(청약 202와 본문, 목록 200과 항목, EXPECT_RATE=1이면 competitionRate)가 하나라도 틀리면 99
  'checks{kind:subscribe}': ['rate==1'],
  'checks{kind:list}': ['rate==1'],
});

// 청약 신청 1건 (s1.js와 같음. 회원 idx % 회원 수, 공모주는 회원이 한 바퀴 돌 때마다 다음 것)
export function subscribeOne() {
  const idx = exec.scenario.iterationInTest;
  const u = users[idx % users.length];
  let ipoId;
  if (IPO_LIST.length > 0) {
    const k = Math.floor(idx / users.length);
    if (k >= IPO_LIST.length) exec.test.abort(`공모주가 모자랍니다: 요청 ${idx}번에 ${k + 1}번째 공모주가 필요한데 ${IPO_LIST.length}개뿐`);
    ipoId = IPO_LIST[k];
  }
  const { res, data } = subscribe({ scenario: 'S7', idx, user: u, accountId: u.accountId, requestId: uuid4(), expected: [202], tags: { kind: 'subscribe' }, ipoId });
  check202(res, data);
}

// 공모주 목록 1페이지 조회 1건. 토큰은 회원을 돌아가며 쓴다 (목록은 회원과 관계없이 같은 결과)
export function listOne() {
  const idx = exec.scenario.iterationInTest;
  const u = users[idx % users.length];
  const res = http.get(`${BASE_URL}/api/v1/ipos?page=0&size=${LIST_SIZE}`, {
    headers: { Authorization: `Bearer ${u.token}` },
    responseCallback: http.expectedStatuses(200),
    tags: { kind: 'list' },
  });
  let items = [];
  try { items = (res.json().data || {}).items || []; } catch (e) { /* 본문이 JSON이 아님 */ }
  check(res, {
    '목록 200': (r) => r.status === 200,
    '목록 항목 있음': () => items.length > 0,
    '경쟁률 필드 있음': () => !EXPECT_RATE || items.every((it) => typeof it.competitionRate === 'number'),
  });
}
