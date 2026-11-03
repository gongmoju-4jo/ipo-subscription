"""docker_stats.py 테스트 (Docker 없이 돈다)

사용: python loadtest/tests/test_docker_stats.py
fixtures/docker-stats.raw의 JSON 줄은 Docker 29.7.2 `docker stats --no-stream --format '{{json .}}'`의 실제 출력 형식이다
"""
import csv
import importlib.util
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("docker_stats", os.path.join(HERE, "..", "verify", "docker_stats.py"))
ds = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ds)
FIX = os.path.join(HERE, "fixtures", "docker-stats.raw")
HEADER = "run_id,scenario,phase,repeat,target_rate,duration_s,expected_requests,ipo_ids,k6_start,k6_end,drain_s\n"


def line(t, name, cpu="10.00%", mem="100MiB / 1GiB", mem_pct="9.77%"):
    return f"{t}\t" + json.dumps({"BlockIO": "0B / 0B", "CPUPerc": cpu, "Container": "x", "ID": "x", "MemPerc": mem_pct,
                                  "MemUsage": mem, "Name": name, "NetIO": "1kB / 2kB", "PIDs": "10"}) + "\n"


class Files:
    def __init__(self, raw, manifest_rows, env=""):
        self.d = tempfile.mkdtemp()
        self.raw = os.path.join(self.d, "r.raw"); open(self.raw, "w", encoding="utf-8").write(raw)
        self.man = os.path.join(self.d, "m.csv"); open(self.man, "w", encoding="utf-8").write(HEADER + "".join(manifest_rows))
        self.env = os.path.join(self.d, "env.txt"); open(self.env, "w", encoding="utf-8").write(env)
        self.csv = os.path.join(self.d, "o.csv"); self.md = os.path.join(self.d, "o.md")

    def run(self):
        rc = ds.main([self.raw, "--manifest", self.man, "--env", self.env, "--csv", self.csv, "--md", self.md])
        md = open(self.md, encoding="utf-8").read() if os.path.exists(self.md) else ""
        return rc, md


def row(run, a, b):
    return f"{run},S1,measure,1,100,90,9000,id,{a},{b},3\n"


class Size(unittest.TestCase):
    def test_units(self):
        self.assertEqual(ds.size("1GiB"), 1024 ** 3)
        self.assertEqual(ds.size("731.3MiB"), 731.3 * 1024 ** 2)
        self.assertEqual(ds.size("965MB"), 965e6)
        self.assertEqual(ds.size("32.8kB"), 32800)
        self.assertEqual(ds.size("0B"), 0)
        self.assertEqual(ds.size("1e+03MB"), 1e9)  # 실제 docker stats 출력 (2026-11-03 정식측정-5)
        self.assertEqual(ds.size("1.5E+02kB"), 150000)

    def test_unknown_unit_fails(self):
        with self.assertRaises(ValueError):
            ds.size("12XB")
        with self.assertRaises(ValueError):
            ds.size("--")
        with self.assertRaises(ValueError):
            ds.size("1e+MB")
        with self.assertRaises(ValueError):
            ds.size("1.2.3MB")


class Real(unittest.TestCase):
    def test_real_docker_output(self):
        samples, errors = ds.parse_raw(FIX)
        self.assertEqual(errors, 1)
        names = {s["name"] for s in samples}
        self.assertIn("ipo-account-service", names)
        # 기대값은 같은 줄의 글자를 이 테스트에서 따로 풀어서 만든다 (docker_stats.py의 함수를 쓰지 않음)
        with open(FIX, encoding="utf-8") as h:
            raw = [json.loads(l.split("\t", 1)[1]) for l in h if "\tERROR\t" not in l and l.strip()]
        d = [x for x in raw if x["Name"] == "ipo-account-service"][0]
        used, limit = [p.strip() for p in d["MemUsage"].split("/")]
        self.assertTrue(used.endswith("MiB") and limit == "1GiB", d["MemUsage"])
        acc = [s for s in samples if s["name"] == "ipo-account-service"][0]
        self.assertAlmostEqual(acc["mem"], float(used[:-3]) * 1024 ** 2)
        self.assertEqual(acc["mem_limit"], 1024 ** 3)
        self.assertEqual(acc["cpu"], float(d["CPUPerc"].rstrip("%")))
        self.assertEqual(len(samples), len(raw))


