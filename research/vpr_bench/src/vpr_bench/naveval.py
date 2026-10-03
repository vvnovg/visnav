"""Оценка ведения по маршруту (M3a): подсказки вовремя, ложные перестроения, прибытие — по GPS-треку журнала."""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path

from vpr_bench.fieldlog import FieldFrame, gps_track
from vpr_bench.geo import M_PER_DEG_LAT, haversine_m, interpolate_track
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


def evaluate_nav(header: dict, events: list[dict], log_frames: list[FieldFrame], reach_m: float = 20.0,
                 lead_s: float = 3.0, on_route_m: float = 15.0) -> NavResult:
    track, _ = clean_track(gps_track(log_frames), max_speed_mps=70.0, max_hdop=None)
    windows = [tuple(w) for k in ("outages", "jams", "spoofs") for w in header.get(k, [])]
    t0 = (header.get("session_started_ms") or 0) / 1000.0
    routes = [(i, e) for i, e in enumerate(events) if e.get("ev") == "route"]
    checks: list[ManeuverCheck] = []
    false_rr = 0
    for ri, (pos, rt) in enumerate(routes):
        t_start = rt["t_ms"] / 1000.0
        t_end = routes[ri + 1][1]["t_ms"] / 1000.0 if ri + 1 < len(routes) else math.inf
        if rt.get("reroute") and ri > 0:
            here = interpolate_track(track, t_start)
            if here is not None and _dist_to_polyline(here[0], here[1], routes[ri - 1][1]["polyline"]) <= on_route_m:
                false_rr += 1
        prompts = [e for e in events[pos + 1:] if e.get("ev") == "prompt" and e["t_ms"] / 1000.0 < t_end]
        for mi, m in enumerate(rt["maneuvers"]):
            if m["type"] in ("depart", "arrive"):
                continue
            reached = next((p.t for p in track if t_start <= p.t < t_end
                            and haversine_m(p.lat, p.lon, m["lat"], m["lon"]) <= reach_m), None)
            if reached is None:
                continue
            ok = [p["t_ms"] / 1000.0 for p in prompts if mi in (p["maneuver"], p.get("then")) and p["stage"] in ("near", "now")
                  and p["t_ms"] / 1000.0 <= reached]
            lead = reached - min(ok) if ok else None
            in_win = any(s <= reached * 1000.0 < e for s, e in windows)
            checks.append(ManeuverCheck(ri, mi, m["type"], reached - t0, in_win, lead))
    dest = header.get("dest")
    end = track[-1] if track else None
    end_dist = haversine_m(end.lat, end.lon, dest[0], dest[1]) if end and dest else math.nan
    return NavResult(checks, len(routes), sum(1 for _, r in routes if r.get("reroute")), false_rr,
                     any(e.get("ev") == "arrive" for e in events), end_dist)


def _share(cs: list[ManeuverCheck], lead_s: float) -> float:
    if not cs:
        return math.nan
    return 100.0 * sum(1 for c in cs if c.prompt_lead_s is not None and c.prompt_lead_s >= lead_s) / len(cs)


def _verdict(v: float) -> str:
    return "⚠️ нет данных" if math.isnan(v) else ("✅" if v >= TARGET_PCT else "❌")


def render_nav_report(r: NavResult, lead_s: float = 3.0) -> str:
    all_pct = _share(r.checks, lead_s)
    win = [c for c in r.checks if c.in_window]
    win_pct = _share(win, lead_s)
    fmt = lambda v: "—" if math.isnan(v) else f"{v:.1f} %"  # noqa: E731
    lines = [
        "# Подсказки о манёврах (M3a)", "",
        f"- Маршрутов: {r.n_routes}, перестроений: {r.n_reroutes}, из них ложных: {r.false_reroutes} — "
        f"{'✅' if r.false_reroutes == 0 else '❌'}",
        f"- Пройдено манёвров: {len(r.checks)}; подсказка вовремя (≥ {lead_s:.0f} с): {fmt(all_pct)} — "
        f"{_verdict(all_pct)} (цель ≥ {TARGET_PCT:.0f} %)",
        f"- В пропаданиях и подмешанных окнах: {len(win)} манёвров, вовремя: {fmt(win_pct)} — {_verdict(win_pct)}",
        f"- Прибытие: {'✅' if r.arrived else '❌'}"
        + (f" (конец трека в {r.end_dist_m:.0f} м от цели)" if not math.isnan(r.end_dist_m) else ""),
    ]
    late = [c for c in r.checks if c.prompt_lead_s is None or c.prompt_lead_s < lead_s]
    if late:
        lines += ["", "## Поздние или пропущенные подсказки", "", "| маршрут | манёвр | тип | t, с | опережение, с |",
                  "|---|---|---|---|---|"]
        lines += [f"| {c.route_idx} | {c.maneuver} | {c.type} | {c.reached_t:.0f} | "
                  f"{'—' if c.prompt_lead_s is None else f'{c.prompt_lead_s:.1f}'} |" for c in late]
    lines += ["", "Дорожные данные © участники OpenStreetMap, ODbL 1.0.", ""]
    return "\n".join(lines)
