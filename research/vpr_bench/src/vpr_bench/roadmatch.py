"""Офлайн-привязка GPS-трека к дорожному графу — эталон для road-eval.

Модель та же, что у онлайн-MapMatcher на телефоне (ребро с направлением движения, переход по пути
по графу со штрафом за отсутствие пути, модель Newson–Krumm), но без курса (в журнале его нет) и
с обратным проходом Витерби по всему треку.
"""
from __future__ import annotations

import heapq
import math
from dataclasses import dataclass

import numpy as np

from vpr_bench.geo import M_PER_DEG_LAT, TrackPoint
from vpr_bench.roadpack import FLAG_ONEWAY, RoadGraph

BACKTRACK_TOLERANCE_M = 5.0
TELEPORT_PENALTY = 20.0  # как MatchConfig.teleportPenalty на телефоне


@dataclass(frozen=True)
class Snap:
    way_id: int
    lat: float
    lon: float
    dist_m: float  # расстояние от точки GPS до точки привязки на оси дороги


@dataclass(frozen=True)
class _State:
    edge: int
    forward: bool
    t: float
    pe: float
    pn: float
    dist: float


class RoadNet:
    """Граф в плоских координатах вокруг центра графа, сетка ячеек и направленные смежности."""

    def __init__(self, g: RoadGraph, cell_m: float = 50.0):
        self.g = g
        self.cell_m = cell_m
        self.lat0 = float(np.mean(g.node_lats)) if len(g.node_lats) else 0.0
        self.lon0 = float(np.mean(g.node_lons)) if len(g.node_lons) else 0.0
        self.kx = M_PER_DEG_LAT * math.cos(math.radians(self.lat0))
        self.ne = (np.asarray(g.node_lons) - self.lon0) * self.kx
        self.nn = (np.asarray(g.node_lats) - self.lat0) * M_PER_DEG_LAT
        a, b = np.asarray(g.edge_from), np.asarray(g.edge_to)
        self.length = np.hypot(self.ne[b] - self.ne[a], self.nn[b] - self.nn[a])
        self.adj: list[list[tuple[int, float]]] = [[] for _ in range(len(g.node_lats))]
        self.grid: dict[tuple[int, int], list[int]] = {}
        for k in range(len(a)):
            u, v, length = int(a[k]), int(b[k]), float(self.length[k])
            self.adj[u].append((v, length))
            if not int(g.edge_flags[k]) & FLAG_ONEWAY:
                self.adj[v].append((u, length))
            for cx in range(self._cell(min(self.ne[u], self.ne[v])), self._cell(max(self.ne[u], self.ne[v])) + 1):
                for cy in range(self._cell(min(self.nn[u], self.nn[v])), self._cell(max(self.nn[u], self.nn[v])) + 1):
                    self.grid.setdefault((cx, cy), []).append(k)

    def _cell(self, v: float) -> int:
        return math.floor(v / self.cell_m)

    def to_en(self, lat: float, lon: float) -> tuple[float, float]:
        return (lon - self.lon0) * self.kx, (lat - self.lat0) * M_PER_DEG_LAT

    def to_ll(self, e: float, n: float) -> tuple[float, float]:
        return self.lat0 + n / M_PER_DEG_LAT, self.lon0 + e / self.kx

    def project(self, k: int, e: float, n: float) -> tuple[float, float, float, float]:
        """(t, pe, pn, dist) — проекция точки на ребро k."""
        a, b = int(self.g.edge_from[k]), int(self.g.edge_to[k])
        de, dn = self.ne[b] - self.ne[a], self.nn[b] - self.nn[a]
        len2 = de * de + dn * dn
        t = 0.0 if len2 == 0 else min(1.0, max(0.0, ((e - self.ne[a]) * de + (n - self.nn[a]) * dn) / len2))
        pe, pn = self.ne[a] + t * de, self.nn[a] + t * dn
        return t, float(pe), float(pn), math.hypot(e - pe, n - pn)

    def near(self, e: float, n: float, radius: float) -> list[tuple[int, float, float, float, float]]:
        """[(edge, t, pe, pn, dist)] ближе radius, по возрастанию расстояния."""
        seen, out = set(), []
        for cx in range(self._cell(e - radius), self._cell(e + radius) + 1):
            for cy in range(self._cell(n - radius), self._cell(n + radius) + 1):
                for k in self.grid.get((cx, cy), ()):
                    if k in seen:
                        continue
                    seen.add(k)
                    t, pe, pn, d = self.project(k, e, n)
                    if d <= radius:
                        out.append((k, t, pe, pn, d))
        out.sort(key=lambda x: x[4])
        return out

    def route_from(self, node: int, limit: float) -> dict[int, float]:
        dist = {node: 0.0}
        pq = [(0.0, node)]
        while pq:
            d, u = heapq.heappop(pq)
            if d > dist.get(u, math.inf):
                continue
            for v, length in self.adj[u]:
                nd = d + length
                if nd <= limit and nd < dist.get(v, math.inf):
                    dist[v] = nd
                    heapq.heappush(pq, (nd, v))
        return dist


