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
MANIFEST_HEADER = "run_id,scenario,phase,repeat,target_rate,duration_s,expected_requests,ipo_ids,k6_start,k6_end,drain_s,drain_end\n"


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

    def run(self, run_id, scenario, phase, target, ipos, s, d, dur="", expected=None, rep=""):
        os.makedirs(os.path.join(self.tmp, run_id))
        with open(os.path.join(self.tmp, run_id, "summary.json"), "w", encoding="utf-8") as f:
            json.dump(s, f)
        if expected is None:  # official.sh처럼 S0은 1,000건, S1은 목표 속도 x 유지 시간
            expected = 1000 if scenario == "S0" else (int(target) * int(dur) if target and dur else "")
        self.rows.append(f"{run_id},{scenario},{phase},{rep},{target},{dur},{expected},{';'.join(ipos)},0,0,1")
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
    def s1(self, stages, repeat=3):
        """단계마다 같은 값으로 repeat회 (official.sh 기본과 같이 3회)"""
        c = Case()
        n = 0
        for rate, s, d in stages:
            for k in range(1, repeat + 1):
                c.run(f"s1-{rate}-r{k}", "S1", "measure", rate, [U[n]], s, d, rep=k)
                n += 1
        return section(c.judge(), "S1")

    def runs(self, rate, items):
        """한 단계의 반복마다 다른 값: items = [(summary, g2), ...]"""
        c = Case()
        for k, (s, d) in enumerate(items, start=1):
            c.run(f"s1-{rate}-r{k}", "S1", "measure", rate, [U[k]], s, d, rep=k)
        return section(c.judge(), "S1")

    def test_median_of_three(self):  # 반복 3회 중 하나가 튀어도 중앙값으로 판정
        ok = summary(requests=9000, avg=5, p95=20, dropped=0)
        out = self.runs(100, [(ok, g2(confirmed=9000, p95=5, drain=5)), (ok, g2(confirmed=9000, p95=40, drain=40)),
                              (ok, g2(confirmed=9000, p95=6, drain=7))])
        self.assertIn("100건/초 (3회 중앙값)", out)
        self.assertIn("p95 6.00초, 소진 7.00초 → 통과", out)

    def test_invalid_run_blocks_stage(self):  # 무효 회차가 섞이면 그 단계는 판정하지 않는다 (S0과 같은 규칙)
        ok = summary(requests=9000, avg=5, p95=20, dropped=0)
        out = self.runs(100, [(ok, g2(confirmed=9000, p95=5, drain=5)), (summary(requests=9000, dropped=3), g2(confirmed=9000)),
                              (ok, g2(confirmed=9000, p95=6, drain=7))])
        self.assertIn("100건/초: 판정하지 않음 (3회 중 유효 2회", out)
        self.assertIn("100건/초부터 판정 불가", out)

    def test_fewer_than_three_is_reference(self):
        out = self.s1([(100, summary(requests=9000, avg=4, p95=14, dropped=0), g2(confirmed=9000, p95=1, drain=1))], repeat=1)
        self.assertIn("1회 중앙값, 참고값: 1회", out)
        self.assertIn("3회 미만이라 참고값", out)

    def test_three_runs_no_reference_note(self):
        out = self.s1([(100, summary(requests=9000, avg=4, p95=14, dropped=0), g2(confirmed=9000, p95=1, drain=1))])
        self.assertNotIn("참고값", out)

    def test_limit_point(self):
        out = self.s1([(100, summary(requests=9000, rate=93, avg=4, p95=14, dropped=0), g2(confirmed=9000, p95=1, drain=1)),
                       (200, summary(requests=18000, rate=186, avg=5, p95=25, dropped=0), g2(confirmed=18000, p95=40, drain=50)),
                       (300, summary(requests=27000, rate=278, avg=5, p95=26, dropped=0), g2(confirmed=27000, p95=90, drain=99))])
        self.assertIn("한계 지점: 200건/초", out)

    def test_not_reached(self):
        out = self.s1([(100, summary(requests=9000, rate=93, avg=4, p95=14, dropped=0), g2(confirmed=9000, p95=1, drain=1))])
        self.assertIn("한계 지점 미도달", out)

    def test_dropped_makes_invalid(self):
        c = Case()
        c.run("s1-100-r1", "S1", "measure", 100, [U[0]], summary(requests=9000, rate=93, avg=4, p95=14, dropped=5),
              g2(confirmed=9000, p95=1, drain=1), rep=1)
        full = c.judge()
        line = [x for x in full.splitlines() if x.startswith("| s1-100-r1 ")][0]
        self.assertIn("무효(밀린 반복)", line)
        self.assertIn("측정 실패", section(full, "S1"))

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
    """subprocess.run을 바꿔서 실제 query_g2 경로(인자 순서, SQL, 출력 해석)를 그대로 지나가게 한다.
    stdout이 목록이면 호출마다 차례로 하나씩 돌려준다 (query_counts처럼 psql을 여러 번 부르는 경로)"""
    def __init__(self, stdout="", returncode=0, stderr=""):
        self.calls, self.stdout, self.returncode, self.stderr = [], stdout, returncode, stderr

    def __call__(self, cmd, **kw):
        self.calls.append(cmd)
        out = self.stdout.pop(0) if isinstance(self.stdout, list) else self.stdout
        return type("R", (), {"returncode": self.returncode, "stdout": out, "stderr": self.stderr})()


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

    def test_query_counts_parses_all_queries(self):
        fake = FakeRun(["CONFIRMED,-,903\nREJECTED,INSUFFICIENT_BALANCE,97\n", "903,0.42,1.25\n", "lb0003,1000,0\nlb0007,149000,0\n"])
        judge.subprocess.run = fake
        c = judge.query_counts([U[0]], "ipo-postgres", ["lb0003", "lb0007"])
        self.assertEqual(c["by_status"], {("CONFIRMED", "-"): 903, ("REJECTED", "INSUFFICIENT_BALANCE"): 97})
        self.assertEqual((c["margins"], c["reject_p50"], c["reject_p95"]), (903, 0.42, 1.25))
        self.assertEqual(c["balances"], {"lb0003": (1000, 0), "lb0007": (149000, 0)})
        self.assertIn("in ('lb0003','lb0007')", fake.calls[2][-1])
        self.assertEqual(len(fake.calls), 3)

    def test_query_counts_no_rejection_and_no_logins(self):
        judge.subprocess.run = FakeRun(["CONFIRMED,-,10\n", "10,,\n"])
        c = judge.query_counts([U[0]], "ipo-postgres")
        self.assertIsNone(c["reject_p50"])
        self.assertEqual(c["balances"], {})

    def test_query_counts_rejects_bad_login_before_sql(self):
        fake = FakeRun("")
        judge.subprocess.run = fake
        with self.assertRaises(ValueError):
            judge.query_counts([U[0]], "ipo-postgres", ["lb0001'); drop table x;--"])
        self.assertEqual(fake.calls, [])

    def test_query_counts_bad_output_raises(self):
        judge.subprocess.run = FakeRun(["CONFIRMED,903\n"])
        with self.assertRaises(RuntimeError):
            judge.query_counts([U[0]], "ipo-postgres")


