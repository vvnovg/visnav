"""Прогон набора моделей по базе эталонов и кадрам-запросам."""
from __future__ import annotations

import time
from collections.abc import Callable
from pathlib import Path

import cv2
import numpy as np

from vpr_bench.dataset import Place, read_places
from vpr_bench.evaluate import EvalResult, evaluate
from vpr_bench.index import GeoIndex
from vpr_bench.models import VprModel, load_model

# (режим, радиус окна поиска, σ шума центра окна)
SETTINGS: list[tuple[str, float | None, float]] = [
    ("global", None, 0.0),
    ("prior-500m", 500.0, 100.0),
]


def embed_places(model: VprModel, places: list[Place], root: Path, batch_size: int = 16) -> tuple[np.ndarray, float]:
    chunks = []
    start = time.perf_counter()
    for i in range(0, len(places), batch_size):
        images = []
        for p in places[i : i + batch_size]:
            img = cv2.imread(str(root / p.path))
            if img is None:
                raise FileNotFoundError(root / p.path)
            images.append(img)
        chunks.append(model.embed(images))
    ms_per_image = (time.perf_counter() - start) * 1000 / max(1, len(places))
    return np.concatenate(chunks), ms_per_image


def run_benchmark(
    model_names: list[str],
    refs_csv: Path,
    queries_csv: Path,
    loader: Callable[[str, str], VprModel] = load_model,
    device: str = "cpu",
) -> tuple[list[EvalResult], dict[str, dict]]:
    refs = read_places(refs_csv)
    queries = read_places(queries_csv)
    q_lats = np.array([p.lat for p in queries])
    q_lons = np.array([p.lon for p in queries])
    results: list[EvalResult] = []
    meta: dict[str, dict] = {}
    for name in model_names:
        model = loader(name, device)
        ref_desc, _ = embed_places(model, refs, refs_csv.parent)
        q_desc, ms = embed_places(model, queries, queries_csv.parent)
        index = GeoIndex(ref_desc, [p.lat for p in refs], [p.lon for p in refs])
        meta[name] = {"dim": int(ref_desc.shape[1]), "size_mb": model.size_mb(), "ms_per_image": ms}
        for setting, radius, noise in SETTINGS:
            results.append(
                evaluate(index, q_desc, q_lats, q_lons, name, setting,
                         prior_radius_m=radius, prior_noise_m=noise)
            )
    return results, meta
