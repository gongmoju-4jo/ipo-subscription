# S4 응답 원문

`s4-prompt.md`에 대한 LLM 응답이다. 손대지 않고 그대로 둔다. 아래 코드는 `s4-generate.py`에 그대로 옮겼다.

---

## 비율과 금액 분포를 정한 이유

아래 값은 실제 통계가 아니라, 청약 첫날 오전에 예수금을 준비하지 못한 투자자가 섞인 상황을 가정해 정한 값입니다.

| 유형 | 인원 | 입금액 | 근거 |
|---|---|---|---|
| 입금을 잊음 | 38명 | 1,000~30,000원 (1,000원 단위) | 예전 거래 뒤 남은 소액만 있는 상태로 청약 버튼을 누릅니다 |
| 일부만 입금 | 31명 | 30,000~120,000원 (1,000원 단위) | 다른 계좌에서 옮기다 덜 옮겼거나, 이체 한도에 걸린 경우입니다 |
| 필요 금액 착각 | 28명 | 140,000, 142,500, 145,000, 147,000, 149,000원 중 하나 | 공모가나 수량을 잘못 계산해 조금 모자라게 넣은 경우입니다. 실제로 가장 헷갈리기 쉬운 구간이라 150,000원 바로 아래에 몰았습니다 |
| 충분히 입금 | 903명 | 1,000,000원 이상 (중앙값 약 5,000,000원, 10,000원 단위, 최대 300,000,000원) | 공모주 청약자는 보통 청약 금액보다 훨씬 많이 넣어 둡니다. 큰 금액 쪽으로 꼬리가 긴 로그정규분포로 뽑았습니다 |

- 부족한 투자자: 38 + 31 + 28 = 97명 (9.7%)
- 4번 반복해도 결과가 같도록, 부족한 투자자는 모두 150,000원 미만, 충분한 투자자는 모두 1,000,000원 이상입니다. 조건(600,000원 이상)보다 여유를 두었습니다
- 부족한 투자자는 0~999번 중에서 무작위로 골라 요청 순서 전체에 흩어집니다
- 행 순서는 투자자 번호 순서입니다 (i번째 행 = 투자자 i)

투자자 유형은 직장인 40%, 전업투자자 15%, 자영업자 15%, 대학생 10%, 주부 10%, 은퇴자 10%의 가중치로 뽑았습니다. 예수금이 부족한 투자자는 유형 뒤에 괄호로 사유를 붙였습니다 (예: `직장인(입금을 잊음)`).

## 코드

```python
"""S4 행동 CSV 생성 (표준 라이브러리만, 고정 시드)."""
import csv
import math
import random

rng = random.Random(102)

TOTAL = 1000
PERSONAS = [("직장인", 40), ("전업투자자", 15), ("자영업자", 15), ("대학생", 10), ("주부", 10), ("은퇴자", 10)]
SHORT = [
    ("입금을 잊음", 38, lambda: rng.randrange(1, 31) * 1000),
    ("일부만 입금", 31, lambda: rng.randrange(30, 121) * 1000),
    ("필요 금액 착각", 28, lambda: rng.choice([140000, 142500, 145000, 147000, 149000])),
]

names = [p for p, _ in PERSONAS]
weights = [w for _, w in PERSONAS]
investors = list(range(TOTAL))
persona = {v: rng.choices(names, weights)[0] for v in investors}

short_count = sum(n for _, n, _ in SHORT)
short_users = rng.sample(investors, short_count)
deposit, reason = {}, {}
i = 0
for label, n, amount in SHORT:
    for v in short_users[i:i + n]:
        deposit[v], reason[v] = amount(), label
    i += n


def enough():
    # 로그정규분포, 중앙값 약 5,000,000원, 10,000원 단위, 1,000,000 ~ 300,000,000원
    x = math.exp(rng.gauss(math.log(5_000_000), 0.9))
    return int(min(max(round(x, -4), 1_000_000), 300_000_000))


for v in investors:
    if v not in deposit:
        deposit[v] = enough()

assert len(deposit) == TOTAL
assert all(d < 150_000 for v, d in deposit.items() if v in reason)
assert all(d >= 600_000 for v, d in deposit.items() if v not in reason)

with open("s4-behavior.csv", "w", encoding="utf-8", newline="") as f:
    w = csv.writer(f)
    w.writerow(["vuser", "persona", "deposit"])
    for v in investors:
        label = persona[v] + (f"({reason[v]})" if v in reason else "")
        w.writerow([v, label, deposit[v]])
```
