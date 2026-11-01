#!/usr/bin/env python3
"""부하 테스트용 회원, 계좌, 예수금, 로그인 토큰을 만들어 CSV로 저장한다 (표준 라이브러리만 사용).

사용 예 (Gateway 경유):
  python seed_users.py --run-id r1                       # S0, S1, S2용 회원 1,000명
  python seed_users.py --run-id r1 --second-account-count 100   # S3용: 100번~199번 회원에게 두 번째 계좌
  python seed_users.py --run-id r1 --prefix lb --low-balance-ratio 0.1 --out ../data/users-s4.csv   # S4용: 10% 잔액 부족
  python seed_users.py --run-id r1 --refresh-tokens      # 토큰만 다시 발급 (토큰은 1시간 만료)

- 회원은 회차마다 새로 만들지 않고 재사용한다 (삭제하지 않는 규칙). 가입, 계좌 개설은 이미 있으면 그대로 쓴다.
- 예수금은 회차(--run-id)마다 한 번 입금한다. 같은 run-id로 다시 실행해도 중복 입금되지 않는다 (requestId 고정).
- CSV 열: loginId,userId,accountId,accountId2,token (비밀번호가 아니라 토큰이 들어 있으므로 커밋하지 않는다)
"""
import argparse
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

NS = uuid.UUID("6f1b6c5e-0000-4000-8000-0000000000a1")  # 고정 requestId 생성용 네임스페이스


def call(base, method, path, body=None, token=None, retries=4):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    for attempt in range(retries):
        req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                payload = json.loads(raw) if raw else {}
            except ValueError:
                payload = {}
            if e.code == 503 and attempt < retries - 1:  # Gateway가 서비스를 아직 못 찾는 경우
                time.sleep(2 + attempt * 2)
                continue
            return e.code, payload
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            if attempt == retries - 1:
                raise
            time.sleep(1 + attempt)
    raise RuntimeError("unreachable")


def rid(*parts):
    return str(uuid.uuid5(NS, ":".join(parts)))


def seed_one(args, idx):
    base, password = args.base_url, args.password
    login_id = f"{args.prefix}{idx:04d}"
    low = idx >= args.users - int(round(args.users * args.low_balance_ratio))
    second = args.second_account_from <= idx < args.second_account_from + args.second_account_count

    if not args.refresh_tokens:
        s, _ = call(base, "POST", "/api/v1/users", {"loginId": login_id, "password": password, "name": "loadtest"})
        if s not in (201, 409):
            raise RuntimeError(f"{login_id} 가입 실패: {s}")
    s, j = call(base, "POST", "/api/v1/auth/login", {"loginId": login_id, "password": password})
    if s != 200:
        raise RuntimeError(f"{login_id} 로그인 실패: {s}")
    token = j["data"]["accessToken"]

    if args.refresh_tokens:
        return {"loginId": login_id, "token": token}

    accounts = []
    for kind in (["account"] + (["account2"] if second else [])):
        s, j = call(base, "POST", "/api/v1/accounts", {"requestId": rid(login_id, kind)}, token)
        if s not in (200, 201):
            raise RuntimeError(f"{login_id} {kind} 개설 실패: {s}")
        d = j["data"]
        accounts.append((d.get("accountId") or d.get("id"), d["userId"]))
        amount = args.low_amount if low else args.amount
        s, _ = call(base, "POST", f"/api/v1/accounts/{accounts[-1][0]}/deposits",
                    {"requestId": rid(login_id, kind, args.run_id), "amount": amount}, token)
        if s not in (200, 201):
            raise RuntimeError(f"{login_id} {kind} 입금 실패: {s}")
    return {"loginId": login_id, "userId": accounts[0][1], "accountId": accounts[0][0],
            "accountId2": accounts[1][0] if len(accounts) > 1 else "", "token": token}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    ap.add_argument("--run-id", required=True, help="회차 이름. 예수금 입금의 requestId에 쓰인다")
    ap.add_argument("--users", type=int, default=1000)
    ap.add_argument("--prefix", default="lt", help="loginId 접두사. 시나리오 묶음(cohort)마다 다르게 쓴다")
    ap.add_argument("--password", default=os.environ.get("LOADTEST_PASSWORD", "loadtest1234"))
    ap.add_argument("--amount", type=int, default=1_000_000, help="회원당 입금액 (청약 수량 x 공모가 이상이어야 한다)")
    ap.add_argument("--low-balance-ratio", type=float, default=0.0, help="S4: 뒤쪽 이 비율의 회원은 잔액을 부족하게 입금")
    ap.add_argument("--low-amount", type=int, default=1_000)
    ap.add_argument("--second-account-from", type=int, default=100)
    ap.add_argument("--second-account-count", type=int, default=0, help="S3: 이 수만큼의 회원에게 두 번째 계좌")
    ap.add_argument("--threads", type=int, default=16)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "users.csv"))
    ap.add_argument("--refresh-tokens", action="store_true", help="토큰만 다시 발급해 기존 CSV를 갱신")
    args = ap.parse_args()

    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=args.threads) as ex:
        results = list(ex.map(lambda i: seed_one(args, i), range(args.users)))

    if args.refresh_tokens:
        with open(out, encoding="utf-8", newline="") as f:
            rows = {r["loginId"]: r for r in csv.DictReader(f)}
        for r in results:
            rows[r["loginId"]]["token"] = r["token"]
        results = [rows[r["loginId"]] for r in results]

    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["loginId", "userId", "accountId", "accountId2", "token"])
        w.writeheader()
        w.writerows(results)
    n2 = sum(1 for r in results if r.get("accountId2"))
    print(f"완료: 회원 {len(results)}명, 두 번째 계좌 {n2}개, {time.time() - t0:.1f}초 → {out}")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        print("실패:", e, file=sys.stderr)
        sys.exit(1)
