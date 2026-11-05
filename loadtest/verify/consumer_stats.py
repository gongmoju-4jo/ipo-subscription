"""정식 측정(official.sh) 결과 폴더에서 계좌 서비스 증거금 인출 Consumer(account-margin-reserve)의 S1 처리량, 랙, 오류를 정리한다.

사용: python loadtest/verify/consumer_stats.py --batch <official.sh의 --batch 이름> [--prometheus http://127.0.0.1:9090] [--container ipo-postgres]
입력: loadtest/results/<batch>/manifest.csv (S1 실행만), env.txt
출력 (같은 폴더):
  인출.md               실행별 표, 단계별 중앙값, 병목 의심 단계
  인출.csv              실행별 5초 단위 원본 (랙, 리스너 누적 건수, 그 5초에 생긴 증거금 수)
  인출-prometheus.json  Prometheus query_range 응답 원본 (보관 기간이 지나도 다시 볼 수 있게)

측정 구간: 실행마다 k6_start ~ k6_end + drain_s (소진 대기까지). "부하 중"은 k6_start ~ k6_end
구분 (원천):
  인출 성공      이번 실행 공모주의 margin 중 created_at이 측정 구간 안 (DB)
  고유 이벤트 처리 processed_event(consumer_name=account-margin-reserve)의 processed_at이 측정 구간 안. 같은 eventId 중복 수신, DLT로 간 건은 빠진다 (DB)
  거절           account.outbox topic=margin.reserve-failed, created_at이 측정 구간 안 (DB)
  재발행         account.outbox topic=margin.reserved 건수 - 인출 성공 (이미 PENDING인 증거금에 온 재요청) (DB)
  기타           고유 이벤트 처리 - 성공 - 거절 - 재발행 (선행 반환으로 건너뜀, POSTED/VOIDED 증거금에 온 요청)
  전체 수신      spring_kafka_listener_seconds_count 증가분 (Prometheus, 중복 수신과 재시도 포함)
  오류           위 리스너 지표의 result=failure 증가분
  랙             sum(kafka_consumer_fetch_manager_records_lag{topic="margin_reserve-requested"}) (Consumer가 계산한 값. 처리 중 멈추면 갱신이 늦다)
병목 의심 (단계마다): 랙 증가 추세 AND 처리량 정체. 자동 분석은 후보만 찾는다
  랙 증가 추세  실행마다 부하 중 랙의 기울기(최소제곱) > 0. 반복 중 과반이면 그 단계는 증가 추세
  처리량 정체  목표 속도를 올렸는데 고유 처리 건/초(부하 중, 반복 중앙값)가 이전 단계 반복 중 최댓값을 넘지 못함. 첫 단계는 비교할 단계가 없어 판정하지 않음
  둘 다 짧은 구간과 반복 변동에 흔들리므로 확정 판정이 아니다. 최종 판단은 인출.csv의 랙, 처리량 시계열과 자원.md(CPU), slow-queries.txt(DB)를 함께 보고 내린다
실패로 끝나는 경우(종료 코드 1): S1 실행이 없음, DB 또는 Prometheus 조회 실패, 랙 시계열이 없음, 랙 저장 샘플이 기대의 90% 미만
"""
import argparse
import csv
import datetime as dt
import json
import os
import re
import statistics
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

CONSUMER = "account-margin-reserve"
LAG_Q = 'sum(kafka_consumer_fetch_manager_records_lag{topic="margin_reserve-requested"})'
LISTENER_Q = 'sum by (result) (spring_kafka_listener_seconds_count{name=~"account-margin-reserve-[0-9]+"})'
LISTENER_SCRAPE_Q = 'max(timestamp(spring_kafka_listener_seconds_count{name=~"account-margin-reserve-[0-9]+"}))'
LAG_SAMPLES_Q = 'max(count_over_time(kafka_consumer_fetch_manager_records_lag{{topic="margin_reserve-requested"}}[{d}s]))'
STEP = 5
SAMPLE_RATIO = 0.9
HERE = os.path.dirname(os.path.abspath(__file__))


