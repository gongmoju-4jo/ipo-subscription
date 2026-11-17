"""scale_report.py 테스트 (결과 파일을 임시 폴더에 생성해 읽는다. Docker, DB 없이 실행)
사용: python3 loadtest/tests/test_scale_report.py   (scale_report.py 경로를 첫 인자로 지정 가능)
"""
import importlib.util
import os
import sys
import tempfile
import unittest

_path = sys.argv.pop(1) if len(sys.argv) > 1 and sys.argv[1].endswith(".py") else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scale_report.py")
spec = importlib.util.spec_from_file_location("scale_report", _path)
sr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sr)

CLOSE = "IpoCloseScheduler.closeDueIpos"


def write(d, name, text):
    with open(os.path.join(d, name), "w", encoding="utf-8") as f:
        f.write(text)


def lock_row(name, by, at, until):
    return f"subscription|{name}|{by}|2026-11-17 01:00:{at}|2026-11-17 {until}\n"


class V2Test(unittest.TestCase):

    def v2(self, rows):
        with tempfile.TemporaryDirectory() as d:
            write(d, "v2-shedlock.txt", "".join(rows))
            write(d, "v2-seconds.txt", "120\n")
            return sr.v2(d)[("subscription", rows[0].split("|")[1])]

    def test_실행_중_값만_조회된_두_획득은_해제_미관측이고_중복_획득이_아니다(self):
        # 두 획득 모두 lock_until = 획득 + 1분(실행 중 값)만 조회됨. 해제 시각을 알 수 없어 중복 판정하지 않음
        n, hosts, dup, unknown = self.v2([
            lock_row(CLOSE, "h1", "00", "01:01:00"),
            lock_row(CLOSE, "h2", "05", "01:01:05"),
        ])
        self.assertEqual((n, hosts, dup, unknown), (2, 2, 0, 2))

    def test_해제_시각_이전에_다음_획득이_있으면_중복_획득이다(self):
        # A: 00초 획득, 04초 해제 관측 / B: 02초 획득 → 중복 1
        n, _, dup, unknown = self.v2([
            lock_row(CLOSE, "h1", "00", "01:00:04"),
            lock_row(CLOSE, "h2", "02", "01:00:06"),
        ])
        self.assertEqual((n, dup, unknown), (2, 1, 0))

    def test_해제_후_다음_획득은_중복_획득이_아니다(self):
        # A: 실행 중 값과 해제 값 모두 조회 (해제 04초) / B: 05초 획득 → 중복 0
        n, _, dup, unknown = self.v2([
            lock_row(CLOSE, "h1", "00", "01:01:00"),
            lock_row(CLOSE, "h1", "00", "01:00:04"),
            lock_row(CLOSE, "h2", "05", "01:00:09"),
        ])
        self.assertEqual((n, dup, unknown), (2, 0, 0))

    def test_설정값을_알_수_없는_락은_모든_획득이_해제_미관측이다(self):
        n, _, dup, unknown = self.v2([
            lock_row("Unknown.run", "h1", "00", "01:00:04"),
            lock_row("Unknown.run", "h2", "02", "01:00:06"),
        ])
        self.assertEqual((n, dup, unknown), (2, 0, 2))


class V2VerdictTest(unittest.TestCase):

    def test_모든_획득이_해제_미관측이면_판정_불가다(self):
        with tempfile.TemporaryDirectory() as d:
            write(d, "v2-shedlock.txt", lock_row("Unknown.run", "h1", "00", "01:00:04") + lock_row("Unknown.run", "h2", "02", "01:00:06"))
            write(d, "v2-seconds.txt", "120\n")
            n, _, dup, unknown = sr.v2(d)[("subscription", "Unknown.run")]
        self.assertIn("판정 불가", sr.v2_verdict(n, dup, unknown))

    def test_판정_가능한_획득이_있으면_중복_판정을_유지한다(self):
        self.assertEqual(sr.v2_verdict(3, 0, 1), "✅ 통과")
        self.assertEqual(sr.v2_verdict(3, 1, 1), "❌ 불합격")


