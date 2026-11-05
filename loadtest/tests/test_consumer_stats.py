"""consumer_stats.py 테스트 (Docker, DB, Prometheus 없이 돈다. 조회는 가짜로 바꾼다)

사용: python loadtest/tests/test_consumer_stats.py
"""
import csv
import importlib.util
import io
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("consumer_stats", os.path.join(HERE, "..", "verify", "consumer_stats.py"))
cs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cs)

IPO = "6f1c3b0e-1111-4a2b-8c3d-000000000001"
HEADER = "run_id,scenario,phase,repeat,target_rate,duration_s,expected_requests,ipo_ids,k6_start,k6_end,drain_s\n"
T0 = 1_700_000_000


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def lag_points(values, start=T0):
    return [(start + i * 5, float(v)) for i, v in enumerate(values)]


class Judge(unittest.TestCase):
    def test_transient_lag_is_not_rising(self):
        # 중간에 900까지 올랐다가 끝에 0으로 내려옴 → 기울기 음수
        self.assertFalse(cs.judge_run(lag_points([0, 300, 900, 400, 50, 0]))["rising"])

    def test_rising_trend(self):
        self.assertTrue(cs.judge_run(lag_points([0, 200, 400, 600, 800, 1000]))["rising"])

    def test_small_lag_with_rising_trend_is_rising(self):
        # 누적 랙이 적어도 증가 추세면 후보로 잡는다 (끝 랙 크기 기준 없음)
        self.assertTrue(cs.judge_run(lag_points([0, 1, 2, 3]))["rising"])

    def test_dip_at_end_still_rising(self):
        # 끝에서 잠깐 줄어도 전체 추세가 증가면 증가 (최고치 대비 끝 랙 기준 없음)
        self.assertTrue(cs.judge_run(lag_points([0, 200, 400, 600, 800, 300]))["rising"])

    def test_flat_lag_is_not_rising(self):
        self.assertFalse(cs.judge_run(lag_points([0, 0, 0, 0]))["rising"])
        self.assertFalse(cs.judge_run(lag_points([50, 50, 50]))["rising"])

    def test_no_samples_fails(self):
        with self.assertRaises(ValueError):
            cs.judge_run([])


def res(rate, processed, rising):
    return [dict(rate=rate, processed_load_rate=p, rising=r, success_rate=p, lag_max=0, lag_end=0, lag_slope=0, error_pct=0)
            for p, r in zip(processed, rising)]


class Stage(unittest.TestCase):
    ALL = (True, True, True)
    NONE = (False, False, False)

    def test_rising_and_plateau_is_suspect(self):
        # 목표 100 → 200으로 올렸는데 처리량이 이전 단계 최대(100)를 못 넘음
        s = cs.stage_rows(res(100, (95, 100, 98), self.NONE) + res(200, (100, 99, 101), self.ALL))
        self.assertFalse(s[0]["suspect"])
        self.assertTrue(s[1]["plateau"])
        self.assertTrue(s[1]["suspect"])

    def test_rising_without_plateau_is_not_suspect(self):
        # 랙은 늘지만 처리량은 목표를 따라 올라감
        s = cs.stage_rows(res(100, (95, 100, 98), self.NONE) + res(200, (190, 195, 200), self.ALL))
        self.assertTrue(s[1]["rising"])
        self.assertFalse(s[1]["plateau"])
        self.assertFalse(s[1]["suspect"])

    def test_plateau_without_lag_is_not_suspect(self):
        # k6가 목표 속도를 못 보내서 처리량이 그대로인 경우 (랙 없음)
        s = cs.stage_rows(res(100, (95, 100, 98), self.NONE) + res(200, (99, 100, 98), self.NONE))
        self.assertTrue(s[1]["plateau"])
        self.assertFalse(s[1]["suspect"])

    def test_first_stage_is_not_judged(self):
        # 비교할 이전 단계가 없으면 랙이 늘어도 병목 의심으로 표시하지 않음
        s = cs.stage_rows(res(100, (50, 50, 50), self.ALL))
        self.assertIsNone(s[0]["prev_max"])
        self.assertFalse(s[0]["plateau"])
        self.assertFalse(s[0]["suspect"])

    def test_plateau_boundary(self):
        # 중앙값 = 이전 단계 최대 → 정체, 조금이라도 넘으면 정체 아님
        self.assertTrue(cs.stage_rows(res(100, (90, 100, 95), self.NONE) + res(200, (100, 100, 100), self.ALL))[1]["plateau"])
        self.assertFalse(cs.stage_rows(res(100, (90, 100, 95), self.NONE) + res(200, (100.1, 100.1, 100.1), self.ALL))[1]["plateau"])

    def test_rising_needs_majority(self):
        s = cs.stage_rows(res(100, (95, 100, 98), self.NONE) + res(200, (100, 99, 101), (True, False, False)))
        self.assertEqual(s[1]["rises"], 1)
        self.assertFalse(s[1]["suspect"])


