// S3: 재전송과 두 번째 계좌가 섞인 시나리오 (상세 계획 8장, 모두 [가정] 값이라 환경변수로 바꿀 수 있다).
//   정상 (전체 - RESEND - SECOND)건 + 같은 requestId 재전송 RESEND건 + 두 번째 계좌 청약 SECOND건 = 전체 1,000건
//   재전송: 앞쪽 회원(0번부터)의 원래 요청과 같은 requestId. 응답의 subscriptionId가 원래 요청과 같아야 한다 (verify/check_requests.py로 대조)
//   두 번째 계좌: SECOND_FROM번 회원부터. 회원 CSV에 accountId2가 있어야 한다 (seed_users.py --second-account-count)
// 두 번째 계좌 청약의 이중청약 거절(ALREADY_SUBSCRIBED)은 Consumer가 판정한다 (HTTP는 202).
import { makeOptions, loadUsers, subscribe, check202, uuidFromSeed, RUN_ID, sleepRamp } from './lib.js';

const vus = Number(__ENV.VUS || 100);
const iters = Number(__ENV.ITERATIONS || 10);
const total = vus * iters;
const RESEND = Number(__ENV.S3_RESEND || 100);
const SECOND = Number(__ENV.S3_SECOND || 100);
const SECOND_FROM = Number(__ENV.S3_SECOND_FROM || 100);
const NORMAL = total - RESEND - SECOND;
const users = loadUsers();

export const options = makeOptions('S3', {
  S3: { executor: 'per-vu-iterations', vus, iterations: iters, maxDuration: '2m' },
});

export default function () {
  if (__ITER === 0) sleepRamp(vus);
  const idx = (__VU - 1) * iters + __ITER;
  let j = idx;
  let accountId;
  let requestId;
  if (idx < NORMAL) {
    accountId = users[j].accountId;
    requestId = uuidFromSeed(`${RUN_ID}:${j}`);
  } else if (idx < NORMAL + RESEND) {
    j = idx - NORMAL;
    accountId = users[j].accountId;
    requestId = uuidFromSeed(`${RUN_ID}:${j}`);
  } else {
    j = SECOND_FROM + (idx - NORMAL - RESEND);
    accountId = users[j].accountId2;
    requestId = uuidFromSeed(`${RUN_ID}:acc2:${j}`);
    if (!accountId) throw new Error(`회원 ${j}에 두 번째 계좌(accountId2)가 없습니다`);
  }
  const { res, data } = subscribe({ scenario: 'S3', idx, user: users[j], accountId, requestId, expected: [202] });
  check202(res, data);
}
