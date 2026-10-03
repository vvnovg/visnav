"""Оценка привязки к дорогам (NFR-3): доля времени движения на правильной дороге относительно офлайн-эталона."""
from __future__ import annotations

import bisect
import math
from dataclasses import dataclass

from vpr_bench.fieldlog import FieldFrame, gps_track
from vpr_bench.geo import haversine_m
from vpr_bench.query import clean_track, pose_at
from vpr_bench.replayeval import TrajRow
from vpr_bench.roadmatch import RoadNet, Snap, match_track

TARGET_PCT = 98.0
FAR_TRUTH_M = 15.0  # эталон дальше от дороги — вероятно, истинной дороги нет в OSM


@dataclass(frozen=True)
class RoadResult:
    has_road_fields: bool
    roads: str | None
    road_constraint: bool | None
    n_rows: int
    correct_pct: float
    n_window: int
    window_correct_pct: float
    off_road_pct: float
    used_pct: float
    no_truth_rows: int
    no_truth_pct: float  # доля строк в движении без эталонной привязки
    far_truth_pct: float  # доля оценённых строк, где эталонная точка GPS дальше FAR_TRUTH_M от своей дороги
    wrong_spans: list[tuple[float, float]]  # (начало, с от старта сессии; длительность, с)


def _pct(k: int, n: int) -> float:
    return 100.0 * k / n if n else float("nan")


def _truth_at(times: list[float], snaps: list[Snap | None], t: float) -> Snap | None:
    i = bisect.bisect_left(times, t)
    best = None
    for j in (i - 1, i):
        if 0 <= j < len(times) and abs(times[j] - t) <= 1.0:
            if best is None or abs(times[j] - t) < abs(times[best] - t):
                best = j
    return None if best is None else snaps[best]


def _ways_near(times: list[float], snaps: list[Snap | None], t: float, window_s: float) -> set[int]:
    """OSM way всех эталонных привязок в окне ±window_s: устойчивость к разбиению дороги на несколько way."""
    lo, hi = bisect.bisect_left(times, t - window_s), bisect.bisect_right(times, t + window_s)
    return {s.way_id for s in snaps[lo:hi] if s is not None}


def evaluate_roads(header: dict, rows: list[TrajRow], log_frames: list[FieldFrame], net: RoadNet,
                   min_speed_mps: float = 2.0, tol_m: float = 5.0, window_s: float = 1.5) -> RoadResult:
    has = header.get("roads") is not None
    roads, constraint = header.get("roads"), header.get("road_constraint")
    if not has:
        nan = float("nan")
        return RoadResult(False, roads, constraint, 0, nan, 0, nan, nan, nan, 0, nan, nan, [])
    track, _ = clean_track(gps_track(log_frames), max_speed_mps=70.0, max_hdop=None)
    snaps = match_track(net, track)
    times = [p.t for p in track]
    t0 = (header.get("session_started_ms") or 0) / 1000.0
    n = ok = nw = okw = off = used = no_truth = far = 0
    spans: list[list[float]] = []
    for r in rows:
        t = r.t_ms / 1000.0
        pose = pose_at(track, t)
        if pose is None or pose[3] < min_speed_mps:
            continue
        truth = _truth_at(times, snaps, t)
        if truth is None:
            no_truth += 1
            continue
        n += 1
        far += truth.dist_m > FAR_TRUTH_M
        used += bool(r.road_used)
        if r.way_id is None:
            off += 1
            good = False
        else:
            good = r.way_id in _ways_near(times, snaps, t, window_s) or (
                r.road_lat is not None and r.road_lon is not None
                and haversine_m(r.road_lat, r.road_lon, truth.lat, truth.lon) <= tol_m)
        ok += good
        if r.outage or r.injected is not None:
            nw += 1
            okw += good
        if not good:
            if spans and t - spans[-1][1] <= 1.5:
                spans[-1][1] = t
            else:
                spans.append([t, t])
    wrong = sorted(((s - t0, e - s) for s, e in spans), key=lambda x: -x[1])[:5]
    return RoadResult(True, roads, constraint, n, _pct(ok, n), nw, _pct(okw, nw), _pct(off, n), _pct(used, n),
                      no_truth, _pct(no_truth, n + no_truth), _pct(far, n), wrong)


def _fmt(v: float) -> str:
    return "—" if math.isnan(v) else f"{v:.1f} %"


def _verdict(v: float) -> str:
    if math.isnan(v):
        return "⚠️ нет данных"
    return "✅" if v >= TARGET_PCT else "❌"


def render_road_report(r: RoadResult) -> str:
    c = {True: "включено", False: "выключено"}.get(r.road_constraint, "неизвестно")
    lines = ["# Привязка к дорогам (NFR-3)", "", f"- Граф дорог: {r.roads or 'нет'}; подсказка фильтру дорогой: {c}"]
    if not r.has_road_fields:
        lines += ["", "⚠️ нет данных: траектория без привязки к дорогам (replay без --roads?)"]
    else:
        lines += [
            f"- Строк в движении с эталоном дороги: {r.n_rows} (без эталонной привязки: {r.no_truth_rows})",
            f"- Без эталонной привязки: {_fmt(r.no_truth_pct)} строк в движении",
            f"- Эталон далеко от дороги (> {FAR_TRUTH_M:.0f} м, вероятно дороги нет в OSM): {_fmt(r.far_truth_pct)}",
            f"- Правильная дорога: {_fmt(r.correct_pct)} — {_verdict(r.correct_pct)} (цель ≥ {TARGET_PCT:.0f} %)",
            f"- В пропаданиях и подмешанных окнах: {_fmt(r.window_correct_pct)} из {r.n_window} строк — "
            f"{_verdict(r.window_correct_pct)}",
            f"- Без привязки (вне дорог): {_fmt(r.off_road_pct)}",
            f"- Подсказка фильтру применялась: {_fmt(r.used_pct)} строк",
        ]
        if r.wrong_spans:
            lines += ["", "## Самые длинные отрезки с неверной дорогой", "",
                      "| начало, с от старта | длительность, с |", "|---|---|"]
            lines += [f"| {s:.0f} | {d:.1f} |" for s, d in r.wrong_spans]
        lines += ["", "Эталон — офлайн-привязка очищенного GPS-трека к тому же графу (HMM с обратным проходом). "
                      "Строка верна, если OSM way совпадает с way любой эталонной привязки в пределах ±1.5 с или точка на дороге "
                      "ближе 5 м к эталонной."]
    lines += ["", "Дорожные данные © участники OpenStreetMap, ODbL 1.0.", ""]
    return "\n".join(lines)