class Counters(unittest.TestCase):
    def test_increase(self):
        self.assertEqual(cs.increase([(T0, 10), (T0 + 5, 15), (T0 + 10, 40)], T0), 30)
        # 구간 중간에 처음 생긴 실패 시계열은 0에서 시작
        self.assertEqual(cs.increase([(T0 + 30, 2), (T0 + 35, 3)], T0), 3)
        self.assertEqual(cs.increase([], T0), 0)

    def test_increase_base_only_at_start(self):
        # 범위 조회 첫 평가는 start. start+5초에 처음 보인 실패 시계열은 구간 중 생긴 것이라 0에서 시작
        self.assertEqual(cs.increase([(T0 + 5, 2), (T0 + 10, 3)], T0), 3)

    def test_counter_reset_fails(self):
        # 측정 중 재시작으로 누적값이 줄면 증가분을 알 수 없어 실패
        with self.assertRaises(ValueError):
            cs.increase([(T0, 500), (T0 + 5, 20), (T0 + 10, 40)], T0)
        self.assertEqual(cs.increase([(T0, 500), (T0 + 5, 500), (T0 + 10, 540)], T0), 40)  # 같은 값은 리셋 아님

    def test_slope(self):
        self.assertAlmostEqual(cs.slope(lag_points([0, 10, 20])), 2.0)
        self.assertEqual(cs.slope(lag_points([5])), 0.0)


def prom_resp(values):
    return {"status": "success", "data": {"result": values}}


