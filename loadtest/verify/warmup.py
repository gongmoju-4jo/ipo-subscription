"""정식 측정의 워밍업 판정: 일정한 부하를 거는 동안 응답 시간 추세가 평평해졌는지(기울기 0 근처) 본다.

사용: python warmup.py --since <워밍업 시작 epoch초> --csv <warmup.csv> [--prometheus http://127.0.0.1:9090]
      [--bucket 15] [--window 8] [--slope-pct 5] [--slope-ms 1] [--consecutive 2] [--min 180] [--max 900]

- 구간(bucket)마다 게이트웨이 평균 응답 시간을 Prometheus에서 읽는다 (구간 동안의 요청 시간 합 / 요청 수, actuator 제외)
- 구간이 끝날 때마다 최근 window개 구간에 직선을 맞추고, 기울기를 "분당 창 평균 대비 %"와 "분당 ms"로 바꾼다
- 기울기가 ±slope-pct % 이내이거나 ±slope-ms ms 이내면 그 판정은 통과다
  - 응답이 수 ms로 작으면 %로 본 허용 폭이 분당 1ms 아래로 좁아져, 평평한 상태의 작은 흔들림도 통과하지 못한다. 절대 기울기는 그 경우를 받아 준다
  - slope-ms를 0으로 주면 절대 기울기는 보지 않는다
- 통과가 consecutive번 연속이고, 시작 후 min초가 지났으면 정상 상태로 보고 끝낸다
- 구간마다 warmup.csv에 한 줄씩 남긴다 (끝 시각, 요청 수, 평균 ms, 분당 기울기 %, 분당 기울기 ms, 판정)

종료 코드: 0 정상 상태 도달 / 2 max초 안에 도달하지 못함 / 3 Prometheus 조회가 연속 3번 실패 / 1 인자
요청이 0건인 구간은 조회 실패로 센다 (부하가 끊겼는데 평평하다고 판정하지 않게).
조회가 실패하면 창과 연속 통과 수를 비우고 다시 연속 window개 구간을 모은 뒤 판정한다
"""
import argparse
import csv
import json
import sys
import time
import urllib.parse
import urllib.request

SUM = 'sum(increase(http_server_requests_seconds_sum{{job="api-gateway",uri!~"/actuator.*"}}[{w}s]))'
CNT = 'sum(increase(http_server_requests_seconds_count{{job="api-gateway",uri!~"/actuator.*"}}[{w}s]))'


def prom_value(base, query, at):
    url = base.rstrip("/") + "/api/v1/query?" + urllib.parse.urlencode({"query": query, "time": at})
    with urllib.request.urlopen(url, timeout=10) as r:
        d = json.load(r)
    if d.get("status") != "success":
        raise RuntimeError(f"Prometheus 응답 실패: {d}")
    res = d["data"]["result"]
    if not res:
        raise RuntimeError("결과 없음")
    return float(res[0]["value"][1])


def bucket_avg_ms(base, end, bucket):
    """end 시각까지 bucket초 동안의 (요청 수, 평균 ms). 요청 0건이면 RuntimeError"""
    s = prom_value(base, SUM.format(w=bucket), end)
    c = prom_value(base, CNT.format(w=bucket), end)
    if c <= 0:
        raise RuntimeError("구간 요청 0건")
    return c, s / c * 1000


def slope_ms_per_min(values, bucket):
    """구간 평균 목록에 최소제곱 직선을 맞춘 기울기 (ms/분)"""
    n = len(values)
    xs = [i * bucket for i in range(n)]
    mx, my = sum(xs) / n, sum(values) / n
    den = sum((x - mx) ** 2 for x in xs)
    if den == 0:
        return None
    return sum((x - mx) * (y - my) for x, y in zip(xs, values)) / den * 60


def slope_pct_per_min(values, bucket):
    """위 기울기를 분당 창 평균 대비 %로"""
    b, my = slope_ms_per_min(values, bucket), sum(values) / len(values)
    if b is None or my <= 0:
        return None
    return b / my * 100


def is_flat(sp, sm, slope_pct, slope_ms):
    """상대 기울기(%/분)나 절대 기울기(ms/분) 중 하나라도 허용 범위 안이면 평평하다고 본다"""
    if sp is None:
        return False
    return abs(sp) <= slope_pct or (slope_ms > 0 and sm is not None and abs(sm) <= slope_ms)


