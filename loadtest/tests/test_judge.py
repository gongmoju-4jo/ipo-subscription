"""judge.py 판정 테스트 (DB 없이 돈다. psql 호출은 가짜로 바꾼다)

사용: python loadtest/tests/test_judge.py   (judge.py 경로를 첫 인자로 줄 수 있다)
"""
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest

_path = sys.argv.pop(1) if len(sys.argv) > 1 and sys.argv[1].endswith(".py") else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "verify", "judge.py")
spec = importlib.util.spec_from_file_location("judge", _path)
judge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(judge)

U = ["6f1c3b0e-1111-4a2b-8c3d-%012d" % i for i in range(1, 20)]
# official.sh가 쓰는 manifest.csv 머리줄과 같아야 한다
MANIFEST_HEADER = "run_id,scenario,phase,target_rate,duration_s,expected_requests,ipo_ids,k6_start,k6_end,drain_s\n"


def summary(requests=1000, rate=500.0, avg=80.0, p95=130.0, fail=0.0, dropped=None):
    m = {"http_reqs": {"count": requests, "rate": rate},
         "http_req_duration": {"avg": avg, "p(95)": p95},
         "http_req_failed": {"value": fail}}
    if dropped is not None:
        m["dropped_iterations"] = {"count": dropped, "rate": 0}
    return {"metrics": m}


class Case:
    def __init__(self):
        self.tmp = tempfile.mkdtemp()
        self.batch = os.path.join(self.tmp, "batch")
        os.makedirs(self.batch)
        self.rows = []
        self.g2 = {}

    def run(self, run_id, scenario, phase, target, ipos, s, d, dur="", expected=None):
        os.makedirs(os.path.join(self.tmp, run_id))
        with open(os.path.join(self.tmp, run_id, "summary.json"), "w", encoding="utf-8") as f:
            json.dump(s, f)
        if expected is None:  # official.sh처럼 S0은 1,000건, S1은 목표 속도 x 유지 시간
            expected = 1000 if scenario == "S0" else (int(target) * int(dur) if target and dur else "")
        self.rows.append(f"{run_id},{scenario},{phase},{target},{dur},{expected},{';'.join(ipos)},0,0,1")
        self.g2[tuple(ipos)] = d

    def manifest(self):
        path = os.path.join(self.batch, "manifest.csv")
        with open(path, "w", encoding="utf-8") as f:
            f.write(MANIFEST_HEADER + "\n".join(self.rows) + "\n")
        return path

    def judge(self, p95=15, drain=15):
        path = self.manifest()
        out = io.StringIO()
        judge.judge(path, p95, drain, query=lambda ipos, c: self.g2[tuple(ipos)], out=out)
        return out.getvalue()


def g2(confirmed=1000, p50=3.0, p95=6.0, mx=7.0, drain=6.0):
    return {"confirmed": confirmed, "p50": p50, "p95": p95, "max": mx, "drain": drain}


def s0_case(rates=(500, 500, 500), g2s=None, avgs=(80, 80, 80)):
    c = Case()
    c.run("w1", "S0", "warmup", "", [U[0]], summary(rate=100, avg=900), g2(p95=40, drain=40))
    for i in range(3):
        c.run(f"r{i + 1}", "S0", "measure", "", [U[i + 1]], summary(rate=rates[i], avg=avgs[i]), (g2s or [g2()] * 3)[i])
    return c


def section(text, title):
    return text.split(f"## {title}")[1].split("##")[0]


class TestG1(unittest.TestCase):
    def test_s0_rate_below_200_fails(self):  # CodeRabbit 지적: S0 처리량 기준이 빠지면 안 된다
        out = section(s0_case(rates=(199.9, 199.9, 199.9)).judge(), "S0")
        self.assertIn("합격 불합격", out)

    def test_s0_rate_200_passes_but_goal_needs_400(self):
        out = section(s0_case(rates=(200, 200, 200)).judge(), "S0")
        self.assertIn("합격 통과", out)
        self.assertIn("목표 미달", out)

    def test_s0_goal(self):
        out = section(s0_case(rates=(400, 450, 500)).judge(), "S0")
        self.assertIn("목표 통과", out)

    def test_avg_boundary(self):
        self.assertEqual(judge.g1({"avg": 500.0, "fail": 0, "rate": 300, "p95": 1, "dropped": 0}, "S0")[0], "통과")
        self.assertEqual(judge.g1({"avg": 500.1, "fail": 0, "rate": 300, "p95": 1, "dropped": 0}, "S0")[0], "불합격")
        self.assertEqual(judge.g1({"avg": 10, "fail": 0.01, "rate": 300, "p95": 1, "dropped": 0}, "S0")[0], "불합격")

    def test_s1_rate_not_in_criteria(self):  # 문서 8-2: S1은 평균, 오류율로만
        self.assertEqual(judge.g1({"avg": 5, "fail": 0, "rate": 93, "p95": 14, "dropped": 0}, "S1")[0], "통과")

    def test_s1_dropped_invalid(self):
        self.assertEqual(judge.g1({"avg": 5, "fail": 0, "rate": 93, "p95": 14, "dropped": 3}, "S1"), ("무효", "무효"))


