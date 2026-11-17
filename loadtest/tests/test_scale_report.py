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
            n, _, dup, unknown = sr.v2(d)[("subscription", "Unknown.run")]
        self.assertIn("판정 불가", sr.v2_verdict(n, dup, unknown))

    def test_판정_가능한_획득이_있으면_중복_판정을_유지한다(self):
        self.assertEqual(sr.v2_verdict(3, 0, 1), "✅ 통과")
        self.assertEqual(sr.v2_verdict(3, 1, 1), "❌ 불합격")


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

    def run_(self, n, integ=0, perf=True):
        p = {"success": 1.0, "rate": 300.0, "p95": 300.0, "confirm_p95": 3.0,
             "pending": {"subscription-service": 0.0}, "active": {}, "cpu": {"api-gateway": 50.0}} if perf else None
        return {"n": n, "incomplete": False, "invalid": [], "topics": [], "dup": 0, "short": 0,
                "integ": {"v17_dup_margin": integ}, "violations": integ, "dup_log": 0, "meta": {}, "status": "", "perf": p}

    def render(self, phases):
        lines = []
        sr.perf_section(lines.append, phases)
        return "\n".join(lines)

    def test_정합성_실패_회차는_성능을_판정하지_않고_중앙값에서_제외한다(self):
        out = self.render((("2개 (1차)", "", {"commit": "a"}, [self.run_(1), self.run_(2, integ=1)]),))
        self.assertIn("정합성 실패 (성능 판정 안 함)", out)
        self.assertIn("| 2개 (1차) | 1 |", out)

    def test_단계별_커밋이_다르면_비교_무효를_표시한다(self):
        out = self.render((("2개 (1차)", "", {"commit": "a"}, [self.run_(1)]), ("1개", "", {"commit": "b"}, [self.run_(1)])))
        self.assertIn("비교 무효", out)

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
