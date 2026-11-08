"""G2 대량 환불 212만 건 소요 시간 추정 (g2_run.sh 결과 → 추정.md)

사용: python loadtest/verify/g2_estimate.py <g2_run.sh 결과 폴더>... [--out 추정.md] [--target 2122367]
  결과 폴더: loadtest/results/g2-<batch>/ (manifest.csv와 회차 폴더의 result.txt를 읽는다). 1천, 1만, 10만 배치를 모두 준다

추정 규칙 (측정 전에 고정. 바꾸지 않는다)
  1. 1천 건은 추세에서 빼고 참고값으로만 보인다
  2. 1만 → 10만 두 점의 처리량 비율 r = 처리량(10만) / 처리량(1만)만 본다. 곡선을 맞추지 않는다
  3. 0.9 <= r <= 1.1: 선형 외삽(두 점을 지나는 직선 T = a·N + b)을 대표값으로 쓴다
  4. r < 0.9: 범위. 낙관 = 212만 / 처리량(10만), 비관 = 212만 / (처리량(10만) x r^k), k = log10(212만 / 10만) ≈ 1.33
  5. r > 1.1: 오르는 추세는 잇지 않고 낙관 값(10만 처리량 유지)을 대표값으로 쓴다
  6. 구간별로 계산하고 합계는 구간 값의 합. 구간은 겹치지 않게 나눈다 (배정 결과 발행은 정산과 겹쳐서 따로 더하지 않는다)
       배정 대기 wait_s / 배정 계산, 저장 alloc_s / 배정 끝 → 첫 정산 (first_settle_s - wait_s - alloc_s) / 정산 settle_s
       → 합 = T1 - T0 (total_s). 환불 완료 표시 mark_s(T1 → T2)는 같은 규칙으로 따로 계산해 참고로 보인다 (판정은 T1 기준)
  7. 10만 건이 측정 불가(VALID 회차 없음, UNMEASURABLE 있음)면 그 케이스는 외삽하지 않는다 ("측정 불가 + 병목")
  8. 모든 값은 "추정"이다
대표값: 같은 조건(케이스, 건수)의 VALID이고 정합성 PASS인 회차들의 구간별 중앙값. 3회 미만이면 참고값으로 표시
판정: 합격선 3시간, 목표 1.5시간 (임의 기준). 범위면 비관(긴 쪽)이 선 안 → 합격 / 낙관(짧은 쪽)만 안 → 조건부 / 둘 다 밖 → 불합격
종료 코드: 0 추정 표를 만듦 (판정 결과와 관계없음) / 1 입력 오류
"""
import argparse
import csv
import math
import os
import statistics
import sys

TARGET = 2_122_367          # LG에너지솔루션 공모 KB증권 청약 건수 (DART)
LOW, HIGH, REF = 10_000, 100_000, 1_000
PASS_S, GOAL_S = 3 * 3600, 1.5 * 3600
R_LOW, R_HIGH = 0.9, 1.1
EPS = 1e-9
SEGMENTS = [  # (키, 이름)
    ("wait", "배정 대기 (T0 → 배정 시작)"),
    ("alloc", "배정 계산, 저장"),
    ("gap", "배정 끝 → 첫 정산 (발행 간격, Kafka 전달)"),
    ("settle", "정산, 환불 (첫 정산 → 마지막 정산)"),
]
MARK = ("mark", "환불 완료 표시 (T1 → T2, 참고)")


def parse_kv(line):
    out = {}
    for tok in line.split():
        if "=" in tok:
            k, v = tok.split("=", 1)
            out[k] = v
    return out


def num(d, k):
    v = d.get(k)
    if v in (None, "", "-"):
        raise ValueError(f"{k} 값이 없다")
    return float(v)


def segments_of(m):
    """result.txt의 M 줄(dict) → 구간 초 dict. 값이 없으면 ValueError"""
    wait, alloc, first, settle, mark = (num(m, k) for k in ("wait_s", "alloc_s", "first_settle_s", "settle_s", "mark_s"))
    # DB 시각이 밀리초라 빼기로 만든 구간도 밀리초로 반올림한다 (0이 1e-16 같은 양수로 남아 처리량이 터무니없이 커지지 않게)
    return {"wait": wait, "alloc": alloc, "gap": round(first - wait - alloc, 3), "settle": settle, "mark": mark, "total": num(m, "total_s")}


