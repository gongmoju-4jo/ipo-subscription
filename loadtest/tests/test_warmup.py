"""warmup.py 판정 테스트 (Prometheus 없이 돈다. 구간 평균 읽기는 가짜 함수로 바꾼다)

사용: python loadtest/tests/test_warmup.py
"""
import argparse
import csv
import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("warmup", os.path.join(HERE, "..", "verify", "warmup.py"))
wu = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wu)


# official-1104-d 워밍업의 15초 구간 평균(ms). 900초 동안 상대 기울기 기준으로 정상 상태가 나오지 않았다
JITTER = [23.08, 10.15, 7.71, 7.18, 5.71, 1.96, 1.92, 3.37, 3.25, 6.69, 6.54, 6.21, 6.41, 5.77, 3.32, 3.66, 6.95, 6.34, 6.9,
          6.58, 6.45, 6.79, 6.41, 4.1, 2.2, 6.48, 7.1, 6.69, 6.31, 6.92, 2.74, 1.9, 4.38, 2.16, 6.28, 5.94, 5.87, 7.33, 5.34,
          2.2, 4.37, 1.95, 2.53, 6.48, 6.03, 7.03, 6.36, 5.3, 1.88, 5.49, 6.08, 5.94, 5.91, 10.01, 5.79, 7.08, 4.31, 2.2, 6.25,
          6.07]


class Clock:
    """run()이 sleep으로 기다리는 시간을 실제로 기다리지 않고 시각만 옮긴다"""
    def __init__(self, t):
        self.t = t

    def now(self):
        return self.t

    def sleep(self, s):
        self.t += s


def args(**kw):
    d = dict(since=1000, csv=os.path.join(tempfile.mkdtemp(), "w.csv"), prometheus="x", bucket=15, window=8,
             slope_pct=5.0, slope_ms=1.0, consecutive=2, min=180, max=900)
    d.update(kw)
    return argparse.Namespace(**d)


