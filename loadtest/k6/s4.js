// S4: 잔액 부족 회원이 섞인 시나리오. 요청은 S0과 같고(HTTP는 전부 202), 잔액 부족 판정은 Consumer가 한다.
// 회원 CSV는 잔액 부족 회원이 포함된 묶음을 쓴다 (USERS_CSV=../data/users-s4.csv)
import { normal } from './lib.js';

const s = normal('S4');
export const options = s.options;
export default s.run;
