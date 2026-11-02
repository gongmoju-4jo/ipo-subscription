#!/usr/bin/env python3
"""S1 단계별 결과 표를 만든다 (표준 라이브러리만 사용).
단계별 지연, 실패율은 summary.json의 stage 태그 지표에서, 실제 도달 속도는 requests.log에서 읽는다.
s1.js와 같은 방식으로 단계별 요청 순번(idx) 범위를 계산해서, 단계에 속한 요청의 시각(초 단위 로그)으로 실제 속도를 구한다.

사용: python stage_table.py --run-id <run_id> [--stages 100,200,300,433,600,800] [--hold 90] [--ramp 10] [--warmup 60] [--results-dir results]
단계 값은 s1.js를 실행할 때 쓴 STAGES, HOLD, RAMP, WARMUP과 같아야 한다.
"""
import argparse, collections, datetime, json, math, os, re

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--stages", default="100,200,300,433,600,800")
    ap.add_argument("--hold", type=int, default=90)
    ap.add_argument("--ramp", type=int, default=10)
    ap.add_argument("--warmup", type=int, default=60)
    ap.add_argument("--results-dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "results"))
    a = ap.parse_args()
    steps = [int(x) for x in a.stages.split(",")]
    d = os.path.join(a.results_dir, a.run_id)
    m = json.load(open(os.path.join(d, "summary.json"), encoding="utf-8"))["metrics"]
    # 단계별 요청 순번 범위
    bounds, n, rate = [], 0, steps[0]
    def add(target, sec, tag):
        nonlocal n, rate
        # s1.js의 Math.round와 같게 .5는 올린다 (파이썬 round는 .5를 짝수로 맞춰서 경계가 어긋난다)
        lo = n; n += math.floor((rate + target) / 2 * sec + 0.5); rate = target; bounds.append((tag, lo, n))
    if a.warmup > 0: add(steps[0], a.warmup, "warmup")
    for s in steps:
        if a.ramp > 0: add(s, a.ramp, "ramp")
        add(s, a.hold, str(s))
    times = collections.defaultdict(list)  # 단계 -> 요청 시각(초)
    rx = re.compile(r'time="([^"]+)".*?\\"idx\\":(\d+)')
    for line in open(os.path.join(d, "requests.log"), encoding="utf-8"):
        mm = rx.search(line)
        if not mm: continue
        idx = int(mm.group(2))
        for tag, lo, hi in bounds:
            if lo <= idx < hi:
                t = datetime.datetime.fromisoformat(mm.group(1).replace("Z", "+00:00")).timestamp()
                times[tag].append(t); break
    dropped = int(m.get("dropped_iterations", {}).get("count", 0))
    if dropped > 0:
        # 밀린 반복이 있으면 요청 순번과 시각이 어긋나 단계 경계가 틀어질 수 있다. 무효 회차라는 표시를 맨 위에 남기고 표는 참고용으로 출력한다
        print("⚠️ 무효 회차: 밀린 반복(dropped_iterations) %d건. 단계 경계가 어긋날 수 있어 아래 표는 참고용이다.\n" % dropped)
    rows = ["| 단계(건/초) | 요청 수 | 실제 처리량(건/초) | 평균(ms) | p95(ms) | p99(ms) | 오류율 | 기준 통과(평균 500ms, 오류 1%) |", "|---|---|---|---|---|---|---|---|"]
    for s in steps:
        dur = m.get(f"http_req_duration{{stage:{s}}}"); fail = m.get(f"http_req_failed{{stage:{s}}}"); cnt = m.get(f"http_reqs{{stage:{s}}}")
        ts = times.get(str(s), [])
        span = (max(ts) - min(ts) + 1) if ts else 0
        thr = (not any(dur["thresholds"].values()) and not any(fail["thresholds"].values())) if dur and fail else None
        rows.append("| {} | {} | {} | {} | {} | {} | {} | {} |".format(
            s, cnt["count"] if cnt else "-", "%.0f" % (len(ts) / span) if span else "-",
            "%.0f" % dur["avg"] if dur else "-", "%.0f" % dur["p(95)"] if dur else "-", "%.0f" % dur["p(99)"] if dur else "-",
            "%.2f%%" % (fail["value"] * 100) if fail else "-", "통과" if thr else ("초과" if thr is False else "-")))
    print("\n".join(rows))
    print("\n밀린 반복(dropped_iterations): %s, check 실패: %s, 전체 요청: %s" % (
        m.get("dropped_iterations", {}).get("count", 0), m["checks"]["fails"], m["http_reqs"]["count"]))

if __name__ == "__main__":
    main()
