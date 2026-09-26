import json

import cv2
import numpy as np
import torch

from vpr_bench.dataset import Place, write_places
from vpr_bench.models import VprModel
from vpr_bench.pipeline import SETTINGS, embed_places_cached, run_benchmark

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


def _fake_loader(name, device):
    net = torch.nn.Sequential(torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten())
    return VprModel(name, net, (32, 32), device)


def test_run_benchmark_perfect_match(tmp_path):
    refs = _make_set(tmp_path / "refs", "refs.csv")
    queries = _make_set(tmp_path / "queries", "queries.csv")
    results, meta = run_benchmark(["fake"], refs, queries, loader=_fake_loader)
    assert [r.setting for r in results] == [s[0] for s in SETTINGS]
    assert all(r.recall[1] == 1.0 for r in results)
    assert meta["fake"]["dim"] == 3
    assert meta["fake"]["ms_per_image"] > 0


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
