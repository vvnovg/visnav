"""Оценка ведения по маршруту (M3a): подсказки вовремя, ложные перестроения, прибытие — по GPS-треку журнала."""
from __future__ import annotations

import bisect
import json
import math
from dataclasses import dataclass, field
from pathlib import Path

from vpr_bench.fieldlog import FieldFrame, gps_track
from vpr_bench.geo import M_PER_DEG_LAT, TrackPoint, haversine_m, interpolate_track
from vpr_bench.query import clean_track

TARGET_PCT = 95.0


@dataclass(frozen=True)
class ManeuverCheck:
    route_idx: int
    maneuver: int
    type: str
    reached_t: float
    in_window: bool
    prompt_lead_s: float | None


@dataclass(frozen=True)
class NavResult:
    checks: list[ManeuverCheck]
    n_routes: int
    n_reroutes: int
    false_reroutes: int
    arrived: bool
    end_dist_m: float
    n_maneuvers: int = 0
    n_not_driven: int = 0
    excluded: list[ManeuverCheck] = field(default_factory=list)
    unverifiable_reroutes: int = 0


def read_nav(path: Path) -> tuple[dict, list[dict]]:
    lines = [l for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    if not lines:
        raise ValueError(f"{path}: empty nav log")
    header = json.loads(lines[0])
    if header.get("type") != "nav":
        raise ValueError(f"{path}: not a nav log")
    return header, [json.loads(l) for l in lines[1:]]


def _dist_to_polyline(lat: float, lon: float, poly: list[list[float]]) -> float:
    kx = M_PER_DEG_LAT * math.cos(math.radians(lat))
    best = math.inf
    pts = [((p[1] - lon) * kx, (p[0] - lat) * M_PER_DEG_LAT) for p in poly]
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        dx, dy = bx - ax, by - ay
        l2 = dx * dx + dy * dy
        t = 0.0 if l2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / l2))
        best = min(best, math.hypot(ax + t * dx, ay + t * dy))
    if len(pts) == 1:
        best = math.hypot(*pts[0])
    return best


def _ref_pos(track: list[TrackPoint], t: float, max_gap_s: float = 5.0) -> tuple[float, float] | None:
    """Эталонная позиция в момент t; None, если нет трека или соседние точки дальше max_gap_s друг от друга."""
    pos = interpolate_track(track, t)
    if pos is None:
        return None
    times = [p.t for p in track]
    i = bisect.bisect_left(times, t)
    if times[i] != t and track[i].t - track[i - 1].t > max_gap_s:
        return None
    return pos


def _chainage_track(poly: list[list[float]], samples: list[TrackPoint], on_route_m: float
                    ) -> list[tuple[float, float]]:
    """(t, chainage) для выборок, лежащих на маршруте; хода по ломаной монотонный (окно вокруг прошлой цепочки)."""
    if len(poly) < 2:
        return []
    lat0, lon0 = poly[0][0], poly[0][1]
    kx = M_PER_DEG_LAT * math.cos(math.radians(lat0))
    enu = lambda lat, lon: ((lon - lon0) * kx, (lat - lat0) * M_PER_DEG_LAT)  # noqa: E731
    pts = [enu(p[0], p[1]) for p in poly]
    segs = []  # (ax, ay, dx, dy, length, start chainage)
    acc = 0.0
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        ln = math.hypot(bx - ax, by - ay)
        segs.append((ax, ay, bx - ax, by - ay, ln, acc))
        acc += ln
    out: list[tuple[float, float]] = []
    c_prev: float | None = None
    xy_prev: tuple[float, float] | None = None
    for p in samples:
        x, y = enu(p.lat, p.lon)
        if c_prev is None or xy_prev is None:
            lo, hi = 0.0, acc
        else:
            ds = math.hypot(x - xy_prev[0], y - xy_prev[1])
            lo, hi = c_prev - 15.0, c_prev + max(200.0, 5.0 * ds)
        best_d, best_c = math.inf, 0.0
        for ax, ay, dx, dy, ln, s0 in segs:
            if ln == 0:
                continue
            tmin, tmax = max(0.0, (lo - s0) / ln), min(1.0, (hi - s0) / ln)
            if tmin > tmax:
                continue
            t = max(tmin, min(tmax, ((x - ax) * dx + (y - ay) * dy) / (ln * ln)))
            d = math.hypot(ax + t * dx - x, ay + t * dy - y)
            if d < best_d:
                best_d, best_c = d, s0 + t * ln
        if best_d <= on_route_m:
            out.append((p.t, best_c))
            c_prev, xy_prev = best_c, (x, y)
    return out


