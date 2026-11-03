"""official.sh가 측정 중에 모은 docker stats 원본을 표로 바꾼다.

입력: docker-stats.raw (한 줄 = "<epoch초>\t<docker stats --format '{{json .}}' 한 줄>", 조회 실패는 "<epoch초>\tERROR\t<메시지>")
출력: docker-stats.csv (샘플마다 컨테이너 한 줄, 숫자 원본), 자원.md (실행마다 k6 구간의 컨테이너별 CPU, 메모리 최대)

사용: python docker_stats.py <docker-stats.raw> --manifest <manifest.csv> --env <env.txt> --csv <출력.csv> --md <출력.md>
- CPU %는 docker stats 기준이라 100% = 1코어다. env.txt의 "자원 <컨테이너> cpu=<코어>" 줄이 있으면 상한 대비 %도 낸다
- k6 컨테이너는 official.sh가 "k6-<run_id>" 이름으로 띄운다. 표에서는 "k6"로 묶는다
- 실패로 끝나는 경우(종료 코드 1): 샘플이 하나도 없음, manifest의 실행 중 k6 구간에 샘플이 없는 실행이 있음, 형식이 틀린 줄
"""
import argparse
import csv
import datetime as dt
import json
import re
import sys

BIN = {"B": 1, "KiB": 1024, "MiB": 1024 ** 2, "GiB": 1024 ** 3, "TiB": 1024 ** 4}
DEC = {"B": 1, "kB": 1000, "KB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3, "TB": 1000 ** 4}
SIZE = re.compile(r"^\s*([0-9.]+)\s*([A-Za-z]+)\s*$")


def size(text):
    """'731.3MiB', '1GiB', '965MB', '0B' → 바이트. 모르는 단위면 ValueError"""
    m = SIZE.match(text)
    if not m:
        raise ValueError(f"크기 형식이 아님: {text!r}")
    num, unit = float(m.group(1)), m.group(2)
    if unit in BIN:
        return num * BIN[unit]
    if unit in DEC:
        return num * DEC[unit]
    raise ValueError(f"모르는 단위: {text!r}")


def pair(text):
    a, b = text.split("/")
    return size(a), size(b)


def pct(text):
    t = text.strip()
    if not t.endswith("%"):
        raise ValueError(f"%가 아님: {text!r}")
    return float(t[:-1])


def parse_raw(path):
    """(샘플 목록, 조회 실패 수). 샘플 = dict(t, name, cpu, mem, mem_limit, mem_pct, pids, net_rx, net_tx, blk_r, blk_w)"""
    samples, errors = [], 0
    with open(path, encoding="utf-8") as f:
        for no, line in enumerate(f, 1):
            line = line.rstrip("\r\n")
            if not line:
                continue
            parts = line.split("\t", 2)
            if len(parts) < 2 or not parts[0].isdigit():
                raise ValueError(f"{path}:{no}: 형식이 틀린 줄: {line[:80]!r}")
            t = int(parts[0])
            if parts[1] == "ERROR":
                errors += 1
                continue
            d = json.loads(parts[1])
            mem, mem_limit = pair(d["MemUsage"])
            rx, tx = pair(d["NetIO"])
            br, bw = pair(d["BlockIO"])
            name = d["Name"]
            samples.append(dict(t=t, name=name, cpu=pct(d["CPUPerc"]), mem=mem, mem_limit=mem_limit, mem_pct=pct(d["MemPerc"]),
                                pids=int(d["PIDs"]) if str(d["PIDs"]).isdigit() else None, net_rx=rx, net_tx=tx, blk_r=br, blk_w=bw))
    return samples, errors


def group(name):
    return "k6" if name.startswith("k6-") else name


def read_limits(path):
    """env.txt의 '자원 <이름> cpu=<코어> mem=<바이트>' 줄 → {이름: 코어}"""
    lim = {}
    if not path:
        return lim
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = re.match(r"^자원 (\S+) cpu=([0-9.]+)", line)
            if m and float(m.group(2)) > 0:
                lim[m.group(1)] = float(m.group(2))
    return lim


def read_runs(path):
    with open(path, encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    runs = []
    for r in rows:
        if not r.get("run_id"):
            continue
        a, b = r.get("k6_start", ""), r.get("k6_end", "")
        if not (a.isdigit() and b.isdigit()):
            raise ValueError(f"manifest {r['run_id']}: k6_start, k6_end가 숫자가 아님")
        runs.append((r["run_id"], int(a), int(b)))
    return runs


def mib(x):
    return x / 1024 ** 2


def write_csv(samples, path):
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["epoch", "time_local", "container", "cpu_pct_of_1core", "mem_used_bytes", "mem_limit_bytes", "mem_pct",
                    "pids", "net_rx_bytes", "net_tx_bytes", "block_read_bytes", "block_write_bytes"])
        for s in samples:
            w.writerow([s["t"], dt.datetime.fromtimestamp(s["t"]).strftime("%Y-%m-%d %H:%M:%S"), s["name"], s["cpu"], int(s["mem"]),
                        int(s["mem_limit"]), s["mem_pct"], s["pids"] if s["pids"] is not None else "", int(s["net_rx"]), int(s["net_tx"]),
                        int(s["blk_r"]), int(s["blk_w"])])


def summarize(samples, errors, runs, limits):
    """(자원.md 본문, 문제 목록)"""
    problems = []
    if not samples:
        problems.append("샘플이 하나도 없음")
    times = sorted({s["t"] for s in samples})
    gaps = [b - a for a, b in zip(times, times[1:])]
    lines = ["# 컨테이너 자원 사용 (docker stats)", "",
             f"- 샘플 시각 {len(times)}개, 조회 실패 {errors}번, 샘플 간격 최대 {max(gaps) if gaps else '-'}초",
             "- CPU는 docker stats 기준(100% = 1코어). 상한 대비는 env.txt의 컨테이너 CPU 상한으로 나눈 값",
             "- 메모리는 docker stats의 사용량(컨테이너 메모리 상한 대비 %는 docker가 계산한 값). k6는 실행마다 `k6-<run_id>` 컨테이너",
             "- 구간은 실행마다 manifest의 k6_start ~ k6_end. 5초 간격 순간값이라 실제 최대보다 낮을 수 있다", ""]
    names = sorted({group(s["name"]) for s in samples})
    for run, a, b in runs:
        w = [s for s in samples if a <= s["t"] <= b and (not s["name"].startswith("k6-") or s["name"] == f"k6-{run}")]
        if not w:
            problems.append(f"{run}: k6 구간({a}~{b})에 샘플 없음")
            lines += [f"## {run}", "", "샘플 없음", ""]
            continue
        lines += [f"## {run}", "", f"샘플 시각 {len({s['t'] for s in w})}개", "",
                  "| 컨테이너 | CPU 최대 (1코어=100%) | CPU 평균 | 상한 대비 최대 | 메모리 최대 | 메모리 상한 대비 최대 |",
                  "| --- | --- | --- | --- | --- | --- |"]
        for n in names:
            v = [s for s in w if group(s["name"]) == n]
            if not v:
                lines.append(f"| {n} | 샘플 없음 | | | | |")
                continue
            cmax = max(s["cpu"] for s in v); cavg = sum(s["cpu"] for s in v) / len(v)
            lim = limits.get(n)
            rel = f"{cmax / (lim * 100) * 100:.0f}%" if lim else "-"
            mmax = max(v, key=lambda s: s["mem"])
            lines.append(f"| {n} | {cmax:.1f}% | {cavg:.1f}% | {rel} | {mib(mmax['mem']):.0f}MiB / {mib(mmax['mem_limit']):.0f}MiB | {max(s['mem_pct'] for s in v):.1f}% |")
        if not any(s["name"] == f"k6-{run}" for s in w):
            problems.append(f"{run}: k6 컨테이너(k6-{run}) 샘플 없음")
        lines.append("")
    if problems:
        lines += ["## 문제", ""] + [f"- {p}" for p in problems] + [""]
    return "\n".join(lines), problems


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("raw")
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--env")
    ap.add_argument("--csv", required=True)
    ap.add_argument("--md", required=True)
    a = ap.parse_args(argv)
    try:
        samples, errors = parse_raw(a.raw)
        runs = read_runs(a.manifest)
        limits = read_limits(a.env)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as e:
        print(f"docker_stats.py: {e}", file=sys.stderr)
        return 1
    write_csv(samples, a.csv)
    md, problems = summarize(samples, errors, runs, limits)
    with open(a.md, "w", encoding="utf-8") as f:
        f.write(md)
    for p in problems:
        print(f"docker_stats.py: {p}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
