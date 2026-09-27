"""Оценка replay-траектории (M2a) против отфильтрованного GPS той же поездки: NFR-1 и NFR-5."""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from vpr_bench.fieldlog import FieldFrame, gps_track
from vpr_bench.geo import haversine_m, interpolate_track
from vpr_bench.query import clean_track, has_gap, pose_at


@dataclass(frozen=True)
class TrajRow:
    t_ms: int
    lat: float
    lon: float
    sigma_m: float
    outage: bool


@dataclass(frozen=True)
class OutageResult:
    start_ms: int
    end_ms: int
    n_points: int
    distance_m: float
    final_err_m: float
    drift_pct: float


@dataclass(frozen=True)
class ReplayResult:
    visual: bool
    n_points: int
    p50_m: float
    p95_m: float
    outages: list[OutageResult]

    def has_data(self, min_outage_dist_m: float = 200.0) -> bool:
        """Check if there is data to evaluate: visual mode needs n_points > 0;
        DR mode needs at least one outage with distance >= threshold and finite drift."""
        if self.visual:
            return self.n_points > 0
        else:
            return any(o.distance_m >= min_outage_dist_m and not math.isnan(o.drift_pct) for o in self.outages)


def read_trajectory(path: Path) -> tuple[dict, list[TrajRow]]:
    lines = [l for l in path.read_text().splitlines() if l.strip()]
    header = json.loads(lines[0])
    if header.get("type") != "replay":
        raise ValueError(f"{path}: not a replay trajectory")
    rows = []
    for r in map(json.loads, lines[1:]):
        # Skip rows with null lat or lon
        if r["lat"] is None or r["lon"] is None:
            continue
        # Treat null sigma_m as NaN
        sigma_m = r["sigma_m"] if r["sigma_m"] is not None else float("nan")
        rows.append(TrajRow(int(r["t_ms"]), r["lat"], r["lon"], sigma_m, bool(r["outage"])))
    # Sort rows by t_ms
    rows.sort(key=lambda row: row.t_ms)
    return header, rows


def evaluate_replay(
    header: dict,
    rows: list[TrajRow],
    log_frames: list[FieldFrame],
    min_speed_mps: float = 2.0,
    max_gap_s: float = 3.0,
    min_outage_dist_m: float = 200.0,
) -> ReplayResult:
    track, _ = clean_track(gps_track(log_frames), max_speed_mps=70.0, max_hdop=None)
    times = [p.t for p in track]
    all_errors: list[float] = []
    outages: list[OutageResult] = []
    for start_ms, end_ms in header["outages"]:
        errors: list[float] = []
        gts: list[tuple[float, float]] = []
        for r in rows:
            if not (start_ms <= r.t_ms < end_ms):
                continue
            t = r.t_ms / 1000.0
            gt = interpolate_track(track, t)
            pose = pose_at(track, t)
            if gt is None or pose is None or has_gap(times, t, max_gap_s) or pose[3] < min_speed_mps:
                continue
            errors.append(haversine_m(gt[0], gt[1], r.lat, r.lon))
            gts.append(gt)
        distance = sum(haversine_m(*a, *b) for a, b in zip(gts, gts[1:]))
        final = errors[-1] if errors else float("nan")
        drift = final / distance * 100 if distance > 0 else float("nan")
        outages.append(OutageResult(start_ms, end_ms, len(errors), distance, final, drift))
        all_errors.extend(errors)
    arr = np.array(all_errors) if all_errors else np.array([float("nan")])
    return ReplayResult(
        visual=bool(header["visual"]),
        n_points=len(all_errors),
        p50_m=float(np.quantile(arr, 0.5, method="higher")),
        p95_m=float(np.quantile(arr, 0.95, method="higher")),
        outages=outages,
    )


def render_replay_report(r: ReplayResult, min_outage_dist_m: float = 200.0) -> str:
    lines = ["# Replay M2a", ""]
    if not r.has_data(min_outage_dist_m):
        if r.visual:
            lines += [
                f"Режим: визуальные фиксации. NFR-1: P50 ≤ 5 м, P95 ≤ 15 м — "
                f"⚠️ нет данных для проверки",
            ]
        else:
            lines += [
                f"Режим: счисление пути без визуальных фиксаций. NFR-5: дрейф ≤ 3 % пути — "
                f"⚠️ нет данных для проверки",
            ]
    elif r.visual:
        ok = r.p50_m <= 5.0 and r.p95_m <= 15.0
        lines += [
            f"Режим: визуальные фиксации. NFR-1: P50 ≤ 5 м, P95 ≤ 15 м — "
            f"P50 = {r.p50_m:.1f} м, P95 = {r.p95_m:.1f} м, точек {r.n_points} {'✅' if ok else '❌'}",
        ]
    else:
        long = [o for o in r.outages if o.distance_m >= min_outage_dist_m and not math.isnan(o.drift_pct)]
        worst = max((o.drift_pct for o in long), default=float("nan"))
        ok = worst <= 3.0
        lines += [
            f"Режим: счисление пути без визуальных фиксаций. NFR-5: дрейф ≤ 3 % пути — "
            f"худший {worst:.2f} % по {len(long)} пропаданиям ≥ {min_outage_dist_m:.0f} м {'✅' if ok else '❌'}",
        ]
    lines += [
        "",
        "| Пропадание, с | Точек | Путь, м | Ошибка в конце, м | Дрейф, % |",
        "|---|---|---|---|---|",
    ]
    for o in r.outages:
        lines.append(
            f"| {(o.end_ms - o.start_ms) / 1000:.0f} | {o.n_points} | {o.distance_m:.0f} "
            f"| {o.final_err_m:.1f} | {o.drift_pct:.2f} |"
        )
    return "\n".join(lines) + "\n"