def decide(values, bucket, window, slope_pct, consecutive, streak, slope_ms=0.0):
    """새 구간이 들어온 뒤의 (분당 기울기 %, 이어진 통과 수, 이번 판정 통과 여부). slope_ms가 0이면 상대 기울기만 본다"""
    if len(values) < window:
        return None, 0, False
    recent = values[-window:]
    sp = slope_pct_per_min(recent, bucket)
    passed = is_flat(sp, slope_ms_per_min(recent, bucket), slope_pct, slope_ms)
    streak = streak + 1 if passed else 0
    return sp, streak, passed and streak >= consecutive


def run(args, now=time.time, sleep=time.sleep, read=bucket_avg_ms):
    values, streak, fails = [], 0, 0
    end = args.since + args.bucket
    with open(args.csv, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["bucket_end_epoch", "elapsed_s", "requests", "avg_ms", "slope_pct_per_min", "slope_ms_per_min", "streak", "result"])
        while True:
            # 구간이 끝나고 Prometheus 수집(5초)이 한 번 더 지난 뒤 읽는다
            wait = end + 6 - now()
            if wait > 0:
                sleep(wait)
            elapsed = end - args.since
            try:
                c, avg = read(args.prometheus, end, args.bucket)
                fails = 0
            except (OSError, ValueError, RuntimeError, KeyError) as e:
                fails += 1
                # 빠진 구간을 건너뛰고 이어 붙이면 창의 시간 축이 어긋나고, 실패를 사이에 둔 두 통과가 연속으로 세어진다.
                # 그래서 창과 연속 통과 수를 비우고 다시 연속 구간을 모은다 (판정이 최대 창 길이만큼 늦어질 수 있다)
                values, streak = [], 0
                w.writerow([end, elapsed, "", "", "", "", streak, f"조회 실패: {e}"]); f.flush()
                if fails >= 3:
                    print(f"warmup.py: Prometheus 조회가 연속 {fails}번 실패", file=sys.stderr)
                    return 3
                end += args.bucket
                continue
            values.append(avg)
            sp, streak, done = decide(values, args.bucket, args.window, args.slope_pct, args.consecutive, streak, args.slope_ms)
            sm = slope_ms_per_min(values[-args.window:], args.bucket) if sp is not None else None
            done = done and elapsed >= args.min
            result = "정상 상태" if done else ("창 채우는 중" if sp is None else ("통과" if is_flat(sp, sm, args.slope_pct, args.slope_ms) else "아직"))
            w.writerow([end, elapsed, round(c), round(avg, 2), "" if sp is None else round(sp, 2),
                        "" if sm is None else round(sm, 3), streak, result]); f.flush()
            if done:
                print(f"warmup.py: 정상 상태 {elapsed}초 (최근 {args.window}구간 분당 기울기 {sp:.2f}%, {sm:.3f}ms, 연속 {streak}번 통과)")
                return 0
            if elapsed >= args.max:
                print(f"warmup.py: {args.max}초 안에 정상 상태에 닿지 않음 (마지막 분당 기울기 {sp})", file=sys.stderr)
                return 2
            end += args.bucket


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", type=int, required=True)
    ap.add_argument("--csv", required=True)
    ap.add_argument("--prometheus", default="http://127.0.0.1:9090")
    ap.add_argument("--bucket", type=int, default=15)
    ap.add_argument("--window", type=int, default=8)
    ap.add_argument("--slope-pct", type=float, default=5.0)
    ap.add_argument("--slope-ms", type=float, default=1.0)
    ap.add_argument("--consecutive", type=int, default=2)
    ap.add_argument("--min", type=int, default=180)
    ap.add_argument("--max", type=int, default=900)
    a = ap.parse_args(argv)
    if (a.bucket < 5 or a.window < 3 or a.consecutive < 1 or a.min < 0 or a.max < a.min or a.slope_pct <= 0
            or a.slope_ms < 0):
        print("warmup.py: 인자 범위가 올바르지 않음", file=sys.stderr)
        return 1
    return run(a)


if __name__ == "__main__":
    sys.exit(main())
