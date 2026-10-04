"""CLI этапа M3: vpr-m3 nav-eval, vpr-m3 pack-map."""
from __future__ import annotations

import argparse
import sqlite3
import sys
import zipfile
from pathlib import Path

from vpr_bench.db_builder import Corridor
from vpr_bench.fieldlog import gps_track, read_log
from vpr_bench.geo import haversine_m
from vpr_bench.mapkit import check_mbtiles, clip_mbtiles, extract_glyphs, write_map_meta
from vpr_bench.naveval import evaluate_nav, read_nav, render_nav_report
from vpr_bench.query import clean_track, parse_gpx


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vpr-m3")
    sub = parser.add_subparsers(dest="command", required=True)
    n = sub.add_parser("nav-eval", help="оценить подсказки, перестроения и прибытие по журналу ведения (.nav.jsonl)")
    n.add_argument("--nav", required=True, type=Path)
    n.add_argument("--log", required=True, type=Path, help="журнал кадров сессии (.jsonl) — источник GPS")
    n.add_argument("--out", required=True, type=Path)
    m = sub.add_parser("pack-map", help="обрезать офлайн-карту (.mbtiles) до коридора поездки и достать глифы")
    m.add_argument("--mbtiles", required=True, type=Path)
    m.add_argument("--fonts-zip", required=True, type=Path)
    m.add_argument("--gpx", action="append", default=[], type=Path)
    m.add_argument("--log", action="append", default=[], type=Path)
    m.add_argument("--buffer-m", type=float, default=500.0)
    m.add_argument("--out", required=True, type=Path)
    return parser


def _pack_map(args: argparse.Namespace) -> int:
    try:
        tracks = [parse_gpx(p) for p in args.gpx]
        tracks += [clean_track(gps_track(read_log(p)[1]), max_hdop=None)[0] for p in args.log]
    except (OSError, ValueError) as e:
        print(f"error: track: {e}", file=sys.stderr)
        return 2
    tracks = [t for t in tracks if t]
    if not tracks:
        print("error: need at least one --gpx or --log track", file=sys.stderr)
        return 2
    out = args.out
    try:
        check_mbtiles(args.mbtiles)
        out.mkdir(parents=True, exist_ok=True)
        extract_glyphs(args.fonts_zip, out / "fonts")
        kept, removed = clip_mbtiles(args.mbtiles, out / "corridor.mbtiles", Corridor(tracks, args.buffer_m))
    except (ValueError, sqlite3.Error, FileNotFoundError, zipfile.BadZipFile) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    info = check_mbtiles(out / "corridor.mbtiles")
    write_map_meta(out, info, args.buffer_m)
    km = sum(haversine_m(a.lat, a.lon, b.lat, b.lon) for t in tracks for a, b in zip(t, t[1:])) / 1000.0
    print(f"tiles kept={kept} removed={removed}, {info.bytes / 1e6:.1f} MB, "
          f"{info.bytes / 1e6 / max(km, 1e-9) * 100:.1f} MB per 100 km of tracks "
          f"(NFR-8: <= 50 MB per 100 km incl. reference pack; repeated drives of one corridor understate it) -> {out}")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "pack-map":
        return _pack_map(args)
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