class Summary(unittest.TestCase):
    def test_window_max_avg_and_limit(self):
        raw = (line(100, "ipo-postgres", "50.00%") + line(105, "ipo-postgres", "90.00%", "300MiB / 1GiB")
               + line(105, "k6-b-r1", "150.00%") + line(200, "ipo-postgres", "99.00%"))
        f = Files(raw, [row("b-r1", 100, 110)], env="자원 ipo-postgres cpu=1 mem=1073741824\n자원 k6 cpu=2 mem=1g\n")
        rc, md = f.run()
        self.assertEqual(rc, 0, md)
        pg = [l for l in md.splitlines() if l.startswith("| ipo-postgres")][0]
        self.assertIn("| 90.0% | 70.0% | 90% | 300MiB / 1024MiB |", pg)  # 200초 샘플(99%)은 구간 밖
        k6 = [l for l in md.splitlines() if l.startswith("| k6 ")][0]
        self.assertIn("| 150.0% | 150.0% | 75% |", k6)  # 상한 2코어 대비
        with open(f.csv, encoding="utf-8") as h:
            rows = list(csv.DictReader(h))
        self.assertEqual(len(rows), 4)
        self.assertEqual(rows[1]["mem_used_bytes"], str(300 * 1024 ** 2))

    def test_other_runs_k6_not_mixed(self):
        raw = line(100, "k6-b-r1", "10%") + line(100, "k6-b-r2", "190%")
        f = Files(raw, [row("b-r1", 100, 110)])
        rc, md = f.run()
        k6 = [l for l in md.splitlines() if l.startswith("| k6 ")][0]
        self.assertIn("| 10.0% |", k6)

    def test_run_without_samples_fails(self):
        f = Files(line(100, "ipo-kafka") + line(100, "k6-b-r1"), [row("b-r1", 100, 110), row("b-r2", 500, 510)])
        rc, md = f.run()
        self.assertEqual(rc, 1)
        self.assertIn("b-r2: k6 구간(500~510, 10초)에 샘플 없음", md)

    def test_short_run_missing_k6_is_warning(self):
        # S0처럼 k6 구간이 기록 간격(5초)의 2배보다 짧으면 실패가 아니라 참고로 남긴다 (정식측정-5에서 S0 회차 4개)
        f = Files(line(100, "ipo-kafka") + line(120, "ipo-kafka") + line(120, "k6-b-r2"),
                  [row("b-r1", 100, 104), row("b-r2", 118, 125), row("b-r3", 200, 203)], env="설정 STATS_INTERVAL=5 K6_CPUS=2\n")
        rc, md = f.run()
        self.assertEqual(rc, 0, md)
        self.assertIn("## 참고 (짧은 실행)", md)
        self.assertIn("b-r1: k6 컨테이너(k6-b-r1) 샘플 없음", md)
        self.assertIn("b-r3: k6 구간(200~203, 3초)에 샘플 없음", md)
        self.assertNotIn("## 문제", md)

    def test_long_run_missing_samples_still_fails(self):
        f = Files(line(100, "ipo-kafka"), [row("b-r1", 100, 110)], env="설정 STATS_INTERVAL=5\n")
        rc, md = f.run()
        self.assertEqual(rc, 1)
        self.assertIn("## 문제", md)

    def test_missing_k6_container_fails(self):
        f = Files(line(100, "ipo-kafka"), [row("b-r1", 100, 110)])
        rc, md = f.run()
        self.assertEqual(rc, 1)
        self.assertIn("k6 컨테이너(k6-b-r1) 샘플 없음", md)

    def test_no_samples_fails(self):
        f = Files("100\tERROR\tCannot connect\n", [])
        rc, md = f.run()
        self.assertEqual(rc, 1)
        self.assertIn("샘플이 하나도 없음", md)

    def test_malformed_line_fails(self):
        f = Files("abc\t{}\n", [row("b-r1", 1, 2)])
        rc, _ = f.run()
        self.assertEqual(rc, 1)

    def test_bad_manifest_time_fails(self):
        f = Files(line(100, "ipo-kafka"), ["b-r1,S1,measure,1,100,90,9000,id,,,3\n"])
        rc, _ = f.run()
        self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main()
