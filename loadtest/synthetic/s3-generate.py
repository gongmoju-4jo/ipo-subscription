"""S3 행동 CSV 생성 (표준 라이브러리만, 고정 시드)."""
import csv
import random

rng = random.Random(102)

N = 860          # 첫 청약을 하는 투자자 수 (vuser 0~859)
R = 80           # 같은 requestId 재전송
S = 60           # 다른 계좌로 같은 종목 재청약
BOTH = 10        # R, S 중 두 행동을 모두 하는 투자자
PERSONAS = [("직장인", 40), ("전업투자자", 15), ("자영업자", 15), ("대학생", 10), ("주부", 10), ("은퇴자", 10)]

names = [p for p, _ in PERSONAS]
weights = [w for _, w in PERSONAS]
investors = list(range(N))
persona = {v: rng.choices(names, weights)[0] for v in investors}

resend = set(rng.sample(investors, R))
second = set(rng.sample(sorted(resend), BOTH))
second |= set(rng.sample([v for v in investors if v not in resend], S - BOTH))

# 정렬 키: 첫 청약은 투자자 번호, 후속 요청은 "첫 청약 위치 + 몇 행 뒤 - 0.5"
rows = [(float(v), 0, v, "NORMAL") for v in investors]
for v in sorted(resend):
    offset = 1 if rng.random() < 0.7 else 10
    rows.append((v + offset - 0.5, 1, v, "RESEND"))
for v in sorted(second):
    offset = rng.randint(2, 6) if rng.random() < 0.8 else 10
    rows.append((v + offset - 0.5, 2, v, "SECOND_ACCOUNT"))
rows.sort()

assert len(rows) == N + R + S == 1000

with open("s3-behavior.csv", "w", encoding="utf-8", newline="") as f:
    w = csv.writer(f)
    w.writerow(["vuser", "persona", "action"])
    for _, _, v, action in rows:
        w.writerow([v, persona[v], action])
