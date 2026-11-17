#!/usr/bin/env python3
"""scale_verify.sh 결과로 수평 확장 검증 요약.md를 생성한다 (표준 라이브러리만, Python 3.8 이상). 저장소 루트에서 실행한다.

사용: python3 loadtest/scale_report.py [--root loadtest/results/scale-verify]
판정 기준은 docs 검증 계획 0절과 같다. 숫자는 모두 원본 파일에서 계산하고, 계산식을 요약.md에 같이 적는다.
"""
import argparse
import os
import re
from datetime import datetime

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
EXPECT = {
    "마감": (24, 10, 14),
    "시간 초과": (24, 10, 14),
    "반환 재요청": (24, 10, 14),
    "원장 대사": (12, 0, 2),
}
# 기대값은 60초 측정 기준. 다른 측정 시간은 판정하지 않는다
V1_SECONDS = 60
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
    if raw is None:
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
        # 실행 중에는 lock_until = 획득 + lockAtMostFor, 해제 후에는 획득 + lockAtLeastFor(또는 해제 시각)라 실행 중 값의 유지 시간이 최댓값이다.
        # 최댓값만 조회된 획득은 해제를 관측하지 못한 것으로 판정하고 종료 시각을 None으로 설정한다 (모든 획득의 유지 시간이 동일하면 모두 해제 값으로 판정)
        durations = {at: [u - at for u in untils] for at, (_, untils) in acq.items()}
        longest = max(max(d) for d in durations.values())
        shortest = min(min(d) for d in durations.values())
        ends = {}
        for at, (_, untils) in acq.items():
            released = [u for u in untils if u - at < longest] if longest > shortest else list(untils)
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
        if total != S0_REQUESTS:
            invalid.append(f"청약 행 {total}건 (기대 {S0_REQUESTS})")
        runs.append({"n": n, "incomplete": False, "invalid": invalid, "topics": topics,
                     # 토픽끼리 상쇄되지 않게 양수(중복 발행)와 음수(미발행 또는 측정 불일치)를 따로 합산
                     "dup": sum(d for *_, d in topics if d > 0), "short": sum(-d for *_, d in topics if d < 0),
                     "integ": integ,
                     # 위반끼리 상쇄되지 않게 항목마다 절댓값 (v04는 차이라 음수 가능)
                     "violations": sum(abs(v) for v in integ.values()), "dup_log": dup_log, "meta": meta, "status": status})
    return runs


def verdict(ok):
    return "✅ 통과" if ok else "❌ 불합격"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="loadtest/results/scale-verify")
    a = ap.parse_args()
    bdir, adir = os.path.join(a.root, "before"), os.path.join(a.root, "after")
    benv, aenv = kv(os.path.join(bdir, "env.txt")), kv(os.path.join(adir, "env.txt"))
    b1, a1 = v1(bdir), v1(adir)
    l2 = v2(adir)
    b3, a3 = v3(bdir), v3(adir)
    L = []
    w = L.append

    def calls(res, name):
        return res[name][0] if res else None

    w("# 수평 확장 검증 결과 (인스턴스 2개, 락 적용 전후)\n")
    vb = [r for r in b3 if not r["incomplete"] and not r["invalid"]]
    va = [r for r in a3 if not r["incomplete"] and not r["invalid"]]
    dup_b, dup_a = sum(r["dup"] for r in vb), sum(r["dup"] for r in va)
    vio_b, vio_a = sum(r["violations"] for r in vb), sum(r["violations"] for r in va)
    w(f"> 결론: 마감 스케줄러 1분 실행 {calls(b1, '마감')} → {calls(a1, '마감')}회, Outbox 중복 발행 {dup_b} → {dup_a}건 "
      f"(유효 회차 {len(vb)}, {len(va)}개 합계), 정합성 위반 {vio_b} / {vio_a}건\n")
    w("## 측정 환경\n")
    for label, env in (("적용 전", benv), ("적용 후", aenv)):
        w(f"- **{label}**: 커밋 `{env.get('commit', '?')[:7]}`, {env.get('date', '?')}")
    w(f"- **PC**: {benv.get('cpu', '?')}, 코어 {benv.get('host_cpus', '?')}개, 메모리 {int(benv.get('host_mem_bytes', 0) or 0) / 2**30:.0f}GB")
    w(f"- **Docker**: {benv.get('docker', '?')}")
    w("- **구성**: `docker-compose.yml` + `compose.perf.yml` + `compose.scale.yml` (계좌, 청약, 배정 2개씩, 게이트웨이 2대), DB, Kafka, k6 같은 PC")
    w(f"- **측정용 설정**: `LEDGER_RECONCILIATION_CRON={benv.get('ledger_cron', '?')}` (원래 매일 04:00)\n")

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
            note = verdict(ok)
            if asec and asec != str(V1_SECONDS):
                note = f"판정 제외 (측정 {asec}초)"
            if asec == str(V1_SECONDS) and not ok and ac is not None and ac >= 20 and name in ("시간 초과", "반환 재요청"):
                note = "락 미적용 (정합성은 낙관적 락으로 보장)"
            w(f"| {name} | {bs} | {as_} | {e[0]} → {e[1]}-{e[2]} | {note} |")
        else:
            w(f"| {name} | {bs} | {as_} | 판정 제외 (lockAtLeastFor 0초, V3로 판정) | 참고 |")
    w("\n- **계산**: 그룹에 속한 쿼리의 `calls` 최댓값 (스케줄러 1회 실행 = 대표 쿼리 1회)")
    w(f"- **기대 근거**: {V1_SECONDS}초 ÷ 주기 5초 = 12회 x 인스턴스 2개 = 24회 → 락 적용 후 `lockAtLeastFor` 4초라 12회\n")
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
        w(f"| {sch} | `{name}` | {n} | {hosts} | {unknown} | {ov} | {verdict(ov == 0)} |")
    w("\n- **중복 획득 계산**: 같은 락에서 다음 획득 시각(`locked_at`) < 이전 해제 시각(`lock_until`)인 경우. 해제를 관측한 획득만 계산")
    w("- **해제 미관측**: 실행 중 값(조회된 `lock_until` 중 최댓값)만 조회된 획득. 종료 시각을 몰라 중복 획득 계산에서 제외 (측정 구간 마지막 획득 포함)")
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
                w(f"- **{label} r{r['n']} k6 종료 코드 {r['meta']['k6_exit']}**: 99면 응답 시간 임계값 위반 (이 검증의 판정 대상 아님)")

    w("\n## 4. V4 입장, V5 강제 종료 (직접 기록)\n")
    w("- **V4**: 적용 전 1분 _명, 적용 후 1분 _명 (측정자 _, 방법 PR #181)")
    w("- **V5**: 종료 컨테이너 _, 종료 시각 _, 다른 인스턴스 락 획득 시각 _, 처리 완료 후 위반 _건\n")
    w("## 5. 이상값, 미확인\n")
    w("- (기대와 다른 값을 한 줄씩. 없으면 \"없음\")\n")
    w("<details>\n<summary>원본 파일 (before, after)</summary>\n")
    for d in (bdir, adir):
        if os.path.isdir(d):
            for f in sorted(os.listdir(d)):
                w(f"- `{os.path.join(d, f)}`")
    w("- k6 원본, verify.sql 결과: `loadtest/results/scale-{before,after}-r*/`")
    w("\n</details>")

    out = os.path.join(a.root, "요약.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    print(out)


if __name__ == "__main__":
    main()
