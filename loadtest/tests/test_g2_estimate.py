"""g2_estimate.py 테스트 (DB, Docker 없이 돈다. g2_run.sh 결과 폴더를 임시로 만든다)

사용: python loadtest/tests/test_g2_estimate.py
"""
import importlib.util
import io
import math
import os
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "verify", "g2_estimate.py")
spec = importlib.util.spec_from_file_location("g2_estimate", _path)
ge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ge)

T = ge.TARGET
HEADER = "session,seq,snapshot,case,n,started,ended,status,integrity,total_s,throughput,idempotency,reasons\n"


def m_line(wait=0.5, alloc=0.6, gap=2.0, settle=7.0, mark=0.9):
    """result.txt의 M 줄. first_settle_s = wait + alloc + gap, total_s = 그 합 + settle"""
    first = wait + alloc + gap
    return (f"M trigger=x T0=x wait_s={wait} alloc_s={alloc} publish_s=1 first_settle_s={first} settle_s={settle} "
            f"mark_s={mark} total_s={first + settle} thr=1 policy_s=22 settled=1 refunded=1 round=DONE seed=1\n")


class Batch:
    """g2_run.sh 결과 폴더 하나 (manifest.csv + 회차 폴더의 result.txt)"""
    def __init__(self, root, name):
        self.dir = os.path.join(root, name)
        os.makedirs(self.dir)
        self.rows = []

    def add(self, case, n, status="VALID", integrity="PASS", **seg):
        seq = len(self.rows) + 1
        snap = f"g2-{case}-{n}"
        self.rows.append(f"S,{seq},{snap},{case},{n},a,b,{status},{integrity},1,1,-,\n")
        if status == "VALID" and integrity == "PASS":
            rd = os.path.join(self.dir, f"{seq:02d}-{snap}")
            os.makedirs(rd)
            with open(os.path.join(rd, "result.txt"), "w", encoding="utf-8") as f:
                f.write("W1 wait_s=9\n" + m_line(**seg))
        with open(os.path.join(self.dir, "manifest.csv"), "w", encoding="utf-8") as f:
            f.write(HEADER + "".join(self.rows))
        return self


def times_for(r, t_low=50.0):
    """1만 건 t_low초일 때 처리량 비율이 r이 되는 10만 건 시간"""
    thr_low = ge.LOW / t_low
    return ge.HIGH / (thr_low * r)


class EstimateRules(unittest.TestCase):
    def test_doc_example_rule4(self):
        # 결정 문서 예시: 처리량 1만 200, 10만 160건/초 → r 0.8 → 낙관 약 13,265초, 비관 약 17,835초 (지수 1.33 기준)
        e = ge.estimate(10_000 / 200, 100_000 / 160)
        self.assertAlmostEqual(e["r"], 0.8)
        self.assertEqual(e["rule"], 4)
        self.assertAlmostEqual(e["optimistic"], T / 160, places=6)
        self.assertAlmostEqual(e["k"], math.log10(T / 100_000))
        self.assertAlmostEqual(e["pessimistic"], T / (160 * 0.8 ** e["k"]), places=6)
        self.assertAlmostEqual(e["pessimistic"], 17_835, delta=30)   # 문서는 지수를 1.33으로 반올림해 계산
        self.assertEqual((e["lo"], e["hi"]), (e["optimistic"], e["pessimistic"]))

    def test_boundaries_inclusive(self):
        for r in (0.9, 1.0, 1.1):
            e = ge.estimate(50.0, times_for(r))
            self.assertEqual(e["rule"], 3, r)
            self.assertEqual(e["lo"], e["linear"])
            self.assertEqual(e["hi"], e["linear"])
        self.assertEqual(ge.estimate(50.0, times_for(0.8999))["rule"], 4)
        self.assertEqual(ge.estimate(50.0, times_for(1.1001))["rule"], 5)

    def test_rule5_keeps_10man_throughput(self):
        e = ge.estimate(50.0, times_for(1.5))
        self.assertEqual(e["rule"], 5)
        self.assertEqual(e["lo"], e["optimistic"])
        self.assertEqual(e["hi"], e["optimistic"])
        self.assertGreater(e["optimistic"], e["pessimistic"])   # r > 1이면 비관 공식은 오르는 추세를 이어 더 짧게 나온다. 그 값은 쓰지 않는다

    def test_linear_passes_both_points(self):
        e = ge.estimate(50.0, 400.0)
        a = (400.0 - 50.0) / (ge.HIGH - ge.LOW)
        self.assertAlmostEqual(e["linear"], 50.0 + a * (T - ge.LOW))
        self.assertAlmostEqual(ge.estimate(50.0, 400.0, target=ge.HIGH)["linear"], 400.0)

    def test_nonpositive_time_raises(self):
        for lo, hi in ((0, 10), (10, 0), (-1, 10)):
            with self.assertRaises(ValueError):
                ge.estimate(lo, hi)

    def test_verdict(self):
        P, G = ge.PASS_S, ge.GOAL_S
        self.assertEqual(ge.verdict(P - 100, P, P), "합격")          # 비관이 정확히 선이면 합격
        self.assertEqual(ge.verdict(P - 100, P + 1, P), "조건부")    # 낙관만 안
        self.assertEqual(ge.verdict(P + 1, P + 100, P), "불합격")
        self.assertEqual(ge.verdict(G, G, G), "달성")
        self.assertEqual(ge.verdict(G - 1, G + 1, G), "조건부")
        self.assertEqual(ge.verdict(G + 1, G + 1, G), "미달")


