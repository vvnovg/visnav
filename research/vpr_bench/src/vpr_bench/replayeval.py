"""Оценка replay-траектории (M2a) против отфильтрованного GPS той же поездки: NFR-1 и NFR-5."""
from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from vpr_bench.fieldlog import FieldFrame, gps_track
from vpr_bench.geo import haversine_m, interpolate_track
from vpr_bench.query import clean_track, has_gap, pose_at

# Владелец решил (2026-09-27): порог NFR-5 для вердикта поднят с 200 м до 1000 м пройденного пути за
# пропадание (короткие пропадания остаются в таблице как ориентировочные, но не входят в pass/fail).
VERDICT_MIN_OUTAGE_DIST_M = 1000.0
TABLE_MIN_OUTAGE_DIST_M = 200.0
VIS_STATES = ("ok", "gated", "below", "empty_window", "no_desc")


@dataclass(frozen=True)
class TrajRow:
    t_ms: int
    lat: float
    lon: float
    sigma_m: float
    outage: bool
    vis_state: str | None = None
    stationary: bool | None = None
    mode: str | None = None
    health: str | None = None
    reasons: tuple[str, ...] = ()
    injected: str | None = None
    way_id: int | None = None
    road_lat: float | None = None
    road_lon: float | None = None
    road_conf: float | None = None
    road_used: bool | None = None


@dataclass(frozen=True)
class OutageResult:
    start_ms: int
    end_ms: int
    n_points: int
    distance_m: float
    final_err_m: float
    drift_pct: float
    p95_second_half_m: float = float("nan")
    vis_counts: dict[str, int] = field(default_factory=dict)
    false_stationary_pct: float = float("nan")
    missed_stationary_pct: float = float("nan")


@dataclass(frozen=True)
class ReplayResult:
    visual: bool
    n_points: int
    p50_m: float
    p95_m: float
    outages: list[OutageResult]
    # Whole-trajectory diagnostics (not just within outage windows) — always computed and always
    # shown in the report, independent of has_data(): a trajectory can have "no data" for the NFR
    # verdict (e.g. no outages at all) while still being worth checking for a sane no_desc rate or
    # ZUPT behavior on the very first drive (see docs/research/m2a-replay.md "Первый прогон").
    vis_counts: dict[str, int] = field(default_factory=dict)  # only populated when visual
    false_stationary_pct: float = float("nan")
    missed_stationary_pct: float = float("nan")

    def has_data(self, min_outage_dist_m: float = TABLE_MIN_OUTAGE_DIST_M) -> bool:
        """Check if there is data to evaluate: visual mode needs n_points > 0;
        DR mode needs at least one outage with distance >= threshold and finite drift."""
        if self.visual:
            return self.n_points > 0
        else:
            return any(o.distance_m >= min_outage_dist_m and not math.isnan(o.drift_pct) for o in self.outages)

    def _qualifying_outages(self, min_dist_m: float) -> list[OutageResult]:
        return [o for o in self.outages if o.distance_m >= min_dist_m and not math.isnan(o.drift_pct)]

    def worst_drift_pct(self, min_dist_m: float = VERDICT_MIN_OUTAGE_DIST_M) -> float:
        q = self._qualifying_outages(min_dist_m)
        return max((o.drift_pct for o in q), default=float("nan"))

    def weighted_drift_pct(self, min_dist_m: float = VERDICT_MIN_OUTAGE_DIST_M) -> float:
        """Σ final_err / Σ distance × 100 over outages >= min_dist_m — a single-number NFR-5 view
        weighted by how much distance each outage actually covered, unlike the per-outage worst case."""
        q = self._qualifying_outages(min_dist_m)
        total_dist = sum(o.distance_m for o in q)
        if total_dist <= 0:
            return float("nan")
        return sum(o.final_err_m for o in q) / total_dist * 100


def _vis_state_line(counts: dict[str, int], prefix: str = "визуальные фиксации") -> str:
    n = sum(counts.get(s, 0) for s in VIS_STATES)
    if n == 0:
        return f"{prefix}: нет данных"
    parts = ", ".join(f"{s} {counts.get(s, 0) / n * 100:.0f} %" for s in VIS_STATES)
    return f"{prefix}: {parts}"