class Main(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.out = os.path.join(self.root, "b1")
        os.makedirs(self.out)
        rows = [f"b1-S1-100-r{k},S1,measure,{k},100,10,1000,{IPO},{T0 + k * 100},{T0 + k * 100 + 10},5\n" for k in (1, 2, 3)]
        write(os.path.join(self.out, "manifest.csv"), HEADER + "b1-S0-r1,S0,measure,,,,100,x,1,2,0\n" + "".join(rows))
        write(os.path.join(self.out, "env.txt"), 
            "git=abc branch=x commit_time=t\ndirty_files=0\n자원 ipo-account-service cpu=1 mem=1073741824 JAVA_TOOL_OPTIONS=-Xmx512m\n자원 ipo-api-gateway cpu=1 mem=1\n")

    def fake_db(self, run, container):
        return dict(success=1000, processed=1010, processed_load=900, rejected=5, reserved=1003, first=run["start"] + 1.0,
                    last=run["start"] + 11.0, buckets={run["start"] - run["start"] % 5: 400})

    def fake_prom(self, lag=(0, 0, 0, 0), samples=3, listener=True, scrape_after=3, partitions=6):
        def f(run, base):
            pts = lag_points(lag, run["start"])
            lis = {"success": [(run["start"], 0.0), (run["start"] + 15, 1010.0)]} if listener else {}
            if not lis:
                raise RuntimeError("리스너 시계열이 없음")
            return {"raw": run["run"]}, pts, lis, samples, run["w1"] + scrape_after, partitions
        return f

    def run_main(self, **kw):
        return cs.main(["--batch", "b1", "--results", self.root, "--partitions", "6"], db=kw.get("db", self.fake_db), prom=kw.get("prom", self.fake_prom()),
                       clock=lambda c, b: dict(db=0.25, prom=-0.5))

    def test_normal_outputs(self):
        self.assertEqual(self.run_main(), 0)
        md = read(os.path.join(self.out, "인출.md"))
        self.assertIn("의심 없음", md)
        self.assertIn("git=abc", md)
        self.assertIn("ipo-account-service", md)
        self.assertNotIn("ipo-api-gateway", md)
        self.assertNotIn("b1-S0-r1", md)  # S0은 제외
        # 성공 1000건 / (첫 생성 ~ 마지막 생성 10초) = 100건/초, 재발행 = 1003 - 1000, 기타 = 1010 - 1000 - 5 - 3
        self.assertIn("| b1-S1-100-r1 |", md)
        self.assertIn("| 1000 | 100.0 | 1010 | 5 | 3 | 2 |", md)
        raw = json.loads(read(os.path.join(self.out, "인출-prometheus.json")))
        self.assertEqual(sorted(raw), ["b1-S1-100-r1", "b1-S1-100-r2", "b1-S1-100-r3"])
        csv_text = read(os.path.join(self.out, "인출.csv"))
        self.assertIn("load", csv_text)
        self.assertIn("drain", csv_text)

    def test_suspect_from_second_stage(self):
        # 100, 200 단계 모두 고유 처리 90건/초 + 랙 증가 추세 → 200부터 병목 의심 (100은 첫 단계라 판정 안 함)
        rows = [f"b1-S1-{rate}-r{k},S1,measure,{k},{rate},10,1000,{IPO},{T0 + i * 100},{T0 + i * 100 + 10},5\n"
                for i, (rate, k) in enumerate((r, k) for r in (100, 200) for k in (1, 2, 3))]
        write(os.path.join(self.out, "manifest.csv"), HEADER + "".join(rows))
        self.assertEqual(self.run_main(prom=self.fake_prom(lag=(0, 200, 400, 600))), 0)
        md = read(os.path.join(self.out, "인출.md"))
        self.assertIn("목표 200건/초부터 Consumer 병목 의심", md)
        self.assertIn("| 100 | 3 |", md)

    def test_db_failure_is_failure_not_zero(self):
        def bad(run, container):
            raise RuntimeError("psql 실패")
        self.assertEqual(self.run_main(db=bad), 1)
        self.assertFalse(os.path.exists(os.path.join(self.out, "인출.md")))

    def test_missing_listener_series_fails(self):
        self.assertEqual(self.run_main(prom=self.fake_prom(listener=False)), 1)

    def test_missing_lag_series_fails(self):
        def call(path, params):
            return prom_resp([]) if "records_lag" in params.get("query", "") and path.endswith("query_range") else prom_resp([{"metric": {}, "values": [[T0, "1"]]}])
        run = dict(run="r", start=T0, end=T0 + 10, drain=5, w1=T0 + 15, stop=T0 + 16, exact=True)
        with self.assertRaises(RuntimeError):
            cs.query_prom(run, "http://x", get=lambda base, path, params: call(path, params))

    def test_listener_read_one_scrape_after_window(self):
        # 소진 확인 뒤 수집된 누적값까지 읽어야 마지막 처리분이 빠지지 않는다
        seen = {}
        def call(base, path, params):
            q = params.get("query", "")
            if "listener" in q and path.endswith("query_range"):
                seen["end"] = params["end"]
            if path.endswith("query_range"):
                return prom_resp([{"metric": {"result": "success"}, "values": [[T0, "1"]]}])
            if "listener" in q and "timestamp" not in q:
                return prom_resp([{"metric": {"result": "success"}, "value": [T0, "1"]}])
            return prom_resp([{"value": [T0, "3"]}])
        run = dict(run="r", start=T0, end=T0 + 10, drain=5, w1=T0 + 15, stop=T0 + 16, exact=True)
        cs.query_prom(run, "http://x", get=call)
        self.assertEqual(seen["end"], T0 + 16 + cs.STEP)  # 소진 확인 초(T0+15)가 끝난 뒤 + 수집 1회

    def test_drain_end_is_window_end(self):
        # manifest에 drain_end가 있으면 그 초 전체까지 집계하고, 없으면 k6 종료 + drain_s로 근사
        write(os.path.join(self.out, "manifest.csv"), HEADER.strip() + ",drain_end\n"
              + f"b1-S1-100-r1,S1,measure,1,100,10,1000,{IPO},{T0},{T0 + 10},5,{T0 + 17}\n")
        r = cs.read_s1_runs(os.path.join(self.out, "manifest.csv"))[0]
        self.assertEqual((r["w1"], r["stop"], r["exact"]), (T0 + 17, T0 + 18, True))
        write(os.path.join(self.out, "manifest.csv"), HEADER + f"b1-S1-100-r1,S1,measure,1,100,10,1000,{IPO},{T0},{T0 + 10},5\n")
        r = cs.read_s1_runs(os.path.join(self.out, "manifest.csv"))[0]
        self.assertEqual((r["w1"], r["stop"], r["exact"]), (T0 + 15, T0 + 16, False))

    def test_db_window_uses_drain_end_second(self):
        sqls = []
        orig = cs.psql
        def fake(sql, container):
            sqls.append(sql)
            return [["1"] * 7] if len(sqls) == 1 else []
        cs.psql = fake
        try:
            cs.query_db(dict(run="r", ipos=[IPO], start=T0, end=T0 + 10, drain=5, w1=T0 + 17, stop=T0 + 18, exact=True), "c")
        finally:
            cs.psql = orig
        self.assertIn(f"created_at < to_timestamp({T0 + 18})", sqls[0])
        self.assertIn(f"processed_at <= to_timestamp({T0 + 10})", sqls[0])  # 부하 중 기준은 k6 종료 그대로
        self.assertNotIn(f"to_timestamp({T0 + 15})", sqls[0])

    def test_basis_is_shown(self):
        write(os.path.join(self.out, "manifest.csv"), HEADER.strip() + ",drain_end\n"
              + f"b1-S1-100-r1,S1,measure,1,100,10,1000,{IPO},{T0},{T0 + 10},5,{T0 + 15}\n"
              + f"b1-S1-100-r2,S1,measure,2,100,10,1000,{IPO},{T0 + 100},{T0 + 110},5,\n")
        self.assertEqual(self.run_main(), 0)
        md = read(os.path.join(self.out, "인출.md"))
        self.assertIn("(drain_end)", md)
        self.assertIn("(근사: k6 종료 + drain_s)", md)

    def test_expected_samples_match_lag_query_range(self):
        # 랙 샘플은 k6 시작부터 소진 확인 초까지(19초) 조회 → 기대 3개. 집계 상한(+1초, 20초)으로 세면 4개라 누락으로 잘못 판정
        write(os.path.join(self.out, "manifest.csv"), HEADER.strip() + ",drain_end\n"
              + f"b1-S1-100-r1,S1,measure,1,100,10,1000,{IPO},{T0},{T0 + 10},5,{T0 + 19}\n")
        self.assertEqual(self.run_main(prom=self.fake_prom(samples=3)), 0)

    def test_bad_drain_end_fails(self):
        write(os.path.join(self.out, "manifest.csv"), HEADER.strip() + ",drain_end\n"
              + f"b1-S1-100-r1,S1,measure,1,100,10,1000,{IPO},{T0},{T0 + 10},5,x\n")
        with self.assertRaisesRegex(ValueError, "drain_end가 숫자가 아님"):
            cs.read_s1_runs(os.path.join(self.out, "manifest.csv"))

    def test_scrape_within_drain_second_fails(self):
        # 소진 확인 초 안의 수집은 확인 시각보다 이를 수 있어 실패
        self.assertEqual(self.run_main(prom=self.fake_prom(scrape_after=0.5)), 1)

    def test_listener_end_matches_scrape_check_time(self):
        # 시작 T0, stop T0+18 → 범위 평가는 T0+20이 마지막인데 수집 확인은 T0+23. 그 사이(T0+22.5) 수집분도 전체 수신에 들어가야 한다
        def call(base, path, params):
            q = params.get("query", "")
            if path.endswith("query_range"):
                if "listener" in q:
                    return prom_resp([{"metric": {"result": "success"}, "values": [[T0, "0"], [T0 + 20, "990"]]}])
                return prom_resp([{"metric": {}, "values": [[T0, "0"]]}])
            if "timestamp" in q:
                return prom_resp([{"value": [T0 + 23, str(T0 + 22.5)]}])
            if "listener" in q:
                self.assertEqual(params["time"], T0 + 23)
                return prom_resp([{"metric": {"result": "success"}, "value": [T0 + 23, "1000"]},
                                  {"metric": {"result": "failure"}, "value": [T0 + 23, "2"]}])
            return prom_resp([{"value": [T0, "3"]}])
        run = dict(run="r", start=T0, end=T0 + 10, drain=7, w1=T0 + 17, stop=T0 + 18, exact=True)
        _, _, listener, _, scrape, _ = cs.query_prom(run, "http://x", get=call)
        self.assertEqual(cs.increase(listener["success"], T0), 1000)
        self.assertEqual(cs.increase(listener["failure"], T0), 2)  # 범위 조회에 없던 실패도 보고
        self.assertGreaterEqual(scrape, run["stop"])

    def test_csv_keeps_listener_end_row(self):
        # 리스너 마지막 값(랙 평가 시각에 없는 시각)도 인출.csv에 남고, 그 행의 랙은 빈 값. 오류 건수 집계는 그대로
        def prom(run, base):
            t_end = run["stop"] + cs.STEP
            lis = {"success": [(run["start"], 0.0), (t_end, 1010.0)], "failure": [(t_end, 2.0)]}
            return {}, lag_points((0, 0, 0, 0), run["start"]), lis, 3, run["w1"] + 3, 6
        self.assertEqual(self.run_main(prom=prom), 0)
        rows = list(csv.reader(io.StringIO(read(os.path.join(self.out, "인출.csv")))))
        t_end = T0 + 100 + 16 + cs.STEP  # r1: 시작 T0+100, 소진 확인 T0+115, stop T0+116
        last = [r for r in rows if r[0] == "b1-S1-100-r1" and r[1] == str(t_end)]
        self.assertEqual(len(last), 1)
        self.assertEqual((last[0][3], last[0][4], last[0][5], last[0][6]), ("after", "", "1010", "2"))
        self.assertIn("| 1012 | 2 |", read(os.path.join(self.out, "인출.md")))  # 전체 수신 = 성공 1010 + 실패 2

    def test_time_basis_is_written(self):
        self.assertEqual(self.run_main(), 0)
        md = read(os.path.join(self.out, "인출.md"))
        self.assertIn("## 시간 기준", md)
        self.assertIn(cs.kst(T0 + 100 + 18), md)  # r1 리스너 최종 수집 = 소진 확인(T0+100+15) + 3초
        self.assertIn("DB 컨테이너 - 이 PC +0.25초", md)
        self.assertIn("한계: 두 지표의 시간 기준이 달라", md)

    def test_listener_scrape_before_window_end_fails(self):
        # 소진 확인 뒤 수집이 없으면 마지막 처리분이 빠졌을 수 있어 실패
        self.assertEqual(self.run_main(prom=self.fake_prom(scrape_after=0)), 1)
        self.assertIn("리스너 최종 수집 시각이 소진 확인", read(os.path.join(self.out, "인출.md")))

    def test_missing_partition_fails(self):
        # 구간 중 랙을 보고한 파티션이 6개보다 적으면 합계 랙이 작게 나왔을 수 있어 실패
        self.assertEqual(self.run_main(prom=self.fake_prom(partitions=5)), 1)
        self.assertIn("랙을 보고한 파티션 5개 < 기대 6개", read(os.path.join(self.out, "인출.md")))

    def test_counter_reset_in_main_fails(self):
        def prom(run, base):
            lis = {"success": [(run["start"], 500.0), (run["start"] + 5, 10.0), (run["stop"] + cs.STEP, 900.0)]}
            return {}, lag_points((0, 0, 0, 0), run["start"]), lis, 3, run["w1"] + 3, 6
        self.assertEqual(self.run_main(prom=prom), 1)
        self.assertFalse(os.path.exists(os.path.join(self.out, "인출.md")))

    def partition_call(self, counts, seen=None):
        # counts: 평가 시각 → 파티션 수 (없는 시각은 값 없음)
        def call(base, path, params):
            q = params.get("query", "")
            if "count by (partition)" in q:
                if seen is not None:
                    seen.update(path=path, start=params["start"], end=params["end"], step=params["step"])
                return prom_resp([{"metric": {}, "values": [[t, str(n)] for t, n in counts.items()]}])
            if path.endswith("query_range") and "records_lag" in q:
                t = params["start"]
                return prom_resp([{"metric": {}, "values": [[t + i * cs.STEP, "0"] for i in range(4)]}])
            if path.endswith("query_range"):
                return prom_resp([{"metric": {"result": "success"}, "values": [[params["start"], "1"]]}])
            if "listener" in q and "timestamp" not in q:
                return prom_resp([{"metric": {"result": "success"}, "value": [T0, "1"]}])
            return prom_resp([{"value": [T0, "3"]}])
        return call

    def test_partition_query_uses_lag_eval_times(self):
        # 시작이 5초 경계가 아니어도(T0+2) 랙 조회와 같은 시각(start부터 STEP 간격)으로 조회
        seen = {}
        start = T0 + 2
        run = dict(run="r", start=start, end=start + 10, drain=5, w1=start + 15, stop=start + 16, exact=True)
        *_, partitions = cs.query_prom(run, "http://x", get=self.partition_call({start + i * 5: 6 for i in range(4)}, seen))
        self.assertEqual(seen, dict(path="/api/v1/query_range", start=start, end=start + 15, step=cs.STEP))
        self.assertEqual(partitions, 6)

    def test_partition_missing_at_one_eval_time(self):
        # 랙 평가 시각 중 한 번이라도 파티션 값이 없거나 적으면 최솟값에 반영
        start = T0 + 2
        run = dict(run="r", start=start, end=start + 10, drain=5, w1=start + 15, stop=start + 16, exact=True)
        *_, missing = cs.query_prom(run, "http://x", get=self.partition_call({start: 6, start + 5: 6, start + 15: 6}))
        self.assertEqual(missing, 0)
        *_, fewer = cs.query_prom(run, "http://x", get=self.partition_call({start + i * 5: (5 if i == 2 else 6) for i in range(4)}))
        self.assertEqual(fewer, 5)

    def test_sample_gap_fails(self):
        # 측정 구간 15초 / 5초 = 기대 3개. 2개는 90% 미만 → 실패
        self.assertEqual(self.run_main(prom=self.fake_prom(samples=2)), 1)
        md = read(os.path.join(self.out, "인출.md"))
        self.assertIn("수집 누락", md)

    def test_no_s1_runs_fails(self):
        write(os.path.join(self.out, "manifest.csv"), HEADER + "b1-S0-r1,S0,measure,,,,100,x,1,2,0\n")
        self.assertEqual(self.run_main(), 1)

    def test_bad_ipo_id_fails(self):
        write(os.path.join(self.out, "manifest.csv"), HEADER + "r,S1,measure,1,100,10,1000,x';drop,1,2,0\n")
        self.assertEqual(self.run_main(), 1)


if __name__ == "__main__":
    unittest.main()
