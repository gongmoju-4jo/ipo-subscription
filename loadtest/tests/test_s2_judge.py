"""s2_judge.py 판정 테스트 (DB 없이 돈다. psql 호출은 가짜로 바꾼다)
사용: python loadtest/tests/test_s2_judge.py   (s2_judge.py 경로를 첫 인자로 줄 수 있다)
"""
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

_path = sys.argv.pop(1) if len(sys.argv) > 1 and sys.argv[1].endswith(".py") else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "verify", "s2_judge.py")
spec = importlib.util.spec_from_file_location("s2_judge", _path)
s2 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s2)

IPO = "6f1c3b0e-1111-4a2b-8c3d-000000000001"
END_MS = 1794215330000  # 대상 공모주 마감 시각
SQL_OK = """== 16. 같은 requestId의 청약이 2행 이상 (기대: 0행)
 request_id | count
------------+-------
(0 rows)

== 20. 마감 처리 뒤에 생긴 청약 (기대: 0행)
 subscription_id | status | received_at | created_at | closed_at
-----------------+--------+-------------+------------+-----------
(0 rows)

== 21. 마감 시각 이후 도착(received_at >= 마감 시각)인데 접수된 청약 (기대: 0행)
 subscription_id | status | received_at | subscription_end_at
-----------------+--------+-------------+---------------------
(0 rows)

== 22. 마감 때 확정 수와 지금 유효 청약 수가 다름 (기대: 0행)
 confirmed_at_close | valid_now
--------------------+-----------
(0 rows)
"""


class Case:
    """requests.log, summary.json, sql_result.txt와 가짜 DB 조회 결과를 만든다"""

    def __init__(self):
        self.tmp = tempfile.mkdtemp()
        self.rows = []
        self.dropped = 0
        self.check_fails = 0
        self.no_checks = False
        self.checks = None   # 주면 checks 칸을 그대로 쓴다
        self.no_dropped = False
        self.sql = SQL_OK
        self.closed = [["1794215376.497", "2"]]   # ipo.closed Outbox: 생성 시각, confirmedCount
        self.subs = ["t1", "t2"]                  # 대상 공모주 청약 행의 request_id

    def req(self, rid, status, stage="target", code=""):
        self.rows.append({"s": "S2", "run": "x", "idx": len(self.rows), "requestId": rid, "status": status,
                          "subscriptionId": "", "code": code, "stage": stage})
        return self

    def normal(self):
        return (self.req("t1", 202).req("t2", 202).req("t3", 422, code="SUBSCRIPTION_CLOSED")
                .req("t4", 422, code="SUBSCRIPTION_CLOSED").req("b1", 202, stage="bg"))

    def run(self):
        log = os.path.join(self.tmp, "requests.log")
        with open(log, "w", encoding="utf-8") as f:
            # k6 --console-output 한 줄 모양 (msg 안에 JSON이 따옴표 이스케이프로 들어간다)
            for r in self.rows:
                f.write('time="2026-11-09T09:00:00Z" level=info msg="' + json.dumps(r).replace('"', '\\"') + '" source=console\n')
        summ = os.path.join(self.tmp, "summary.json")
        with open(summ, "w", encoding="utf-8") as f:
            m = {} if self.no_dropped else {"dropped_iterations": {"count": self.dropped, "rate": 0}}
            if self.checks is not None:
                m["checks"] = self.checks
            elif not self.no_checks:
                m["checks"] = {"passes": 100, "fails": self.check_fails, "value": 1}
            json.dump({"metrics": m}, f)
        sqlf = os.path.join(self.tmp, "sql_result.txt")
        with open(sqlf, "w", encoding="utf-8") as f:
            f.write(self.sql)

        def fake_query(sql):
            if "ipo.closed" in sql:
                return self.closed
            if "request_id" in sql:
                return [[r] for r in self.subs]
            raise AssertionError("예상하지 않은 쿼리: " + sql)

        s2.query = fake_query
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = s2.main(["--requests", log, "--summary", summ, "--sql-result", sqlf, "--ipo", IPO, "--end-ms", str(END_MS)])
        return rc, out.getvalue(), err.getvalue()


