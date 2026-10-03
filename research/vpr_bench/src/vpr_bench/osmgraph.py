"""Дорожный граф из OSM: отбор проезжих дорог, направление, тоннели и мосты, разбиение на рёбра-отрезки."""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

import numpy as np

from vpr_bench.geo import BBox
from vpr_bench.roadpack import (
    DEFAULT_SPEED_KMH, FLAG_BRIDGE, FLAG_ONEWAY, FLAG_ROUNDABOUT, FLAG_TUNNEL, KIND_NO, KIND_ONLY, RoadGraph)

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


@dataclass(frozen=True)
class RawRestriction:
    from_way: int
    via_node: int  # OSM id узла
    to_way: int
    kind: int      # KIND_NO | KIND_ONLY


_RU_SPEED = {"RU:urban": 60, "RU:rural": 90, "RU:motorway": 110, "RU:living_street": 20}


def _parse_maxspeed(v: str | None) -> float | None:
    if not v:
        return None
    if v in _RU_SPEED:
        return float(_RU_SPEED[v])
    m = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*(km/h|kmh|mph)?\s*", v)
    if not m:
        return None
    x = float(m.group(1))
    return x * 1.609344 if m.group(2) == "mph" else x


def speed_kmh(tags: dict[str, str], cls: int) -> int:
    """Скорость для оценки времени в пути: 0,8 от maxspeed (не больше 110), иначе по классу дороги."""
    ms = _parse_maxspeed(tags.get("maxspeed"))
    if ms is None:
        return DEFAULT_SPEED_KMH[cls]
    return max(5, round(min(ms, 110.0) * 0.8))


def street_name(tags: dict[str, str]) -> str | None:
    return tags.get("name") or tags.get("ref") or None


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
    if tags.get("junction") in ("roundabout", "circular"):
        f |= FLAG_ROUNDABOUT
    return f


def build_graph(ways: Iterable[RawWay], keep: Callable[[float, float], bool] | None = None,
                restrictions: Iterable[RawRestriction] = ()) -> RoadGraph:
    index: dict[int, int] = {}
    lats: list[float] = []
    lons: list[float] = []
    kept: dict[int, bool] = {}
    e_from: list[int] = []
    e_to: list[int] = []
    e_way: list[int] = []
    e_flags: list[int] = []
    e_cls: list[int] = []
    e_speed: list[int] = []
    e_name: list[int] = []
    names: list[str] = []
    name_index: dict[str, int] = {}

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
        speed = speed_kmh(w.tags, cls)
        name = street_name(w.tags)
        if name is None:
            name_idx = -1
        else:
            if name not in name_index:
                name_index[name] = len(names)
                names.append(name)
            name_idx = name_index[name]
        nodes = w.nodes if d >= 0 else list(reversed(w.nodes))
        for a, b in zip(nodes, nodes[1:]):
            if a[0] == b[0] or not (inside(a) or inside(b)):
                continue
            e_from.append(idx(a))
            e_to.append(idx(b))
            e_way.append(w.id)
            e_flags.append(flags)
            e_cls.append(cls)
            e_speed.append(speed)
            e_name.append(name_idx)
    edges_of_way: dict[int, list[int]] = {}
    for k, wid in enumerate(e_way):
        edges_of_way.setdefault(wid, []).append(k)
    rows: list[tuple[int, int, int, int]] = []
    dropped = 0

    def candidates(way_id: int, via: int, arriving: bool) -> list[int]:
        # Односторонняя линия: въезжает в via только по ребру, которое в него приходит, выезжает — по уходящему.
        out: list[int] = []
        for k in edges_of_way.get(way_id, []):
            if e_flags[k] & FLAG_ONEWAY:
                if (e_to[k] if arriving else e_from[k]) == via:
                    out.append(k)
            elif e_from[k] == via or e_to[k] == via:
                out.append(k)
        return out

    for r in restrictions:
        via = index.get(r.via_node)
        if via is None or r.from_way not in edges_of_way or r.to_way not in edges_of_way:
            continue  # вне графа: молча пропускаем, не считаем
        fes = candidates(r.from_way, via, True)
        tes = candidates(r.to_way, via, False)
        if not fes and not tes:
            continue
        # Неоднозначно внутри графа (линия проходит через via или у одной стороны нет кандидатов):
        # запрет отбрасываем и считаем — потерянный запрет безопаснее неверного.
        if len(fes) != 1 or len(tes) != 1:
            dropped += 1
            continue
        rows.append((fes[0], via, tes[0], r.kind))
    return RoadGraph(
        np.array(lats, dtype=np.float64), np.array(lons, dtype=np.float64), np.array(e_way, dtype=np.int64),
        np.array(e_from, dtype=np.int32), np.array(e_to, dtype=np.int32),
        np.array(e_flags, dtype=np.uint8), np.array(e_cls, dtype=np.uint8),
        edge_speed=np.array(e_speed, dtype=np.uint8), edge_name=np.array(e_name, dtype=np.int32),
        names=tuple(names), restrictions=np.array(rows, dtype=np.int32).reshape(len(rows), 4), restrictions_dropped=dropped,
    )


def _in_bbox(b: BBox, lat: float, lon: float) -> bool:
    return b.min_lat <= lat <= b.max_lat and b.min_lon <= lon <= b.max_lon


def read_ways(path: Path, bbox: BBox | None = None) -> list[RawWay]:
    return read_osm(path, bbox)[0]


def read_osm(path: Path, bbox: BBox | None = None) -> tuple[list[RawWay], list[RawRestriction]]:
    """Проезжие линии и запреты поворотов из .osm.pbf/.osm. Узел без координат разрывает линию на части."""
    import osmium  # необязательная зависимость: uv sync --extra osm

    out: list[RawWay] = []
    rs: list[RawRestriction] = []

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

        def relation(self, r) -> None:
            tags = {t.k: t.v for t in r.tags}
            if tags.get("type") != "restriction":
                return
            if "motorcar" in tags.get("except", ""):
                return
            value = tags.get("restriction") or tags.get("restriction:motorcar") or ""
            kind = KIND_NO if value.startswith("no_") else KIND_ONLY if value.startswith("only_") else 0
            if not kind:
                return
            roles: dict[str, list[tuple[str, int]]] = {}
            for m in r.members:
                roles.setdefault(m.role, []).append((m.type, m.ref))
            fr, via, to = roles.get("from", []), roles.get("via", []), roles.get("to", [])
            if len(fr) == 1 and len(via) == 1 and len(to) == 1 and fr[0][0] == "w" and via[0][0] == "n" \
                    and to[0][0] == "w":
                rs.append(RawRestriction(fr[0][1], via[0][1], to[0][1], kind))

    Handler().apply_file(str(path), locations=True)
    return out, rs
