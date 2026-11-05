# S3 응답 원문

`s3-prompt.md`에 대한 LLM 응답이다. 손대지 않고 그대로 둔다. 아래 코드는 `s3-generate.py`에 그대로 옮겼다.

---

## 비율과 배치를 정한 이유

아래 비율은 실제 통계가 아니라, 마감일 오전에 응답이 느려지는 상황을 가정해 정한 값입니다.

| 행동 | 요청 수 | 근거 |
|---|---|---|
| 첫 청약 (`NORMAL`) | 860건 (투자자 860명) | 대부분은 한 번 누르고 결과 화면을 기다립니다 |
| 같은 청약 재전송 (`RESEND`) | 80건 (투자자의 약 9%) | 응답이 1~2초만 늦어도 버튼을 다시 누르는 사용자가 생깁니다. 마감일처럼 몰리는 시간대라 평소보다 높게 잡았습니다 |
| 다른 계좌로 다시 청약 (`SECOND_ACCOUNT`) | 60건 (투자자의 약 7%) | 본인 명의 계좌가 여러 개인 투자자가 "한 계좌만 되나?" 하고 다른 계좌로 한 번 더 시도하는 경우입니다. 재전송보다는 드뭅니다 |
| (두 행동을 모두 함) | 위 80, 60건에 포함, 10명 | 재전송한 사람 중 일부가 다른 계좌로도 시도합니다 |

합계: 860 + 80 + 60 = 1,000건

배치
- 첫 청약은 투자자 번호 순서로 놓았습니다. 번호가 곧 접속 순서입니다
- 재전송 70%는 바로 다음 행에 둡니다. 같은 VU가 앞 요청의 응답을 받은 직후 다시 누르는 경우입니다
- 재전송 30%는 약 10행 뒤에 둡니다. 옆 VU가 거의 같은 시각에 보내므로, 다른 기기나 탭에서 동시에 다시 보내는 경우입니다
- 다른 계좌 청약 80%는 2~6행 뒤에 둡니다. 첫 청약 결과를 보고 잠시 뒤 계좌를 바꿔 다시 시도하는 경우입니다
- 다른 계좌 청약 20%는 약 10행 뒤에 둡니다. 두 계좌로 거의 동시에 보내는 경우입니다
- 끼워 넣은 행 때문에 "10행 뒤"는 정확히 10이 아니라 10 안팎이 될 수 있습니다. 순서 조건(첫 청약보다 뒤)은 항상 지켜집니다

투자자 유형은 공모주 청약자 구성을 가정해 직장인 40%, 전업투자자 15%, 자영업자 15%, 대학생 10%, 주부 10%, 은퇴자 10%의 가중치로 뽑았습니다. 유형은 투자자마다 하나이고, 같은 투자자의 모든 행에 같은 값이 들어갑니다.

## 코드

```python
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
```
