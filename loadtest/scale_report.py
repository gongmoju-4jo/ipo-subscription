#!/usr/bin/env python3
"""scale_verify.sh 결과로 수평 확장 검증 요약.md를 생성한다 (표준 라이브러리만, Python 3.8 이상). 저장소 루트에서 실행한다.

사용: python3 loadtest/scale_report.py [--root loadtest/results/scale-verify]
판정 기준은 docs 검증 계획 0절과 같다. 숫자는 모두 원본 파일에서 계산하고, 계산식을 요약.md에 같이 적는다.
"""
import argparse
import json
import os
import re
import statistics
from datetime import datetime, timedelta

# V1 쿼리 → 스케줄러. 상수가 원문으로 저장되거나($1로 변환되거나) 둘 다 매칭되도록 구조로 구분한다 (11/17 실측: 네이티브 쿼리의 'PENDING'은 원문으로 저장됨).
# 한 번 실행할 때 대표 쿼리를 1번 실행하므로, 같은 그룹에 쿼리가 여러 개면 가장 큰 calls를 실행 횟수로 사용한다
RULES = [
    ("마감", "subscription_app", lambda s: re.search(r"subscription_end_at\s*<=", s)),
    ("시간 초과", "subscription_app", lambda s: re.search(r"not exists", s) and re.search(r"\bs\.status\s*=\s*(\$|')", s)),
    ("반환 재요청", "subscription_app", lambda s: re.search(r"not exists", s) and re.search(r"\bs\.status\s+in\s*\(", s)),
    ("원장 대사", "account_app", lambda s: re.search(r"ledger_entry", s) and not re.search(r"\boutbox\b", s)),
    ("Outbox 발행기 (계좌)", "account_app", lambda s: is_outbox_poll(s)),
    ("Outbox 발행기 (청약)", "subscription_app", lambda s: is_outbox_poll(s)),
    ("Outbox 발행기 (배정)", "allocation_app", lambda s: is_outbox_poll(s)),
]
# 기대값: (적용 전, 적용 후 하한, 적용 후 상한). None은 판정하지 않음 (참고값)
# 원장 대사는 측정용 설정(cron 10초, LEDGER_RECONCILIATION_LOCK_AT_LEAST_FOR=PT5S) 기준: 10초 회차마다 한 인스턴스만 실행해 1분 6회.
# lockAtLeastFor를 운영값(5분)으로 두면 인스턴스 1개여도 1분 0-1회라 중복 실행 제거와 구분할 수 없음 (#167 리뷰)
EXPECT = {
    "마감": (24, 10, 14),
    "시간 초과": (24, 10, 14),
    "반환 재요청": (24, 10, 14),
    "원장 대사": (12, 5, 7),
}
# 기대값은 60초 측정 기준. 다른 측정 시간은 판정하지 않는다
V1_SECONDS = 60
# 락별 lockAtMostFor (설정 기본값). ShedLock은 획득 시 lock_until = 획득 시각 + lockAtMostFor로 저장하고, 해제 시 그 이전 시각으로 변경한다.
# 그래서 유지 시간(lock_until - locked_at)이 이 값과 동일하면 실행 중 값, 미만이면 해제 값으로 구분한다. 설정을 변경하면 이 값도 같이 변경한다
# 출처: application.yml (마감 ipo.close, 원장 대사 ledger.reconciliation), D-33 (Outbox), PROJECT_SETUP 5장 추천값 (시간 초과, 반환 재요청)
LOCK_AT_MOST_FOR = {
    "IpoCloseScheduler.closeDueIpos": timedelta(minutes=1),
    "PendingTimeoutScheduler.handleTimedOutPending": timedelta(minutes=1),
    "VoidRetryScheduler.retryStaleVoid": timedelta(minutes=1),
    "LedgerReconciliationScheduler.reconcile": timedelta(minutes=30),
    "OutboxPublisher.publishPending": timedelta(minutes=1),
}
# 성능 공식 합격 판정(B): 팀 G1 목표 (LOADTEST 8-2, judge.py). S0 처리량 목표(400건/초)는 S0가 시작 간격에 좌우돼 제외
G1_AVG_MS = 250
G1_P95_MS = 500
# S0 요청 수. 청약 행이 이 수와 다르면 요청이 전부 실행되지 않은 회차로 판정한다
S0_REQUESTS = 1000


def is_outbox_poll(s):
    return (re.match(r"\s*select", s) and re.search(r"\bfrom\s+(\w+\.)?outbox\b", s)
            and re.search(r"published_at\s+is\s+null", s) and not re.search(r"not exists", s))


def read(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, encoding="utf-8") as f:
        return f.read()


def kv(path):
    out = {}
    for line in (read(path, "") or "").splitlines():
        if "=" in line and not line.startswith("=="):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def v1(phase_dir):
    raw = read(os.path.join(phase_dir, "v1-raw.txt"))
    if raw is None:
        return None
    groups = {name: [] for name, _, _ in RULES}
    for line in raw.splitlines():
        parts = line.split("|", 2)
        if len(parts) != 3:
            continue
        role, calls, query = parts[0], int(parts[1]), parts[2].lower()
        for name, r, match in RULES:
            if role == r and match(query):
                groups[name].append((calls, parts[2][:120]))
                break
    return {name: (max(c for c, _ in rows) if rows else 0, rows) for name, rows in groups.items()}


