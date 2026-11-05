#!/usr/bin/env python3
"""official.sh가 남긴 manifest.csv로 S0, S1, S3, S4를 판정한다 (표준 라이브러리만 사용, Python 3.8 이상, 부하가 끝난 뒤 실행).

사용: python judge.py results/<batch>/manifest.csv --p95-limit 15 --drain-limit 15 [--container ipo-postgres] [--s4-prefix lb]

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
- S1은 속도 단계마다 반복 측정의 중앙값으로 판정한다. 무효 회차가 하나라도 있으면 그 단계는 판정하지 않고,
  3회 미만이면 판정은 하되 참고값으로 표시한다 (팀 문서 0장: 최소 3회, 중앙값)

S3, S4 (Synthetic Data, loadtest/synthetic/). 회차마다 세 가지를 따로 판정한다
- 기대값: 행동 CSV를 synthetic/validate.py로 집계한다 (요청 수, 청약 행, 확정, 사유별 거절, 증거금). CSV가 규칙을 어기면 판정 전체를 실패로 끝낸다
- 유효성: 요청 수가 기대 요청 수와 다르면 무효 (G1, G2, 정합성 모두 판정하지 않음)
- 정합성: 청약 행, 확정, 사유별 거절, 증거금 수가 기대값과 같고 미처리(PENDING, COMPENSATING, CANCEL_REQUESTED)가 0.
  S4는 예수금 부족 투자자의 예수금이 행동 CSV 입금액 그대로이고 청약증거금이 0 (돈이 움직이지 않았음).
  HTTP 오류가 있으면 접수되지 않은 요청이 섞여 기대값과 비교할 수 없으므로 "판정 불가". 불합격은 무효(시험 잘못)가 아니라 결함이다
- 성능: G1은 S0과 같은 기준(처리량 포함, 부하 모양이 같다), G2는 확정 건만 (확정 수가 기대 확정 수와 다르면 G2 무효)
- 거절 지연(접수 → 거절, updated_at - received_at)은 참고 지표로만 보여 준다 (청약에 거절 시각 컬럼이 없어 대신 쓰는 값)
- 최종 판정은 measure 회차가 정확히 3개이고 모두 유효할 때만 그 중앙값으로 하고, 정합성은 3회 모두 통과해야 통과
"""
import argparse
import csv
import importlib.util
import json
import os
import re
import statistics
import subprocess
import sys

G1_PASS_AVG, G1_PASS_FAIL, G1_PASS_RATE = 500.0, 0.01, 200.0
G1_GOAL_AVG, G1_GOAL_P95, G1_GOAL_RATE = 250.0, 500.0, 400.0
REPEAT_MIN = 3   # 팀 문서 0장: 같은 조건으로 최소 3회, 대표값은 중앙값 (모든 측정에 적용)
SYNTHETIC = ("S3", "S4")                 # 행동 CSV로 기대값을 계산하는 시나리오
RATE_SCENARIOS = ("S0", "S3", "S4")      # 100명 x 10회라 처리량도 G1 기준에 넣는 시나리오
STUCK = ("PENDING", "COMPENSATING", "CANCEL_REQUESTED")   # 소진 뒤 남으면 안 되는 상태 (멈춘 SAGA)
VALIDATE_PY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "synthetic", "validate.py")


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


def ipo_in(ipos):
    """SQL의 in (...) 목록. UUID 형식이 아니면 SQL을 만들기 전에 ValueError"""
    for i in ipos:
        if len(i) != 36 or any(c not in "0123456789abcdefABCDEF-" for c in i):
            raise ValueError(f"공모주 ID 형식이 아니다: {i}")
    return ",".join(f"'{i}'" for i in ipos)


