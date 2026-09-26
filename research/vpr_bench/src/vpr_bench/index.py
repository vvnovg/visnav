"""Поиск ближайших эталонов по дескриптору с опциональным гео-префильтром."""
from __future__ import annotations

import numpy as np

from vpr_bench.geo import haversine_m_vec


class GeoIndex:
    def __init__(self, descriptors: np.ndarray, lats: np.ndarray, lons: np.ndarray):
        self.descriptors = np.asarray(descriptors, dtype=np.float32)
        self.lats = np.asarray(lats, dtype=np.float64)
        self.lons = np.asarray(lons, dtype=np.float64)
        if not (len(self.descriptors) == len(self.lats) == len(self.lons)):
            raise ValueError("descriptors, lats and lons must have equal length")

    def search(
        self,
        query: np.ndarray,
        k: int,
        center: tuple[float, float] | None = None,
        radius_m: float | None = None,
    ) -> np.ndarray:
        candidates = np.arange(len(self.descriptors))
        if center is not None and radius_m is not None:
            dist = haversine_m_vec(center[0], center[1], self.lats, self.lons)
            candidates = candidates[dist <= radius_m]
        if candidates.size == 0:
            return candidates
        sims = self.descriptors[candidates] @ np.asarray(query, dtype=np.float32)
        k = min(k, candidates.size)
        top = np.argpartition(-sims, k - 1)[:k]
        top = top[np.argsort(-sims[top])]
        return candidates[top]