class ReviewFixTest(unittest.TestCase):

    def test_V2_완료_표시가_없으면_측정_안_함이다(self):
        with tempfile.TemporaryDirectory() as d:
            write(d, "v2-shedlock.txt", lock_row(CLOSE, "h1", "00", "01:00:04"))
            self.assertIsNone(sr.v2(d))

    def test_회차_보고서_실패는_성능만_제외하고_회차는_유효하다(self):
        with tempfile.TemporaryDirectory() as d:
            V3Test().run_dir(d, 1, meta="k6_exit=0\nreport_exit=1\ncomplete=1\n")
            r = sr.v3(d)[0]
        self.assertEqual(r["invalid"], [])
        self.assertIsNone(r["perf"])

    def test_summary_지표가_null이면_성능은_None이다(self):
        with tempfile.TemporaryDirectory() as root:
            run = os.path.join(root, "loadtest", "results", "run-x")
            os.makedirs(run)
            write(run, "summary.json", '{"metrics": {"http_req_failed": {"value": null}, "http_reqs": {"rate": 1}, "http_req_duration": {"p(95)": 1}}}')
            write(run, "prometheus.json", "{}")
            phase = os.path.join(root, "phase")
            os.makedirs(phase)
            write(phase, "v3-r1-meta.txt", "k6_exit=0\n")
            cwd = os.getcwd()
            try:
                os.chdir(root)
                self.assertIsNone(sr.perf("run-x", phase, 1))
            finally:
                os.chdir(cwd)

    def test_이전_처리_종료_시각이_비면_잔여_처리_확인_안_함이다(self):
        with tempfile.TemporaryDirectory() as root:
            run = os.path.join(root, "loadtest", "results", "run-y")
            os.makedirs(run)
            write(run, "summary.json", '{"metrics": {"http_req_failed": {"value": 0}, "http_reqs": {"rate": 1}, "http_req_duration": {"p(95)": 1}}}')
            write(run, "prometheus.json", "{}")
            write(run, "report.md", "| 서버 지표 구간 | 2026-11-17 15:47:31 ~ 2026-11-17 15:47:54 |\n")
            phase = os.path.join(root, "phase")
            os.makedirs(phase)
            write(phase, "v3-r1-meta.txt", "start_lag=0\nprev_activity_end=\n")
            cwd = os.getcwd()
            try:
                os.chdir(root)
                self.assertIsNone(sr.perf("run-y", phase, 1)["residual_ok"])
            finally:
                os.chdir(cwd)

    def run_(self, n, dup=0, commit="a", invalid=None):
        return {"n": n, "incomplete": False, "invalid": invalid or [], "topics": [], "dup": dup, "short": 0,
                "integ": {"v17_dup_margin": 0}, "violations": 0, "dup_log": 0, "meta": {"commit": commit}, "status": "",
                "perf": {"residual_ok": True, "success": 1.0, "rate": 300.0, "p95": 300.0, "confirm_p95": 3.0,
                         "pending": {}, "active": {}, "cpu": {}}}

    def test_적용_후_커밋_단계의_중복_발행_회차는_성능_판정에서_제외한다(self):
        lines = []
        sr.perf_section(lines.append, (("1개", "", {}, [self.run_(1, dup=3)]),))
        self.assertIn("정합성 실패 (성능 판정 안 함)", "\n".join(lines))

    def test_회차별_커밋이_다르면_최종_성능은_비교_무효다(self):
        summary = sr.perf_section(lambda _: None, (("2개 (1차)", "", {}, [self.run_(1, commit="a")]),
                                                   ("1개", "", {}, [self.run_(1, commit="b")]),
                                                   ("2개 (2차)", "", {}, [self.run_(1, commit="a")])))
        rows = dict((k, v) for k, v, _ in sr.final_verdicts(None, None, [], [], [], [], {}, summary, 1.2))
        self.assertEqual(rows["성능 (같은 커밋, 2개 vs 1개)"], "⚠️ 비교 무효")

    def test_유효_회차가_없는_단계가_있으면_정합성은_판정_불가다(self):
        rows = dict((k, v) for k, v, _ in sr.final_verdicts(None, None, [self.run_(1)], [self.run_(1, invalid=["k6 종료 코드 1"])], [], [], {}, {}, 1.2))
        self.assertIn("판정 불가", rows["정합성"])

    def test_V2를_측정하지_않으면_중복_실행은_판정_불가다(self):
        a1 = {k: (12 if k != "원장 대사" else 1, []) for k in sr.EXPECT}
        rows = dict((k, v) for k, v, _ in sr.final_verdicts(a1, None, [], [self.run_(1)], [], [], {}, {}, 1.2))
        self.assertIn("판정 불가 (V2 측정 안 함)", rows["중복 실행 (적용 후)"])


