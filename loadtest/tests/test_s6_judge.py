"""s6_judge.py 판정 테스트 (DB 없이 돈다. psql 호출은 가짜로 바꾼다)
사용: python loadtest/tests/test_s6_judge.py   (s6_judge.py 경로를 첫 인자로 줄 수 있다)
"""
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

_path = sys.argv.pop(1) if len(sys.argv) > 1 and sys.argv[1].endswith(".py") else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "verify", "s6_judge.py")
spec = importlib.util.spec_from_file_location("s6_judge", _path)
s6 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s6)

IPO = "6f1c3b0e-1111-4a2b-8c3d-000000000001"
START = 1794215000000
KILL = START + 3000
RESTART = KILL + 10000
UP = RESTART + 25000


def empty(n, title, cols):
    sep = "-+-".join("-" * len(c) for c in cols)
    return f"== {n}. {title}\n {' | '.join(cols)} \n-{sep}-\n(0 rows)\n\n"


def sql_s6(total=4, confirmed=3, timeout=1, other=0, bad=(), void=None):
    """verify-s6.sql 출력 모양. bad에 든 번호(23~26)는 1행 위반으로 만든다"""
    out = ("== S6 요약-1. 상태별 건수 (CANCELLED는 취소 사유별)\n status | cancel_reason | reject_reason | count \n"
           "--------+---------------+---------------+-------\n CONFIRMED |  |  | 3\n(1 row)\n\n"
           "== S6 요약-2. 요청 수 = CONFIRMED + CANCELLED(TIMEOUT) (기대: other = 0)\n"
           " total | confirmed | cancelled_timeout | other \n-------+-----------+-------------------+-------\n"
           f"     {total} |         {confirmed} |                 {timeout} |     {other}\n(1 row)\n\n")
    if void is not None:
        out += ("== S6 요약-3. 반환 기록(margin_void_intent) 수\n void_intent | without_margin \n-------------+----------------\n"
                f"           {void[0]} |              {void[1]}\n(1 row)\n\n")
    for n in ("23", "24", "25", "26"):
        if n in bad:
            out += f"== {n}. (S6) 위반 예시\n subscription_id \n-----------------\n x1\n(1 row)\n\n"
        else:
            out += empty(n, "(S6) 위반 예시", ["subscription_id"])
    return out


def sql_base(confirmed=3, pending=3, bad=()):
    out = ""
    for n in ("2", "3", "5", "6", "17"):
        if n in bad:
            out += f"== {n}. 위반 예시\n x \n---\n a\n(1 row)\n\n"
        else:
            out += empty(n, "위반 예시", ["x"])
    out += (f"== 4. 확정 청약 수 = 인출된 증거금 수\n confirmed | pending_margin \n-----------+----------------\n"
            f"         {confirmed} |              {pending}\n(1 row)\n\n")
    return out


