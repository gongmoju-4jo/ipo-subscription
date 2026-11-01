#!/usr/bin/env python3
"""회차 보고서를 만든다 (표준 라이브러리만 사용).

k6 요약(summary.json), 요청 로그(requests.log), 같은 시간대의 Prometheus 지표를 합쳐서 results/<run_id>/report.md로 쓴다.
수치를 손으로 옮기다 틀리는 것을 막고, 모든 수치의 원본을 같은 폴더에 남긴다 (Prometheus 질의와 응답은 prometheus.json).

사용: python report.py --run-id <run_id> [--prometheus http://localhost:9090] [--pad 10]
  --prometheus  기본값은 환경변수 PROMETHEUS_URL 또는 http://localhost:9090 (컨테이너에서 실행하면 http://prometheus:9090)
  --pad         측정 구간 앞뒤로 더하는 초 (Prometheus 수집 간격이 5초라 구간이 짧으면 샘플이 모자란다)
서버 지표의 구간은 요청 로그의 첫 요청과 마지막 요청 시각에 앞뒤 pad초를 더한 값이다.
"""
import argparse
import collections
import datetime
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "verify"))
from check_requests import classify, parse  # noqa: E402

JOBS = ["api-gateway", "subscription-service", "account-service", "allocation-service", "eureka-server"]
TIME_RE = re.compile(r'time="(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)Z"')
HTTP = 'uri!~"/actuator.*"'
KST = datetime.timezone(datetime.timedelta(hours=9))


def diff(selector, by, r):
    """구간 안의 증가량: 끝 시점 합계 - 시작 시점 합계. 구간 안에서 처음 생긴 시계열(새 URI의 첫 요청 등)은
    시작 시점에 없으므로 0으로 본다. rate나 increase는 이런 시계열의 첫 값을 증가량으로 세지 않아 쓰지 않는다."""
    return f"(sum by ({by}) ({selector}) - (sum by ({by}) ({selector} offset {r}) or sum by ({by}) ({selector}) * 0))"


def queries(w):
    """이름 → PromQL. w는 구간 길이(초)."""
    r = f"{w}s"
    count = f"http_server_requests_seconds_count{{{HTTP}}}"
    count5 = f'http_server_requests_seconds_count{{status=~"5..",{HTTP}}}'
    total = f"http_server_requests_seconds_sum{{{HTTP}}}"
    bucket = f"http_server_requests_seconds_bucket{{{HTTP}}}"
    return {
        "requests": diff(count, "job", r),
        "errors_5xx": diff(count5, "job", r),
        "avg_ms": f"1000 * {diff(total, 'job', r)} / {diff(count, 'job', r)}",
        "p95_ms": f"1000 * histogram_quantile(0.95, {diff(bucket, 'le, job', r)})",
        "hikari_active_max": f'max by (job) (max_over_time(hikaricp_connections_active[{r}]))',
        "hikari_pending_max": f'max by (job) (max_over_time(hikaricp_connections_pending[{r}]))',
        "tomcat_busy_max": f'max by (job) (max_over_time(tomcat_threads_busy_threads[{r}]))',
        "cpu_max_pct": f'100 * max by (job) (max_over_time(process_cpu_usage[{r}]))',
        "heap_max_mib": f'max by (job) (max_over_time((sum by (job) (jvm_memory_used_bytes{{area="heap"}}))[{r}:5s])) / 1048576',
        "gc_pause_ms": f"1000 * {diff('jvm_gc_pause_seconds_sum', 'job', r)}",
        "producer_latency_avg_ms": f'avg by (job) (avg_over_time(kafka_producer_request_latency_avg[{r}]))',
        "producer_latency_max_ms": f'max by (job) (max_over_time(kafka_producer_request_latency_max[{r}]))',
    }


def prom_query(base, q, t):
    url = f"{base}/api/v1/query?" + urllib.parse.urlencode({"query": q, "time": f"{t:.3f}"})
    with urllib.request.urlopen(url, timeout=30) as resp:
        d = json.load(resp)
    if d.get("status") != "success":
        raise RuntimeError(d)
    return d["data"]["result"]


def num(v, nd=1):
    if v is None or (isinstance(v, float) and (math.isnan(v) or math.isinf(v))):
        return "-"
    return f"{v:,.{nd}f}" if nd else f"{v:,.0f}"


