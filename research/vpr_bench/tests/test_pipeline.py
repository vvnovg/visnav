import cv2
import numpy as np
import torch

from vpr_bench.dataset import Place, write_places
from vpr_bench.models import VprModel
from vpr_bench.pipeline import SETTINGS, run_benchmark

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
