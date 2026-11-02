// S0: 기본 시나리오. 회원 1,000명이 각각 1건 (100명 x 10회, 1초에 걸쳐 시작). 기대: 전부 202
// 실행: IPO_ID=<이번 회차 공모주> RUN_ID=<run_id> k6 run --summary-export=... --console-output=... s0.js
import { normal } from './lib.js';

const s = normal('S0');
export const options = s.options;
export default s.run;
