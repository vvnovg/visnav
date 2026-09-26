"""Клиент Mapillary Graph API v4: список снимков в bbox и их загрузка."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import requests

from vpr_bench.geo import BBox

GRAPH_URL = "https://graph.mapillary.com/images"
FIELDS = "id,computed_geometry,computed_compass_angle,captured_at,is_pano,thumb_2048_url"
PAGE_LIMIT = 2000


@dataclass(frozen=True)
class RefImage:
    id: str
    lat: float
    lon: float
    heading: float
    captured_at: int  # unix-время, миллисекунды
    is_pano: bool
    url: str


def parse_image(item: dict) -> RefImage | None:
    geom = item.get("computed_geometry")
    url = item.get("thumb_2048_url")
    if not geom or not url:
        return None
    lon, lat = geom["coordinates"]
    return RefImage(
        id=str(item["id"]),
        lat=float(lat),
        lon=float(lon),
        heading=float(item.get("computed_compass_angle") or 0.0) % 360.0,
        captured_at=int(item.get("captured_at") or 0),
        is_pano=bool(item.get("is_pano", False)),
        url=url,
    )


class MapillaryClient:
    def __init__(self, token: str, session=None, tile_step_deg: float = 0.005, timeout_s: float = 30.0):
        self._token = token
        self._session = session or requests.Session()
        self.tile_step_deg = tile_step_deg
        self._timeout_s = timeout_s

    def list_images(self, bbox: BBox) -> list[RefImage]:
        seen: dict[str, RefImage] = {}
        for tile in bbox.tiles(self.tile_step_deg):
            resp = self._session.get(
                GRAPH_URL,
                params={
                    "fields": FIELDS,
                    "bbox": tile.as_param(),
                    "limit": PAGE_LIMIT,
                },
                timeout=self._timeout_s,
                headers={"Authorization": f"OAuth {self._token}"},
            )
            resp.raise_for_status()
            for item in resp.json().get("data", []):
                img = parse_image(item)
                if img is not None:
                    seen.setdefault(img.id, img)
        return list(seen.values())

    def download(self, img: RefImage, dest: Path) -> Path:
        resp = self._session.get(img.url, timeout=self._timeout_s)
        resp.raise_for_status()
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(resp.content)
        return dest
