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
from vpr_bench.models import MODEL_SPECS, pick_device
from vpr_bench.pipeline import run_benchmark
from vpr_bench.query import extract_query_frames, parse_gpx
from vpr_bench.report import render_report


def parse_queries(specs: list[str]) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for spec in specs:
        if "=" in spec:
            name, _, path = spec.partition("=")
        else:
            path = spec
            name = Path(spec).parent.name
        if name in result:
            raise ValueError(f"duplicate --queries session name {name!r}")
        result[name] = Path(path)
    return result


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
    b.add_argument(
        "--queries", action="append", default=[], required=True,
        help="NAME=PATH (можно несколько раз); голый PATH берёт имя из родительского каталога",
    )
    b.add_argument("--models", required=True, help="через запятую, напр. eigenplaces-r50,salad-dinov2")
    b.add_argument("--out", required=True, type=Path)
    b.add_argument("--device", default=None)
    b.add_argument(
        "--pool", action="append", default=[],
        help="SESSION,SESSION,... — объединить сессии для сводной оценки (можно несколько раз)",
    )
    b.add_argument("--decision-session", default=None, help="сессия для отметки решения M0 в отчёте")
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

    model_names = [m.strip() for m in args.models.split(",") if m.strip()]
    unknown = [m for m in model_names if m not in MODEL_SPECS]
    if not model_names or unknown:
        if unknown:
            print(
                f"error: unknown model(s) {unknown}; known models: {sorted(MODEL_SPECS)}",
                file=sys.stderr,
            )
        else:
            print(f"error: no models given; known models: {sorted(MODEL_SPECS)}", file=sys.stderr)
        return 2

    try:
        queries = parse_queries(args.queries)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    pools = [[s.strip() for s in p.split(",") if s.strip()] for p in args.pool]
    decision_session = args.decision_session
    if decision_session is None and pools:
        decision_session = "+".join(pools[0])

    def _on_model_done(results_so_far, meta_so_far):
        args.out.mkdir(parents=True, exist_ok=True)
        report_tmp = args.out / "report.md.tmp"
        report_path = args.out / "report.md"
        report_tmp.write_text(render_report(results_so_far, meta_so_far, decision_session=decision_session))
        os.replace(report_tmp, report_path)

        results_tmp = args.out / "results.json.tmp"
        results_path = args.out / "results.json"
        results_tmp.write_text(
            json.dumps(
                {"results": [asdict(r) for r in results_so_far], "meta": meta_so_far},
                ensure_ascii=False, indent=2,
            )
        )
        os.replace(results_tmp, results_path)

    results, meta = run_benchmark(
        model_names, args.refs, queries, pools=pools or None,
        device=args.device or pick_device(), on_model_done=_on_model_done,
    )
    print(f"report -> {args.out / 'report.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
