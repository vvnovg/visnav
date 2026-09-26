"""Прогон набора моделей по базе эталонов и кадрам-запросам."""
from __future__ import annotations

import hashlib
import json
import time
from collections.abc import Callable
from pathlib import Path

import cv2
import numpy as np

from vpr_bench.dataset import Place, read_places
from vpr_bench.evaluate import EvalResult, evaluate
from vpr_bench.index import GeoIndex
from vpr_bench.models import MODEL_SPECS, VprModel, load_model

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


def embed_places_cached(
    model: VprModel, places: list[Place], csv_path: Path, cache_dir: Path
) -> tuple[np.ndarray, float]:
    key = hashlib.sha1(csv_path.read_bytes()).hexdigest()[:12]
    npy_path = cache_dir / f"{model.name}-{key}.npy"
    json_path = cache_dir / f"{model.name}-{key}.json"
    if npy_path.exists() and json_path.exists():
        desc = np.load(npy_path)
        meta = json.loads(json_path.read_text())
        return desc, meta["ms_per_image"]
    desc, ms_per_image = embed_places(model, places, csv_path.parent)
    cache_dir.mkdir(parents=True, exist_ok=True)
    np.save(npy_path, desc)
    json_path.write_text(json.dumps({"ms_per_image": ms_per_image}))
    return desc, ms_per_image


def _evaluate_all_settings(
    index: GeoIndex,
    desc: np.ndarray,
    lats: np.ndarray,
    lons: np.ndarray,
    name: str,
    session: str,
) -> list[EvalResult]:
    return [
        evaluate(
            index, desc, lats, lons, name, setting,
            prior_radius_m=radius, prior_noise_m=noise, session=session,
        )
        for setting, radius, noise in SETTINGS
    ]


def run_benchmark(
    model_names: list[str],
    refs_csv: Path,
    queries: dict[str, Path],
    pools: list[list[str]] | None = None,
    loader: Callable[[str, str], VprModel] = load_model,
    device: str = "cpu",
    on_model_done: Callable[[list[EvalResult], dict[str, dict]], None] | None = None,
) -> tuple[list[EvalResult], dict[str, dict]]:
    for pool in pools or []:
        for session in pool:
            if session not in queries:
                raise ValueError(f"unknown session {session!r} in pool; known sessions: {sorted(queries)}")

    refs = read_places(refs_csv)
    session_places = {session: read_places(path) for session, path in queries.items()}
    refs_cache_dir = refs_csv.parent / "desc"

    results: list[EvalResult] = []
    meta: dict[str, dict] = {}
    for name in model_names:
        model = loader(name, device)
        ref_desc, _ = embed_places_cached(model, refs, refs_csv, refs_cache_dir)
        index = GeoIndex(ref_desc, [p.lat for p in refs], [p.lon for p in refs])

        session_desc: dict[str, np.ndarray] = {}
        session_lats: dict[str, np.ndarray] = {}
        session_lons: dict[str, np.ndarray] = {}
        ms_weighted_sum = 0.0
        n_total = 0
        for session, places in session_places.items():
            csv_path = queries[session]
            cache_dir = csv_path.parent / "desc"
            desc, ms = embed_places_cached(model, places, csv_path, cache_dir)
            session_desc[session] = desc
            session_lats[session] = np.array([p.lat for p in places])
            session_lons[session] = np.array([p.lon for p in places])
            ms_weighted_sum += ms * len(places)
            n_total += len(places)
        ms_per_image = ms_weighted_sum / n_total if n_total else 0.0

        hub_ref = MODEL_SPECS[name].repo if name in MODEL_SPECS else ""
        meta[name] = {
            "dim": int(ref_desc.shape[1]), "size_mb": model.size_mb(),
            "ms_per_image": ms_per_image, "hub_ref": hub_ref,
        }

        for session in session_places:
            results.extend(
                _evaluate_all_settings(
                    index, session_desc[session], session_lats[session], session_lons[session], name, session
                )
            )

        for pool in pools or []:
            pool_name = "+".join(pool)
            pool_desc = np.concatenate([session_desc[s] for s in pool])
            pool_lats = np.concatenate([session_lats[s] for s in pool])
            pool_lons = np.concatenate([session_lons[s] for s in pool])
            results.extend(_evaluate_all_settings(index, pool_desc, pool_lats, pool_lons, name, pool_name))

        if on_model_done is not None:
            on_model_done(results, meta)

    return results, meta
