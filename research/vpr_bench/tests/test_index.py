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


def _random_index(n_refs=50, dim=16, seed=0):
    rng = np.random.default_rng(seed)
    descriptors = rng.normal(size=(n_refs, dim)).astype(np.float32)
    lats = LATS[0] + rng.normal(scale=0.001, size=n_refs)
    lons = LONS[0] + rng.normal(scale=0.001, size=n_refs)
    return GeoIndex(descriptors, lats, lons)


def test_search_batch_matches_search_without_prefilter():
    index = _random_index()
    rng = np.random.default_rng(1)
    queries = rng.normal(size=(10, 16)).astype(np.float32)
    expected = [index.search(q, k=5) for q in queries]
    got = index.search_batch(queries, k=5)
    assert len(got) == len(expected)
    for e, g in zip(expected, got):
        assert g.tolist() == e.tolist()


def test_search_batch_matches_search_with_prefilter():
    index = _random_index()
    rng = np.random.default_rng(2)
    queries = rng.normal(size=(6, 16)).astype(np.float32)
    centers = [(float(LATS[0] + rng.normal(scale=0.0005)), float(LONS[0])) for _ in range(6)]
    expected = [index.search(q, k=4, center=c, radius_m=150.0) for q, c in zip(queries, centers)]
    got = index.search_batch(queries, k=4, centers=centers, radius_m=150.0)
    assert len(got) == len(expected)
    for e, g in zip(expected, got):
        assert g.tolist() == e.tolist()


def test_search_batch_respects_chunk_size():
    index = _random_index()
    rng = np.random.default_rng(3)
    queries = rng.normal(size=(20, 16)).astype(np.float32)
    expected = [index.search(q, k=3) for q in queries]
    got = index.search_batch(queries, k=3, chunk=7)
    for e, g in zip(expected, got):
        assert g.tolist() == e.tolist()
