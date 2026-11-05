"""autovacuum.py 테스트 (Docker, DB 없이 돈다)

사용: python loadtest/tests/test_autovacuum.py
"""
import importlib.util
import io
import os
import shutil
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

spec = importlib.util.spec_from_file_location(
    "autovacuum", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "verify", "autovacuum.py"))
av = importlib.util.module_from_spec(spec)
spec.loader.exec_module(av)

HEAD = "table,live_tup,dead_tup,autovacuum_count,autoanalyze_count,last_autovacuum,last_autoanalyze\n"


def snap(**tables):
    """snap(t1=(vacuum, analyze), ...) → CSV 문자열. 테이블 이름의 _는 .으로"""
    rows = [f"{t.replace('__', '.')},100,5,{v},{a},," for t, (v, a) in tables.items()]
    return HEAD + "\n".join(rows) + "\n"


class Case:
    def __init__(self):
        self.tmp = tempfile.mkdtemp()
        self.results = os.path.join(self.tmp, "results")
        os.makedirs(os.path.join(self.results, "b"))
        self.runs = []

    def write(self, path, text):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(text)

    def before(self, text):
        self.write(os.path.join(self.results, "b", "autovacuum-before.csv"), text)

    def run(self, run_id, scenario, phase, text=None):
        self.runs.append((run_id, scenario, phase))
        if text is not None:
            self.write(os.path.join(self.results, run_id, "autovacuum.csv"), text)

    def summarize(self):
        m = os.path.join(self.results, "b", "manifest.csv")
        self.write(m, "run_id,scenario,phase\n" + "".join(f"{r},{s},{p}\n" for r, s, p in self.runs))
        return av.summarize(m, self.results, os.path.join(self.results, "b", "autovacuum-before.csv"))

    def close(self):
        shutil.rmtree(self.tmp, ignore_errors=True)


class TestAutovacuum(unittest.TestCase):
    def setUp(self):
        self.c = Case()

    def tearDown(self):
        self.c.close()

    def test_diff_per_run(self):
        c = self.c
        c.before(snap(sub__subscription=(0, 1), acc__margin=(0, 0)))
        c.run("b-W", "W", "warmup", snap(sub__subscription=(2, 3), acc__margin=(0, 1)))
        c.run("b-S0-r1", "S0", "measure", snap(sub__subscription=(2, 3), acc__margin=(0, 1)))
        c.run("b-S3-r1", "S3", "measure", snap(sub__subscription=(3, 3), acc__margin=(1, 1)))
        md, missing = c.summarize()
        self.assertEqual(missing, [])
        self.assertIn("| b-W | W | warmup | sub.subscription 2 | acc.margin 1, sub.subscription 2 |", md)
        self.assertIn("| b-S0-r1 | S0 | measure | - | - |", md)
        self.assertIn("| b-S3-r1 | S3 | measure | acc.margin 1, sub.subscription 1 | - |", md)
        self.assertIn("- 합계: autovacuum acc.margin 1, sub.subscription 3 / autoanalyze acc.margin 1, sub.subscription 2", md)

    def test_new_table_counts_from_zero(self):
        c = self.c
        c.before(snap(a__x=(0, 0)))
        c.run("b-S0-r1", "S0", "measure", snap(a__x=(0, 0), a__y=(1, 2)))
        md, _ = c.summarize()
        self.assertIn("| a.y 1 | a.y 2 |", md)

    def test_missing_record_compares_next_with_last_known(self):
        c = self.c
        c.before(snap(a__x=(0, 0)))
        c.run("b-S0-r1", "S0", "measure")          # 기록 실패
        c.run("b-S0-r2", "S0", "measure", snap(a__x=(1, 0)))
        md, missing = c.summarize()
        self.assertEqual(missing, ["b-S0-r1"])
        self.assertIn("| b-S0-r1 | S0 | measure | 기록 없음 | 기록 없음 |", md)
        self.assertIn("| b-S0-r2 | S0 | measure | a.x 1 | - |", md)  # r1이 없으니 before와 비교

    def test_counter_reset(self):
        c = self.c
        c.before(snap(a__x=(5, 5)))
        c.run("b-S0-r1", "S0", "measure", snap(a__x=(0, 1)))
        c.run("b-S0-r2", "S0", "measure", snap(a__x=(1, 1)))
        md, _ = c.summarize()
        self.assertIn("| b-S0-r1 | S0 | measure | 초기화됨", md)
        self.assertIn("| b-S0-r2 | S0 | measure | a.x 1 | - |", md)   # 초기화 뒤 값부터 다시 센다

    def test_bad_files(self):
        for name, text in {"머리줄": "a,b\nx,1\n", "숫자 아님": HEAD + "a.x,1,1,x,0,,\n", "열 수": HEAD + "a.x,1,1\n", "빈 표": HEAD}.items():
            with self.subTest(name=name):
                c = Case()
                c.before(text)
                with self.assertRaises(ValueError):
                    c.summarize()
                c.close()

    def test_cli(self):
        c = self.c
        c.before(snap(a__x=(0, 0)))
        c.run("b-S0-r1", "S0", "measure", snap(a__x=(1, 0)))
        c.run("b-S0-r2", "S0", "measure")
        c.summarize()
        m = os.path.join(c.results, "b", "manifest.csv")
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = av.main([m, "--results", c.results, "--before", os.path.join(c.results, "b", "autovacuum-before.csv")])
        self.assertEqual(rc, 1)  # 기록 없는 실행이 있으면 1
        self.assertIn("a.x 1", out.getvalue())
        self.assertIn("b-S0-r2", err.getvalue())
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(av.main([m, "--results", c.results, "--before", os.path.join(c.tmp, "없음.csv")]), 1)


if __name__ == "__main__":
    unittest.main(verbosity=1)