# ---------- S3, S4 (Synthetic Data) ----------

EXP = {
    "S3": {"scenario": "S3", "requests": 1000, "subscription_rows": 920, "confirmed": 860, "rejected": {"ALREADY_SUBSCRIBED": 60},
           "margins": 860, "detail": {}},
    "S4": {"scenario": "S4", "requests": 1000, "subscription_rows": 1000, "confirmed": 903, "rejected": {"INSUFFICIENT_BALANCE": 97},
           "margins": 903, "detail": {"short_vusers": [3, 7], "deposits": {"3": 1000, "7": 149000}}},
}


def counts(confirmed, rejected, margins, stuck=None, other=None, balances=None, rp=(0.4, 1.1)):
    by = {("CONFIRMED", "-"): confirmed}
    for reason, n in rejected.items():
        by[("REJECTED", reason)] = n
    for st, n in (stuck or {}).items():
        by[(st, "-")] = n
    for st, n in (other or {}).items():
        by[(st, "-")] = n
    return {"by_status": by, "margins": margins, "reject_p50": rp[0], "reject_p95": rp[1], "balances": balances or {}}


S3_OK = counts(860, {"ALREADY_SUBSCRIBED": 60}, 860)
S4_OK = counts(903, {"INSUFFICIENT_BALANCE": 97}, 903, balances={"lb0003": (1000, 0), "lb0007": (149000, 0)})


class SynthCase(Case):
    def __init__(self, exp=None):
        super().__init__()
        self.counts, self.logins, self.exp = {}, {}, exp or EXP

    def srun(self, run_id, scenario, phase, ipo, s, d, c, expected=1000):
        self.run(run_id, scenario, phase, "", [ipo], s, d, expected=expected)
        self.counts[(ipo,)] = c

    def judge(self, p95=15, drain=15, real_expected=False):
        path = self.manifest()
        out = io.StringIO()

        def fake_counts(ipos, container, logins=()):
            if tuple(ipos) not in self.counts or self.counts[tuple(ipos)] is None:
                raise AssertionError(f"조회하면 안 되는 회차: {ipos}")
            self.logins[tuple(ipos)] = list(logins)
            return self.counts[tuple(ipos)]
        kw = {} if real_expected else {"expected_of": lambda s: self.exp[s]}
        judge.judge(path, p95, drain, query=lambda ipos, c: self.g2[tuple(ipos)], counts=fake_counts, out=out, **kw)
        return out.getvalue()