class V3Test(unittest.TestCase):

    def run_dir(self, root, n, meta="k6_exit=0\ncomplete=1\n", after="margin.reserve-requested 1000\nmargin.reserved 1000\n",
                integrity="v02_margin_balance|0\n", status="CONFIRMED|1000\n"):
        p = f"v3-r{n}"
        write(root, p + "-meta.txt", meta)
        write(root, p + "-offsets-before.txt", "margin.reserve-requested 0\nmargin.reserved 0\n")
        write(root, p + "-offsets-after.txt", after)
        write(root, p + "-outbox.txt", "margin.reserve-requested|1000\nmargin.reserved|1000\n")
        write(root, p + "-integrity.txt", integrity)
        write(root, p + "-status.txt", status)
        write(root, p + "-dup-log.txt", "0\n")

    def one(self, **kw):
        with tempfile.TemporaryDirectory() as d:
            self.run_dir(d, 1, **kw)
            return sr.v3(d)[0]

    def test_토픽별_양수와_음수가_상쇄되지_않는다(self):
        r = self.one(after="margin.reserve-requested 1003\nmargin.reserved 997\n")
        self.assertEqual((r["dup"], r["short"]), (3, 3))

    def test_위반_항목의_음수가_다른_위반을_상쇄하지_않는다(self):
        r = self.one(integrity="v02_margin_balance|1\nv04_confirmed_minus_pending_margin|-1\n")
        self.assertEqual(r["violations"], 2)

    def test_k6_실행_실패_회차는_무효다(self):
        r = self.one(meta="k6_exit=1\ncomplete=1\n")
        self.assertTrue(any("k6" in x for x in r["invalid"]))

    def test_k6_임계값_위반_종료_코드_99는_유효하다(self):
        r = self.one(meta="k6_exit=99\ncomplete=1\n")
        self.assertEqual(r["invalid"], [])

    def test_정합성_수집이_실패하면_무효다(self):
        r = self.one(meta="k6_exit=0\nverify_sh_exit=1\ncomplete=1\n")
        self.assertTrue(any("정합성 수집" in x for x in r["invalid"]))

    def test_청약_행이_요청_수와_다르면_무효다(self):
        r = self.one(status="CONFIRMED|900\n")
        self.assertTrue(any("청약 행" in x for x in r["invalid"]))

    def test_완료_표시가_없으면_무효다(self):
        r = self.one(meta="k6_exit=0\n")
        self.assertIn("완료 표시 없음", r["invalid"])

    def test_meta_tmp만_있는_회차는_미완료다(self):
        with tempfile.TemporaryDirectory() as d:
            write(d, "v3-r1-meta.tmp", "ipo=x\n")
            runs = sr.v3(d)
        self.assertEqual(runs, [{"n": 1, "incomplete": True}])