def _along(t: float, length: float, forward: bool) -> float:
    return t * length if forward else (1 - t) * length


def _route(net: RoadNet, a: _State, b: _State, limit: float, cache: dict[int, dict[int, float]]) -> float | None:
    la = float(net.length[a.edge])
    pos_a = _along(a.t, la, a.forward)
    if a.edge == b.edge and a.forward == b.forward:
        d = _along(b.t, la, b.forward) - pos_a
        return abs(d) if d >= -BACKTRACK_TOLERANCE_M else None
    exit_ = int(net.g.edge_to[a.edge] if a.forward else net.g.edge_from[a.edge])
    entry = int(net.g.edge_from[b.edge] if b.forward else net.g.edge_to[b.edge])
    if exit_ not in cache:
        cache[exit_] = net.route_from(exit_, limit)
    mid = cache[exit_].get(entry)
    return None if mid is None else (la - pos_a) + mid + _along(b.t, float(net.length[b.edge]), b.forward)


def match_track(net: RoadNet, track: list[TrackPoint], radius_m: float = 30.0, sigma_m: float = 5.0,
                beta_m: float = 10.0, max_gap_s: float = 5.0, max_candidates: int = 8) -> list[Snap | None]:
    """Привязка каждой точки трека; None — вне дорог (нет рёбер ближе radius_m)."""
    out: list[Snap | None] = [None] * len(track)
    chain: list[tuple[int, list[_State], list[float], list[int]]] = []  # (точка, состояния, очки, обратные ссылки)
    prev_en: tuple[float, float] | None = None
    prev_t: float | None = None

    def flush() -> None:
        if not chain:
            return
        j = int(np.argmax(chain[-1][2]))
        for i, states, _, back in reversed(chain):
            s = states[j]
            lat, lon = net.to_ll(s.pe, s.pn)
            out[i] = Snap(int(net.g.edge_way[s.edge]), lat, lon, s.dist)
            j = back[j]
        chain.clear()

    for i, p in enumerate(track):
        e, n = net.to_en(p.lat, p.lon)
        # Кандидаты: ближайшая проекция на каждую дорогу (way), а не на каждый сегмент — иначе узлы через
        # 5-15 м забивают набор рёбрами одной дороги и вытесняют истинную параллельную.
        cand: dict[int, tuple[int, float, float, float, float]] = {}
        nearest_per_way: dict[int, tuple[int, float, float, float, float]] = {}
        for c in net.near(e, n, radius_m):
            nearest_per_way.setdefault(int(net.g.edge_way[c[0]]), c)
        for c in list(nearest_per_way.values())[:max_candidates]:
            cand[c[0]] = c
        # Плюс проекции на рёбра всех состояний предыдущей точки цепочки: переход по тому же ребру не теряется.
        if chain:
            for ps in chain[-1][1]:
                if ps.edge not in cand:
                    t, pe, pn, d = net.project(ps.edge, e, n)
                    if d <= radius_m:
                        cand[ps.edge] = (ps.edge, t, pe, pn, d)
        states = [_State(k, fwd, t, pe, pn, d)
                  for k, t, pe, pn, d in cand.values()
                  for fwd in (True, False) if fwd or not int(net.g.edge_flags[k]) & FLAG_ONEWAY]
        if not states:
            flush()
            prev_en = None
            continue
        emis = [-0.5 * (s.dist / sigma_m) ** 2 for s in states]
        scores, back = emis, [-1] * len(states)
        if chain and prev_en is not None and prev_t is not None and p.t - prev_t <= max_gap_s:
            trav = math.hypot(e - prev_en[0], n - prev_en[1])
            _, pstates, pscores, _ = chain[-1]
            cache: dict[int, dict[int, float]] = {}
            limit = 2 * trav + 50.0
            new_scores, new_back = [], []
            for s, em in zip(states, emis):
                best, arg = -math.inf, -1
                for pi, (ps, psc) in enumerate(zip(pstates, pscores)):
                    r = _route(net, ps, s, limit, cache)
                    v = psc - (TELEPORT_PENALTY if r is None else min(abs(r - trav) / beta_m, TELEPORT_PENALTY))
                    if v > best:
                        best, arg = v, pi
                new_scores.append(best + em)
                new_back.append(arg)
            scores, back = new_scores, new_back
        else:
            flush()
        top = max(scores)
        chain.append((i, states, [x - top for x in scores], back))
        prev_en, prev_t = (e, n), p.t
    flush()
    return out
