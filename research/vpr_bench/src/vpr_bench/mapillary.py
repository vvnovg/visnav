"""Клиент Mapillary Graph API v4: список снимков в bbox и их загрузка."""
from __future__ import annotations

import os
import time
import warnings
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

import requests

from vpr_bench.geo import BBox

GRAPH_URL = "https://graph.mapillary.com/images"
FIELDS = "id,computed_geometry,computed_compass_angle,captured_at,is_pano,thumb_2048_url"
PAGE_LIMIT = 2000
MIN_TILE_DEG = 0.000625


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
    if item.get("id") is None:
        return None
    geom = item.get("computed_geometry")
    url = item.get("thumb_2048_url")
    if not geom or not url:
        return None
    coords = geom.get("coordinates")
    if not isinstance(coords, (list, tuple)) or len(coords) != 2:
        return None
    lon, lat = coords
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
    def __init__(
        self,
        token: str,
        session=None,
        tile_step_deg: float = 0.005,
        timeout_s: float = 30.0,
        max_retries: int = 5,
        backoff_s: float = 1.0,
        sleep=time.sleep,
    ):
        self._token = token
        self._session = session or requests.Session()
        self.tile_step_deg = tile_step_deg
        self._timeout_s = timeout_s
        self._max_retries = max_retries
        self._backoff_s = backoff_s
        self._sleep = sleep

    def _get_with_retry(self, get_fn, *args, **kwargs):
        """Единая точка HTTP GET с повторами: 429/5xx/ConnectionError/Timeout."""
        attempt = 0
        while True:
            try:
                resp = get_fn(*args, **kwargs)
            except (requests.ConnectionError, requests.Timeout):
                if attempt >= self._max_retries:
                    raise
                self._sleep(self._backoff_s * 2**attempt)
                attempt += 1
                continue
            try:
                resp.raise_for_status()
            except requests.HTTPError:
                status = getattr(resp, "status_code", None)
                retryable = status == 429 or (status is not None and 500 <= status < 600)
                if not retryable or attempt >= self._max_retries:
                    raise
                wait = self._backoff_s * 2**attempt
                retry_after = getattr(resp, "headers", {}).get("Retry-After")
                if retry_after is not None:
                    try:
                        wait = float(retry_after)
                    except (TypeError, ValueError):
                        pass
                self._sleep(wait)
                attempt += 1
                continue
            return resp

    def list_images(
        self, bbox: BBox, tile_filter: Callable[[BBox], bool] | None = None
    ) -> list[RefImage]:
        seen: dict[str, RefImage] = {}
        skipped = 0
        for tile in bbox.tiles(self.tile_step_deg):
            if tile_filter is not None and not tile_filter(tile):
                continue
            skipped += self._fetch_tile(tile, seen, tile_filter)
        if skipped:
            warnings.warn(f"skipped {skipped} Mapillary items without usable geometry/url")
        return list(seen.values())

    def _fetch_tile(
        self,
        tile: BBox,
        seen: dict[str, RefImage],
        tile_filter: Callable[[BBox], bool] | None,
    ) -> int:
        resp = self._get_with_retry(
            self._session.get,
            GRAPH_URL,
            params={
                "fields": FIELDS,
                "bbox": tile.as_param(),
                "limit": PAGE_LIMIT,
            },
            timeout=self._timeout_s,
            headers={"Authorization": f"OAuth {self._token}"},
        )
        data = resp.json().get("data", [])
        if len(data) >= PAGE_LIMIT:
            lon_span = tile.max_lon - tile.min_lon
            lat_span = tile.max_lat - tile.min_lat
            if lon_span > MIN_TILE_DEG + 1e-12 or lat_span > MIN_TILE_DEG + 1e-12:
                mid_lon = (tile.min_lon + tile.max_lon) / 2
                mid_lat = (tile.min_lat + tile.max_lat) / 2
                quadrants = [
                    BBox(tile.min_lon, tile.min_lat, mid_lon, mid_lat),
                    BBox(mid_lon, tile.min_lat, tile.max_lon, mid_lat),
                    BBox(tile.min_lon, mid_lat, mid_lon, tile.max_lat),
                    BBox(mid_lon, mid_lat, tile.max_lon, tile.max_lat),
                ]
                skipped = 0
                for q in quadrants:
                    if tile_filter is not None and not tile_filter(q):
                        continue
                    skipped += self._fetch_tile(q, seen, tile_filter)
                return skipped
            warnings.warn(f"tile {tile.as_param()} truncated at {PAGE_LIMIT} images")
        skipped = 0
        for item in data:
            img = parse_image(item)
            if img is None:
                skipped += 1
            else:
                seen.setdefault(img.id, img)
        return skipped

    def download(self, img: RefImage, dest: Path) -> Path:
        resp = self._get_with_retry(self._session.get, img.url, timeout=self._timeout_s)
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_name(dest.name + ".part")
        tmp.write_bytes(resp.content)
        os.replace(tmp, dest)
        return dest
