"""consumer_stats.py의 리스너 조회식(LISTENER_Q, LISTENER_SCRAPE_Q)을 promtool로 실제 실행하는 테스트

리스너 지표의 새 형식(error 태그)을 예전 형식(result 태그)으로 바꾸는 일은 Prometheus가 PromQL(label_replace)로 한다.
test_consumer_stats.py는 가짜 응답에 result 라벨을 바로 넣어서 이 변환식을 실행하지 않는다. 여기서는 consumer_stats.py의
조회식을 그대로 가져와 Prometheus 이미지의 promtool로 실행한다 (Docker 필요. 없으면 건너뜀)

사용: python loadtest/tests/test_consumer_stats_promql.py
"""
import importlib.util
import json
import os
import shutil
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("consumer_stats", os.path.join(HERE, "..", "verify", "consumer_stats.py"))
cs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cs)

# docker-compose.yml의 prometheus 이미지와 같은 버전
IMAGE = os.environ.get("PROMETHEUS_IMAGE", "prom/prometheus:v3.5.0")
METRIC = "spring_kafka_listener_seconds_count"


def series(labels, values):
    body = ",".join(f'{k}="{v}"' for k, v in labels.items())
    return {"series": f"{METRIC}{{{body}}}", "values": values}


def new(listener_id, error, values):
    """지금 형식 (관측): spring_kafka_listener_id, error=none/예외 이름"""
    return series({"spring_kafka_listener_id": listener_id, "error": error, "messaging_system": "kafka"}, values)


def old(name, result, values):
    """예전 형식 (Spring Kafka 타이머): name, result=success/failure"""
    return series({"name": name, "result": result, "exception": "none"}, values)


def expr_test(expr, eval_time, samples):
    return {"expr": expr, "eval_time": eval_time,
            "exp_samples": [{"labels": labels, "value": value} for labels, value in samples]}


def run_promtool(tests):
    """테스트 파일(JSON은 YAML로도 읽힌다)을 표준 입력으로 넘겨 promtool test rules를 실행한다"""
    doc = json.dumps({"rule_files": [], "evaluation_interval": "5s", "tests": tests})
    return subprocess.run(["docker", "run", "--rm", "-i", "--entrypoint", "sh", IMAGE, "-c",
                           "cat > /tmp/t.yml && promtool test rules /tmp/t.yml"],
                          input=doc, capture_output=True, text=True, timeout=300)


def docker_available():
    if shutil.which("docker") is None:
        return False
    return subprocess.run(["docker", "info"], capture_output=True).returncode == 0


@unittest.skipUnless(docker_available(), "Docker가 없어 건너뜀")
class ListenerQueryTest(unittest.TestCase):

    def assertPromtoolPasses(self, tests):
        r = run_promtool(tests)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_new_format_error_none_is_success_and_exception_is_failure(self):
        self.assertPromtoolPasses([{
            "interval": "5s",
            "input_series": [
                new("account-margin-reserve-0", "none", "10x12"),
                new("account-margin-reserve-1", "none", "5x12"),
                new("account-margin-reserve-0", "IllegalStateException", "2x12"),
                new("account-margin-reserve-1", "ListenerExecutionFailedException", "1x12"),
            ],
            "promql_expr_test": [expr_test(cs.LISTENER_Q, "60s", [('{result="success"}', 15), ('{result="failure"}', 3)])],
        }])

    def test_old_format_result_is_read_as_is(self):
        self.assertPromtoolPasses([{
            "interval": "5s",
            "input_series": [
                old("account-margin-reserve-0", "success", "7x12"),
                old("account-margin-reserve-1", "success", "3x12"),
                old("account-margin-reserve-0", "failure", "1x12"),
            ],
            "promql_expr_test": [expr_test(cs.LISTENER_Q, "60s", [('{result="success"}', 10), ('{result="failure"}', 1)])],
        }])

    def test_other_listeners_are_excluded(self):
        # 다른 리스너, 같은 접두사의 다른 ID(DLT 등)는 세지 않는다
        self.assertPromtoolPasses([{
            "interval": "5s",
            "input_series": [
                new("account-margin-reserve-0", "none", "4x12"),
                new("account-margin-void-0", "none", "100x12"),
                new("account-margin-reserve-dlt-0", "none", "100x12"),
                old("account-margin-void-0", "success", "100x12"),
                old("account-margin-reserve-dlt-0", "failure", "100x12"),
            ],
            "promql_expr_test": [expr_test(cs.LISTENER_Q, "60s", [('{result="success"}', 4)])],
        }])

    def test_scrape_time_is_last_sample_time_for_both_formats(self):
        # 마지막 수집이 50초인데 60초에 조회하면 평가 시각(60)이 아니라 수집 시각(50)이 나와야 수집이 끊긴 것을 알 수 있다
        tests = []
        for s in (new("account-margin-reserve-0", "none", "1x10"), old("account-margin-reserve-0", "success", "1x10")):
            tests.append({"interval": "5s", "input_series": [s],
                          "promql_expr_test": [expr_test(cs.LISTENER_SCRAPE_Q, "60s", [("{}", 50)])]})
        self.assertPromtoolPasses(tests)


if __name__ == "__main__":
    unittest.main()