def read_trajectory(path: Path, keep_null_pos: bool = False,
                    allow_fusion: bool = False) -> tuple[dict, list[TrajRow]]:
    """keep_null_pos=True keeps rows with null lat/lon (as NaN) for health/mode metrics; replay-eval skips them.
    allow_fusion=True also accepts the phone's .fusion.jsonl (header type "fusion", same row format)."""
    lines = [l for l in path.read_text().splitlines() if l.strip()]
    if not lines:
        raise ValueError(f"{path}: empty trajectory file")
    header = json.loads(lines[0])
    if header.get("type") != "replay" and not (allow_fusion and header.get("type") == "fusion"):
        raise ValueError(f"{path}: not a replay trajectory")
    rows = []
    for r in map(json.loads, lines[1:]):
        # Skip rows with null lat or lon
        if r["lat"] is None or r["lon"] is None:
            if not keep_null_pos:
                continue
            r = {**r, "lat": float("nan"), "lon": float("nan")}
        # Treat null sigma_m as NaN
        sigma_m = r["sigma_m"] if r["sigma_m"] is not None else float("nan")
        rows.append(TrajRow(
            int(r["t_ms"]), r["lat"], r["lon"], sigma_m, bool(r["outage"]),
            r.get("vis_state"), r.get("stationary"),
            r.get("mode"), r.get("health"), tuple(r.get("reasons") or ()), r.get("injected"),
            way_id=r.get("way_id"), road_lat=r.get("road_lat"), road_lon=r.get("road_lon"),
            road_conf=r.get("road_conf"), road_used=r.get("road_used"),
        ))
    # Sort rows by t_ms
    rows.sort(key=lambda row: row.t_ms)
    return header, rows


def _zupt_stats(
    rows: list[TrajRow],
    track,
    times: list[float],
    max_gap_s: float,
    min_speed_mps: float,
) -> tuple[float, float]:
    """Ложная стоянка: доля движущихся (GT >= min_speed_mps) строк с stationary=true.
    Пропущенная стоянка: доля строк с GT < 0.5 м/с и stationary=false. Строки без поля
    `stationary` (старые траектории) пропускаются из обеих статистик. `rows` может быть как всей
    траекторией, так и подмножеством (например, только строки одного пропадания) — вызывающий код
    решает, над каким подмножением считать."""
    n_moving = n_moving_false_stationary = 0
    n_slow = n_slow_missed = 0
    for r in rows:
        if r.stationary is None:
            continue
        t = r.t_ms / 1000.0
        gt = interpolate_track(track, t)
        pose = pose_at(track, t)
        if gt is None or pose is None or has_gap(times, t, max_gap_s):
            continue
        speed = pose[3]
        if speed >= min_speed_mps:
            n_moving += 1
            if r.stationary:
                n_moving_false_stationary += 1
        if speed < 0.5:
            n_slow += 1
            if not r.stationary:
                n_slow_missed += 1
    false_pct = n_moving_false_stationary / n_moving * 100 if n_moving else float("nan")
    missed_pct = n_slow_missed / n_slow * 100 if n_slow else float("nan")
    return false_pct, missed_pct


