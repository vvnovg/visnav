import numpy as np
import pytest

from vpr_bench.evaluate import evaluate
from vpr_bench.geo import offset_m
from vpr_bench.index import GeoIndex

LATS = np.array([55.7500, 55.7509, 55.7518])
LONS = np.array([37.6, 37.6, 37.6])


def _setup():
    index = GeoIndex(np.eye(3, dtype=np.float32), LATS, LONS)
    q1 = np.array([0.0, 0.6, 0.8], dtype=np.float32)  # похож на эталон 2, стоит у эталона 1
    q_desc = np.stack([np.array([1.0, 0, 0], np.float32), q1])
    return index, q_desc, LATS[:2], LONS[:2]


def test_global_recall_and_errors():
    index, q_desc, q_lats, q_lons = _setup()
    r = evaluate(index, q_desc, q_lats, q_lons, "m", "global", ks=(1, 2))
    assert r.n_queries == 2
    assert r.n_covered == 2
    assert r.coverage == pytest.approx(1.0)
    assert r.recall == {1: 0.5, 2: 1.0}
    # method="higher": with errors [0, ~100.2] the 50th percentile picks the
    # higher of the two values (100.2), not their linear-interpolated average.
    assert r.median_err_m == pytest.approx(100.0, rel=0.02)


def test_prior_window_removes_wrong_candidate():
    index, q_desc, q_lats, q_lons = _setup()
    r = evaluate(index, q_desc, q_lats, q_lons, "m", "prior", ks=(1,), prior_radius_m=50, prior_noise_m=0.0)
    assert r.n_covered == 2
    assert r.coverage == pytest.approx(1.0)
    assert r.recall == {1: 1.0}
    assert r.p95_err_m == pytest.approx(0.0, abs=1e-6)


def test_empty_queries_raise():
    index, *_ = _setup()
    with pytest.raises(ValueError):
        evaluate(index, np.zeros((0, 3), np.float32), np.array([]), np.array([]), "m", "global")


def test_coverage_excludes_far_queries_from_recall():
    index, q_desc, q_lats, q_lons = _setup()
    far_lat, far_lon = offset_m(float(q_lats[0]), float(q_lons[0]), 0.0, 1000.0)
    q_lats2 = np.array([float(q_lats[0]), far_lat])
    q_lons2 = np.array([float(q_lons[0]), far_lon])
    q_desc2 = np.stack([q_desc[0], q_desc[0]])
    r = evaluate(index, q_desc2, q_lats2, q_lons2, "m", "global", ks=(1,))
    assert r.n_queries == 2
    assert r.n_covered == 1
    assert r.coverage == pytest.approx(0.5)
    # recall is computed over the single covered query, which is a hit
    assert r.recall == {1: 1.0}


def test_p95_and_median_are_inf_not_nan_when_mostly_uncovered():
    index = GeoIndex(np.eye(1, dtype=np.float32), np.array([55.75]), np.array([37.6]))
    q_desc = np.stack([np.array([1.0], np.float32)] * 4)
    q_lats = np.array([55.75, 55.76, 55.76, 55.76])
    q_lons = np.array([37.6, 37.6, 37.6, 37.6])
    r = evaluate(
        index, q_desc, q_lats, q_lons, "m", "prior", ks=(1,),
        prior_radius_m=10.0, prior_noise_m=0.0,
    )
    assert r.n_queries == 4
    assert r.median_err_m == float("inf")
    assert r.p95_err_m == float("inf")
    assert not np.isnan(r.median_err_m)
    assert not np.isnan(r.p95_err_m)