def psql_rows(sql, container, cols):
    """psql 결과를 행(쉼표로 나눈 값 목록)으로. 실패하거나 열 수가 cols와 다른 행이 있으면 RuntimeError"""
    r = subprocess.run(["docker", "exec", "-i", container, "psql", "-U", "postgres", "-d", "ipo", "-v", "ON_ERROR_STOP=1",
                        "-qtA", "-F", ",", "-c", sql], capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise RuntimeError(f"psql 실패: {r.stderr.strip()}")
    rows = [line.split(",") for line in r.stdout.strip().splitlines() if line.strip()]
    if any(len(p) != cols for p in rows):
        raise RuntimeError(f"psql 출력 형식이 다르다: {r.stdout!r}")
    return rows


def query_g2(ipos, container):
    """공모주 목록의 확정 청약으로 접수→확정 p50/p95/max와 소진 시간을 구한다. 확정 0건이면 None"""
    ins = ipo_in(ipos)
    sql = ("select count(*), "
           "percentile_cont(0.5) within group (order by extract(epoch from confirmed_at - received_at)), "
           "percentile_cont(0.95) within group (order by extract(epoch from confirmed_at - received_at)), "
           "max(extract(epoch from confirmed_at - received_at)), "
           "extract(epoch from max(confirmed_at) - max(received_at)) "
           f"from subscription.subscription where ipo_id in ({ins}) and status = 'CONFIRMED'")
    rows = psql_rows(sql, container, 5)
    if len(rows) != 1:
        raise RuntimeError(f"psql 출력 형식이 다르다: {rows!r}")
    p = rows[0]
    if int(p[0]) == 0:
        return None
    return {"confirmed": int(p[0]), "p50": float(p[1]), "p95": float(p[2]), "max": float(p[3]), "drain": float(p[4])}


def query_counts(ipos, container, logins=()):
    """S3, S4 정합성용 조회: 상태·사유별 건수, 증거금 수, 거절 지연 p50/p95, logins 회원의 예수금과 청약증거금.
    거절 사유가 없으면 '-'. 회원 계좌가 여러 개면 합계"""
    ins = ipo_in(ipos)
    for login in logins:
        if not re.fullmatch(r"[a-z0-9]{1,20}", login):
            raise ValueError(f"loginId 형식이 아니다: {login}")
    by = {}
    for st, reason, n in psql_rows("select status, coalesce(reject_reason, '-'), count(*) from subscription.subscription "
                                   f"where ipo_id in ({ins}) group by 1, 2", container, 3):
        by[(st, reason)] = int(n)
    rows = psql_rows(f"select (select count(*) from account.margin where ipo_id in ({ins})), "
                     "percentile_cont(0.5) within group (order by extract(epoch from updated_at - received_at)), "
                     "percentile_cont(0.95) within group (order by extract(epoch from updated_at - received_at)) "
                     f"from subscription.subscription where ipo_id in ({ins}) and status = 'REJECTED'", container, 3)
    if len(rows) != 1:
        raise RuntimeError(f"psql 출력 형식이 다르다: {rows!r}")
    margins, rp50, rp95 = rows[0]
    balances = {}
    if logins:
        names = ",".join(f"'{x}'" for x in logins)
        for login, cash, margin in psql_rows("select u.login_id, sum(a.cash_balance), sum(a.margin_balance) from account.users u "
                                             f"join account.account a on a.user_id = u.user_id where u.login_id in ({names}) group by 1",
                                             container, 3):
            balances[login] = (int(cash), int(margin))
    return {"by_status": by, "margins": int(margins), "reject_p50": float(rp50) if rp50 else None,
            "reject_p95": float(rp95) if rp95 else None, "balances": balances}


def load_expected(scenario):
    """행동 CSV(loadtest/synthetic/<시나리오>-behavior.csv)의 기대 결과. 규칙 위반이면 ValueError (판정하지 않는다)"""
    spec = importlib.util.spec_from_file_location("synthetic_validate", VALIDATE_PY)
    validate = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(validate)
    errors, exp = validate.check(scenario)
    if errors:
        raise ValueError(f"{scenario} 행동 CSV 규칙 위반 {len(errors)}건: " + "; ".join(errors[:3]))
    return exp


def short_logins(exp, prefix):
    """S4 예수금 부족 투자자의 loginId (seed_users.py와 같은 형식: 접두사 + 번호 4자리)"""
    return [f"{prefix}{v:04d}" for v in exp["detail"]["short_vusers"]]


def integrity(s, c, exp, prefix="lb"):
    """S3, S4 정합성. (판정, 문제 목록). 판정은 통과, 불합격, 판정 불가"""
    if s["fail"] > 0:
        return "판정 불가", [f"HTTP 오류 {s['fail'] * 100:.2f}% (접수되지 않은 요청이 있어 기대 건수와 비교할 수 없다)"]
    by = c["by_status"]
    total = sum(by.values())
    confirmed = sum(n for (st, _), n in by.items() if st == "CONFIRMED")
    rejected = {}
    for (st, reason), n in by.items():
        if st == "REJECTED":
            rejected[reason] = rejected.get(reason, 0) + n
    stuck = sum(n for (st, _), n in by.items() if st in STUCK)
    problems = []
    if total != exp["subscription_rows"]:
        problems.append(f"청약 행 {total} ≠ 기대 {exp['subscription_rows']}")
    if confirmed != exp["confirmed"]:
        problems.append(f"확정 {confirmed} ≠ 기대 {exp['confirmed']}")
    for reason in sorted(set(rejected) | set(exp["rejected"])):
        if rejected.get(reason, 0) != exp["rejected"].get(reason, 0):
            problems.append(f"거절 {reason} {rejected.get(reason, 0)} ≠ 기대 {exp['rejected'].get(reason, 0)}")
    if stuck:
        problems.append(f"미처리 {stuck}건 ({', '.join(f'{st} {n}' for (st, _), n in sorted(by.items()) if st in STUCK)})")
    other = total - confirmed - sum(rejected.values()) - stuck
    if other:
        problems.append(f"그 밖의 상태 {other}건 ({', '.join(f'{st} {n}' for (st, _), n in sorted(by.items()) if st not in STUCK + ('CONFIRMED', 'REJECTED'))})")
    if c["margins"] != exp["margins"]:
        problems.append(f"증거금 {c['margins']} ≠ 기대 {exp['margins']}")
    if exp["scenario"] == "S4":
        deposits = exp["detail"]["deposits"]
        moved = []
        for v in exp["detail"]["short_vusers"]:
            login = f"{prefix}{v:04d}"
            if login not in c["balances"]:
                moved.append(f"{login} 계좌 없음")
            elif c["balances"][login] != (deposits[str(v)], 0):
                cash, margin = c["balances"][login]
                moved.append(f"{login} 예수금 {cash}/입금 {deposits[str(v)]}, 청약증거금 {margin}")
        if moved:
            problems.append(f"예수금 부족 투자자의 돈이 움직임 {len(moved)}명 ({'; '.join(moved[:3])}{' 외' if len(moved) > 3 else ''})")
    return ("불합격" if problems else "통과"), problems


def g1(s, scenario):
    """(합격, 목표) 판정. 무효면 ('무효', '무효')"""
    if scenario == "S1" and s["dropped"] > 0:
        return "무효", "무효"
    ok_pass = s["avg"] <= G1_PASS_AVG and s["fail"] < G1_PASS_FAIL
    ok_goal = s["avg"] <= G1_GOAL_AVG and s["p95"] <= G1_GOAL_P95 and s["fail"] == 0
    if scenario in RATE_SCENARIOS:
        ok_pass = ok_pass and s["rate"] >= G1_PASS_RATE
        ok_goal = ok_goal and s["rate"] >= G1_GOAL_RATE
    return ("통과" if ok_pass else "불합격"), ("통과" if ok_goal else "미달")


def g2(s, d, scenario, p95_limit, drain_limit, expected_confirmed=None):
    """expected_confirmed: 거절이 섞인 시나리오(S3, S4)의 기대 확정 수. 없으면 요청 수"""
    if scenario == "S1" and s["dropped"] > 0:
        return "무효(밀린 반복)"
    want = s["requests"] if expected_confirmed is None else expected_confirmed
    if d is None or d["confirmed"] != want:
        return f"무효(확정 {d['confirmed'] if d else 0} / {'요청' if expected_confirmed is None else '기대 확정'} {want})"
    return "통과" if d["p95"] <= p95_limit and d["drain"] <= drain_limit else "불합격"


def fmt(x, digits=1):
    return "-" if x is None else f"{x:.{digits}f}"


def median_verdict(valid, scenario, p95_limit, drain_limit):
    """유효한 measure 회차들의 중앙값으로 G1, G2를 다시 판정한다. valid의 원소는 (summary, g2 조회값, ...)"""
    med = statistics.median
    s = {k: med([x[0][k] for x in valid]) for k in ("rate", "avg", "p95", "fail")}
    s["dropped"] = 0
    gp, gg = g1(s, scenario)
    p95, drain = med([x[1]["p95"] for x in valid]), med([x[1]["drain"] for x in valid])
    v2 = "통과" if p95 <= p95_limit and drain <= drain_limit else "불합격"
    return {"s": s, "gp": gp, "gg": gg, "p95": p95, "drain": drain, "v2": v2}


def judge(manifest, p95_limit, drain_limit, query=query_g2, container="ipo-postgres", out=sys.stdout,
          counts=query_counts, s4_prefix="lb", expected_of=load_expected):
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
    synth, integ_rows, exps = {}, [], {}
    for r in rows:
        scen, phase = r["scenario"], r["phase"]
        target = int(r["target_rate"]) if r["target_rate"] else None
        s = load_summary(results_dir, r["run_id"])
        ipos = [i for i in r["ipo_ids"].split(";") if i]
        if not ipos:
            raise ValueError(f"{r['run_id']}: 공모주 ID가 없다")
        d = query(ipos, container)
        exp = None
        if scen in SYNTHETIC:
            if scen not in exps:
                exps[scen] = expected_of(scen)
            exp = exps[scen]
        gp, gg = g1(s, scen)
        v2 = g2(s, d, scen, p95_limit, drain_limit, exp["confirmed"] if exp else None)
        expected = int(r["expected_requests"]) if r.get("expected_requests") else None
        if scen == "S0" and expected is not None and s["requests"] != expected:
            gp, gg, v2 = "무효", "무효", f"무효(요청 {s['requests']} / 예상 {expected})"
        if exp:
            if expected is not None and expected != exp["requests"]:
                raise ValueError(f"{r['run_id']}: manifest의 예상 요청 수 {expected}가 {scen} 행동 CSV의 요청 수 {exp['requests']}와 다르다")
            c = None
            if s["requests"] != exp["requests"]:
                gp, gg, v2 = "무효", "무효", f"무효(요청 {s['requests']} / 예상 {exp['requests']})"
                integ = ("무효", [f"요청 {s['requests']} / 예상 {exp['requests']}"])
            else:
                c = counts(ipos, container, short_logins(exp, s4_prefix) if scen == "S4" else ())
                integ = integrity(s, c, exp, s4_prefix)
            integ_rows.append((r["run_id"], scen, phase, exp, c, integ))
            if phase == "measure":
                synth.setdefault(scen, []).append((s, d, gp, v2, integ, c))
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

    if integ_rows:
        w()
        w("## 정합성 (S3, S4 회차별. 기대값은 행동 CSV 집계)")
        w("| run_id | 시나리오 | 구간 | 청약 행 (기대) | 확정 (기대) | 거절 (기대) | 증거금 (기대) | 미처리 | 거절 지연 p50 / p95(초, 참고) | 정합성 | 문제 |")
        w("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        for run_id, scen, phase, exp, c, (verdict, problems) in integ_rows:
            ex_rej = ", ".join(f"{k} {n}" for k, n in exp["rejected"].items())
            if c is None:
                w(f"| {run_id} | {scen} | {phase} | - ({exp['subscription_rows']}) | - ({exp['confirmed']}) | - ({ex_rej}) | - ({exp['margins']}) | - | - | {verdict} | {'; '.join(problems)} |")
                continue
            by = c["by_status"]
            rej = {}
            for (st, reason), n in by.items():
                if st == "REJECTED":
                    rej[reason] = rej.get(reason, 0) + n
            rej_txt = ", ".join(f"{k} {n}" for k, n in sorted(rej.items())) or "0"
            stuck = sum(n for (st, _), n in by.items() if st in STUCK)
            confirmed = sum(n for (st, _), n in by.items() if st == "CONFIRMED")
            w(f"| {run_id} | {scen} | {phase} | {sum(by.values())} ({exp['subscription_rows']}) | {confirmed} ({exp['confirmed']}) | {rej_txt} ({ex_rej}) | "
              f"{c['margins']} ({exp['margins']}) | {stuck} | {fmt(c['reject_p50'], 2)} / {fmt(c['reject_p95'], 2)} | {verdict} | {'; '.join(problems) or '-'} |")

    w()
    w("## S0 (measure 회차 중앙값)")
    valid = [x for x in s0_measure if x[2] != "무효" and not x[3].startswith("무효")]
    s0_med = None
    if len(s0_measure) != 3 or len(valid) != 3:
        w(f"판정하지 않음: measure 회차 {len(s0_measure)}개 중 유효 {len(valid)}개 (3개 모두 유효해야 한다)")
    else:
        s0_med = m = median_verdict(valid, "S0", p95_limit, drain_limit)
        s = m["s"]
        w(f"- G1: 처리량 {s['rate']:.1f}건/초, avg {s['avg']:.1f}ms, p95 {s['p95']:.1f}ms, 오류율 {s['fail'] * 100:.2f}% → 합격 {m['gp']}, 목표 {m['gg']}")
        w(f"- G2: 접수→확정 p95 {m['p95']:.2f}초, 소진 {m['drain']:.2f}초 → {m['v2']}")

    synth_med = {}
    for scen in sorted(synth):
        runs = synth[scen]
        w()
        w(f"## {scen} (measure 회차 중앙값, Synthetic Data)")
        verdicts = [x[4][0] for x in runs]
        bad = [v for v in verdicts if v != "통과"]
        w(f"- 정합성: {len(runs)}회 중 통과 {verdicts.count('통과')}회" + (f", 그 밖 {', '.join(bad)}" if bad else "")
          + (" → 통과" if len(runs) == 3 and not bad else " → 통과 아님 (3회 모두 통과해야 한다. 정합성 표 확인)"))
        valid = [x for x in runs if x[2] != "무효" and not x[3].startswith("무효")]
        if len(runs) != 3 or len(valid) != 3:
            w(f"- G1, G2 판정하지 않음: measure 회차 {len(runs)}개 중 유효 {len(valid)}개 (3개 모두 유효해야 한다. G2는 확정 수가 기대값과 같아야 유효)")
            continue
        synth_med[scen] = m = median_verdict(valid, scen, p95_limit, drain_limit)
        s = m["s"]
        w(f"- G1: 처리량 {s['rate']:.1f}건/초, avg {s['avg']:.1f}ms, p95 {s['p95']:.1f}ms, 오류율 {s['fail'] * 100:.2f}% → 합격 {m['gp']}, 목표 {m['gg']}")
        w(f"- G2 (확정 건): 접수→확정 p95 {m['p95']:.2f}초, 소진 {m['drain']:.2f}초 → {m['v2']}")
        rp = [x[5]["reject_p95"] for x in valid if x[5] and x[5]["reject_p95"] is not None]
        rp50 = [x[5]["reject_p50"] for x in valid if x[5] and x[5]["reject_p50"] is not None]
        if rp:
            w(f"- 거절 지연 (참고): p50 {statistics.median(rp50):.2f}초, p95 {statistics.median(rp):.2f}초")

    if synth_med:
        w()
        w("## S0 대비 (중앙값의 차이. 합격선 없이 수치로만 본다)")
        if s0_med is None:
            w("S0 중앙값이 없어 비교하지 않음")
        else:
            w("| 시나리오 | 처리량(건/초) | avg(ms) | p95(ms) | 접수→확정 p95(초) | 소진(초) |")
            w("| --- | --- | --- | --- | --- | --- |")
            b = s0_med
            w(f"| S0 | {b['s']['rate']:.1f} | {b['s']['avg']:.1f} | {b['s']['p95']:.1f} | {b['p95']:.2f} | {b['drain']:.2f} |")
            for scen, m in sorted(synth_med.items()):
                diff = lambda x, y, k=1: f"{x:.{k}f} ({x - y:+.{k}f})"
                w(f"| {scen} | {diff(m['s']['rate'], b['s']['rate'])} | {diff(m['s']['avg'], b['s']['avg'])} | {diff(m['s']['p95'], b['s']['p95'])} | "
                  f"{diff(m['p95'], b['p95'], 2)} | {diff(m['drain'], b['drain'], 2)} |")

    w()
    w(f"## S1 (속도 단계별, 같은 단계 반복 측정의 중앙값. 팀 규칙: 최소 {REPEAT_MIN}회)")
    if not s1_rows:
        w("S1 실행 없음")
        return
    stages = {}
    for target, s, d, gp, gg, v2 in s1_rows:
        stages.setdefault(target, []).append((s, d, gp, gg, v2))
    limit, few = None, []
    for target in sorted(stages):
        runs = stages[target]
        valid = [x for x in runs if x[2] != "무효" and not x[4].startswith("무효")]
        if len(valid) != len(runs):
            w(f"- {target}건/초: 판정하지 않음 ({len(runs)}회 중 유효 {len(valid)}회. 무효 회차를 다시 측정해야 한다)")
            if limit is None:
                limit = (target, None, None)
            continue
        med = statistics.median
        s = {k: med([x[0][k] for x in valid]) for k in ("avg", "p95", "fail")}
        s["dropped"], s["rate"] = 0, 0.0
        gp, gg = g1(s, "S1")
        p95, drain = med([x[1]["p95"] for x in valid]), med([x[1]["drain"] for x in valid])
        v2 = "통과" if p95 <= p95_limit and drain <= drain_limit else "불합격"
        note = ""
        if len(runs) < REPEAT_MIN:
            few.append(target)
            note = f", 참고값: {len(runs)}회"
        w(f"- {target}건/초 ({len(runs)}회 중앙값{note}): G1 avg {s['avg']:.1f}ms, p95 {s['p95']:.1f}ms, 오류율 {s['fail'] * 100:.2f}% → {gp} (목표 {gg}) / "
          f"G2 접수→확정 p95 {p95:.2f}초, 소진 {drain:.2f}초 → {v2}")
        if not (gp == "통과" and v2 == "통과") and limit is None:
            limit = (target, gp, v2)
    if limit is None:
        w(f"- 한계 지점: 측정한 가장 높은 단계({max(stages)}건/초)까지 G1, G2 모두 통과 (한계 지점 미도달)")
    elif limit[1] is None:
        w(f"- 한계 지점: {limit[0]}건/초부터 판정 불가 (무효 회차가 있다. 한계가 아니라 측정 실패다)")
    else:
        w(f"- 한계 지점: {limit[0]}건/초 (G1 {limit[1]}, G2 {limit[2]})")
    if few:
        w(f"- {', '.join(f'{t}건/초' for t in few)}는 {REPEAT_MIN}회 미만이라 참고값이다. 결론에 쓰지 않는다 (팀 문서 0장)")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest")
    ap.add_argument("--p95-limit", type=float, required=True)
    ap.add_argument("--drain-limit", type=float, required=True)
    ap.add_argument("--container", default="ipo-postgres")
    ap.add_argument("--s4-prefix", default="lb", help="S4 잔액 부족 묶음의 loginId 접두사 (seed_users.py --prefix)")
    a = ap.parse_args(argv)
    judge(a.manifest, a.p95_limit, a.drain_limit, container=a.container, s4_prefix=a.s4_prefix)


if __name__ == "__main__":
    main()