def evaluate_replay(
    header: dict,
    rows: list[TrajRow],
    log_frames: list[FieldFrame],
    min_speed_mps: float = 2.0,
    max_gap_s: float = 3.0,
    min_outage_dist_m: float = TABLE_MIN_OUTAGE_DIST_M,
) -> ReplayResult:
    visual = bool(header["visual"])
    track, _ = clean_track(gps_track(log_frames), max_speed_mps=70.0, max_hdop=None)
    times = [p.t for p in track]
    all_errors: list[float] = []
    outages: list[OutageResult] = []
    for start_ms, end_ms in header["outages"]:
        mid_ms = (start_ms + end_ms) / 2.0
        rows_in_outage = [r for r in rows if start_ms <= r.t_ms < end_ms]
        errors: list[float] = []
        errors_second_half: list[float] = []
        gts: list[tuple[float, float]] = []
        for r in rows_in_outage:
            t = r.t_ms / 1000.0
            gt = interpolate_track(track, t)
            pose = pose_at(track, t)
            if gt is None or pose is None or has_gap(times, t, max_gap_s) or pose[3] < min_speed_mps:
                continue
            err = haversine_m(gt[0], gt[1], r.lat, r.lon)
            errors.append(err)
            gts.append(gt)
            if r.t_ms >= mid_ms:
                errors_second_half.append(err)
        distance = sum(haversine_m(*a, *b) for a, b in zip(gts, gts[1:]))
        final = errors[-1] if errors else float("nan")
        drift = final / distance * 100 if distance > 0 else float("nan")
        p95_second_half = (
            float(np.quantile(np.array(errors_second_half), 0.95, method="higher"))
            if errors_second_half else float("nan")
        )
        vis_counts = {s: 0 for s in VIS_STATES}
        if visual:
            for r in rows_in_outage:
                if r.vis_state in vis_counts:
                    vis_counts[r.vis_state] += 1
        false_pct, missed_pct = _zupt_stats(rows_in_outage, track, times, max_gap_s, min_speed_mps)
        outages.append(OutageResult(
            start_ms, end_ms, len(errors), distance, final, drift,
            p95_second_half_m=p95_second_half, vis_counts=vis_counts,
            false_stationary_pct=false_pct, missed_stationary_pct=missed_pct,
        ))
        all_errors.extend(errors)
    arr = np.array(all_errors) if all_errors else np.array([float("nan")])

    # Whole-trajectory diagnostics — over every row, not just those inside an outage window (and
    # regardless of whether there are any outages at all).
    whole_vis_counts = {s: 0 for s in VIS_STATES}
    if visual:
        for r in rows:
            if r.vis_state in whole_vis_counts:
                whole_vis_counts[r.vis_state] += 1
    whole_false_pct, whole_missed_pct = _zupt_stats(rows, track, times, max_gap_s, min_speed_mps)

    return ReplayResult(
        visual=visual,
        n_points=len(all_errors),
        p50_m=float(np.quantile(arr, 0.5, method="higher")),
        p95_m=float(np.quantile(arr, 0.95, method="higher")),
        outages=outages,
        vis_counts=whole_vis_counts if visual else {},
        false_stationary_pct=whole_false_pct,
        missed_stationary_pct=whole_missed_pct,
    )


def render_replay_report(r: ReplayResult, min_outage_dist_m: float = TABLE_MIN_OUTAGE_DIST_M) -> str:
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
        long = r._qualifying_outages(VERDICT_MIN_OUTAGE_DIST_M)
        if not long:
            lines += [
                f"Режим: счисление пути без визуальных фиксаций. NFR-5: дрейф ≤ 3 % пути — "
                f"⚠️ нет пропаданий ≥ {VERDICT_MIN_OUTAGE_DIST_M:.0f} м для вердикта (короче — только ориентировочные)",
            ]
        else:
            worst = r.worst_drift_pct()
            weighted = r.weighted_drift_pct()
            ok = worst <= 3.0
            lines += [
                f"Режим: счисление пути без визуальных фиксаций. NFR-5: дрейф ≤ 3 % пути — "
                f"худший {worst:.2f} %, взвешенный {weighted:.2f} % по {len(long)} пропаданиям "
                f"≥ {VERDICT_MIN_OUTAGE_DIST_M:.0f} м {'✅' if ok else '❌'}",
            ]
    # Whole-trajectory diagnostics — always shown, independent of has_data() above: useful even when
    # there's no NFR verdict yet (e.g. no outages replayed), see docs/research/m2a-replay.md.
    lines.append("")
    if r.visual:
        lines.append(_vis_state_line(r.vis_counts))
    lines.append(
        f"ZUPT по всей траектории: ложная стоянка {r.false_stationary_pct:.1f} %, "
        f"пропущенная стоянка {r.missed_stationary_pct:.1f} %"
    )
    lines += [
        "",
        "| Пропадание, с | Точек | Путь, м | Ошибка в конце, м | Дрейф, % | P95 2-я половина, м "
        "| Ложная стоянка, % | Пропущенная стоянка, % |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for o in r.outages:
        if o.distance_m < min_outage_dist_m:
            continue
        indicative = " (ориентир.)" if o.distance_m < VERDICT_MIN_OUTAGE_DIST_M else ""
        lines.append(
            f"| {(o.end_ms - o.start_ms) / 1000:.0f} | {o.n_points} | {o.distance_m:.0f}{indicative} "
            f"| {o.final_err_m:.1f} | {o.drift_pct:.2f} | {o.p95_second_half_m:.1f} "
            f"| {o.false_stationary_pct:.1f} | {o.missed_stationary_pct:.1f} |"
        )
    if r.visual:
        shown = [o for o in r.outages if o.distance_m >= min_outage_dist_m]
        if shown:
            lines += ["", "Визуальные фиксации по пропаданиям:"]
            for i, o in enumerate(shown, start=1):
                lines.append(f"- Пропадание {i} ({(o.end_ms - o.start_ms) / 1000:.0f} с): {_vis_state_line(o.vis_counts)}")
    return "\n".join(lines) + "\n"