def v2(after_dir):
    raw = read(os.path.join(after_dir, "v2-shedlock.txt"))
    # v2-seconds.txt는 수집이 완료된 후에만 기록되는 완료 표시
    if raw is None or not os.path.exists(os.path.join(after_dir, "v2-seconds.txt")):
        return None
    # 락 이름 → 획득 시각(locked_at) → (획득 인스턴스, 조회된 lock_until 목록)
    locks = {}
    for line in raw.splitlines():
        p = line.split("|")
        if len(p) != 5:
            continue
        acq = locks.setdefault((p[0], p[1]), {})
        by, untils = acq.setdefault(ts(p[3]), (p[2], set()))
        untils.add(ts(p[4]))
    result = {}
    for key, acq in locks.items():
        at_most = LOCK_AT_MOST_FOR.get(key[1])
        ends = {}
        for at, (_, untils) in acq.items():
            # 설정값을 알 수 없는 락은 해제 값을 구분할 근거가 없어 모든 획득을 해제 미관측으로 처리한다
            released = [u for u in untils if at_most is not None and u - at < at_most - timedelta(milliseconds=1)]
            ends[at] = min(released) if released else None
        rows = sorted(acq)
        overlaps = sum(1 for a, b in zip(rows, rows[1:]) if ends[a] is not None and b < ends[a])
        unknown = sum(1 for at in rows if ends[at] is None)
        result[key] = (len(rows), len({acq[at][0] for at in rows}), overlaps, unknown)
    return result


def ts(value):
    # TIMESTAMP 문자열. 소수 자리 수가 달라도 읽히게 6자리로 통일한다 (Python 3.9, 3.10의 fromisoformat 제약)
    v = value.strip()
    if "." in v:
        head, frac = v.split(".", 1)
        v = head + "." + (frac + "000000")[:6]
    return datetime.fromisoformat(v)


def offsets(path):
    out = {}
    for line in (read(path, "") or "").splitlines():
        p = line.split()
        if len(p) == 2:
            out[p[0]] = int(p[1])
    return out


def v3(phase_dir):
    runs = []
    for n in range(1, 10):
        p = os.path.join(phase_dir, f"v3-r{n}")
        if not os.path.exists(p + "-meta.txt"):
            if os.path.exists(p + "-meta.tmp"):
                runs.append({"n": n, "incomplete": True})
            continue
        before, after = offsets(p + "-offsets-before.txt"), offsets(p + "-offsets-after.txt")
        outbox = {}
        for line in (read(p + "-outbox.txt", "") or "").splitlines():
            t, c = line.split("|")
            outbox[t] = int(c)
        topics = []
        for t in sorted(set(before) | set(after)):
            sent = after.get(t, 0) - before.get(t, 0)
            rows = outbox.get(t, 0)
            if sent or rows:
                topics.append((t, sent, rows, sent - rows))
        missing = [f for f in ("-offsets-before.txt", "-offsets-after.txt", "-outbox.txt", "-integrity.txt", "-status.txt")
                   if not os.path.exists(p + f)]
        integ = {}
        for line in (read(p + "-integrity.txt", "") or "").splitlines():
            k, v = line.split("|")
            integ[k] = int(v)
        dup_log = int((read(p + "-dup-log.txt", "0") or "0").strip() or 0)
        meta = kv(p + "-meta.txt")
        status_rows = [line.split("|") for line in (read(p + "-status.txt", "") or "").strip().splitlines() if "|" in line]
        status = ", ".join(f"{k} {v}" for k, v in status_rows)
        total = sum(int(v) for _, v in status_rows)
        # 무효 회차: 필수 파일 누락, 완료 표시 없음, k6 실행 실패(0, 99 외 종료 코드), 청약 행 수가 요청 수와 다름
        invalid = []
        if missing:
            invalid.append(f"파일 누락 {missing}")
        if meta.get("complete") != "1":
            invalid.append("완료 표시 없음")
        if meta.get("k6_exit") not in ("0", "99"):
            invalid.append(f"k6 종료 코드 {meta.get('k6_exit')}")
        # 키가 없으면 정상 종료 (verify.sh가 실패할 때만 기록함)
        if meta.get("verify_sh_exit", "0") != "0":
            invalid.append(f"정합성 수집 종료 코드 {meta.get('verify_sh_exit')}")
        if total != S0_REQUESTS:
            invalid.append(f"청약 행 {total}건 (기대 {S0_REQUESTS})")
        runs.append({"n": n, "incomplete": False, "invalid": invalid, "topics": topics,
                     "perf": perf(meta.get("run", ""), phase_dir, n),
                     # 토픽끼리 상쇄되지 않게 양수(중복 발행)와 음수(미발행 또는 측정 불일치)를 따로 합산
                     "dup": sum(d for *_, d in topics if d > 0), "short": sum(-d for *_, d in topics if d < 0),
                     "integ": integ,
                     # 위반끼리 상쇄되지 않게 항목마다 절댓값 (v04는 차이라 음수 가능)
                     "violations": sum(abs(v) for v in integ.values()), "dup_log": dup_log, "meta": meta, "status": status})
    return runs


# k6 회차 원본 폴더 (run-k6.sh, report.py가 저장하는 위치). 저장소 루트 기준
K6_RESULTS = os.path.join("loadtest", "results")
PERF_JOBS = ("api-gateway", "subscription-service", "account-service")


def prom_by_job(prom, key):
    # report.py의 prometheus.json: {지표: {"result": [{"metric": {"job": ...}, "value": [시각, "값"]}]}}
    out = {}
    for r in (prom.get(key) or {}).get("result", []):
        try:
            out[r["metric"].get("job")] = float(r["value"][1])
        except (KeyError, ValueError, IndexError):
            continue
    return out


