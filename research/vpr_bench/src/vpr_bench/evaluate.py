"""Метрики VPR: Recall@K с порогом расстояния и ошибка позиции top-1."""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from vpr_bench.geo import haversine_m_vec, offset_m
from vpr_bench.index import GeoIndex


@dataclass(frozen=True)
class EvalResult:
    model: str
    setting: str
    n_queries: int
    recall: dict[int, float]
    median_err_m: float
    p95_err_m: float


def evaluate(
    index: GeoIndex,
    q_desc: np.ndarray,
    q_lats: np.ndarray,
    q_lons: np.ndarray,
    model: str,
    setting: str,
    ks: tuple[int, ...] = (1, 5, 10),
    threshold_m: float = 25.0,
    prior_radius_m: float | None = None,
    prior_noise_m: float = 0.0,
    seed: int = 0,
) -> EvalResult:
    n = len(q_desc)
    if n == 0:
        raise ValueError("no queries to evaluate")
    rng = np.random.default_rng(seed)
    kmax = max(ks)
    hits = {k: 0 for k in ks}
    errors: list[float] = []
    for i in range(n):
        lat, lon = float(q_lats[i]), float(q_lons[i])
        center = None
        if prior_radius_m is not None:
            east, north = rng.normal(0.0, prior_noise_m, 2) if prior_noise_m > 0 else (0.0, 0.0)
            center = offset_m(lat, lon, east, north)
        top = index.search(q_desc[i], kmax, center=center, radius_m=prior_radius_m)
        if top.size == 0:
            errors.append(math.inf)
            continue
        dists = haversine_m_vec(lat, lon, index.lats[top], index.lons[top])
        errors.append(float(dists[0]))
        for k in ks:
            if np.any(dists[:k] <= threshold_m):
                hits[k] += 1
    errs = np.array(errors)
    return EvalResult(
        model=model,
        setting=setting,
        n_queries=n,
        recall={k: hits[k] / n for k in ks},
        median_err_m=float(np.median(errs)),
        p95_err_m=float(np.percentile(errs, 95)),
    )
