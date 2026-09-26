"""Геодезические утилиты: расстояния, азимуты, тайлинг bbox, интерполяция трека."""
from __future__ import annotations

import bisect
import math
from dataclasses import dataclass

import numpy as np

EARTH_RADIUS_M = 6_371_000.0
M_PER_DEG_LAT = math.pi * EARTH_RADIUS_M / 180.0  # согласовано с haversine_m


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def haversine_m_vec(lat: float, lon: float, lats: np.ndarray, lons: np.ndarray) -> np.ndarray:
    p1 = np.radians(lat)
    p2 = np.radians(np.asarray(lats, dtype=np.float64))
    dp = p2 - p1
    dl = np.radians(np.asarray(lons, dtype=np.float64) - lon)
    a = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * np.arcsin(np.sqrt(np.clip(a, 0.0, 1.0)))


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    x = math.sin(dl) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return math.degrees(math.atan2(x, y)) % 360.0


def offset_m(lat: float, lon: float, east_m: float, north_m: float) -> tuple[float, float]:
    dlat = north_m / M_PER_DEG_LAT
    dlon = east_m / (M_PER_DEG_LAT * math.cos(math.radians(lat)))
    return lat + dlat, lon + dlon


@dataclass(frozen=True)
class BBox:
    min_lon: float
    min_lat: float
    max_lon: float
    max_lat: float

    @classmethod
    def parse(cls, s: str) -> BBox:
        parts = [float(p) for p in s.split(",")]
        if len(parts) != 4:
            raise ValueError(f"bbox must be 'min_lon,min_lat,max_lon,max_lat', got {s!r}")
        b = cls(*parts)
        if b.min_lon >= b.max_lon or b.min_lat >= b.max_lat:
            raise ValueError(f"bbox min must be < max, got {s!r}")
        return b

    def tiles(self, step_deg: float) -> list[BBox]:
        out: list[BBox] = []
        lat = self.min_lat
        while lat < self.max_lat - 1e-12:
            top = min(lat + step_deg, self.max_lat)
            lon = self.min_lon
            while lon < self.max_lon - 1e-12:
                right = min(lon + step_deg, self.max_lon)
                out.append(BBox(lon, lat, right, top))
                lon = right
            lat = top
        return out

    def as_param(self) -> str:
        return f"{self.min_lon},{self.min_lat},{self.max_lon},{self.max_lat}"


@dataclass(frozen=True)
class TrackPoint:
    t: float  # unix-время, секунды
    lat: float
    lon: float


def interpolate_track(track: list[TrackPoint], t: float) -> tuple[float, float] | None:
    """Линейная интерполяция позиции; None вне временного диапазона трека."""
    if not track or t < track[0].t or t > track[-1].t:
        return None
    times = [p.t for p in track]
    i = bisect.bisect_left(times, t)
    if times[i] == t:
        return track[i].lat, track[i].lon
    a, b = track[i - 1], track[i]
    w = (t - a.t) / (b.t - a.t)
    return a.lat + w * (b.lat - a.lat), a.lon + w * (b.lon - a.lon)
