import json

import cv2
import numpy as np
import pytest
import torch

from vpr_bench import pipeline
from vpr_bench.dataset import Place, write_places
from vpr_bench.models import VprModel
from vpr_bench.pipeline import SETTINGS, embed_places, embed_places_cached, run_benchmark

COLORS = [(0, 0, 255), (0, 255, 0), (255, 0, 0)]
LATS = [55.7500, 55.7509, 55.7518]


def _make_set(root, name):
    places = []
    for i, color in enumerate(COLORS):
        rel = f"images/{i}.jpg"
        (root / "images").mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(root / rel), np.full((48, 64, 3), color, np.uint8))
        places.append(Place(rel, LATS[i], 37.6, 0.0))
    write_places(root / name, places)
    return root / name


def _make_set_n(root, name, n):
    places = []
    (root / "images").mkdir(parents=True, exist_ok=True)
    for i in range(n):
        rel = f"images/{i}.jpg"
        color = COLORS[i % len(COLORS)]
        cv2.imwrite(str(root / rel), np.full((48, 64, 3), color, np.uint8))
        places.append(Place(rel, 55.75 + i * 0.0001, 37.6, 0.0))
    write_places(root / name, places)
    return root / name


def _fake_loader(name, device):
    net = torch.nn.Sequential(torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten())
    return VprModel(name, net, (32, 32), device)


def test_run_benchmark_perfect_match(tmp_path):
    refs = _make_set(tmp_path / "refs", "refs.csv")
    queries = _make_set(tmp_path / "queries", "queries.csv")
    results, meta = run_benchmark(["fake"], refs, {"S1": queries}, loader=_fake_loader)
    assert [r.setting for r in results] == [s[0] for s in SETTINGS]
    assert all(r.recall[1] == 1.0 for r in results)
    assert all(r.session == "S1" for r in results)
    assert meta["fake"]["dim"] == 3
    assert meta["fake"]["ms_per_image"] > 0
    assert meta["fake"]["hub_ref"] == ""


def test_run_benchmark_sessions_and_pool(tmp_path, monkeypatch):
    refs = _make_set(tmp_path / "refs", "refs.csv")
    q1 = _make_set(tmp_path / "q1", "queries.csv")
    q2 = _make_set(tmp_path / "q2", "queries.csv")

    read_counts = {"n": 0}
    real_imread = cv2.imread

    def _counting_imread(path, *a, **kw):
        read_counts["n"] += 1
        return real_imread(path, *a, **kw)

    monkeypatch.setattr(cv2, "imread", _counting_imread)
    results, meta = run_benchmark(
        ["fake"], refs, {"S1": q1, "S2": q2}, pools=[["S1", "S2"]], loader=_fake_loader
    )

    sessions = {r.session for r in results}
    assert sessions == {"S1", "S2", "S1+S2"}
    for setting, _, _ in SETTINGS:
        settings_for = {r.session for r in results if r.setting == setting}
        assert settings_for == {"S1", "S2", "S1+S2"}
    # 3 refs + 3 queries (S1) + 3 queries (S2) images read, exactly once each
    # (refs embedded once regardless of number of sessions/pools).
    assert read_counts["n"] == 9


def test_run_benchmark_unknown_pool_session_raises(tmp_path):
    refs = _make_set(tmp_path / "refs", "refs.csv")
    q1 = _make_set(tmp_path / "q1", "queries.csv")
    with pytest.raises(ValueError):
        run_benchmark(["fake"], refs, {"S1": q1}, pools=[["S1", "S99"]], loader=_fake_loader)


def test_run_benchmark_ms_per_image_is_query_count_weighted_mean(tmp_path, monkeypatch):
    refs = _make_set(tmp_path / "refs", "refs.csv")
    n1, n2 = 3, 5
    ms1, ms2 = 10.0, 40.0
    q1 = _make_set_n(tmp_path / "q1", "queries.csv", n1)
    q2 = _make_set_n(tmp_path / "q2", "queries.csv", n2)

    def _fake_cached(model, places, csv_path, cache_dir):
        desc, real_ms = embed_places(model, places, csv_path.parent)
        if len(places) == n1:
            return desc, ms1
        if len(places) == n2:
            return desc, ms2
        return desc, real_ms  # refs pass-through, not part of this assertion

    monkeypatch.setattr(pipeline, "embed_places_cached", _fake_cached)

    _, meta = run_benchmark(["fake"], refs, {"S1": q1, "S2": q2}, loader=_fake_loader)

    expected = (ms1 * n1 + ms2 * n2) / (n1 + n2)
    assert meta["fake"]["ms_per_image"] == pytest.approx(expected)


def test_run_benchmark_calls_on_model_done_once_per_model(tmp_path):
    refs = _make_set(tmp_path / "refs", "refs.csv")
    q1 = _make_set(tmp_path / "q1", "queries.csv")
    calls = []

    def _on_done(results_so_far, meta_so_far):
        calls.append((len(results_so_far), set(meta_so_far)))

    run_benchmark(["fake"], refs, {"S1": q1}, loader=_fake_loader, on_model_done=_on_done)
    assert len(calls) == 1
    assert calls[0][1] == {"fake"}


def _fake_model():
    net = torch.nn.Sequential(torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten())
    return VprModel("fake", net, (32, 32), "cpu")


def test_embed_places_cached_hit_does_not_read_images(tmp_path, monkeypatch):
    from vpr_bench.dataset import read_places

    root = tmp_path / "refs"
    csv_path = _make_set(root, "refs.csv")
    places = read_places(csv_path)
    model = _fake_model()
    cache_dir = tmp_path / "desc"

    desc1, ms1 = embed_places_cached(model, places, csv_path, cache_dir)

    def _boom(*args, **kwargs):
        raise AssertionError("cv2.imread should not be called on cache hit")

    monkeypatch.setattr(cv2, "imread", _boom)
    desc2, ms2 = embed_places_cached(model, places, csv_path, cache_dir)

    assert np.array_equal(desc1, desc2)
    assert ms2 == ms1  # cached ms_per_image, not re-measured


def test_embed_places_cached_writes_cache_files(tmp_path):
    from vpr_bench.dataset import read_places

    root = tmp_path / "refs"
    csv_path = _make_set(root, "refs.csv")
    places = read_places(csv_path)
    model = _fake_model()
    cache_dir = tmp_path / "desc"

    embed_places_cached(model, places, csv_path, cache_dir)

    npy_files = list(cache_dir.glob("fake-*.npy"))
    json_files = list(cache_dir.glob("fake-*.json"))
    assert len(npy_files) == 1
    assert len(json_files) == 1
    meta = json.loads(json_files[0].read_text())
    assert meta["ms_per_image"] > 0
