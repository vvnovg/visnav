"""CLI этапа M2: vpr-m2 replay-eval."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from vpr_bench.fieldlog import read_log
from vpr_bench.monitoreval import evaluate_monitor, render_monitor_report
from vpr_bench.replayeval import evaluate_replay, read_trajectory, render_replay_report


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vpr-m2")
    sub = parser.add_subparsers(dest="command", required=True)
    r = sub.add_parser("replay-eval", help="оценить replay-траекторию по NFR-1/NFR-5")
    r.add_argument("--traj", required=True, type=Path)
    r.add_argument("--log", required=True, type=Path, help="журнал кадров сессии (.jsonl) — источник GPS")
    r.add_argument("--out", required=True, type=Path)
    m = sub.add_parser("monitor-eval", help="оценить монитор GNSS: задержки, ложные тревоги, полнота")
    m.add_argument("--traj", required=True, type=Path)
    m.add_argument("--log", required=True, type=Path, help="журнал кадров сессии (.jsonl) — источник GPS")
    m.add_argument("--out", required=True, type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    header, rows = read_trajectory(args.traj, keep_null_pos=args.command == "monitor-eval")
    log_header, frames = read_log(args.log)
    if log_header.get("started_ms") != header.get("session_started_ms"):
        print("error: trajectory and log are from different sessions", file=sys.stderr)
        return 2
    if args.command == "monitor-eval":
        mon = evaluate_monitor(header, rows, frames)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(render_monitor_report(mon))
        print(f"windows={len(mon.windows)} false_untrusted={mon.false_untrusted_pct:.2f} % -> {args.out}")
        return 0
    result = evaluate_replay(header, rows, frames)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(render_replay_report(result))

    if not result.has_data():
        print("warning: no data to evaluate", file=sys.stderr)

    if result.visual:
        print(f"P50={result.p50_m:.1f} m P95={result.p95_m:.1f} m over {result.n_points} points -> {args.out}")
    else:
        worst = result.worst_drift_pct()
        weighted = result.weighted_drift_pct()
        print(f"worst={worst:.2f} % weighted={weighted:.2f} % -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
