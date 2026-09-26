import numpy as np
import pytest

from vpr_bench.evaluate import evaluate
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
    assert r.recall == {1: 0.5, 2: 1.0}
    assert r.median_err_m == pytest.approx(50.0, rel=0.02)


def test_prior_window_removes_wrong_candidate():
    index, q_desc, q_lats, q_lons = _setup()
    r = evaluate(index, q_desc, q_lats, q_lons, "m", "prior", ks=(1,), prior_radius_m=50, prior_noise_m=0.0)
    assert r.recall == {1: 1.0}
    assert r.p95_err_m == pytest.approx(0.0, abs=1e-6)


def test_empty_queries_raise():
    index, *_ = _setup()
    with pytest.raises(ValueError):
        evaluate(index, np.zeros((0, 3), np.float32), np.array([]), np.array([]), "m", "global")
