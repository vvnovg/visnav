"""Тестовые графы в метрах вокруг (LAT0, LON0); узлы с одинаковыми координатами общие."""
import numpy as np

from vpr_bench.geo import offset_m
from vpr_bench.roadpack import RoadGraph

LAT0, LON0 = 55.75, 37.6


def graph_from_lines(lines):
    """lines: [(way_id, [(e, n), ...], flags, cls)] — рёбра между соседними точками каждой линии."""
    index, lats, lons = {}, [], []
    ef, et, ew, efl, ec = [], [], [], [], []

    def idx(p):
        key = (round(p[0], 3), round(p[1], 3))
        if key not in index:
            lat, lon = offset_m(LAT0, LON0, p[0], p[1])
            index[key] = len(lats)
            lats.append(lat)
            lons.append(lon)
        return index[key]

    for way, pts, flags, cls in lines:
        for a, b in zip(pts, pts[1:]):
            ef.append(idx(a)); et.append(idx(b)); ew.append(way); efl.append(flags); ec.append(cls)
    return RoadGraph(np.array(lats), np.array(lons), np.array(ew, dtype=np.int64), np.array(ef, dtype=np.int32),
                     np.array(et, dtype=np.int32), np.array(efl, dtype=np.uint8), np.array(ec, dtype=np.uint8))


def line(e0, n0, e1, n1, step=100.0):
    k = max(1, round(((e1 - e0) ** 2 + (n1 - n0) ** 2) ** 0.5 / step))
    return [(e0 + (e1 - e0) * i / k, n0 + (n1 - n0) * i / k) for i in range(k + 1)]
