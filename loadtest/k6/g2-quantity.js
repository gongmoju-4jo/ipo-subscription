// 대량 환불 측정(G2)의 청약 수량. k6 의존이 없어 Node로도 테스트한다 (loadtest/tests/test_g2_quantity.mjs)
//   A: 모든 청약 172주 (0주 없음)
//   B: 회원 순번 50개 단위로 고정 배치. 10주 35개, 50주 10개, 200주 4개, 1000주 1개 = 70% / 20% / 8% / 2%, 평균 53주 (약 50%가 0주 배정)
//      같은 순번은 회차가 바뀌어도 같은 수량이다. 건수가 50의 배수일 때 비율이 정확하다
export const CASE_A_QUANTITY = 172;
export const CASE_B_BLOCK = 50;

export function quantityFor(caseName, idx) {
  if (!Number.isInteger(idx) || idx < 0) throw new Error(`순번은 0 이상의 정수: ${idx}`);
  if (caseName === 'A') return CASE_A_QUANTITY;
  if (caseName === 'B') {
    const k = idx % CASE_B_BLOCK;
    if (k < 35) return 10;
    if (k < 45) return 50;
    if (k < 49) return 200;
    return 1000;
  }
  throw new Error(`CASE는 A 또는 B: ${caseName}`);
}