def perf(run_name, phase_dir, n):
    # 회차의 성능 값. 원본이 없으면 None (판정하지 않음)
    d = os.path.join(K6_RESULTS, run_name)
    meta = kv(os.path.join(phase_dir, f"v3-r{n}-meta.txt"))
    # 회차 보고서(report.py)가 실패한 회차는 성능 값만 제외한다 (V3 중복 발행, 정합성은 유지)
    if meta.get("report_exit", "0") != "0":
        return None
    try:
        with open(os.path.join(d, "summary.json"), encoding="utf-8") as f:
            m = json.load(f)["metrics"]
        success = 1 - float(m["http_req_failed"]["value"])
        rate = float(m["http_reqs"]["rate"])
        p95 = float(m["http_req_duration"]["p(95)"])
        avg = float(m["http_req_duration"]["avg"])
        with open(os.path.join(d, "prometheus.json"), encoding="utf-8") as f:
            prom = json.load(f)
    except (OSError, ValueError, KeyError, TypeError):
        return None
    confirm = (read(os.path.join(phase_dir, f"v3-r{n}-confirm-p95.txt"), "") or "").strip()
    # 잔여 처리 확인: 회차 시작 시 Consumer 랙 0이고, 이전 회차의 마지막 처리 시각이 성능 집계 구간 시작보다 이전이어야 함
    residual_ok = None
    m_win = re.search(r"서버 지표 구간 \| (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)", read(os.path.join(d, "report.md"), "") or "")
    # 빈 값(이전 처리 없음 또는 조회 실패, 구분 불가)과 변환 실패는 확인 안 함으로 처리한다
    if "start_lag" in meta and meta.get("prev_activity_end") and m_win:
        try:
            win_start_utc = datetime.strptime(m_win.group(1), "%Y-%m-%d %H:%M:%S") - timedelta(hours=9)
            prev_end = ts(meta["prev_activity_end"].rstrip("Z").replace("T", " "))
            residual_ok = meta["start_lag"] == "0" and prev_end < win_start_utc
        except ValueError:
            residual_ok = None
    return {
        "residual_ok": residual_ok,
        "success": success,
        "rate": rate,
        "p95": p95,
        "avg": avg,
        "confirm_p95": float(confirm) if confirm else None,
        "pending": prom_by_job(prom, "hikari_pending_max"),
        "active": prom_by_job(prom, "hikari_active_max"),
        "cpu": prom_by_job(prom, "cpu_max_pct"),
    }


def verdict(ok):
    return "✅ 통과" if ok else "❌ 불합격"


def v2_verdict(acquired, overlaps, unknown):
    # 모든 획득이 해제 미관측이면 비교할 종료 시각이 없어서 중복 0건이 아니라 판정 불가로 표시한다
    if acquired == unknown:
        return "⚠️ 판정 불가 (모든 획득 해제 미관측)"
    return verdict(overlaps == 0)


def fmt(v, digits=1, suffix=""):
    return "-" if v is None else f"{v:,.{digits}f}{suffix}"