class Case:
    def __init__(self):
        self.tmp = tempfile.mkdtemp()
        self.rows = []
        self.dropped = 0
        self.check_fails = 0
        self.http_reqs = None
        self.summary_drop = None     # 요약에서 뺄 칸 이름
        self.expect = None
        self.events = {"k6_start_ms": START, "kill_ms": KILL, "restart_ms": RESTART, "up_ms": UP}
        self.lag = [(START + i * 2000, 0 if i < 1 else 80 - i * 3) for i in range(0, 20)]   # 종료(3초) 전에는 0, 장애 중에는 쌓임
        self.sql_s6 = sql_s6()
        self.sql_base = sql_base()
        self.before, self.after = 2, 2
        self.subs = None             # 청약 행의 request_id (없으면 202 요청 전부)
        self.dlt = None              # (시작 텍스트, 끝 텍스트). 주면 --dlt-start, --dlt-end를 넘긴다. 끝이 None이면 파일을 만들지 않는다
        self.account_log = None      # 계좌 서비스 로그 텍스트. 주면 --account-log를 넘긴다

    def req(self, rid, status, stage="s6"):
        self.rows.append({"s": "S6", "run": "x", "idx": len(self.rows), "requestId": rid, "status": status,
                          "subscriptionId": "", "code": "", "stage": stage})
        return self

    def normal(self):
        for i in range(4):
            self.req(f"r{i}", 202)
        return self

    def run(self, with_base=True, warmup=None):
        log = os.path.join(self.tmp, "requests.log")
        with open(log, "w", encoding="utf-8") as f:
            for r in self.rows:
                f.write('time="2026-11-11T09:00:00Z" level=info msg="' + json.dumps(r).replace('"', '\\"') + '" source=console\n')
        summ = os.path.join(self.tmp, "summary.json")
        m = {"dropped_iterations": {"count": self.dropped}, "http_reqs": {"count": len(self.rows) if self.http_reqs is None else self.http_reqs},
             "checks": {"passes": 10, "fails": self.check_fails, "value": 1}}
        if self.summary_drop:
            m.pop(self.summary_drop)
        with open(summ, "w", encoding="utf-8") as f:
            json.dump({"metrics": m}, f)
        ev = os.path.join(self.tmp, "events.txt")
        with open(ev, "w", encoding="utf-8") as f:
            f.write("".join(f"{k}={v}\n" for k, v in self.events.items()))
        lag = os.path.join(self.tmp, "lag.csv")
        with open(lag, "w", encoding="utf-8") as f:
            f.write("ts_ms,lag\n" + "".join(f"{t},{lg}\n" for t, lg in self.lag))
        s6f = os.path.join(self.tmp, "sql_result_s6.txt")
        with open(s6f, "w", encoding="utf-8") as f:
            f.write(self.sql_s6)
        basef = os.path.join(self.tmp, "sql_result.txt")
        with open(basef, "w", encoding="utf-8") as f:
            f.write(self.sql_base)
        subs = [r["requestId"] for r in self.rows if r["status"] == 202] if self.subs is None else self.subs

        def fake_query(sql):
            if "count(*) filter" in sql:
                return [[str(self.before), str(self.after)]]
            if "request_id" in sql:
                return [[s] for s in subs]
            raise AssertionError(sql)
        s6.query = fake_query
        args = ["--requests", log, "--summary", summ, "--events", ev, "--lag", lag, "--sql-s6", s6f, "--ipo", IPO,
                "--expect-requests", str(len(self.rows) if self.expect is None else self.expect)]
        if with_base:
            args += ["--sql-base", basef]
        if warmup is not None:
            args += ["--warmup", warmup]
        if self.dlt is not None:
            ds, de = os.path.join(self.tmp, "dlt-start.txt"), os.path.join(self.tmp, "dlt-end.txt")
            with open(ds, "w", encoding="utf-8") as f:
                f.write(self.dlt[0])
            if self.dlt[1] is not None:
                with open(de, "w", encoding="utf-8") as f:
                    f.write(self.dlt[1])
            args += ["--dlt-start", ds, "--dlt-end", de]
        if self.account_log is not None:
            al = os.path.join(self.tmp, "account-service.log")
            if self.account_log != "":
                with open(al, "w", encoding="utf-8") as f:
                    f.write(self.account_log)
            args += ["--account-log", al]
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = s6.main(args)
        return rc, out.getvalue(), err.getvalue()


DLT0 = "subscription.requested.DLT 0\nmargin.reserve-requested.DLT 0\n"


