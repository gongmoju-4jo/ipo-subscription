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
             slope_pct=5.0, consecutive=2, min=180, max=900)
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

    def test_three_failures_in_a_row_stop(self):
        rc, rows, _, _ = run_with(lambda i: RuntimeError("결과 없음"))
        self.assertEqual(rc, 3)
        self.assertEqual(len(rows), 3)
        self.assertTrue(all(r["result"].startswith("조회 실패") for r in rows))

    def test_isolated_failure_does_not_stop_and_is_not_counted_as_flat(self):
        rc, rows, _, _ = run_with(lambda i: RuntimeError("일시") if i == 3 else 100.0)
        self.assertEqual(rc, 0)
        self.assertEqual(sum(1 for r in rows if r["result"].startswith("조회 실패")), 1)

    def test_reads_after_bucket_end_plus_scrape(self):
        _, _, calls, _ = run_with(lambda i: 100.0)
        self.assertEqual(calls[0], 1015)  # 첫 구간 끝 = since + 15


class Args(unittest.TestCase):
    def test_bad_args_rejected(self):
        self.assertEqual(wu.main(["--since", "1", "--csv", "x", "--window", "2"]), 1)
        self.assertEqual(wu.main(["--since", "1", "--csv", "x", "--min", "500", "--max", "100"]), 1)
        self.assertEqual(wu.main(["--since", "1", "--csv", "x", "--slope-pct", "0"]), 1)


if __name__ == "__main__":
    unittest.main()
