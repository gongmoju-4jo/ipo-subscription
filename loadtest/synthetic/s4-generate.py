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
