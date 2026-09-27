"""Оценка журнала поездки с телефона (M1): визуальные фиксации против отфильтрованного GPS."""
from __future__ import annotations

import json
import warnings
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from vpr_bench.geo import TrackPoint, haversine_m, haversine_m_vec, interpolate_track
from vpr_bench.query import clean_track, has_gap, pose_at


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
    n_stationary: int
    coverage: float
    frac_within: float
    threshold_m: float
    median_err_m: float
    p95_err_m: float
    latency_ms: dict[str, dict[str, float]]
    gps_lag_ms: dict[str, float]


def read_log(path: Path) -> tuple[dict, list[FieldFrame]]:
    header: dict = {}
    frames: list[FieldFrame] = []
    lines = path.read_text().splitlines()
    last_nonblank = 0
    for i, line in enumerate(lines, start=1):
        if line.strip():
            last_nonblank = i
    for i, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            rec = json.loads(line)
        except json.JSONDecodeError as e:
            if i == last_nonblank:
                warnings.warn(f"{path}: skipping truncated final line {i}: {e}")
                continue
            raise ValueError(f"{path}: malformed log line {i}: {e}") from e
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


def _lag_stats(values: list[float]) -> dict[str, float]:
    if not values:
        return {"p50": float("nan"), "p95": float("nan"), "min": float("nan"), "max": float("nan")}
    arr = np.array(values)
    return {
        "p50": float(np.median(arr)),
        "p95": float(np.quantile(arr, 0.95, method="higher")),
        "min": float(arr.min()),
        "max": float(arr.max()),
    }


def evaluate_field(
    frames: list[FieldFrame],
    ref_lats: np.ndarray,
    ref_lons: np.ndarray,
    threshold_m: float = 20.0,
    cover_m: float = 25.0,
    max_gap_s: float = 3.0,
    max_speed_mps: float = 70.0,
    min_speed_mps: float = 2.0,
) -> list[FieldResult]:
    track, _ = clean_track(gps_track(frames), max_speed_mps=max_speed_mps, max_hdop=None)
    times = [p.t for p in track]
    modes = list(dict.fromkeys(fr.mode for fr in frames))
    results = []
    for mode in modes:
        mf = [fr for fr in frames if fr.mode == mode]
        n_gt = n_cov = n_ok = n_stationary = 0
        errors: list[float] = []
        for fr in mf:
            t = fr.t_ms / 1000.0
            gt = interpolate_track(track, t)
            if gt is None or has_gap(times, t, max_gap_s):
                continue
            pose = pose_at(track, t)
            if pose is None:
                continue
            if pose[3] < min_speed_mps:
                n_stationary += 1
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
        gps_lag = _lag_stats([fr.t_ms - fr.gps[3] for fr in mf if fr.gps is not None])
        results.append(FieldResult(
            mode=mode, n_frames=len(mf), n_with_gt=n_gt, n_covered=n_cov, n_stationary=n_stationary,
            coverage=n_cov / n_gt if n_gt else 0.0,
            frac_within=n_ok / n_cov if n_cov else 0.0,
            threshold_m=threshold_m,
            median_err_m=float(np.quantile(err_arr, 0.5, method="higher")),
            p95_err_m=float(np.quantile(err_arr, 0.95, method="higher")),
            latency_ms=lat,
            gps_lag_ms=gps_lag,
        ))
    return results


def gps_lag_warnings(results: list[FieldResult]) -> list[str]:
    """Строки-предупреждения для результатов, у которых медиана gps_lag_ms вне [0, 1500] мс."""
    out = []
    for r in results:
        p50 = r.gps_lag_ms["p50"]
        if not (0.0 <= p50 <= 1500.0):
            out.append(
                f"внимание: режим {r.mode} — медиана расхождения часов кадра и GPS "
                f"(gps_lag_ms p50) вне ожидаемого диапазона [0, 1500] мс: {p50:.0f} мс"
            )
    return out


def render_field_report(header: dict, results: list[FieldResult], target: float = 0.70) -> str:
    lines = [
        "# Полевой тест M1",
        "",
        f"Модель: {header.get('model', '—')}. Устройство: {header.get('device', '—')}.",
        f"Критерий M1: фиксация ≤ {results[0].threshold_m:g} м в ≥ {target * 100:.0f} % покрытых кадров "
        "в движении (≥ 2 м/с), режим gps."
        if results else "Нет кадров.",
        "",
    ]
    for warning in gps_lag_warnings(results):
        lines.append(f"> ⚠️ {warning}")
    if any(gps_lag_warnings(results)):
        lines.append("")
    lines += [
        "| Режим | Кадров | С GPS, в движении | Стоп | Покрытие, % | ≤ порога, % | Медиана, м | P95, м "
        "| Инференс p50/p95, мс | Всего p50/p95, мс | Лаг GPS p50/p95, мс | Критерий |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        verdict = ("✅" if r.frac_within >= target else "❌") if r.mode == "gps" else "—"
        inf, tot, lag = r.latency_ms["inf"], r.latency_ms["total"], r.gps_lag_ms
        lines.append(
            f"| {r.mode} | {r.n_frames} | {r.n_with_gt} | {r.n_stationary} | {r.coverage * 100:.1f} "
            f"| {r.frac_within * 100:.1f} "
            f"| {r.median_err_m:.1f} | {r.p95_err_m:.1f} | {inf['p50']:.0f}/{inf['p95']:.0f} "
            f"| {tot['p50']:.0f}/{tot['p95']:.0f} | {lag['p50']:.0f}/{lag['p95']:.0f} | {verdict} |"
        )
    return "\n".join(lines) + "\n"
