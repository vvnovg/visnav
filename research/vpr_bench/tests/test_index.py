import numpy as np
import pytest

from vpr_bench.index import GeoIndex

LATS = np.array([55.7500, 55.7509, 55.7518])  # шаг ≈ 100 м
LONS = np.array([37.6, 37.6, 37.6])


def _index():
    return GeoIndex(np.eye(3, dtype=np.float32), LATS, LONS)


def test_search_orders_by_similarity():
    q = np.array([0.0, 0.6, 0.8], dtype=np.float32)
    assert _index().search(q, k=2).tolist() == [2, 1]


def test_search_respects_geo_prefilter():
    q = np.array([0.0, 0.0, 1.0], dtype=np.float32)
    got = _index().search(q, k=3, center=(LATS[0], LONS[0]), radius_m=50)
    assert got.tolist() == [0]


def test_search_empty_when_nothing_in_radius():
    got = _index().search(np.ones(3, np.float32), k=3, center=(0.0, 0.0), radius_m=10)
    assert got.size == 0


def test_mismatched_lengths_raise():
    with pytest.raises(ValueError):
        GeoIndex(np.eye(3, dtype=np.float32), LATS[:2], LONS)
