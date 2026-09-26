"""Сборка базы эталонов: загрузка снимков, нарезка панорам на виды, запись refs.csv."""
from __future__ import annotations

import shutil
import warnings
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import cv2

from vpr_bench.dataset import Place, write_places
from vpr_bench.geo import BBox
from vpr_bench.mapillary import RefImage
from vpr_bench.panorama import perspective_views


class ImageSource(Protocol):
    def list_images(self, bbox: BBox) -> list[RefImage]: ...

    def download(self, img: RefImage, dest: Path) -> Path: ...


@dataclass(frozen=True)
class ViewConfig:
    n_views: int = 8
    fov_deg: float = 90.0
    width: int = 640
    height: int = 480


def build_reference_db(
    source: ImageSource, bbox: BBox, out_dir: Path, views: ViewConfig = ViewConfig()
) -> list[Place]:
    raw_dir = out_dir / "raw"
    (out_dir / "images").mkdir(parents=True, exist_ok=True)
    places: list[Place] = []
    for img in source.list_images(bbox):
        raw_path = raw_dir / f"{img.id}.jpg"
        if not raw_path.exists():
            source.download(img, raw_path)
        if img.is_pano:
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
            shutil.copyfile(raw_path, out_dir / rel)
            places.append(Place(rel, img.lat, img.lon, img.heading))
    write_places(out_dir / "refs.csv", places)
    return places