def synth_case(scen="S3", cs=None, g2s=None, sums=None, with_s0=True):
    ok = S3_OK if scen == "S3" else S4_OK
    conf = 860 if scen == "S3" else 903
    c = SynthCase()
    if with_s0:
        for i in range(3):
            c.run(f"s0-r{i + 1}", "S0", "measure", "", [U[i]], summary(rate=900, avg=4), g2(p95=1.5, drain=1.5))
    c.srun(f"{scen}-W", scen, "warmup", U[3], summary(), g2(confirmed=conf), ok)
    for i in range(3):
        c.srun(f"{scen}-r{i + 1}", scen, "measure", U[4 + i], (sums or [summary(rate=850, avg=5, p95=9)] * 3)[i],
               (g2s or [g2(confirmed=conf, p95=1.8, drain=2.0)] * 3)[i], (cs or [ok] * 3)[i])
    return c


class TestSynthetic(unittest.TestCase):
    def test_s3_all_pass(self):
        out = synth_case("S3").judge()
        sec = section(out, "S3 (measure")
        self.assertIn("3회 중 통과 3회 → 통과", sec)
        self.assertIn("합격 통과", sec)
        self.assertIn("G2 (확정 건): 접수→확정 p95 1.80초, 소진 2.00초 → 통과", sec)
        self.assertIn("거절 지연 (참고): p50 0.40초, p95 1.10초", sec)
        integ = section(out, "정합성")
        self.assertIn("| S3-r1 | S3 | measure | 920 (920) | 860 (860) | ALREADY_SUBSCRIBED 60 (ALREADY_SUBSCRIBED 60) | 860 (860) | 0 |", integ)
        self.assertIn("| S3-W | S3 | warmup |", integ)

    def test_comparison_with_s0(self):
        out = section(synth_case("S3").judge(), "S0 대비")
        self.assertIn("| S0 | 900.0 | 4.0 | 130.0 | 1.50 | 1.50 |", out)
        self.assertIn("| S3 | 850.0 (-50.0) | 5.0 (+1.0) | 9.0 (-121.0) | 1.80 (+0.30) | 2.00 (+0.50) |", out)

    def test_comparison_without_s0(self):
        out = section(synth_case("S3", with_s0=False).judge(), "S0 대비")
        self.assertIn("S0 중앙값이 없어 비교하지 않음", out)

    def test_g2_uses_expected_confirmed_not_requests(self):  # S0 규칙(확정 = 요청)이면 거절이 섞인 S3는 항상 무효가 된다
        self.assertEqual(judge.g2({"requests": 1000, "dropped": 0}, g2(confirmed=860), "S3", 15, 15, expected_confirmed=860), "통과")
        self.assertTrue(judge.g2({"requests": 1000, "dropped": 0}, g2(confirmed=859), "S3", 15, 15, expected_confirmed=860).startswith("무효(확정 859 / 기대 확정 860)"))

    def test_rate_applies_to_s3_s4(self):  # 부하 모양이 S0과 같으므로 처리량 기준도 같다
        for scen in ("S3", "S4"):
            self.assertEqual(judge.g1({"avg": 5, "fail": 0, "rate": 199, "p95": 9, "dropped": 0}, scen)[0], "불합격")

    def test_stuck_and_mismatch_fail_integrity(self):
        bad = counts(859, {"ALREADY_SUBSCRIBED": 60}, 859, stuck={"PENDING": 1})
        out = synth_case("S3", cs=[S3_OK, bad, S3_OK], g2s=[g2(confirmed=860), g2(confirmed=859), g2(confirmed=860)]).judge()
        integ = section(out, "정합성")
        self.assertIn("확정 859 ≠ 기대 860", integ)
        self.assertIn("미처리 1건 (PENDING 1)", integ)
        self.assertIn("증거금 859 ≠ 기대 860", integ)
        sec = section(out, "S3 (measure")
        self.assertIn("3회 중 통과 2회, 그 밖 불합격 → 통과 아님", sec)
        self.assertIn("G1, G2 판정하지 않음", sec)  # 확정 수가 기대와 달라 G2 무효

    def test_wrong_reason_and_other_status(self):
        bad = counts(860, {"ALREADY_SUBSCRIBED": 59, "INSUFFICIENT_BALANCE": 1}, 860, other={"CANCELLED": 1})
        out = section(synth_case("S3", cs=[bad, S3_OK, S3_OK]).judge(), "정합성")
        self.assertIn("거절 ALREADY_SUBSCRIBED 59 ≠ 기대 60", out)
        self.assertIn("거절 INSUFFICIENT_BALANCE 1 ≠ 기대 0", out)
        self.assertIn("그 밖의 상태 1건 (CANCELLED 1)", out)
        self.assertIn("청약 행 921 ≠ 기대 920", out)

    def test_http_error_is_undecidable_not_defect(self):
        out = synth_case("S3", sums=[summary(fail=0.002), summary(), summary()]).judge()
        self.assertIn("판정 불가 | HTTP 오류 0.20%", section(out, "정합성"))
        self.assertIn("그 밖 판정 불가", section(out, "S3 (measure"))

    def test_request_count_mismatch_is_invalid_and_not_queried(self):
        c = synth_case("S3", sums=[summary(requests=999), summary(), summary()])
        c.counts[(U[4],)] = None  # 조회되면 실패
        out = c.judge()
        self.assertIn("| S3-r1 | S3 | measure | - (920) |", section(out, "정합성"))
        self.assertIn("무효 | 요청 999 / 예상 1000", section(out, "정합성"))
        self.assertIn("G1, G2 판정하지 않음", section(out, "S3 (measure"))

    def test_manifest_expected_must_match_csv(self):
        c = SynthCase()
        c.srun("S3-r1", "S3", "measure", U[0], summary(), g2(confirmed=860), S3_OK, expected=900)
        with self.assertRaises(ValueError) as e:
            c.judge()
        self.assertIn("900", str(e.exception))

    def test_s4_passes_logins_and_checks_balances(self):
        c = synth_case("S4")
        out = c.judge()
        self.assertEqual(c.logins[(U[4],)], ["lb0003", "lb0007"])
        self.assertIn("3회 중 통과 3회 → 통과", section(out, "S4 (measure"))

    def test_s4_money_moved_fails(self):
        moved = counts(903, {"INSUFFICIENT_BALANCE": 97}, 903, balances={"lb0003": (850, 150), "lb0007": (149000, 0)})
        missing = counts(903, {"INSUFFICIENT_BALANCE": 97}, 903, balances={"lb0003": (1000, 0)})
        out = section(synth_case("S4", cs=[moved, missing, S4_OK]).judge(), "정합성")
        self.assertIn("예수금 부족 투자자의 돈이 움직임 1명 (lb0003 예수금 850/입금 1000, 청약증거금 150)", out)
        self.assertIn("lb0007 계좌 없음", out)

    def test_s4_prefix(self):
        c = synth_case("S4")
        path = c.manifest()
        seen = {}

        def fake_counts(ipos, container, logins=()):
            seen[tuple(ipos)] = logins
            return dict(c.counts[tuple(ipos)], balances={"zz0003": (1000, 0), "zz0007": (149000, 0)})
        out = io.StringIO()
        judge.judge(path, 15, 15, query=lambda ipos, x: c.g2[tuple(ipos)], out=out, s4_prefix="zz",
                    expected_of=lambda s: EXP[s], counts=fake_counts)
        self.assertEqual(seen[(U[4],)], ["zz0003", "zz0007"])
        self.assertIn("3회 중 통과 3회 → 통과", section(out.getvalue(), "S4 (measure"))

    def test_real_expected_from_committed_csv(self):
        out = synth_case("S3").judge(real_expected=True)
        self.assertIn("920 (920) | 860 (860) | ALREADY_SUBSCRIBED 60 (ALREADY_SUBSCRIBED 60)", section(out, "정합성"))

    def test_bad_behavior_csv_stops_judging(self):
        tmp = tempfile.mkdtemp()
        fake = os.path.join(tmp, "validate.py")
        with open(fake, "w", encoding="utf-8") as f:
            f.write("def check(scenario):\n    return ['0행: 규칙 위반'], None\n")
        orig = judge.VALIDATE_PY
        judge.VALIDATE_PY = fake
        try:
            with self.assertRaises(ValueError) as e:
                synth_case("S3").judge(real_expected=True)
            self.assertIn("규칙 위반", str(e.exception))
        finally:
            judge.VALIDATE_PY = orig

    def test_s0_s1_output_unchanged_without_synthetic(self):  # S3, S4가 없으면 새 절이 나오지 않는다
        out = s0_case().judge()
        self.assertNotIn("정합성", out)
        self.assertNotIn("S0 대비", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
