#!/usr/bin/env python3
"""k6가 남긴 요청 로그(requests.log)를 분석한다 (표준 라이브러리만 사용).

사용: python check_requests.py results/<run_id>/requests.log [--expect-requests 1000]

- 결과 분류: 시스템 성공(202) / 마감 거절(422) / 그 밖의 4xx / 시스템 오류(5xx) / 타임아웃, 연결 실패(상태 0)
- 같은 requestId로 여러 번 응답이 온 요청(S3 재전송)은 subscriptionId가 모두 같아야 한다. 다르면 종료 코드 2
- --expect-requests를 주면 요청 수가 그 값과 같은지도 확인한다
"""
import argparse
import collections
import json
import sys


def parse(line):
    start, end = line.find("{"), line.rfind("}")
    if start < 0 or end < start:
        return None
    text = line[start:end + 1]
    for candidate in (text, text.replace('\\"', '"')):
        try:
            d = json.loads(candidate)
            if isinstance(d, dict) and "requestId" in d:
                return d
        except ValueError:
            continue
    return None


def load_rows(path):
    rows = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            d = parse(line)
            if d:
                rows.append(d)
    return rows


def classify(r):
    s = r["status"]
    if s == 202: return "시스템 성공 (202)"
    if s == 422 and r.get("code") == "SUBSCRIPTION_CLOSED": return "업무 거절: 마감 (422)"
    if 400 <= s < 500: return "그 밖의 4xx"
    if s >= 500: return "시스템 오류: 5xx"
    return "시스템 오류: 타임아웃, 연결 실패 (상태 0)"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log")
    ap.add_argument("--expect-requests", type=int)
    args = ap.parse_args()

    rows = load_rows(args.log)
    total = len(rows)
    by_status = collections.Counter(r["status"] for r in rows)
    by_code = collections.Counter((r["status"], r.get("code") or "-") for r in rows)

    by_class = collections.Counter(classify(r) for r in rows)

    print(f"요청 수: {total}")
    print("상태 코드별:", dict(sorted(by_status.items())))
    print("상태 코드, 오류 코드별:", {f"{s} {c}": n for (s, c), n in sorted(by_code.items())})
    print("결과 분류:")
    for k, v in by_class.most_common():
        print(f"  {k}: {v}")
    print(f"  합계 = {sum(by_class.values())} (요청 수와 같아야 한다)")

    ids = collections.defaultdict(list)
    for r in rows:
        ids[r["requestId"]].append(r.get("subscriptionId") or "")
    repeated = {k: v for k, v in ids.items() if len(v) > 1}
    mismatch = {k: v for k, v in repeated.items() if len(set(v)) > 1}
    print(f"같은 requestId로 여러 번 온 요청: {len(repeated)}건, subscriptionId가 다른 것: {len(mismatch)}건")

    ok = True
    if args.expect_requests is not None and total != args.expect_requests:
        print(f"❌ 요청 수가 기대({args.expect_requests})와 다릅니다: {total}")
        ok = False
    if mismatch:
        print("❌ 재전송 응답의 subscriptionId가 원래 요청과 다릅니다")
        ok = False
    sys.exit(0 if ok else 2)


if __name__ == "__main__":
    main()
