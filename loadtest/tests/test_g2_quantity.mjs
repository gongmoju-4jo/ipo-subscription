// k6/g2-quantity.js 단위 테스트 (Node로 실행, k6, Docker 없이)
// 사용: node loadtest/tests/test_g2_quantity.mjs
import { quantityFor, CASE_A_QUANTITY, CASE_B_BLOCK } from '../k6/g2-quantity.js';

let pass = 0, fail = 0;
const ok = (name) => { pass++; console.log(`ok   ${name}`); };
const bad = (name, why) => { fail++; console.log(`FAIL ${name} (${why})`); };
const t = (name, cond, why = '') => (cond ? ok(name) : bad(name, why));
const throws = (fn) => { try { fn(); return false; } catch (e) { return true; } };

// A: 모두 172주
t('A: 순번과 관계없이 172주', [0, 1, 49, 50, 99999].every((i) => quantityFor('A', i) === 172) && CASE_A_QUANTITY === 172);

// B: 50건마다 10주 35, 50주 10, 200주 4, 1000주 1
const count = (n) => {
  const c = {};
  for (let i = 0; i < n; i++) { const q = quantityFor('B', i); c[q] = (c[q] || 0) + 1; }
  return c;
};
const c50 = count(50);
t('B: 50건 안 분포 10주 35, 50주 10, 200주 4, 1000주 1', c50[10] === 35 && c50[50] === 10 && c50[200] === 4 && c50[1000] === 1 && Object.keys(c50).length === 4, JSON.stringify(c50));
const c1k = count(1000);
t('B: 1,000건 비율 70 / 20 / 8 / 2%', c1k[10] === 700 && c1k[50] === 200 && c1k[200] === 80 && c1k[1000] === 20, JSON.stringify(c1k));
let sum = 0; for (let i = 0; i < 100000; i++) sum += quantityFor('B', i);
t('B: 10만 건 평균 53주 (g2_ipo.sh 물량 계산의 평균과 같음)', sum / 100000 === 53, String(sum / 100000));
t('B: 같은 순번은 늘 같은 수량, 50건마다 반복', [0, 34, 35, 44, 45, 48, 49].every((i) => quantityFor('B', i) === quantityFor('B', i + CASE_B_BLOCK * 7)));
t('B: 경계 34→10, 35→50, 44→50, 45→200, 48→200, 49→1000',
  quantityFor('B', 34) === 10 && quantityFor('B', 35) === 50 && quantityFor('B', 44) === 50 && quantityFor('B', 45) === 200 && quantityFor('B', 48) === 200 && quantityFor('B', 49) === 1000);

// 잘못된 입력
t('CASE가 A, B가 아니면 예외 (빈 값, 소문자 포함)', ['', 'a', 'C', undefined].every((c) => throws(() => quantityFor(c, 0))));
t('순번이 음수, 소수, 숫자가 아니면 예외', [-1, 1.5, NaN, '3'].every((i) => throws(() => quantityFor('A', i))));

console.log(`\n통과 ${pass}, 실패 ${fail}`);
process.exit(fail === 0 ? 0 : 1);
