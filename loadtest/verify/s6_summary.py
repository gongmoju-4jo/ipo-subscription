"""S6 여러 회차의 결과를 한 표로 모은다 (회차별 값, 중앙값, 최소~최대). 팀 규칙 "같은 조건 최소 3회, 대표값은 중앙값"용이고, 워밍업 유무 비교에도 쓴다.
사용: python loadtest/verify/s6_summary.py --runs S6-a-r1 S6-a-r2 S6-a-r3 [--results-dir loadtest/results]
읽는 파일 (회차 폴더): s6-judge.txt, sql_result.txt(9, 10번), summary.json(접수 API 응답 시간)
유효한 회차만 중앙값, 최소~최대에 넣고, 무효 회차는 표에 남기되 계산에서 뺀다. 읽지 못한 회차가 있으면 종료 코드 1
"""
import argparse
import json
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from s6_judge import first_row, sql_sections  # noqa: E402

# (열 이름, 키, 표시 형식). 숫자 열만 중앙값을 계산한다
COLUMNS = [("UP(초)", "up", "{:.1f}"), ("CONFIRMED", "confirmed", "{:.0f}"), ("TIMEOUT", "timeout", "{:.0f}"),
           ("반환 기록", "void", "{:.0f}"), ("랙 최댓값", "lag", "{:.0f}"), ("확정 p95(초)", "p95", "{:.1f}"),
           ("소진(초)", "drain", "{:.1f}"), ("API p95(ms)", "api_p95", "{:.1f}"), ("DLT 증가", "dlt", "{:.0f}")]


def _interval_sec(text):
    """psql interval "00:01:49.431"(시:분:초)를 초로"""
    m = re.match(r"(\d+):(\d+):(\d+(?:\.\d+)?)", text.strip())
    if not m:
        raise ValueError(f"시간 형식을 읽지 못함: {text}")
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))


def read_run(results_dir, run_id):
    """회차 한 개를 읽어 값 딕셔너리로. 필수 항목이 없으면 ValueError"""
    d = os.path.join(results_dir, run_id)
    with open(os.path.join(d, "s6-judge.txt"), encoding="utf-8") as f:
        j = f.read()

    def grab(pattern, what, required=True):
        m = re.search(pattern, j)
        if not m and required:
            raise ValueError(f"{run_id}: s6-judge.txt에서 {what}을 읽지 못함")
        return m

    r = {"run": run_id}
    r["valid"] = grab(r"회차: (유효|무효)", "회차")[1] == "유효"
    r["violation"] = grab(r"결과: (위반 없음|위반[^\n]*)", "결과")[1]
    w = grab(r"워밍업: ([^\n]+)", "워밍업", required=False)
    r["warm"] = w[1].strip() if w else "-"
    r["up"] = float(grab(r"종료 → UP\(healthy\): ([\d.]+)초", "종료→UP")[1])
    lag = grab(r"장애 중 인출 요청 랙 최댓값: (\d+|없음)", "랙 최댓값")[1]
    r["lag"] = float(lag) if lag.isdigit() else None
    m = grab(r"요청 (\d+)건: CONFIRMED (\d+)건, CANCELLED\(TIMEOUT\) (\d+)건", "요청 결과")
    r["total"], r["confirmed"], r["timeout"] = int(m[1]), float(m[2]), float(m[3])
    v = grab(r"선행 반환 기록\(margin_void_intent\): (\d+)건", "반환 기록", required=False)
    r["void"] = float(v[1]) if v else None
    dl = grab(r"DLT 증가: (\d+)건", "DLT 증가", required=False)   # 옛 결과, 또는 "기록하지 못함"이면 None
    r["dlt"] = float(dl[1]) if dl else None

    secs = sql_sections(os.path.join(d, "sql_result.txt"))
    row9 = first_row(secs["9"]) if "9" in secs and not secs["9"]["skip"] else None
    row10 = first_row(secs["10"]) if "10" in secs and not secs["10"]["skip"] else None
    if not row9 or len(row9) != 3 or not row10 or len(row10) != 3:
        raise ValueError(f"{run_id}: sql_result.txt에서 9번, 10번 결과를 읽지 못함")
    r["p95"] = float(row9[1])
    r["drain"] = _interval_sec(row10[2])

    with open(os.path.join(d, "summary.json"), encoding="utf-8") as f:
        dur = json.load(f)["metrics"]["http_req_duration"]
    r["api_p95"] = float(dur["p(95)"])
    return r


def _fmt(value, fmt):
    return "-" if value is None else fmt.format(value)


def render(runs):
    """마크다운 표 줄 목록 (회차별, 중앙값, 최소~최대)"""
    head = ["회차", "유효", "결과", "워밍업"] + [c[0] for c in COLUMNS]
    lines = ["| " + " | ".join(head) + " |", "|" + "|".join("---" for _ in head) + "|"]
    for r in runs:
        row = [r["run"], "유효" if r["valid"] else "무효", r["violation"], r["warm"]] + [_fmt(r[k], f) for _, k, f in COLUMNS]
        lines.append("| " + " | ".join(row) + " |")
    ok = [r for r in runs if r["valid"]]
    for label, fn in (("중앙값", statistics.median), ("최소~최대", None)):
        row = [label, f"{len(ok)}/{len(runs)}회", "", ""]
        for _, k, f in COLUMNS:
            vals = [r[k] for r in ok if r[k] is not None]
            if not vals:
                row.append("-")
            elif fn:
                row.append(f.format(fn(vals)))
            else:
                row.append(f"{f.format(min(vals))}~{f.format(max(vals))}")
        lines.append("| " + " | ".join(row) + " |")
    return lines


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--runs", nargs="+", required=True, help="회차 run_id 목록")
    ap.add_argument("--results-dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "results"))
    a = ap.parse_args(argv)
    runs = []
    for run_id in a.runs:
        try:
            runs.append(read_run(a.results_dir, run_id))
        except (ValueError, OSError, KeyError) as e:
            print(f"읽지 못함: {run_id} ({e})", file=sys.stderr)
            return 1
    print("\n".join(render(runs)))
    invalid = [r["run"] for r in runs if not r["valid"]]
    bad = [r["run"] for r in runs if r["valid"] and r["violation"] != "위반 없음"]
    if invalid:
        print(f"\n※ 무효 회차({', '.join(invalid)})는 중앙값, 최소~최대에서 뺐습니다.")
    if bad:
        print(f"※ 정합성 위반이 있는 회차: {', '.join(bad)}")
    print("※ 환경 한계: 로컬 단일 머신, Docker, 인스턴스 1개 (k6도 같은 머신). 실제 운영 수치가 아닌 시뮬레이션입니다.")
    # 워밍업 진행 여부(함, 안 함)만 비교한다. 워밍업이 정상 상태에 닿는 시간(204초, 312초 등)은 회차마다 달라도 같은 조건이다
    warm = {r["warm"].split(" (")[0] for r in runs}
    if len(warm) > 1:
        print(f"※ 워밍업 조건이 섞여 있습니다 ({', '.join(sorted(warm))}). 같은 조건끼리 묶어서 보세요.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