def run_with(series, **kw):
    """series: 구간 번호(0부터) → 평균 ms. 예외를 넣으면 그 구간은 조회 실패"""
    a = args(**kw)
    clk = Clock(a.since)
    calls = []

    def read(base, end, bucket):
        i = (end - a.since) // bucket - 1
        calls.append(end)
        v = series(i)
        if isinstance(v, Exception):
            raise v
        return 3000, v

    rc = wu.run(a, now=clk.now, sleep=clk.sleep, read=read)
    with open(a.csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    return rc, rows, calls, clk


class Slope(unittest.TestCase):
    def test_flat_is_zero(self):
        self.assertEqual(wu.slope_pct_per_min([100.0] * 8, 15), 0.0)

    def test_falling_3ms_per_bucket(self):
        # 15초마다 3ms씩 → 분당 12ms, 창 평균 109.5ms → 약 -10.96%/분
        self.assertAlmostEqual(wu.slope_pct_per_min([120, 117, 114, 111, 108, 105, 102, 99], 15), -10.958, places=2)

    def test_decide_needs_full_window_and_streak(self):
        self.assertEqual(wu.decide([100] * 7, 15, 8, 5, 2, 0), (None, 0, False))
        sp, streak, done = wu.decide([100] * 8, 15, 8, 5, 2, 0)
        self.assertEqual((streak, done), (1, False))
        sp, streak, done = wu.decide([100] * 8, 15, 8, 5, 2, 1)
        self.assertEqual((streak, done), (2, True))

    def test_ms_slope(self):
        # 15초마다 3ms씩 내려감 → 분당 -12ms
        self.assertAlmostEqual(wu.slope_ms_per_min([120, 117, 114, 111, 108, 105, 102, 99], 15), -12.0, places=6)

    def test_small_values_pass_by_ms_even_when_pct_is_large(self):
        # 창 평균 약 4ms에서 분당 0.6ms 오르는 창: 상대로는 약 15%/분이라 %만 보면 "아직", 절대로는 1ms 이내라 통과
        vals = [3.0 + 0.15 * i for i in range(8)]
        sp = wu.slope_pct_per_min(vals, 15)
        self.assertGreater(abs(sp), 5)
        self.assertFalse(wu.decide(vals, 15, 8, 5, 2, 0)[2] or wu.decide(vals, 15, 8, 5, 2, 0)[1])
        self.assertEqual(wu.decide(vals, 15, 8, 5, 2, 0, slope_ms=1.0)[1], 1)

    def test_slope_ms_zero_turns_absolute_check_off(self):
        vals = [3.0 + 0.15 * i for i in range(8)]
        self.assertEqual(wu.decide(vals, 15, 8, 5, 2, 0, slope_ms=0.0)[1], 0)

    def test_decide_resets_streak_when_not_flat(self):
        sp, streak, done = wu.decide([120, 117, 114, 111, 108, 105, 102, 99], 15, 8, 5, 2, 5)
        self.assertEqual((streak, done), (0, False))


class Run(unittest.TestCase):
    def test_flat_from_start_waits_for_min(self):
        rc, rows, calls, _ = run_with(lambda i: 100.0)
        self.assertEqual(rc, 0)
        # 창이 8구간(120초)에 차고 연속 2번 통과는 135초에 되지만 최소 180초까지 기다린다
        self.assertEqual(int(rows[-1]["elapsed_s"]), 180)
        self.assertEqual(rows[-1]["result"], "정상 상태")

    def test_falling_then_flat_ends_after_two_flat_judgements(self):
        # 20구간(300초) 동안 내려가다가 평평해짐 → 창이 평평한 값으로 찬 뒤 연속 2번 통과해야 끝
        rc, rows, _, _ = run_with(lambda i: 200.0 - 5 * i if i < 20 else 100.0)
        self.assertEqual(rc, 0)
        end = int(rows[-1]["elapsed_s"])
        self.assertGreater(end, 300)
        flat_rows = [r for r in rows if r["result"] in ("통과", "정상 상태")]
        self.assertGreaterEqual(len(flat_rows), 2)
        self.assertTrue(all(abs(float(r["slope_pct_per_min"])) <= 5 for r in flat_rows))

    def test_never_flat_hits_max(self):
        # 15초마다 10ms씩 내려감 → 분당 40ms. 창 평균 300~400ms라 분당 10% 넘게 내려가는 중 → 최대 시간에서 실패
        rc, rows, _, _ = run_with(lambda i: 500.0 - 10 * i, max=300)
        self.assertEqual(rc, 2)
        self.assertEqual(int(rows[-1]["elapsed_s"]), 300)
        self.assertNotIn("정상 상태", [r["result"] for r in rows])

    def test_slow_drift_within_tolerance_counts_as_flat(self):
        # 분당 약 2.7%(기준 5% 이내)로 아주 천천히 내려가면 정상 상태로 본다 (기준이 의도대로 허용하는 범위)
        rc, _, _, _ = run_with(lambda i: 500.0 - 3 * i)
        self.assertEqual(rc, 0)

    def test_low_latency_jitter_reaches_steady_state(self):
        # 실제 기록 official-1104-d: 23ms에서 내려온 뒤 2~7ms를 2~3분 주기로 오르내림. 오류 없음
        # 상대 기울기(±5%)만 보면 900초 안에 연속 2번 통과가 나오지 않아 실패했다. 절대 기울기(±1ms/분)를 함께 보면 끝난다
        rc_pct, rows_pct, _, _ = run_with(lambda i: JITTER[i], slope_ms=0.0)
        self.assertEqual(rc_pct, 2)
        rc, rows, _, _ = run_with(lambda i: JITTER[i])
        self.assertEqual(rc, 0)
        self.assertEqual(int(rows[-1]["elapsed_s"]), 240)
        self.assertLessEqual(abs(float(rows[-1]["slope_ms_per_min"])), 1.0)

    def test_low_latency_still_falling_is_not_flat(self):
        # 응답이 작아도 분당 1ms보다 빨리 내려가는 중이면 통과하지 않는다 (20ms에서 15초마다 0.5ms씩, 분당 -2ms)
        rc, rows, _, _ = run_with(lambda i: max(20.0 - 0.5 * i, 2.0) if i < 36 else 2.0)
        self.assertEqual(rc, 0)
        judged = [r for r in rows if r["slope_ms_per_min"] != "" and int(r["elapsed_s"]) <= 36 * 15]
        self.assertTrue(all(r["result"] == "아직" for r in judged))

    def test_large_decline_ends_at_same_time_with_or_without_ms(self):
        # 응답이 클 때는 절대 기울기가 판정을 바꾸지 않는다 (200ms에서 15초마다 5ms씩 내려간 뒤 평평)
        series = lambda i: 200.0 - 5 * i if i < 20 else 100.0
        _, with_ms, _, _ = run_with(series)
        _, without_ms, _, _ = run_with(series, slope_ms=0.0)
        self.assertEqual(with_ms[-1]["elapsed_s"], without_ms[-1]["elapsed_s"])

    def test_three_failures_in_a_row_stop(self):
        rc, rows, _, _ = run_with(lambda i: RuntimeError("결과 없음"))
        self.assertEqual(rc, 3)
        self.assertEqual(len(rows), 3)
        self.assertTrue(all(r["result"].startswith("조회 실패") for r in rows))

    def test_isolated_failure_does_not_stop_and_is_not_counted_as_flat(self):
        rc, rows, _, _ = run_with(lambda i: RuntimeError("일시") if i == 3 else 100.0)
        self.assertEqual(rc, 0)
        self.assertEqual(sum(1 for r in rows if r["result"].startswith("조회 실패")), 1)

    def test_failure_between_passes_is_not_consecutive(self):
        # 평평한 값이 이어지다가 구간 하나가 조회 실패 → 창과 연속 통과 수를 비우고 8개 연속 구간을 다시 모은다
        # (실패를 사이에 둔 두 통과가 연속 2번으로 세어져 일찍 끝나면 안 된다)
        # 최소 시간을 길게 둬서 실패 뒤의 판정까지 간다 (최소 3분이면 실패 전에 끝남)
        fail_at = 12
        rc, rows, _, _ = run_with(lambda i: RuntimeError("일시") if i == fail_at else 100.0, min=400)
        self.assertEqual(rc, 0)
        before = [r for r in rows if int(r["elapsed_s"]) <= fail_at * 15]
        self.assertGreaterEqual(int(before[-1]["streak"]), 2)   # 실패 전에는 연속 통과가 쌓여 있었다
        after = [r for r in rows if int(r["elapsed_s"]) > (fail_at + 1) * 15]
        first_judged = next(r for r in after if r["slope_pct_per_min"] != "")
        # 실패 다음 구간부터 8개가 다시 차야 판정하고, 연속 통과 수는 1부터 다시 센다
        self.assertEqual(int(first_judged["elapsed_s"]), (fail_at + 1 + 8) * 15)
        self.assertEqual(first_judged["streak"], "1")
        self.assertEqual(int(rows[-1]["elapsed_s"]), 405)

    def test_reads_after_bucket_end_plus_scrape(self):
        _, _, calls, _ = run_with(lambda i: 100.0)
        self.assertEqual(calls[0], 1015)  # 첫 구간 끝 = since + 15


class Args(unittest.TestCase):
    def test_bad_args_rejected(self):
        self.assertEqual(wu.main(["--since", "1", "--csv", "x", "--window", "2"]), 1)
        self.assertEqual(wu.main(["--since", "1", "--csv", "x", "--min", "500", "--max", "100"]), 1)
        self.assertEqual(wu.main(["--since", "1", "--csv", "x", "--slope-pct", "0"]), 1)
        self.assertEqual(wu.main(["--since", "1", "--csv", "x", "--slope-ms", "-1"]), 1)


if __name__ == "__main__":
    unittest.main()
