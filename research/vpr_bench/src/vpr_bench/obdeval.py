"""Оценка записи OBD (M4b): частота и разрывы опроса, RTT, грубая оценка поправки спидометра k."""
from __future__ import annotations

import bisect
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

GAP_MS = 2_000.0        # интервал между событиями OBD длиннее — разрыв
FRESH_MS = 500.0        # OBD старше — фикс не сравнивается (как при обучении k в фильтре)
MIN_GPS_MPS = 5.0       # обучение k в фильтре — только от этой скорости
RATIO_MIN, RATIO_MAX = 0.80, 1.07  # отношение вне диапазона — выброс (как в фильтре)

Loc = tuple[float, float]           # (t_ms, spd м/с)
Obd = tuple[float, int, float]      # (t_ms, kmh, rtt_ms)


def read_sensors(path: Path) -> tuple[dict, list[Loc], list[Obd]]:
    """Читает из .sensors.jsonl только фиксы со скоростью (loc) и OBD (obd); остальные строки пропускает."""
    header: dict = {}
    locs: list[Loc] = []
    obds: list[Obd] = []
    for line in Path(path).read_text().splitlines():
        if not line.strip():
            continue
        o = json.loads(line)
        if "type" in o:
            header = o
            continue
        k = o.get("k")
        if k == "loc" and o.get("spd") is not None:
            locs.append((float(o["t"]), float(o["spd"])))
        elif k == "obd":
            obds.append((float(o["t"]), int(o["kmh"]), float(o["rtt_ms"])))
    locs.sort()
    obds.sort()
    return header, locs, obds


@dataclass(frozen=True)
class ObdResult:
    n_obd: int
    duration_s: float               # охват фиксов и событий OBD
    median_interval_ms: float | None = None
    n_gaps: int = 0                 # промежутки без OBD > 2 с (включая начало и конец записи)
    gap_s: float = 0.0
    no_obd_pct: float = 0.0
    rtt_p50_ms: float | None = None
    rtt_p95_ms: float | None = None
    rtt_max_ms: float | None = None
    n_ratio: int = 0                # фиксов, вошедших в оценку k
    n_outliers: int = 0             # отброшено как выброс
    k: float | None = None          # медиана v_gps / v_obd
    speedometer_pct: float | None = None  # спидометр завышает на (1/k − 1)·100 %
    resid_p50_mps: float | None = None    # |v_gps − k·v_obd| по свежим парам, выбросы включены
    resid_p95_mps: float | None = None


def _obd_at(t: float, times: list[float], obds: list[Obd]) -> float | None:
    """Скорость OBD (км/ч) в момент t: линейно между соседними событиями, оба не дальше FRESH_MS."""
    i = bisect.bisect_left(times, t)
    if i < len(times) and times[i] == t:
        return float(obds[i][1])
    if i == 0 or i == len(times):
        return None
    (t0, v0, _), (t1, v1, _) = obds[i - 1], obds[i]
    if t - t0 > FRESH_MS or t1 - t > FRESH_MS:
        return None
    return v0 + (v1 - v0) * (t - t0) / (t1 - t0)


def evaluate_obd(locs: list[Loc], obds: list[Obd]) -> ObdResult:
    all_t = [t for t, _ in locs] + [t for t, _, _ in obds]
    duration_s = (max(all_t) - min(all_t)) / 1000.0 if all_t else 0.0
    if not obds:
        return ObdResult(n_obd=0, duration_s=duration_s)
    times = [t for t, _, _ in obds]
    edges = np.diff([min(all_t), *times, max(all_t)])
    gaps = edges[edges > GAP_MS]
    gap_s = float(gaps.sum()) / 1000.0
    rtt = np.array([r for _, _, r in obds])

    ratios: list[float] = []
    pairs: list[tuple[float, float]] = []
    n_outliers = 0
    for t, spd in locs:
        if spd < MIN_GPS_MPS:
            continue
        kmh = _obd_at(t, times, obds)
        if kmh is None or kmh <= 0:
            continue
        v_obd = kmh / 3.6
        pairs.append((spd, v_obd))
        ratio = spd / v_obd
        if RATIO_MIN <= ratio <= RATIO_MAX:
            ratios.append(ratio)
        else:
            n_outliers += 1
    k = float(np.median(ratios)) if ratios else None
    resid = np.array([abs(g - k * o) for g, o in pairs]) if k is not None else np.array([])
    return ObdResult(
        n_obd=len(obds), duration_s=duration_s,
        median_interval_ms=float(np.median(np.diff(times))) if len(times) > 1 else None,
        n_gaps=len(gaps), gap_s=gap_s,
        no_obd_pct=gap_s / duration_s * 100.0 if duration_s > 0 else 0.0,
        rtt_p50_ms=float(np.percentile(rtt, 50)), rtt_p95_ms=float(np.percentile(rtt, 95)),
        rtt_max_ms=float(rtt.max()),
        n_ratio=len(ratios), n_outliers=n_outliers, k=k,
        speedometer_pct=(1.0 / k - 1.0) * 100.0 if k is not None else None,
        resid_p50_mps=float(np.percentile(resid, 50)) if resid.size else None,
        resid_p95_mps=float(np.percentile(resid, 95)) if resid.size else None,
    )


def _f(v: float | None, fmt: str) -> str:
    return "—" if v is None else format(v, fmt)


def render_obd_report(r: ObdResult) -> str:
    lines = ["# Оценка OBD (M4b)", ""]
    if r.n_obd == 0:
        lines += [f"Длительность записи {r.duration_s:.1f} с: нет данных OBD.", ""]
        return "\n".join(lines)
    freq = f"{1000.0 / r.median_interval_ms:.1f} Гц" if r.median_interval_ms else "—"
    lines += [
        "## Поток",
        "",
        f"- Длительность: {r.duration_s:.1f} с; событий OBD: {r.n_obd}",
        f"- Медиана интервала: {_f(r.median_interval_ms, '.0f')} мс ({freq})",
        f"- Разрывы > {GAP_MS / 1000:.0f} с: {r.n_gaps}, всего {r.gap_s:.1f} с; "
        f"доля времени без OBD {r.no_obd_pct:.1f} %",
        "",
        "## RTT",
        "",
        f"- p50 {r.rtt_p50_ms:.0f} мс, p95 {r.rtt_p95_ms:.0f} мс, max {r.rtt_max_ms:.0f} мс",
        "",
        "## Поправка спидометра",
        "",
        f"Фиксы со скоростью ≥ {MIN_GPS_MPS:.0f} м/с, OBD не старше {FRESH_MS:.0f} мс (интерполяция между "
        f"соседними событиями), kmh > 0; отношение вне [{RATIO_MIN:.2f}, {RATIO_MAX:.2f}] — выброс.",
        "",
        f"- Фиксов в оценке: {r.n_ratio}, выбросов: {r.n_outliers}",
    ]
    if r.k is None:
        lines.append("- k: нет пар GPS/OBD для оценки")
    else:
        lines += [
            f"- k = медиана v_gps / v_obd = {r.k:.3f}; спидометр {r.speedometer_pct:+.1f} %",
            f"- |v_gps − k·v_obd|: p50 {r.resid_p50_mps:.2f} м/с, p95 {r.resid_p95_mps:.2f} м/с",
        ]
    lines += [
        "",
        "Фильтр обучает k только при здоровье GNSS `GOOD`. Здесь здоровье неизвестно, поэтому это грубая оценка.",
        "",
    ]
    return "\n".join(lines)
