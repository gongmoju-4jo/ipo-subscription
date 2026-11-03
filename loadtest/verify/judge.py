#!/usr/bin/env python3
"""official.sh가 남긴 manifest.csv로 S0, S1의 G1, G2를 판정한다 (표준 라이브러리만 사용, Python 3.8 이상, 부하가 끝난 뒤 실행).

사용: python judge.py results/<batch>/manifest.csv --p95-limit 15 --drain-limit 15 [--container ipo-postgres]

판정 기준 (팀 문서 1장, 8-2)
- G1 합격: 평균 500ms 이하, 오류율 1% 미만. S0은 처리량 200건/초 이상도 본다
  G1 목표: 평균 250ms 이하, p95 500ms 이하, 오류 0. S0은 처리량 400건/초 이상도 본다
  S1은 8-2의 한계 지점 정의(평균 500ms 또는 오류율 1%가 처음 깨지는 단계)대로 처리량을 기준에 넣지 않는다.
  대신 전달률(보낸 요청 수 / (목표 속도 x 유지 시간))을 같이 보여준다 (k6가 목표 속도를 다 못 보낸 경우를 구분하려고)
- G2: 접수→확정 p95 <= --p95-limit, 소진 시간(마지막 접수 이후 마지막 확정까지) <= --drain-limit
무효 (판정하지 않는다)
- S1에 dropped_iterations가 있으면 G1, G2 모두 무효 (요청을 정한 속도로 못 보냄)
- S0 요청 수가 manifest의 expected_requests(1,000)와 다르면 G1, G2 모두 무효 (S0은 per-vu-iterations라
  dropped_iterations가 생기지 않는다. 대신 maxDuration 2분 안에 다 못 보낸 회차를 요청 수로 걸러낸다)
- 확정 수가 요청 수와 다르면 G2 무효 (오류, 거절, 미처리가 섞인 값)
- S0 최종 판정은 measure 회차가 정확히 3개이고 모두 유효할 때만 그 중앙값으로 한다
"""
import argparse
import csv
import json
import os
import statistics
import subprocess
import sys

G1_PASS_AVG, G1_PASS_FAIL, G1_PASS_RATE = 500.0, 0.01, 200.0
G1_GOAL_AVG, G1_GOAL_P95, G1_GOAL_RATE = 250.0, 500.0, 400.0


def load_summary(results_dir, run):
    path = os.path.join(results_dir, run, "summary.json")
    with open(path, encoding="utf-8") as f:
        m = json.load(f)["metrics"]
    for k in ("http_reqs", "http_req_duration", "http_req_failed"):
        if k not in m:
            raise ValueError(f"{path}에 {k}가 없다")
    return {
        "requests": int(m["http_reqs"]["count"]),
        "rate": float(m["http_reqs"]["rate"]),
        "avg": float(m["http_req_duration"]["avg"]),
        "p95": float(m["http_req_duration"]["p(95)"]),
        "fail": float(m["http_req_failed"]["value"]),
        "dropped": int(m.get("dropped_iterations", {}).get("count", 0)),
    }


