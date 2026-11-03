#!/usr/bin/env python3
"""official.sh가 남긴 manifest.csv로 S0, S1의 G1, G2를 판정한다 (표준 라이브러리만 사용, 부하가 끝난 뒤 실행).

사용: python judge.py results/<batch>/manifest.csv --p95-limit 10 --drain-limit 10 [--container ipo-postgres]

G1 (1장): 합격 = 처리량 200건/초 이상, 평균 500ms 이하, 오류율 1% 미만 / 목표 = 처리량 400건/초 이상, 평균 250ms 이하, p95 500ms 이하, 오류 0
          처리량 기준은 목표 속도가 그 기준 이상인 S1 단계에만 건다 (100건/초 단계는 200건/초를 낼 수 없다). dropped_iterations가 있으면 무효.
G2 (1장): 접수→확정 p95, 소진 시간(마지막 접수 이후 마지막 확정까지) <= 기준(--p95-limit, --drain-limit)
          확정 수가 요청 수와 다르면 무효 (거절, 미처리가 섞인 값으로 판정하지 않는다)
S0 판정값은 구분이 '측정'인 회차(r1~r3)의 중앙값이다. 워밍업 회차는 값만 보여준다.
"""
import argparse
import csv
import json
import os
import statistics
import subprocess
import sys


def summary(results_dir, run):
    m = json.load(open(os.path.join(results_dir, run, "summary.json"), encoding="utf-8"))["metrics"]
    g = lambda k, f, d=0: m.get(k, {}).get(f, d)
    return {"requests": int(g("http_reqs", "count")), "rate": g("http_reqs", "rate"), "avg": g("http_req_duration", "avg"),
            "p95": g("http_req_duration", "p(95)"), "fail": g("http_req_failed", "value"), "dropped": int(g("dropped_iterations", "count"))}


def g2(container, ipos):
    ins = ",".join(f"'{i}'" for i in ipos)
    sql = ("select count(*), "
           "round((percentile_cont(0.5) within group (order by extract(epoch from confirmed_at - received_at)))::numeric, 2), "
           "round((percentile_cont(0.95) within group (order by extract(epoch from confirmed_at - received_at)))::numeric, 2), "
           "round(max(extract(epoch from confirmed_at - received_at))::numeric, 2), "
           "round(extract(epoch from max(confirmed_at) - max(received_at))::numeric, 2) "
           f"from subscription.subscription where ipo_id in ({ins}) and status = 'CONFIRMED'")
    r = subprocess.run(["docker", "exec", "-i", container, "psql", "-U", "postgres", "-d", "ipo", "-qtA", "-F", ",", "-c", sql],
                       capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        sys.exit(f"psql 실패: {r.stderr.strip()}")
    p = r.stdout.strip().split(",")
    return {"confirmed": int(p[0]), "p50": float(p[1]), "p95": float(p[2]), "max": float(p[3]), "drain": float(p[4])} if p[0] != "0" else None


def g1_verdict(s, target):
    if s["dropped"] > 0:
        return "무효(밀린 반복)", "무효"
    ok_pass = s["avg"] <= 500 and s["fail"] < 0.01 and (target < 200 or s["rate"] >= 200)
    ok_goal = s["avg"] <= 250 and s["p95"] <= 500 and s["fail"] == 0 and (target < 400 or s["rate"] >= 400)
    return ("통과" if ok_pass else "불합격"), ("통과" if ok_goal else "미달")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest")
    ap.add_argument("--p95-limit", type=float, required=True)
    ap.add_argument("--drain-limit", type=float, required=True)
    ap.add_argument("--container", default="ipo-postgres")
    a = ap.parse_args()
    results_dir = os.path.dirname(os.path.dirname(os.path.abspath(a.manifest)))
    rows = list(csv.DictReader(open(a.manifest, encoding="utf-8")))

    print("| run_id | 구분 | 목표속도 | 요청 수 | 처리량(건/초) | avg(ms) | p95(ms) | 오류율 | G1 합격 | G1 목표 | 확정 수 | 접수→확정 p50/p95/max(초) | 소진(초) | G2 |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    s0 = []
    for r in rows:
        target = int(r["목표속도"]) if r["목표속도"] else 0
        s = summary(results_dir, r["run_id"]); d = g2(a.container, r["공모주"].split(";"))
        gp, gg = g1_verdict(s, target)
        if d is None or d["confirmed"] != s["requests"]:
            g2v = f"무효(확정 {d['confirmed'] if d else 0} / 요청 {s['requests']})"; ds = ("-", "-", "-", "-", "-")
        else:
            g2v = "통과" if d["p95"] <= a.p95_limit and d["drain"] <= a.drain_limit else "불합격"
            ds = (d["confirmed"], f"{d['p50']} / {d['p95']} / {d['max']}", d["drain"], "", "")
        if r["구분"] == "워밍업":
            gp, gg, g2v = "참고", "참고", "참고"
        print(f"| {r['run_id']} | {r['구분']} | {target or '-'} | {s['requests']} | {s['rate']:.1f} | {s['avg']:.1f} | {s['p95']:.1f} | {s['fail']*100:.2f}% | {gp} | {gg} | {ds[0]} | {ds[1]} | {ds[2]} | {g2v} |")
        if r["구분"] == "측정" and d and d["confirmed"] == s["requests"]:
            s0.append((s, d))
    print(f"\nG2 기준: 접수→확정 p95 {a.p95_limit}초 이하, 소진 {a.drain_limit}초 이하")
    if len(s0) == 3:
        med = lambda xs: statistics.median(xs)
        s = {k: med([x[0][k] for x in s0]) for k in ("rate", "avg", "p95", "fail", "dropped")}
        d = {k: med([x[1][k] for x in s0]) for k in ("p95", "drain")}
        gp, gg = g1_verdict(s, 0)
        g2v = "통과" if d["p95"] <= a.p95_limit and d["drain"] <= a.drain_limit else "불합격"
        print(f"\nS0 측정 r1~r3 중앙값: 처리량 {s['rate']:.1f}건/초, avg {s['avg']:.1f}ms, p95 {s['p95']:.1f}ms → G1 합격 {gp} / 목표 {gg}")
        print(f"S0 측정 r1~r3 중앙값: 접수→확정 p95 {d['p95']}초, 소진 {d['drain']}초 → G2 {g2v}")
    else:
        print("\nS0 측정 회차(r1~r3)가 유효한 값 3개가 아니라 중앙값 판정을 하지 않는다")
    print("S1 판정은 위 표의 단계(S1 행)별 값이다. 한계 지점 = G1 합격 또는 G2가 처음 깨지는 단계.")


if __name__ == "__main__":
    main()
