"""CLI этапа M2: vpr-m2 replay-eval."""
from __future__ import annotations

import argparse
import sys
from datetime import datetime, timezone
from pathlib import Path

from vpr_bench.fieldlog import gps_track, read_log
from vpr_bench.monitoreval import evaluate_monitor, render_monitor_report
from vpr_bench.replayeval import evaluate_replay, read_trajectory, render_replay_report
from vpr_bench.roadeval import evaluate_roads, render_road_report
from vpr_bench.roadmatch import RoadNet
from vpr_bench.roadpack import read_roadpack, write_roadpack


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
    pr = sub.add_parser("pack-roads", help="упаковать дорожный граф OSM в коридоре вокруг треков (roadpack)")
    pr.add_argument("--pbf", required=True, type=Path, help="выгрузка OSM (.osm.pbf или .osm)")
    pr.add_argument("--gpx", action="append", default=[], type=Path, help="трек поездки GPX")
    pr.add_argument("--log", action="append", default=[], type=Path, help="журнал кадров сессии (.jsonl): его GPS-трек")
    pr.add_argument("--bbox", default=None, help="min_lon,min_lat,max_lon,max_lat — граф всего района без обрезки по треку")
    pr.add_argument("--buffer-m", type=float, default=300.0)
    pr.add_argument("--out", required=True, type=Path)
    rd = sub.add_parser("road-eval", help="оценить привязку к дорогам (NFR-3)")
    rd.add_argument("--traj", required=True, type=Path)
    rd.add_argument("--log", required=True, type=Path, help="журнал кадров сессии (.jsonl) — источник GPS")
    rd.add_argument("--roads", required=True, type=Path, help="каталог roadpack")
    rd.add_argument("--out", required=True, type=Path)
    return parser


def _pack_roads(args) -> int:
    from vpr_bench.db_builder import Corridor
    from vpr_bench.geo import BBox
    from vpr_bench.osmgraph import build_graph, read_osm
    from vpr_bench.query import clean_track, parse_gpx

    if args.bbox is not None:
        if args.gpx or args.log:
            print("error: --bbox cannot be combined with --gpx or --log", file=sys.stderr)
            return 2
        try:
            bbox = BBox.parse(args.bbox)
        except ValueError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        ways, rs = read_osm(args.pbf, bbox)
        graph = build_graph(ways, keep=None, restrictions=rs)
        extra = {"bbox": [bbox.min_lon, bbox.min_lat, bbox.max_lon, bbox.max_lat], "buffer_m": None}
        return _write_roads(args, graph, extra, "error: no drivable roads in the bbox")
    tracks = [parse_gpx(p) for p in args.gpx]
    # Трек из журнала очищаем от подмены, иначе коридор уедет к точке, куда «прыгал» GPS.
    tracks += [clean_track(gps_track(read_log(p)[1]), max_hdop=None)[0] for p in args.log]
    tracks = [t for t in tracks if t]
    if not tracks:
        print("error: need at least one non-empty --gpx or --log track", file=sys.stderr)
        return 2
    corridor = Corridor(tracks, args.buffer_m)
    ways, rs = read_osm(args.pbf, corridor.bbox())
    graph = build_graph(ways, keep=corridor.contains, restrictions=rs)
    return _write_roads(args, graph, {"buffer_m": args.buffer_m}, "error: no drivable roads in the corridor")


def _write_roads(args, graph, extra: dict, empty_error: str) -> int:
    if len(graph.edge_from) == 0:
        print(empty_error, file=sys.stderr)
        return 2
    meta = {
        "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": args.pbf.name, **extra,
        "attribution": "© участники OpenStreetMap, ODbL 1.0",
        "restrictions_dropped": graph.restrictions_dropped,
        "boundary_nodes": len(graph.boundary_nodes),
    }
    write_roadpack(args.out, graph, meta)
    print(f"nodes={len(graph.node_lats)} edges={len(graph.edge_from)} boundary={len(graph.boundary_nodes)} -> {args.out}")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "pack-roads":
        return _pack_roads(args)
    is_monitor = args.command == "monitor-eval"
    is_road = args.command == "road-eval"
    header, rows = read_trajectory(args.traj, keep_null_pos=is_monitor, allow_fusion=is_monitor or is_road)
    log_header, frames = read_log(args.log)
    if log_header.get("started_ms") != header.get("session_started_ms"):
        print("error: trajectory and log are from different sessions", file=sys.stderr)
        return 2
    if is_road:
        graph, meta = read_roadpack(args.roads)
        if header.get("roads") is not None and header["roads"] != meta.get("created_at"):
            print("error: trajectory was made with a different roadpack", file=sys.stderr)
            return 2
        res = evaluate_roads(header, rows, frames, RoadNet(graph))
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(render_road_report(res))
        print(f"correct={res.correct_pct:.1f} % rows={res.n_rows} -> {args.out}")
        return 0
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