def perf_section(w, phases):
    w("\n# B. 동일 커밋 인스턴스 수 비교 (성능)\n")
    w("> 같은 커밋에서 인스턴스 수만 변경한 비교. A의 적용 전후 비교와 함께 해석하지 않음\n")
    w("## 4. 성능 비교 (같은 커밋, 인스턴스 2개 → 1개 → 2개, S0 각 3회)\n")
    if not any(runs for *_, runs in phases):
        w("- 측정 안 함\n")
        return {}
    # 같은 커밋 판정은 회차별 커밋(meta.txt)으로만 한다. 단계 env.txt는 처음 한 번만 기록돼 오래된 값일 수 있어 대체하지 않고,
    # 회차 커밋이 없는 회차(이전 스크립트 결과)가 포함되면 비교 무효로 표시한다
    commits = {r["meta"].get("commit") or None for _, _, _, runs in phases for r in runs if not r["incomplete"]}
    commit_ok = len(commits) == 1 and None not in commits
    if None in commits:
        w("- ⚠️ **비교 무효**: 회차 커밋이 없는 회차가 있음 (이전 스크립트 결과). 회차별 커밋이 기록된 결과로만 비교 가능\n")
    elif not commit_ok:
        w(f"- ⚠️ **비교 무효**: 회차별 커밋이 다름 {sorted(c[:7] for c in commits)}. 같은 커밋에서 인스턴스 수만 달라야 비교 가능\n")
    w("| 단계 | 회차 | 성공률 | S0 완료 속도 (건/초) | p95 (ms) | 접수 → 확정 p95 (초) | Hikari 대기 최대 (청약 / 계좌) | 인스턴스별 CPU 최대값 중 최댓값 (게이트웨이 / 청약 / 계좌, %) | 정합성 | 판정 |")
    w("|---|---|---|---|---|---|---|---|---|---|")
    medians = []
    for label, _, _, runs in phases:
        valid = []
        for r in runs:
            if r["incomplete"]:
                w(f"| {label} | r{r['n']} | - | - | - | - | - | - | - | ⚠️ 미완료 |")
                continue
            p = r["perf"]
            # 적용 후 커밋 단계(2개, 1개, 2개 다시)는 모두 락이 적용된 상태라 중복 발행도 0이어야 정상
            integ_ok = all(v == 0 for v in r["integ"].values()) and r["short"] == 0 and r["dup"] == 0
            if r["invalid"] or p is None:
                note = "⚠️ 무효: " + ", ".join(r["invalid"] or ["성능 원본 없음"])
            elif not integ_ok:
                # 정합성 실패 회차는 성능 결과를 판정하지 않는다
                note = "❌ 정합성 실패 (성능 판정 안 함)"
            elif p["residual_ok"] is None:
                note = "⚠️ 잔여 처리 확인 안 함 (성능 제외)"
            elif not p["residual_ok"]:
                note = "⚠️ 이전 회차 잔여 처리 포함 가능 (성능 제외)"
            else:
                note = "✅ 유효"
                valid.append(p)
            if p is None:
                w(f"| {label} | r{r['n']} | - | - | - | - | - | - | {'통과' if integ_ok else '실패'} | {note} |")
                continue
            w(f"| {label} | r{r['n']} | {fmt(100 * p['success'], 2, '%')} | {fmt(p['rate'])} | {fmt(p['p95'])} | {fmt(p['confirm_p95'], 2)} | "
              f"{fmt(p['pending'].get('subscription-service'), 0)} / {fmt(p['pending'].get('account-service'), 0)} | "
              f"{' / '.join(fmt(p['cpu'].get(j), 0) for j in PERF_JOBS)} | {'통과' if integ_ok else '실패'} | {note} |")
        if valid:
            med = lambda f: statistics.median(f(p) for p in valid)
            medians.append((label, len(valid), med(lambda p: p["rate"]), med(lambda p: p["p95"]),
                            med(lambda p: p["confirm_p95"] if p["confirm_p95"] is not None else float("nan")),
                            valid))
    if medians:
        w("\n| 단계 | 유효 회차 | S0 완료 속도 중앙값 (건/초) | p95 중앙값 (ms) | 접수 → 확정 p95 중앙값 (초) |")
        w("|---|---|---|---|---|")
        for label, n, rate, p95, conf, _ in medians:  # 마지막 값은 유효 회차 목록
            w(f"| {label} | {n} | {fmt(rate)} | {fmt(p95)} | {fmt(conf, 2)} |")
    w("\n- **이 표의 용도**: 회차별 원본 값과 유효성. 공식 합격(B)과 관측 비교(C)는 0절에서 분리해 표시\n- **조건**: 같은 커밋, 같은 S0(가상 사용자 100개 x 10회, 회원 1,000명이 1건씩, 회차마다 새 공모주), 구성 전환 후 회차 0 워밍업 제외")
    w("- **S0 완료 속도**: S0는 가상 사용자 100개가 1초에 걸쳐 시작하는 고정 1,000건이라 서버 처리 용량이 아님. 지속 부하 처리량, 고가용성 근거로 사용하지 않음")
    w("- **CPU**: 서비스별로 인스턴스 중 최댓값 (`max by (job)`, report.py). 2개 합계가 아니라 총 자원 효율 비교에는 사용할 수 없음")
    w("- **판정**: 정합성 실패(중복 발행 포함) 회차는 성능 값을 판정하지 않음. 무효, 미완료, 잔여 처리 포함 가능, 회차 보고서 실패 회차는 중앙값에서 제외")
    w("- **잔여 처리 확인**: 회차 시작 시 Consumer 랙 0, 이전 회차 마지막 처리 시각(청약 갱신, Outbox 발행, 처리 기록) < 성능 집계 구간 시작 (`meta.txt`의 `start_lag`, `prev_activity_end`, `report.md` 구간)")
    w("- **측정 순서**: 2개 → 1개 → 2개, 단계마다 컨테이너 재시작 후 워밍업 1회. 회차를 무작위 순서로 배치하지 않아 같은 단계 3회는 독립 반복이 아님")
    w("- **자원 예산 (교란 요인)**: 1개와 2개는 인스턴스 수만 다른 것이 아님\n")
    w("| 항목 | 1개 | 2개 | 출처 |")
    w("|---|---|---|---|")
    w("| 서비스 CPU 상한 합 (게이트웨이, 계좌, 청약, 배정) | 1 + 1 + 2 + 0.5 = 4.5 | 2 + 2 + 4 + 1 = 9.0 | `compose.perf.yml`, `compose.scale.yml` |")
    w("| DB 커넥션 풀 합 (청약 30, 계좌 10, 배정 10) | 50 | 100 (사용 가능 97 초과) | 서비스 `application.yml`, LOADTEST 3-5 |")
    w("| PC 전체 CPU (Colima) | 동일 | 동일 | `env.txt` |")
    w("\n- 2개는 상한 합 9.0이 Colima CPU 6개를 초과해, 상한이 아니라 PC CPU를 나눠 사용함\n")
    return {label: {"n": n, "p95": p95, "runs": valid, "commit_ok": commit_ok} for label, n, rate, p95, conf, valid in medians}


def g1_ok(p):
    # 팀 G1 목표: 평균 ≤ 250ms, p95 ≤ 500ms, 오류 0 (성공률 100%)
    return p["avg"] <= G1_AVG_MS and p["p95"] <= G1_P95_MS and p["success"] == 1.0


def observe(perf_sum):
    # C: 두 2개 단계의 p95 중앙값이 모두 1개보다 높거나 모두 낮으면 "반복된 방향의 차이 관측", 아니면 미관측. 판정이 아니라 관측 기록
    need = ("2개 (1차)", "1개", "2개 (2차)")
    if not all(k in perf_sum for k in need) or any(not perf_sum[k]["commit_ok"] for k in need):
        return None
    base = perf_sum["1개"]["p95"]
    a, b = perf_sum["2개 (1차)"]["p95"], perf_sum["2개 (2차)"]["p95"]
    if a > base and b > base:
        label = "반복된 방향의 차이 관측 (두 2개 단계 모두 1개보다 높음)"
    elif a < base and b < base:
        label = "반복된 방향의 차이 관측 (두 2개 단계 모두 1개보다 낮음)"
    else:
        label = "일관된 방향의 차이 미관측"
    return label, f"p95 중앙값 2개 1차 {a:.1f} / 1개 {base:.1f} / 2개 2차 {b:.1f}ms"