def load(batch_dirs):
    """manifest.csv와 result.txt를 읽어 회차 목록을 돌려준다. 회차: dict(case, n, status, integrity, seg, src)"""
    runs = []
    for d in batch_dirs:
        mf = os.path.join(d, "manifest.csv")
        if not os.path.isfile(mf):
            raise ValueError(f"manifest.csv 없음: {d}")
        with open(mf, encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                run = {"case": row["case"], "n": int(row["n"]), "status": row["status"], "integrity": row["integrity"],
                       "seg": None, "src": f"{os.path.basename(os.path.normpath(d))}/{int(row['seq']):02d}-{row['snapshot']}"}
                if row["status"] == "VALID" and row["integrity"] == "PASS":
                    rp = os.path.join(d, f"{int(row['seq']):02d}-{row['snapshot']}", "result.txt")
                    with open(rp, encoding="utf-8") as rf:
                        mline = [l for l in rf if l.startswith("M ")]
                    if len(mline) != 1:
                        raise ValueError(f"result.txt에 M 줄이 1개가 아님: {rp}")
                    run["seg"] = segments_of(parse_kv(mline[0][2:]))
                runs.append(run)
    return runs


def bucket(n):
    """건수를 1천, 1만, 10만 묶음으로. 그 밖이면 None (같은 묶음은 같은 건수여야 한다)"""
    return n if n in (REF, LOW, HIGH) else None


def representative(runs, case, n):
    """(구간별 중앙값 dict 또는 None, 쓴 회차 수, 제외 회차 요약)"""
    same = [r for r in runs if r["case"] == case and r["n"] == n]
    good = [r for r in same if r["seg"] is not None]
    excluded = [f"{r['src']} {r['status']} {r['integrity']}" for r in same if r["seg"] is None]
    if not good:
        return None, 0, excluded, same
    keys = [k for k, _ in SEGMENTS] + ["mark", "total"]
    return {k: statistics.median(r["seg"][k] for r in good) for k in keys}, len(good), excluded, same


def estimate(t_low, t_high, target=TARGET, n_low=LOW, n_high=HIGH):
    """구간 하나 추정. 시간이 0 이하면 처리량을 정의할 수 없어 ValueError"""
    if t_low <= 0 or t_high <= 0:
        raise ValueError(f"시간이 0 이하라 처리량을 계산할 수 없다 (1만 {t_low}, 10만 {t_high})")
    thr_low, thr_high = n_low / t_low, n_high / t_high
    r = thr_high / thr_low
    a = (t_high - t_low) / (n_high - n_low)
    linear = a * target + (t_low - a * n_low)
    optimistic = target / thr_high
    k = math.log10(target / n_high)
    pessimistic = target / (thr_high * r ** k)
    if R_LOW - EPS <= r <= R_HIGH + EPS:
        rule, lo, hi = 3, linear, linear
    elif r < R_LOW:
        rule, lo, hi = 4, optimistic, pessimistic
    else:
        rule, lo, hi = 5, optimistic, optimistic
    return {"thr_low": thr_low, "thr_high": thr_high, "r": r, "k": k, "linear": linear, "optimistic": optimistic,
            "pessimistic": pessimistic, "rule": rule, "lo": lo, "hi": hi}


def verdict(lo, hi, line):
    if hi <= line + EPS:
        return "합격" if line == PASS_S else "달성"
    if lo <= line + EPS:
        return "조건부"
    return "불합격" if line == PASS_S else "미달"


def analyze(runs, case, target=TARGET):
    """케이스 하나의 추정 결과 dict"""
    res = {"case": case, "rep": {}, "note": []}
    for n in (REF, LOW, HIGH):
        res["rep"][n] = representative(runs, case, n)
    low, nlow = res["rep"][LOW][0], res["rep"][LOW][1]
    high, nhigh, _, high_all = res["rep"][HIGH]
    if high is None and any(r["status"] == "UNMEASURABLE" for r in high_all):
        res["state"] = "10만 건 측정 불가 + 병목 (규칙 7: 외삽하지 않음, 속도 판정 안 함)"
        return res
    if low is None or high is None:
        res["state"] = "입력 부족 (1만, 10만 건 VALID PASS 회차가 모두 있어야 한다)"
        return res
    seg = {}
    try:
        for k, _ in SEGMENTS + [MARK]:
            seg[k] = estimate(low[k], high[k], target)
    except ValueError as e:
        res["state"] = f"계산 불가: {e}"
        return res
    res["seg"] = seg
    res["lo"] = sum(seg[k]["lo"] for k, _ in SEGMENTS)
    res["hi"] = sum(seg[k]["hi"] for k, _ in SEGMENTS)
    res["linear"] = sum(seg[k]["linear"] for k, _ in SEGMENTS)
    res["pass"] = verdict(res["lo"], res["hi"], PASS_S)
    res["goal"] = verdict(res["lo"], res["hi"], GOAL_S)
    if nlow < 3 or nhigh < 3:
        res["note"].append(f"1만 {nlow}회, 10만 {nhigh}회: 3회 미만이 있어 참고값")
    res["state"] = "ok"
    return res


def hms(s):
    s = int(round(s))
    return f"{s // 3600}시간 {s % 3600 // 60}분 {s % 60}초" if s >= 3600 else f"{s // 60}분 {s % 60}초"


def span(lo, hi):
    return f"{hms(lo)}" if abs(hi - lo) < 0.5 else f"{hms(lo)} ~ {hms(hi)}"


RULE_TEXT = {3: "규칙 3 (거의 일정 → 선형)", 4: "규칙 4 (떨어짐 → 범위)", 5: "규칙 5 (오름 → 10만 처리량 유지)"}


def render(results, target=TARGET):
    out = [f"# G2 대량 환불 {target:,}건 소요 시간 추정 (추정)", "",
           f"- 합격선 {hms(PASS_S)}, 목표 {hms(GOAL_S)} (임의 기준). 판정은 T1(마지막 정산) 기준",
           f"- 규칙: 1천 건 제외, r = 처리량(10만) / 처리량(1만), 0.9 ~ 1.1 선형 / 0.9 미만 범위 (비관 지수 k = log10({target:,} / 100,000) = {math.log10(target / HIGH):.3f}) / 1.1 초과 10만 처리량 유지",
           "- 대표값: VALID이고 정합성 PASS인 회차의 구간별 중앙값", ""]
    for res in results:
        out += [f"## 케이스 {res['case']}", ""]
        out += ["| 건수 | 회차 | 배정 대기 | 배정 | 배정 끝 → 첫 정산 | 정산 | 합 (T1 - T0) | 처리량 | 환불 표시 (참고) | 제외 회차 |", "|---|---|---|---|---|---|---|---|---|---|"]
        for n in (REF, LOW, HIGH):
            rep, cnt, excl, _ = res["rep"][n]
            label = f"{n:,}" + (" (참고)" if n == REF else "")
            if rep is None:
                out.append(f"| {label} | 0 | | | | | | | | {'; '.join(excl) or '-'} |")
            else:
                tot = sum(rep[k] for k, _ in SEGMENTS)
                out.append(f"| {label} | {cnt}{' (참고값)' if cnt < 3 else ''} | {rep['wait']:.3f} | {rep['alloc']:.3f} | {rep['gap']:.3f} | {rep['settle']:.3f} | {tot:.3f} | {n / tot:.1f}건/초 | {rep['mark']:.3f} | {'; '.join(excl) or '-'} |")
        out.append("")
        if res["state"] != "ok":
            out += [f"**{res['state']}**", ""]
            continue
        out += ["| 구간 | 처리량 1만 | 처리량 10만 | r | 적용 | 선형 (X1) | 낙관 | 비관 | 추정 (추정) |", "|---|---|---|---|---|---|---|---|---|"]
        for k, name in SEGMENTS + [MARK]:
            e = res["seg"][k]
            out.append(f"| {name} | {e['thr_low']:.1f} | {e['thr_high']:.1f} | {e['r']:.3f} | {RULE_TEXT[e['rule']]} | {hms(e['linear'])} | {hms(e['optimistic'])} | {hms(e['pessimistic'])} | {span(e['lo'], e['hi'])} |")
        out += ["", f"- **{target:,}건 추정 (T1 기준, 추정): {span(res['lo'], res['hi'])}** (선형만 쓰면 {hms(res['linear'])})",
                f"- 판정: 합격선 {hms(PASS_S)} → **{res['pass']}**, 목표 {hms(GOAL_S)} → **{res['goal']}**"]
        out += [f"- {n}" for n in res["note"]]
        out.append("")
    out += ["## 한계", "", "- 로컬 단일 머신, Docker, 인스턴스 1개. 실제 서비스 DB에는 다른 종목 데이터도 있어 더 느릴 수 있다",
            "- 실측 점은 1만, 10만 두 개다. 비관 값은 두 점의 처리량 비율이 212만까지 이어진다는 가정이다",
            "- ±10%, 지수, 합격선은 근거 자료가 없는 임의 기준이다 (측정 전에 고정)", ""]
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("batches", nargs="+", help="g2_run.sh 결과 폴더 (loadtest/results/g2-<batch>)")
    ap.add_argument("--out", help="추정.md 경로 (없으면 표준 출력)")
    ap.add_argument("--target", type=int, default=TARGET)
    a = ap.parse_args(argv)
    try:
        runs = load(a.batches)
    except (ValueError, OSError, KeyError) as e:
        print(f"입력 오류: {e}", file=sys.stderr)
        return 1
    odd = sorted({r["n"] for r in runs if bucket(r["n"]) is None})
    if odd:
        print(f"참고: 1천, 1만, 10만이 아닌 건수는 쓰지 않는다: {odd}", file=sys.stderr)
    cases = sorted({r["case"] for r in runs})
    if not cases:
        print("입력 오류: 회차가 없다", file=sys.stderr)
        return 1
    text = render([analyze(runs, c, a.target) for c in cases], a.target)
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"→ {a.out}", file=sys.stderr)
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
