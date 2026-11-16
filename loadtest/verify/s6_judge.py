#!/usr/bin/env python3
"""S6 장애 주입 한 회차 판정 (s6.sh가 부른다. 표준 라이브러리만 사용).

사용: python s6_judge.py --requests results/<run_id>/requests.log --summary results/<run_id>/summary.json
                         --events results/<run_id>/events.txt --lag results/<run_id>/lag.csv
                         --sql-s6 results/<run_id>/sql_result_s6.txt [--sql-base results/<run_id>/sql_result.txt]
                         --ipo <공모주 ID> --expect-requests <계획한 요청 수> [--container ipo-postgres]
                         [--dlt-start results/<run_id>/dlt-start.txt --dlt-end results/<run_id>/dlt-end.txt] [--account-log results/<run_id>/account-service.log]

- 회차 유효성: 밀린 요청이 없고, k6 응답 검사 실패가 없고, 401과 202가 아닌 응답이 없고(접수는 계좌 서비스와 무관해 장애 중에도 202여야 한다),
  종료가 부하 도중에 일어났고(종료 전과 후에 접수된 청약이 모두 있음), 장애 중에 인출 요청 랙이 쌓였어야 한다
- 결과: 청약 행 수 = 요청 수, CONFIRMED + CANCELLED(TIMEOUT) = 요청 수, verify-s6.sql 23~26번 위반 0, 202를 받았는데 청약 행이 없는 요청 0,
  (--sql-base를 주면) 기존 정합성 검사 2, 3, 5, 6, 17번 위반 0과 4번 확정 수 = 인출 수
- 기록용(판정에 영향 없음): 선행 반환 기록 수, DLT 증가(--dlt-start, --dlt-end. 늘었으면 경고), 같은 eventId를 processed_event가 걸러낸 횟수(--account-log)
- 입력 확인: 읽은 로그 행 수가 k6 요청 수와 같고, 밀린 요청이 없으면 k6 요청 수가 계획 수와 같고, 모든 행의 stage가 s6이어야 판정한다
- events.txt: 줄마다 key=value (epoch ms): k6_start_ms, kill_ms, restart_ms, up_ms. lag.csv: 머리줄(ts_ms,lag) 뒤에 줄마다 ts_ms,lag
- 종료 코드: 0 회차 유효이고 위반 없음, 4 회차 유효인데 위반 있음, 3 회차 무효(이유를 출력), 1 입력을 읽지 못함
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
EVENT_KEYS = ("k6_start_ms", "kill_ms", "restart_ms", "up_ms")
# 청약 서비스가 계좌 응답을 기다리는 시간(saga.timeout.response-timeout 30초 x max-attempts 3). 이 시간 이상 서비스가 못 받으면 시간 초과 보상이 나와야 한다
TIMEOUT_SEC = 90
# 계좌 서비스가 같은 eventId를 processed_event로 걸러낼 때 남기는 로그 (MarginReserveService)
DUP_MARK = "[증거금 인출] 중복 수신, 건너뜀"


# psql 한 번 실행. 테스트에서는 이 함수를 가짜로 바꾼다
def query(sql):
    out = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "postgres", "-d", "ipo", "-v", "ON_ERROR_STOP=1",
                          "-qtA", "-F", "|", "-c", sql], capture_output=True, text=True, encoding="utf-8", check=True).stdout
    return [line.split("|") for line in out.splitlines() if line.strip()]


def read_events(path):
    """events.txt의 key=value (epoch ms)를 읽는다. 키가 빠졌거나 숫자가 아니거나 시각 순서가 틀리면 ValueError"""
    ev = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                ev[k.strip()] = v.strip()
    out = {}
    for k in EVENT_KEYS:
        if k not in ev:
            raise ValueError(f"events.txt에 {k}가 없음")
        try:
            out[k] = int(ev[k])
        except ValueError:
            raise ValueError(f"events.txt의 {k}가 숫자가 아님: {ev[k]}")
    if not (out["k6_start_ms"] <= out["kill_ms"] <= out["restart_ms"] <= out["up_ms"]):
        raise ValueError(f"events.txt 시각 순서가 맞지 않음: {out} (부하 시작 <= 종료 <= 재시작 <= UP)")
    return out


def read_lag(path):
    """lag.csv를 (ts_ms, lag) 목록으로 읽는다. 행이 없거나 형식이 틀리면 ValueError"""
    rows = []
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f):
            line = line.strip()
            if not line or (i == 0 and not line[0].isdigit()):
                continue
            try:
                ts, lag = line.split(",")
                rows.append((int(ts), int(lag)))
            except ValueError:
                raise ValueError(f"lag.csv {i + 1}번째 줄을 읽지 못함: {line}")
    if not rows:
        raise ValueError("lag.csv에 샘플이 없음")
    return rows


def sql_sections(path):
    """sql_result 파일을 검사 이름("23", "S6 요약-2" 등)별로 나눈다. {이름: {skip, n(결과 행 수), lines}}"""
    secs, cur = {}, None
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.rstrip("\r\n")
            if line.startswith("== "):
                m = re.match(r"== (.+?)\. (.*)", line)
                cur = m.group(1) if m else None
                if cur is not None:
                    secs[cur] = {"skip": "건너뜀" in line, "n": None, "lines": []}
                continue
            if cur is None:
                continue
            s = line.strip()
            m = re.match(r"\((\d+) rows?\)", s)
            if m:
                secs[cur]["n"] = int(m.group(1))
            elif s:
                secs[cur]["lines"].append(s)
    return secs


def need(secs, name, path):
    sec = secs.get(name)
    if sec is None or sec["skip"] or sec["n"] is None:
        raise ValueError(f"{path}에서 {name}번 결과를 읽지 못함 (없거나 건너뜀)")
    return sec


def first_row(sec):
    """표의 첫 데이터 행을 칸 목록으로 돌려준다 (머리줄, 구분선 다음). 없으면 None"""
    data = [ln for ln in sec["lines"][2:] if "|" in ln]
    return [c.strip() for c in data[0].split("|")] if data else None


def read_dlt(path):
    """dlt-start.txt, dlt-end.txt("<토픽>.DLT <건수>" 줄)를 {토픽: 건수}로. 비었거나 모양이 틀리면 ValueError"""
    d = {}
    with open(path, encoding="utf-8") as f:
        for ln in f:
            ln = ln.strip()
            if not ln:
                continue
            parts = ln.split()
            if len(parts) != 2 or not parts[1].isdigit():
                raise ValueError(f"{path}: 읽지 못한 줄 '{ln}'")
            d[parts[0]] = int(parts[1])
    if not d:
        raise ValueError(f"{path}가 비어 있음")
    return d


def dlt_growth(start_path, end_path):
    """({토픽: 증가 건수}, 합계). 시작과 끝의 토픽이 다르거나 줄어든 토픽이 있으면 ValueError"""
    a, b = read_dlt(start_path), read_dlt(end_path)
    if set(a) != set(b):
        raise ValueError("시작과 끝의 DLT 토픽이 다름")
    grow = {t: b[t] - a[t] for t in a}
    if any(v < 0 for v in grow.values()):
        raise ValueError("DLT 건수가 줄어듦 (Kafka가 중간에 초기화됨)")
    return grow, sum(grow.values())


def judge(requests_log, summary_path, events_path, lag_path, sql_s6, sql_base, ipo, expect_requests,
          dlt_start=None, dlt_end=None, account_log=None):
    """(출력 줄 목록, 무효 이유 목록, 위반 목록)을 돌려준다. 입력을 읽지 못하면 ValueError"""
    rows = load_rows(requests_log)
    with open(summary_path, encoding="utf-8") as f:
        metrics = json.load(f)["metrics"]
    for name, key in (("dropped_iterations", "count"), ("http_reqs", "count"), ("checks", "fails")):
        if key not in metrics.get(name, {}):
            raise ValueError(f"summary.json에 {name}.{key}가 없음")
    dropped = int(metrics["dropped_iterations"]["count"])
    sent = int(metrics["http_reqs"]["count"])
    check_fails = int(metrics["checks"]["fails"])
    # 읽지 못한 로그 행은 아래 대조에서 빠져 0건처럼 보인다. 로그 행 수는 늘 k6 요청 수와 같아야 하고,
    # k6 요청 수는 밀린 요청이 없을 때만 계획 수와 비교한다 (밀린 요청은 k6가 보내지 않아 적은 것이 정상이고 회차가 무효가 된다)
    if len(rows) != sent or (dropped == 0 and sent != expect_requests):
        raise ValueError(f"요청 수가 맞지 않음: 계획 {expect_requests}건, k6 요청 {sent}건, 밀린 요청 {dropped}건, 읽은 로그 행 {len(rows)}건")
    unknown = sum(1 for r in rows if r.get("stage") != "s6")
    if unknown:
        raise ValueError(f"stage가 s6이 아닌 로그 행 {unknown}건")
    statuses = collections.Counter(r["status"] for r in rows)
    ok202 = {r["requestId"] for r in rows if r["status"] == 202}

    ev = read_events(events_path)
    lag = read_lag(lag_path)
    k = ev["kill_ms"]
    before, after = (int(x) for x in query(
        f"select count(*) filter (where received_at < to_timestamp({k} / 1000.0)), "
        f"count(*) filter (where received_at >= to_timestamp({k} / 1000.0)) "
        f"from subscription.subscription where ipo_id = '{ipo}'")[0])
    subs = {s[0] for s in query(f"select request_id from subscription.subscription where ipo_id = '{ipo}'")}
    missing = [r for r in ok202 if r not in subs]
    outage_lags = [lg for ts, lg in lag if ev["kill_ms"] <= ts <= ev["up_ms"]]

    s6 = sql_sections(sql_s6)
    summ = first_row(need(s6, "S6 요약-2", sql_s6))
    if summ is None or len(summ) != 4:
        raise ValueError(f"{sql_s6}의 S6 요약-2 결과를 읽지 못함: {summ}")
    total, confirmed, timeout, other = (int(x) for x in summ)
    checks_s6 = {n: need(s6, n, sql_s6)["n"] for n in ("23", "24", "25", "26")}
    void = None   # 반환 기록 수 (요약-3은 선택. 옛 결과 파일에는 없다)
    vsec = s6.get("S6 요약-3")
    if vsec and not vsec["skip"] and vsec["n"] is not None:
        vrow = first_row(vsec)
        if vrow is None or len(vrow) != 2:
            raise ValueError(f"{sql_s6}의 S6 요약-3 결과를 읽지 못함: {vrow}")
        void = (int(vrow[0]), int(vrow[1]))
    base = {}
    if sql_base:
        bs = sql_sections(sql_base)
        base = {n: need(bs, n, sql_base)["n"] for n in ("2", "3", "5", "6", "17")}
        row4 = first_row(need(bs, "4", sql_base))
        if row4 is None or len(row4) != 2:
            raise ValueError(f"{sql_base}의 4번 결과를 읽지 못함: {row4}")
        base["4"] = (int(row4[0]), int(row4[1]))

    invalid = []
    if dropped:
        invalid.append(f"밀린 요청 {dropped}건 (요청 시각이 계획보다 늦어짐)")
    if check_fails:
        invalid.append(f"k6 응답 검사 실패 {check_fails}건")
    if statuses.get(401):
        invalid.append(f"401 {statuses[401]}건 (토큰 만료)")
    not202 = {s: n for s, n in statuses.items() if s not in (202, 401)}
    if not202:
        invalid.append(f"202가 아닌 응답 {dict(not202)} (접수는 계좌 서비스와 무관해 장애 중에도 202여야 함)")
    if before == 0:
        invalid.append("종료 전에 접수된 청약이 없음 (종료가 부하 시작보다 이름)")
    if after == 0:
        invalid.append("종료 이후에 접수된 청약이 없음 (종료가 부하가 끝난 뒤)")
    if not outage_lags:
        invalid.append("장애 구간에 랙 샘플이 없음")
    elif max(outage_lags) == 0:
        invalid.append("장애 중 인출 요청 랙이 쌓이지 않음 (종료 전에 이미 모두 처리됨)")

    violations = []
    if missing:
        violations.append(f"202를 받았는데 청약 행이 없음 {len(missing)}건 (유실)")
    if total != expect_requests:
        violations.append(f"청약 행 수 {total}건이 요청 수 {expect_requests}건과 다름")
    if other:
        violations.append(f"CONFIRMED, CANCELLED(TIMEOUT)가 아닌 상태 {other}건")
    for n, c in checks_s6.items():
        if c:
            violations.append(f"verify-s6.sql {n}번 위반 {c}건")
    for n in ("2", "3", "5", "6", "17"):
        if base.get(n):
            violations.append(f"verify.sql {n}번 위반 {base[n]}건")
    if "4" in base and base["4"][0] != base["4"][1]:
        violations.append(f"verify.sql 4번: 확정 청약 {base['4'][0]}건, 인출된 증거금 {base['4'][1]}건")

    warnings = []
    outage_sec = (ev["up_ms"] - ev["kill_ms"]) / 1000
    if outage_sec >= TIMEOUT_SEC and timeout == 0:
        warnings.append(f"종료→UP이 {outage_sec:.1f}초로 시간 초과 기준({TIMEOUT_SEC}초) 이상인데 CANCELLED(TIMEOUT)이 0건이라 시간 초과 보상 경로를 검증하지 못함 "
                        "(요청이 많으면 시간 초과 스케줄러가 한 번에 batch-size건씩만 재요청해 보상이 접수 90초보다 늦어진다. env.txt의 saga_timeout 참고)")

    out = ["# S6 장애 주입 판정",
           f"회차: {'유효' if not invalid else '무효'}" + ("" if not invalid else " (" + "; ".join(invalid) + ")"),
           f"결과: {'위반 없음' if not violations else '위반 ' + str(len(violations)) + '종'}" + ("" if not violations else " (" + "; ".join(violations) + ")")
           + ("" if not invalid else " (회차가 무효라 참고용)"),
           "",
           "## 장애 구간 (컨테이너 시계)",
           f"부하 시작 → 종료: {(ev['kill_ms'] - ev['k6_start_ms']) / 1000:.1f}초",
           f"종료 → 재시작 명령: {(ev['restart_ms'] - ev['kill_ms']) / 1000:.1f}초, 종료 → UP(healthy): {(ev['up_ms'] - ev['kill_ms']) / 1000:.1f}초",
           f"종료 전 접수 {before}건, 종료 이후 접수 {after}건",
           f"장애 중 인출 요청 랙 최댓값: {max(outage_lags) if outage_lags else '없음'} (샘플 {len(outage_lags)}개)",
           "",
           "## 결과",
           f"요청 {expect_requests}건: CONFIRMED {confirmed}건, CANCELLED(TIMEOUT) {timeout}건, 그 밖 {other}건 (청약 행 {total}건)",
           f"202를 받았는데 청약 행이 없음: {len(missing)}건",
           "verify-s6.sql 위반 행 수 (기대 0): " + ", ".join(f"{n}번 {c}" for n, c in checks_s6.items())]
    if base:
        out.append("verify.sql 위반 행 수 (기대 0): " + ", ".join(f"{n}번 {base[n]}" for n in ("2", "3", "5", "6", "17"))
                   + f", 4번 확정 {base['4'][0]} / 인출 {base['4'][1]}")
    if void is not None:
        out.append(f"선행 반환 기록(margin_void_intent): {void[0]}건, 그중 증거금 없이 늦은 인출이 막힌 건 {void[1]}건 (기록용)")
    # 기록용 (부하 테스트 상세 문서 6-2 DLT 증가, 8-1 재시작 뒤 같은 eventId 걸러짐). 판정에는 영향 없음
    if dlt_start is not None or dlt_end is not None:
        try:
            grow, dlt_total = dlt_growth(dlt_start, dlt_end)
            moved = ", ".join(f"{t} +{n}" for t, n in grow.items() if n)
            out.append(f"DLT 증가: {dlt_total}건" + (f" ({moved})" if moved else "") + " (기록용)")
            if dlt_total:
                warnings.append(f"DLT로 간 메시지가 {dlt_total}건 있음. 재시작 직후 처리 실패 등 원인을 account-service.log, subscription-service.log로 확인한다")
        except (ValueError, OSError) as e:
            out.append(f"DLT 증가: 기록하지 못함 ({e})")
    if account_log is not None:
        try:
            with open(account_log, encoding="utf-8", errors="replace") as f:
                dup = sum(1 for ln in f if DUP_MARK in ln)
            out.append(f"processed_event가 같은 eventId를 걸러낸 인출 요청: {dup}건 (account-service.log의 \"중복 수신, 건너뜀\" 줄. 기록용)")
        except OSError as e:
            out.append(f"processed_event가 걸러낸 인출 요청: 기록하지 못함 ({e})")
    if warnings:
        out += ["", "## 경고 (판정에는 영향 없음)"] + [f"- {w}" for w in warnings]
    return out, invalid, violations


def main(argv=None):
    global CONTAINER
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--requests", required=True)
    ap.add_argument("--summary", required=True)
    ap.add_argument("--events", required=True)
    ap.add_argument("--lag", required=True)
    ap.add_argument("--sql-s6", required=True)
    ap.add_argument("--sql-base")
    ap.add_argument("--ipo", required=True)
    ap.add_argument("--expect-requests", type=int, required=True)
    ap.add_argument("--container", default=CONTAINER)
    ap.add_argument("--warmup", default="", help="워밍업 여부 표시 문구 (비우면 줄을 내지 않는다)")
    ap.add_argument("--dlt-start", help="회차 시작 전 DLT 건수 파일 (dlt-start.txt). 주면 DLT 증가를 기록한다")
    ap.add_argument("--dlt-end", help="소진 뒤 DLT 건수 파일 (dlt-end.txt)")
    ap.add_argument("--account-log", help="계좌 서비스 로그 (재시작 뒤 같은 eventId가 걸러진 횟수를 센다)")
    a = ap.parse_args(argv)
    CONTAINER = a.container
    try:
        out, invalid, violations = judge(a.requests, a.summary, a.events, a.lag, a.sql_s6, a.sql_base, a.ipo, a.expect_requests,
                                         a.dlt_start, a.dlt_end, a.account_log)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as e:
        print(f"판정 입력을 읽지 못함: {e}", file=sys.stderr)
        return 1
    if a.warmup:
        out.insert(3, f"워밍업: {a.warmup}")
    print("\n".join(out))
    if invalid:
        return 3
    return 4 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