def observation_section(w, perf_sum):
    w("\n## 4-1. C: 1개 대비 관측 (판정 아님)\n")
    w("> 단계별 p95 분포와 1개 대비 차이를 기록만 함. 통계적 유의성, 성능 저하 여부를 판정하지 않고, 인스턴스 수가 차이의 원인이라고 단정하지 않음\n")
    order = ("2개 (1차)", "1개", "2개 (2차)")
    if not any(k in perf_sum for k in order):
        w("- 측정 안 함\n")
        return
    w("| 단계 | 유효 회차 | p95 중앙값 (ms) | 최솟값 (ms) | 최댓값 (ms) | 1개 대비 중앙값 차이 |")
    w("|---|---|---|---|---|---|")
    base = perf_sum.get("1개", {}).get("p95")
    for k in order:
        if k not in perf_sum:
            w(f"| {k} | 0 | - | - | - | - |")
            continue
        vals = [p["p95"] for p in perf_sum[k]["runs"]]
        med = perf_sum[k]["p95"]
        diff = "기준" if k == "1개" else ("-" if base is None else f"{med - base:+.1f}ms ({(med - base) / base * 100:+.1f}%)")
        w(f"| {k} | {len(vals)} | {med:.1f} | {min(vals):.1f} | {max(vals):.1f} | {diff} |")
    obs = observe(perf_sum)
    w(f"\n- **관측 결과**: {obs[0] if obs else '판정에 필요한 단계 부족'}")
    w("- **표현 기준**: 두 2개 단계가 모두 1개보다 높거나 모두 낮으면 \"반복된 방향의 차이 관측\", 방향이 다르면 \"일관된 방향의 차이 미관측\"")
    w("- **해석 제한**: 같은 단계 3회는 같은 재시작 상태를 공유해 독립 반복이 아니며, 단계 순서가 고정이라 교환 가능성 가정이 성립하지 않음. 순열 확률 등 통계 수치를 산출하지 않음\n")


