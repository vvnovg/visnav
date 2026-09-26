"""CLI: vpr-bench fetch-refs | extract-queries | bench."""
from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import asdict
from datetime import datetime
from pathlib import Path

from vpr_bench.db_builder import Corridor, ViewConfig, build_reference_db
from vpr_bench.geo import BBox
from vpr_bench.mapillary import MapillaryClient
from vpr_bench.models import pick_device
from vpr_bench.pipeline import run_benchmark
from vpr_bench.query import extract_query_frames, parse_gpx
from vpr_bench.report import render_report


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vpr-bench")
    sub = parser.add_subparsers(dest="command", required=True)

    f = sub.add_parser("fetch-refs", help="скачать эталоны Mapillary в bbox или вдоль треков")
    f.add_argument("--bbox", default=None, help="min_lon,min_lat,max_lon,max_lat")
    f.add_argument(
        "--gpx", action="append", default=[], type=Path, help="GPX-трек (можно несколько раз)"
    )
    f.add_argument("--buffer-m", type=float, default=600.0, help="буфер коридора вокруг треков, м")
    f.add_argument("--out", required=True, type=Path)
    f.add_argument("--views", type=int, default=8)
    f.add_argument("--fov", type=float, default=90.0)
    f.add_argument("--width", type=int, default=640)
    f.add_argument("--height", type=int, default=480)
    f.add_argument("--workers", type=int, default=8)

    q = sub.add_parser("extract-queries", help="нарезать видео поездки в кадры-запросы")
    q.add_argument("--video", required=True, type=Path)
    q.add_argument("--gpx", required=True, type=Path)
    q.add_argument("--video-start", required=True, help="ISO-время первого кадра, напр. 2026-09-20T10:00:03+03:00")
    q.add_argument("--every", type=float, default=1.0, help="шаг между кадрами, с")
    q.add_argument("--out", required=True, type=Path)

    b = sub.add_parser("bench", help="сравнить модели")
    b.add_argument("--refs", required=True, type=Path)
    b.add_argument("--queries", required=True, type=Path)
    b.add_argument("--models", required=True, help="через запятую, напр. eigenplaces-r50,salad-dinov2")
    b.add_argument("--out", required=True, type=Path)
    b.add_argument("--device", default=None)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.command == "fetch-refs":
        token = os.environ.get("MAPILLARY_TOKEN")
        if not token:
            print("error: set MAPILLARY_TOKEN environment variable", file=sys.stderr)
            return 2
        if not args.bbox and not args.gpx:
            print("error: specify at least one of --bbox or --gpx", file=sys.stderr)
            return 2
        corridor = None
        if args.gpx:
            tracks = [parse_gpx(p) for p in args.gpx]
            corridor = Corridor(tracks, args.buffer_m)
        bbox = BBox.parse(args.bbox) if args.bbox else corridor.bbox()
        places = build_reference_db(
            MapillaryClient(token), bbox, args.out,
            ViewConfig(n_views=args.views, fov_deg=args.fov, width=args.width, height=args.height),
            corridor=corridor,
            workers=args.workers,
        )
        print(f"{len(places)} reference images -> {args.out / 'refs.csv'}")
        return 0

    if args.command == "extract-queries":
        track = parse_gpx(args.gpx)
        start = datetime.fromisoformat(args.video_start).timestamp()
        places = extract_query_frames(args.video, track, start, args.every, args.out)
        print(f"{len(places)} query frames -> {args.out / 'queries.csv'}")
        return 0

    results, meta = run_benchmark(
        [m.strip() for m in args.models.split(",") if m.strip()],
        args.refs, args.queries, device=args.device or pick_device(),
    )
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "report.md").write_text(render_report(results, meta))
    (args.out / "results.json").write_text(
        json.dumps({"results": [asdict(r) for r in results], "meta": meta}, ensure_ascii=False, indent=2)
    )
    print(f"report -> {args.out / 'report.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
