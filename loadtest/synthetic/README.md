# Synthetic Data (S3, S4)

실제 사용 패턴을 흉내 낸 가상 사용자 행동 데이터입니다. LLM에 시나리오를 주고 만든 행동 CSV를 시드, k6, 판정이 함께 읽습니다. 일반 부하 테스트(S0, S1)는 균일한 요청으로 두고, 이 데이터로 돌린 S3, S4 결과를 S0과 비교합니다.

## 파일

| 파일 | 내용 |
| --- | --- |
| `s3-prompt.md`, `s4-prompt.md` | LLM에 준 프롬프트 원문 |
| `s3-response.md`, `s4-response.md` | LLM 응답 원문 (비율, 분포를 정한 이유와 생성 코드). 손대지 않음 |
| `s3-generate.py`, `s4-generate.py` | 응답의 생성 코드를 그대로 옮긴 것 (`tests/test_synthetic.py`가 응답 원문과 같은지 확인) |
| `s3-behavior.csv`, `s4-behavior.csv` | 생성 코드로 만든 행동 CSV. 검증을 통과한 고정본이라 다시 생성하지 않음 |
| `validate.py` | 행동 CSV 규칙 검사와 기대 결과 계산 |

행동 CSV에는 투자자 번호, 유형, 행동(또는 입금액)만 있고 회원 ID, 계좌 ID, 토큰은 없습니다. 실제 값은 시드가 만든 회원 CSV(`loadtest/data/`, 커밋하지 않음)에 있고, k6가 투자자 번호(`vuser`) = 회원 CSV의 행 번호로 이어 붙입니다.

## 생성 방법

- LLM: Claude (Opus 5.5), Claude Code 세션, 2026-11-05
- 1,000행의 개수, 순서 조건을 정확히 맞추려고 프롬프트에서 CSV를 만드는 Python 코드(표준 라이브러리, 고정 시드)를 함께 요청했습니다. 비율, 분포, 배치 규칙은 LLM이 정하고, 행은 그 코드가 만듭니다
- 생성 코드를 실행한 Python: 3.10.11. `tests/test_synthetic.py`가 생성 코드를 다시 돌려 커밋한 CSV와 바이트 단위로 같은지 확인합니다
- 행동 CSV는 줄 끝이 CRLF입니다 (Python `csv.writer` 기본값). OS마다 git이 개행을 바꾸지 않도록 `.gitattributes`에서 `-text`로 둡니다

```bash
cd loadtest/synthetic
python s3-generate.py && python s4-generate.py     # 다시 만들 때만 (결과는 커밋한 CSV와 같아야 한다)
python validate.py s3 && python validate.py s4     # 규칙 검사와 기대 결과
```

## 데이터 요약

| 시나리오 | 구성 (요청 1,000건) | 청약 행 | 확정 | 거절 | 증거금 |
| --- | --- | --- | --- | --- | --- |
| S3 | 첫 청약 860 (투자자 860명) + 같은 `requestId` 재전송 80 + 다른 계좌 청약 60 (10명은 둘 다) | 920 | 860 | 60 `ALREADY_SUBSCRIBED` | 860 |
| S4 | 투자자 1,000명 x 1건, 그중 97명은 예수금 150,000원 미만 | 1,000 | 903 | 97 `INSUFFICIENT_BALANCE` | 903 |

- 값은 `python validate.py s3`, `python validate.py s4`의 출력입니다
- S3 배치: 재전송 70%는 바로 다음 요청(같은 VU가 응답을 받은 뒤), 30%는 10행 안팎 뒤(옆 VU가 거의 같은 시각). 다른 계좌 청약 80%는 2~6행 뒤, 20%는 10행 안팎 뒤
- S4 금액: 부족 97명은 1,000~149,000원 (입금을 잊음 38, 일부만 입금 31, 필요 금액 착각 28), 나머지는 1,000,000원 이상 (중앙값 약 5,000,000원). 부족한 투자자는 요청 순서 전체에 흩어져 있음 (100행마다 4~14명)
- 비율, 분포는 실제 통계가 아니라 시나리오를 가정해 LLM이 정한 값입니다

## 규칙 (`validate.py`)

- 공통: 행 수 = 요청 수(1,000), `vuser`는 0 이상 회원 수 미만의 정수, `persona`는 비어 있지 않음
- S3 (`vuser,persona,action`)
  - `action`은 `NORMAL`, `RESEND`, `SECOND_ACCOUNT`
  - `NORMAL`은 투자자마다 정확히 1번, 투자자 번호는 0부터 빈 번호 없이
  - `RESEND`, `SECOND_ACCOUNT`는 투자자당 최대 1번, `NORMAL`이 있는 투자자만, 그 `NORMAL`보다 뒤 행
  - 같은 투자자의 `persona`는 모든 행에서 같음
- S4 (`vuser,persona,deposit`)
  - 투자자마다 정확히 1번
  - `deposit`은 1 이상의 정수이고, 청약 금액(150,000원) 미만이거나 청약 금액 x 반복 횟수(4회, 600,000원) 이상. 그 사이 금액은 회차마다 결과가 달라져 기대값을 정할 수 없으므로 위반
  - 반복 횟수 4 = 경로 예열 1회 + 측정 3회 (같은 회원이 종목만 바꿔 4번 청약하고, 예수금은 처음 한 번만 넣음)

## 기대 결과 계산

- S3: 청약 행 = `NORMAL` + `SECOND_ACCOUNT` (재전송은 행이 생기지 않음), 확정 = `NORMAL` (투자자당 유효 청약 1건), 거절 `ALREADY_SUBSCRIBED` = `SECOND_ACCOUNT` (두 요청 중 늦게 처리된 쪽), 증거금 = 확정
- S4: 청약 행 = 요청 수, 거절 `INSUFFICIENT_BALANCE` = 예수금이 청약 금액 미만인 투자자, 확정 = 나머지, 증거금 = 확정
- HTTP는 S3, S4 모두 202라 k6 check로는 거절을 확인할 수 없습니다. 판정은 DB의 상태, 사유별 건수로 합니다
