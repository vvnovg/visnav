"""Сборка базы эталонов: загрузка снимков, нарезка панорам на виды, запись refs.csv."""
from __future__ import annotations

import concurrent.futures
import json
import shutil
import warnings
from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Protocol

import cv2
import numpy as np

from vpr_bench.dataset import Place, write_places
from vpr_bench.geo import BBox, TrackPoint, haversine_m, haversine_m_vec, offset_m
from vpr_bench.mapillary import RefImage
from vpr_bench.panorama import perspective_views


class ImageSource(Protocol):
    def list_images(
        self, bbox: BBox, tile_filter: Callable[[BBox], bool] | None = None
    ) -> list[RefImage]: ...

    def download(self, img: RefImage, dest: Path) -> Path: ...


@dataclass(frozen=True)
class ViewConfig:
    n_views: int = 8
    fov_deg: float = 90.0
    width: int = 640
    height: int = 480


@dataclass
class Corridor:
    """Ограничивает выборку окрестностью (буфером) вокруг одного или нескольких треков."""

    tracks: list[list[TrackPoint]]
    buffer_m: float

    def __post_init__(self) -> None:
        points = [p for track in self.tracks for p in track]
        if not points:
            raise ValueError("Corridor requires at least one track point")
        self._lats = np.array([p.lat for p in points], dtype=np.float64)
        self._lons = np.array([p.lon for p in points], dtype=np.float64)

    def bbox(self) -> BBox:
        min_lat, max_lat = float(self._lats.min()), float(self._lats.max())
        min_lon, max_lon = float(self._lons.min()), float(self._lons.max())
        sw_lat, sw_lon = offset_m(min_lat, min_lon, -self.buffer_m, -self.buffer_m)
        ne_lat, ne_lon = offset_m(max_lat, max_lon, self.buffer_m, self.buffer_m)
        return BBox(sw_lon, sw_lat, ne_lon, ne_lat)

    def contains(self, lat: float, lon: float) -> bool:
        d = haversine_m_vec(lat, lon, self._lats, self._lons)
        return bool(d.min() <= self.buffer_m)

    def intersects(self, tile: BBox) -> bool:
        center_lat = (tile.min_lat + tile.max_lat) / 2
        center_lon = (tile.min_lon + tile.max_lon) / 2
        diag_m = haversine_m(tile.min_lat, tile.min_lon, tile.max_lat, tile.max_lon)
        d = haversine_m_vec(center_lat, center_lon, self._lats, self._lons)
        return bool(d.min() <= self.buffer_m + diag_m / 2)


def _iso_utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _iso_from_ms(ms: int) -> str:
    return _iso_utc(datetime.fromtimestamp(ms / 1000, tz=timezone.utc))


def _write_meta(
    out_dir: Path,
    images: list[RefImage],
    places: list[Place],
    bbox: BBox,
    corridor: Corridor | None,
    views: ViewConfig,
) -> None:
    captured = [img.captured_at for img in images if img.captured_at]
    meta = {
        "n_images": len(images),
        "n_panos": sum(1 for img in images if img.is_pano),
        "n_places": len(places),
        "captured_at_min": _iso_from_ms(min(captured)) if captured else None,
        "captured_at_max": _iso_from_ms(max(captured)) if captured else None,
        "bbox": bbox.as_param(),
        "corridor_buffer_m": corridor.buffer_m if corridor is not None else None,
        "views": asdict(views),
        "created_at": _iso_utc(datetime.now(timezone.utc)),
    }
    (out_dir / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2))


def build_reference_db(
    source: ImageSource,
    bbox: BBox,
    out_dir: Path,
    views: ViewConfig = ViewConfig(),
    corridor: Corridor | None = None,
    workers: int = 8,
) -> list[Place]:
    raw_dir = out_dir / "raw"
    (out_dir / "images").mkdir(parents=True, exist_ok=True)

    tile_filter = corridor.intersects if corridor is not None else None
    images = source.list_images(bbox, tile_filter=tile_filter)
    if corridor is not None:
        images = [img for img in images if corridor.contains(img.lat, img.lon)]

    raw_paths = {img.id: raw_dir / f"{img.id}.jpg" for img in images}
    to_download = [img for img in images if not raw_paths[img.id].exists()]

    if to_download:
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
            futures = [
                executor.submit(source.download, img, raw_paths[img.id]) for img in to_download
            ]
        error: Exception | None = None
        for fut in futures:
            try:
                fut.result()
            except Exception as exc:  # noqa: BLE001 - re-raised below, not swallowed
                if error is None:
                    error = exc
        if error is not None:
            raise error

    step = 360.0 / views.n_views if views.n_views else 0.0
    yaws = [i * step for i in range(views.n_views)]

    places: list[Place] = []
    for img in images:
        raw_path = raw_paths[img.id]
        if img.is_pano:
            rels = [f"images/{img.id}_{int(round(yaw)):03d}.jpg" for yaw in yaws]
            if rels and all((out_dir / rel).exists() for rel in rels):
                for yaw, rel in zip(yaws, rels):
                    places.append(Place(rel, img.lat, img.lon, (img.heading + yaw) % 360.0))
                continue
            frame = cv2.imread(str(raw_path))
            if frame is None:
                warnings.warn(f"skipping undecodable panorama: {raw_path}", stacklevel=2)
                continue
            for yaw, view in perspective_views(frame, views.n_views, views.fov_deg, views.width, views.height):
                rel = f"images/{img.id}_{int(round(yaw)):03d}.jpg"
                cv2.imwrite(str(out_dir / rel), view)
                places.append(Place(rel, img.lat, img.lon, (img.heading + yaw) % 360.0))
        else:
            rel = f"images/{img.id}.jpg"
            if not (out_dir / rel).exists():
                shutil.copyfile(raw_path, out_dir / rel)
            places.append(Place(rel, img.lat, img.lon, img.heading))
    write_places(out_dir / "refs.csv", places)
    _write_meta(out_dir, images, places, bbox, corridor, views)
    return places