class Analyze(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.root)

    def batch(self, name):
        return Batch(self.root, name)

    def test_median_sum_and_exclusion(self):
        b = self.batch("g2-b")
        for s in (7.0, 9.0, 8.0):
            b.add("A", 10_000, settle=s * 10)
        b.add("A", 10_000, status="INVALID")                    # 판정하지 않는 회차는 빠진다
        b.add("A", 10_000, integrity="FAIL", settle=1.0)        # 정합성 불합격도 결과로 쓰지 않는다
        for s in (700.0, 900.0, 800.0):
            b.add("A", 100_000, settle=s)
        b.add("A", 1_000, settle=5.0)
        runs = ge.load([b.dir])
        res = ge.analyze(runs, "A")
        self.assertEqual(res["state"], "ok")
        rep, cnt, excl, _ = res["rep"][ge.LOW]
        self.assertEqual(cnt, 3)
        self.assertEqual(rep["settle"], 80.0)                   # 중앙값
        self.assertEqual(len(excl), 2)
        self.assertAlmostEqual(rep["gap"], 2.0)                 # first_settle - wait - alloc
        # 합계 = 4구간 합 (환불 표시는 빠짐), 1천 건은 추정에 쓰지 않음
        self.assertAlmostEqual(res["lo"], sum(res["seg"][k]["lo"] for k, _ in ge.SEGMENTS))
        self.assertNotIn("mark", [k for k, _ in ge.SEGMENTS])
        self.assertEqual(res["note"], [])

    def test_mark_not_in_total(self):
        b = self.batch("g2-b")
        b.add("A", 10_000, mark=1.0)
        b.add("A", 100_000, mark=1000.0)                        # 환불 표시가 아무리 커도 T1 합계와 판정은 그대로
        res1 = ge.analyze(ge.load([b.dir]), "A")
        b2 = self.batch("g2-c")
        b2.add("A", 10_000, mark=1.0)
        b2.add("A", 100_000, mark=1.0)
        res2 = ge.analyze(ge.load([b2.dir]), "A")
        self.assertAlmostEqual(res1["lo"], res2["lo"])
        self.assertAlmostEqual(res1["hi"], res2["hi"])

    def test_less_than_3_runs_is_reference(self):
        b = self.batch("g2-b")
        b.add("A", 10_000)
        b.add("A", 100_000)
        res = ge.analyze(ge.load([b.dir]), "A")
        self.assertEqual(res["state"], "ok")
        self.assertTrue(any("3회 미만" in n for n in res["note"]))

    def test_rule7_unmeasurable(self):
        b = self.batch("g2-b")
        b.add("A", 10_000)
        b.add("A", 100_000, status="UNMEASURABLE", integrity="-")
        res = ge.analyze(ge.load([b.dir]), "A")
        self.assertIn("측정 불가", res["state"])
        self.assertNotIn("seg", res)

    def test_missing_inputs(self):
        b = self.batch("g2-b")
        b.add("A", 100_000)
        res = ge.analyze(ge.load([b.dir]), "A")
        self.assertIn("입력 부족", res["state"])

    def test_cases_separate_and_multiple_batches(self):
        b1 = self.batch("g2-1k")
        b1.add("A", 1_000).add("B", 1_000)
        b2 = self.batch("g2-10k")
        b2.add("A", 10_000, settle=70.0).add("B", 10_000, settle=40.0)
        b3 = self.batch("g2-100k")
        b3.add("A", 100_000, settle=900.0).add("B", 100_000, settle=400.0)
        runs = ge.load([b1.dir, b2.dir, b3.dir])
        a, bb = ge.analyze(runs, "A"), ge.analyze(runs, "B")
        self.assertEqual(a["seg"]["settle"]["rule"], 4)      # 70 → 900초: 처리량이 떨어짐
        self.assertEqual(bb["seg"]["settle"]["rule"], 3)     # 40 → 400초: 일정
        self.assertNotEqual(a["lo"], bb["lo"])

    def test_zero_segment_is_not_estimated(self):
        b = self.batch("g2-b")
        b.add("A", 10_000, gap=0.0)
        b.add("A", 100_000)
        res = ge.analyze(ge.load([b.dir]), "A")
        self.assertIn("계산 불가", res["state"])


class Main(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.root)

    def run_main(self, args):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = ge.main(args)
        return rc, out.getvalue(), err.getvalue()

    def test_missing_manifest_is_error(self):
        rc, _, err = self.run_main([os.path.join(self.root, "none")])
        self.assertEqual(rc, 1)
        self.assertIn("manifest.csv 없음", err)

    def test_missing_result_txt_is_error(self):
        b = Batch(self.root, "g2-b").add("A", 10_000)
        os.remove(os.path.join(b.dir, "01-g2-A-10000", "result.txt"))
        rc, _, err = self.run_main([b.dir])
        self.assertEqual(rc, 1)

    def test_writes_markdown(self):
        b = Batch(self.root, "g2-b")
        b.add("A", 1_000).add("A", 10_000, settle=70.0).add("A", 100_000, settle=900.0)
        out = os.path.join(self.root, "추정.md")
        rc, _, _ = self.run_main([b.dir, "--out", out])
        self.assertEqual(rc, 0)
        with open(out, encoding="utf-8") as f:
            text = f.read()
        self.assertIn("2,122,367건 추정 (T1 기준, 추정)", text)
        self.assertIn("규칙 4 (떨어짐 → 범위)", text)
        self.assertIn("1,000 (참고)", text)
        self.assertIn("판정: 합격선 3시간 0분 0초", text)


if __name__ == "__main__":
    unittest.main(verbosity=1)
