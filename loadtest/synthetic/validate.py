#!/usr/bin/env python3
"""Synthetic Data 행동 CSV(S3, S4)를 검사하고 기대 결과를 계산한다 (표준 라이브러리만 사용).

사용:
  python loadtest/synthetic/validate.py s3                  # loadtest/synthetic/s3-behavior.csv 검사
  python loadtest/synthetic/validate.py s4 --csv <경로>      # 다른 CSV 검사
  python loadtest/synthetic/validate.py s3 --json           # 기대 결과만 JSON 한 줄로 출력

종료 코드: 0 통과, 1 규칙 위반(위반 내용을 모두 출력), 2 인자 오류 또는 파일을 읽지 못함.

규칙 (README.md, 각 프롬프트 원문과 같다)
- 공통: 요청 수(행 수) = --total, vuser는 0 이상 --users 미만의 정수, persona는 비어 있지 않음
- S3 (열 vuser,persona,action): action은 NORMAL, RESEND, SECOND_ACCOUNT. NORMAL은 투자자마다 정확히 1번이고 투자자 번호가 0부터 빈 번호 없이 이어짐.
  RESEND, SECOND_ACCOUNT는 투자자당 최대 1번, NORMAL이 있는 투자자만, 그 투자자의 NORMAL보다 뒤 행. 같은 투자자의 persona는 모든 행에서 같음
- S4 (열 vuser,persona,deposit): 투자자마다 정확히 1번. deposit은 1 이상의 정수이고 --amount 미만(매번 거절)이거나
  --amount x --runs 이상(매번 확정). 그 사이 금액은 회차마다 결과가 달라져 기대값을 정할 수 없으므로 위반

기대 결과 (expected)
- S3: 청약 행 = NORMAL + SECOND_ACCOUNT (재전송은 행이 생기지 않음), 확정 = NORMAL(투자자당 유효 청약 1건),
  거절 ALREADY_SUBSCRIBED = SECOND_ACCOUNT (두 요청 중 늦게 처리된 쪽), 증거금 = 확정
- S4: 청약 행 = 요청 수, 거절 INSUFFICIENT_BALANCE = deposit < --amount인 투자자, 확정 = 나머지, 증거금 = 확정
"""
import argparse
import csv
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
COLUMNS = {"S3": ["vuser", "persona", "action"], "S4": ["vuser", "persona", "deposit"]}
ACTIONS = ("NORMAL", "RESEND", "SECOND_ACCOUNT")
UINT = re.compile(r"^(0|[1-9][0-9]*)$")


def default_csv(scenario):
    return os.path.join(HERE, f"{scenario.lower()}-behavior.csv")