class S6JudgeTest(unittest.TestCase):
    def test_dlt_no_growth(self):
        c = Case().normal()
        c.dlt = (DLT0, DLT0)
        rc, out, _ = c.run()
        self.assertEqual(rc, 0, out)
        self.assertIn("DLT 증가: 0건 (기록용)", out)
        self.assertNotIn("DLT로 간 메시지", out)

    def test_dlt_growth_is_warning_not_violation(self):
        c = Case().normal()
        c.dlt = (DLT0, "subscription.requested.DLT 0\nmargin.reserve-requested.DLT 3\n")
        rc, out, _ = c.run()
        self.assertEqual(rc, 0, out)     # 기록용이라 판정은 그대로
        self.assertIn("DLT 증가: 3건 (margin.reserve-requested.DLT +3) (기록용)", out)
        self.assertIn("DLT로 간 메시지가 3건 있음", out)

    def test_dlt_empty_end_file_is_not_recorded(self):
        c = Case().normal()
        c.dlt = (DLT0, "")
        rc, out, _ = c.run()
        self.assertEqual(rc, 0, out)
        self.assertIn("DLT 증가: 기록하지 못함", out)

    def test_dlt_missing_end_file_is_not_recorded(self):
        c = Case().normal()
        c.dlt = (DLT0, None)
        rc, out, _ = c.run()
        self.assertEqual(rc, 0, out)
        self.assertIn("DLT 증가: 기록하지 못함", out)

    def test_dlt_decrease_is_not_recorded(self):
        c = Case().normal()
        c.dlt = ("subscription.requested.DLT 5\n", "subscription.requested.DLT 2\n")
        rc, out, _ = c.run()
        self.assertIn("DLT 증가: 기록하지 못함 (DLT 건수가 줄어듦", out)

    def test_dlt_not_asked_has_no_line(self):
        rc, out, _ = Case().normal().run()
        self.assertNotIn("DLT 증가", out)

    def test_account_log_counts_duplicate_skips(self):
        c = Case().normal()
        c.account_log = ("INFO ... [증거금 인출] 인출 저장. eventId=a\n"
                         "INFO ... [증거금 인출] 중복 수신, 건너뜀. eventId=a, subscriptionId=s1\n"
                         "INFO ... [증거금 인출] 중복 수신, 건너뜀. eventId=b, subscriptionId=s2\n"
                         "INFO ... [증거금 정산] 중복 수신, 건너뜀. eventId=c, subscriptionId=s3\n")
        rc, out, _ = c.run()
        self.assertEqual(rc, 0, out)
        self.assertIn("processed_event가 같은 eventId를 걸러낸 인출 요청: 2건", out)

    def test_account_log_missing_is_not_recorded(self):
        c = Case().normal()
        c.account_log = ""
        rc, out, _ = c.run()
        self.assertEqual(rc, 0, out)
        self.assertIn("걸러낸 인출 요청: 기록하지 못함", out)

    def test_valid_no_violation(self):
        rc, out, _ = Case().normal().run()
        self.assertEqual(rc, 0, out)
        self.assertIn("회차: 유효", out)
        self.assertIn("결과: 위반 없음", out)
        self.assertIn("종료 → UP(healthy): 35.0초", out)

    def test_warmup_line_after_result(self):
        rc, out, _ = Case().normal().run(warmup="함 (200초, 200건/초)")
        self.assertEqual(rc, 0, out)
        lines = out.splitlines()
        self.assertEqual(lines[3], "워밍업: 함 (200초, 200건/초)")
        self.assertTrue(lines[2].startswith("결과:"))

    def test_no_warmup_line_when_not_given(self):
        rc, out, _ = Case().normal().run()
        self.assertNotIn("워밍업", out)

    def test_void_intent_line(self):
        c = Case().normal()
        c.sql_s6 = sql_s6(void=(2, 1))
        rc, out, _ = c.run()
        self.assertEqual(rc, 0, out)
        self.assertIn("선행 반환 기록(margin_void_intent): 2건, 그중 증거금 없이 늦은 인출이 막힌 건 1건", out)

    def test_no_void_line_for_old_sql_result(self):
        rc, out, _ = Case().normal().run()
        self.assertNotIn("margin_void_intent", out)

    def test_warning_when_long_outage_without_timeout(self):
        c = Case().normal()
        c.events["up_ms"] = KILL + 95000
        c.sql_s6 = sql_s6(total=4, confirmed=4, timeout=0)
        rc, out, _ = c.run()
        self.assertEqual(rc, 0, out)
        self.assertIn("## 경고", out)
        self.assertIn("95.0초", out)
        self.assertIn("시간 초과 보상 경로를 검증하지 못함", out)

    def test_no_warning_when_long_outage_with_timeout(self):
        c = Case().normal()
        c.events["up_ms"] = KILL + 95000
        rc, out, _ = c.run()
        self.assertNotIn("## 경고", out)

    def test_no_warning_for_short_outage_without_timeout(self):
        c = Case().normal()
        c.sql_s6 = sql_s6(total=4, confirmed=4, timeout=0)
        rc, out, _ = c.run()
        self.assertEqual(rc, 0, out)
        self.assertNotIn("## 경고", out)

    def test_valid_without_base_sql(self):
        rc, out, _ = Case().normal().run(with_base=False)
        self.assertEqual(rc, 0, out)

    def test_dropped_is_invalid(self):
        c = Case().normal()
        c.dropped = 3
        c.expect = 7
        rc, out, _ = c.run()
        self.assertEqual(rc, 3, out)
        self.assertIn("밀린 요청 3건", out)

    def test_check_fail_is_invalid(self):
        c = Case().normal()
        c.check_fails = 1
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("k6 응답 검사 실패 1건", out)

    def test_non_202_is_invalid(self):
        c = Case().normal().req("r9", 503)
        rc, out, _ = c.run()
        self.assertEqual(rc, 3, out)
        self.assertIn("202가 아닌 응답", out)

    def test_401_is_invalid(self):
        rc, out, _ = Case().normal().req("r9", 401).run()
        self.assertEqual(rc, 3)
        self.assertIn("401 1건", out)

    def test_kill_before_any_request_is_invalid(self):
        c = Case().normal()
        c.before = 0
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("종료 전에 접수된 청약이 없음", out)

    def test_kill_after_load_is_invalid(self):
        c = Case().normal()
        c.after = 0
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("종료 이후에 접수된 청약이 없음", out)

    def test_no_lag_during_outage_is_invalid(self):
        c = Case().normal()
        c.lag = [(START + i * 2000, 0) for i in range(20)]
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("랙이 쌓이지 않음", out)

    def test_no_lag_sample_in_outage_window_is_invalid(self):
        c = Case().normal()
        c.lag = [(START, 0), (START + 1000, 5)]    # 종료(3초) 전 샘플만 있음
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("장애 구간에 랙 샘플이 없음", out)

    def test_violation_23(self):
        c = Case().normal()
        c.sql_s6 = sql_s6(bad=("23",))
        rc, out, _ = c.run()
        self.assertEqual(rc, 4, out)
        self.assertIn("verify-s6.sql 23번 위반 1건", out)

    def test_violation_26_and_other(self):
        c = Case().normal()
        c.sql_s6 = sql_s6(total=4, confirmed=2, timeout=1, other=1, bad=("26",))
        rc, out, _ = c.run()
        self.assertEqual(rc, 4)
        self.assertIn("26번 위반 1건", out)
        self.assertIn("그 밖 1건", out)

    def test_violation_row_count_differs_from_requests(self):
        c = Case().normal()
        c.sql_s6 = sql_s6(total=3, confirmed=2, timeout=1)
        rc, out, _ = c.run()
        self.assertEqual(rc, 4)
        self.assertIn("청약 행 수 3건이 요청 수 4건과 다름", out)

    def test_violation_202_without_row(self):
        c = Case().normal()
        c.subs = ["r0", "r1", "r2"]
        rc, out, _ = c.run()
        self.assertEqual(rc, 4)
        self.assertIn("202를 받았는데 청약 행이 없음 1건", out)

    def test_violation_base_17_and_4(self):
        c = Case().normal()
        c.sql_base = sql_base(confirmed=3, pending=4, bad=("17",))
        rc, out, _ = c.run()
        self.assertEqual(rc, 4)
        self.assertIn("verify.sql 17번 위반 1건", out)
        self.assertIn("4번: 확정 청약 3건, 인출된 증거금 4건", out)

    def test_invalid_overrides_violation_exit_code(self):
        c = Case().normal()
        c.dropped = 1
        c.expect = 5
        c.sql_s6 = sql_s6(bad=("23",))
        rc, out, _ = c.run()
        self.assertEqual(rc, 3)
        self.assertIn("회차가 무효라 참고용", out)

    def test_request_count_mismatch_is_input_error(self):
        c = Case().normal()
        c.http_reqs = 5
        rc, _, err = c.run()
        self.assertEqual(rc, 1)
        self.assertIn("요청 수가 맞지 않음", err)

    def test_unknown_stage_is_input_error(self):
        c = Case().normal().req("r9", 202, stage="bg")
        rc, _, err = c.run()
        self.assertEqual(rc, 1)
        self.assertIn("stage가 s6이 아닌", err)

    def test_summary_without_dropped_is_input_error(self):
        c = Case().normal()
        c.summary_drop = "dropped_iterations"
        rc, _, err = c.run()
        self.assertEqual(rc, 1)
        self.assertIn("dropped_iterations", err)

    def test_missing_event_key_is_input_error(self):
        c = Case().normal()
        del c.events["up_ms"]
        rc, _, err = c.run()
        self.assertEqual(rc, 1)
        self.assertIn("up_ms가 없음", err)

    def test_event_order_is_input_error(self):
        c = Case().normal()
        c.events["restart_ms"] = KILL - 1
        rc, _, err = c.run()
        self.assertEqual(rc, 1)
        self.assertIn("시각 순서", err)

    def test_missing_s6_section_is_input_error(self):
        c = Case().normal()
        c.sql_s6 = sql_s6().replace("== 25.", "== 99.")
        rc, _, err = c.run()
        self.assertEqual(rc, 1)
        self.assertIn("25번 결과를 읽지 못함", err)

    def test_sql_sections_parses_real_psql_shape(self):
        p = os.path.join(tempfile.mkdtemp(), "x.txt")
        with open(p, "w", encoding="utf-8") as f:
            f.write(sql_s6())
        secs = s6.sql_sections(p)
        self.assertEqual(secs["23"]["n"], 0)
        self.assertEqual(s6.first_row(secs["S6 요약-2"]), ["4", "3", "1", "0"])


if __name__ == "__main__":
    unittest.main()
