"""Synthetic Data 행동 CSV 검증(validate.py)과 재현성 테스트 (Docker, DB 없이 돈다)

사용: python loadtest/tests/test_synthetic.py
"""
import importlib.util
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

SYN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "synthetic")
spec = importlib.util.spec_from_file_location("validate", os.path.join(SYN, "validate.py"))
validate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validate)


def s3(*actions):
    """(vuser, action) 목록 → S3 행. persona는 투자자마다 같은 값"""
    return [{"vuser": str(v), "persona": f"유형{v}", "action": a} for v, a in actions]


def s4(*deposits):
    return [{"vuser": str(v), "persona": "직장인", "deposit": str(d)} for v, d in enumerate(deposits)]


def run_cli(*args):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        rc = validate.main(list(args))
    return rc, out.getvalue(), err.getvalue()


class CommittedCsv(unittest.TestCase):
    """커밋한 행동 CSV가 규칙을 지키고, 생성 코드를 다시 돌리면 같은 파일이 나온다"""

    def test_s3_passes(self):
        errors, exp = validate.check("S3")
        self.assertEqual(errors, [])
        self.assertEqual(exp["requests"], 1000)
        d = exp["detail"]
        self.assertEqual(d["normal"] + d["resend"] + d["second_account"], 1000)
        self.assertEqual(exp["subscription_rows"], d["normal"] + d["second_account"])
        self.assertEqual(exp["rejected"], {"ALREADY_SUBSCRIBED": d["second_account"]})

    def test_s4_passes(self):
        errors, exp = validate.check("S4")
        self.assertEqual(errors, [])
        self.assertEqual(exp["requests"], 1000)
        self.assertEqual(exp["confirmed"] + exp["rejected"]["INSUFFICIENT_BALANCE"], 1000)

    def test_generators_reproduce_committed_csv(self):
        for name in ("s3", "s4"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                shutil.copy(os.path.join(SYN, f"{name}-generate.py"), tmp)
                subprocess.run([sys.executable, "-I", "-X", "utf8", f"{name}-generate.py"], cwd=tmp, check=True)
                with open(os.path.join(tmp, f"{name}-behavior.csv"), "rb") as a, open(os.path.join(SYN, f"{name}-behavior.csv"), "rb") as b:
                    self.assertEqual(a.read(), b.read(), f"{name}-generate.py를 다시 돌린 결과가 커밋한 CSV와 다르다")

    def test_generator_code_matches_response(self):
        # 생성 코드는 응답 원문의 코드 블록을 그대로 옮긴 것이어야 한다
        import re
        for name in ("s3", "s4"):
            with self.subTest(name=name):
                with open(os.path.join(SYN, f"{name}-response.md"), encoding="utf-8") as f:
                    blocks = re.findall(r"```python\n(.*?)```", f.read(), re.S)
                self.assertEqual(len(blocks), 1)
                with open(os.path.join(SYN, f"{name}-generate.py"), encoding="utf-8") as f:
                    self.assertEqual(f.read(), blocks[0])


class S3Rules(unittest.TestCase):
    def test_expected_counts(self):
        rows = s3((0, "NORMAL"), (1, "NORMAL"), (0, "RESEND"), (1, "SECOND_ACCOUNT"), (0, "SECOND_ACCOUNT"))
        errors, exp = validate.check_s3(rows, total=5, users=10)
        self.assertEqual(errors, [])
        self.assertEqual((exp["subscription_rows"], exp["confirmed"], exp["margins"]), (4, 2, 2))
        self.assertEqual(exp["rejected"], {"ALREADY_SUBSCRIBED": 2})
        self.assertEqual(exp["detail"]["second_account_vusers"], [0, 1])

    def test_violations(self):
        cases = {
            "행 수": (s3((0, "NORMAL")), 2),
            "NORMAL 두 번": (s3((0, "NORMAL"), (0, "NORMAL")), 2),
            "재전송이 앞": (s3((0, "RESEND"), (0, "NORMAL")), 2),
            "NORMAL 없는 투자자": (s3((0, "NORMAL"), (1, "SECOND_ACCOUNT")), 2),
            "재전송 두 번": (s3((0, "NORMAL"), (0, "RESEND"), (0, "RESEND")), 3),
            "번호가 빔": (s3((0, "NORMAL"), (2, "NORMAL")), 2),
            "모르는 action": (s3((0, "NORMAL"), (0, "CANCEL")), 2),
            "회원 수 이상": (s3((0, "NORMAL"), (1, "NORMAL"), (10, "NORMAL")), 3),
        }
        for name, (rows, total) in cases.items():
            with self.subTest(name=name):
                errors, exp = validate.check_s3(rows, total=total, users=10)
                self.assertTrue(errors, name)
                self.assertIsNone(exp)

    def test_vuser_format_and_persona(self):
        rows = s3((0, "NORMAL"), (1, "NORMAL"))
        for bad in ("01", "-1", "1.0", " 1", ""):
            with self.subTest(vuser=bad):
                r = [dict(x) for x in rows]
                r[1]["vuser"] = bad
                self.assertTrue(validate.check_s3(r, total=2, users=10)[0])
        r = [dict(x) for x in rows] + [{"vuser": "0", "persona": "다른유형", "action": "RESEND"}]
        self.assertTrue(any("persona" in e for e in validate.check_s3(r, total=3, users=10)[0]))
        r = [dict(x) for x in rows]
        r[0]["persona"] = " "
        self.assertTrue(any("persona" in e for e in validate.check_s3(r, total=2, users=10)[0]))


class S4Rules(unittest.TestCase):
    def test_expected_counts_and_band(self):
        errors, exp = validate.check_s4(s4(1000, 149_999, 600_000, 5_000_000), total=4, users=10, amount=150_000, runs=4)
        self.assertEqual(errors, [])
        self.assertEqual(exp["rejected"], {"INSUFFICIENT_BALANCE": 2})
        self.assertEqual((exp["confirmed"], exp["margins"], exp["subscription_rows"]), (2, 2, 4))
        self.assertEqual(exp["detail"]["short_vusers"], [0, 1])

    def test_ambiguous_band_and_bad_deposit(self):
        for d in (150_000, 599_999):
            with self.subTest(deposit=d):
                errors, exp = validate.check_s4(s4(d), total=1, users=10, amount=150_000, runs=4)
                self.assertTrue(any("회차마다" in e for e in errors))
                self.assertIsNone(exp)
        for d in ("0", "-5", "1.5", "", "1,000"):
            with self.subTest(deposit=d):
                rows = [{"vuser": "0", "persona": "직장인", "deposit": d}]
                self.assertTrue(validate.check_s4(rows, total=1, users=10)[0])

    def test_runs_changes_band(self):
        # 같은 금액도 반복 횟수가 늘면 애매한 구간에 들어간다
        self.assertEqual(validate.check_s4(s4(600_000), total=1, users=10, runs=4)[0], [])
        self.assertTrue(validate.check_s4(s4(600_000), total=1, users=10, runs=5)[0])

    def test_duplicate_vuser(self):
        rows = s4(1_000_000, 1_000_000)
        rows[1]["vuser"] = "0"
        self.assertTrue(any("두 번째" in e for e in validate.check_s4(rows, total=2, users=10)[0]))


class FileAndCli(unittest.TestCase):
    def write(self, text):
        f = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8", newline="")
        f.write(text)
        f.close()
        self.addCleanup(os.unlink, f.name)
        return f.name

    def test_header_must_match(self):
        path = self.write("vuser,action,persona\n0,NORMAL,직장인\n")
        with self.assertRaises(ValueError):
            validate.check("S3", path, total=1)
        self.assertEqual(run_cli("s3", "--csv", path, "--total", "1")[0], 2)

    def test_column_count(self):
        path = self.write("vuser,persona,action\n0,직장인,NORMAL,extra\n")
        with self.assertRaises(ValueError):
            validate.check("S3", path, total=1)

    def test_cli_exit_codes(self):
        ok = self.write("vuser,persona,action\n0,직장인,NORMAL\n")
        bad = self.write("vuser,persona,action\n0,직장인,RESEND\n")
        self.assertEqual(run_cli("s3", "--csv", ok, "--total", "1")[0], 0)
        rc, _, err = run_cli("s3", "--csv", bad, "--total", "1")
        self.assertEqual(rc, 1)
        self.assertIn("위반", err)
        self.assertEqual(run_cli("s3", "--csv", os.path.join(tempfile.gettempdir(), "없는파일.csv"))[0], 2)
        self.assertEqual(run_cli("s4", "--runs", "0")[0], 2)

    def test_cli_json(self):
        rc, out, _ = run_cli("s4", "--json")
        self.assertEqual(rc, 0)
        import json
        exp = json.loads(out)
        self.assertNotIn("detail", exp)
        self.assertEqual(exp["scenario"], "S4")


if __name__ == "__main__":
    unittest.main(verbosity=1)