def load(path, scenario):
    """CSV를 읽어 행 목록을 돌려준다. 머리줄이 시나리오 열과 정확히 같지 않으면 ValueError."""
    with open(path, encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        header = next(reader, None)
        if header != COLUMNS[scenario]:
            raise ValueError(f"머리줄이 {','.join(COLUMNS[scenario])}가 아님: {header}")
        rows = []
        for n, cols in enumerate(reader, start=2):
            if len(cols) != len(header):
                raise ValueError(f"{n}줄: 열 수가 {len(header)}개가 아님: {cols}")
            rows.append(dict(zip(header, cols)))
    return rows


def _common(rows, total, users):
    errors = []
    if len(rows) != total:
        errors.append(f"요청 수(행 수) {len(rows)} ≠ {total}")
    for i, r in enumerate(rows):
        if not UINT.match(r["vuser"]):
            errors.append(f"{i}행: vuser가 0 이상의 정수가 아님: {r['vuser']!r}")
        elif int(r["vuser"]) >= users:
            errors.append(f"{i}행: vuser {r['vuser']}가 회원 수 {users} 이상")
        if not r["persona"].strip():
            errors.append(f"{i}행: persona가 비어 있음")
    return errors


def check_s3(rows, total=1000, users=1000):
    """(위반 목록, 기대 결과)를 돌려준다. 위반이 있으면 기대 결과는 None."""
    errors = _common(rows, total, users)
    first, seen, persona = {}, {}, {}
    for i, r in enumerate(rows):
        a = r["action"]
        if a not in ACTIONS:
            errors.append(f"{i}행: action이 {', '.join(ACTIONS)} 중 하나가 아님: {a!r}")
            continue
        if not UINT.match(r["vuser"]):
            continue
        v = int(r["vuser"])
        if persona.setdefault(v, r["persona"]) != r["persona"]:
            errors.append(f"{i}행: 투자자 {v}의 persona가 앞 행과 다름: {persona[v]!r} → {r['persona']!r}")
        if (v, a) in seen:
            errors.append(f"{i}행: 투자자 {v}의 {a}가 두 번째 (앞: {seen[(v, a)]}행)")
            continue
        seen[(v, a)] = i
        if a == "NORMAL":
            first[v] = i
        elif v not in first:
            errors.append(f"{i}행: 투자자 {v}의 {a}가 NORMAL보다 앞이거나 NORMAL이 없음")
    normal = sorted(first)
    if normal != list(range(len(normal))):
        missing = sorted(set(range(max(normal, default=-1) + 1)) - set(normal))
        errors.append(f"NORMAL 투자자 번호가 0부터 이어지지 않음 (빠진 번호 {missing[:10]}{' 외' if len(missing) > 10 else ''})")
    if errors:
        return errors, None
    n_normal = len(first)
    n_resend = sum(1 for r in rows if r["action"] == "RESEND")
    n_second = sum(1 for r in rows if r["action"] == "SECOND_ACCOUNT")
    return [], {
        "scenario": "S3", "requests": len(rows), "subscription_rows": n_normal + n_second,
        "confirmed": n_normal, "rejected": {"ALREADY_SUBSCRIBED": n_second}, "margins": n_normal,
        "detail": {"normal": n_normal, "resend": n_resend, "second_account": n_second,
                   "investors": n_normal, "second_account_vusers": sorted(int(r["vuser"]) for r in rows if r["action"] == "SECOND_ACCOUNT")},
    }


def check_s4(rows, total=1000, users=1000, amount=150_000, runs=4):
    """(위반 목록, 기대 결과)를 돌려준다. 위반이 있으면 기대 결과는 None."""
    errors = _common(rows, total, users)
    seen = {}
    short = []
    enough = amount * runs
    for i, r in enumerate(rows):
        if UINT.match(r["vuser"]):
            v = int(r["vuser"])
            if v in seen:
                errors.append(f"{i}행: 투자자 {v}가 두 번째 (앞: {seen[v]}행)")
            seen.setdefault(v, i)
        if not UINT.match(r["deposit"]) or int(r["deposit"]) < 1:
            errors.append(f"{i}행: deposit이 1 이상의 정수가 아님: {r['deposit']!r}")
            continue
        d = int(r["deposit"])
        if amount <= d < enough:
            errors.append(f"{i}행: deposit {d}원이 {amount}원 이상 {enough}원 미만 (회차마다 결과가 달라짐)")
        elif d < amount:
            short.append(int(r["vuser"]) if UINT.match(r["vuser"]) else r["vuser"])
    if errors:
        return errors, None
    return [], {
        "scenario": "S4", "requests": len(rows), "subscription_rows": len(rows),
        "confirmed": len(rows) - len(short), "rejected": {"INSUFFICIENT_BALANCE": len(short)}, "margins": len(rows) - len(short),
        "detail": {"amount": amount, "runs": runs, "short_vusers": sorted(short),
                   "deposits": {r["vuser"]: int(r["deposit"]) for r in rows}},
    }


def check(scenario, path=None, total=1000, users=1000, amount=150_000, runs=4):
    """시나리오와 CSV 경로로 검사한다. 파일, 머리줄 오류는 예외(OSError, ValueError)로 올린다."""
    scenario = scenario.upper()
    if scenario not in COLUMNS:
        raise ValueError(f"시나리오는 S3, S4 중 하나: {scenario}")
    rows = load(path or default_csv(scenario), scenario)
    if scenario == "S3":
        return check_s3(rows, total, users)
    return check_s4(rows, total, users, amount, runs)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scenario", type=str.upper, choices=["S3", "S4"])
    ap.add_argument("--csv", help="행동 CSV 경로 (기본 loadtest/synthetic/<시나리오>-behavior.csv)")
    ap.add_argument("--total", type=int, default=1000, help="요청 수 (k6 VUS x ITERATIONS)")
    ap.add_argument("--users", type=int, default=1000, help="회원 CSV의 회원 수 (vuser 상한)")
    ap.add_argument("--amount", type=int, default=150_000, help="S4: 청약 1건 금액 (공모가 x 수량)")
    ap.add_argument("--runs", type=int, default=4, help="S4: 같은 회원으로 청약하는 횟수 (경로 예열 1 + 측정 3)")
    ap.add_argument("--json", action="store_true", help="통과하면 기대 결과를 JSON 한 줄로 출력 (detail 제외)")
    args = ap.parse_args(argv)
    for name in ("total", "users", "amount", "runs"):
        if getattr(args, name) < 1:
            print(f"--{name}는 1 이상: {getattr(args, name)}", file=sys.stderr)
            return 2
    path = args.csv or default_csv(args.scenario)
    try:
        errors, expected = check(args.scenario, path, args.total, args.users, args.amount, args.runs)
    except (OSError, ValueError) as e:
        print(f"읽기 실패: {path}: {e}", file=sys.stderr)
        return 2
    if errors:
        print(f"위반 {len(errors)}건: {path}", file=sys.stderr)
        for e in errors:
            print("  - " + e, file=sys.stderr)
        return 1
    summary = {k: v for k, v in expected.items() if k != "detail"}
    if args.json:
        print(json.dumps(summary, ensure_ascii=False))
        return 0
    print(f"통과: {path}")
    print(f"  요청 {summary['requests']}건 → 청약 행 {summary['subscription_rows']}, 확정 {summary['confirmed']}, "
          f"거절 {summary['rejected']}, 증거금 {summary['margins']}")
    d = expected["detail"]
    if args.scenario == "S3":
        print(f"  첫 청약 {d['normal']}, 재전송 {d['resend']}, 다른 계좌 청약 {d['second_account']}")
    else:
        print(f"  예수금 부족 {len(d['short_vusers'])}명 ({d['amount']}원 미만), 나머지는 {d['amount'] * d['runs']}원 이상")
    return 0


if __name__ == "__main__":
    sys.exit(main())