class PerfSectionTest(unittest.TestCase):

    def run_(self, n, integ=0, perf=True, residual_ok=True, p95=300.0):
        p = {"residual_ok": residual_ok, "success": 1.0, "rate": 300.0, "p95": p95, "confirm_p95": 3.0,
             "pending": {"subscription-service": 0.0}, "active": {}, "cpu": {"api-gateway": 50.0}} if perf else None
        return {"n": n, "incomplete": False, "invalid": [], "topics": [], "dup": 0, "short": 0,
                "integ": {"v17_dup_margin": integ}, "violations": integ, "dup_log": 0, "meta": {"commit": "a"}, "status": "", "perf": p}

    def render(self, phases):
        lines = []
        sr.perf_section(lines.append, phases)
        return "\n".join(lines)

    def test_정합성_실패_회차는_성능을_판정하지_않고_중앙값에서_제외한다(self):
        out = self.render((("2개 (1차)", "", {"commit": "a"}, [self.run_(1), self.run_(2, integ=1)]),))
        self.assertIn("정합성 실패 (성능 판정 안 함)", out)
        self.assertIn("| 2개 (1차) | 1 |", out)

    def test_단계_env_커밋은_사용하지_않고_회차_커밋으로_판정한다(self):
        # 단계 env.txt 커밋이 서로 달라도(오래된 기록) 회차 커밋이 같으면 비교 유효
        out = self.render((("2개 (1차)", "", {"commit": "old"}, [self.run_(1)]), ("1개", "", {"commit": "a"}, [self.run_(1)])))
        self.assertNotIn("비교 무효", out)

    def test_회차_커밋이_없는_이전_결과가_포함되면_비교_무효다(self):
        old = self.run_(1)
        old["meta"] = {}
        out = self.render((("2개 (1차)", "", {"commit": "a"}, [old]), ("1개", "", {"commit": "a"}, [self.run_(1)])))
        self.assertIn("회차 커밋이 없는 회차가 있음", out)
        summary = sr.perf_section(lambda _: None, (("2개 (1차)", "", {"commit": "a"}, [old]), ("1개", "", {"commit": "a"}, [self.run_(1)]),
                                                   ("2개 (2차)", "", {"commit": "a"}, [self.run_(1)])))
        rows = dict((k, v) for k, v, _ in sr.final_verdicts(None, None, [], [], [], [], {}, summary, 1.2))
        self.assertEqual(rows["성능 (같은 커밋, 2개 vs 1개)"], "⚠️ 비교 무효")

    def test_잔여_처리_포함_가능_회차는_성능에서_제외한다(self):
        out = self.render((("2개 (1차)", "", {"commit": "a"}, [self.run_(1), self.run_(2, residual_ok=False), self.run_(3, residual_ok=None)]),))
        self.assertIn("잔여 처리 포함 가능 (성능 제외)", out)
        self.assertIn("잔여 처리 확인 안 함 (성능 제외)", out)
        self.assertIn("| 2개 (1차) | 1 |", out)

    def phases(self, a1, s1, a2):
        return (("2개 (1차)", "", {"commit": "a"}, [self.run_(1, p95=a1)]), ("1개", "", {"commit": "a"}, [self.run_(1, p95=s1)]),
                ("2개 (2차)", "", {"commit": "a"}, [self.run_(1, p95=a2)]))

    def test_성능_판정은_1개_대비_기준_배수_이하면_저하_없음이다(self):
        summary = sr.perf_section(lambda _: None, self.phases(330.0, 300.0, 320.0))
        rows = dict((k, v) for k, v, _ in sr.final_verdicts(None, None, [], [], [], [], {}, summary, 1.2))
        self.assertEqual(rows["성능 (같은 커밋, 2개 vs 1개)"], "✅ 저하 없음")

    def test_성능_판정은_기준_배수를_초과하면_저하_있음이다(self):
        summary = sr.perf_section(lambda _: None, self.phases(400.0, 300.0, 320.0))
        rows = dict((k, v) for k, v, _ in sr.final_verdicts(None, None, [], [], [], [], {}, summary, 1.2))
        self.assertEqual(rows["성능 (같은 커밋, 2개 vs 1개)"], "❌ 저하 있음")

    def test_측정하지_않은_항목은_측정_안_함이다(self):
        rows = dict((k, v) for k, v, _ in sr.final_verdicts(None, None, [], [], [], [], {}, {}, 1.2))
        self.assertEqual(set(rows.values()), {"측정 안 함"})

    def test_CPU는_인스턴스별_최댓값으로_표시한다(self):
        out = self.render((("2개 (1차)", "", {"commit": "a"}, [self.run_(1)]),))
        self.assertIn("인스턴스별 CPU 최대값 중 최댓값", out)


class V1Test(unittest.TestCase):

    def test_상수가_원문이거나_변환돼도_시간_초과로_매칭한다(self):
        raw = ("subscription_app|12|SELECT s.subscription_id FROM subscription s WHERE s.status = 'PENDING' AND NOT EXISTS (SELECT 1 FROM outbox o)\n"
               "subscription_app|7|SELECT s.subscription_id FROM subscription s WHERE s.status = $1 AND NOT EXISTS (SELECT $2 FROM outbox o)\n"
               "subscription_app|12|SELECT s.subscription_id FROM subscription s WHERE s.status IN ('COMPENSATING', 'CANCEL_REQUESTED') AND NOT EXISTS (SELECT 1 FROM outbox o)\n")
        with tempfile.TemporaryDirectory() as d:
            write(d, "v1-raw.txt", raw)
            res = sr.v1(d)
        self.assertEqual(res["시간 초과"][0], 12)
        self.assertEqual(len(res["시간 초과"][1]), 2)
        self.assertEqual(res["반환 재요청"][0], 12)


if __name__ == "__main__":
    unittest.main()
