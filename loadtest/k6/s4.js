// S4: 예수금이 부족한 투자자가 섞인 시나리오. 요청은 S0과 같고(HTTP는 전부 202), 잔액 부족 판정은 Consumer가 한다.
//   i번째 요청은 Synthetic Data 행동 CSV(loadtest/synthetic/s4-behavior.csv)의 i번째 행 투자자(vuser)가 보낸다
//   누가 부족한지는 시드할 때 행동 CSV의 입금액으로 정해진다 (seed_users.py --prefix lb --behavior ../synthetic/s4-behavior.csv)
// 회원 CSV는 잔액 부족 묶음을 쓴다 (USERS_CSV=../data/users-s4.csv, run-k6.sh는 --users data/users-s4.csv).
// 기본 묶음(lt)으로 돌리면 S0과 같은 요청이 나가 "거절 0"이 결함처럼 보이므로 시작 전에 멈춘다
import { normal, loadBehavior, uuid4 } from './lib.js';

const behavior = loadBehavior('S4');
const PREFIX = __ENV.S4_PREFIX || 'lb';

function verify(users, total) {
  if (behavior.length !== total) {
    throw new Error(`행동 CSV 행 수(${behavior.length})가 요청 수(VUS x ITERATIONS = ${total})와 다릅니다`);
  }
  behavior.forEach((row, i) => {
    const v = Number(row.vuser);
    if (!Number.isInteger(v) || v < 0 || v >= users.length) throw new Error(`행동 CSV ${i}행: vuser ${row.vuser}가 회원 CSV(${users.length}명) 범위 밖입니다`);
    if (!String(users[v].loginId).startsWith(PREFIX)) {
      throw new Error(`회원 ${v}(${users[v].loginId})가 잔액 부족 묶음(${PREFIX})이 아닙니다. USERS_CSV=../data/users-s4.csv로 실행합니다`);
    }
  });
}

function pick(idx, users) {
  const user = users[Number(behavior[idx].vuser)];
  return { user, accountId: user.accountId, requestId: uuid4() };
}

const s = normal('S4', { pick, verify });
export const options = s.options;
export default s.run;