# ---------- 입력 ----------
def read_s1_runs(path):
    """manifest.csv의 S1 실행 → [dict(run, rep, rate, ipos, start, end, drain)]"""
    with open(path, encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    runs = []
    for r in rows:
        if r.get("scenario") != "S1":
            continue
        for k in ("target_rate", "k6_start", "k6_end", "drain_s"):
            if not (r.get(k) or "").isdigit():
                raise ValueError(f"manifest {r.get('run_id')}: {k}가 숫자가 아님")
        ipos = [i for i in r["ipo_ids"].split(";") if i]
        for i in ipos:
            if not re.fullmatch(r"[0-9a-fA-F-]{36}", i):
                raise ValueError(f"manifest {r['run_id']}: 공모주 ID 형식이 아님: {i}")
        if not ipos:
            raise ValueError(f"manifest {r['run_id']}: 공모주 ID 없음")
        runs.append(dict(run=r["run_id"], rep=r.get("repeat", ""), rate=int(r["target_rate"]), ipos=ipos,
                         start=int(r["k6_start"]), end=int(r["k6_end"]), drain=int(r["drain_s"])))
    return runs


def env_lines(path):
    """env.txt에서 커밋, dirty, 계좌/DB/Kafka 자원 줄만"""
    keep = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith(("git=", "dirty_files=")) or re.match(r"^자원 ipo-(account-service|postgres|kafka) ", line) \
                    or "ALLOW_DIRTY=" in line or line.startswith("설정 GAP="):
                keep.append(line)
    return keep


# ---------- 조회 ----------
def query_db(run, container):
    """측정 구간과 부하 중 건수, 증거금 생성 5초 버킷. 실패하면 RuntimeError (0으로 넘기지 않음)"""
    ins = ",".join(f"'{i}'" for i in run["ipos"])
    w0, w1, l1 = run["start"], run["end"] + run["drain"], run["end"]
    win = lambda col: f"{col} >= to_timestamp({w0}) and {col} <= to_timestamp({w1})"
    sql = ("select "
           f"(select count(*) from account.margin where ipo_id in ({ins}) and {win('created_at')}), "
           f"(select count(*) from account.processed_event where consumer_name = '{CONSUMER}' and {win('processed_at')}), "
           f"(select count(*) from account.processed_event where consumer_name = '{CONSUMER}' "
           f"and processed_at >= to_timestamp({w0}) and processed_at <= to_timestamp({l1})), "
           f"(select count(*) from account.outbox where topic = 'margin.reserve-failed' and {win('created_at')}), "
           f"(select count(*) from account.outbox where topic = 'margin.reserved' and {win('created_at')}), "
           f"(select coalesce(extract(epoch from min(created_at)), 0) from account.margin where ipo_id in ({ins}) and {win('created_at')}), "
           f"(select coalesce(extract(epoch from max(created_at)), 0) from account.margin where ipo_id in ({ins}) and {win('created_at')})")
    p = psql(sql, container)[0]
    if len(p) != 7:
        raise RuntimeError(f"psql 출력 형식이 다르다: {p}")
    d = dict(success=int(p[0]), processed=int(p[1]), processed_load=int(p[2]), rejected=int(p[3]), reserved=int(p[4]),
             first=float(p[5]), last=float(p[6]))
    rows = psql(f"select floor(extract(epoch from created_at) / {STEP})::bigint * {STEP}, count(*) from account.margin "
                f"where ipo_id in ({ins}) and {win('created_at')} group by 1 order by 1", container)
    d["buckets"] = {int(t): int(n) for t, n in rows if t}
    return d


def psql(sql, container):
    r = subprocess.run(["docker", "exec", "-i", container, "psql", "-U", "postgres", "-d", "ipo", "-v", "ON_ERROR_STOP=1",
                        "-qtA", "-F", ",", "-c", sql], capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise RuntimeError(f"psql 실패: {r.stderr.strip()}")
    return [line.split(",") for line in r.stdout.strip().splitlines()]


def prom_get(base, path, params):
    url = base.rstrip("/") + path + "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=30) as resp:
        d = json.load(resp)
    if d.get("status") != "success":
        raise RuntimeError(f"Prometheus 실패: {d}")
    return d


def query_prom(run, base, get=prom_get):
    """(원본 응답 dict, 랙 [(t, v)], 리스너 {result: [(t, v)]}, 저장 샘플 수, 리스너 최종 수집 시각). 랙 시계열이 없으면 RuntimeError"""
    w0, w1 = run["start"], run["end"] + run["drain"]
    rng = {"start": w0, "end": w1, "step": STEP}
    raw = {"lag": get(base, "/api/v1/query_range", {"query": LAG_Q, **rng}),
           # 누적값은 5초마다 수집되므로 소진 확인 뒤 한 번 더 수집된 값(+STEP)까지 읽는다. 실행 사이 GAP(>= STEP) 동안 요청이 없어 다음 실행은 섞이지 않는다
           "listener": get(base, "/api/v1/query_range", {"query": LISTENER_Q, **rng, "end": w1 + STEP}),
           "listener_scrape": get(base, "/api/v1/query", {"query": LISTENER_SCRAPE_Q, "time": w1 + STEP}),
           "lag_samples": get(base, "/api/v1/query", {"query": LAG_SAMPLES_Q.format(d=w1 - w0), "time": w1})}
    lag_res = raw["lag"]["data"]["result"]
    if not lag_res:
        raise RuntimeError(f"{run['run']}: 랙 시계열이 없음 ({LAG_Q})")
    lag = [(int(float(t)), float(v)) for t, v in lag_res[0]["values"]]
    listener = {s["metric"].get("result", "?"): [(int(float(t)), float(v)) for t, v in s["values"]]
                for s in raw["listener"]["data"]["result"]}
    if not listener:
        raise RuntimeError(f"{run['run']}: 리스너 시계열이 없음 ({LISTENER_Q})")
    smp = raw["lag_samples"]["data"]["result"]
    samples = int(float(smp[0]["value"][1])) if smp else 0
    sc = raw["listener_scrape"]["data"]["result"]
    scrape = float(sc[0]["value"][1]) if sc else 0.0
    return raw, lag, listener, samples, scrape


def clock_offsets(container, base):
    """분석 시점의 시계 차이(초): DB 컨테이너 - 이 PC, Prometheus - 이 PC. 측정 시점 값이 아니다"""
    t0 = dt.datetime.now().timestamp()
    db_now = float(psql("select extract(epoch from clock_timestamp())", container)[0][0])
    t1 = dt.datetime.now().timestamp()
    prom_now = float(prom_get(base, "/api/v1/query", {"query": "time()"})["data"]["result"][0])
    t2 = dt.datetime.now().timestamp()
    return dict(db=db_now - (t0 + t1) / 2, prom=prom_now - (t1 + t2) / 2)


# ---------- 계산 ----------
def slope(points):
    """최소제곱 기울기 (건/초). 점이 2개 미만이면 0"""
    if len(points) < 2:
        return 0.0
    n = len(points)
    mx = sum(t for t, _ in points) / n
    my = sum(v for _, v in points) / n
    den = sum((t - mx) ** 2 for t, _ in points)
    return sum((t - mx) * (v - my) for t, v in points) / den if den else 0.0


def increase(series, start):
    """누적 지표의 구간 증가분. 구간 시작에 없던 시계열(구간 중 처음 실패 등)은 0에서 시작한 것으로 본다"""
    if not series:
        return 0.0
    base = series[0][1] if series[0][0] <= start + STEP else 0.0
    return series[-1][1] - base


def judge_run(lag_load):
    """부하 중 랙이 증가 추세인지 판정. lag_load가 비어 있으면 ValueError (판정할 수 없음을 통과로 넘기지 않음)"""
    if not lag_load:
        raise ValueError("부하 구간 랙 샘플이 없음")
    vals = [v for _, v in lag_load]
    peak, end, sl = max(vals), vals[-1], slope(lag_load)
    rising = sl > 0
    return dict(lag_max=peak, lag_end=end, lag_slope=sl, rising=rising)


def analyze(run, db, lag, listener, samples, scrape):
    load = run["end"] - run["start"]
    window = run["end"] + run["drain"] - run["start"]
    lag_load = [(t, v) for t, v in lag if t <= run["end"]]
    rate_load = db["processed_load"] / load if load > 0 else 0.0
    j = judge_run(lag_load)
    ok = increase(listener.get("success", []), run["start"])
    fail = increase(listener.get("failure", []), run["start"])
    received = ok + fail
    span = db["last"] - db["first"]
    return dict(run=run["run"], rate=run["rate"], rep=run["rep"], load_s=load, window_s=window,
                success=db["success"], success_rate=db["success"] / span if span > 0 else 0.0,
                success_rate_window=db["success"] / window if window > 0 else 0.0,
                processed=db["processed"], processed_load_rate=rate_load, rejected=db["rejected"],
                republished=db["reserved"] - db["success"], other=db["processed"] - db["success"] - db["rejected"] - (db["reserved"] - db["success"]),
                received=received, failure=fail, error_pct=(fail / received * 100) if received else 0.0,
                samples=samples, samples_expected=window // STEP, listener_scrape=scrape, **j)


def check_scrape(r, run):
    w1 = run["end"] + run["drain"]
    if r["listener_scrape"] <= w1:
        return f"{r['run']}: 리스너 최종 수집 시각이 소진 확인({kst(w1)}) 이전 → 마지막 처리분이 전체 수신에서 빠졌을 수 있음"
    return None


def check_samples(r):
    exp = r["samples_expected"]
    if exp > 0 and r["samples"] < SAMPLE_RATIO * exp:
        return f"{r['run']}: 랙 저장 샘플 {r['samples']}개 < 기대 {exp}개의 {int(SAMPLE_RATIO * 100)}% (수집 누락)"
    return None


def stage_rows(results):
    """단계(목표 속도)별 반복 중앙값, 랙 증가 추세(반복 과반), 처리량 정체(이전 단계 최댓값과 비교), 병목 의심"""
    out, prev_max = [], None
    for rate in sorted({r["rate"] for r in results}):
        rs = [r for r in results if r["rate"] == rate]
        med = lambda k: statistics.median(r[k] for r in rs)
        n_rise = sum(r["rising"] for r in rs)
        rising = n_rise * 2 > len(rs)
        processed = med("processed_load_rate")
        plateau = prev_max is not None and processed <= prev_max
        out.append(dict(rate=rate, n=len(rs), success_rate=med("success_rate"), processed_load_rate=processed,
                        lag_max=med("lag_max"), lag_end=med("lag_end"), lag_slope=med("lag_slope"), error_pct=med("error_pct"),
                        rises=n_rise, rising=rising, prev_max=prev_max, plateau=plateau, suspect=rising and plateau))
        prev_max = max(r["processed_load_rate"] for r in rs)
    return out


# ---------- 출력 ----------
def kst(t):
    return dt.datetime.fromtimestamp(t, dt.timezone(dt.timedelta(hours=9))).strftime("%H:%M:%S")


def render(batch, env, runs, results, stages, problems, clock=None):
    yn = lambda b: "예" if b else "아니오"
    L = [f"# 증거금 인출 Consumer 처리량 ({batch})", "", "## 측정 환경 (env.txt)", ""] + [f"- `{x}`" for x in env] + [""]
    L += ["## 정의", "",
          "- 측정 구간: k6 시작 ~ k6 종료 + 소진 대기. 부하 중: k6 시작 ~ 종료",
          "- 인출 성공 건/초: 이번 실행 공모주의 새 증거금 수 / (첫 생성 ~ 마지막 생성 시각)",
          "- 고유 처리 건/초(부하 중): processed_event 건수(중복 eventId, DLT 제외) / 부하 시간",
          "- 재발행: 이미 PENDING인 증거금에 온 재요청 (margin.reserved Outbox - 성공). 기타: 고유 처리 - 성공 - 거절 - 재발행",
          "- 전체 수신, 오류: 리스너 지표(spring_kafka_listener) 증가분. 중복 수신과 재시도 포함",
          "- 랙 증가 추세(실행): 부하 중 랙 기울기(최소제곱) > 0",
          "- 랙 증가 추세(단계): 반복 중 과반이 위 조건",
          "- 처리량 정체(단계): 목표를 올렸는데 고유 처리 건/초 중앙값이 이전 단계 반복 중 최댓값 이하. 첫 단계는 판정하지 않음. 병목 의심 = 둘 다",
          "- 자동 분석은 병목 후보만 찾는다. 기울기와 반복 변동에 흔들리므로 최종 판단은 시계열(인출.csv), CPU(자원.md), DB(slow-queries.txt)를 함께 보고 내린다", ""]
    L += ["## 단계별 (반복 중앙값)", "",
          "| 목표 건/초 | 반복 | 인출 성공 건/초 | 고유 처리 건/초(부하 중) | 이전 단계 최대 | 처리량 정체 | 랙 최대 | 부하 끝 랙 | 랙 기울기(건/초) | 랙 증가 추세 반복 | 오류율 | 판정 |",
          "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for s in stages:
        prev = "-" if s["prev_max"] is None else f"{s['prev_max']:.1f}"
        L.append(f"| {s['rate']} | {s['n']} | {s['success_rate']:.1f} | {s['processed_load_rate']:.1f} | {prev} | {'-' if s['prev_max'] is None else yn(s['plateau'])} | "
                 f"{s['lag_max']:.0f} | {s['lag_end']:.0f} | {s['lag_slope']:.2f} | {s['rises']}/{s['n']} | {s['error_pct']:.2f}% | "
                 f"{'병목 의심' if s['suspect'] else '의심 없음'} |")
    first = next((s for s in stages if s["suspect"]), None)
    L += ["", "**자동 분석:** " + (f"목표 {first['rate']}건/초부터 Consumer 병목 의심 (랙 증가 추세 + 처리량 정체). "
                                  "확정 전에 인출.csv 랙, 처리량 그래프와 자원.md CPU, slow-queries.txt를 확인한다." if first else
                                  "S1 모든 단계에서 Consumer 병목 의심 없음. 한계값을 만들지 않고 다른 병목(판정.md, 자원.md, slow-queries.txt)을 본다."), ""]
    L += ["## 실행별", "",
          "| 실행 | 구간(KST) | 성공 | 성공 건/초 | 고유 처리 | 거절 | 재발행 | 기타 | 전체 수신 | 오류 | 랙 최대/끝 | 기울기 | 증가 추세 | 랙 샘플 |",
          "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for run, r in zip(runs, results):
        L.append(f"| {r['run']} | {kst(run['start'])}~{kst(run['end'])}+{run['drain']}s | {r['success']} | {r['success_rate']:.1f} | {r['processed']} | "
                 f"{r['rejected']} | {r['republished']} | {r['other']} | {r['received']:.0f} | {r['failure']:.0f} | {r['lag_max']:.0f}/{r['lag_end']:.0f} | "
                 f"{r['lag_slope']:.2f} | {yn(r['rising'])} | {r['samples']}/{r['samples_expected']} |")
    L += ["", "## 시간 기준", "",
          "| 실행 | DB 집계 구간 | 고유 처리(부하 중) 구간 | 랙 조회 구간 | 리스너 누적값 기준 | 리스너 최종 수집 시각 |",
          "| --- | --- | --- | --- | --- | --- |"]
    for run, r in zip(runs, results):
        w1 = run["end"] + run["drain"]
        L.append(f"| {r['run']} | {kst(run['start'])} ~ {kst(w1)} | {kst(run['start'])} ~ {kst(run['end'])} | "
                 f"{kst(run['start'])} ~ {kst(w1)} (5초 간격 평가) | {kst(run['start'])} 직전 수집 → {kst(w1 + STEP)} 직전 수집 | "
                 f"{kst(r['listener_scrape']) if r['listener_scrape'] else '없음'} |")
    L += ["", "- DB 집계: margin.created_at, processed_event.processed_at, outbox.created_at이 구간 안인 행 (양 끝 포함). 시각은 서비스 컨테이너(JVM) 시계",
          "- 구간 경계(k6 시작, 종료, 소진 확인)는 official.sh가 이 PC 시계로 정수 초 단위로 기록",
          "- Prometheus: 5초마다 수집. 랙은 평가 시각 직전 수집값이라 최대 5초 늦다. 리스너 누적값은 구간 끝 뒤 첫 수집까지 읽어서 DB 구간보다 최대 5초 길다",
          "- 한계: 두 지표의 시간 기준이 달라 같은 구간의 건수가 경계에서 최대 수 초 어긋날 수 있다. 실행 사이 GAP(요청 없음) 동안은 처리할 메시지가 없어 "
          "GAP이 5초 이상이면 건수는 섞이지 않는다. 5초 단위 시계열(인출.csv)을 나란히 볼 때는 이 차이를 감안한다",
          "- 랙은 Consumer가 fetch할 때 계산한 값이다. 처리 중 멈춘 Consumer는 늦게 갱신되므로 소진 대기(커밋 기준 랙 0)와 같이 본다"]
    if clock:
        L.append(f"- 시계 차이 (분석 시점, 측정 시점 값 아님): DB 컨테이너 - 이 PC {clock['db']:+.2f}초, Prometheus - 이 PC {clock['prom']:+.2f}초")
    L.append("")
    if problems:
        L += ["## 문제", ""] + [f"- {p}" for p in problems] + [""]
    return "\n".join(L)


def write_csv(path, runs, series):
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["run_id", "epoch", "time_kst", "phase", "lag", "listener_success_total", "listener_failure_total", "margin_created_in_5s"])
        for run, (lag, listener, buckets) in zip(runs, series):
            ok = dict(listener.get("success", [])); fail = dict(listener.get("failure", []))
            for t, v in lag:
                w.writerow([run["run"], t, kst(t), "load" if t <= run["end"] else "drain", f"{v:g}",
                            f"{ok[t]:g}" if t in ok else "", f"{fail[t]:g}" if t in fail else "", buckets.get(t - t % STEP, 0)])


def main(argv=None, db=query_db, prom=query_prom, clock=clock_offsets):
    ap = argparse.ArgumentParser()
    ap.add_argument("--batch", required=True)
    ap.add_argument("--results", default=os.path.join(HERE, "..", "results"))
    ap.add_argument("--prometheus", default=os.environ.get("PROMETHEUS_URL", "http://127.0.0.1:9090"))
    ap.add_argument("--container", default="ipo-postgres")
    a = ap.parse_args(argv)
    out = os.path.join(a.results, a.batch)
    try:
        runs = read_s1_runs(os.path.join(out, "manifest.csv"))
        env = env_lines(os.path.join(out, "env.txt"))
        if not runs:
            raise ValueError("manifest.csv에 S1 실행이 없음")
        results, series, raws, problems = [], [], {}, []
        for run in runs:
            d = db(run, a.container)
            raw, lag, listener, samples, scrape = prom(run, a.prometheus)
            raws[run["run"]] = raw
            r = analyze(run, d, lag, listener, samples, scrape)
            results.append(r); series.append((lag, listener, d["buckets"]))
            problems += [p for p in (check_samples(r), check_scrape(r, run)) if p]
        offsets = clock(a.container, a.prometheus)
    except (OSError, ValueError, KeyError, RuntimeError, urllib.error.URLError, json.JSONDecodeError) as e:
        print(f"consumer_stats.py: {e}", file=sys.stderr)
        return 1
    with open(os.path.join(out, "인출-prometheus.json"), "w", encoding="utf-8") as f:
        json.dump(raws, f, ensure_ascii=False)
    write_csv(os.path.join(out, "인출.csv"), runs, series)
    with open(os.path.join(out, "인출.md"), "w", encoding="utf-8") as f:
        f.write(render(a.batch, env, runs, results, stage_rows(results), problems, offsets))
    for p in problems:
        print(f"consumer_stats.py: {p}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
