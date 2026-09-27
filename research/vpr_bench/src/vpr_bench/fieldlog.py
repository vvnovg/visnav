"""Оценка журнала поездки с телефона (M1): визуальные фиксации против отфильтрованного GPS."""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from vpr_bench.geo import TrackPoint, haversine_m, haversine_m_vec, interpolate_track
from vpr_bench.query import clean_track, has_gap


@dataclass(frozen=True)
class FieldFrame:
    t_ms: int
    mode: str
    gps: tuple[float, float, float, int] | None
    fix: tuple[float, float, float] | None
    lat_ms: dict[str, float]


@dataclass(frozen=True)
class FieldResult:
    mode: str
    n_frames: int
    n_with_gt: int
    n_covered: int
    coverage: float
    frac_within: float
    threshold_m: float
    median_err_m: float
    p95_err_m: float
    latency_ms: dict[str, dict[str, float]]


def read_log(path: Path) -> tuple[dict, list[FieldFrame]]:
    header: dict = {}
    frames: list[FieldFrame] = []
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        rec = json.loads(line)
        if rec.get("type") == "session":
            header = rec
            continue
        g, f = rec.get("gps"), rec.get("fix")
        frames.append(FieldFrame(
            t_ms=int(rec["t_ms"]),
            mode=rec["mode"],
            gps=(g["lat"], g["lon"], g["acc_m"], int(g["t_ms"])) if g else None,
            fix=(f["lat"], f["lon"], f["sim"]) if f else None,
            lat_ms=dict(rec["lat_ms"]),
        ))
    return header, frames


def gps_track(frames: list[FieldFrame], max_acc_m: float = 20.0) -> list[TrackPoint]:
    seen: dict[int, TrackPoint] = {}
    for fr in frames:
        if fr.gps is None:
            continue
        lat, lon, acc, t_ms = fr.gps
        if acc <= max_acc_m:
            seen.setdefault(t_ms, TrackPoint(t_ms / 1000.0, lat, lon))
    return sorted(seen.values(), key=lambda p: p.t)


def _quantiles(values: list[float]) -> dict[str, float]:
    if not values:
        return {"p50": float("nan"), "p95": float("nan")}
    arr = np.array(values)
    return {"p50": float(np.median(arr)), "p95": float(np.quantile(arr, 0.95, method="higher"))}


def evaluate_field(
    frames: list[FieldFrame],
    ref_lats: np.ndarray,
    ref_lons: np.ndarray,
    threshold_m: float = 20.0,
    cover_m: float = 25.0,
    max_gap_s: float = 3.0,
    max_speed_mps: float = 70.0,
) -> list[FieldResult]:
    track, _ = clean_track(gps_track(frames), max_speed_mps=max_speed_mps, max_hdop=None)
    times = [p.t for p in track]
    modes = list(dict.fromkeys(fr.mode for fr in frames))
    results = []
    for mode in modes:
        mf = [fr for fr in frames if fr.mode == mode]
        n_gt = n_cov = n_ok = 0
        errors: list[float] = []
        for fr in mf:
            t = fr.t_ms / 1000.0
            gt = interpolate_track(track, t)
            if gt is None or has_gap(times, t, max_gap_s):
                continue
            n_gt += 1
            if len(ref_lats) == 0 or haversine_m_vec(gt[0], gt[1], ref_lats, ref_lons).min() > cover_m:
                continue
            n_cov += 1
            err = haversine_m(gt[0], gt[1], fr.fix[0], fr.fix[1]) if fr.fix else float("inf")
            errors.append(err)
            n_ok += err <= threshold_m
        err_arr = np.array(errors) if errors else np.array([float("nan")])
        lat = {k: _quantiles([fr.lat_ms[k] for fr in mf]) for k in ("pre", "inf", "search")}
        lat["total"] = _quantiles([sum(fr.lat_ms.values()) for fr in mf])
        results.append(FieldResult(
            mode=mode, n_frames=len(mf), n_with_gt=n_gt, n_covered=n_cov,
            coverage=n_cov / n_gt if n_gt else 0.0,
            frac_within=n_ok / n_cov if n_cov else 0.0,
            threshold_m=threshold_m,
            median_err_m=float(np.quantile(err_arr, 0.5, method="higher")),
            p95_err_m=float(np.quantile(err_arr, 0.95, method="higher")),
            latency_ms=lat,
        ))
    return results


def render_field_report(header: dict, results: list[FieldResult], target: float = 0.70) -> str:
    lines = [
        "# Полевой тест M1",
        "",
        f"Модель: {header.get('model', '—')}. Устройство: {header.get('device', '—')}.",
        f"Критерий M1: фиксация ≤ {results[0].threshold_m:g} м в ≥ {target * 100:.0f} % покрытых кадров, режим gps."
        if results else "Нет кадров.",
        "",
        "| Режим | Кадров | С GPS | Покрытие, % | ≤ порога, % | Медиана, м | P95, м "
        "| Инференс p50/p95, мс | Всего p50/p95, мс | Критерий |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        verdict = ("✅" if r.frac_within >= target else "❌") if r.mode == "gps" else "—"
        inf, tot = r.latency_ms["inf"], r.latency_ms["total"]
        lines.append(
            f"| {r.mode} | {r.n_frames} | {r.n_with_gt} | {r.coverage * 100:.1f} | {r.frac_within * 100:.1f} "
            f"| {r.median_err_m:.1f} | {r.p95_err_m:.1f} | {inf['p50']:.0f}/{inf['p95']:.0f} "
            f"| {tot['p50']:.0f}/{tot['p95']:.0f} | {verdict} |"
        )
    return "\n".join(lines) + "\n"
