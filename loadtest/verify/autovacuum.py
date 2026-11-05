#!/usr/bin/env python3
"""정식 측정 실행마다 남긴 pg_stat_user_tables 누적값(autovacuum.csv)의 차이로, 각 실행 동안 autovacuum, autoanalyze가 돈 테이블을 정리한다.
표준 라이브러리만 사용, Python 3.8 이상.

사용: python autovacuum.py <batch>/manifest.csv --results loadtest/results --before <batch>/autovacuum-before.csv > <batch>/autovacuum.md

왜 보나: PostgreSQL은 행을 바꾸거나 지울 때 옛 버전(죽은 행)을 남기고, autovacuum이 쌓인 죽은 행을 백그라운드에서 치운다.
autoanalyze는 쿼리 계획용 통계를 새로 고친다. 측정 중 청약 상태 변경, outbox 발행 표시처럼 행 변경이 많아 측정 도중에 자동으로 돌고,
돌 때 CPU, IO를 써서 그 실행이 느려질 수 있다. 누적 횟수만 남는 통계라, 실행마다 찍어 두고 앞 기록과의 차이로 어느 실행과 겹쳤는지 본다.

- 실행 순서는 manifest 순서. 첫 실행은 --before(첫 실행 직전 기록)와 비교한다
- 실행 i의 차이 = 실행 i가 끝나고 소진 확인 뒤 기록 - 앞 실행의 기록. 그래서 앞 실행 뒤 대기(GAP), 토큰 재발급 시간도 포함한다
- 통계는 PostgreSQL이 몇 초 늦게 반영할 수 있어 경계 근처의 1회는 앞뒤 실행으로 밀릴 수 있다
- 누적값이 줄었으면(통계 초기화, 재시작) 그 실행은 "초기화됨"으로 표시하고 차이를 세지 않는다
- 기록 파일이 없는 실행이 있으면 표에 "기록 없음"으로 남기고 종료 코드 1
"""
import argparse
import csv
import os
import sys

COLS = ["table", "live_tup", "dead_tup", "autovacuum_count", "autoanalyze_count", "last_autovacuum", "last_autoanalyze"]


def load(path):
    """autovacuum.csv → {테이블: {열: 값}}. 머리줄이 다르거나 숫자가 아니면 ValueError"""
    with open(path, encoding="utf-8", newline="") as f:
        r = csv.reader(f)
        head = next(r, None)
        if head != COLS:
            raise ValueError(f"{path}: 머리줄이 {','.join(COLS)}가 아님: {head}")
        out = {}
        for cols in r:
            if not cols:
                continue
            if len(cols) != len(COLS):
                raise ValueError(f"{path}: 열 수가 다름: {cols}")
            row = dict(zip(COLS, cols))
            for k in ("live_tup", "dead_tup", "autovacuum_count", "autoanalyze_count"):
                row[k] = int(row[k])
            out[row["table"]] = row
    if not out:
        raise ValueError(f"{path}: 테이블 행이 없다")
    return out


def diff(prev, cur):
    """(autovacuum {테이블: 횟수}, autoanalyze {테이블: 횟수}, 초기화 여부). 앞 기록에 없던 테이블은 0에서 시작한 것으로 본다"""
    vac, ana, reset = {}, {}, False
    for t, row in cur.items():
        p = prev.get(t, {"autovacuum_count": 0, "autoanalyze_count": 0})
        dv = row["autovacuum_count"] - p["autovacuum_count"]
        da = row["autoanalyze_count"] - p["autoanalyze_count"]
        if dv < 0 or da < 0:
            reset = True
            continue
        if dv:
            vac[t] = dv
        if da:
            ana[t] = da
    return vac, ana, reset


def fmt(counts):
    return ", ".join(f"{t} {n}" for t, n in sorted(counts.items())) or "-"


def summarize(manifest, results, before):
    """(마크다운 문자열, 기록이 없는 실행 목록)"""
    with open(manifest, encoding="utf-8", newline="") as f:
        runs = list(csv.DictReader(f))
    prev = load(before)
    lines = ["# 실행별 autovacuum, autoanalyze", "",
             "- 실행이 끝나고 소진 확인 뒤 남긴 `pg_stat_user_tables` 누적 횟수와 앞 기록의 차이 (앞 실행 뒤 대기 시간 포함)",
             "- 통계 반영이 몇 초 늦을 수 있어 경계 근처의 1회는 앞뒤 실행으로 밀릴 수 있다",
             "- 표의 값: `테이블 횟수`. `-`는 그 사이에 돌지 않음", "",
             "| run_id | 시나리오 | 구간 | autovacuum | autoanalyze |", "| --- | --- | --- | --- | --- |"]
    missing, total_v, total_a = [], {}, {}
    for r in runs:
        path = os.path.join(results, r["run_id"], "autovacuum.csv")
        head = f"| {r['run_id']} | {r.get('scenario', '-')} | {r.get('phase', '-')} |"
        if not os.path.exists(path):
            missing.append(r["run_id"])
            lines.append(f"{head} 기록 없음 | 기록 없음 |")
            continue
        cur = load(path)
        vac, ana, reset = diff(prev, cur)
        if reset:
            lines.append(f"{head} 초기화됨 (누적값이 줄었다) | 초기화됨 |")
        else:
            lines.append(f"{head} {fmt(vac)} | {fmt(ana)} |")
            for t, n in vac.items():
                total_v[t] = total_v.get(t, 0) + n
            for t, n in ana.items():
                total_a[t] = total_a.get(t, 0) + n
        prev = cur
    lines += ["", f"- 합계: autovacuum {fmt(total_v)} / autoanalyze {fmt(total_a)}"]
    if missing:
        lines.append(f"- 기록 없음: {', '.join(missing)}")
    return "\n".join(lines) + "\n", missing


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("manifest")
    ap.add_argument("--results", required=True, help="실행별 폴더가 있는 곳 (loadtest/results)")
    ap.add_argument("--before", required=True, help="첫 실행 직전 기록 (<batch>/autovacuum-before.csv)")
    a = ap.parse_args(argv)
    try:
        md, missing = summarize(a.manifest, a.results, a.before)
    except (OSError, ValueError) as e:
        print(f"autovacuum 정리 실패: {e}", file=sys.stderr)
        return 1
    sys.stdout.write(md)
    if missing:
        print(f"기록이 없는 실행 {len(missing)}개: {', '.join(missing)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