class TestG2(unittest.TestCase):
    def test_boundary_inclusive(self):
        out = section(s0_case(g2s=[g2(p95=15.0, drain=15.0)] * 3).judge(), "S0")
        self.assertIn("→ 통과", out.split("G2:")[1])

    def test_over_limit(self):
        out = section(s0_case(g2s=[g2(p95=15.01)] * 3).judge(), "S0")
        self.assertIn("→ 불합격", out.split("G2:")[1])

    def test_drain_over_limit(self):
        out = section(s0_case(g2s=[g2(drain=16)] * 3).judge(), "S0")
        self.assertIn("→ 불합격", out.split("G2:")[1])

    def test_median_used(self):
        out = section(s0_case(g2s=[g2(p95=5), g2(p95=30), g2(p95=6)]).judge(), "S0")
        self.assertIn("p95 6.00초", out)

    def test_confirmed_mismatch_blocks_s0_verdict(self):  # 무효 회차는 중앙값에 넣지 않는다
        out = section(s0_case(g2s=[g2(), g2(confirmed=999), g2()]).judge(), "S0")
        self.assertIn("판정하지 않음", out)
        self.assertIn("유효 2개", out)

    def test_s0_incomplete_run_is_invalid(self):  # 2분 안에 1,000건을 다 못 보낸 회차 (확정 수 = 요청 수라 G2만 보면 유효로 보인다)
        c = Case()
        for i in range(3):
            n = 950 if i == 1 else 1000
            c.run(f"r{i + 1}", "S0", "measure", "", [U[i]], summary(requests=n), g2(confirmed=n))
        out = c.judge()
        line = [x for x in out.splitlines() if x.startswith("| r2 ")][0]
        self.assertIn("무효(요청 950 / 예상 1000)", line)
        self.assertIn("| 무효 | 무효 |", line)
        s0 = section(out, "S0")
        self.assertIn("판정하지 않음", s0)
        self.assertIn("유효 2개", s0)

    def test_s0_complete_runs_judged(self):
        out = section(s0_case().judge(), "S0")
        self.assertIn("G1:", out)

    def test_no_confirmed(self):
        c = s0_case(g2s=[g2(), None, g2()])
        self.assertIn("판정하지 않음", section(c.judge(), "S0"))

    def test_warmup_shown_as_reference(self):
        out = s0_case().judge()
        line = [x for x in out.splitlines() if x.startswith("| w1 ")][0]
        self.assertIn("참고", line)


class TestS1(unittest.TestCase):
    def s1(self, stages):
        c = Case()
        for i, (rate, s, d) in enumerate(stages):
            c.run(f"s1-{rate}", "S1", "measure", rate, [U[2 * i], U[2 * i + 1]], s, d)
        return section(c.judge(), "S1")

    def test_limit_point(self):
        out = self.s1([(100, summary(requests=9000, rate=93, avg=4, p95=14, dropped=0), g2(confirmed=9000, p95=1, drain=1)),
                       (200, summary(requests=18000, rate=186, avg=5, p95=25, dropped=0), g2(confirmed=18000, p95=40, drain=50)),
                       (300, summary(requests=27000, rate=278, avg=5, p95=26, dropped=0), g2(confirmed=27000, p95=90, drain=99))])
        self.assertIn("한계 지점: 200건/초", out)

    def test_not_reached(self):
        out = self.s1([(100, summary(requests=9000, rate=93, avg=4, p95=14, dropped=0), g2(confirmed=9000, p95=1, drain=1))])
        self.assertIn("한계 지점 미도달", out)

    def test_dropped_makes_invalid(self):
        out = self.s1([(100, summary(requests=9000, rate=93, avg=4, p95=14, dropped=5), g2(confirmed=9000, p95=1, drain=1))])
        self.assertIn("G2 무효(밀린 반복)", out)
        self.assertIn("측정 실패", out)

    def test_delivered_uses_request_count(self):  # 스모크: k6 rate 78.6건/초였지만 실제로 998/1000건을 보냈다 (rate에 VU 준비 시간이 섞임)
        c = Case()
        c.run("s1-100", "S1", "measure", 100, [U[0]], summary(requests=998, rate=78.6, avg=7, p95=36, dropped=0),
              g2(confirmed=998, p95=1, drain=1), dur=10)
        line = [x for x in c.judge().splitlines() if x.startswith("| s1-100 ")][0]
        self.assertIn("| 78.6 | 100% |", line)

    def test_delivered_without_duration_is_dash(self):
        c = Case()
        c.run("s1-100", "S1", "measure", 100, [U[0]], summary(requests=998, rate=78.6, dropped=0), g2(confirmed=998, p95=1, drain=1))
        line = [x for x in c.judge().splitlines() if x.startswith("| s1-100 ")][0]
        self.assertIn("| 78.6 | - |", line)

    def test_stage_order_numeric(self):  # 문자열 정렬이면 1000이 200보다 앞선다
        out = self.s1([(1000, summary(requests=1, rate=1, avg=900, dropped=0), g2(confirmed=1)),
                       (200, summary(requests=1, rate=1, avg=4, dropped=0), g2(confirmed=1, p95=1, drain=1))])
        self.assertLess(out.index("200건/초"), out.index("1000건/초"))
        self.assertIn("한계 지점: 1000건/초", out)