class S2JudgeTest(unittest.TestCase):
    def test_valid_run(self):
        rc, out, _ = Case().normal().run()
        self.assertEqual(rc, 0)
        self.assertIn("회차: 유효", out)
        self.assertIn("마감 시각 + 46.497초", out)
        self.assertIn("(verify.sql 20번, 202를 받고 마감 집계에서 빠진 청약): 0", out)
        self.assertIn("202를 받았는데 청약 행이 없음 (k6 로그 대조): 0", out)

    def test_dropped_iterations_make_run_invalid(self):
        c = Case().normal()
        c.dropped = 103
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("밀린 요청 103건", out)

    def test_k6_check_failures_make_run_invalid(self):
        # 상태 코드는 맞아도 오류 코드가 다르면(예: 대상 422가 VALIDATION_ERROR) k6 응답 검사만 잡는다
        c = Case().normal()
        c.check_fails = 2
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("k6 응답 검사 실패 2건", out)

    def test_missing_checks_in_summary_is_an_input_error(self):
        # 확인하지 못한 실패 수를 0으로 넘기지 않는다: checks가 없거나, 비었거나, fails가 없으면 입력 오류
        for name, checks in (("checks 없음", None), ("빈 checks", {}), ("fails 없음", {"passes": 100})):
            with self.subTest(name):
                c = Case().normal()
                if checks is None:
                    c.no_checks = True
                else:
                    c.checks = checks
                rc, out, err = c.run()
                self.assertEqual(rc, 1)
                self.assertIn("checks.fails가 없음", err)
                self.assertEqual(out, "")

    def test_missing_dropped_count_is_an_input_error(self):
        c = Case().normal()
        c.no_dropped = True
        rc, out, err = c.run()
        self.assertEqual(rc, 1)
        self.assertIn("dropped_iterations.count가 없음", err)

    def test_no_422_means_close_time_outside_target_window(self):
        c = Case().req("t1", 202).req("t2", 202)
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("202와 422가 모두 있지 않음", out)

    def test_401_makes_run_invalid_once(self):
        c = Case().normal().req("b2", 401, stage="bg")
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("401 1건 (토큰 만료)", out)
        self.assertNotIn("기대하지 않은 상태 코드", out)   # 401은 토큰 만료 이유로만 센다
        self.assertNotIn("202가 아닌 응답", out)

    def test_unexpected_target_status_makes_run_invalid(self):
        c = Case().normal().req("t5", 500)
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("대상 요청에 기대하지 않은 상태 코드 {500: 1}", out)

    def test_background_non_202_makes_run_invalid(self):
        # 배경 공모주는 마감되지 않으므로 202만 정상이다. 대상에서는 정상인 409, 422도 배경에서는 무효다
        for status, code in ((500, ""), (409, "INVALID_STATUS_TRANSITION"), (422, "SUBSCRIPTION_CLOSED")):
            with self.subTest(status=status):
                c = Case().normal().req("b2", status, stage="bg", code=code)
                rc, out, _ = c.run()
                self.assertEqual(rc, 3)
                self.assertIn(f"배경 요청에 202가 아닌 응답 {{{status}: 1}}", out)

    def test_target_409_and_422_are_expected(self):
        c = Case().normal().req("t5", 409, code="INVALID_STATUS_TRANSITION")
        rc, out, _ = c.run()
        self.assertEqual(rc, 0)
        self.assertIn("회차: 유효", out)

    def test_not_closed_makes_run_invalid_without_reading_20_22(self):
        c = Case().normal()
        c.closed = []
        c.sql = SQL_OK.replace("== 20. 마감 처리 뒤에 생긴 청약 (기대: 0행)", "== 20. (건너뜀: 아직 마감되지 않은 공모주)")
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("ipo.closed Outbox 없음", out)
        self.assertNotIn("verify.sql 20번", out)

    def test_skipped_check_on_closed_ipo_is_an_input_error(self):
        c = Case().normal()
        c.sql = SQL_OK.replace("== 22. 마감 때 확정 수와 지금 유효 청약 수가 다름 (기대: 0행)", "== 22. (건너뜀: 아직 마감되지 않은 공모주)")
        rc, out, err = c.run()
        self.assertEqual(rc, 1)
        self.assertIn("22번 결과를 읽지 못함", err)
        self.assertEqual(out, "")

    def test_missing_check_is_an_input_error(self):
        c = Case().normal()
        c.sql = SQL_OK.split("== 21.")[0]
        rc, _, err = c.run()
        self.assertEqual(rc, 1)
        self.assertIn("21번 결과를 읽지 못함", err)

    def test_reads_violation_row_counts(self):
        c = Case().normal()
        c.sql = SQL_OK.replace("(0 rows)\n\n== 21.", "(1 row)\n\n== 21.", 1)
        rc, out, _ = c.run()
        self.assertEqual(rc, 0)   # 측정값은 회차 유효성과 별개다 (위반이 나와도 유효한 측정)
        self.assertIn("(verify.sql 20번, 202를 받고 마감 집계에서 빠진 청약): 1", out)

    def test_counts_202_without_subscription_row(self):
        c = Case().normal()
        c.subs = ["t1"]
        rc, out, _ = c.run()
        self.assertEqual(rc, 0)
        self.assertIn("202를 받았는데 청약 행이 없음 (k6 로그 대조): 1", out)


if __name__ == "__main__":
    unittest.main()
