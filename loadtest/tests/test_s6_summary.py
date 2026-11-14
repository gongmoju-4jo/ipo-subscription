"""s6_summary.py 테스트 (회차 폴더를 임시로 만들어 읽는다)
사용: python loadtest/tests/test_s6_summary.py   (s6_summary.py 경로를 첫 인자로 줄 수 있다)
"""
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

_path = sys.argv.pop(1) if len(sys.argv) > 1 and sys.argv[1].endswith(".py") else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "verify", "s6_summary.py")
spec = importlib.util.spec_from_file_location("s6_summary", _path)
sm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sm)


def make_run(root, run_id, valid=True, violation="위반 없음", warm=None, up=33.0, confirmed=1000, timeout=0, void=None,
             lag=900, p95=49.0, drain="00:01:01.500", api_p95=9.0, dlt=None):
    d = os.path.join(root, run_id)
    os.makedirs(d)
    judge = ["# S6 장애 주입 판정", f"회차: {'유효' if valid else '무효'}", f"결과: {violation}"]
    if warm:
        judge.append(f"워밍업: {warm}")
    judge += ["", "## 장애 구간 (컨테이너 시계)", "부하 시작 → 종료: 4.7초",
              f"종료 → 재시작 명령: 11.6초, 종료 → UP(healthy): {up}초", "종료 전 접수 500건, 종료 이후 접수 500건",
              f"장애 중 인출 요청 랙 최댓값: {lag} (샘플 5개)", "", "## 결과",
              f"요청 1000건: CONFIRMED {confirmed}건, CANCELLED(TIMEOUT) {timeout}건, 그 밖 0건 (청약 행 1000건)",
              "202를 받았는데 청약 행이 없음: 0건"]
    if void is not None:
        judge.append(f"선행 반환 기록(margin_void_intent): {void}건, 그중 증거금 없이 늦은 인출이 막힌 건 0건 (기록용)")
    if dlt is not None:
        judge.append(f"DLT 증가: {dlt}건 (기록용)" if isinstance(dlt, int) else f"DLT 증가: {dlt}")
    with open(os.path.join(d, "s6-judge.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(judge) + "\n")
    with open(os.path.join(d, "sql_result.txt"), "w", encoding="utf-8") as f:
        f.write("== 9. 접수 → 확정 시간 (초. 기대: 기록)\n   p50   |   p95   |   max_s   \n---------+---------+-----------\n"
                f" 40.0 | {p95} | 50.0\n(1 row)\n\n"
                "== 10. 전체 소진 시간 (기대: 기록)\n min | max | ?column? \n-----+-----+----------\n"
                f" 2026-11-12 09:00:00+09 | 2026-11-12 09:01:00+09 | {drain}\n(1 row)\n\n")
    with open(os.path.join(d, "summary.json"), "w", encoding="utf-8") as f:
        json.dump({"metrics": {"http_req_duration": {"avg": 5.0, "p(95)": api_p95}}}, f)


class S6SummaryTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()

    def run_main(self, runs):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = sm.main(["--runs", *runs, "--results-dir", self.root])
        return rc, out.getvalue(), err.getvalue()

    def test_reads_values(self):
        make_run(self.root, "r1", warm="함 (204초, 200건/초)", up=33.6, timeout=5, confirmed=995, void=2, lag=923, p95=49.2,
                 drain="00:01:09.250", api_p95=9.2)
        r = sm.read_run(self.root, "r1")
        self.assertTrue(r["valid"])
        self.assertEqual((r["up"], r["confirmed"], r["timeout"], r["void"], r["lag"]), (33.6, 995.0, 5.0, 2.0, 923.0))
        self.assertEqual((r["p95"], r["drain"], r["api_p95"]), (49.2, 69.25, 9.2))
        self.assertTrue(r["warm"].startswith("함"))

    def test_median_and_range(self):
        for i, up in enumerate((33.0, 34.0, 36.0), 1):
            make_run(self.root, f"r{i}", up=up, timeout=i * 10)
        rc, out, _ = self.run_main(["r1", "r2", "r3"])
        self.assertEqual(rc, 0)
        median = [ln for ln in out.splitlines() if ln.startswith("| 중앙값")][0]
        rng = [ln for ln in out.splitlines() if ln.startswith("| 최소~최대")][0]
        self.assertIn("3/3회", median)
        self.assertIn("| 34.0 |", median)       # UP 중앙값
        self.assertIn("| 20 |", median)         # TIMEOUT 중앙값
        self.assertIn("33.0~36.0", rng)
        self.assertIn("10~30", rng)

    def test_invalid_run_excluded_from_median(self):
        make_run(self.root, "r1", up=33.0)
        make_run(self.root, "r2", up=34.0)
        make_run(self.root, "r3", up=99.0, valid=False)
        rc, out, _ = self.run_main(["r1", "r2", "r3"])
        self.assertEqual(rc, 0)
        median = [ln for ln in out.splitlines() if ln.startswith("| 중앙값")][0]
        self.assertIn("2/3회", median)
        self.assertIn("| 33.5 |", median)
        self.assertIn("무효 회차(r3)", out)

    def test_violation_is_noted(self):
        make_run(self.root, "r1")
        make_run(self.root, "r2", violation="위반 1종 (verify-s6.sql 23번 위반 1건)")
        rc, out, _ = self.run_main(["r1", "r2"])
        self.assertEqual(rc, 0)
        self.assertIn("정합성 위반이 있는 회차: r2", out)

    def test_old_judge_format_without_warmup_and_void(self):
        make_run(self.root, "r1")
        r = sm.read_run(self.root, "r1")
        self.assertEqual(r["warm"], "-")
        self.assertIsNone(r["void"])
        rc, out, _ = self.run_main(["r1"])
        self.assertEqual(rc, 0)
        self.assertNotIn("워밍업 조건이 섞여", out)

    def test_mixed_warmup_is_noted(self):
        make_run(self.root, "r1", warm="안 함")
        make_run(self.root, "r2", warm="함 (200초, 200건/초)")
        rc, out, _ = self.run_main(["r1", "r2"])
        self.assertEqual(rc, 0)
        self.assertIn("워밍업 조건이 섞여 있습니다", out)

    def test_dlt_column_and_median(self):
        for i, n in enumerate((0, 2, 4), 1):
            make_run(self.root, f"r{i}", dlt=n)
        self.assertEqual(sm.read_run(self.root, "r2")["dlt"], 2.0)
        rc, out, _ = self.run_main(["r1", "r2", "r3"])
        self.assertEqual(rc, 0)
        self.assertIn("DLT 증가", out)
        median = [ln for ln in out.splitlines() if ln.startswith("| 중앙값")][0]
        self.assertTrue(median.rstrip().endswith("| 2 |"), median)

    def test_dlt_not_recorded_is_dash(self):
        make_run(self.root, "r1", dlt="기록하지 못함 (dlt-end.txt가 비어 있음)")
        make_run(self.root, "r2")   # 옛 결과: DLT 줄 없음
        self.assertIsNone(sm.read_run(self.root, "r1")["dlt"])
        self.assertIsNone(sm.read_run(self.root, "r2")["dlt"])
        rc, out, _ = self.run_main(["r1", "r2"])
        self.assertEqual(rc, 0)
        self.assertTrue([ln for ln in out.splitlines() if ln.startswith("| r1")][0].rstrip().endswith("| - |"))

    def test_environment_limit_footer(self):
        make_run(self.root, "r1")
        rc, out, _ = self.run_main(["r1"])
        self.assertEqual(rc, 0)
        self.assertIn("로컬 단일 머신, Docker, 인스턴스 1개", out)

    def test_different_warmup_seconds_are_same_condition(self):
        make_run(self.root, "r1", warm="함 (204초, 200건/초)")
        make_run(self.root, "r2", warm="함 (312초, 200건/초)")
        rc, out, _ = self.run_main(["r1", "r2"])
        self.assertEqual(rc, 0)
        self.assertNotIn("워밍업 조건이 섞여", out)
        self.assertIn("함 (204초, 200건/초)", out)   # 표에는 회차별 워밍업 시간을 그대로 보인다

    def test_missing_run_returns_1(self):
        make_run(self.root, "r1")
        rc, _, err = self.run_main(["r1", "nope"])
        self.assertEqual(rc, 1)
        self.assertIn("읽지 못함: nope", err)

    def test_unparsable_judge_returns_1(self):
        make_run(self.root, "r1")
        with open(os.path.join(self.root, "r1", "s6-judge.txt"), "w", encoding="utf-8") as f:
            f.write("엉뚱한 내용\n")
        rc, _, err = self.run_main(["r1"])
        self.assertEqual(rc, 1)
        self.assertIn("읽지 못함", err)

    def test_missing_sql_section_returns_1(self):
        make_run(self.root, "r1")
        with open(os.path.join(self.root, "r1", "sql_result.txt"), "w", encoding="utf-8") as f:
            f.write("== 1. 상태별 건수\n a \n---\n(0 rows)\n")
        rc, _, err = self.run_main(["r1"])
        self.assertEqual(rc, 1)
        self.assertIn("9번, 10번", err)


if __name__ == "__main__":
    unittest.main()
