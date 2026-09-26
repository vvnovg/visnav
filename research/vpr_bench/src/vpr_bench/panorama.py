"""Проекция equirectangular-панорамы 360° в перспективные виды с заданным курсом и FOV."""
from __future__ import annotations

import math

import cv2
import numpy as np


def equirect_to_perspective(
    pano: np.ndarray,
    yaw_deg: float,
    fov_deg: float,
    out_w: int,
    out_h: int,
    pitch_deg: float = 0.0,
) -> np.ndarray:
    h, w = pano.shape[:2]
    f = 0.5 * out_w / math.tan(math.radians(fov_deg) / 2)
    xs = np.arange(out_w, dtype=np.float64) - (out_w - 1) / 2
    ys = np.arange(out_h, dtype=np.float64) - (out_h - 1) / 2
    x, y = np.meshgrid(xs, ys)
    z = np.full_like(x, f)

    # Камера: x вправо, y вниз, z вперёд. Сначала наклон (вокруг x), затем курс (вокруг y).
    p, yw = math.radians(pitch_deg), math.radians(yaw_deg)
    y2 = y * math.cos(p) - z * math.sin(p)
    z2 = y * math.sin(p) + z * math.cos(p)
    x3 = x * math.cos(yw) + z2 * math.sin(yw)
    z3 = -x * math.sin(yw) + z2 * math.cos(yw)

    lon = np.arctan2(x3, z3)  # 0 = центр панорамы
    lat = np.arctan2(-y2, np.hypot(x3, z3))  # вверх положительно

    map_x = ((lon / (2 * math.pi) + 0.5) * w - 0.5).astype(np.float32)
    map_y = ((0.5 - lat / math.pi) * h - 0.5).astype(np.float32)
    return cv2.remap(pano, map_x, map_y, interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)


def perspective_views(
    pano: np.ndarray, n_views: int, fov_deg: float, out_w: int, out_h: int
) -> list[tuple[float, np.ndarray]]:
    step = 360.0 / n_views
    return [
        (i * step, equirect_to_perspective(pano, i * step, fov_deg, out_w, out_h))
        for i in range(n_views)
    ]
