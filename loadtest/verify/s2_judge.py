#!/usr/bin/env python3
"""S2 마감 경계 한 회차 판정 (s2.sh가 부른다. 표준 라이브러리만 사용).

사용: python s2_judge.py --requests results/<run_id>/requests.log --summary results/<run_id>/summary.json
                         --sql-result results/<run_id>/sql_result.txt --ipo <대상 공모주 ID> --end-ms <마감 시각 epoch ms>
                         [--container ipo-postgres]

- 회차 유효성 (k6 로그와 요약을 본다): 밀린 요청이 없고, 401이 없고, 기대하지 않은 상태 코드가 없고,
  대상 요청에 202(마감 전 도착)와 422(마감 후 도착)가 모두 있고, 대상 공모주가 마감(ipo.closed)됐어야 한다
- 측정값: verify.sql 20~22번 결과 행 수(sql_result.txt), 202를 받았는데 청약 행이 없는 요청 수(k6 로그와 DB 대조)
- 종료 코드: 0 유효, 3 무효(이유를 출력), 1 입력을 읽지 못함(sql_result.txt에 20~22번이 없거나 건너뜀 등)
"""
import argparse
import collections
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_requests import load_rows  # noqa: E402

CONTAINER = "ipo-postgres"


# psql 한 번 실행. 테스트에서는 이 함수를 가짜로 바꾼다
def query(sql):
    out = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "postgres", "-d", "ipo", "-v", "ON_ERROR_STOP=1",
                          "-qtA", "-F", "|", "-c", sql], capture_output=True, text=True, encoding="utf-8", check=True).stdout
    return [line.split("|") for line in out.splitlines() if line.strip()]


def sql_counts(path):
    """sql_result.txt에서 검사 번호마다 결과 행 수를 읽는다. 건너뛴 검사는 "skip", 행 수가 없으면 None"""
    res, cur = {}, None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = re.match(r"== (\d+)\.", line)
            if m:
                cur = int(m.group(1))
                res[cur] = "skip" if "건너뜀" in line else None
                continue
            m = re.match(r"\((\d+) rows?\)", line.strip())
            if m and cur is not None and res.get(cur) is None:
                res[cur] = int(m.group(1))
    return res


def judge(requests_log, summary_path, sql_result, ipo, end_ms):
    """판정 결과 문자열 목록과 무효 이유 목록을 돌려준다. 입력을 읽지 못하면 ValueError"""
    rows = load_rows(requests_log)
    tgt = [r for r in rows if r.get("stage") == "target"]
    bg = [r for r in rows if r.get("stage") == "bg"]
    ts = collections.Counter(r["status"] for r in tgt)
    bs = collections.Counter(r["status"] for r in bg)
    unauth = sum(1 for r in rows if r["status"] == 401)
    ok202 = {r["requestId"] for r in tgt if r["status"] == 202}

    end_epoch = end_ms / 1000.0
    closed = query(f"select extract(epoch from created_at), payload::json->>'confirmedCount' from subscription.outbox "
                   f"where topic = 'ipo.closed' and aggregate_id = '{ipo}' order by created_at limit 1")
    close_epoch = float(closed[0][0]) if closed else None
    confirmed_at_close = int(closed[0][1]) if closed else None
    subs = {s[0] for s in query(f"select request_id from subscription.subscription where ipo_id = '{ipo}'")}
    missing = [r for r in ok202 if r not in subs]

    sc = sql_counts(sql_result)
    if close_epoch is not None:
        for n in (20, 21, 22):
            if not isinstance(sc.get(n), int):
                raise ValueError(f"sql_result.txt {n}번 결과를 읽지 못함: {sc.get(n)} (마감된 대상 공모주라 건너뛰면 안 된다)")

    reasons = []
    # 요청 순번으로 대상 구간을 정하므로(k6 iterationInTest는 보낸 요청만 센다), 밀린 요청이 있으면 대상 구간이 그만큼 뒤로 밀린다
    with open(summary_path, encoding="utf-8") as f:
        dropped = int(json.load(f)["metrics"].get("dropped_iterations", {}).get("count", 0))
    if dropped:
        reasons.append(f"밀린 요청 {dropped}건 (대상 구간이 계획보다 늦어짐)")
    if ts.get(202, 0) == 0 or ts.get(422, 0) == 0:
        reasons.append("대상 요청에 202와 422가 모두 있지 않음 (마감 시각이 대상 구간 밖)")
    if unauth:
        reasons.append(f"401 {unauth}건 (토큰 만료)")
    others = {s: n for s, n in (ts + bs).items() if s not in (202, 401, 409, 422)}
    if others:
        reasons.append(f"기대하지 않은 상태 코드 {dict(others)}")
    if close_epoch is None:
        reasons.append("ipo.closed Outbox 없음 (대상 공모주가 마감되지 않음)")

    out = ["# S2 마감 경계 판정",
           f"회차: {'유효' if not reasons else '무효'}" + ("" if not reasons else " (" + "; ".join(reasons) + ")"),
           "",
           f"대상 요청: {dict(sorted(ts.items()))} (202 마감 전 도착, 422 마감 후 도착, 409 마감 처리 뒤 도착)",
           f"배경 요청: {dict(sorted(bs.items()))}",
           f"마감 시각(대상 공모주 subscription_end_at): {end_epoch:.3f}"]
    if close_epoch is not None:
        out.append(f"마감 처리(ipo.closed Outbox 생성): {close_epoch:.3f} (마감 시각 + {close_epoch - end_epoch:.3f}초), 마감 때 확정 수 {confirmed_at_close}")
    out += [f"대상 청약 행: {len(subs)}건", "", "## 측정값 (기대: 모두 0)"]
    if close_epoch is not None:
        out += [f"마감 처리 뒤에 생긴 대상 청약 행 (verify.sql 20번, 202를 받고 마감 집계에서 빠진 청약): {sc[20]}",
                f"마감 시각 이후 도착인데 접수된 행 (21번): {sc[21]}",
                f"마감 때 확정 수와 지금 유효 청약 수가 다름 (22번, 다르면 1): {sc[22]}"]
    out.append(f"202를 받았는데 청약 행이 없음 (k6 로그 대조): {len(missing)}")
    return out, reasons


def main(argv=None):
    global CONTAINER
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--requests", required=True)
    ap.add_argument("--summary", required=True)
    ap.add_argument("--sql-result", required=True)
    ap.add_argument("--ipo", required=True)
    ap.add_argument("--end-ms", type=int, required=True)
    ap.add_argument("--container", default=CONTAINER)
    a = ap.parse_args(argv)
    CONTAINER = a.container
    try:
        out, reasons = judge(a.requests, a.summary, a.sql_result, a.ipo, a.end_ms)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as e:
        print(f"판정 입력을 읽지 못함: {e}", file=sys.stderr)
        return 1
    print("\n".join(out))
    return 0 if not reasons else 3


if __name__ == "__main__":
    sys.exit(main())