def evaluate_nav(header: dict, events: list[dict], log_frames: list[FieldFrame], lead_s: float = 3.0,
                 on_route_m: float = 15.0, window_lead_s: float = 10.0) -> NavResult:
    track, _ = clean_track(gps_track(log_frames), max_speed_mps=70.0, max_hdop=None)
    windows = [tuple(w) for k in ("outages", "jams", "spoofs") for w in header.get(k, [])]
    t0 = (header.get("session_started_ms") or 0) / 1000.0
    routes = [(i, e) for i, e in enumerate(events) if e.get("ev") == "route"]
    checks: list[ManeuverCheck] = []
    excluded: list[ManeuverCheck] = []
    n_man = n_not_driven = 0
    false_rr = unverifiable = 0
    for ri, (pos, rt) in enumerate(routes):
        t_start = rt["t_ms"] / 1000.0
        t_end = routes[ri + 1][1]["t_ms"] / 1000.0 if ri + 1 < len(routes) else math.inf
        if rt.get("reroute") and ri > 0:
            here = _ref_pos(track, t_start)
            if here is None:
                unverifiable += 1
            elif _dist_to_polyline(here[0], here[1], routes[ri - 1][1]["polyline"]) <= on_route_m:
                false_rr += 1
        prompts = [e for e in events[pos + 1:] if e.get("ev") == "prompt" and e["t_ms"] / 1000.0 < t_end]
        chain = _chainage_track(rt["polyline"], [p for p in track if t_start <= p.t < t_end], on_route_m)
        for mi, m in enumerate(rt["maneuvers"]):
            if m["type"] in ("depart", "arrive"):
                continue
            n_man += 1
            reached = None
            for (ta, ca), (tb, cb) in zip(chain, chain[1:]):
                if ca < m["at_m"] <= cb:
                    reached = ta + (m["at_m"] - ca) / (cb - ca) * (tb - ta)
                    break
            if reached is None:
                n_not_driven += 1
                continue
            ok = [p["t_ms"] / 1000.0 for p in prompts if mi in (p["maneuver"], p.get("then")) and p["stage"] in ("near", "now")
                  and p["t_ms"] / 1000.0 <= reached]
            lead = reached - min(ok) if ok else None
            r_ms = reached * 1000.0
            in_win = any(s <= r_ms and e >= r_ms - window_lead_s * 1000.0 for s, e in windows)
            chk = ManeuverCheck(ri, mi, m["type"], reached - t0, in_win, lead)
            (excluded if reached - t_start < lead_s else checks).append(chk)
    dest = header.get("dest")
    end = track[-1] if track else None
    end_dist = haversine_m(end.lat, end.lon, dest[0], dest[1]) if end and dest else math.nan
    return NavResult(checks, len(routes), sum(1 for _, r in routes if r.get("reroute")), false_rr,
                     any(e.get("ev") == "arrive" for e in events), end_dist, n_man, n_not_driven, excluded,
                     unverifiable)


def _share(cs: list[ManeuverCheck], lead_s: float) -> float:
    if not cs:
        return math.nan
    return 100.0 * sum(1 for c in cs if c.prompt_lead_s is not None and c.prompt_lead_s >= lead_s) / len(cs)


def _verdict(v: float) -> str:
    return "⚠️ нет данных" if math.isnan(v) else ("✅" if v >= TARGET_PCT else "❌")


def render_nav_report(r: NavResult, lead_s: float = 3.0, window_lead_s: float = 10.0) -> str:
    all_pct = _share(r.checks, lead_s)
    win = [c for c in r.checks if c.in_window]
    win_pct = _share(win, lead_s)
    fmt = lambda v: "—" if math.isnan(v) else f"{v:.1f} %"  # noqa: E731
    lines = [
        "# Подсказки о манёврах (M3a)", "",
        f"- Маршрутов: {r.n_routes}, перестроений: {r.n_reroutes}, из них ложных: {r.false_reroutes} — "
        f"{'✅' if r.false_reroutes == 0 else '❌'}",
        f"- Непроверяемых перестроений (нет эталонной позиции): {r.unverifiable_reroutes}",
        f"- Манёвров в маршрутах: {r.n_maneuvers}, пройдено: {len(r.checks) + len(r.excluded)}, "
        f"не пройдено: {r.n_not_driven}, исключено (слишком близко к началу маршрута): {len(r.excluded)}",
        f"- Учтено манёвров: {len(r.checks)}; подсказка вовремя (≥ {lead_s:.0f} с): {fmt(all_pct)} — "
        f"{_verdict(all_pct)} (цель ≥ {TARGET_PCT:.0f} %)",
        f"- В пропаданиях и подмешанных окнах (манёвр в окне или не позже {window_lead_s:.0f} с после его начала): {len(win)} манёвров, вовремя: {fmt(win_pct)} — {_verdict(win_pct)}",
        f"- Прибытие: {'✅' if r.arrived else '❌'}"
        + (f" (конец трека в {r.end_dist_m:.0f} м от цели)" if not math.isnan(r.end_dist_m) else ""),
    ]
    late = [c for c in r.checks if c.prompt_lead_s is None or c.prompt_lead_s < lead_s]
    if late:
        lines += ["", "## Поздние или пропущенные подсказки", "", "| маршрут | манёвр | тип | t, с | опережение, с |",
                  "|---|---|---|---|---|"]
        lines += [f"| {c.route_idx} | {c.maneuver} | {c.type} | {c.reached_t:.0f} | "
                  f"{'—' if c.prompt_lead_s is None else f'{c.prompt_lead_s:.1f}'} |" for c in late]
    if r.excluded:
        lines += ["", f"## Исключены (пройдены менее чем через {lead_s:.0f} с после начала маршрута)", "",
                  "| маршрут | манёвр | тип | t, с |", "|---|---|---|---|"]
        lines += [f"| {c.route_idx} | {c.maneuver} | {c.type} | {c.reached_t:.0f} |" for c in r.excluded]
    lines += ["", "Дорожные данные © участники OpenStreetMap, ODbL 1.0.", ""]
    return "\n".join(lines)
