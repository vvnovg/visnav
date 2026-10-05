"""CLI этапа M4: vpr-m4 perf-eval."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from vpr_bench.perfeval import evaluate, read_perf, render


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vpr-m4")
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("perf-eval", help="отчёт NFR-4/6/7 и рекомендация буфера по журналу .perf.jsonl")
    p.add_argument("--perf", required=True, type=Path, help="прогон с профилем full")
    p.add_argument("--baseline", type=Path, help="прогон с профилем baseline (для NFR-6)")
    p.add_argument("--out", required=True, type=Path)
    return parser


def _perf_eval(args: argparse.Namespace) -> int:
    try:
        full = read_perf(args.perf)
        base = read_perf(args.baseline) if args.baseline is not None else None
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    if full.header.get("profile") != "full":
        print(f"error: --perf must have profile=full, got {full.header.get('profile')!r}", file=sys.stderr)
        return 2
    if base is not None and base.header.get("profile") != "baseline":
        print(f"error: --baseline must have profile=baseline, got {base.header.get('profile')!r}", file=sys.stderr)
        return 2
    res = evaluate(full, base)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(render(res), encoding="utf-8")
    print(f"duration={res.duration_min:.1f} min frames={res.stages['e2e'].n} nfr7={res.nfr7} -> {args.out}")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return _perf_eval(args)


if __name__ == "__main__":
    sys.exit(main())