class TestInput(unittest.TestCase):
    def test_bad_manifest(self):
        c = Case()
        path = os.path.join(c.batch, "manifest.csv")
        with open(path, "w", encoding="utf-8") as f:
            f.write("run_id,x\nA,B\n")
        with self.assertRaises(ValueError):
            judge.judge(path, 15, 15, query=lambda i, c: None, out=io.StringIO())

    def test_bad_uuid_rejected_before_sql(self):
        with self.assertRaises(ValueError) as e:
            judge.query_g2(["x');drop table t;--"], "ipo-postgres")
        self.assertIn("drop table", str(e.exception))  # 컨테이너 이름이 아니라 그 ID 때문에 거절됐는지 확인


class FakeRun:
    """subprocess.run을 바꿔서 실제 query_g2 경로(인자 순서, SQL, 출력 해석)를 그대로 지나가게 한다"""
    def __init__(self, stdout="", returncode=0, stderr=""):
        self.calls, self.stdout, self.returncode, self.stderr = [], stdout, returncode, stderr

    def __call__(self, cmd, **kw):
        self.calls.append(cmd)
        return type("R", (), {"returncode": self.returncode, "stdout": self.stdout, "stderr": self.stderr})()


class TestRealQueryPath(unittest.TestCase):
    def setUp(self):
        self.orig = judge.subprocess.run

    def tearDown(self):
        judge.subprocess.run = self.orig

    def test_judge_with_default_query(self):  # 스모크에서 드러난 인자 순서 결함 재현
        fake = FakeRun("1000,3.5,6.25,7.0,6.5\n")
        judge.subprocess.run = fake
        c = Case()
        c.run("r1", "S0", "measure", "", [U[0]], summary(), None)
        path = c.manifest()
        out = io.StringIO()
        judge.judge(path, 15, 15, container="ipo-postgres", out=out)
        cmd = fake.calls[0]
        self.assertEqual(cmd[:4], ["docker", "exec", "-i", "ipo-postgres"])
        self.assertIn(f"'{U[0]}'", cmd[-1])
        self.assertIn("3.50 / 6.25 / 7.00", out.getvalue())

    def test_multiple_ipos_in_sql(self):
        fake = FakeRun("2,1,2,3,4\n")
        judge.subprocess.run = fake
        judge.query_g2([U[0], U[1]], "ipo-postgres")
        self.assertIn(f"in ('{U[0]}','{U[1]}')", fake.calls[0][-1])

    def test_psql_failure_raises(self):
        judge.subprocess.run = FakeRun(returncode=2, stderr="connection refused")
        with self.assertRaises(RuntimeError):
            judge.query_g2([U[0]], "ipo-postgres")

    def test_unexpected_output_raises(self):
        judge.subprocess.run = FakeRun("ERROR something\n")
        with self.assertRaises(RuntimeError):
            judge.query_g2([U[0]], "ipo-postgres")

    def test_zero_confirmed_is_none(self):
        judge.subprocess.run = FakeRun("0,,,,\n")
        self.assertIsNone(judge.query_g2([U[0]], "ipo-postgres"))

    def test_missing_metric(self):
        c = Case()
        c.run("r", "S0", "measure", "", [U[0]], {"metrics": {"http_reqs": {"count": 1, "rate": 1}}}, g2())
        with self.assertRaises(ValueError):
            c.judge()


if __name__ == "__main__":
    unittest.main(verbosity=2)
