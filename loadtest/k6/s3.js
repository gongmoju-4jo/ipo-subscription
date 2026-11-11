// S3: 재전송과 다른 계좌 청약이 섞인 시나리오. 요청 구성은 Synthetic Data 행동 CSV(loadtest/synthetic/s3-behavior.csv)를 따른다.
//   i번째 행 = i번째 요청 (VU i/10+1의 i%10번째). 열: vuser(회원 CSV의 행 번호), persona, action
//   NORMAL: 첫 청약 / RESEND: 같은 투자자의 NORMAL과 같은 requestId로 재전송 (응답의 subscriptionId가 같아야 한다)
//   SECOND_ACCOUNT: 같은 투자자가 두 번째 계좌(accountId2)로 같은 종목을 다시 청약. 늦게 처리된 쪽이 ALREADY_SUBSCRIBED로 거절 (청약 API가 접수 트랜잭션에서 판정, HTTP는 202)
// 회원 CSV는 SECOND_ACCOUNT 투자자에게 두 번째 계좌가 있어야 한다 (seed_users.py --behavior ../synthetic/s3-behavior.csv)
// 요청 수(VUS x ITERATIONS)는 행동 CSV 행 수와 같아야 한다. 다르면 시작 전에 멈춘다
import { normal, loadBehavior, uuidFromSeed, RUN_ID } from './lib.js';

const behavior = loadBehavior('S3');
const ACTIONS = ['NORMAL', 'RESEND', 'SECOND_ACCOUNT'];

function verify(users, total) {
  if (behavior.length !== total) {
    throw new Error(`행동 CSV 행 수(${behavior.length})가 요청 수(VUS x ITERATIONS = ${total})와 다릅니다`);
  }
  behavior.forEach((row, i) => {
    const v = Number(row.vuser);
    if (!ACTIONS.includes(row.action)) throw new Error(`행동 CSV ${i}행: 모르는 action ${row.action}`);
    if (!Number.isInteger(v) || v < 0 || v >= users.length) throw new Error(`행동 CSV ${i}행: vuser ${row.vuser}가 회원 CSV(${users.length}명) 범위 밖입니다`);
    if (row.action === 'SECOND_ACCOUNT' && !users[v].accountId2) {
      throw new Error(`회원 ${v}(${users[v].loginId})에 두 번째 계좌(accountId2)가 없습니다. seed_users.py --behavior로 시드합니다`);
    }
  });
}

// 같은 투자자의 NORMAL과 RESEND는 같은 requestId (회차 RUN_ID가 다르면 다른 값이라 회차끼리 겹치지 않는다)
function pick(idx, users) {
  const row = behavior[idx];
  const user = users[Number(row.vuser)];
  if (row.action === 'SECOND_ACCOUNT') {
    return { user, accountId: user.accountId2, requestId: uuidFromSeed(`${RUN_ID}:acc2:${row.vuser}`) };
  }
  return { user, accountId: user.accountId, requestId: uuidFromSeed(`${RUN_ID}:${row.vuser}`) };
}

const s = normal('S3', { pick, verify });
export const options = s.options;
export default s.run;