def kst(ts):
    return datetime.datetime.fromtimestamp(ts, KST).strftime("%Y-%m-%d %H:%M:%S")


def read_times_and_rows(log):
    times, rows = [], []
    with open(log, encoding="utf-8", errors="replace") as f:
        for line in f:
            d = parse(line)
            if not d:
                continue
            rows.append(d)
            m = TIME_RE.search(line)
            if m:
                times.append(datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=datetime.timezone.utc).timestamp())
    return times, rows


def k6_section(summary):
    m = summary["metrics"]
    dur, reqs, failed = m.get("http_req_duration", {}), m.get("http_reqs", {}), m.get("http_req_failed", {})
    checks, iters, dropped = m.get("checks", {}), m.get("iterations", {}), m.get("dropped_iterations", {})
    lines = ["## k6 요약", "", "| 지표 | 값 |", "|---|---|",
             f"| 요청 수 | {num(reqs.get('count'), 0)} |",
             f"| 처리량 (건/초) | {num(reqs.get('rate'))} |",
             f"| 평균 응답 (ms) | {num(dur.get('avg'))} |",
             f"| p90 / p95 / p99 (ms) | {num(dur.get('p(90)'))} / {num(dur.get('p(95)'))} / {num(dur.get('p(99)'))} |",
             f"| 최소 / 중앙값 / 최대 (ms) | {num(dur.get('min'))} / {num(dur.get('med'))} / {num(dur.get('max'))} |",
             f"| 실패율 (시스템 오류 기준) | {num(failed.get('value', 0) * 100, 2)}% ({num(failed.get('passes'), 0)}건) |",
             f"| check 통과율 | {num(checks.get('value', 0) * 100, 2)}% |",
             f"| 반복 수 / 밀린 반복(dropped) | {num(iters.get('count'), 0)} / {num(dropped.get('count', 0), 0)} |", ""]
    crossed, passed = [], []
    for name, v in m.items():
        for expr, bad in (v.get("thresholds") or {}).items():
            (crossed if bad else passed).append(f"`{name}` {expr}")
    lines.append("**임계값**: " + ("✅ 모두 통과" if not crossed else "❌ 위반 " + ", ".join(crossed)))
    if passed and crossed:
        lines.append("통과: " + ", ".join(passed))
    lines.append("")
    return lines


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--results-dir", default=os.path.join(HERE, "results"))
    ap.add_argument("--prometheus", default=os.environ.get("PROMETHEUS_URL", "http://localhost:9090"))
    ap.add_argument("--pad", type=int, default=10)
    ap.add_argument("--no-wait", action="store_true", help="마지막 수집을 기다리지 않는다")
    args = ap.parse_args()

    out = os.path.join(args.results_dir, args.run_id)
    summary_path, log_path = os.path.join(out, "summary.json"), os.path.join(out, "requests.log")
    for p in (summary_path, log_path):
        if not os.path.exists(p):
            sys.exit(f"파일이 없습니다: {p}")
    summary = json.load(open(summary_path, encoding="utf-8"))
    times, rows = read_times_and_rows(log_path)
    if not times:
        sys.exit("requests.log에서 요청 시각을 읽지 못했습니다")

    start, end = min(times) - args.pad, max(times) + args.pad
    window = max(int(math.ceil(end - start)), 5)
    scenario = rows[0].get("s", "-")

    report = [f"# 회차 보고서: {args.run_id}", "",
              "| 항목 | 값 |", "|---|---|",
              f"| 시나리오 | {scenario} |",
              f"| 요청 구간 | {kst(min(times))} ~ {kst(max(times))} (KST) |",
              f"| 서버 지표 구간 | {kst(start)} ~ {kst(end)} (앞뒤 {args.pad}초 포함, {window}초) |", ""]
    report += k6_section(summary)

    by_class = collections.Counter(classify(r) for r in rows)
    report += ["## 결과 분류 (요청 로그)", "", "| 분류 | 건수 |", "|---|---|"]
    report += [f"| {k} | {v} |" for k, v in by_class.most_common()]
    report += [f"| **합계** | **{sum(by_class.values())}** |", ""]
    ids = collections.defaultdict(list)
    for r in rows:
        ids[r["requestId"]].append(r.get("subscriptionId") or "")
    repeated = {k: v for k, v in ids.items() if len(v) > 1}
    mismatch = {k: v for k, v in repeated.items() if len(set(v)) > 1}
    report += [f"같은 requestId로 여러 번 온 요청 {len(repeated)}건, subscriptionId가 다른 것 {len(mismatch)}건", ""]

    raw, server_ok = {}, True
    if not args.no_wait:
        wait = end + 6 - time.time()  # 구간 끝 이후의 수집(5초 간격)이 끝날 때까지
        if wait > 0:
            print(f"Prometheus 수집을 기다립니다 ({wait:.0f}초)")
            time.sleep(wait)
    data = collections.defaultdict(dict)
    try:
        for name, q in queries(window).items():
            res = prom_query(args.prometheus, q, end)
            raw[name] = {"query": q, "time": end, "result": res}
            for item in res:
                try:
                    data[item["metric"].get("job", "?")][name] = float(item["value"][1])
                except (ValueError, KeyError):
                    pass
    except (urllib.error.URLError, OSError, RuntimeError) as e:
        server_ok = False
        report += ["## 서버 지표 (Prometheus)", "", f"Prometheus({args.prometheus})에 연결하지 못해 생략했습니다: {e}", ""]

    if server_ok:
        report += ["## 서버 지표 (Prometheus)", "",
                   "구간 안의 증가량(끝 시점 - 시작 시점)과 최대값입니다. 평균 응답과 p95는 구간 전체의 요청 기준이라 앞뒤 여유 시간에 영향받지 않습니다.", "",
                   "| 서비스 | 요청 수 | 평균 (ms) | p95 (ms) | 5xx | Hikari 활성 최대 | Hikari 대기 최대 | Tomcat 사용 최대 | CPU 최대 (%) | 힙 최대 (MiB) | GC 정지 (ms) | Producer 지연 평균/최대 (ms) |",
                   "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for job in JOBS:
            d = data.get(job)
            if not d:
                continue
            report.append("| " + " | ".join([
                job, num(d.get("requests"), 0), num(d.get("avg_ms")), num(d.get("p95_ms")), num(d.get("errors_5xx", 0), 0),
                num(d.get("hikari_active_max"), 0), num(d.get("hikari_pending_max"), 0), num(d.get("tomcat_busy_max"), 0),
                num(d.get("cpu_max_pct")), num(d.get("heap_max_mib"), 0), num(d.get("gc_pause_ms")),
                (num(d.get("producer_latency_avg_ms")) + " / " + num(d.get("producer_latency_max_ms"))) if "producer_latency_avg_ms" in d else "-"]) + " |")
        report += ["",
                   "- Hikari, Tomcat 지표는 서비스 3개(account, subscription, allocation)에만 있습니다. Gateway는 WebFlux라 Tomcat 지표가 없습니다.",
                   "- 요청 수는 구간 안의 모든 요청을 셉니다. k6 요청 수와 다르면 앞뒤 여유 시간에 다른 요청(시드, 이전 회차)이 섞인 것이니 시드나 이전 회차 직후에는 측정하지 않습니다.",
                   "- Hikari 활성, Tomcat 사용, CPU 같은 순간값은 5초 간격 수집값의 최대라서, 짧은 부하에서는 실제 최대보다 낮게 보일 수 있습니다 (0으로 보여도 쓰지 않았다는 뜻이 아닙니다).",
                   "- CPU는 JVM 프로세스의 사용률이고, 컨테이너 CPU 상한이 있으면 그 상한 기준입니다. GC 정지는 GC가 일어나야 값이 생깁니다.", ""]
        json.dump(raw, open(os.path.join(out, "prometheus.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    report += ["## 원본", "", "| 파일 | 내용 |", "|---|---|", "| `summary.json` | k6 요약 |", "| `requests.log` | 요청별 requestId, subscriptionId, 상태 코드 |"]
    if server_ok:
        report.append("| `prometheus.json` | 서버 지표의 질의와 응답 |")
    for extra, desc in (("precheck.txt", "사전 점검 결과"), ("sql_result.txt", "정합성 SQL 결과")):
        if os.path.exists(os.path.join(out, extra)):
            report.append(f"| `{extra}` | {desc} |")
    report += ["", "측정하지 않은 항목은 `-`입니다. 환경(머신, 자원 상한, k6 실행 위치)은 별도로 기록합니다.", ""]

    path = os.path.join(out, "report.md")
    open(path, "w", encoding="utf-8", newline="\n").write("\n".join(report))
    print(f"→ {path}")


if __name__ == "__main__":
    main()