def query_g2(ipos, container):
    """공모주 목록의 확정 청약으로 접수→확정 p50/p95/max와 소진 시간을 구한다. 확정 0건이면 None"""
    for i in ipos:
        if len(i) != 36 or any(c not in "0123456789abcdefABCDEF-" for c in i):
            raise ValueError(f"공모주 ID 형식이 아니다: {i}")
    ins = ",".join(f"'{i}'" for i in ipos)
    sql = ("select count(*), "
           "percentile_cont(0.5) within group (order by extract(epoch from confirmed_at - received_at)), "
           "percentile_cont(0.95) within group (order by extract(epoch from confirmed_at - received_at)), "
           "max(extract(epoch from confirmed_at - received_at)), "
           "extract(epoch from max(confirmed_at) - max(received_at)) "
           f"from subscription.subscription where ipo_id in ({ins}) and status = 'CONFIRMED'")
    r = subprocess.run(["docker", "exec", "-i", container, "psql", "-U", "postgres", "-d", "ipo", "-v", "ON_ERROR_STOP=1",
                        "-qtA", "-F", ",", "-c", sql], capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise RuntimeError(f"psql 실패: {r.stderr.strip()}")
    p = r.stdout.strip().split(",")
    if len(p) != 5:
        raise RuntimeError(f"psql 출력 형식이 다르다: {r.stdout!r}")
    if int(p[0]) == 0:
        return None
    return {"confirmed": int(p[0]), "p50": float(p[1]), "p95": float(p[2]), "max": float(p[3]), "drain": float(p[4])}


def g1(s, scenario):
    """(합격, 목표) 판정. 무효면 ('무효', '무효')"""
    if scenario == "S1" and s["dropped"] > 0:
        return "무효", "무효"
    ok_pass = s["avg"] <= G1_PASS_AVG and s["fail"] < G1_PASS_FAIL
    ok_goal = s["avg"] <= G1_GOAL_AVG and s["p95"] <= G1_GOAL_P95 and s["fail"] == 0
    if scenario == "S0":
        ok_pass = ok_pass and s["rate"] >= G1_PASS_RATE
        ok_goal = ok_goal and s["rate"] >= G1_GOAL_RATE
    return ("통과" if ok_pass else "불합격"), ("통과" if ok_goal else "미달")


def g2(s, d, scenario, p95_limit, drain_limit):
    if scenario == "S1" and s["dropped"] > 0:
        return "무효(밀린 반복)"
    if d is None or d["confirmed"] != s["requests"]:
        return f"무효(확정 {d['confirmed'] if d else 0} / 요청 {s['requests']})"
    return "통과" if d["p95"] <= p95_limit and d["drain"] <= drain_limit else "불합격"


def fmt(x, digits=1):
    return "-" if x is None else f"{x:.{digits}f}"


def judge(manifest, p95_limit, drain_limit, query=query_g2, container="ipo-postgres", out=sys.stdout):
    results_dir = os.path.dirname(os.path.dirname(os.path.abspath(manifest)))
    with open(manifest, encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    need = {"run_id", "scenario", "phase", "target_rate", "ipo_ids"}
    if not rows or not need.issubset(rows[0].keys()):
        raise ValueError(f"manifest 형식이 다르다 (필요한 열: {sorted(need)})")
    w = lambda line="": print(line, file=out)

    w(f"G2 기준: 접수→확정 p95 {p95_limit}초 이하, 소진 시간 {drain_limit}초 이하")
    w()
    w("| run_id | 시나리오 | 구간 | 목표 속도 | 요청 수 | 처리량(건/초) | 전달률 | avg(ms) | p95(ms) | 오류율 | 밀린 반복 | G1 합격 | G1 목표 | 확정 수 | 접수→확정 p50 / p95 / max(초) | 소진(초) | G2 |")
    w("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    s0_measure, s1_rows = [], []
    for r in rows:
        scen, phase = r["scenario"], r["phase"]
        target = int(r["target_rate"]) if r["target_rate"] else None
        s = load_summary(results_dir, r["run_id"])
        ipos = [i for i in r["ipo_ids"].split(";") if i]
        if not ipos:
            raise ValueError(f"{r['run_id']}: 공모주 ID가 없다")
        d = query(ipos, container)
        gp, gg = g1(s, scen)
        v2 = g2(s, d, scen, p95_limit, drain_limit)
        expected = int(r["expected_requests"]) if r.get("expected_requests") else None
        if scen == "S0" and expected is not None and s["requests"] != expected:
            gp, gg, v2 = "무효", "무효", f"무효(요청 {s['requests']} / 예상 {expected})"
        shown = (gp, gg, v2) if phase != "warmup" else ("참고", "참고", "참고")
        # 전달률 = 보낸 요청 수 / (목표 속도 x 유지 시간). k6의 rate는 가상 사용자 준비 시간까지 포함한 전체 시간으로 나눈 값이라 짧은 실행에서 낮게 나온다
        dur = int(r["duration_s"]) if r.get("duration_s") else None
        delivered = f"{s['requests'] / (target * dur) * 100:.0f}%" if target and dur else "-"
        g2txt = f"{fmt(d['p50'], 2)} / {fmt(d['p95'], 2)} / {fmt(d['max'], 2)}" if d else "-"
        w(f"| {r['run_id']} | {scen} | {phase} | {target or '-'} | {s['requests']} | {s['rate']:.1f} | {delivered} | {s['avg']:.1f} | {s['p95']:.1f} | "
          f"{s['fail'] * 100:.2f}% | {s['dropped']} | {shown[0]} | {shown[1]} | {d['confirmed'] if d else 0} | {g2txt} | {fmt(d['drain'], 2) if d else '-'} | {shown[2]} |")
        if scen == "S0" and phase == "measure":
            s0_measure.append((s, d, gp, v2))
        if scen == "S1":
            s1_rows.append((target, s, d, gp, gg, v2))

    w()
    w("## S0 (measure 회차 중앙값)")
    valid = [x for x in s0_measure if x[2] != "무효" and not x[3].startswith("무효")]
    if len(s0_measure) != 3 or len(valid) != 3:
        w(f"판정하지 않음: measure 회차 {len(s0_measure)}개 중 유효 {len(valid)}개 (3개 모두 유효해야 한다)")
    else:
        med = statistics.median
        s = {k: med([x[0][k] for x in valid]) for k in ("rate", "avg", "p95", "fail")}
        s["dropped"] = 0
        gp, gg = g1(s, "S0")
        p95, drain = med([x[1]["p95"] for x in valid]), med([x[1]["drain"] for x in valid])
        v2 = "통과" if p95 <= p95_limit and drain <= drain_limit else "불합격"
        w(f"- G1: 처리량 {s['rate']:.1f}건/초, avg {s['avg']:.1f}ms, p95 {s['p95']:.1f}ms, 오류율 {s['fail'] * 100:.2f}% → 합격 {gp}, 목표 {gg}")
        w(f"- G2: 접수→확정 p95 {p95:.2f}초, 소진 {drain:.2f}초 → {v2}")

    w()
    w("## S1 (속도 단계별)")
    if not s1_rows:
        w("S1 실행 없음")
    else:
        limit = None
        for target, s, d, gp, gg, v2 in sorted(s1_rows, key=lambda x: x[0]):
            ok = gp == "통과" and v2 == "통과"
            w(f"- {target}건/초: G1 {gp} (목표 {gg}), G2 {v2}")
            if not ok and limit is None:
                limit = (target, gp, v2)
        if limit is None:
            w(f"- 한계 지점: 측정한 가장 높은 단계({max(x[0] for x in s1_rows)}건/초)까지 G1, G2 모두 통과 (한계 지점 미도달)")
        else:
            w(f"- 한계 지점: {limit[0]}건/초 (G1 {limit[1]}, G2 {limit[2]}). 무효가 원인이면 한계가 아니라 측정 실패다")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest")
    ap.add_argument("--p95-limit", type=float, required=True)
    ap.add_argument("--drain-limit", type=float, required=True)
    ap.add_argument("--container", default="ipo-postgres")
    a = ap.parse_args(argv)
    judge(a.manifest, a.p95_limit, a.drain_limit, container=a.container)


if __name__ == "__main__":
    main()
