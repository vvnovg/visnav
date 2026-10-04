"""CLI этапа M3: vpr-m3 nav-eval."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from vpr_bench.fieldlog import read_log
from vpr_bench.naveval import evaluate_nav, read_nav, render_nav_report


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vpr-m3")
    sub = parser.add_subparsers(dest="command", required=True)
    n = sub.add_parser("nav-eval", help="оценить подсказки, перестроения и прибытие по журналу ведения (.nav.jsonl)")
    n.add_argument("--nav", required=True, type=Path)
    n.add_argument("--log", required=True, type=Path, help="журнал кадров сессии (.jsonl) — источник GPS")
    n.add_argument("--out", required=True, type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    header, events = read_nav(args.nav)
    log_header, frames = read_log(args.log)
    if log_header.get("started_ms") != header.get("session_started_ms"):
        print("error: nav log and frame log are from different sessions", file=sys.stderr)
        return 2
    res = evaluate_nav(header, events, frames)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(render_nav_report(res), encoding="utf-8")
    print(f"maneuvers={len(res.checks)} reroutes={res.n_reroutes} false={res.false_reroutes} -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