def final_verdicts(a1, l2, b3, a3, s3, r3, v4, perf_sum):
    rows = []
    # 1. 정합성: 측정한 모든 단계의 유효 회차
    runs = [r for rs in (b3, a3, s3, r3) for r in rs if not r["incomplete"] and not r["invalid"]]
    measured = ", ".join(name for name, rs in (("적용 전", b3), ("적용 후", a3), ("1개", s3), ("2개 다시", r3)) if rs)
    per_phase = [(name, sum(1 for r in rs if not r["incomplete"] and not r["invalid"])) for name, rs in
                 (("적용 전", b3), ("적용 후", a3), ("1개", s3), ("2개 다시", r3)) if rs]
    undecided = [name for name, n in per_phase if n == 0]
    if runs:
        bad = [r for r in runs if not (all(v == 0 for v in r["integ"].values()) and r["short"] == 0)]
        # 측정한 단계 중 유효 회차가 0개인 단계가 있으면 그 단계는 검증하지 못한 것이라 통과로 표시하지 않음 (위반이 있으면 불합격 유지)
        state = f"⚠️ 판정 불가 (유효 회차 없는 단계: {', '.join(undecided)})" if undecided and not bad else verdict(not bad)
        counts = ", ".join(f"{name} {n}회" for name, n in per_phase)
        rows.append(("정합성", state, f"유효 회차 {len(runs)}회 중 위반 {len(bad)}회 (단계별 유효 회차: {counts})"))
    else:
        rows.append(("정합성", "측정 안 함", ""))
    # 2. 중복 실행 (적용 후): V1 실행 횟수, V2 중복 획득, V3 중복 발행
    if a1 is None or not a3:
        rows.append(("중복 실행 (적용 후)", "측정 안 함", "V1 적용 후, V3 적용 후 필요"))
    else:
        v1_ok = all(EXPECT[k][1] <= a1[k][0] <= EXPECT[k][2] for k in EXPECT)
        v2_rows = list((l2 or {}).values())
        v2_ok = bool(v2_rows) and all(ov == 0 for _, _, ov, _ in v2_rows) and any(n != unk for n, _, _, unk in v2_rows)
        va = [r for r in a3 if not r["incomplete"] and not r["invalid"]]
        v3_ok = bool(va) and all(r["dup"] == 0 for r in va)
        v2_state = ("측정 안 함" if not v2_rows else "판정 불가" if all(n == unk for n, _, _, unk in v2_rows)
                    else "통과" if v2_ok else "불합격")
        if not (v1_ok and v3_ok) or v2_state == "불합격":
            state = verdict(False)
        elif v2_state in ("측정 안 함", "판정 불가"):
            state = f"⚠️ 판정 불가 (V2 {v2_state})"
        else:
            state = verdict(True)
        rows.append(("중복 실행 (적용 후)", state,
                     f"V1 {'통과' if v1_ok else '불합격'}, V2 {v2_state}, V3 중복 발행 {sum(r['dup'] for r in va)}건"))
    # 3. 전역 입장 제한: V4 직접 기록 (after/v4.txt)
    if "after_admitted" in v4:
        limit = int(v4.get("limit", "25980"))
        rows.append(("전역 입장 제한", verdict(int(v4["after_admitted"]) <= limit),
                     f"게이트웨이 2대 1분 입장 {v4['after_admitted']}명, 기준 {limit:,}명 이하 (적용 전 {v4.get('before_admitted', '측정 안 함')}명)"))
    else:
        rows.append(("전역 입장 제한", "측정 안 함", "`after/v4.txt`에 직접 기록 (V4)"))
    # 4. 성능 B (공식 합격): 2개 단계(1차, 2차) 유효 회차 각각이 팀 G1 목표를 충족하는지
    two = [p for k in ("2개 (1차)", "2개 (2차)") for p in (perf_sum.get(k) or {}).get("runs", [])]
    if any(not perf_sum[k]["commit_ok"] for k in perf_sum):
        rows.append(("성능 B: G1 목표 (2개, 공식 합격)", "⚠️ 비교 무효", "회차별 커밋이 다름 (4절)"))
    elif two:
        fails = [p for p in two if not g1_ok(p)]
        rows.append(("성능 B: G1 목표 (2개, 공식 합격)", verdict(not fails),
                     f"2개 유효 회차 {len(two)}회 중 미충족 {len(fails)}회 (회차마다 평균 ≤ {G1_AVG_MS}ms, p95 ≤ {G1_P95_MS}ms, 오류 0)"))
    else:
        rows.append(("성능 B: G1 목표 (2개, 공식 합격)", "측정 안 함", "2개 단계 유효 회차 필요"))
    # 5. 성능 C (관측, 판정 아님): 1개 대비 2개 p95 중앙값의 방향
    obs = observe(perf_sum)
    rows.append(("성능 C: 1개 대비 관측 (판정 아님)", obs[0] if obs else "측정 안 함",
                 (obs[1] + ". 통계적 유의성, 성능 저하 판정 아님 (4-1절)") if obs else "2개 → 1개 → 2개 유효 회차 필요"))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="loadtest/results/scale-verify")
    a = ap.parse_args()
    bdir, adir = os.path.join(a.root, "before"), os.path.join(a.root, "after")
    benv, aenv = kv(os.path.join(bdir, "env.txt")), kv(os.path.join(adir, "env.txt"))
    b1, a1 = v1(bdir), v1(adir)
    l2 = v2(adir)
    b3, a3 = v3(bdir), v3(adir)
    sdir, rdir = os.path.join(a.root, "single"), os.path.join(a.root, "rescale")
    s3, r3 = v3(sdir), v3(rdir)
    senv, renv = kv(os.path.join(sdir, "env.txt")), kv(os.path.join(rdir, "env.txt"))
    L = []
    w = L.append

    def calls(res, name):
        return res[name][0] if res else None

    w("# 수평 확장 검증 결과 (인스턴스 2개, 락 적용 전후)\n")
    vb = [r for r in b3 if not r["incomplete"] and not r["invalid"]]
    va = [r for r in a3 if not r["incomplete"] and not r["invalid"]]
    dup_b, dup_a = sum(r["dup"] for r in vb), sum(r["dup"] for r in va)
    vio_b, vio_a = sum(r["violations"] for r in vb), sum(r["violations"] for r in va)
    def shown(v):
        return "측정 안 함" if v is None else f"{v}회"

    def shown_dup(runs, total):
        return f"{total}건" if runs else "측정 안 함"

    w(f"> 결론: 마감 스케줄러 1분 실행 {shown(calls(b1, '마감'))} → {shown(calls(a1, '마감'))}, "
      f"Outbox 중복 발행 {shown_dup(vb, dup_b)} → {shown_dup(va, dup_a)} (유효 회차 {len(vb)}, {len(va)}개 합계), "
      f"정합성 위반 {shown_dup(vb, vio_b)} / {shown_dup(va, vio_a)}\n")
    perf_lines = []
    perf_sum = perf_section(perf_lines.append, (("2개 (1차)", adir, aenv, a3), ("1개", sdir, senv, s3), ("2개 (2차)", rdir, renv, r3)))
    w("## 0. 최종 판정 (항목별 별도)\n")
    w("| 항목 | 판정 | 근거 |")
    w("|---|---|---|")
    for name, v, why in final_verdicts(a1, l2, b3, a3, s3, r3, kv(os.path.join(adir, "v4.txt")), perf_sum):
        w(f"| {name} | {v} | {why} |")
    w("\n- **구분**: 정합성, 중복 실행은 A(적용 전후 변경 전체)에서 판정. 성능은 같은 커밋 측정에서 B(공식 합격)와 C(관측)로 분리")
    w(f"- **성능 B 기준**: 팀 G1 목표 (LOADTEST 8-2). 2개 단계 유효 회차마다 평균 ≤ {G1_AVG_MS}ms, p95 ≤ {G1_P95_MS}ms, 오류 0. S0 처리량 목표는 S0가 시작 간격에 좌우돼 제외")
    w("- **G1 충족과 수평 확장 효과는 다름**: B는 2개 구성이 서비스 기준을 충족하는지만 판정함. 인스턴스 수가 성능을 변화시켰는지는 검증하지 않음 (C는 관측 기록)\n")
    w("## 측정 조건\n")
    for label, env in (("적용 전", benv), ("적용 후", aenv)):
        w(f"- **{label}**: 커밋 `{env['commit'][:7]}`, {env.get('date', '?')}" if env.get("commit") else f"- **{label}**: 측정 안 함")
    w(f"- **PC**: {benv.get('cpu', '?')}, 코어 {benv.get('host_cpus', '?')}개, 메모리 {int(benv.get('host_mem_bytes', 0) or 0) / 2**30:.0f}GB")
    w(f"- **Docker**: {benv.get('docker', '?')}")
    w("- **구성**: `docker-compose.yml` + `compose.perf.yml` + `compose.scale.yml` (계좌, 청약, 배정 2개씩, 게이트웨이 2대), DB, Kafka, k6 같은 PC")
    w(f"- **측정용 설정**: `LEDGER_RECONCILIATION_CRON={benv.get('ledger_cron', '?')}` (원래 매일 04:00), "
      f"`LEDGER_RECONCILIATION_LOCK_AT_LEAST_FOR={aenv.get('ledger_lock_at_least_for') or '기록 없음'}` (원래 PT5M, 적용 후 측정에서 PT5S)")
    for label, env in (("1개", senv), ("2개 다시", renv)):
        if env:
            w(f"- **{label}**: 커밋 `{env.get('commit', '?')[:7]}`, {env.get('date', '?')}")
    w("- **부하**: S0 (가상 사용자 100개 x 10회, 회원 1,000명이 1건씩, 회차마다 새 공모주), 측정 중 마감 예정 공모주 없음 (스크립트 확인)\n")
    w("# A. 적용 전후 변경 전체 효과 (적용 전 커밋 → 적용 후 커밋)\n")
    w("> 두 커밋 사이에는 락 외 변경(#148 백오프, #162 인출 배치 등)도 포함. 실행 횟수, 중복 건수, 정합성만 비교하고 처리량, 응답 시간은 비교하지 않음\n")

    bsec = (read(os.path.join(bdir, "v1-seconds.txt"), "") or "").strip()
    asec = (read(os.path.join(adir, "v1-seconds.txt"), "") or "").strip()
    w(f"## 1. V1 스케줄러 실행 횟수 (부하 없는 {V1_SECONDS}초)\n")
    w(f"- **측정 시간**: 적용 전 {bsec or '측정 안 함'}초, 적용 후 {asec or '측정 안 함'}초. 기대값은 {V1_SECONDS}초 기준이라 다른 시간은 판정하지 않음\n")
    w("| 스케줄러 | 적용 전 | 적용 후 | 기대 (전 → 후) | 판정 |")
    w("|---|---|---|---|---|")
    for name, _, _ in RULES:
        bc, ac = calls(b1, name), calls(a1, name)
        bs = "측정 안 함" if bc is None else bc
        as_ = "측정 안 함" if ac is None else ac
        if name in EXPECT:
            e = EXPECT[name]
            ok = ac is not None and e[1] <= ac <= e[2]
            note = verdict(ok) if ac is not None else "측정 안 함"
            if asec and asec != str(V1_SECONDS):
                note = f"판정 제외 (측정 {asec}초)"
            if asec == str(V1_SECONDS) and not ok and ac is not None and ac >= 20 and name in ("시간 초과", "반환 재요청"):
                note = "락 미적용 (정합성은 낙관적 락으로 보장)"
            w(f"| {name} | {bs} | {as_} | {e[0]} → {e[1]}-{e[2]} | {note} |")
        else:
            w(f"| {name} | {bs} | {as_} | 판정 제외 (lockAtLeastFor 0초, V3로 판정) | 참고 |")
    w("\n- **계산**: 그룹에 속한 쿼리의 `calls` 최댓값 (스케줄러 1회 실행 = 대표 쿼리 1회)")
    w(f"- **기대 근거**: {V1_SECONDS}초 ÷ 주기 5초 = 12회 x 인스턴스 2개 = 24회 → 락 적용 후 `lockAtLeastFor` 4초라 12회")
    w(f"- **원장 대사 기대 근거**: cron 10초라 {V1_SECONDS}초에 6회 x 인스턴스 2개 = 12회 → 락 적용 후 회차마다 한 인스턴스만 실행해 6회 (측정용 `lockAtLeastFor` 5초. 운영값 5분이면 인스턴스 수와 무관하게 0-1회라 판정 불가)\n")
    w("<details>\n<summary>V1 매칭 쿼리 (스케줄러별 calls, 쿼리 처음 120자)</summary>\n")
    for label, res in (("적용 전", b1), ("적용 후", a1)):
        w(f"**{label}**\n")
        for name, (_, rows) in (res or {}).items():
            for c, q in rows:
                w(f"- {name}: {c}회, `{q}`")
        w("")
    w("</details>\n")

    w("## 2. V2 동시 실행 (적용 후)\n")
    w("| 스키마 | 락 이름 | 획득 횟수 | 획득 인스턴스 수 | 해제 미관측 | 중복 획득 | 판정 |")
    w("|---|---|---|---|---|---|---|")
    for (sch, name), (n, hosts, ov, unknown) in sorted((l2 or {}).items()):
        w(f"| {sch} | `{name}` | {n} | {hosts} | {unknown} | {ov} | {v2_verdict(n, ov, unknown)} |")
    w("\n- **중복 획득 계산**: 같은 락에서 다음 획득 시각(`locked_at`) < 이전 해제 시각(`lock_until`)인 경우. 해제를 관측한 획득만 계산")
    w("- **해제 미관측**: 실행 중 값(유지 시간 = `lockAtMostFor`)만 조회된 획득, 또는 `LOCK_AT_MOST_FOR`에 없는 락의 획득. 종료 시각을 알 수 없어 중복 획득 계산에서 제외 (측정 구간 마지막 획득 포함)")
    w("- **Outbox 발행기**: 락 유지가 수 ms라 1초 조회로 대부분 누락됨. V3 중복 발행 건수로 판정\n")

    w("## 3. V3 중복 발행, 정합성 (S0 1,000건)\n")
    w("| 시점 | 회차 | 토픽 | Kafka 증가 | Outbox 행 | 중복 발행 |")
    w("|---|---|---|---|---|---|")
    for label, runs in (("전", b3), ("후", a3)):
        for r in runs:
            if r["incomplete"]:
                continue
            for t, sent, rows, d in r["topics"]:
                w(f"| {label} | r{r['n']} | {t} | {sent} | {rows} | {d} |")
    w("\n- **계산**: 토픽별 차이 = Kafka 증가(측정 후 오프셋 - 측정 전 오프셋) - 같은 시간대 Outbox 행 수. 양수는 중복 발행, 음수는 미발행 또는 측정 불일치로 따로 합산 (서로 상쇄하지 않음)\n")
    w("| 시점 | 회차 | 청약 상태 | 중복 발행 | 미발행, 불일치 | 계좌 중복 수신 로그 | 정합성 위반 | 판정 |")
    w("|---|---|---|---|---|---|---|---|")
    for label, runs, need_zero_dup in (("전", b3, False), ("후", a3, True)):
        for r in runs:
            if r["incomplete"]:
                w(f"| {label} | r{r['n']} | - | - | - | - | - | ⚠️ 미완료 (`meta.tmp`만 있음) |")
                continue
            if r["invalid"]:
                note = "⚠️ 무효: " + ", ".join(r["invalid"])
            else:
                # 위반은 항목마다 0이어야 하고, 음수 차이는 측정 불일치라 통과시키지 않음
                ok = all(v == 0 for v in r["integ"].values()) and r["short"] == 0 and (r["dup"] == 0 or not need_zero_dup)
                note = verdict(ok)
            w(f"| {label} | r{r['n']} | {r['status']} | {r['dup']} | {r['short']} | {r['dup_log']} | {r['violations']} | {note} |")
    w("\n- **정합성 위반**: `verify.sql` 2, 3, 4, 5, 6, 11, 16, 17, 18번과 처리 중 청약 수를 건수로 집계한 합 (원본 22종은 `sql_result.txt`)")
    w("- **판정**: 적용 전은 위반 항목 모두 0이고 미발행 0이면 통과 (중복 발행은 기록), 적용 후는 여기에 중복 발행 0건 추가")
    w(f"- **무효**: 필수 파일 누락, 완료 표시 없음, k6 종료 코드가 0, 99 외, 청약 행이 {S0_REQUESTS}건이 아닌 회차. 결론 합계에서 제외\n")
    for label, runs in (("적용 전", b3), ("적용 후", a3)):
        for r in runs:
            if r["incomplete"]:
                continue
            bad = {k: v for k, v in r["integ"].items() if v}
            if bad:
                w(f"- **{label} r{r['n']} 위반 항목**: {bad}")
            if r["meta"].get("drain_timeout"):
                w(f"- **{label} r{r['n']} 처리 완료 대기 시간 초과**: 원인 미확인")
            if r["meta"].get("k6_exit", "0") != "0":
                w(f"- **{label} r{r['n']} k6 종료 코드 {r['meta']['k6_exit']}**: 99면 k6 임계값(thresholds) 위반, 위반 항목은 "
                  f"`loadtest/results/{r['meta'].get('run', '?')}/summary.json`의 `thresholds` (이 검증의 판정 대상 아님)")

    L.extend(perf_lines)
    observation_section(w, perf_sum)

    w("\n## 5. V4 입장, V5 강제 종료 (직접 기록)\n")
    w("- **V4**: `after/v4.txt`에 `before_admitted=`, `after_admitted=` (1분 입장 인원), `limit=25980` 기록 → 0절 판정에 반영 (측정자, 방법 PR #181)")
    w("- **V5**: 종료 컨테이너 _, 종료 시각 _, 다른 인스턴스 락 획득 시각 _, 처리 완료 후 위반 _건\n")
    w("## 6. 이상값, 미확인\n")
    w("- (기대와 다른 값을 한 줄씩. 없으면 \"없음\")\n")
    w("## 7. 한계\n")
    w("- **로컬 1대**: 인스턴스 2개가 같은 PC의 CPU, DB, Kafka를 공유. 실제 다중 서버 환경과 다름")
    w("- **A의 비교 범위**: 적용 전후 커밋 차이에 락 외 변경 포함. 중복 실행, 정합성 변화만 락 효과로 해석")
    w("- **성능 범위**: S0 1,000건의 응답 시간. 지속 부하 처리량, 고가용성은 측정하지 않음")
    w("- **성능 비교의 교란 가능성**")
    w("  - 측정 순서: 2개 → 1개 → 2개 고정, 회차 무작위 배치 없음. 단계별 시점 차이(PC 백그라운드 부하)가 인스턴스 수 효과와 구분되지 않음")
    w("  - 단계별 재시작: 단계마다 컨테이너를 재구성해 JVM, Kafka 파티션 할당 상태가 단계마다 다름. 같은 단계 3회는 독립 반복이 아님")
    w("  - 자원 설정: 1개와 2개는 서비스 CPU 상한 합(4.5 vs 9.0), DB 커넥션 풀 합(50 vs 100), Prometheus 수집 설정도 다름")
    w("  - DB 데이터 증가: 회차마다 청약 1,000건과 증거금, 원장이 추가되어 이후 단계일수록 테이블이 큼")
    w("  - 결론: 1개와 2개의 차이를 인스턴스 수의 효과로 단정하지 않음. 통계적 판정에는 회차마다 재시작, 무작위 순서, 단계당 5회 이상이 필요")
    w("- **CPU**: 인스턴스별 최댓값이라 2개의 총 CPU 사용량이 아님")
    w("- **외부 요청**: 게이트웨이 8080 1대만 수신. 2번째 게이트웨이는 스케줄러, 입장 처리 중복 상황만 재현")
    w("- **원장 대사 주기**: 실제 매일 04:00, 락 최소 유지 5분을 측정용 10초, 5초로 변경. 실행 간격과 무관하게 회차당 1번만 실행되는지 확인하려는 설정\n")
    w("<details>\n<summary>원본 파일 (before, after, single, rescale)</summary>\n")
    for d in (bdir, adir, sdir, rdir):
        if os.path.isdir(d):
            for f in sorted(os.listdir(d)):
                w(f"- `{os.path.join(d, f)}`")
    w("- k6 원본, verify.sql 결과, report.md: `loadtest/results/scale-{before,after,single,rescale}-r*/`")
    w("\n</details>")

    out = os.path.join(a.root, "요약.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    print(out)


if __name__ == "__main__":
    main()
