"""CLI этапа M3: vpr-m3 nav-eval, vpr-m3 pack-map, vpr-m3 pack-trip."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import sys
import zipfile
from datetime import datetime, timezone
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
    t = sub.add_parser("pack-trip", help="собрать пакет поездки (эталоны, дороги, карта, маршрут) в <out>/trips/<name>")
    t.add_argument("--route", required=True, type=Path)
    t.add_argument("--name", required=True)
    t.add_argument("--refs", required=True, type=Path)
    t.add_argument("--onnx", required=True, type=Path)
    t.add_argument("--pbf", required=True, type=Path)
    t.add_argument("--mbtiles", required=True, type=Path)
    t.add_argument("--fonts-zip", required=True, type=Path)
    t.add_argument("--out", required=True, type=Path)
    t.add_argument("--dest-name")
    t.add_argument("--refs-buffer-m", type=float, default=300.0)
    t.add_argument("--roads-buffer-m", type=float, default=300.0)
    t.add_argument("--map-buffer-m", type=float, default=500.0)
    t.add_argument("--force", action="store_true")
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


def _step_refs(ns: argparse.Namespace) -> int:
    from vpr_bench.m1cli import _pack
    return _pack(ns)


def _step_roads(ns: argparse.Namespace) -> int:
    from vpr_bench.m2cli import _pack_roads
    return _pack_roads(ns)


def _step_map(ns: argparse.Namespace) -> int:
    return _pack_map(ns)


def _file_sha256(path: Path) -> str:
    """То же, что onnx_export.onnx_sha256, но без импорта cv2/onnxruntime/torch и блоками."""
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def _size_mb(paths) -> float:
    total = 0
    for p in paths:
        if p.is_dir():
            total += sum(f.stat().st_size for f in p.rglob("*") if f.is_file())
        elif p.is_file():
            total += p.stat().st_size
    return total / 1e6


def _pack_trip(args: argparse.Namespace) -> int:
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", args.name):
        print("error: bad trip name", file=sys.stderr)
        return 2
    try:
        track = parse_gpx(args.route)
    except (OSError, ValueError) as e:
        print(f"error: route: {e}", file=sys.stderr)
        return 2
    if len(track) < 2:
        print("error: route has fewer than 2 timed points", file=sys.stderr)
        return 2
    trip = args.out / "trips" / args.name
    if trip.exists():
        if not args.force:
            print("error: trip exists, pass --force", file=sys.stderr)
            return 2
        shutil.rmtree(trip)
    trip.mkdir(parents=True)
    try:
        steps = (
            (_step_refs, argparse.Namespace(refs=args.refs, onnx=args.onnx, out=trip, gpx=[args.route],
                                            buffer_m=args.refs_buffer_m)),
            (_step_roads, argparse.Namespace(pbf=args.pbf, gpx=[args.route], log=[], bbox=None,
                                             buffer_m=args.roads_buffer_m, out=trip)),
            (_step_map, argparse.Namespace(mbtiles=args.mbtiles, fonts_zip=args.fonts_zip, gpx=[args.route], log=[],
                                           buffer_m=args.map_buffer_m, out=trip / "map")),
        )
        for step, ns in steps:
            rc = step(ns)
            if rc != 0:
                shutil.rmtree(trip)
                return rc
    except BaseException:
        shutil.rmtree(trip, ignore_errors=True)
        raise
    sha = _file_sha256(trip / "model.onnx")
    root_model = args.out / "model.onnx"
    if root_model.exists() and _file_sha256(root_model) != sha:
        shutil.rmtree(trip)
        print(f"error: trip model differs from {root_model} — all trips share one model", file=sys.stderr)
        return 2
    os.replace(trip / "model.onnx", root_model)
    dest = track[-1]
    (trip / "route.json").write_text(json.dumps(
        {"dest_lat": dest.lat, "dest_lon": dest.lon, "dest_name": args.dest_name or args.name},
        ensure_ascii=False, indent=2), encoding="utf-8")
    route_km = sum(haversine_m(a.lat, a.lon, b.lat, b.lon) for a, b in zip(track, track[1:])) / 1000.0
    sizes = {
        "refs": _size_mb(trip.glob("refpack.*")),
        "roads": _size_mb(trip.glob("roadpack.*")),
        "map": _size_mb([trip / "map"]),
    }
    total_mb = sum(sizes.values())
    per_100 = total_mb / max(route_km, 1e-9) * 100
    meta = {
        "name": args.name,
        "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "route_km": round(route_km, 3),
        "model": args.onnx.stem,
        "onnx_sha256": sha,
        "buffers_m": {"refs": args.refs_buffer_m, "roads": args.roads_buffer_m, "map": args.map_buffer_m},
        "sizes_mb": {k: round(v, 2) for k, v in sizes.items()},
        "total_mb": round(total_mb, 2),
        "mb_per_100km": round(per_100, 2),
        "nfr8_target_mb_per_100km": 50,
    }
    (trip / "trip.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"trip {args.name}: {route_km:.1f} km, refs {sizes['refs']:.2f} MB, roads {sizes['roads']:.2f} MB, "
          f"map {sizes['map']:.2f} MB, total {total_mb:.2f} MB = {per_100:.2f} MB per 100 km "
          f"(NFR-8 target 50, incl. refs) -> {trip}")
    if per_100 > 50:
        print("warning: over NFR-8 target")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "pack-map":
        return _pack_map(args)
    if args.command == "pack-trip":
        return _pack_trip(args)
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
