"""Дорожный граф из OSM: отбор проезжих дорог, направление, тоннели и мосты, разбиение на рёбра-отрезки."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

import numpy as np

from vpr_bench.geo import BBox
from vpr_bench.roadpack import FLAG_BRIDGE, FLAG_ONEWAY, FLAG_TUNNEL, RoadGraph

ROAD_CLASS = {
    "motorway": 1, "motorway_link": 1, "trunk": 2, "trunk_link": 2, "primary": 3, "primary_link": 3,
    "secondary": 4, "secondary_link": 4, "tertiary": 5, "tertiary_link": 5, "unclassified": 6,
    "residential": 7, "living_street": 8, "service": 9,
}
_NO_ACCESS = {"no", "private"}
_EXCLUDED_SERVICE = {"parking_aisle", "drive-through", "emergency_access"}


@dataclass(frozen=True)
class RawWay:
    id: int
    tags: dict[str, str]
    nodes: list[tuple[int, float, float]]  # (osm id, lat, lon)


def road_class(tags: dict[str, str]) -> int | None:
    cls = ROAD_CLASS.get(tags.get("highway", ""))
    if cls is None or tags.get("area") == "yes":
        return None
    if tags.get("access") in _NO_ACCESS or tags.get("motor_vehicle") in _NO_ACCESS:
        return None
    if cls == ROAD_CLASS["service"] and tags.get("service") in _EXCLUDED_SERVICE:
        return None
    return cls


def direction(tags: dict[str, str]) -> int:
    """1 — ехать можно только по ходу линии, -1 — только против, 0 — в обе стороны."""
    ow = tags.get("oneway", "")
    if ow in ("yes", "true", "1"):
        return 1
    if ow == "-1":
        return -1
    if ow == "no":
        return 0
    if tags.get("junction") in ("roundabout", "circular") or tags.get("highway") == "motorway":
        return 1
    return 0


def _flags(tags: dict[str, str], d: int) -> int:
    f = FLAG_ONEWAY if d != 0 else 0
    if tags.get("tunnel") in ("yes", "building_passage") or tags.get("covered") == "yes":
        f |= FLAG_TUNNEL
    if tags.get("bridge") not in (None, "no"):
        f |= FLAG_BRIDGE
    return f


def build_graph(ways: Iterable[RawWay], keep: Callable[[float, float], bool] | None = None) -> RoadGraph:
    index: dict[int, int] = {}
    lats: list[float] = []
    lons: list[float] = []
    kept: dict[int, bool] = {}
    e_from: list[int] = []
    e_to: list[int] = []
    e_way: list[int] = []
    e_flags: list[int] = []
    e_cls: list[int] = []

    def inside(node: tuple[int, float, float]) -> bool:
        if keep is None:
            return True
        if node[0] not in kept:
            kept[node[0]] = bool(keep(node[1], node[2]))
        return kept[node[0]]

    def idx(node: tuple[int, float, float]) -> int:
        if node[0] not in index:
            index[node[0]] = len(lats)
            lats.append(node[1])
            lons.append(node[2])
        return index[node[0]]

    for w in ways:
        cls = road_class(w.tags)
        if cls is None or len(w.nodes) < 2:
            continue
        d = direction(w.tags)
        flags = _flags(w.tags, d)
        nodes = w.nodes if d >= 0 else list(reversed(w.nodes))
        for a, b in zip(nodes, nodes[1:]):
            if a[0] == b[0] or not (inside(a) or inside(b)):
                continue
            e_from.append(idx(a))
            e_to.append(idx(b))
            e_way.append(w.id)
            e_flags.append(flags)
            e_cls.append(cls)
    return RoadGraph(
        np.array(lats, dtype=np.float64), np.array(lons, dtype=np.float64), np.array(e_way, dtype=np.int64),
        np.array(e_from, dtype=np.int32), np.array(e_to, dtype=np.int32),
        np.array(e_flags, dtype=np.uint8), np.array(e_cls, dtype=np.uint8),
    )


def _in_bbox(b: BBox, lat: float, lon: float) -> bool:
    return b.min_lat <= lat <= b.max_lat and b.min_lon <= lon <= b.max_lon


def read_ways(path: Path, bbox: BBox | None = None) -> list[RawWay]:
    """Проезжие линии из .osm.pbf/.osm. Узел без координат разрывает линию на части."""
    import osmium  # необязательная зависимость: uv sync --extra osm

    out: list[RawWay] = []

    def flush(way_id: int, tags: dict[str, str], run: list[tuple[int, float, float]]) -> None:
        if len(run) >= 2 and (bbox is None or any(_in_bbox(bbox, la, lo) for _, la, lo in run)):
            out.append(RawWay(way_id, tags, list(run)))

    class Handler(osmium.SimpleHandler):
        def __init__(self) -> None:
            super().__init__()

        def way(self, w) -> None:
            if "highway" not in w.tags:
                return
            tags = {t.k: t.v for t in w.tags}
            if road_class(tags) is None:
                return
            run: list[tuple[int, float, float]] = []
            for n in w.nodes:
                if not n.location.valid():
                    flush(w.id, tags, run)
                    run = []
                    continue
                run.append((n.ref, n.location.lat, n.location.lon))
            flush(w.id, tags, run)

    Handler().apply_file(str(path), locations=True)
    return out
